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
 * dialogue_0_0 = "Uhm... hello?"
 * dialogue_0_1 = "I don't really remember much, but I think I got hurt near here..."
 * dialogue_0_2 = "Please take my sword, it might help you on your journey."
 * dialogue_0_3 = "... Help me later too, I don't want to be alone here."
 * 
 */
public class NPC_Generic extends Entity {

    /** Sprite path set by MapObjectLoader; loaded lazily in getImage(). */
    public String spritePath = null;
    /** Idle sprite sheet path; loaded lazily in getImage(). */
    public String idleSpritePath = null;
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

    /** Call after setting spritePath / idleSpritePath to load the sheets. Safe to call multiple times. */
    public void getImage() {
        if (imageLoaded) return;
        // Walk sprite sheet
        if (spritePath != null) {
            try {
                int[] framesPerRow = {6, 6, 6, 6};
                BufferedImage[][] frames = loadSheetVariable(spritePath, framesPerRow);
                walkFrames = new BufferedImage[4][];
                walkFrames[DIR_DOWN]  = frames[0];
                walkFrames[DIR_UP]    = frames[1];
                walkFrames[DIR_LEFT]  = frames[2];
                walkFrames[DIR_RIGHT] = frames[3];
            } catch (Exception e) {
                System.out.println("NPC_Generic: Failed to load walk sprite '" + spritePath + "': " + e.getMessage());
            }
        }
        // Idle sprite sheet — row order: down / left / right / up
        if (idleSpritePath != null) {
            try {
                java.io.InputStream is = getClass().getResourceAsStream(idleSpritePath + ".png");
                if (is != null) {
                    BufferedImage raw = javax.imageio.ImageIO.read(is);
                    is.close();
                    int rows = 4;
                    int cellSize = raw.getHeight() / rows; // cell height (and width, assuming square)
                    int maxCols  = raw.getWidth()  / cellSize;

                    // Count actual (non-transparent) frames per row
                    int[] actualFrames = new int[rows];
                    for (int r = 0; r < rows; r++) {
                        actualFrames[r] = maxCols;
                        while (actualFrames[r] > 1) {
                            int fx = (actualFrames[r] - 1) * cellSize;
                            int fy = r * cellSize;
                            if (hasVisiblePixel(raw, fx, fy, cellSize, cellSize)) break;
                            actualFrames[r]--;
                        }
                    }

                    // Find the global visible-content bounding box across ALL frames/rows
                    // so the character is cropped out of its transparent padding and scaled to tileSize.
                    int cropMinY = cellSize, cropMaxY = -1;
                    int cropMinX = cellSize, cropMaxX = -1;
                    for (int r = 0; r < rows; r++) {
                        for (int f = 0; f < actualFrames[r]; f++) {
                            int ox = f * cellSize, oy = r * cellSize;
                            for (int py = 0; py < cellSize; py++) {
                                for (int px = 0; px < cellSize; px++) {
                                    if ((raw.getRGB(ox + px, oy + py) >>> 24) > 10) {
                                        if (py < cropMinY) cropMinY = py;
                                        if (py > cropMaxY) cropMaxY = py;
                                        if (px < cropMinX) cropMinX = px;
                                        if (px > cropMaxX) cropMaxX = px;
                                    }
                                }
                            }
                        }
                    }
                    // Fallback to full cell if image was empty
                    if (cropMaxY < 0) { cropMinX = 0; cropMinY = 0; cropMaxX = cellSize - 1; cropMaxY = cellSize - 1; }

                    int cropW = cropMaxX - cropMinX + 1;
                    int cropH = cropMaxY - cropMinY + 1;
                    int ts = gp.tileSize;

                    // Extract each frame as its content crop, scaled to tileSize×tileSize
                    // (bottom-aligned, preserving aspect ratio, pixel-art quality)
                    int[] dirMap = {DIR_DOWN, DIR_LEFT, DIR_RIGHT, DIR_UP};
                    idleFrames = new BufferedImage[4][];
                    for (int r = 0; r < rows; r++) {
                        idleFrames[dirMap[r]] = new BufferedImage[actualFrames[r]];
                        for (int f = 0; f < actualFrames[r]; f++) {
                            BufferedImage crop = raw.getSubimage(
                                f * cellSize + cropMinX, r * cellSize + cropMinY, cropW, cropH);
                            // Scale proportionally to fit inside tileSize, bottom-align
                            int dh = ts;
                            int dw = (int)(cropW * (float) ts / cropH);
                            if (dw > ts) { dw = ts; dh = (int)(cropH * (float) ts / cropW); }
                            int ox = (ts - dw) / 2;
                            int oy = ts - dh; // bottom-align
                            BufferedImage frame = new BufferedImage(ts, ts, BufferedImage.TYPE_INT_ARGB);
                            java.awt.Graphics2D sg = frame.createGraphics();
                            sg.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                                                java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                            sg.drawImage(crop, ox, oy, dw, dh, null);
                            sg.dispose();
                            idleFrames[dirMap[r]][f] = frame;
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("NPC_Generic: Failed to load idle sprite '" + idleSpritePath + "': " + e.getMessage());
            }
        }
        imageLoaded = true;
    }

    /** Returns true if any pixel in the given rectangle has alpha > 0. */
    private boolean hasVisiblePixel(BufferedImage img, int x, int y, int w, int h) {
        int x2 = Math.min(x + w, img.getWidth());
        int y2 = Math.min(y + h, img.getHeight());
        for (int py = y; py < y2; py++) {
            for (int px = x; px < x2; px++) {
                if ((img.getRGB(px, py) >>> 24) > 0) return true;
            }
        }
        return false;
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

        // Give item on first interaction
        if (giveItemId != null && !giveItemGiven) {
            Entity item = data.ItemFactory.create(gp, giveItemId);
            if (item != null) gp.player.canObtainItem(item);
            giveItemGiven = true;
            startDialogue(this, giveItemDialogueSet);
            return;
        }

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

        // Post-walk dialogue (NPC has arrived at destination)
        if (walkToCol < 0 && walkToDialogueSet >= 0) {
            // Give second item after helping, if configured
            if (giveItem2Id != null && !giveItem2Given) {
                Entity item2 = data.ItemFactory.create(gp, giveItem2Id);
                if (item2 != null) gp.player.canObtainItem(item2);
                giveItem2Given = true;
                int dlg = (giveItem2DialogueSet >= 0) ? giveItem2DialogueSet : walkToDialogueSet;
                startDialogue(this, dlg);
                return;
            }
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
