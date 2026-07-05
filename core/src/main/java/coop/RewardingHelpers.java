package coop;

import main.GamePanel;

/**
 * What a JOINER's own game does when the host's co-op boss fight ends in victory. Called once,
 * on the joiner's machine only, right after {@link BossCoopClient} receives {@code BOSS_DEFEATED}
 * — the host already applied its own normal (single-player) death handling locally and never goes
 * through this method itself.
 *
 * <p>Deliberately kept generic/safe by default (XP grant + the boss-defeated flag, both no-ops if
 * the numbers are zero/the flag is already set) so a co-op session never crashes a joiner's game
 * even before this is tuned further. Customize the body below for whatever a helped-out friend
 * should actually walk away with (bonus loot, currency, a unique memento item, etc).
 */
public final class RewardingHelpers {
    private RewardingHelpers() {}

    public static void rewardingHelpers(GamePanel gp, int bossId, int rewardExp) {
        if (rewardExp > 0) {
            gp.player.exp += rewardExp;
            gp.player.checkLevelUp();
        }

        switch (bossId) {
            case 1 -> gp.boss1Defeated = true;
            case 2 -> gp.boss2Defeated = true;
            case 3 -> gp.boss3Defeated = true;
            case 4 -> gp.boss4Defeated = true;
            default -> { /* unrecognized bossId — nothing more to flag */ }
        }

        if (gp.ui != null) {
            gp.ui.addMessage("Boss defeated! +" + rewardExp + " XP", gfx.Color.WHITE);
        }
    }
}
