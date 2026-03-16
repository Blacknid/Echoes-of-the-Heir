package monster;

import entity.Entity;
import java.awt.image.BufferedImage;
import java.util.Random;
import main.GamePanel;

public class MON_monster extends Entity {

    GamePanel gp;

    public MON_monster(GamePanel gp, int col, int row) {
        super(gp);
        this.gp = gp;
        
        type = 2; // Monster
        name = "Mummy";
        collision = true; // Blocks player movement
        this.worldX = col * gp.tileSize;
        this.worldY = row * gp.tileSize;
        defaultSpeed = 1;
        speed = defaultSpeed;
        walkFrameCount = 6;
        maxLife = 3;
        life = maxLife;
        attack = 1;
        defense = 0;
        exp = 2;

        solidArea.x = 12;  // Balanced left/right padding (12 pixels each side = 40 width)
        solidArea.y = 8;   // Top padding for head clearance
        solidArea.width = 40;  // 12 + 40 + 12 = 64 (full sprite width)
        solidArea.height = 48; // Covers body from neck to feet (64 - 8 - 8 = 48)
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;
        
        getImage();
    }

    public void getImage() {
        
        int[] framesPerRow = {6, 6, 6, 6}; // down, left, right, up
        BufferedImage[][] frames = loadSheetVariable("/res/monster/Monster_walking-sheet", framesPerRow);
        // Assign down frames
        down1 = frames[0][0];   down2 = frames[0][1];   down3 = frames[0][2];
        down4 = frames[0][3];   down5 = frames[0][4];   down6 = frames[0][5];

        // Assign left frames
        left1 = frames[1][0];   left2 = frames[1][1];   left3 = frames[1][2];
        left4 = frames[1][3];   left5 = frames[1][4];   left6 = frames[1][5];

        // Assign right frames
        right1 = frames[2][0];   right2 = frames[2][1];   right3 = frames[2][2];
        right4 = frames[2][3];   right5 = frames[2][4];   right6 = frames[2][5];

        // Assign up frames
        up1 = frames[3][0];  up2 = frames[3][1];  up3 = frames[3][2];
        up4 = frames[3][3];  up5 = frames[3][4];  up6 = frames[3][5];

    }

    @Override
    public void setAction() {
        // 1. If we're currently fleeing, run directly away from the player
        if (fleeing) {
            fleeCounter++;
            // calculate direction opposite of player centre
            int dx = worldX - gp.player.worldX;
            int dy = worldY - gp.player.worldY;
            if (Math.abs(dx) > Math.abs(dy)) {
                direction = (dx > 0) ? "right" : "left";
            } else {
                direction = (dy > 0) ? "down" : "up";
            }
            if (fleeCounter > fleeDuration) {
                fleeing = false;
                fleeCounter = 0;
                speed = defaultSpeed;
            }
            return;
        }
        
        if (onPath == true) {
            // If player manages to run farther than a bigger radius, give up
            int loseInterestRange = gp.tileSize * 8;
            if (!isPlayerInRange(loseInterestRange)) {
                onPath = false;
            } else {
                // continue chasing using pathfinding
                int goalCol = gp.player.getTileCol();
                int goalRow = gp.player.getTileRow();
                
                // When very close (within 2 tiles), use direct chase for snappy movement
                int closeDist = gp.tileSize * 2;
                int absDx = Math.abs(getCenterX() - gp.player.getCenterX());
                int absDy = Math.abs(getCenterY() - gp.player.getCenterY());
                if (absDx < closeDist && absDy < closeDist) {
                    directChase(goalCol, goalRow);
                } else {
                    searchPath(goalCol, goalRow);
                }
            }
        }
        else {
             // Aggro check: start chasing when player enters range
             int aggroRange = gp.tileSize * 6;
             if (isPlayerInRange(aggroRange)) {
                 onPath = true;
             } else {
                 randomMovement();
             }
        }
    }

    public void damageReaction() {
        actionLockCounter = 0;
        // always start chasing when hit
        onPath = true;
        // if health is low, trigger a brief flee state instead
        if (life <= maxLife / 2) {
            fleeing = true;
            onPath = false;
            fleeCounter = 0;
            // give them a brief speed boost while escaping
            speed = defaultSpeed + 1;
        }
    }

    private void randomMovement() {
        actionLockCounter++;
        if (actionLockCounter >= 120) {
            Random random = new Random();
            int i = random.nextInt(100) + 1; // 1 to 100

            if (i <= 25) direction = "up";
            else if (i <= 50) direction = "down";
            else if (i <= 75) direction = "left";
            else direction = "right";

            actionLockCounter = 0;
        }
    }
}