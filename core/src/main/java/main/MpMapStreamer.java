package main;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

import util.ResourceCache;

/**
 * Multiplayer map streaming client.
 *
 * <p>When the game runs in multiplayer mode the world lives on the server,
 * not on the client. The server sends:
 *
 * <ol>
 *   <li><b>world_info</b> &mdash; a small JSON envelope with map dimensions,
 *       chunk grid size, layer list, default spawn, and a "skeleton" TMX
 *       (the original TMX with every {@code <data>} block emptied). The
 *       skeleton is preloaded into {@link ResourceCache} so that the existing
 *       {@code TileManager} and {@code MapObjectLoader} pipelines pick it up
 *       through the regular path resolution (the single-player code path is
 *       <b>not</b> modified).</li>
 *   <li><b>chunk</b> messages &mdash; per (layer, cx, cy) tile-data blobs
 *       (gzipped little-endian uint32 GIDs preserving Tiled flip-flag bits).
 *       The streamer applies them directly to the running TileManager's
 *       {@code mapLayers} / {@code mapFlipLayers} so the world fills in
 *       progressively as the player explores.</li>
 * </ol>
 *
 * <p>Chunks are requested in player-priority order: nearest the spawn /
 * current player position first, expanding outward in concentric rings. The
 * server caps the chunks-per-tick rate, so the streamer can safely enqueue
 * the entire map's worth of requests up front &mdash; the server drains them
 * as bandwidth allows.
 *
 * <p>Single-threaded usage assumed: all mutating methods are called from the
 * MultiplayerClient receive thread; reads from the EDT happen on simple
 * volatile / atomic fields only.
 */
public class MpMapStreamer {

    /** GID flip bits live in the top 3 bits, matching {@code TileManager.GID_MASK}. */
    private static final long GID_MASK = 0x1FFFFFFFL;

    private final GamePanel gp;
    private final MultiplayerClient client;

    public volatile String mapId = "";
    public volatile int worldTilesWide;
    public volatile int worldTilesHigh;
    public volatile int tileWidthPx;
    public volatile int tileHeightPx;
    public volatile int chunkSize;
    public volatile int chunksX;
    public volatile int chunksY;
    public volatile int spawnCol;
    public volatile int spawnRow;

    // Per-player authoritative spawn from the welcome packet.
    // -1 means no override — fall back to the TMX default spawn.
    public volatile int welcomeSpawnX = -1;
    public volatile int welcomeSpawnY = -1;

    /** {@code layerIdx -> layer name}. Server-authoritative ordering. */
    private final List<String> layerNamesByIdx = new ArrayList<>();

    private final AtomicBoolean skeletonLoaded   = new AtomicBoolean(false);
    private final AtomicBoolean worldReady       = new AtomicBoolean(false);
    private final AtomicInteger chunksReceived   = new AtomicInteger(0);
    private volatile int totalChunksExpected = 0;

    /** Per-(layerIdx,cx,cy) bookkeeping. */
    private final Set<Long> chunksRequested = new HashSet<>();
    private final Set<Long> chunksApplied   = new HashSet<>();

    /** Layer name -&gt; index in TileManager.mapLayers (after skeleton load). */
    private final Map<String, Integer> tileLayerIndex = new HashMap<>();

    public MpMapStreamer(GamePanel gp, MultiplayerClient client) {
        this.gp = gp;
        this.client = client;
    }

    public boolean isWorldReady() { return worldReady.get(); }
    public boolean isSkeletonLoaded() { return skeletonLoaded.get(); }
    public int chunksReceived() { return chunksReceived.get(); }
    public int chunksExpected() { return totalChunksExpected; }

    public String progressString() {
        int got = chunksReceived.get();
        int exp = totalChunksExpected;
        if (exp <= 0) return skeletonLoaded.get() ? "Loading world..." : "Receiving world...";
        int pct = Math.min(100, (got * 100) / Math.max(1, exp));
        return "Loading world " + pct + "%  (" + got + "/" + exp + " chunks)";
    }

