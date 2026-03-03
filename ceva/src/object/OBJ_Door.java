package object;

import entity.Entity;
import main.GamePanel;

public class OBJ_Door extends Entity {

    GamePanel gp;
    // map transition / portal info
    public boolean portal = false; // when true, this door uses `location`
    public String location = null; // either a map id / path (eg "/res/maps/test.tmx" or "test") or "col,row" for local coords
    public boolean requiresKey = false;

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
        dialogues[0][0] = "You need a key to open this door. [ EQUIP ]";
    }

    public void interact() {
        if (portal && location != null) {
            if (requiresKey && gp.player.hasKey <= 0) {
                startDialogue(this, 0);
                return;
            }
            if (requiresKey) gp.player.hasKey--;

            if (location.contains(".tmx") || location.startsWith("/res/maps/") || gp.mapRegistry.containsKey(location)) {
                gp.changeMap(location, 1, 1);
            } else if (location.matches("\\d+,\\d+")) {
                String[] parts = location.split(",");
                int col = Integer.parseInt(parts[0].trim());
                int row = Integer.parseInt(parts[1].trim());
                gp.player.worldX = col * gp.tileSize;
                gp.player.worldY = row * gp.tileSize;
            } else {
                gp.changeMap(location, 1, 1);
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
    }

    /**
     * Backwards-compatible: non-portal map destination using explicit spawn coords.
     */
    public void setDestination(String mapId, int col, int row, boolean needKey) {
        this.portal = true;
        if (mapId != null && (mapId.contains(".tmx") || mapId.startsWith("/res/maps/"))) {
            this.location = mapId; // path
        } else {
            this.location = mapId; // map id
        }
        this.requiresKey = needKey;
        // Note: spawn coords are not stored on the door; call gp.changeMap directly for custom spawn positions if needed.
    }

}
