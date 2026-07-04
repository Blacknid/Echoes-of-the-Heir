package environment;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import main.GamePanel;

/**
 * Per-map wind model.
 *
 * <p>Wind is stored as a continuous per-tile grid (one cell per map tile), independent of Tiled.
 * Each cell holds:
 * <ul>
 *   <li><b>strength</b> — base wind coefficient at that tile (0 = calm, 1 = full gale). This is the
 *       per-tile "wind coefficient" the design calls for: a tile can be windier than its neighbour.</li>
 *   <li><b>dirX, dirY</b> — a unit vector giving the <em>local</em> wind direction painted into that tile.</li>
 *   <li><b>rainBonus</b> — extra strength added <em>only while it is raining</em>. Per-tile, so you decide
 *       which tiles get windier in rain (and how much) and which don't.</li>
 * </ul>
 *
 * <p>The grid is authored with the in-game {@link WindPainter} brush (map shown behind, paint with the
 * mouse) and saved to a small companion file <code>&lt;Map&gt;.windmap</code> next to the .tmx. If no
 * file exists for a map, {@link #proceduralWind} is consulted instead, letting wind be defined purely in
 * code (a "wind gradient" function of position) without any painting.
 *
 * <p>On top of the static painted field, the whole map <em>gusts</em> coherently: a global animated
 * direction wobbles over time, and when it decides to swing to a new heading it first lets the gust die
 * down to 70% strength, then rotates, then builds back up — so the wind never instantly reverses.
 *
 * <p>{@link #sampleX}/{@link #sampleY} return the final wind <em>force</em> vector at a world position,
 * already including the per-tile strength, the rain bonus, and the global gust envelope. The player
 * physics then projects that force onto the player's movement axis only (never sideways).
 */
public class WindField {

    /** Magic header + version for the .windmap binary format. */
    private static final int MAGIC   = 0x57494E44; // 'WIND'
    private static final int VERSION = 1;

    private final GamePanel gp;

    // ── Painted grid (cols x rows, one cell per tile). Null until a map is loaded. ──
    private float[][] strength;   // [row][col] base coefficient 0..1
    private float[][] rainBonus;  // [row][col] extra strength while raining 0..1
    private float[][] angle;      // [row][col] local wind direction, radians
    private int cols, rows;
    private boolean hasPaintedData = false;
    private String currentMapId = "";

    // ── Global gust envelope (applies to the whole field) ──
    // The painted angle is the *local* heading; the global gust adds a slowly varying rotation
    // offset + a strength multiplier that dips to GUST_MIN whenever the heading swings.
    private float gustStrength = 1f;          // 0..1 multiplier over the whole field
    private float gustAngleOffset = 0f;       // radians added to every cell's local angle
    private float gustTargetOffset = 0f;      // heading we are easing toward
    private boolean gustSwinging = false;     // true while strength is dipping for a heading change
    private int gustTimer = 0;
    private int gustNextChange;
    private final java.util.Random rng = new java.util.Random();

    private static final float GUST_MIN          = 0.70f; // dip to 70% before changing direction
    private static final float GUST_RECOVER_RATE = 0.010f; // per-frame strength ramp
    private static final float GUST_ROTATE_RATE  = 0.03f;  // per-frame radians toward target offset
    private static final int   GUST_CHANGE_MIN   = 240;    // 4s @60fps
    private static final int   GUST_CHANGE_MAX   = 720;    // 12s @60fps
    private static final float GUST_MAX_SWING    = 0.9f;   // max radians a single swing rotates the field

