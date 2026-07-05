package platform;

import main.GamePanel;

/** Read-only Bluetooth-boss-coop surface shared code uses instead of depending on the BLE implementation. */
public final class Bt {
    private Bt() {}

    private static volatile BtBossCoop active;

    public static void set(BtBossCoop impl) { active = impl; }

    public static boolean isAvailable() { return active != null && active.isAvailable(); }

    public static coop.BossCoopSession startHosting(GamePanel gp, int bossId, String sessionToken) {
        return active != null ? active.startHosting(gp, bossId, sessionToken) : null;
    }

    public static void proceed(GamePanel gp, int coopPlayerCount) {
        if (active != null) active.proceed(gp, coopPlayerCount);
    }

    public static void notifyBossDefeated(int bossId, int rewardExp) {
        if (active != null) active.notifyBossDefeated(bossId, rewardExp);
    }

    public static void stopHosting() { if (active != null) active.stopHosting(); }

    public static coop.BossCoopSession joinSession(GamePanel gp, String sessionToken) {
        return active != null ? active.joinSession(gp, sessionToken) : null;
    }

    public static void leaveSession() { if (active != null) active.leaveSession(); }
}
