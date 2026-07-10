package androidlauncher.ble;

import java.util.UUID;

/** Shared GATT UUIDs for the local multiplayer BLE session — see main.BleMultiplayerSession. */
final class BleProtocol {
    private BleProtocol() {}

    /** Custom 128-bit service UUID (randomly generated, fixed for this app/protocol version). */
    static final UUID SERVICE_UUID = UUID.fromString("6d7a1000-2f2a-4b8e-9c9c-9a5f6a5e1a01");

    /** Guest-writes-to-host characteristic (WRITE, no response needed for a fire-and-forget line). */
    static final UUID GUEST_WRITE_UUID = UUID.fromString("6d7a1001-2f2a-4b8e-9c9c-9a5f6a5e1a01");

    /** Host-notifies-guest characteristic (NOTIFY) — carries lines addressed to this one guest
     *  (its own welcome, mob sync, other players' updates relayed by the host). */
    static final UUID HOST_NOTIFY_UUID = UUID.fromString("6d7a1002-2f2a-4b8e-9c9c-9a5f6a5e1a01");

    /** Standard Client Characteristic Configuration descriptor, needed to enable notifications. */
    static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /** Practical line-length cap for a single GATT write/notify value (see class docs on both
     *  BleHostServiceImpl/BleGuestServiceImpl — real-world BLE ATT payload is much smaller than
     *  the 512-byte spec ceiling unless MTU is explicitly negotiated up, so lines are kept short
     *  pipe-delimited text, never map/tile data — see BleMultiplayerSession's class doc). */
    static final int MAX_LINE_BYTES = 500;
}
