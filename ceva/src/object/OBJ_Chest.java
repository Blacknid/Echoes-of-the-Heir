package object;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import audio.SFX;
import entity.Entity;
import main.GamePanel;

public class OBJ_Chest extends Entity {

    // Requirement to open (empty string = no requirement)
    public String requiredItem = "";
    public boolean consumeItem = false;

    // Opening animation state
    private boolean opening = false;
    private int openAnimFrame = 0;
    private int openAnimCounter = 0;
    private static final int OPEN_ANIM_SPEED = 8; // frames between each animation step
    private BufferedImage[] openFrames; // animation frames (loaded from spritesheet or built from 2 images)

    public OBJ_Chest(GamePanel gp) {
        super(gp);

        type = TYPE_OBSTACLE;
        name = "Chest";
        image  = setup("/res/objects/Chest_closed", gp.tileSize, gp.tileSize);
        image1 = setup("/res/objects/Chest_opened", gp.tileSize, gp.tileSize);
        down1 = image;
        collision = true;

        solidArea.x      = gp.tileSize / 8;          // 8 at 64px
        solidArea.y      = gp.tileSize * 3 / 16;     // 12 at 64px
        solidArea.width  = gp.tileSize * 3 / 4;      // 48 at 64px
        solidArea.height = gp.tileSize * 52 / 64;    // 52 at 64px
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;

        // Default 2-frame animation (closed → opened)
        openFrames = new BufferedImage[]{ image, image1 };
    }

    /**
     * Load a multi-frame opening animation from a horizontal spritesheet.
     * Each frame is tileSize × tileSize, laid out left-to-right.
     */
    public void loadOpenAnimation(String sheetPath, int frameCount) {
        BufferedImage sheet = setup(sheetPath, gp.tileSize * frameCount, gp.tileSize);
        if (sheet != null && frameCount > 1) {
            openFrames = new BufferedImage[frameCount];
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

        // Check item requirement
        if (!requiredItem.isEmpty()) {
            int idx = gp.player.searchItemInInventory(requiredItem);
            if (idx == 999) {
                // Player doesn't have the required item — use dialogue for this
                startDialogue(this, 3);
                return;
            }
            // Consume the item if configured
            if (consumeItem && idx < gp.player.inventory.size()) {
                gp.player.inventory.remove(idx);
            }
        }

        // Start the opening animation
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
    public void draw(Graphics2D g2) {
        int screenX = worldX - gp.player.worldX + gp.player.screenX;
        int screenY = worldY - gp.player.worldY + gp.player.screenY;

        if (worldX + gp.tileSize > gp.player.worldX - gp.player.screenX &&
            worldX - gp.tileSize < gp.player.worldX + gp.player.screenX &&
            worldY + gp.tileSize > gp.player.worldY - gp.player.screenY &&
            worldY - gp.tileSize < gp.player.worldY + gp.player.screenY) {

            // Drive opening animation
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

            BufferedImage sprite;
            if (opening && openAnimFrame < openFrames.length) {
                sprite = openFrames[openAnimFrame];
            } else {
                sprite = down1;
            }

            if (sprite != null) {
                g2.drawImage(sprite, screenX, screenY, null);
            }
        }
    }
}
