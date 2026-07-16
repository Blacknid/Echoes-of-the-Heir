package platform;

import java.util.function.BiConsumer;
import java.util.function.IntConsumer;

/**
 * Platform hook for hosting a local Bluetooth LE multiplayer session (pause menu's INVITE
 * PLAYER). Only implemented on Android (see androidlauncher.ble.BleHostServiceImpl); desktop has
 * no BLE peripheral/GATT-server hardware support and leaves {@link BleMultiplayer#activeHost}
 * unset, so hosting is simply unavailable there.
 *
 * <p>The host acts as a BLE GATT server (peripheral role) and advertises so it's discoverable by
 * MAC address alone, guests connect directly using the address handed off via NFC
 * (see NfcInvitePayload), no BLE scan needed. Message framing is transport-internal to the
 * implementation (one GATT characteristic write/notify per line, mirroring the pipe-delimited
 * compactness of {@code MultiplayerClient}'s JSON messages but leaner for BLE's small MTU); see
 * main.BleMultiplayerSession for the actual message vocabulary (move/mob_damage/etc.).
 */
public interface BleHostService {

    /** Whether this device can act as a BLE GATT server (Android with BLE peripheral support). */
    boolean isSupported();

    /**
     * Starts advertising + the GATT server, capped at {@code maxGuests} simultaneous connections
 * (see class doc, server rejects a connection attempt beyond the cap). {@code onMessage}
     * fires (guestSlot, message) for every line received from a guest once it has completed the
     * join handshake; {@code onGuestLeft} fires (guestSlot) when a connected guest disconnects.
     * Safe to call once per session; call {@link #stop()} before starting a new one.
     * @return this device's own Bluetooth address, to embed in the NFC invite payload.
     */
    String start(int maxGuests, BiConsumer<Integer, String> onMessage, IntConsumer onGuestLeft);

    /** Sends a message to one specific guest slot (as reported by {@code onMessage}'s first argument). */
    void sendTo(int guestSlot, String message);

    /** Sends a message to every currently connected guest. */
    void broadcast(String message);

    /** Stops advertising, disconnects all guests, and tears down the GATT server. */
    void stop();

    /** Number of guests currently connected. */
    int guestCount();
}
