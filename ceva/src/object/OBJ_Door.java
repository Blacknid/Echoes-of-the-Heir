package object;

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

    public OBJ_Door(GamePanel gp) {
        super(gp);
        this.gp = gp;

        type = type_obstacle;
        name = "Door";
        down1 = setup("/res/objects/Door", gp.tileSize, gp.tileSize);
        collision = true;

        solidArea.x = 8;
        solidArea.y = 12;
        solidArea.width = 48;
        solidArea.height = 52;
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
            } else {
                // Map change — use smooth fade transition via the safe entry point
                // Save this door's tile position so the return path can lead back here
                gp.mapManager.doorEntryCol = worldX / gp.tileSize;    // convert pixel position to tile
                gp.mapManager.doorEntryRow = worldY / gp.tileSize;    // convert pixel position to tile
                // Named spawn point: resolved on the target map after it loads
                gp.mapManager.nextSpawnId = (spawnId != null) ? spawnId : "";
                gp.startTransition(location, spawnCol, spawnRow);
            }
        } else {
            startDialogue(this, 0);
        }
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

}
