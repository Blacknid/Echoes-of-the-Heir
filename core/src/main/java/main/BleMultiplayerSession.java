package main;

import java.util.concurrent.ConcurrentHashMap;

import entity.DamageNumber;
import entity.Entity;
import platform.BleMultiplayer;

/**
 * Lightweight local Bluetooth LE multiplayer session, the "invite player" alternative to the
 * license-bound {@link MultiplayerClient} TCP server. Two players tap phones (NFC hands off the
 * host's BLE address + a session token + the active map id, see platform.NfcInvitePayload); the
 * host's phone then runs an in-process BLE GATT server (see platform.BleHostService) that up to
 * {@link #MAX_GUESTS} guest phones connect to directly, no internet/account/license involved.
 *
 * <h2>Why this is "lightweight" compared to MultiplayerClient</h2>
 * Both phones already have the identical game install/map files on disk, so unlike the TCP
 * server model (built to support possibly-different installs), this session transmits NO map/tile
 * data at all, {@link #hostMapId} just tells a joining guest which map file (that it already
 * has locally) to load via {@code gp.mapManager.changeMap}. There's also no world-progression
 * ownership, no account/license handshake, and no encryption (a same-room BLE link between two
 * phones you just tapped together doesn't have the threat model a public TCP server does).
 * BLE's tiny characteristic MTU is used for compact pipe-delimited lines (not JSON), one message
 * per line, mirroring MultiplayerClient's message vocabulary at a fraction of the size:
 * <pre>
 *   host -&gt; guest:  W|id|mapId|col|row                 (welcome: assigns guest slot + spawn)
 *                   J|id|name                           (another guest joined)
 *                   L|id                                (a guest left)
 *                   U|id|x|y|dir|sprite|atk|life|maxLife (position/anim update for player id)
 *                   D|mobId|life|maxLife|dmg             (mob took damage)
 *                   K|mobId                              (mob died)
 *                   F|reason                             (session ending / lobby full)
 *   guest -&gt; host:  H|token|name                        (join handshake, see BleGuestService)
 *                   U|x|y|dir|sprite|atk|life|maxLife    (this guest's own position/anim)
 *                   D|mobId|dmg                          (this guest hit a mob for dmg)
 *                   K|mobId                              (this guest killed a mob)
 * </pre>
 * {@link entity.BossMonster}/{@link entity.BOSS_WitheredTree} spawn scaling reads
 * {@link #totalPlayerCount()}, see map.MapObjectLoader's boss-spawn hook.
 */
public class BleMultiplayerSession {

    public static final int MAX_GUESTS = 5;

    private final GamePanel gp;

    // ── Shared state (populated whether we're hosting or guesting) ──────────────────────────
    public final ConcurrentHashMap<Integer, MultiplayerClient.RemotePlayerState> remotePlayers = new ConcurrentHashMap<>();
    private volatile boolean hosting = false;
    private volatile boolean guesting = false;
    private volatile int localId = -1; // 0 = host; 1..MAX_GUESTS = guest slot, assigned by host

    // ── Host-only state ───────────────────────────────────────────────────────────────────────
    private String hostMapId;
    private String hostDisplayName = "Host";
    private String sessionToken;

    private int sendCounter = 0;
    private static final int SEND_INTERVAL = 3; // matches MultiplayerClient's throttle

    public BleMultiplayerSession(GamePanel gp) {
        this.gp = gp;
    }

    public boolean isActive() { return hosting || guesting; }
    public boolean isHosting() { return hosting; }
    public boolean isGuesting() { return guesting; }
    public int getLocalId() { return localId; }

    /** Total players in the session including the local one, 1 if inactive (solo). */
    public int totalPlayerCount() {
        if (!isActive()) return 1;
        return 1 + remotePlayers.size();
    }

    // Host side

    /**
     * Starts BLE hosting (GATT server + advertising). Returns false if BLE hosting isn't supported
 * on this device (permissions, adapter off/absent), see BleHostService#start. The guest finds
     * this host via a BLE scan on BleProtocol.SERVICE_UUID (see BleGuestService's class doc for why
 * NOT a Bluetooth address, Android doesn't let apps read a real one), so nothing device-address
     * -shaped needs to come back from this call for the NFC invite payload to be built.
     */
    public boolean startHosting() {
        if (!platform.BleMultiplayer.isHostingSupported()) return false;
        hostMapId = gp.mapManager.currentMapId;
        hostDisplayName = gp.ui.playerUsername.isBlank() ? "Host" : gp.ui.playerUsername;
        sessionToken = Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong());
        remotePlayers.clear();
        localId = 0;
        hosting = true;

