package monster;

import java.util.Random;
import entity.Entity;
import main.GamePanel;

public class MON_monster extends Entity {

    GamePanel gp;

    public MON_monster(GamePanel gp, int col, int row) {
        super(gp);
        this.gp = gp;
        
        type = 2; // Monster
        name = "Mummy";
        this.worldX = col * gp.tileSize;
        this.worldY = row * gp.tileSize;
        defaultSpeed = 1;
        speed = defaultSpeed;
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
        up1 = setup("/res/monster/Mummy/up/u1", gp.tileSize , gp.tileSize);
        up2 = setup("/res/monster/Mummy/up/u2", gp.tileSize , gp.tileSize);
        up3 = setup("/res/monster/Mummy/up/u3", gp.tileSize , gp.tileSize);
        // up4-6 loaded if you add them to Entity class or use logic to cycle more sprites

        down1 = setup("/res/monster/Mummy/down/j1", gp.tileSize , gp.tileSize);
        down2 = setup("/res/monster/Mummy/down/j2", gp.tileSize , gp.tileSize);
        down3 = setup("/res/monster/Mummy/down/j3", gp.tileSize , gp.tileSize);

        left1 = setup("/res/monster/Mummy/left/s1", gp.tileSize , gp.tileSize);
        left2 = setup("/res/monster/Mummy/left/s2", gp.tileSize , gp.tileSize);
        left3 = setup("/res/monster/Mummy/left/s3", gp.tileSize , gp.tileSize);

        right1 = setup("/res/monster/Mummy/right/d1", gp.tileSize , gp.tileSize);
        right2 = setup("/res/monster/Mummy/right/d2", gp.tileSize , gp.tileSize);
        right3 = setup("/res/monster/Mummy/right/d3", gp.tileSize , gp.tileSize);
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
                searchPath(goalCol, goalRow);
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