    public void reset() {
        skeletonLoaded.set(false);
        worldReady.set(false);
        chunksReceived.set(0);
        totalChunksExpected = 0;
        synchronized (chunksRequested) { chunksRequested.clear(); }
        synchronized (chunksApplied)   { chunksApplied.clear();   }
        layerNamesByIdx.clear();
        tileLayerIndex.clear();
        mapId = "";
        welcomeSpawnX = -1;
        welcomeSpawnY = -1;
    }

    // =====================================================================
    //  world_info handler
    // =====================================================================

    /**
     * Apply the server's world_info packet. Must be called once per session,
     * before any chunk packets arrive.
     */
    public void applyWorldInfo(WorldInfo info) {
        // Preserve the per-player spawn assigned in the welcome packet.
        // reset() clears welcomeSpawnX/Y, but welcome always arrives BEFORE
        // world_info on the same TCP stream, so we must protect these values.
        int savedWelcomeSpawnX = welcomeSpawnX;
        int savedWelcomeSpawnY = welcomeSpawnY;
        reset();
        welcomeSpawnX = savedWelcomeSpawnX;
        welcomeSpawnY = savedWelcomeSpawnY;

        this.mapId         = info.mapId;
        this.worldTilesWide = info.width;
        this.worldTilesHigh = info.height;
        this.tileWidthPx   = info.tilewidth;
        this.tileHeightPx  = info.tileheight;
        this.chunkSize     = info.chunkSize;
        this.chunksX       = info.chunksX;
        this.chunksY       = info.chunksY;
        this.spawnCol      = info.spawnCol;
        this.spawnRow      = info.spawnRow;
        layerNamesByIdx.clear();
        layerNamesByIdx.addAll(info.layerNames);
        this.totalChunksExpected = layerNamesByIdx.size() * chunksX * chunksY;

        try {
            // Stage 1: preload skeleton TMX into the resource cache under the
            // standard path so the existing TileManager pipeline finds it.
            String virtualPath = "/res/maps/" + mapId + ".tmx";
            ResourceCache.invalidateXml(virtualPath);
            ResourceCache.preloadXml(virtualPath, info.skeletonXmlBytes);

            // Stage 2: tell the MapManager about this map and switch to it.
            // The client's tile and event pipelines will run as if it were a
            // local map. Tilesets reference relative paths inside the JAR,
            // so they resolve correctly because the virtual path matches the
            // single-player /res/maps/ layout.
            gp.mapManager.registerMap(mapId, virtualPath);

            // Run the map switch on the EDT-friendly path: GamePanel.update is
            // single-threaded with the receive thread already, but to be safe
            // we just call changeMap directly &mdash; the tile arrays it
            // builds are populated with zeros (since the skeleton has empty
            // CSV blocks) which matches our "nothing loaded yet" state.
            gp.mapManager.changeMap(mapId, spawnCol, spawnRow);
            // Snap player to TMX default spawn for now; finishWorldLoad() will
            // override with the per-player welcomeSpawn once all chunks arrive.
            gp.player.worldX = spawnCol * gp.tileSize;
            gp.player.worldY = spawnRow * gp.tileSize;

            indexTileLayers();
            skeletonLoaded.set(true);

            requestChunksByPriority();
        } catch (Exception e) {
            System.out.println("[MpMapStreamer] Failed to apply world_info: " + e.getMessage());
            e.printStackTrace(System.out);
        }
    }

    /** Build {layerName -> tileManagerLayerIndex} from current TileManager state. */
    private void indexTileLayers() {
        tileLayerIndex.clear();
        for (int i = 0; i < gp.tileM.layerNames.size(); i++) {
            tileLayerIndex.putIfAbsent(gp.tileM.layerNames.get(i), i);
        }
    }

    // =====================================================================
    //  chunk handler
    // =====================================================================

