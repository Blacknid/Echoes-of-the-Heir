package entity;

import java.awt.image.BufferedImage;
import java.util.Random;

import main.GamePanel;

public class NPC_Alucard extends Entity{

    public NPC_Alucard(GamePanel gp) {
        super(gp);

        direction = DIR_DOWN;
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
        int[] framesPerRow = {6, 6, 6, 6}; // sheet rows: down, up, left, right
        BufferedImage[][] frames = loadSheetVariable("/res/npc/Alucard_walking-sheet", framesPerRow);
        // Map rows to direction indices (sheet order differs from DIR_* order)
        walkFrames = new BufferedImage[4][];
        walkFrames[DIR_DOWN]  = frames[0]; // row 0 = down
        walkFrames[DIR_UP]    = frames[1]; // row 1 = up
        walkFrames[DIR_LEFT]  = frames[2]; // row 2 = left
        walkFrames[DIR_RIGHT] = frames[3]; // row 3 = right
    }
    public void setDialogue() {

        ensureDialogues()[0][0] = "Hello, spirit !";
        ensureDialogues()[0][1] = "I see, so you've come for the treasure?";
        ensureDialogues()[0][2] = "You should start by doing some research.";
        ensureDialogues()[0][3] = "I can help you find the first key.\nYou should go South.";

        ensureDialogues()[1][0] = "The Dark Heart is hidden deep\nwithin the castle.";
        ensureDialogues()[1][1] = "Only those worthy can possess it.";
        ensureDialogues()[1][2] = "Prove your worth by reaching\nLevel 3.";
        ensureDialogues()[1][3] = "Good luck on your journey!";

        ensureDialogues()[2][0] = "You have done well to reach Level 3.";
        ensureDialogues()[2][1] = "You are now worthy to possess\nthe Dark Heart.";
        ensureDialogues()[2][2] = "Take it and fulfill your destiny.";

        ensureDialogues()[3][0] = "I see you've defeated the castle's guardian and claimed the Dark Heart.";

    }
    public void setAction() {

        if ( onPath ) {

            int goalCol = (walkToCol >= 0) ? walkToCol : 44;
            int goalRow = (walkToRow >= 0) ? walkToRow : 36;

            searchPath(goalCol, goalRow);
        }
        else {

            actionLockCounter++;

            if ( actionLockCounter == 120 ) {

                Random random = new Random();
                int i = random.nextInt(100)+1; // pick up a number from 1 <-> 100

                if ( i <= 25 ) {
                    direction = DIR_UP;
                }   
                if ( i > 25 && i <= 50 ) {
                    direction = DIR_DOWN;
                }
                if ( i > 50 && i <= 75 ) {
                    direction = DIR_LEFT;
                }
                if ( i > 75 && i <= 100 ) {
                    direction = DIR_RIGHT;
                }

            actionLockCounter = 0;
            }
        }
    }
    public void speak() {

        facePlayer();

        // ── ITEM-CONDITIONAL DIALOGUE: override everything if the player has the required item ──
        if (checkRequiredItemDialogue()) return;

        // ── STEP CHAIN: if npcSteps are defined, use them instead of the hardcoded logic ──
        if (!npcSteps.isEmpty()) {
            int dlg  = (walkToDialogueSet >= 0) ? walkToDialogueSet : 0;
            startDialogue(this, dlg);

            // If the current step has a walk target, start walking after dialogue
            if (walkToCol >= 0 && walkToRow >= 0) {
                onPath = true;
            }
            return;
        }

        // ── LEGACY: single walkTo support (no step chain) ──
        // Post-walk: walkToCol was cleared on arrival; use the designated dialogue set permanently
        if (walkToCol < 0 && walkToDialogueSet >= 0) {
            startDialogue(this, walkToDialogueSet);
            return;
        }

        if ( gp.player.level < 3 && gp.player.hasKey == 0 )
            startDialogue(this, 0);
        else if ( gp.player.level >= 3 && gp.csManager.sceneNum >= gp.csManager.ending )
            startDialogue(this, 2);
        else
            startDialogue(this, 1);

        if (walkToCol >= 0) onPath = true;
    }
}
