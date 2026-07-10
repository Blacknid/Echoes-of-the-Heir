package platform;

/** Static facade shared code uses instead of depending on a platform-specific NFC impl. */
public final class NfcFriend {
    private NfcFriend() {}

    private static volatile NfcFriendService active;

    public static void set(NfcFriendService service) { active = service; }

    public static boolean isSupported() { return active != null && active.isSupported(); }

    public static void startReading(java.util.function.Consumer<String> onResult) {
        if (active != null) active.startReading(onResult);
        else onResult.accept(null);
    }

    public static void stopReading() {
        if (active != null) active.stopReading();
    }

    public static void setEmulatedPayload(String friendId, String username) {
        if (active != null) active.setEmulatedPayload(friendId, username);
    }

    public static void setEmulatedPayloadRaw(String payload) {
        if (active != null) active.setEmulatedPayloadRaw(payload);
    }
}