    /**
     * Apply a server chunk packet. The chunk's GIDs are written directly into
     * TileManager's {@code mapLayers} / {@code mapFlipLayers} arrays. No
     * reload, no allocation churn &mdash; just an in-place patch of the
     * spatial region the chunk covers.
     */
    public void applyChunk(ChunkPacket chunk) {
        if (!skeletonLoaded.get()) {
            // The server should never send chunks before world_info, but be
            // defensive: queue would just complicate this; drop and let the
            // client re-request.
            return;
        }
        long key = chunkKey(chunk.layerIdx, chunk.cx, chunk.cy);
        synchronized (chunksApplied) {
            if (!chunksApplied.add(key)) return;
        }

        Integer tmIdx = tileLayerIndex.get(chunk.layerName);
        if (tmIdx == null) {
            // Layer name didn't survive into TileManager (e.g. it was an
            // imagelayer or a skipped layer). Count as received so progress
            // doesn't stall.
            chunksReceived.incrementAndGet();
            return;
        }

        int[][]  layer = gp.tileM.mapLayers.get(tmIdx);
        byte[][] flips = (tmIdx < gp.tileM.mapFlipLayers.size())
                ? gp.tileM.mapFlipLayers.get(tmIdx) : null;

        long[] raw;
        try {
            raw = decodeRawGids(chunk.dataBase64, chunk.w * chunk.h);
        } catch (Exception e) {
            System.out.println("[MpMapStreamer] chunk decode failed: " + e.getMessage());
            return;
        }

        int x0 = chunk.cx * chunkSize;
        int y0 = chunk.cy * chunkSize;
        for (int row = 0; row < chunk.h; row++) {
            int mapRow = y0 + row;
            if (mapRow < 0 || mapRow >= worldTilesHigh) continue;
            for (int col = 0; col < chunk.w; col++) {
                int mapCol = x0 + col;
                if (mapCol < 0 || mapCol >= worldTilesWide) continue;
                long full = raw[row * chunk.w + col] & 0xFFFFFFFFL;
                int gid   = (int) (full & GID_MASK);
                byte flip = extractFlipFlags(full);
                if (mapCol < layer.length && mapRow < layer[mapCol].length) {
                    layer[mapCol][mapRow] = gid;
                    if (flips != null && mapCol < flips.length && mapRow < flips[mapCol].length) {
                        flips[mapCol][mapRow] = flip;
                    }
                }
            }
        }

        int got = chunksReceived.incrementAndGet();
        if (got >= totalChunksExpected && totalChunksExpected > 0) {
            finishWorldLoad();
        }
    }

    private void finishWorldLoad() {
        if (!worldReady.compareAndSet(false, true)) return;
        try {
            // Reset all player state so singleplayer stats/inventory cannot carry over.
            gp.player.setDefaultValues();
            // Refresh derived caches that rely on tile data (collision rect
            // cache is built from objectgroups already; this is here in case
            // future caches depend on the now-populated tile grids).
            gp.cChecker.updateCollisionRectsCache();
            if (gp.minimap != null) gp.minimap.bakeTerrainImage();
            gp.tileM.rebuildReflectiveTilePositions();
            // Apply the per-player authoritative spawn from the welcome packet now
            // that the world is fully loaded. This overrides the generic TMX default
            // spawn that was set in applyWorldInfo(), so the player lands at the
            // correct server-assigned position.
            if (welcomeSpawnX >= 0 && welcomeSpawnY >= 0) {
                // welcomeSpawnX/Y are in server pixel coords (32px/tile).
                // The client renders with gp.tileSize (64px/tile), so scale up.
                int scale = tileWidthPx > 0 ? gp.tileSize / tileWidthPx : 1;
                gp.player.worldX = welcomeSpawnX * scale;
                gp.player.worldY = welcomeSpawnY * scale;
                System.out.println("[MpMapStreamer] Player spawned at welcome pos: "
                        + gp.player.worldX + ", " + gp.player.worldY);
            }
            client.sendWorldReady();
            System.out.println("[MpMapStreamer] World fully loaded: " + mapId);
        } catch (Exception e) {
            System.out.println("[MpMapStreamer] finishWorldLoad error: " + e.getMessage());
        }
    }

    // =====================================================================
    //  Chunk-request scheduling
    // =====================================================================

