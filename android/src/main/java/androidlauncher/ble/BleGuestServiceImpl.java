package androidlauncher.ble;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import platform.BleGuestService;

/**
 * Android implementation of {@link BleGuestService}: finds the host via a BLE scan filtered to
 * {@link BleProtocol#SERVICE_UUID} and connects to the first match, see the interface's class doc
 * for why this replaced connecting to a MAC address handed off via NFC (Android blocks apps from
 * reading their own/other devices' real Bluetooth address; the "MAC" in an NFC payload was always
 * garbage). The filtered scan is invisible to the player, no list, no picker, and normally
 * resolves in well under a second since only devices advertising our exact service UUID match.
 * Subscribes to the host's notify characteristic and writes the join handshake + subsequent lines
 * to the guest-write characteristic. See BleProtocol for UUIDs and main.BleMultiplayerSession for
 * the line protocol.
 */
public class BleGuestServiceImpl implements BleGuestService {

    /** Give up and report failure if no matching advertiser is found within this long. */
    private static final long SCAN_TIMEOUT_MS = 8_000;

    private final Activity activity;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private Consumer<Boolean> onResult;
    private Consumer<String> onMessage;
    private final StringBuilder recvBuffer = new StringBuilder();

    private BluetoothLeScanner scanner;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable scanTimeoutRunnable = this::onScanTimeout;

    public BleGuestServiceImpl(Activity activity) {
        this.activity = activity;
    }

