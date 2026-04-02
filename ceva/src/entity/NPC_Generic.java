package entity;

import java.awt.image.BufferedImage;
import java.util.Random;

import main.GamePanel;

/**
 * Data-driven NPC — all dialogue, sprite, behavior, and memory-fragment
 * properties are set externally (typically by MapObjectLoader reading Tiled
 * properties) rather than hardcoded.
 *
 * Tiled properties consumed by MapObjectLoader → NPC_Generic:
 *   sprite           (String)  resource path without extension, e.g. "/res/npc/Villager_walk-sheet"
 *   dialogue_S_L     (String)  dialogue set S, line L  (e.g. dialogue_0_0, dialogue_1_2)
 *   memoryFragmentId (String)  fragment id this NPC gives on claim
 *   memoryFragmentName(String) display name for the fragment
 *   memoryText0..4   (String)  flashback text lines
 *   fragmentTrigger  (String)  "talk" (default) | "defeat" | "quest"
 *   dialogueChoices  (String)  pipe-separated choice labels, e.g. "Accept|Refuse"
 *   choiceNextSet    (String)  pipe-separated set indices, e.g. "2|3"
 */
public class NPC_Generic extends Entity {

    /** Sprite path set by MapObjectLoader; loaded lazily in getImage(). */
    public String spritePath = null;
    private boolean imageLoaded = false;

    public NPC_Generic(GamePanel gp) {
        super(gp);

        direction = DIR_DOWN;
        speed = 1;
        walkFrameCount = 6;
        collision = true;

        dialogueSet = -1;

        solidArea.x = 20;
        solidArea.y = 22;
        solidArea.width = 24;
        solidArea.height = 22;
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;
    }

    /** Call after setting spritePath to load the sheet. Safe to call multiple times. */
    public void getImage() {
        if (imageLoaded || spritePath == null) return;
        try {
            int[] framesPerRow = {6, 6, 6, 6};
            BufferedImage[][] frames = loadSheetVariable(spritePath, framesPerRow);
            walkFrames = new BufferedImage[4][];
            walkFrames[DIR_DOWN]  = frames[0];
            walkFrames[DIR_UP]    = frames[1];
            walkFrames[DIR_LEFT]  = frames[2];
            walkFrames[DIR_RIGHT] = frames[3];
            imageLoaded = true;
        } catch (Exception e) {
            System.out.println("NPC_Generic: Failed to load sprite '" + spritePath + "': " + e.getMessage());
        }
    }

    @Override
    public void setAction() {
        if (onPath) {
            int goalCol = (walkToCol >= 0) ? walkToCol : 0;
            int goalRow = (walkToRow >= 0) ? walkToRow : 0;
            searchPath(goalCol, goalRow);
        } else if (!staticNPC && !guardMode) {
            actionLockCounter++;
            if (actionLockCounter == 120) {
                Random random = new Random();
                int i = random.nextInt(100) + 1;
                if (i <= 25)      direction = DIR_DOWN;
                else if (i <= 50) direction = DIR_UP;
                else if (i <= 75) direction = DIR_LEFT;
                else              direction = DIR_RIGHT;
                actionLockCounter = 0;
            }
        }
    }

    @Override
    public void speak() {
        facePlayer();

        // Item-conditional dialogue override
        if (checkRequiredItemDialogue()) return;

        // Memory fragment claim: if this NPC has one and it's not yet collected
        if (memoryFragmentId != null && !memoryFragmentClaimed) {
            boolean canClaim = true;
            // Check prerequisites
            if (fragmentRequiredCount > 0 && gp.memoryJournal != null
                    && gp.memoryJournal.getCount() < fragmentRequiredCount) {
                canClaim = false;
            }
            if (canClaim && memoryFragmentClaimed) canClaim = false;

            if (canClaim) {
                claimFragment();
                return;
            }
        }

        // Step chain (multi-step pathfinding dialogue)
        if (!npcSteps.isEmpty()) {
            int dlg = (walkToDialogueSet >= 0) ? walkToDialogueSet : 0;
            startDialogue(this, dlg);
            if (walkToCol >= 0 && walkToRow >= 0) onPath = true;
            return;
        }

        // Post-walk dialogue
        if (walkToCol < 0 && walkToDialogueSet >= 0) {
            startDialogue(this, walkToDialogueSet);
            return;
        }

        // Default: advance dialogue set on each interaction
        dialogueSet++;
        String[][] d = ensureDialogues();
        if (dialogueSet >= d.length || d[dialogueSet] == null || d[dialogueSet][0] == null) {
            dialogueSet = 0; // loop back
        }
        startDialogue(this, dialogueSet);
    }

    /** Claims the memory fragment this NPC carries and triggers the flashback. */
    private void claimFragment() {
        memoryFragmentClaimed = true;

        if (gp.memoryJournal != null) {
            gp.memoryJournal.collect(memoryFragmentId);
        }
        if ("frag_cave".equals(memoryFragmentId) && gp.questManager != null) {
            gp.questManager.progress("find_exit", 1);
        }

        // Trigger flashback effect
        if (gp.memoryFlashback != null && memoryFragmentText != null) {
            data.MemoryJournal.MemoryFragment frag = (gp.memoryJournal != null)
                    ? gp.memoryJournal.getFragment(memoryFragmentId) : null;
            if (frag != null) {
                gp.memoryFlashback.trigger(frag);
            }
        }
    }
}