    /**
     * Request every chunk in the world, ordered by Chebyshev distance from
     * the player's current spawn tile. The server enforces a per-tick cap so
     * blasting the queue is fine; chunks arrive nearest-first.
     */
    private void requestChunksByPriority() {
        int spawnCx = Math.min(chunksX - 1, Math.max(0, spawnCol / chunkSize));
        int spawnCy = Math.min(chunksY - 1, Math.max(0, spawnRow / chunkSize));

        // Build a flat list of (distance, layerIdx, cx, cy) and sort by dist.
        record Req(int dist, int layerIdx, int cx, int cy) {}
        List<Req> reqs = new ArrayList<>(totalChunksExpected);
        for (int li = 0; li < layerNamesByIdx.size(); li++) {
            for (int cy = 0; cy < chunksY; cy++) {
                for (int cx = 0; cx < chunksX; cx++) {
                    int dist = Math.max(Math.abs(cx - spawnCx), Math.abs(cy - spawnCy));
                    reqs.add(new Req(dist, li, cx, cy));
                }
            }
        }
        reqs.sort((a, b) -> Integer.compare(a.dist, b.dist));
        for (Req r : reqs) {
            long key = chunkKey(r.layerIdx, r.cx, r.cy);
            synchronized (chunksRequested) {
                if (!chunksRequested.add(key)) continue;
            }
            client.sendChunkRequest(r.layerIdx, r.cx, r.cy);
        }
    }

    // =====================================================================
    //  Trigger / position-correction handlers (forwarded from MultiplayerClient)
    // =====================================================================

    public void applyPositionCorrection(int x, int y, String reason) {
        gp.player.worldX = x;
        gp.player.worldY = y;
    }

    public void applyTrigger(TriggerPacket t) {
        // For v1, just surface the trigger to the player as a debug log line.
        // The single-player EventHandler still drives client-side dialogue
        // and gating. Hooking server-authoritative dialogue into the existing
        // EventHandler is intentionally left for a future change so the
        // single-player code path stays untouched.
        String label = (t.name != null && !t.name.isEmpty()) ? t.name
                     : (t.triggerType != null ? t.triggerType : "trigger");
        System.out.println("[MP trigger] " + label + " @ (" + t.x + "," + t.y + ")");
    }

    // =====================================================================
    //  Decoding / utilities
    // =====================================================================

    private static long[] decodeRawGids(String b64, int count) throws Exception {
        byte[] gz = Base64.getDecoder().decode(b64);
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            byte[] buf = gis.readAllBytes();
            if (buf.length < count * 4) {
                throw new java.io.IOException("chunk underrun: got " + buf.length + " bytes, need " + (count * 4));
            }
            long[] out = new long[count];
            for (int i = 0; i < count; i++) {
                int b0 = buf[i*4    ] & 0xff;
                int b1 = buf[i*4 + 1] & 0xff;
                int b2 = buf[i*4 + 2] & 0xff;
                int b3 = buf[i*4 + 3] & 0xff;
                out[i] = ((long) b3 << 24) | ((long) b2 << 16) | ((long) b1 << 8) | b0;
            }
            return out;
        }
    }

    private static byte extractFlipFlags(long raw) {
        // Match TileManager.extractFlipFlags(): low 3 bits encode H, V, D.
        byte flip = 0;
        if ((raw & 0x80000000L) != 0) flip |= 0x01; // horizontal
        if ((raw & 0x40000000L) != 0) flip |= 0x02; // vertical
        if ((raw & 0x20000000L) != 0) flip |= 0x04; // diagonal
        return flip;
    }

    private static long chunkKey(int layerIdx, int cx, int cy) {
        // Pack into 64 bits: 24 layer | 20 cx | 20 cy.
        return (((long) layerIdx & 0xFFFFFF) << 40)
             | (((long) cx       & 0xFFFFF)  << 20)
             |  ((long) cy       & 0xFFFFF);
    }

    // =====================================================================
    //  DTOs (parsed by MultiplayerClient from the server's JSON envelopes)
    // =====================================================================

    public static class WorldInfo {
        public String mapId;
        public int width, height;
        public int tilewidth, tileheight;
        public int chunkSize;
        public int chunksX, chunksY;
        public int spawnCol, spawnRow;
        public List<String> layerNames = new ArrayList<>();
        public byte[] skeletonXmlBytes;
    }

    public static class ChunkPacket {
        public int layerIdx;
        public String layerName;
        public int cx, cy;
        public int w, h;
        public String dataBase64;
    }

    public static class TriggerPacket {
        public int id;
        public String name;
        public String triggerType;
        public double x, y, w, h;
        public String rawJson;     // full JSON so the client can pick props
    }
}
