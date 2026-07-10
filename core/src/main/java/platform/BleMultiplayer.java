package platform;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Static facade shared code uses instead of depending on platform-specific BLE impls directly. */
public final class BleMultiplayer {
    private BleMultiplayer() {}

    private static volatile BleHostService activeHost;
    private static volatile BleGuestService activeGuest;

    public static void setHost(BleHostService service) { activeHost = service; }
    public static void setGuest(BleGuestService service) { activeGuest = service; }

    public static boolean isHostingSupported() { return activeHost != null && activeHost.isSupported(); }
    public static boolean isJoiningSupported() { return activeGuest != null && activeGuest.isSupported(); }

    public static String startHosting(int maxGuests, BiConsumer<Integer, String> onMessage, IntConsumer onGuestLeft) {
        return activeHost != null ? activeHost.start(maxGuests, onMessage, onGuestLeft) : null;
    }

    public static void hostSendTo(int guestSlot, String message) {
        if (activeHost != null) activeHost.sendTo(guestSlot, message);
    }

    public static void hostBroadcast(String message) {
        if (activeHost != null) activeHost.broadcast(message);
    }

    public static void stopHosting() {
        if (activeHost != null) activeHost.stop();
    }

    public static void joinHost(Consumer<Boolean> onResult, Consumer<String> onMessage) {
        if (activeGuest != null) activeGuest.connect(onResult, onMessage);
        else onResult.accept(false);
    }

    public static void guestSend(String message) {
        if (activeGuest != null) activeGuest.send(message);
    }

    public static void leaveHost() {
        if (activeGuest != null) activeGuest.disconnect();
    }
}
