package monster;

import java.util.Random;
import entity.Entity;
import main.GamePanel;

public class MON_monster extends Entity {

    GamePanel gp;

    public MON_monster(GamePanel gp) {
        super(gp);
        this.gp = gp;
        
        type = 2; // Monster
        name = "Mummy";
        speed = 1;
        maxLife = 3;
        life = maxLife;
        attack = 1;
        defense = 0;
        exp = 2;

        // Hitbox setup
        solidArea.x = 3;
        solidArea.y = 18;
        solidArea.width = 42;
        solidArea.height = 30;
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
        
        if (onPath == true) {
            // Check if stuck on path
            int goalCol = gp.player.getTileCol();
            int goalRow = gp.player.getTileRow();
            searchPath(goalCol, goalRow);
        }
        else {
             // Aggro check
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
