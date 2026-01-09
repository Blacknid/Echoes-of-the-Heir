package entity;

import java.util.Random;

import main.GamePanel;

public class NPC_Alucard extends Entity{

    public NPC_Alucard(GamePanel gp) {
        super(gp);

        direction = "down";
        speed = 1;

        getImage();
        setDialogue();
    }
    public void getImage() {
    
        up1 = setup("/res/NPC/b.up/s1", gp.tileSize, gp.tileSize);
        up2 = setup("/res/NPC/b.up/s2", gp.tileSize, gp.tileSize);
        up3 = setup("/res/NPC/b.up/s3", gp.tileSize, gp.tileSize);
        down1 = setup("/res/NPC/b.front/f1", gp.tileSize, gp.tileSize);
        down2 = setup("/res/NPC/b.front/f2", gp.tileSize, gp.tileSize);
        down3 = setup("/res/NPC/b.front/f3", gp.tileSize, gp.tileSize);
        right1 = setup("/res/NPC/b.right/r1", gp.tileSize, gp.tileSize);
        right2 = setup("/res/NPC/b.right/r2", gp.tileSize, gp.tileSize);
        right3 = setup("/res/NPC/b.right/r3", gp.tileSize, gp.tileSize);
        left1 = setup("/res/NPC/b.left/l1", gp.tileSize, gp.tileSize);
        left2 = setup("/res/NPC/b.left/l2", gp.tileSize, gp.tileSize);
        left3 = setup("/res/NPC/b.left/l3", gp.tileSize, gp.tileSize);
    }
    public void setDialogue() {

        dialogues[0][0] = "Hello, spirit !";
        dialogues[0][1] = "I see, so you've come for the treasure?";
        dialogues[0][2] = "You should start by doing some research.";
        dialogues[0][3] = "I can help you find the first key.\nYou should go South.";

    }
    public void setAction() {

        if ( onPath == true ) {

            int goalCol = 44;
            int goalRow = 27;

            searchPath(goalCol, goalRow);
        }
        else {

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
    }
    public void speak() {

        facePlayer();
        startDialogue(this, dialogueSet);    

        onPath = true;

    }
}
