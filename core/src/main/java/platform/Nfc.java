package platform;

/** Read-only NFC surface shared code uses instead of depending on the Android-only implementation. */
public final class Nfc {
    private Nfc() {}

    private static volatile NfcPairing active;

    public static void set(NfcPairing pairing) { active = pairing; }

    public static boolean isAvailable() { return active != null && active.isAvailable(); }

    public static void startSession(String ownUsername, String ownBluetoothToken, NfcPairing.Listener listener) {
        if (active != null) active.startSession(ownUsername, ownBluetoothToken, listener);
        else listener.onError("NFC is not available on this device.");
    }

    public static void stopSession() {
        if (active != null) active.stopSession();
    }
}
