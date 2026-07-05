package platform;

import main.GamePanel;

/**
 * Platform-specific Bluetooth transport for mobile boss co-op (Phase 3), implemented on Android
 * only via BLE GATT (desktop uses LAN/mDNS instead — see {@link LanDiscovery}). Mirrors {@link
 * coop.BossCoopHostServer}/{@link coop.BossCoopClient}'s role split exactly, just over a different
 * wire — both platforms share {@code coop.BossCoopProtocol}'s message shapes, {@code
 * coop.LoadoutSerializer}, and {@code coop.RewardingHelpers} verbatim.
 *
 * <p>Per the design's core trust rule: the game-level Bluetooth connection this opens is gated by
 * a session token exchanged over a dedicated "boss invite" NFC tap (distinct from the friend-add
 * tap — see {@link NfcPairing}) — NOT by whatever the OS Bluetooth stack itself allows. Two phones
 * can be OS-bonded via Settings and still get rejected here if they never tapped for THIS specific
 * session. The joiner never needs to know the host's Bluetooth address ahead of time: BLE
 * discovery finds the host by its advertised service UUID, and the token (read from the NFC tap)
 * is what the host's GATT server checks before accepting the join — the same role the mDNS-session
 * token plays on desktop.
 */
public interface BtBossCoop {
    boolean isAvailable();

    /** Host: start advertising a boss-co-op session over BLE, gated by {@code sessionToken}
     *  (freshly minted per session, handed to the invited friend via the boss-invite NFC tap). */
    coop.BossCoopSession startHosting(GamePanel gp, int bossId, String sessionToken);

    /** Host clicks Proceed once the waiting room roster is ready. */
    void proceed(GamePanel gp, int coopPlayerCount);

    /** Host's own boss just died — broadcast the reward to every joiner over BLE. */
    void notifyBossDefeated(int bossId, int rewardExp);

    void stopHosting();

    /** Joiner: scan for a nearby host advertising this exact session, then connect and join.
     *  {@code sessionToken} comes from the boss-invite NFC tap that was just exchanged. */
    coop.BossCoopSession joinSession(GamePanel gp, String sessionToken);

    void leaveSession();
}
