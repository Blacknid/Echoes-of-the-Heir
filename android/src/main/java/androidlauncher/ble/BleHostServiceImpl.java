package androidlauncher.ble;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;

import platform.BleHostService;

/**
 * Android implementation of {@link BleHostService}: runs a {@link BluetoothGattServer}
 * (peripheral/server role) with one custom service exposing a guest-write characteristic and a
 * host-notify characteristic, capped at {@link main.BleMultiplayerSession#MAX_GUESTS} connected
 * devices — see BleProtocol for the exact UUIDs and main.BleMultiplayerSession for the line
 * protocol carried over these characteristics.
 *
 * <p>Guest slots are assigned 1..MAX_GUESTS in connection order and freed on disconnect, so a
 * reconnecting guest gets a fresh slot rather than reusing a stale one mid-session.
 */
public class BleHostServiceImpl implements BleHostService {

    private final Activity activity;
    private BluetoothGattServer gattServer;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattCharacteristic notifyChar;
    private int maxGuests;
    private BiConsumer<Integer, String> onMessage;
    private IntConsumer onGuestLeft;

    private final Map<BluetoothDevice, Integer> deviceToSlot = new ConcurrentHashMap<>();
    private final Map<Integer, BluetoothDevice> slotToDevice = new LinkedHashMap<>();
    private final StringBuilder[] recvBuffers = new StringBuilder[6]; // slots 1..5, index 0 unused

    public BleHostServiceImpl(Activity activity) {
        this.activity = activity;
    }

