package platform;

/**
 * Platform hook for NFC add-friend taps. Only implemented on Android (see
 * androidlauncher.AndroidLauncher); desktop has no NFC hardware and leaves {@link NfcFriend#active}
 * unset, so {@link NfcFriend#isSupported()} reports false and the UI keeps the typed add-friend flow.
 *
 * <p>Two independent NFC roles, both required for one tap to fully add a friend:
 * <ul>
 * <li><b>Emulate</b> (HCE tag), always-on, works even with the app closed (like Google Pay):
 *   broadcasts this device's own {@code friend_id + username} as an NDEF message to whoever reads it.
 * <li><b>Read</b> (reader mode), requires the app open and this method actively invoked; the
 *   initiator of "Add Friend" reads the other phone's emulated tag to capture their friend_id.
 * </ul>
 */
public interface NfcFriendService {

    /** Whether this device can act as an NFC reader (Android with NFC hardware present/enabled). */
    boolean isSupported();

    /**
     * Starts NFC reader mode so the next tap against another device reads its emulated NDEF friend
     * tag. Calls {@code onResult} on completion with the raw NDEF payload string once a tag is read
     * successfully, or {@code null} if the read failed/timed out. Safe to call repeatedly; a new
     * call replaces any in-flight read. Must be paired with {@link #stopReading()} when the Add
     * Friend screen closes.
     */
    void startReading(java.util.function.Consumer<String> onResult);

    /** Stops NFC reader mode started by {@link #startReading}. No-op if not currently reading. */
    void stopReading();

    /**
     * Updates the NDEF payload this device's HCE service emulates when read by another phone's
 * reader mode, should be called once {@link main.FriendsListManager#getMyFriendId()} and the
     * claimed username are known, so the emulated tag stays current for as long as the app runs
     * (including backgrounded, per HCE's AID-routing behavior).
     */
    void setEmulatedPayload(String friendId, String username);

    /**
     * Same underlying mechanism as {@link #setEmulatedPayload(String, String)} but with an
 * already-encoded payload string, used for BLE session invites ({@code NfcInvitePayload}),
     * which reuse this exact tap-to-exchange-data channel for a different kind of payload. Only
     * one payload can be emulated at a time; callers own switching it back when done (e.g. the
     * host restores its friend payload after ending a BLE session). Pass {@code null} to stop
     * answering taps altogether (e.g. no invite active and no friend payload known yet).
     */
    void setEmulatedPayloadRaw(String payload);
}
