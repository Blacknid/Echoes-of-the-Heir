package platform;

/**
 * Platform-specific NFC tap handler, implemented on Android only (desktop has no NFC hardware).
 * A tap reads the peer's username + a one-time Bluetooth pairing token from their phone and hands
 * both to the {@link Listener}; the same tap also broadcasts our own payload to their phone via
 * host-card emulation, so a single two-phone tap is symmetric — both sides walk away with the
 * other's username request queued and a fresh BT pairing token cached for boss co-op (Phase 3).
 */
public interface NfcPairing {
    boolean isAvailable();

    /** Begin emulating a tag (HCE) + listening for taps. Safe to call repeatedly; no-op if already active. */
    void startSession(String ownUsername, String ownBluetoothToken, Listener listener);

    /** Stop emulating/listening (e.g. when leaving the Friends screen). */
    void stopSession();

    interface Listener {
        /** Called on the UI thread once a peer's payload has been read from an NFC tap. */
        void onPeerRead(String peerUsername, String peerBluetoothToken);
        void onError(String message);
    }
}
