package monster;

import java.util.Random;

import entity.Entity;
import main.GamePanel;

public class MON_monster extends Entity{

    GamePanel gp;

    public MON_monster(GamePanel gp) {
        super(gp);

        this.gp = gp;
        
        type = 2;
        name = "Mummy";
        speed = 1;
        maxLife = 3;
        life = maxLife;
        attack = 1;
        defense = 0;
        exp = 2;

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
        up4 = setup("/res/monster/Mummy/up/u4", gp.tileSize , gp.tileSize);
        up5 = setup("/res/monster/Mummy/up/u5", gp.tileSize , gp.tileSize);
        up6 = setup("/res/monster/Mummy/up/u6", gp.tileSize , gp.tileSize);

        down1 = setup("/res/monster/Mummy/down/j1", gp.tileSize , gp.tileSize);
        down2 = setup("/res/monster/Mummy/down/j2", gp.tileSize , gp.tileSize);
        down3 = setup("/res/monster/Mummy/down/j3", gp.tileSize , gp.tileSize);
        down4 = setup("/res/monster/Mummy/down/j4", gp.tileSize , gp.tileSize);
        down5 = setup("/res/monster/Mummy/down/j5", gp.tileSize , gp.tileSize);
        down6 = setup("/res/monster/Mummy/down/j6", gp.tileSize , gp.tileSize);

        left1 = setup("/res/monster/Mummy/left/s1", gp.tileSize , gp.tileSize);
        left2 = setup("/res/monster/Mummy/left/s2", gp.tileSize , gp.tileSize);
        left3 = setup("/res/monster/Mummy/left/s3", gp.tileSize , gp.tileSize);
        left4 = setup("/res/monster/Mummy/left/s4", gp.tileSize , gp.tileSize);
        left5 = setup("/res/monster/Mummy/left/s5", gp.tileSize , gp.tileSize);
        left6 = setup("/res/monster/Mummy/left/s6", gp.tileSize , gp.tileSize);

        right1 = setup("/res/monster/Mummy/right/d1", gp.tileSize , gp.tileSize);
        right2 = setup("/res/monster/Mummy/right/d2", gp.tileSize , gp.tileSize);
        right3 = setup("/res/monster/Mummy/right/d3", gp.tileSize , gp.tileSize);
        right4 = setup("/res/monster/Mummy/right/d4", gp.tileSize , gp.tileSize);
        right5 = setup("/res/monster/Mummy/right/d5", gp.tileSize , gp.tileSize);
        right6 = setup("/res/monster/Mummy/right/d6", gp.tileSize , gp.tileSize);
    }

    public void setAction() {

        actionLockCounter++;

        if ( actionLockCounter == 120 ) {

            Random random = new Random();
            int i = random.nextInt(100)+1; // pick up a number from 1 <-> 100

            if ( i <= 25 ) {
                direction = "up";
            }
            if ( i > 25 && i <= 50 ) {
                direction = "down";
            }
            if ( i > 50 && i <= 75 ) {
                direction = "left";
            }
            if ( i > 75 && i <= 100 ) {
                direction = "right";
            }

            actionLockCounter = 0;
        }
    }
    public void damageReaction() {

        actionLockCounter = 0;
        direction = gp.player.direction;
    }

}
