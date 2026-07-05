package androidlauncher.coop;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;

import com.badlogic.gdx.Gdx;

import coop.BossCoopProtocol;
import coop.BossCoopSession;
import coop.LoadoutSerializer;
import coop.PlayerLoadout;
import coop.RewardingHelpers;
import main.GamePanel;
import platform.BtBossCoop;

/**
 * BLE-backed {@link BtBossCoop} — the mobile transport for co-op boss fights, mirroring {@code
 * coop.BossCoopHostServer}/{@code coop.BossCoopClient}'s role split over GATT instead of raw
 * sockets. Messages are the exact same {@code coop.BossCoopProtocol} JSON strings used on desktop;
 * only the framing differs, since BLE's default ATT MTU (23 bytes, 20 usable after the header)
 * is far smaller than a TCP stream — see {@link ChunkedMessenger} for the length-prefixed
 * chunking/reassembly this class layers on top of a single write/notify characteristic pair.
 *
 * <p>All GATT/scan/advertise callbacks fire on binder threads; every game-state touch is marshaled
 * back via {@link Gdx#postRunnable}, same convention as {@code androidlauncher.nfc.NfcPairingManager}.
 */
public final class BleBossCoop implements BtBossCoop {

    private static final String TAG = "MichiBLE";

    // Custom 128-bit UUIDs (random, project-specific — not a registered Bluetooth SIG UUID).
    private static final UUID SERVICE_UUID  = UUID.fromString("8f5b1e00-8b1a-4f6e-9b7a-6f6e8b1a4f6e");
    private static final UUID WRITE_CHAR_UUID  = UUID.fromString("8f5b1e01-8b1a-4f6e-9b7a-6f6e8b1a4f6e"); // joiner -> host
    private static final UUID NOTIFY_CHAR_UUID = UUID.fromString("8f5b1e02-8b1a-4f6e-9b7a-6f6e8b1a4f6e"); // host -> joiner
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Activity activity;
    private BluetoothManager btManager;

    // ── Host-side state ──
    private BluetoothGattServer gattServer;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattCharacteristic notifyChar;
    private AdvertiseCallback advertiseCallback;
    private GamePanel hostGp;
    private String hostSessionToken;
    private BossCoopSession hostSession;
    private final Map<BluetoothDevice, ChunkedMessenger> hostMessengers = new ConcurrentHashMap<>();
    private final Map<BluetoothDevice, String> hostUsernamesByDevice = new ConcurrentHashMap<>();

    // ── Joiner-side state ──
    private BluetoothLeScanner scanner;
    private BluetoothGatt clientGatt;
    private ChunkedMessenger clientMessenger;
    private GamePanel joinerGp;
    private BossCoopSession joinerSession;
    private PlayerLoadout ownLoadout;
    private String joinSessionToken;
    private ScanCallback scanCallback;

    public BleBossCoop(Activity activity) {
        this.activity = activity;
    }

    @Override
    public boolean isAvailable() {
        if (btManager == null) btManager = (BluetoothManager) activity.getSystemService(Activity.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = btManager != null ? btManager.getAdapter() : null;
        return adapter != null && adapter.isEnabled();
    }

    // ═══════════════════════════ HOST ═══════════════════════════

    @Override
    public BossCoopSession startHosting(GamePanel gp, int bossId, String sessionToken) {
        if (!isAvailable()) return null;
        this.hostGp = gp;
        this.hostSessionToken = sessionToken;
        this.hostSession = new BossCoopSession(BossCoopSession.Role.HOST, gp.ui.playerUsername, bossId);

        BluetoothAdapter adapter = btManager.getAdapter();
        try {
            gattServer = btManager.openGattServer(activity, gattServerCallback);
            BluetoothGattService service = new BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);

            BluetoothGattCharacteristic writeChar = new BluetoothGattCharacteristic(
                    WRITE_CHAR_UUID,
                    BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE);

            notifyChar = new BluetoothGattCharacteristic(
                    NOTIFY_CHAR_UUID,
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    BluetoothGattCharacteristic.PERMISSION_READ);
            BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
            notifyChar.addDescriptor(cccd);

            service.addCharacteristic(writeChar);
            service.addCharacteristic(notifyChar);
            gattServer.addService(service);

            advertiser = adapter.getBluetoothLeAdvertiser();
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setConnectable(true)
                    .setTimeout(0)
                    .build();
            AdvertiseData data = new AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .addServiceUuid(new ParcelUuid(SERVICE_UUID))
                    .build();
            advertiseCallback = new AdvertiseCallback() {
                @Override public void onStartFailure(int errorCode) {
                    Log.e(TAG, "Advertise failed: " + errorCode);
                    postStatus(hostSession, "Could not start hosting (Bluetooth advertise failed).");
                }
            };
            advertiser.startAdvertising(settings, data, advertiseCallback);
        } catch (SecurityException e) {
            postStatus(hostSession, "Bluetooth permission missing — cannot host.");
        }
        return hostSession;
    }

