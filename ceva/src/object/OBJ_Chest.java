package object;

import gfx.Color;
import gfx.GdxRenderer;
import gfx.Sprite;

import audio.SFX;
import entity.Entity;
import main.GamePanel;

public class OBJ_Chest extends Entity {

    // Requirement to open (empty string = no requirement)
    public String requiredItem = "";
    public boolean consumeItem = false;

    private boolean opening = false;
    private int openAnimFrame = 0;
    private int openAnimCounter = 0;
    private static final int OPEN_ANIM_SPEED = 8; // frames between each animation step
    private Sprite[] openFrames; // animation frames (loaded from spritesheet or built from 2 images)

    public OBJ_Chest(GamePanel gp) {
        super(gp);

        type = TYPE_OBSTACLE;
        name = "Chest";
        collision = true;

        solidArea.x      = gp.tileSize / 8;          // 8 at 64px
        solidArea.y      = gp.tileSize * 3 / 16;     // 12 at 64px
        solidArea.width  = gp.tileSize * 3 / 4;      // 48 at 64px
        solidArea.height = gp.tileSize * 52 / 64;    // 52 at 64px
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;

        // Default 4-frame animation (closed -> opening -> opening -> opened)
        loadOpenAnimation("/res/objects/chests", 4);
        down1 = image;
    }

    /**
     * Load a multi-frame opening animation from a horizontal spritesheet.
     * Each frame is tileSize × tileSize, laid out left-to-right.
     */
    public void loadOpenAnimation(String sheetPath, int frameCount) {
        Sprite sheet = setup(sheetPath, gp.tileSize * frameCount, gp.tileSize);
        if (sheet != null && frameCount > 1) {
            openFrames = new Sprite[frameCount];
            int fw = gp.tileSize;
            for (int i = 0; i < frameCount; i++) {
                openFrames[i] = sheet.getSubimage(i * fw, 0, fw, gp.tileSize);
            }
            image  = openFrames[0];
            image1 = openFrames[frameCount - 1];
            down1  = image;
        }
    }

    public void setDialogue() {
        // Dialogue set 3 is still used for the requirement check (blocks interaction)
        ensureDialogues()[3][0] = "You need a " + requiredItem + " to open this chest.";
    }

    public void setLoot(Entity loot) {
        this.loot = loot;
        setDialogue();
    }

    @Override
    public void interact() {
        if (opened) {
            gp.ui.addMessage("The chest is empty.", Color.GRAY);
            return;
        }

        if (!requiredItem.isEmpty()) {
            int idx = gp.player.searchItemInInventory(requiredItem);
            if (idx == 999) {
                // Player doesn't have the required item — use dialogue for this
                startDialogue(this, 3);
                return;
            }
            if (consumeItem && idx < gp.player.inventory.size()) {
                gp.player.inventory.remove(idx);
            }
        }

        gp.playSE(SFX.DOOR);
        opening = true;
        openAnimFrame = 0;
        openAnimCounter = 0;
    }

    /** Called after the opening animation finishes to give loot. */
    private void giveContents() {
        opened = true;
        down1 = image1;

        if (loot == null) {
            gp.ui.addMessage("The chest is empty.", Color.GRAY);
            return;
        }

        if (!gp.player.canObtainItem(loot)) {
            gp.ui.addMessage("You found " + loot.name + ", but your inventory is full!", new Color(255, 100, 100));
        } else {
            gp.ui.addMessage("You obtained " + loot.name + "!", new Color(255, 230, 100));
            loot = null;
        }
    }

    @Override
    public void draw(GdxRenderer g2) {
        int screenX = worldX - gp.player.worldX + gp.player.screenX;
        int screenY = worldY - gp.player.worldY + gp.player.screenY;

        if (worldX + gp.tileSize > gp.player.worldX - gp.player.screenX &&
            worldX - gp.tileSize < gp.player.worldX + (gp.screenWidth - gp.player.screenX) &&
            worldY + gp.tileSize > gp.player.worldY - gp.player.screenY &&
            worldY - gp.tileSize < gp.player.worldY + (gp.screenHeight - gp.player.screenY)) {

            if (opening) {
                openAnimCounter++;
                if (openAnimCounter >= OPEN_ANIM_SPEED) {
                    openAnimCounter = 0;
                    openAnimFrame++;
                    if (openAnimFrame >= openFrames.length) {
                        opening = false;
                        giveContents();
                    }
                }
            }

            Sprite sprite;
            if (opening && openAnimFrame < openFrames.length) {
                sprite = openFrames[openAnimFrame];
            } else {
                sprite = down1;
            }

            if (sprite != null) {
                g2.drawImage(sprite, screenX, screenY);
            }
        }
    }
}