        return BleMultiplayer.startHosting(MAX_GUESTS, this::onHostReceivedLine, this::onGuestLeft) != null;
    }

    public String getSessionToken() { return sessionToken; }
    public String getHostMapId() { return hostMapId; }
    public String getHostDisplayName() { return hostDisplayName; }

    public void stopHosting() {
        if (!hosting) return;
        BleMultiplayer.hostBroadcast("F|host_ended");
        BleMultiplayer.stopHosting();
        hosting = false;
        localId = -1;
        remotePlayers.clear();
    }

    private void onHostReceivedLine(int guestSlot, String line) {
        System.out.println("[BleHost] recv slot=" + guestSlot + " line=" + line);
        String[] p = line.split("\\|", -1);
        if (p.length == 0) return;
        switch (p[0]) {
            case "H" -> handleGuestHandshake(guestSlot, p);
            case "U" -> handleGuestUpdate(guestSlot, p);
            case "D" -> handleGuestMobDamage(guestSlot, p);
            case "K" -> handleGuestMobDeath(guestSlot, p);
            default -> { /* unknown line, ignore */ }
        }
    }

    private void handleGuestHandshake(int guestSlot, String[] p) {
        if (p.length < 3) return;
        String token = p[1];
        String name = p[2].isBlank() ? ("Guest" + guestSlot) : p[2];
        if (!token.equals(sessionToken)) {
            BleMultiplayer.hostSendTo(guestSlot, "F|bad_token");
            return;
        }
        if (remotePlayers.size() >= MAX_GUESTS && !remotePlayers.containsKey(guestSlot)) {
            BleMultiplayer.hostSendTo(guestSlot, "F|lobby_full");
            return;
        }

        int spawnCol = gp.mapManager.defaultSpawnCol;
        int spawnRow = gp.mapManager.defaultSpawnRow;

        MultiplayerClient.RemotePlayerState rp = new MultiplayerClient.RemotePlayerState();
        rp.name = name;
        rp.worldX = spawnCol >= 0 ? spawnCol * gp.tileSize : gp.player.worldX;
        rp.worldY = spawnRow >= 0 ? spawnRow * gp.tileSize : gp.player.worldY;
        remotePlayers.put(guestSlot, rp);

        BleMultiplayer.hostSendTo(guestSlot, "W|" + guestSlot + "|" + hostMapId + "|" + spawnCol + "|" + spawnRow);
        BleMultiplayer.hostBroadcast("J|" + guestSlot + "|" + name);
        gp.ui.addMessage(name + " joined the game", new gfx.Color(140, 220, 140));
    }

    private void handleGuestUpdate(int guestSlot, String[] p) {
        // Guest's own "U" carries no slot id (the connection it arrived on IS the slot, unlike
        // the host's outgoing "U|0|..." broadcast, see update()), one field fewer than that
        // format, so the length floor here is 8, not 9.
        if (p.length < 8) return;
        MultiplayerClient.RemotePlayerState rp = remotePlayers.get(guestSlot);
        if (rp == null) return;
        applyUpdate(rp, p, 1);
        // Relay to every other connected guest so guests see each other too.
        BleMultiplayer.hostBroadcast("U|" + guestSlot + "|" + line(p, 1));
    }

    private void handleGuestMobDamage(int guestSlot, String[] p) {
        if (p.length < 3) return;
        applyMobDamage(parseIntSafe(p[1], -1), parseIntSafe(p[2], 0), guestSlot);
    }

    private void handleGuestMobDeath(int guestSlot, String[] p) {
        if (p.length < 2) return;
        applyMobDeath(parseIntSafe(p[1], -1), guestSlot);
    }

    /** Called when a guest disconnects (Android BleHostServiceImpl reports this via onGuestCountChanged + a synthetic leave). */
    public void onGuestLeft(int guestSlot) {
        MultiplayerClient.RemotePlayerState rp = remotePlayers.remove(guestSlot);
        BleMultiplayer.hostBroadcast("L|" + guestSlot);
        if (rp != null) gp.ui.addMessage(rp.name + " left the game", new gfx.Color(220, 160, 140));
    }

    // Guest side

    /**
     * Connects to a host using the decoded NFC invite payload. On success, loads the host's map
 * (already present locally, see class doc) and places the local player at the given spawn
     * with default stats/no items, mirroring a fresh join. {@code onResult} fires once (accepted?).
     */
    public void joinHost(platform.NfcInvitePayload.Decoded invite, java.util.function.Consumer<Boolean> onResult) {
        System.out.println("[BleJoin] joinHost() token=" + invite.sessionToken() + " map=" + invite.mapId()
                + " joiningSupported=" + platform.BleMultiplayer.isJoiningSupported());
        if (!platform.BleMultiplayer.isJoiningSupported()) { System.out.println("[BleJoin] ABORT: joining not supported"); onResult.accept(false); return; }
        remotePlayers.clear();
        guesting = true;
        String myName = gp.ui.playerUsername.isBlank() ? "Guest" : gp.ui.playerUsername;

        BleMultiplayer.joinHost(channelReady -> {
            System.out.println("[BleJoin] channelReady=" + channelReady);
            if (!channelReady) {
                guesting = false;
                onResult.accept(false);
                return;
            }
            // Channel is write-ready, send the join handshake now. Acceptance is proven only by
            // the host's own "W" (welcome) line arriving, which is what actually fires onResult
            // true, below in onGuestReceivedLine, not this callback. The session token here is
            // also what rules out a stray device that happens to advertise our service UUID but
            // isn't actually the host we tapped (see BleGuestService's scan-based discovery doc).
            BleMultiplayer.guestSend("H|" + invite.sessionToken() + "|" + myName);
        }, line -> onGuestReceivedLine(line, invite, onResult));
    }

    private boolean welcomed = false;

    private void onGuestReceivedLine(String line, platform.NfcInvitePayload.Decoded invite, java.util.function.Consumer<Boolean> onResult) {
        System.out.println("[BleJoin] guest recv: " + line);
        String[] p = line.split("\\|", -1);
        if (p.length == 0) return;
        switch (p[0]) {
            case "W" -> {
                if (p.length < 5) return;
                localId = parseIntSafe(p[1], -1);
                String mapId = p[2];
                int spawnCol = parseIntSafe(p[3], -1);
                int spawnRow = parseIntSafe(p[4], -1);
                gp.player.setDefaultValues(); // fresh stats, empty inventory, before changeMap so
                                               // its spawn positioning (below) is the final word
                gp.mapManager.changeMap(mapId, spawnCol, spawnRow); // -1,-1 falls back to the map's
                                                                     // own default spawn (see MapManager)
                welcomed = true;
                onResult.accept(true);
            }
            case "J" -> {
                if (p.length < 3) return;
                int id = parseIntSafe(p[1], -1);
                MultiplayerClient.RemotePlayerState rp = new MultiplayerClient.RemotePlayerState();
                rp.name = p[2];
                if (id >= 0) remotePlayers.put(id, rp);
            }
            case "L" -> {
                if (p.length < 2) return;
                remotePlayers.remove(parseIntSafe(p[1], -1));
            }
            case "U" -> {
                if (p.length < 9) return;
                int id = parseIntSafe(p[1], -1);
                if (id < 0 || id == localId) return;
                MultiplayerClient.RemotePlayerState rp = remotePlayers.computeIfAbsent(id,
                        k -> new MultiplayerClient.RemotePlayerState());
                applyUpdate(rp, p, 2);
            }
            case "D" -> {
                if (p.length < 5) return;
                applyMobDamage(parseIntSafe(p[1], -1), parseIntSafe(p[4], 0), -2 /* from host, not us */);
            }
            case "K" -> {
                if (p.length < 2) return;
                applyMobDeath(parseIntSafe(p[1], -1), -2);
            }
            case "F" -> {
                gp.ui.addMessage("Multiplayer session ended", new gfx.Color(220, 160, 140));
                leaveHost();
                if (!welcomed) onResult.accept(false);
            }
            default -> { /* ignore */ }
        }
    }

    public void leaveHost() {
        if (!guesting) return;
        BleMultiplayer.leaveHost();
        guesting = false;
        welcomed = false;
        localId = -1;
        remotePlayers.clear();
    }

    // Per-tick outgoing state (called from GamePanel.update(), same shape as MultiplayerClient)

    public void update() {
        if (!isActive()) return;
        sendCounter++;
        if (sendCounter < SEND_INTERVAL) return;
        sendCounter = 0;

        String payload = gp.player.worldX + "|" + gp.player.worldY + "|" + gp.player.direction + "|"
                + gp.player.spriteNum + "|" + (gp.player.attacking ? 1 : 0) + "|" + gp.player.life + "|" + gp.player.maxLife;

        if (hosting) {
            // Host doesn't need to send its own position to itself; guests only need each other's
            // updates, which are relayed in handleGuestUpdate. Nothing to send here for the host's
            // own transform, guests render the host implicitly via remotePlayers on their side
            // once the host also broadcasts its own "U" line under a reserved id 0.
            BleMultiplayer.hostBroadcast("U|0|" + payload);
        } else if (guesting) {
            BleMultiplayer.guestSend("U|" + payload);
        }
    }

    /** Call from Player's attack/ability code alongside the existing MultiplayerClient sync, see entity.Player. */
    public void sendMobDamage(int mobId, int damage, int currentLife, int maxLife) {
        if (!isActive()) return;
        String msg = "D|" + mobId + "|" + currentLife + "|" + maxLife + "|" + damage;
        if (hosting) {
            applyMobDamage(mobId, damage, 0); // host already has authoritative state locally
            BleMultiplayer.hostBroadcast(msg);
        } else {
            BleMultiplayer.guestSend("D|" + mobId + "|" + damage);
        }
    }

    public void sendMobDeath(int mobId) {
        if (!isActive()) return;
        if (hosting) {
            BleMultiplayer.hostBroadcast("K|" + mobId);
        } else {
            BleMultiplayer.guestSend("K|" + mobId);
        }
    }

    // Shared helpers

    private void applyUpdate(MultiplayerClient.RemotePlayerState rp, String[] p, int off) {
        int x = parseIntSafe(p[off], rp.worldX);
        int y = parseIntSafe(p[off + 1], rp.worldY);
        rp.direction = parseIntSafe(p[off + 2], rp.direction);
        rp.spriteNum = parseIntSafe(p[off + 3], rp.spriteNum);
        rp.attacking = "1".equals(p[off + 4]);
        rp.life = parseIntSafe(p[off + 5], rp.life);
        rp.maxLife = parseIntSafe(p[off + 6], rp.maxLife);

        // Simple linear-interpolation spline (BLE updates arrive far less often than TCP's, but
        // the same Hermite evaluator in RemotePlayerState works fine with zero tangents, it just
        // degrades to a straight lerp between snapshots, which is visually fine for this cadence).
        long now = System.nanoTime();
        rp.spPsX = rp.spReady ? rp.evalSpline(now)[0] : x;
        rp.spPsY = rp.spReady ? rp.evalSpline(now)[1] : y;
        rp.spPeX = x;
        rp.spPeY = y;
        rp.spVsX = 0; rp.spVsY = 0; rp.spVeX = 0; rp.spVeY = 0;
        rp.spStartNs = now;
        rp.spDurationNs = 150_000_000L; // ~150ms, matched to BLE's coarser update cadence
        rp.spReady = true;
        rp.worldX = x;
        rp.worldY = y;
    }

    private String line(String[] p, int fromIdx) {
        return String.join("|", java.util.Arrays.copyOfRange(p, fromIdx, p.length));
    }

    private void applyMobDamage(int mobId, int damage, int fromSlot) {
        if (mobId < 0 || mobId >= gp.monster.length) return;
        Entity mob = gp.monster[mobId];
        if (mob == null || !mob.alive) return;
        if (fromSlot == 0 && hosting) return; // host applies its own hits locally already, via Player

        mob.life -= damage;
        mob.hitFlashCounter = 6;
        mob.damageReaction();
        if (damage > 0) {
            DamageNumber dn = gp.damageNumberPool.get();
            dn.set(mob.worldX + gp.tileSize / 4, mob.worldY - 8, String.valueOf(damage), new gfx.Color(255, 80, 60), false);
            gp.damageNumbers.add(dn);
        }
        if (mob.life <= 0 && !mob.dying) {
            int coinDrop = Math.max(1, mob.exp / 2);
            mob.beginDeath(mob.exp, 1, coinDrop);
        }
        // Host relays every applied hit to all guests so they converge on the same HP.
        if (hosting) {
            BleMultiplayer.hostBroadcast("D|" + mobId + "|" + mob.life + "|" + mob.maxLife + "|" + damage);
        }
    }

    private void applyMobDeath(int mobId, int fromSlot) {
        if (mobId < 0 || mobId >= gp.monster.length) return;
        Entity mob = gp.monster[mobId];
        if (mob == null || !mob.alive || mob.dying) return;
        int coinDrop = Math.max(1, mob.exp / 2);
        mob.beginDeath(mob.exp, 1, coinDrop);
        if (hosting) {
            BleMultiplayer.hostBroadcast("K|" + mobId);
        }
    }

    private static int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }
}