    public WindField(GamePanel gp) {
        this.gp = gp;
        scheduleNextGust();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Map lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    /** Load the wind grid for a map. Called from MapManager.changeMap / GamePanel reload. */
    public void loadForMap(String mapId, int mapCols, int mapRows) {
        this.currentMapId = (mapId != null) ? mapId : "";
        this.cols = Math.max(1, mapCols);
        this.rows = Math.max(1, mapRows);
        this.strength  = new float[rows][cols];
        this.rainBonus = new float[rows][cols];
        this.angle     = new float[rows][cols];
        this.hasPaintedData = false;

        if (!loadWindMapFile(this.currentMapId)) {
            // No painted file — fall back to a code-defined gradient (if any).
            buildProceduralGrid();
        }
        // Reset gust state so a new map starts calm-ish, not mid-swing.
        gustStrength = 1f;
        gustAngleOffset = 0f;
        gustTargetOffset = 0f;
        gustSwinging = false;
        gustTimer = 0;
        scheduleNextGust();
    }

    public boolean hasData() { return hasPaintedData; }
    public int getCols() { return cols; }
    public int getRows() { return rows; }
    public String getCurrentMapId() { return currentMapId; }

    // ──────────────────────────────────────────────────────────────────────────
    // Per-frame gust animation
    // ──────────────────────────────────────────────────────────────────────────

    public void update() {
        if (strength == null) return;

        if (gustSwinging) {
            // Phase 1: let the gust die down to GUST_MIN before rotating.
            gustStrength = Math.max(GUST_MIN, gustStrength - GUST_RECOVER_RATE);
            // Rotate the offset toward the new target while weakened.
            float diff = gustTargetOffset - gustAngleOffset;
            if (Math.abs(diff) <= GUST_ROTATE_RATE) {
                gustAngleOffset = gustTargetOffset;
            } else {
                gustAngleOffset += Math.signum(diff) * GUST_ROTATE_RATE;
            }
            // Once weakened AND aligned, start recovering.
            if (gustStrength <= GUST_MIN + 1e-3f && gustAngleOffset == gustTargetOffset) {
                gustSwinging = false;
            }
        } else {
            // Phase 2: recover strength back to full.
            if (gustStrength < 1f) {
                gustStrength = Math.min(1f, gustStrength + GUST_RECOVER_RATE);
            }
            gustTimer++;
            if (gustTimer >= gustNextChange) {
                gustTimer = 0;
                scheduleNextGust();
                // Pick a new heading offset; begin the dip-rotate-recover cycle.
                float swing = (rng.nextFloat() * 2f - 1f) * GUST_MAX_SWING;
                gustTargetOffset = gustAngleOffset + swing;
                gustSwinging = true;
            }
        }
    }

    private void scheduleNextGust() {
        gustNextChange = GUST_CHANGE_MIN + rng.nextInt(GUST_CHANGE_MAX - GUST_CHANGE_MIN + 1);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Sampling — final wind force vector at a world position
    // ──────────────────────────────────────────────────────────────────────────

    /** True when it's currently raining/storming (rain bonus applies). */
    private boolean isRaining() {
        if (gp.eManager == null) return false;
        int w = gp.eManager.weatherState;
        return w == EnvironmentManager.WEATHER_RAIN || w == EnvironmentManager.WEATHER_STORM;
    }

    /** Effective strength at a tile (base + rain bonus when raining), scaled by weather intensity. */
    private float cellStrength(int col, int row) {
        float s = strength[row][col];
        if (isRaining()) {
            s += rainBonus[row][col] * gp.eManager.weatherIntensity;
        }
        return s;
    }

    private int clampCol(int c) { return c < 0 ? 0 : (c >= cols ? cols - 1 : c); }
    private int clampRow(int r) { return r < 0 ? 0 : (r >= rows ? rows - 1 : r); }

    /** World X → wind force X (game pixels of force, pre-projection). */
    public float sampleX(int worldX, int worldY) {
        if (strength == null) return 0f;
        int col = clampCol(worldX / gp.tileSize);
        int row = clampRow(worldY / gp.tileSize);
        float s = cellStrength(col, row) * gustStrength;
        if (s <= 0f) return 0f;
        return (float) Math.cos(angle[row][col] + gustAngleOffset) * s;
    }

    /** World Y → wind force Y (game pixels of force, pre-projection). */
    public float sampleY(int worldX, int worldY) {
        if (strength == null) return 0f;
        int col = clampCol(worldX / gp.tileSize);
        int row = clampRow(worldY / gp.tileSize);
        float s = cellStrength(col, row) * gustStrength;
        if (s <= 0f) return 0f;
        return (float) Math.sin(angle[row][col] + gustAngleOffset) * s;
    }

    /** Raw painted strength at a tile (no rain, no gust) — for the painter overlay. */
    public float rawStrengthAt(int col, int row) {
        if (strength == null || col < 0 || row < 0 || col >= cols || row >= rows) return 0f;
        return strength[row][col];
    }

    /** Raw painted angle at a tile — for the painter overlay. */
    public float rawAngleAt(int col, int row) {
        if (angle == null || col < 0 || row < 0 || col >= cols || row >= rows) return 0f;
        return angle[row][col];
    }

    public float rawRainBonusAt(int col, int row) {
        if (rainBonus == null || col < 0 || row < 0 || col >= cols || row >= rows) return 0f;
        return rainBonus[row][col];
    }

    /** Current global gust strength (0..1) — for HUD readouts. */
    public float getGustStrength() { return gustStrength; }
    public float getGustAngleOffset() { return gustAngleOffset; }

    // ──────────────────────────────────────────────────────────────────────────
    // Painting API (used by WindPainter)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Stamp a soft round brush of wind into the grid.
     *
     * @param centerCol,centerRow brush centre in tile coords
     * @param radiusTiles         brush radius in tiles (soft falloff to the edge)
     * @param addStrength         how much base strength to add at the centre (use negative to erase)
     * @param dirRadians          wind direction to set under the brush
     * @param paintRain           if true, also paints the rain-bonus channel with |addStrength|
     */
    public void paint(int centerCol, int centerRow, float radiusTiles,
                      float addStrength, float dirRadians, boolean paintRain) {
        if (strength == null || radiusTiles <= 0f) return;
        int r = (int) Math.ceil(radiusTiles);
        for (int dr = -r; dr <= r; dr++) {
            for (int dc = -r; dc <= r; dc++) {
                int col = centerCol + dc, row = centerRow + dr;
                if (col < 0 || row < 0 || col >= cols || row >= rows) continue;
                float dist = (float) Math.sqrt(dc * dc + dr * dr);
                if (dist > radiusTiles) continue;
                float falloff = 1f - (dist / radiusTiles); // 1 at centre → 0 at edge
                float delta = addStrength * falloff;
                float ns = clamp01(strength[row][col] + delta);
                strength[row][col] = ns;
                // Direction is set (not blended) where we add strength, so a stroke
                // imprints a coherent heading; erasing leaves direction untouched.
                if (addStrength > 0f) {
                    angle[row][col] = dirRadians;
                }
                if (paintRain) {
                    rainBonus[row][col] = clamp01(rainBonus[row][col] + Math.abs(addStrength) * falloff);
                }
            }
        }
        hasPaintedData = true;
    }

    /** Wipe the whole grid to calm. */
    public void clearAll() {
        if (strength == null) return;
        for (int row = 0; row < rows; row++) {
            java.util.Arrays.fill(strength[row], 0f);
            java.util.Arrays.fill(rainBonus[row], 0f);
            java.util.Arrays.fill(angle[row], 0f);
        }
        hasPaintedData = true;
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    // ──────────────────────────────────────────────────────────────────────────
    // Persistence — <Map>.windmap companion file
    // ──────────────────────────────────────────────────────────────────────────

    /** Resolve the source-tree path for a map's .windmap (dev-time save target). */
    private java.io.File windMapFile(String mapId) {
        // TMX files live at core/assets/res/maps/<Name>.tmx; mapId is the lowercased stem.
        // We save next to them so they ship in the resources folder.
        String fileName = mapId + ".windmap";
        String[] candidates = {
            "core/assets/res/maps/" + fileName,
            "src/res/maps/" + fileName,
            "res/maps/" + fileName,
        };
        for (String c : candidates) {
            java.io.File f = new java.io.File(c);
            if (f.getParentFile() != null && f.getParentFile().isDirectory()) return f;
        }
        // Fallback: current dir.
        return new java.io.File(fileName);
    }

    /** Load <Map>.windmap from the classpath (works packaged) or source tree. Returns true if found. */
    private boolean loadWindMapFile(String mapId) {
        // 1) classpath resource (packaged jar / runtime)
        InputStream in = util.ResourceCache.openClasspathStream("/res/maps/" + mapId + ".windmap");
        // 2) source tree (dev)
        try {
            if (in == null) {
                java.io.File f = windMapFile(mapId);
                if (f.isFile()) in = new java.io.FileInputStream(f);
            }
            if (in == null) return false;
            try (DataInputStream dis = new DataInputStream(new java.io.BufferedInputStream(in))) {
                int magic = dis.readInt();
                int ver   = dis.readInt();
                int c     = dis.readInt();
                int rw    = dis.readInt();
                if (magic != MAGIC || ver != VERSION) {
                    System.out.println("[WindField] Bad windmap header for " + mapId);
                    return false;
                }
                if (c != cols || rw != rows) {
                    // Grid resized since authoring — accept but clamp into our buffers.
                    System.out.println("[WindField] windmap size " + c + "x" + rw
                        + " != map " + cols + "x" + rows + "; loading with clamp.");
                }
                for (int row = 0; row < rw; row++) {
                    for (int col = 0; col < c; col++) {
                        float s  = dis.readFloat();
                        float rb = dis.readFloat();
                        float a  = dis.readFloat();
                        if (row < rows && col < cols) {
                            strength[row][col]  = s;
                            rainBonus[row][col] = rb;
                            angle[row][col]     = a;
                        }
                    }
                }
            }
            hasPaintedData = true;
            System.out.println("[WindField] Loaded windmap for " + mapId);
            return true;
        } catch (IOException e) {
            System.out.println("[WindField] Failed to read windmap for " + mapId + ": " + e.getMessage());
            return false;
        }
    }

    /** Save the current grid to <Map>.windmap in the source tree. Returns true on success. */
    public boolean save() {
        if (strength == null) return false;
        java.io.File f = windMapFile(currentMapId);
        try (DataOutputStream dos = new DataOutputStream(
                new java.io.BufferedOutputStream(new FileOutputStream(f)))) {
            dos.writeInt(MAGIC);
            dos.writeInt(VERSION);
            dos.writeInt(cols);
            dos.writeInt(rows);
            for (int row = 0; row < rows; row++) {
                for (int col = 0; col < cols; col++) {
                    dos.writeFloat(strength[row][col]);
                    dos.writeFloat(rainBonus[row][col]);
                    dos.writeFloat(angle[row][col]);
                }
            }
            System.out.println("[WindField] Saved windmap -> " + f.getAbsolutePath());
            return true;
        } catch (IOException e) {
            System.out.println("[WindField] Save failed: " + e.getMessage());
            return false;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Code-defined wind gradient (fallback when no .windmap exists)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Fill the grid from {@link #proceduralWind} so wind can be defined entirely in code.
     * Edit {@link #proceduralWind} to give a map a prevailing direction, gradients, etc.
     */
    private void buildProceduralGrid() {
        boolean any = false;
        float[] out = new float[3]; // [strength, rainBonus, angle]
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                out[0] = 0f; out[1] = 0f; out[2] = 0f;
                proceduralWind(currentMapId, col, row, out);
                strength[row][col]  = clamp01(out[0]);
                rainBonus[row][col] = clamp01(out[1]);
                angle[row][col]     = out[2];
                if (out[0] > 0f) any = true;
            }
        }
        hasPaintedData = any;
    }

    /**
     * Code-defined wind for a map+tile. Default: calm everywhere.
     * Override per map here for a quick "wind gradient" without painting a file.
     *
     * @param out fill out[0]=strength(0..1), out[1]=rainBonus(0..1), out[2]=angle(radians)
     */
    protected void proceduralWind(String mapId, int col, int row, float[] out) {
        // Example (commented): a prevailing east wind that strengthens toward the south.
        // if ("shattered_lake".equals(mapId)) {
        //     out[0] = 0.25f + 0.5f * (row / (float) rows); // stronger lower on the map
        //     out[1] = 0.3f;                                 // windier in rain
        //     out[2] = 0f;                                    // 0 rad = blowing east (+X)
        // }
        // Default: calm.
    }
}
