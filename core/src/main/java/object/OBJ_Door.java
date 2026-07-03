package object;

import gfx.Sprite;

import entity.Entity;
import main.GamePanel;

public class OBJ_Door extends Entity {

    GamePanel gp;
    // map transition / portal info
    public boolean portal = false; // when true, this door uses `location`
    public String location = null; // either a map id / path (eg "/res/maps/test.tmx" or "test") or "col,row" for local coords
    public boolean requiresKey = false;
    public int spawnCol = 1;
    public int spawnRow = 1;

    /** Direction the player faces after entering through this door (default: DIR_DOWN). */
    public int spawnDirection = DIR_DOWN;
    /** Named spawn point on the target map — resolved after the map loads. */
    public String spawnId = "";

    public Sprite[] doorOpenFrames;
    public int doorAnimIndex = 0;
    public int doorAnimSpeed = 8;      // ticks per frame
    public int doorAnimCounter = 0;
    public boolean doorOpening = false;

    // Pending transition params (used during door animation)
    private String pendingLocation;
    private int pendingSpawnCol;
    private int pendingSpawnRow;

    /** Whether the door sprite is visible. False for invisible building entry zones. */
    public boolean visible = true;

    /** Prompt text shown when player is nearby (e.g. "Enter", "Open Door"). */
    public String promptText = "Enter";

    public OBJ_Door(GamePanel gp) {
        super(gp);
        this.gp = gp;

        type = TYPE_OBSTACLE;
        name = "Door";
        down1 = setup("/res/objects/Door", gp.tileSize, gp.tileSize);
        collision = true;

        solidArea.x      = gp.tileSize / 8;          // 8 at 64px
        solidArea.y      = gp.tileSize * 3 / 16;     // 12 at 64px
        solidArea.width  = gp.tileSize * 3 / 4;      // 48 at 64px
        solidArea.height = gp.tileSize * 52 / 64;    // 52 at 64px
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;

        setDialogue();
    }

    /**
     * Create an invisible door (building entry zone).
     * Collision is enabled so the player can interact, but no sprite is drawn.
     */
    public OBJ_Door(GamePanel gp, boolean invisible) {
        super(gp);
        this.gp = gp;

        type = TYPE_OBSTACLE;
        name = "Door";
        collision = true;

        if (invisible) {
            visible = false;
            down1 = null; // Entity.draw() safely skips null sprites
        } else {
            down1 = setup("/res/objects/Door", gp.tileSize, gp.tileSize);
        }

        solidArea.x = 0;
        solidArea.y = 0;
        solidArea.width = gp.tileSize;
        solidArea.height = gp.tileSize;
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;

        setDialogue();
    }

    public void setDialogue() {
        ensureDialogues()[0][0] = "You need a key to open this door. [ EQUIP ]";
    }

    public void interact() {
        if (portal && location != null) {
            if (requiresKey && gp.player.hasKey <= 0) {
                startDialogue(this, 0);
                return;
            }
            if (requiresKey) gp.player.hasKey--;

            if (location.matches("\\d+,\\d+")) {
                // Local teleport within the same map (no map change needed)
                String[] parts = location.split(",");
                int col = Integer.parseInt(parts[0].trim());
                int row = Integer.parseInt(parts[1].trim());
                gp.player.worldX = col * gp.tileSize;
                gp.player.worldY = row * gp.tileSize;
            } else if (doorOpenFrames != null && doorOpenFrames.length > 0) {
                // Door has opening animation — play it first, then transition
                pendingLocation = location;
                pendingSpawnCol = spawnCol;
                pendingSpawnRow = spawnRow;
                doorOpening = true;
                doorAnimIndex = 0;
                doorAnimCounter = 0;
                gp.inputLocked = true;
            } else {
                // No animation — immediate transition
                beginTransition(location, spawnCol, spawnRow);
            }
        } else {
            startDialogue(this, 0);
        }
    }

    @Override
    public void update() {
        if (!doorOpening) return;

        doorAnimCounter++;
        if (doorAnimCounter >= doorAnimSpeed) {
            doorAnimCounter = 0;
            doorAnimIndex++;
            if (doorAnimIndex >= doorOpenFrames.length) {
                // Animation finished — start the map transition
                doorOpening = false;
                gp.inputLocked = false;
                down1 = doorOpenFrames[doorOpenFrames.length - 1]; // hold last frame
                beginTransition(pendingLocation, pendingSpawnCol, pendingSpawnRow);
                return;
            }
            // Update visible sprite to current animation frame
            down1 = doorOpenFrames[doorAnimIndex];
        }
    }

    /** Starts the fade transition to another map. */
    private void beginTransition(String loc, int col, int row) {
        gp.mapManager.doorEntryCol = worldX / gp.tileSize;
        gp.mapManager.doorEntryRow = worldY / gp.tileSize;
        gp.mapManager.nextSpawnId = (spawnId != null) ? spawnId : "";
        gp.startTransition(loc, col, row);
    }

    /**
     * Configure this door as a portal. `location` can be:
     * - a registered map id (eg "test")
     * - a path to a tmx (eg "/res/maps/test.tmx")
     * - a local coordinate in the form "col,row" (eg "5,10") to teleport within current map
     */
    public void setPortal(String location, boolean needKey) {
        this.portal = true;
        this.location = location;
        this.requiresKey = needKey;
        this.spawnCol = 1;
        this.spawnRow = 1;
    }

    /**
     * Backwards-compatible: non-portal map destination using explicit spawn coords.
     */
    public void setDestination(String mapId, int col, int row, boolean needKey) {
        setDestination(mapId, col, row, needKey, "");
    }

    /** Full destination: named spawn point overrides col/row when available. */
    public void setDestination(String mapId, int col, int row, boolean needKey, String namedSpawnId) {
        this.portal = true;
        this.location = mapId;
        this.requiresKey = needKey;
        this.spawnCol = col;
        this.spawnRow = row;
        this.spawnId = (namedSpawnId != null) ? namedSpawnId : "";
    }

    /**
     * Resize the door's collision area (and sprite, if visible) to match
     * the pixel dimensions drawn in Tiled. Call after construction.
     *
     * @param pixelW width  in scaled world-pixels (areaW from MapObjectLoader)
     * @param pixelH height in scaled world-pixels (areaH from MapObjectLoader)
     */
    public void setSize(int pixelW, int pixelH) {
        if (pixelW <= 0 || pixelH <= 0) return;

        // Full collision box covering the whole drawn area
        solidArea.x = 0;
        solidArea.y = 0;
        solidArea.width  = pixelW;
        solidArea.height = pixelH;
        solidAreaDefaultX = 0;
        solidAreaDefaultY = 0;

        // Rescale sprite to match (visible doors only)
        if (visible) {
            down1 = setup("/res/objects/Door", pixelW, pixelH);
        }
    }

}
