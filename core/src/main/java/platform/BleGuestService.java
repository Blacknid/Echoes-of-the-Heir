package platform;

import java.util.function.Consumer;

/**
 * Platform hook for joining a host's local Bluetooth LE multiplayer session as a guest (title
 * screen's JOIN GAME, after an NFC tap decodes the host's {@code NfcInvitePayload}). Only
 * implemented on Android; see {@link BleHostService}'s class doc for the general design.
 *
 * <p>This is the GATT client (central role) counterpart, it finds the host via a filtered BLE
 * scan (matching {@code BleProtocol.SERVICE_UUID}, invisible to the player, no device list/picker
 * shown) rather than connecting to a MAC address handed off via NFC. That MAC-based design was
 * tried first and doesn't work: {@code BluetoothAdapter.getAddress()} has returned a fixed
 * placeholder ({@code 02:00:00:00:00:00}) to every non-privileged app since Android 6.0/API 23
 * confirmed on real Galaxy S25 Ultra/A37 hardware, where every connect attempt against the
 * "MAC" handed off via NFC timed out (GATT status 147) because it was never a real address to
 * begin with. Scanning for the advertised service UUID is the only way to actually find the host.
 */
public interface BleGuestService {

    /** Whether this device can act as a BLE GATT client (Android with BLE central support). */
    boolean isSupported();

    /**
     * Scans for a nearby device advertising {@code BleProtocol.SERVICE_UUID} and connects to the
 * first one found, no device list or picker is ever shown; from the player's perspective this
     * is instant and automatic (typically resolves in well under a second since the scan filter is
     * exact-UUID-matched, not a general "nearby devices" scan). {@code onResult} fires once the
     * GATT link + characteristic subscription are actually write/notify-ready (true), or scanning
 * timed out / connection failed (false), this only means the channel is open, NOT that the
     * host has accepted the join; the caller (main.BleMultiplayerSession) is responsible for
     * sending the "H|token|name" handshake line itself once {@code onResult} fires true, and for
     * treating the host's own welcome response (not this callback) as proof of acceptance.
     * {@code onMessage} fires for every line received from the host after that point.
     */
    void connect(Consumer<Boolean> onResult, Consumer<String> onMessage);

    /** Sends a message to the host. No-op if not currently connected. */
    void send(String message);

    /** Disconnects from the host, if connected. */
    void disconnect();

    boolean isConnected();
}
