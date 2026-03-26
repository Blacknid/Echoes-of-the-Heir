package monster;

import entity.Entity;
import java.util.Random;
import main.GamePanel;

public class MON_monster extends Entity {

    GamePanel gp;
    // OPTIMIZATION: Single Random instance instead of creating a new one every frame
    private static final Random random = new Random();
    private static final int[] DIRECTIONS = {DIR_DOWN, DIR_LEFT, DIR_RIGHT, DIR_UP};

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
        int[] framesPerRow = {6, 6, 6, 6}; // down, left, right, up (matches DIR_DOWN=0,LEFT=1,RIGHT=2,UP=3)
        walkFrames = loadSheetVariable("/res/monster/Monster_walking-sheet", framesPerRow);
    }

    @Override
    public void setAction() {
        // 1. If currently fleeing, run away from the player
        if (fleeing) {
            fleeCounter++;
            int dx = worldX - gp.player.worldX;
            int dy = worldY - gp.player.worldY;
            if (Math.abs(dx) > Math.abs(dy)) {
                direction = (dx > 0) ? DIR_RIGHT : DIR_LEFT;
            } else {
                direction = (dy > 0) ? DIR_DOWN : DIR_UP;
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