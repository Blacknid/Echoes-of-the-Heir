package monster;

import entity.Entity;
import java.awt.image.BufferedImage;
import java.util.Random;
import main.GamePanel;

public class MON_monster extends Entity {

    GamePanel gp;
    // OPTIMIZATION: Single Random instance instead of creating a new one every frame
    private static final Random random = new Random();
    private static final String[] DIRECTIONS = {"up", "down", "left", "right"};

    public MON_monster(GamePanel gp, int col, int row) {
        super(gp);
        this.gp = gp;
        
        type = 2;
        name = "Mummy";
        collision = true;
        this.worldX = col * gp.tileSize;
        this.worldY = row * gp.tileSize;
        defaultSpeed = 1;
        speed = defaultSpeed;
        walkFrameCount = 6;
        maxLife = 6;
        life = maxLife;
        attack = 2;
        defense = 0;
        exp = 3;

        solidArea.x = 12;
        solidArea.y = 8;
        solidArea.width = 40;
        solidArea.height = 48;
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;
        
        getImage();
    }

    public void getImage() {
        int[] framesPerRow = {6, 6, 6, 6};
        BufferedImage[][] frames = loadSheetVariable("/res/monster/Monster_walking-sheet", framesPerRow);
        down1 = frames[0][0];   down2 = frames[0][1];   down3 = frames[0][2];
        down4 = frames[0][3];   down5 = frames[0][4];   down6 = frames[0][5];
        left1 = frames[1][0];   left2 = frames[1][1];   left3 = frames[1][2];
        left4 = frames[1][3];   left5 = frames[1][4];   left6 = frames[1][5];
        right1 = frames[2][0];   right2 = frames[2][1];   right3 = frames[2][2];
        right4 = frames[2][3];   right5 = frames[2][4];   right6 = frames[2][5];
        up1 = frames[3][0];  up2 = frames[3][1];  up3 = frames[3][2];
        up4 = frames[3][3];  up5 = frames[3][4];  up6 = frames[3][5];
    }

    @Override
    public void setAction() {
        // 1. If currently fleeing, run away from the player
        if (fleeing) {
            fleeCounter++;
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
        
        if (onPath) {
            int loseInterestRange = gp.tileSize * 8;
            if (!isPlayerInRange(loseInterestRange)) {
                onPath = false;
            } else {
                int goalCol = gp.player.getTileCol();
                int goalRow = gp.player.getTileRow();
                
                // When very close, use direct chase for snappy movement
                int closeDist = gp.tileSize * 2;
                int absDx = Math.abs(getCenterX() - gp.player.getCenterX());
                int absDy = Math.abs(getCenterY() - gp.player.getCenterY());
                if (absDx < closeDist && absDy < closeDist) {
                    directChase(goalCol, goalRow);
                } else {
                    searchPath(goalCol, goalRow);
                }
            }
        } else {
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
        onPath = true;
        if (life <= maxLife / 2) {
            fleeing = true;
            onPath = false;
            fleeCounter = 0;
            speed = defaultSpeed + 1;
        }
    }

    private void randomMovement() {
        actionLockCounter++;
        if (actionLockCounter >= 120) {
            direction = DIRECTIONS[random.nextInt(4)];
            actionLockCounter = 0;
        }
    }
}