    @Override
    public boolean isSupported() {
        if (!BlePermissions.hasAll(activity)) return false;
        BluetoothManager bm = (BluetoothManager) activity.getSystemService(Activity.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
        return adapter != null && adapter.isEnabled();
    }

    @Override
    public void connect(Consumer<Boolean> onResult, Consumer<String> onMessage) {
        // Every GATT/scan callback below arrives on Android's Binder callback thread, not
        // libGDX's GL/render thread, but onResult/onMessage flow straight into game state
        // (map load, gameState transitions, remotePlayers mutation) that the render thread reads
        // every frame with no synchronization. Wrapping once here, at the one place a caller
        // hands us these consumers, means every downstream call site is automatically safe
        // without needing to remember to wrap each one individually. See com.badlogic.gdx.Gdx#app
        // postRunnable's contract: queued Runnables run at the start of the next render() call.
        Consumer<Boolean> safeOnResult = accepted ->
                com.badlogic.gdx.Gdx.app.postRunnable(() -> onResult.accept(accepted));
        Consumer<String> safeOnMessage = msg ->
                com.badlogic.gdx.Gdx.app.postRunnable(() -> onMessage.accept(msg));

        if (!BlePermissions.hasAll(activity)) {
            System.out.println("[BleGuest] connect ABORT: missing permissions");
            BlePermissions.requestAll(activity);
            safeOnResult.accept(false);
            return;
        }
        BluetoothManager bm = (BluetoothManager) activity.getSystemService(Activity.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
        if (adapter == null || !adapter.isEnabled()) {
            System.out.println("[BleGuest] connect ABORT: adapter null/disabled adapter=" + adapter);
            safeOnResult.accept(false);
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            System.out.println("[BleGuest] connect ABORT: scanner null");
            safeOnResult.accept(false);
            return;
        }
        System.out.println("[BleGuest] connect: starting scan for host service UUID");

        this.onResult = safeOnResult;
        this.onMessage = safeOnMessage;
        recvBuffer.setLength(0);

        try {
            ScanFilter filter = new ScanFilter.Builder()
                    .setServiceUuid(new ParcelUuid(BleProtocol.SERVICE_UUID))
                    .build();
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
            System.out.println("[BleGuest] startScan issued (timeout " + SCAN_TIMEOUT_MS + "ms)");
            mainHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS);
        } catch (SecurityException | IllegalArgumentException e) {
            System.out.println("[BleGuest] startScan threw: " + e);
            safeOnResult.accept(false);
        }
    }

    private void stopScan() {
        mainHandler.removeCallbacks(scanTimeoutRunnable);
        if (scanner != null) {
            try { scanner.stopScan(scanCallback); } catch (SecurityException ignored) {}
            scanner = null;
        }
    }

    private void onScanTimeout() {
        if (gatt != null) return; // already found/connecting, timeout raced a real match, ignore
        System.out.println("[BleGuest] scan TIMEOUT — no host advertiser found");
        stopScan();
        if (onResult != null) onResult.accept(false);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (gatt != null) return; // already connecting to the first match
            System.out.println("[BleGuest] scan FOUND host: " + result.getDevice());
            stopScan();
            BluetoothDevice device = result.getDevice();
            try {
                gatt = device.connectGatt(activity, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            } catch (SecurityException e) {
                System.out.println("[BleGuest] connectGatt threw: " + e);
                if (onResult != null) onResult.accept(false);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            System.out.println("[BleGuest] scan FAILED errorCode=" + errorCode);
            stopScan();
            if (onResult != null) onResult.accept(false);
        }
    };

    @Override
    public void send(String message) {
        if (gatt == null || writeChar == null || !connected.get()) return;
        try {
            byte[] bytes = (message + "\n").getBytes(StandardCharsets.UTF_8);
            if (bytes.length > BleProtocol.MAX_LINE_BYTES) return;
            writeChar.setValue(bytes);
            writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            gatt.writeCharacteristic(writeChar);
        } catch (SecurityException ignored) {
        }
    }

    @Override
    public void disconnect() {
        stopScan();
        try {
            if (gatt != null) {
                gatt.disconnect();
                gatt.close();
            }
        } catch (SecurityException ignored) {
        }
        gatt = null;
        writeChar = null;
        connected.set(false);
    }

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // Ask for a larger ATT MTU so a full protocol line (up to BleProtocol.MAX_LINE_BYTES)
                // fits in one notify without relying on undocumented auto-fragmentation of the
                // default ~20-byte payload; onMtuChanged (whatever it negotiates to) proceeds to
                // service discovery either way, reassembly in onCharacteristicChanged handles
                // however many fragments an unaccommodating negotiation still leaves.
                try { g.requestMtu(BleProtocol.MAX_LINE_BYTES + 3); }
                catch (SecurityException e) { try { g.discoverServices(); } catch (SecurityException ignored) {} }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                boolean wasConnected = connected.getAndSet(false);
                if (!wasConnected && onResult != null) {
                    onResult.accept(false); // failed before ever reaching the "connected" handshake state
                } else if (onMessage != null) {
                    onMessage.accept("F|disconnected");
                }
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            // Proceed regardless of whether the negotiation succeeded/what size was granted
            // reassembly in onCharacteristicChanged tolerates however many fragments result.
            try { g.discoverServices(); } catch (SecurityException ignored) {}
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                if (onResult != null) onResult.accept(false);
                return;
            }
            android.bluetooth.BluetoothGattService service = g.getService(BleProtocol.SERVICE_UUID);
            if (service == null) {
                if (onResult != null) onResult.accept(false);
                return;
            }
            writeChar = service.getCharacteristic(BleProtocol.GUEST_WRITE_UUID);
            BluetoothGattCharacteristic notifyChar = service.getCharacteristic(BleProtocol.HOST_NOTIFY_UUID);
            if (writeChar == null || notifyChar == null) {
                if (onResult != null) onResult.accept(false);
                return;
            }

            try {
                g.setCharacteristicNotification(notifyChar, true);
                BluetoothGattDescriptor cccd = notifyChar.getDescriptor(BleProtocol.CCCD_UUID);
                if (cccd != null) {
                    cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(cccd);
                }
            } catch (SecurityException e) {
                if (onResult != null) onResult.accept(false);
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            if (!BleProtocol.CCCD_UUID.equals(descriptor.getUuid())) return;
            // Notifications are now enabled, the write channel is ready. connected=true here means
            // "can send", not "host has accepted"; BleMultiplayerSession.joinHost sends the actual
            // "H|token|name" handshake line from this onResult callback and only reports true up to
            // its own caller once the host's welcome ("W|...") line arrives.
            connected.set(true);
            if (onResult != null) onResult.accept(true);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
            if (!BleProtocol.HOST_NOTIFY_UUID.equals(characteristic.getUuid())) return;
            byte[] value = characteristic.getValue();
            if (value == null) return;
            // Without an MTU negotiation (none is requested), the default ATT payload is only ~20
            // bytes, any host message longer than that arrives as multiple separate notify
            // callbacks. BleHostServiceImpl.notify() now newline-terminates each message, so
            // buffer across callbacks and only dispatch complete lines, mirroring the host's own
            // guest-write reassembly (BleHostServiceImpl.onCharacteristicWriteRequest).
            recvBuffer.append(new String(value, StandardCharsets.UTF_8));
            int nl;
            while ((nl = recvBuffer.indexOf("\n")) >= 0) {
                String line = recvBuffer.substring(0, nl);
                recvBuffer.delete(0, nl + 1);
                if (!line.isEmpty() && onMessage != null) onMessage.accept(line);
            }
        }
    };
}
