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

    // ── ACTIVITY STATE SYSTEM ──
    // Condition-based state machine: evaluates quest/fragment/boss/story progress
    // to pick which animation, position, direction, and dialogue the NPC uses.
    // States are loaded from npcs.json via NPCFactory or from Tiled properties.
    // Last matching state wins (higher index = higher priority).

    /** A single activity state an NPC can be in, depending on game conditions. */
    public static class NPCActivityState {
        public String id;                           // "forging", "idle_shop", "sleeping"
        public String animationKey  = null;         // key into activityAnimations (null = default idle)
        public int    direction     = -1;           // forced facing (-1 = don't override)
        public int    posCol        = -1;           // tile col to stand at (-1 = stay put)
        public int    posRow        = -1;           // tile row to stand at (-1 = stay put)
        public int    dialogueSet   = -1;           // which dialogue set to use (-1 = don't override)
        public String dialogueName  = null;          // named dialogue key (resolved via dialogueNameMap)
        public boolean stationary   = false;        // lock NPC in place during this activity

        // Conditions — ALL must match for this state to activate (AND logic)
        public String requiredQuestComplete = null; // quest ID that must be complete
        public String requiredQuestActive   = null; // quest ID that must be active (not complete)
        public int    requiredFragments     = -1;   // minimum fragment count (-1 = ignore)
        public int    requiredBoss          = -1;   // boss index that must be defeated (-1 = ignore)
        public int    requiredStoryAct      = -1;   // minimum story act (-1 = ignore)
        public int    requiredLevel         = -1;   // minimum player level (-1 = ignore)
    }

    /** Ordered list of activity states. Index 0 = default/fallback, last match wins. */
    public final java.util.ArrayList<NPCActivityState> activityStates = new java.util.ArrayList<>();
    /** Currently active state (resolved by evaluateActivityState). */
    public NPCActivityState activeState = null;
    private String lastStateId = null;
    /** Spawn tile position (default position before any state overrides). */
    public int spawnCol = -1, spawnRow = -1;

    /** Sprite path set by MapObjectLoader; loaded lazily in getImage(). */
    public String spritePath = null;
    /** Idle sprite sheet path; loaded lazily in getImage(). */
    public String idleSpritePath = null;
    /** Number of direction rows in the idle sprite sheet (1 = single direction, 4 = full). Default: 4 */
    public int idleRows = 4;
    /**
     * Cell aspect ratio for both the walk and idle sprite sheet.
     * 1 = square cells (default). 2 = each cell is 2x wider than tall.
     * Used when the sprite sheet has non-square frames (e.g. Fighter_Training).
     */
    public int spriteAspect = 1;
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
                walkFrames = new BufferedImage[4][];
                if (spriteAspect > 1) {
                    // Non-square walk sheet: compute cell dims from sheet height
                    java.io.InputStream is = getClass().getResourceAsStream(spritePath + ".png");
                    if (is != null) {
                        BufferedImage rawWalk = javax.imageio.ImageIO.read(is);
                        is.close();
                        int cellH = rawWalk.getHeight() / 4;
                        int cellW = cellH * spriteAspect;
                        BufferedImage[][] matrix = loadSpriteMatrix(spritePath, cellW, cellH);
                        int ts = gp.tileSize;
                        // Scale each frame to tileSize x tileSize and map directions (0=DOWN,1=LEFT,2=RIGHT,3=UP)
                        int[] dirMap = {DIR_DOWN, DIR_LEFT, DIR_RIGHT, DIR_UP};
                        for (int r = 0; r < Math.min(matrix.length, dirMap.length); r++) {
                            walkFrames[dirMap[r]] = new BufferedImage[matrix[r].length];
                            for (int c = 0; c < matrix[r].length; c++) {
                                walkFrames[dirMap[r]][c] = util.UtilityTool.scaleImage(matrix[r][c], ts, ts);
                            }
                        }
                    }
                } else {
                    int[] framesPerRow = {6, 6, 6, 6};
                    BufferedImage[][] frames = loadSheetVariable(spritePath, framesPerRow);
                    walkFrames[DIR_DOWN]  = frames[0];
                    walkFrames[DIR_UP]    = frames[3];
                    walkFrames[DIR_LEFT]  = frames[1];
                    walkFrames[DIR_RIGHT] = frames[2];
                }
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
                    int rows = idleRows;
                    int cellH = raw.getHeight() / rows; // cell height
                    int cellW = cellH * spriteAspect;   // cell width (spriteAspect=1 → square, 2 → twice as wide)
                    int maxCols  = raw.getWidth()  / cellW;

                    // Count actual (non-transparent) frames per row
                    int[] actualFrames = new int[rows];
                    for (int r = 0; r < rows; r++) {
                        actualFrames[r] = maxCols;
                        while (actualFrames[r] > 1) {
                            int fx = (actualFrames[r] - 1) * cellW;
                            int fy = r * cellH;
                            if (hasVisiblePixel(raw, fx, fy, cellW, cellH)) break;
                            actualFrames[r]--;
                        }
                    }

                    // Compute per-row visible-content bounding boxes so each row is
                    // cropped based only on frames in that row (avoids extreme
                    // horizontal crops caused by other rows).
                    int[] rowMinX = new int[rows];
                    int[] rowMaxX = new int[rows];
                    int[] rowMinY = new int[rows];
                    int[] rowMaxY = new int[rows];
                    for (int r = 0; r < rows; r++) {
                        rowMinX[r] = cellW; rowMaxX[r] = -1;
                        rowMinY[r] = cellH; rowMaxY[r] = -1;
                        for (int f = 0; f < actualFrames[r]; f++) {
                            int ox = f * cellW, oy = r * cellH;
                            for (int py = 0; py < cellH; py++) {
                                for (int px = 0; px < cellW; px++) {
                                    if ((raw.getRGB(ox + px, oy + py) >>> 24) > 10) {
                                        if (py < rowMinY[r]) rowMinY[r] = py;
                                        if (py > rowMaxY[r]) rowMaxY[r] = py;
                                        if (px < rowMinX[r]) rowMinX[r] = px;
                                        if (px > rowMaxX[r]) rowMaxX[r] = px;
                                    }
                                }
                            }
                        }
                        if (rowMaxY[r] < 0) { rowMinX[r] = 0; rowMinY[r] = 0; rowMaxX[r] = cellW - 1; rowMaxY[r] = cellH - 1; }
                    }

                    int ts = gp.tileSize;
                    // dirMap: row index → direction constant.
                    // For a 4-row sheet: row0=DOWN, row1=LEFT, row2=RIGHT, row3=UP.
                    // For a 1-row sheet: the single row is used for all directions.
                    int[] dirMap = {DIR_DOWN, DIR_LEFT, DIR_RIGHT, DIR_UP};
                    boolean debugFruit = idleSpritePath != null && idleSpritePath.toLowerCase().contains("fruit_trader");
                    if (debugFruit) {
                        System.out.println("[NPC_GENERIC DEBUG] Loading idle sprite: " + idleSpritePath +
                                " rawWxH=" + raw.getWidth() + "x" + raw.getHeight() + " cellWxH=" + cellW + "x" + cellH + " maxCols=" + maxCols + " rows=" + rows);
                    }
                    idleFrames = new BufferedImage[4][];
                    for (int r = 0; r < rows; r++) {
                        int cropMinX = rowMinX[r];
                        int cropMaxX = rowMaxX[r];
                        int cropMinY = rowMinY[r];
                        int cropMaxY = rowMaxY[r];
                        int cropW = cropMaxX - cropMinX + 1;
                        int cropH2 = cropMaxY - cropMinY + 1;
                        if (debugFruit) {
                            System.out.println("[NPC_GENERIC DEBUG] row=" + r + " cropMinX=" + cropMinX + " cropMaxX=" + cropMaxX +
                                    " cropMinY=" + cropMinY + " cropMaxY=" + cropMaxY + " cropWxH=" + cropW + "x" + cropH2);
                        }

                        // Safety fallbacks
                        if (cropW <= 0) cropW = cellW;
                        if (cropH2 <= 0) cropH2 = cellH;

                        BufferedImage[] rowFrames = new BufferedImage[actualFrames[r]];
                        for (int f = 0; f < actualFrames[r]; f++) {
                            BufferedImage crop = raw.getSubimage(
                                f * cellW + cropMinX, r * cellH + cropMinY, cropW, cropH2);

                            // Height-first scaling: prefer to fill the tile height so
                            // characters use the full vertical space. Allow horizontal
                            // overflow (it will be clipped) to avoid very short sprites.
                            float scale = (float) ts / (float) cropH2;
                            int dh = ts;
                            int dw = Math.max(1, Math.round(cropW * scale));
                            if (debugFruit) {
                                System.out.println("[NPC_GENERIC DEBUG] frame=" + f + " scale=" + scale + " dw=" + dw + " dh=" + dh);
                            }
                            int ox = (ts - dw) / 2;
                            int oy = ts - dh; // bottom-align

                            BufferedImage frame = new BufferedImage(ts, ts, BufferedImage.TYPE_INT_ARGB);
                            java.awt.Graphics2D sg = frame.createGraphics();
                            sg.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                                                java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                            sg.drawImage(crop, ox, oy, dw, dh, null);
                            sg.dispose();
                            rowFrames[f] = frame;
                        }

                        if (r < dirMap.length) {
                            idleFrames[dirMap[r]] = rowFrames;
                        }
                    }

                    // If the sheet had fewer than 4 direction rows, fill missing
                    // directions with a copy of row 0 (single-direction sprites).
                    if (rows < 4 && idleFrames[DIR_DOWN] != null) {
                        if (idleFrames[DIR_UP]    == null) idleFrames[DIR_UP]    = idleFrames[DIR_DOWN];
                        if (idleFrames[DIR_LEFT]  == null) idleFrames[DIR_LEFT]  = idleFrames[DIR_DOWN];
                        if (idleFrames[DIR_RIGHT] == null) idleFrames[DIR_RIGHT] = idleFrames[DIR_DOWN];
                    }
                }
            } catch (java.io.IOException | RuntimeException e) {
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
        // Evaluate activity state before normal AI
        evaluateActivityState();

        if (onPath) {
            int goalCol = (walkToCol >= 0) ? walkToCol : 0;
            int goalRow = (walkToRow >= 0) ? walkToRow : 0;
            searchPath(goalCol, goalRow);
        } else if (activeState != null && activeState.stationary) {
            // In a stationary activity: don't wander
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

    /**
     * Evaluates all activity states in order. The last state whose conditions
     * all match becomes active. On state change: sets animation, pathfinds to
     * position, overrides direction and dialogue set.
     */
    public void evaluateActivityState() {
        if (activityStates.isEmpty()) return;
        if (gp == null) return;

        NPCActivityState candidate = null;
        for (NPCActivityState state : activityStates) {
            if (matchesConditions(state)) {
                candidate = state;
            }
        }

        // No state matched — clear to defaults
        if (candidate == null) {
            if (activeState != null) {
                activeState = null;
                lastStateId = null;
                currentActivity = null;
                activitySpriteNum = 1;
                activitySpriteCounter = 0;
                // Return to spawn position if we have one
                if (spawnCol >= 0 && spawnRow >= 0) {
                    walkToCol = spawnCol;
                    walkToRow = spawnRow;
                    onPath = true;
                }
            }
            return;
        }

        // Same state as before — no transition needed, but enforce direction once arrived
        if (candidate.id != null && candidate.id.equals(lastStateId)) {
            if (!onPath && candidate.direction >= 0) {
                direction = candidate.direction;
            }
            return;
        }

        // ── STATE TRANSITION ──
        activeState = candidate;
        lastStateId = candidate.id;

        // Animation
        currentActivity = candidate.animationKey;
        activitySpriteNum = 1;
        activitySpriteCounter = 0;

        // Position: pathfind to the state's position (or stay put)
        if (candidate.posCol >= 0 && candidate.posRow >= 0) {
            walkToCol = candidate.posCol;
            walkToRow = candidate.posRow;
            onPath = true;
            guardMode = false; // temporarily unlock movement for pathfinding
        }

        // Direction: applied after arriving (or immediately if no walk needed)
        if (candidate.direction >= 0 && candidate.posCol < 0) {
            direction = candidate.direction;
            if (idleDirection < 0) idleDirection = candidate.direction;
        }

        // Dialogue set override — support named dialogue keys
        if (candidate.dialogueName != null && dialogueNameMap != null) {
            Integer idx = dialogueNameMap.get(candidate.dialogueName);
            if (idx != null) walkToDialogueSet = idx;
        } else if (candidate.dialogueSet >= 0) {
            walkToDialogueSet = candidate.dialogueSet;
        }

        // Stationary flag
        if (candidate.stationary) {
            staticNPC = true;
        }
    }

    /** Checks if ALL conditions of a state are met. */
    private boolean matchesConditions(NPCActivityState state) {
        // Quest complete check
        if (state.requiredQuestComplete != null && !state.requiredQuestComplete.isBlank()) {
            if (gp.questManager == null || !gp.questManager.isComplete(state.requiredQuestComplete)) {
                return false;
            }
        }
        // Quest active (but not complete) check
        if (state.requiredQuestActive != null && !state.requiredQuestActive.isBlank()) {
            if (gp.questManager == null) return false;
            if (!gp.questManager.hasQuest(state.requiredQuestActive)) return false;
            if (gp.questManager.isComplete(state.requiredQuestActive)) return false;
        }
        // Fragment count check
        if (state.requiredFragments >= 0) {
            if (gp.memoryJournal == null || gp.memoryJournal.getCount() < state.requiredFragments) {
                return false;
            }
        }
        // Boss defeated check
        if (state.requiredBoss >= 0) {
            boolean bossDefeated = switch (state.requiredBoss) {
                case 1 -> gp.boss1Defeated;
                case 2 -> gp.boss2Defeated;
                case 3 -> gp.boss3Defeated;
                case 4 -> gp.boss4Defeated;
                default -> false;
            };
            if (!bossDefeated) return false;
        }
        // Story act check
        if (state.requiredStoryAct >= 0) {
            if (gp.storyAct < state.requiredStoryAct) {
                return false;
            }
        }
        // Player level check
        if (state.requiredLevel >= 0) {
            if (gp.player == null || gp.player.level < state.requiredLevel) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void speak() {
        facePlayer();
        syncQuestDrivenNpcState();

        // Re-evaluate in case quest state changed since last tick
        evaluateActivityState();

        // Temporarily pause activity animation while talking (face player naturally)
        String savedActivity = currentActivity;
        currentActivity = null;

        // ── Quest step execution (JSON-driven NPCs) ──
        if (gp.questManager != null && objectId != null) {
            if (gp.questManager.executeStepForNpc(objectId, this)) {
                return;
            }
        }

        // Give item on first interaction
        if (giveItemId != null && !giveItemGiven) {
            Entity item = data.ItemFactory.create(gp, giveItemId);
            if (item != null) gp.player.canObtainItem(item);
            giveItemGiven = true;
            if (giveItemQuestId != null && gp.questManager != null) {
                gp.questManager.addQuest(giveItemQuestId);
            }
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