    private final BluetoothGattServerCallback gattServerCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                String username = hostUsernamesByDevice.remove(device);
                hostMessengers.remove(device);
                if (username != null && hostSession != null) {
                    hostSession.removePlayer(username);
                    if (hostSession.state() == BossCoopSession.State.WAITING) broadcastWaitingRoom();
                }
            }
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                BluetoothGattCharacteristic characteristic, boolean preparedWrite,
                boolean responseNeeded, int offset, byte[] value) {
            if (responseNeeded) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            }
            if (!WRITE_CHAR_UUID.equals(characteristic.getUuid())) return;

            ChunkedMessenger messenger = hostMessengers.computeIfAbsent(device,
                    d -> new ChunkedMessenger(bytes -> sendToDevice(device, bytes)));
            String complete = messenger.receive(value);
            if (complete != null) handleHostMessage(device, complete);
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                BluetoothGattDescriptor descriptor, boolean preparedWrite,
                boolean responseNeeded, int offset, byte[] value) {
            if (responseNeeded) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            }
        }
    };

    private void handleHostMessage(BluetoothDevice device, String json) {
        String cmd = BossCoopProtocol.extractString(json, "cmd");
        if (!BossCoopProtocol.CMD_JOIN.equals(cmd)) return;

        String token = BossCoopProtocol.extractString(json, "token");
        String username = BossCoopProtocol.extractString(json, "username");

        if (hostSession.state() != BossCoopSession.State.WAITING) {
            sendRawTo(device, "{\"cmd\":\"" + BossCoopProtocol.CMD_REJECTED + "\",\"reason\":\"Session already started.\"}");
            return;
        }
        if (hostSession.isFull()) {
            sendRawTo(device, "{\"cmd\":\"" + BossCoopProtocol.CMD_REJECTED + "\",\"reason\":\"Session is full.\"}");
            return;
        }
        if (token == null || !token.equals(hostSessionToken)) {
            sendRawTo(device, "{\"cmd\":\"" + BossCoopProtocol.CMD_REJECTED + "\",\"reason\":\"Invalid or expired invite.\"}");
            return;
        }
        if (username == null || username.isBlank()) return;

        hostUsernamesByDevice.put(device, username);
        hostSession.addPlayer(username);
        Gdx.app.postRunnable(this::broadcastWaitingRoom);
    }

    private void broadcastWaitingRoom() {
        List<String> players = hostSession.players();
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < players.size(); i++) {
            if (i > 0) names.append(',');
            names.append('"').append(BossCoopProtocol.jsonEscape(players.get(i))).append('"');
        }
        String msg = "{\"cmd\":\"" + BossCoopProtocol.CMD_WAITING + "\",\"players\":[" + names + "]}";
        for (BluetoothDevice d : hostUsernamesByDevice.keySet()) sendRawTo(d, msg);
    }

    @Override
    public void proceed(GamePanel gp, int coopPlayerCount) {
        if (hostSession == null || hostSession.state() != BossCoopSession.State.WAITING) return;
        hostSession.setState(BossCoopSession.State.ACTIVE);
        String loadoutJson = LoadoutSerializer.toJson(gp.player);
        String msg = "{\"cmd\":\"" + BossCoopProtocol.CMD_START + "\",\"playerCount\":" + coopPlayerCount
                + ",\"loadout\":" + jsonStringLiteral(loadoutJson) + "}";
        for (BluetoothDevice d : hostUsernamesByDevice.keySet()) sendRawTo(d, msg);
    }

    /** Called by the host's own game when the boss actually dies. */
    @Override
    public void notifyBossDefeated(int bossId, int rewardExp) {
        if (hostSession == null) return;
        String msg = "{\"cmd\":\"" + BossCoopProtocol.CMD_BOSS_DEFEATED + "\",\"bossId\":" + bossId
                + ",\"rewardExp\":" + rewardExp + "}";
        for (BluetoothDevice d : hostUsernamesByDevice.keySet()) sendRawTo(d, msg);
        hostSession.setState(BossCoopSession.State.ENDED);
    }

    @Override
    public void stopHosting() {
        if (hostSession != null) {
            String msg = "{\"cmd\":\"" + BossCoopProtocol.CMD_SESSION_ENDED + "\",\"reason\":\"Host cancelled.\"}";
            for (BluetoothDevice d : hostUsernamesByDevice.keySet()) sendRawTo(d, msg);
            hostSession.setState(BossCoopSession.State.ENDED);
        }
        try { if (advertiser != null && advertiseCallback != null) advertiser.stopAdvertising(advertiseCallback); } catch (SecurityException ignored) {}
        try { if (gattServer != null) gattServer.close(); } catch (SecurityException ignored) {}
        gattServer = null;
        advertiser = null;
        hostSession = null;
        hostMessengers.clear();
        hostUsernamesByDevice.clear();
    }

    private void sendRawTo(BluetoothDevice device, String json) {
        ChunkedMessenger messenger = hostMessengers.get(device);
        if (messenger != null) messenger.send(json);
    }

    private void sendToDevice(BluetoothDevice device, byte[] bytes) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gattServer.notifyCharacteristicChanged(device, notifyChar, false, bytes);
            } else {
                notifyChar.setValue(bytes);
                gattServer.notifyCharacteristicChanged(device, notifyChar, false);
            }
        } catch (SecurityException ignored) {
            // Permission revoked mid-session — the connection will simply drop.
        }
    }

    // ═══════════════════════════ JOINER ═══════════════════════════

    @Override
    public BossCoopSession joinSession(GamePanel gp, String sessionToken) {
        if (!isAvailable()) return null;
        this.joinerGp = gp;
        this.joinSessionToken = sessionToken;
        this.joinerSession = new BossCoopSession(BossCoopSession.Role.JOINER, "", 0);

        BluetoothAdapter adapter = btManager.getAdapter();
        scanner = adapter.getBluetoothLeScanner();
        ScanFilter filter = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE_UUID)).build();
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();

        scanCallback = new ScanCallback() {
            @Override public void onScanResult(int callbackType, ScanResult result) {
                try { scanner.stopScan(this); } catch (SecurityException ignored) {}
                connectToHost(result.getDevice());
            }
            @Override public void onScanFailed(int errorCode) {
                postStatus(joinerSession, "Bluetooth scan failed (code " + errorCode + ").");
            }
        };
        try {
            scanner.startScan(List.of(filter), settings, scanCallback);
        } catch (SecurityException e) {
            postStatus(joinerSession, "Bluetooth permission missing — cannot join.");
        }
        return joinerSession;
    }

    private void connectToHost(BluetoothDevice device) {
        try {
            clientGatt = device.connectGatt(activity, false, clientGattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            postStatus(joinerSession, "Bluetooth permission missing — cannot join.");
        }
    }

    private final BluetoothGattCallback clientGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                try { gatt.discoverServices(); } catch (SecurityException ignored) {}
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (joinerSession != null && joinerSession.state() != BossCoopSession.State.ENDED) {
                    joinerSession.setStatusMessage("Disconnected from host.");
                    joinerSession.setState(BossCoopSession.State.ENDED);
                    restoreOwnLoadout();
                }
                try { gatt.close(); } catch (SecurityException ignored) {}
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) return;
            BluetoothGattService service = gatt.getService(SERVICE_UUID);
            if (service == null) return;
            BluetoothGattCharacteristic writeChar = service.getCharacteristic(WRITE_CHAR_UUID);
            BluetoothGattCharacteristic notify = service.getCharacteristic(NOTIFY_CHAR_UUID);
            if (writeChar == null || notify == null) return;

            clientMessenger = new ChunkedMessenger(bytes -> writeChunk(gatt, writeChar, bytes));

            try {
                gatt.setCharacteristicNotification(notify, true);
                BluetoothGattDescriptor cccd = notify.getDescriptor(CCCD_UUID);
                if (cccd != null) {
                    byte[] value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(cccd, value);
                    } else {
                        cccd.setValue(value);
                        gatt.writeDescriptor(cccd);
                    }
                }
            } catch (SecurityException ignored) {
                postStatus(joinerSession, "Bluetooth permission missing — cannot join.");
                return;
            }

            String join = "{\"cmd\":\"" + BossCoopProtocol.CMD_JOIN + "\",\"token\":\""
                    + BossCoopProtocol.jsonEscape(joinSessionToken) + "\",\"username\":\""
                    + BossCoopProtocol.jsonEscape(joinerGp.ui.playerUsername) + "\"}";
            clientMessenger.send(join);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                onNotifyBytes(characteristic.getValue());
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
            onNotifyBytes(value);
        }
    };

    private void onNotifyBytes(byte[] value) {
        if (clientMessenger == null) return;
        String complete = clientMessenger.receive(value);
        if (complete != null) Gdx.app.postRunnable(() -> handleJoinerMessage(complete));
    }

    private void writeChunk(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] bytes) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            } else {
                characteristic.setValue(bytes);
                characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                gatt.writeCharacteristic(characteristic);
            }
        } catch (SecurityException ignored) {
            // Connection will drop; onConnectionStateChange handles cleanup.
        }
    }

    private void handleJoinerMessage(String json) {
        String cmd = BossCoopProtocol.extractString(json, "cmd");
        if (cmd == null) return;
        switch (cmd) {
            case BossCoopProtocol.CMD_WAITING -> {
                for (String name : BossCoopProtocol.extractStringArray(json, "players")) {
                    joinerSession.addPlayer(name);
                }
            }
            case BossCoopProtocol.CMD_START -> {
                ownLoadout = PlayerLoadout.capture(joinerGp.player);
                int idx = json.indexOf("\"loadout\":");
                if (idx >= 0) {
                    int braceStart = json.indexOf('{', idx);
                    if (braceStart >= 0) {
                        String obj = BossCoopProtocol.extractJsonObject(json, braceStart);
                        LoadoutSerializer.applyTo(joinerGp, obj, joinerGp.player);
                    }
                }
                joinerSession.setState(BossCoopSession.State.ACTIVE);
            }
            case BossCoopProtocol.CMD_BOSS_DEFEATED -> {
                int rewardExp = BossCoopProtocol.extractInt(json, "rewardExp", 0);
                int bossId = BossCoopProtocol.extractInt(json, "bossId", joinerSession.bossId());
                RewardingHelpers.rewardingHelpers(joinerGp, bossId, rewardExp);
                joinerSession.setState(BossCoopSession.State.ENDED);
                restoreOwnLoadout();
            }
            case BossCoopProtocol.CMD_REJECTED, BossCoopProtocol.CMD_SESSION_ENDED -> {
                joinerSession.setStatusMessage(BossCoopProtocol.extractString(json, "reason"));
                joinerSession.setState(BossCoopSession.State.ENDED);
                restoreOwnLoadout();
            }
            default -> { /* ignore unknown */ }
        }
    }

    private void restoreOwnLoadout() {
        if (ownLoadout != null && joinerGp != null) {
            ownLoadout.applyTo(joinerGp.player);
            ownLoadout = null;
        }
    }

    @Override
    public void leaveSession() {
        try {
            if (clientMessenger != null) clientMessenger.send("{\"cmd\":\"" + BossCoopProtocol.CMD_LEAVE + "\"}");
        } catch (Exception ignored) {}
        try { if (scanner != null && scanCallback != null) scanner.stopScan(scanCallback); } catch (SecurityException ignored) {}
        try { if (clientGatt != null) { clientGatt.disconnect(); clientGatt.close(); } } catch (SecurityException ignored) {}
        clientGatt = null;
        clientMessenger = null;
        restoreOwnLoadout();
        joinerSession = null;
    }

    private void postStatus(BossCoopSession session, String message) {
        Gdx.app.postRunnable(() -> {
            if (session != null) {
                session.setStatusMessage(message);
                session.setState(BossCoopSession.State.ENDED);
            }
        });
    }

    private static String jsonStringLiteral(String s) {
        return "\"" + BossCoopProtocol.jsonEscape(s) + "\"";
    }
}
