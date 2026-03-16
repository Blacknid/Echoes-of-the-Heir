package entity;

import java.awt.image.BufferedImage;
import java.util.Random;
import main.GamePanel;

public class NPC_Alucard extends Entity{

    public NPC_Alucard(GamePanel gp) {
        super(gp);

        direction = "down";
        speed = 1;
        walkFrameCount = 6;
        collision = true; // Blocks player movement

        dialogueSet = -1;
        
        // HITBOX: Same as player for consistent collision
        solidArea.x = 20;  // 20px left + 24px width + 20px right = 64
        solidArea.y = 22;  // Slight top offset for upper body
        solidArea.width = 24;  // Covers body width
        solidArea.height = 22; // Covers torso height
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;

        getImage();
        setDialogue();
    }
    public void getImage() {
    
        int[] framesPerRow = {6, 6, 6, 6}; // down, up, left, right
        BufferedImage[][] frames = loadSheetVariable("/res/npc/Alucard_walking-sheet", framesPerRow);
        // Assign down frames
        down1 = frames[0][0];   down2 = frames[0][1];   down3 = frames[0][2];
        down4 = frames[0][3];   down5 = frames[0][4];   down6 = frames[0][5];

        // Assign up frames
        up1 = frames[1][0];   up2 = frames[1][1];   up3 = frames[1][2];
        up4 = frames[1][3];   up5 = frames[1][4];   up6 = frames[1][5];

        // Assign left frames
        left1 = frames[2][0];   left2 = frames[2][1];   left3 = frames[2][2];
        left4 = frames[2][3];   left5 = frames[2][4];   left6 = frames[2][5];

        // Assign right frames
        right1 = frames[3][0];  right2 = frames[3][1];
        right3 = frames[3][2];  right4 = frames[3][3];
        right5 = frames[3][4];  right6 = frames[3][5];
        
    }
    public void setDialogue() {

        dialogues[0][0] = "Hello, spirit !";
        dialogues[0][1] = "I see, so you've come for the treasure?";
        dialogues[0][2] = "You should start by doing some research.";
        dialogues[0][3] = "I can help you find the first key.\nYou should go South.";

        dialogues[1][0] = "The Dark Heart is hidden deep\nwithin the castle.";
        dialogues[1][1] = "Only those worthy can possess it.";
        dialogues[1][2] = "Prove your worth by reaching\nLevel 3.";
        dialogues[1][3] = "Good luck on your journey!";

        dialogues[2][0] = "You have done well to reach Level 3.";
        dialogues[2][1] = "You are now worthy to possess\nthe Dark Heart.";
        dialogues[2][2] = "Take it and fulfill your destiny.";

    }
    public void setAction() {

        if ( onPath == true ) {

            int goalCol = 44;
            int goalRow = 36;

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
        if ( gp.player.level < 3 && gp.player.hasKey == 0 )
            startDialogue(this, 0);
        else if ( gp.player.level <= 3  && gp.player.hasKey >= 0 )
            startDialogue(this, 1);
        else if ( gp.player.level >= 3 && gp.csManager.sceneNum >= gp.csManager.ending )
            startDialogue(this, 2);

        onPath = true;

    }
}