    @Override
    public boolean isSupported() {
        if (!BlePermissions.hasAll(activity)) return false;
        BluetoothManager bm = (BluetoothManager) activity.getSystemService(Activity.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
        return adapter != null && adapter.isEnabled() && adapter.isMultipleAdvertisementSupported();
    }

    @Override
    public String start(int maxGuests, BiConsumer<Integer, String> onMessage, IntConsumer onGuestLeft) {
        if (!BlePermissions.hasAll(activity)) {
            BlePermissions.requestAll(activity);
            return null;
        }
        BluetoothManager bm = (BluetoothManager) activity.getSystemService(Activity.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
        if (adapter == null || !adapter.isEnabled()) return null;

        // gattCallback's methods (below) run on Android's Binder callback thread, not libGDX's
        // GL/render thread — but onMessage/onGuestLeft flow straight into
        // main.BleMultiplayerSession's game-state mutation (remotePlayers, gp.ui.addMessage),
        // which the render thread reads every frame with no synchronization. Wrapping once here
        // means every gattCallback call site is automatically safe. See
        // BleGuestServiceImpl.connect's matching fix for the guest side and the crash/corruption
        // this avoids (confirmed on real hardware: garbled map/title-screen rendering without it).
        this.maxGuests = maxGuests;
        this.onMessage = (slot, line) -> com.badlogic.gdx.Gdx.app.postRunnable(() -> onMessage.accept(slot, line));
        this.onGuestLeft = slot -> com.badlogic.gdx.Gdx.app.postRunnable(() -> onGuestLeft.accept(slot));
        deviceToSlot.clear();
        slotToDevice.clear();

        try {
            gattServer = bm.openGattServer(activity, gattCallback);
            if (gattServer == null) return null;

            BluetoothGattCharacteristic guestWrite = new BluetoothGattCharacteristic(
                    BleProtocol.GUEST_WRITE_UUID,
                    BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE);

            notifyChar = new BluetoothGattCharacteristic(
                    BleProtocol.HOST_NOTIFY_UUID,
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    BluetoothGattCharacteristic.PERMISSION_READ);
            BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(
                    BleProtocol.CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
            notifyChar.addDescriptor(cccd);

            BluetoothGattService service = new BluetoothGattService(
                    BleProtocol.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
            service.addCharacteristic(guestWrite);
            service.addCharacteristic(notifyChar);
            gattServer.addService(service);

            advertiser = adapter.getBluetoothLeAdvertiser();
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(true)
                    .build();
            AdvertiseData data = new AdvertiseData.Builder()
                    .addServiceUuid(new android.os.ParcelUuid(BleProtocol.SERVICE_UUID))
                    .setIncludeDeviceName(false)
                    .build();
            advertiser.startAdvertising(settings, data, advertiseCallback);

            return adapter.getAddress();
        } catch (SecurityException e) {
            stop();
            return null;
        }
    }

    @Override
    public void sendTo(int guestSlot, String message) {
        BluetoothDevice device = slotToDevice.get(guestSlot);
        if (device == null || gattServer == null || notifyChar == null) return;
        notify(device, message);
    }

    @Override
    public void broadcast(String message) {
        if (gattServer == null || notifyChar == null) return;
        for (BluetoothDevice device : slotToDevice.values()) {
            notify(device, message);
        }
    }

    /**
     * Notifies are sent one at a time from a single thread (see the class's use sites — sendTo/
     * broadcast are only ever called from the game's own update loop, never concurrently), so
     * mutating notifyChar's shared value immediately before each notifyCharacteristicChanged call
     * is safe in practice despite the two calls not being atomic from the API's own point of view:
     * there is no second writer that could interleave. (An earlier attempt to sidestep this by
     * passing a freshly-constructed, never-registered BluetoothGattCharacteristic crashed instead —
     * notifyCharacteristicChanged requires the characteristic object to actually belong to a
     * service added to this gattServer; API 33+'s 4-arg overload avoids the shared-value method
     * entirely, but this app's minSdk 26 floor means it can't be used unconditionally here.)
     */
    private void notify(BluetoothDevice device, String message) {
        try {
            // Trailing newline: the guest side (BleGuestServiceImpl) buffers and splits on '\n'
            // rather than assuming one notify == one complete line, since without an MTU
            // negotiation (never requested here) the default 20-byte ATT payload will fragment
            // anything longer than that into multiple onCharacteristicChanged callbacks.
            byte[] bytes = (message + "\n").getBytes(StandardCharsets.UTF_8);
            if (bytes.length > BleProtocol.MAX_LINE_BYTES) return; // drop rather than corrupt-truncate
            notifyChar.setValue(bytes);
            gattServer.notifyCharacteristicChanged(device, notifyChar, false);
        } catch (SecurityException ignored) {
        }
    }

    @Override
    public void stop() {
        try {
            if (advertiser != null) advertiser.stopAdvertising(advertiseCallback);
            if (gattServer != null) {
                for (BluetoothDevice device : slotToDevice.values()) {
                    gattServer.cancelConnection(device);
                }
                gattServer.close();
            }
        } catch (SecurityException ignored) {
        }
        gattServer = null;
        advertiser = null;
        notifyChar = null;
        deviceToSlot.clear();
        slotToDevice.clear();
    }

    @Override
    public int guestCount() {
        return slotToDevice.size();
    }

    private int nextFreeSlot() {
        for (int slot = 1; slot <= maxGuests; slot++) {
            if (!slotToDevice.containsKey(slot)) return slot;
        }
        return -1;
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartFailure(int errorCode) {
            System.out.println("[BleHost] Advertising failed to start, error=" + errorCode);
        }
    };

    private final BluetoothGattServerCallback gattCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                Integer slot = deviceToSlot.remove(device);
                if (slot != null) {
                    slotToDevice.remove(slot);
                    recvBuffers[slot] = null;
                    if (onGuestLeft != null) onGuestLeft.accept(slot);
                }
            }
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                BluetoothGattCharacteristic characteristic, boolean preparedWrite,
                boolean responseNeeded, int offset, byte[] value) {
            if (!BleProtocol.GUEST_WRITE_UUID.equals(characteristic.getUuid())) {
                if (responseNeeded) {
                    try { gattServer.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_FAILURE, 0, null); }
                    catch (SecurityException ignored) {}
                }
                return;
            }

            Integer slot = deviceToSlot.get(device);
            if (slot == null) {
                slot = nextFreeSlot();
                if (slot < 0) {
                    // Lobby full — respond ok (avoids a stuck guest write) but never assign a slot,
                    // so this device is simply never routed to onMessage; guest sees no welcome and
                    // times out. (The explicit "F|lobby_full" line requires a slot to notify back on,
                    // which a rejected connection deliberately doesn't get.)
                    if (responseNeeded) {
                        try { gattServer.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, null); }
                        catch (SecurityException ignored) {}
                    }
                    return;
                }
                deviceToSlot.put(device, slot);
                slotToDevice.put(slot, device);
                recvBuffers[slot] = new StringBuilder();
            }

            if (responseNeeded) {
                try { gattServer.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, null); }
                catch (SecurityException ignored) {}
            }

            String chunk = new String(value, StandardCharsets.UTF_8);
            StringBuilder buf = recvBuffers[slot];
            if (buf == null) return;
            buf.append(chunk);
            int nl;
            while ((nl = buf.indexOf("\n")) >= 0) {
                String line = buf.substring(0, nl);
                buf.delete(0, nl + 1);
                if (!line.isEmpty() && onMessage != null) onMessage.accept(slot, line);
            }
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded,
                int offset, byte[] value) {
            if (responseNeeded) {
                try { gattServer.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, null); }
                catch (SecurityException ignored) {}
            }
        }
    };
}
