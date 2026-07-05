package entity;

import gfx.Sprite;
import java.util.Random;

import main.GamePanel;

/**
 * NPC condus de date — toate dialogurile, sprite-urile, comportamentul si proprietatile
 * fragmentelor de memorie sunt setate extern (de obicei de MapObjectLoader citind
 * proprietatile din Tiled), nu hardcodate.
 *
 * Proprietati Tiled consumate de MapObjectLoader → NPC_Generic:
 *   sprite           (String)  calea resursei fara extensie, ex: "/res/npc/Villager_walk-sheet"
 *   dialogue_S_L     (String)  set S, linia L de dialog (ex: dialogue_0_0, dialogue_1_2)
 *   memoryFragmentId (String)  id-ul fragmentului de memorie dat de acest NPC la obtinere
 *   memoryFragmentName(String) numele afisat al fragmentului
 *   memoryText0..4   (String)  linii de text pentru flashback
 *   fragmentTrigger  (String)  "talk" (implicit) | "defeat" | "quest"
 *   dialogueChoices  (String)  etichete optiuni separate prin |, ex: "Accept|Refuz"
 *   choiceNextSet    (String)  indici set urmator separati prin |, ex: "2|3"
 * dialogue_0_0 = "Uhm... hello?"
 * dialogue_0_1 = "I don't really remember much, but I think I got hurt near here..."
 * dialogue_0_2 = "Please take my sword, it might help you on your journey."
 * dialogue_0_3 = "... Help me later too, I don't want to be alone here."
 * 
 */
public class NPC_Generic extends Entity {

    // Masina de stari bazata pe conditii: evalueaza progresul quest/fragment/boss/poveste
    // pentru a alege animatia, pozitia, directia si dialogul NPC-ului.
    // Starile sunt incarcate din npcs.json via NPCFactory sau din proprietatile Tiled.
    // Ultima stare care se potriveste castiga (index mai mare = prioritate mai mare).

    /** O singura stare de activitate in care poate fi NPC-ul, in functie de conditiile jocului. */
    public static class NPCActivityState {
        public String id;                           // "forjare", "magazin_inactiv", "dormit"
        public String animationKey  = null;         // cheie in activityAnimations (null = inactiv implicit)
        public int    direction     = -1;           // directie fortata (-1 = nu suprascrie)
        public int    posCol        = -1;           // coloana tile unde sta (-1 = ramane pe loc)
        public int    posRow        = -1;           // randul tile unde sta (-1 = ramane pe loc)
        public int    dialogueSet   = -1;           // setul de dialog de folosit (-1 = nu suprascrie)
        public String dialogueName  = null;          // cheie dialog dupa nume (rezolvata via dialogueNameMap)
        public boolean stationary   = false;        // blocheaza NPC-ul in loc in aceasta activitate

        // Conditii — TOATE trebuie sa fie satisfacute pentru activarea starii (logica AND)
        public String requiredQuestComplete = null; // ID quest care trebuie finalizat
        public String requiredQuestActive   = null; // ID quest care trebuie activ (nefinalizat)
        public int    requiredFragments     = -1;   // numar minim de fragmente (-1 = ignora)
        public int    requiredBoss          = -1;   // boss care trebuie invins (-1 = ignora)
        public int    requiredStoryAct      = -1;   // act minim al povestii (-1 = ignora)
        public int    requiredLevel         = -1;   // nivel minim al jucatorului (-1 = ignora)
        public String npcNotMet             = null; // conditie: NPC-ul cu acest objectId NU e inca in gp.metNPCs
        public boolean marksNpcMet          = false; // cand dialogul se termina, adauga npc.objectId in gp.metNPCs
    }

    /** Lista ordonata de stari de activitate. Indexul 0 = implicit/fallback, ultima potrivire castiga. */
    public final java.util.ArrayList<NPCActivityState> activityStates = new java.util.ArrayList<>();
    /** Starea activa curenta (rezolvata de evaluateActivityState). */
    public NPCActivityState activeState = null;
    private String lastStateId = null;
    /** Pozitia tile de spawn (pozitia implicita inainte de orice suprascrie de stare). */
    public int spawnCol = -1, spawnRow = -1;

    /** Calea sprite-ului setata de MapObjectLoader; incarcata lazy in getImage(). */
    public String spritePath = null;
    /** Calea foii de sprite inactiv; incarcata lazy in getImage(). */
    public String idleSpritePath = null;
    /** Numarul de randuri de directii in foaia de sprite inactiv (1 = directie unica, 4 = complet). Implicit: 4 */
    public int idleRows = 4;
    /**
     * Raportul de aspect al celulei pentru foile de sprite walk si idle.
     * 1 = celule patrate (implicit). 2 = fiecare celula e de 2 ori mai lata decat inalta.
     * Folosit cand foaia are cadre non-patrate (ex: Fighter_Training).
     */
    public float spriteAspect = 1.0f;
    private boolean imageLoaded = false;

    public NPC_Generic(GamePanel gp) {
        super(gp);

        direction = DIR_DOWN;
        speed = 1;
        walkFrameCount = 6;
        collision = true;

        dialogueSet = -1;

        solidArea.x = gp.tileSize * 20 / 64;
        solidArea.y = gp.tileSize * 22 / 64;
        solidArea.width  = gp.tileSize * 24 / 64;
        solidArea.height = gp.tileSize * 22 / 64;
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;

        int hcx = solidArea.x + solidArea.width  / 2;
        int hcy = solidArea.y + solidArea.height / 2;
        int hr  = Math.min(solidArea.width, solidArea.height) / 2;
        setOctagonHurt(hcx, hcy, hr);
    }

    /** Cheama dupa setarea spritePath / idleSpritePath pentru a incarca foile. Sigur de apelat de mai multe ori. */
    // GPU port: visible-pixel bounding-box cropping uses Sprite.getRGB; the per-frame
    // crop+scale+bottom-align uses Sprite.croppedBottomAligned (Pixmap composite at load time).
    public void getImage() {
        if (imageLoaded) return;
        // Batch CPU pixel reads: getRGB()/croppedBottomAligned() below are called thousands of
        // times per sheet; without batching each call re-decodes the whole PNG (~12s on class
        // select). The batch decodes each source texture once and disposes it at the end.
        Sprite.beginPixelBatch();
        try {
        if (spritePath != null) {
            try {
                walkFrames = new Sprite[4][];
                if (spriteAspect != 1.0f) {
                    // Foaie walk non-patrata: calculeaza dimensiunile celulei din inaltimea foii
                    Sprite rawWalk = util.ResourceCache.loadImageIfPresent(spritePath + ".png");
                    if (rawWalk != null) {
                        int cellH = rawWalk.getHeight() / 4;
                        int cellW = Math.max(1, Math.round(cellH * spriteAspect));
                        Sprite[][] matrix = loadSpriteMatrix(spritePath, cellW, cellH);
                        int ts = gp.tileSize;
                        // Scale each frame to tileSize x tileSize and map directions (0=DOWN,1=LEFT,2=RIGHT,3=UP)
                        int[] dirMap = {DIR_DOWN, DIR_LEFT, DIR_RIGHT, DIR_UP};
                        for (int r = 0; r < Math.min(matrix.length, dirMap.length); r++) {
                            walkFrames[dirMap[r]] = new Sprite[matrix[r].length];
                            for (int c = 0; c < matrix[r].length; c++) {
                                walkFrames[dirMap[r]][c] = util.UtilityTool.scaleImage(matrix[r][c], ts, ts);
                            }
                        }
                    }
                } else {
                    int[] framesPerRow = {6, 6, 6, 6};
                    Sprite[][] frames = loadSheetVariable(spritePath, framesPerRow);
                    walkFrames[DIR_DOWN]  = frames[0];
                    walkFrames[DIR_UP]    = frames[3];
                    walkFrames[DIR_LEFT]  = frames[1];
                    walkFrames[DIR_RIGHT] = frames[2];
                }
            } catch (Exception e) {
                System.out.println("NPC_Generic: Failed to load walk sprite '" + spritePath + "': " + e.getMessage());
            }
        }
        // Foaie sprite inactiv — ordinea randurilor: jos / stanga / dreapta / sus
        if (idleSpritePath != null) {
            try {
                Sprite raw = util.ResourceCache.loadImageIfPresent(idleSpritePath + ".png");
                if (raw != null) {
                    int rows = idleRows;
                    int cellH = raw.getHeight() / rows; // inaltimea celulei
                    int cellW = Math.max(1, Math.round(cellH * spriteAspect));   // latimea celulei (spriteAspect=1 → patrat, 0.5 → lat jumatate, 2 → dublu lat)
                    int maxCols  = raw.getWidth()  / cellW;

                    // Numara cadrele reale (non-transparente) per rand
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

                    // Calculeaza bounding box-urile vizibile per rand — fiecare rand e
                    // decupat pe baza cadrelor din acel rand (evita decupari extreme
                    // cauzate de alte randuri).
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
                    // dirMap: index rand → constanta de directie.
                    // Pentru o foaie cu 4 randuri: rand0=JOS, rand1=STANGA, rand2=DREAPTA, rand3=SUS.
                    // Pentru o foaie cu 1 rand: randul unic e folosit pentru toate directiile.
                    int[] dirMap = {DIR_DOWN, DIR_LEFT, DIR_RIGHT, DIR_UP};
                    boolean debugFruit = idleSpritePath != null && idleSpritePath.toLowerCase().contains("fruit_trader");
                    if (debugFruit) {
                        System.out.println("[NPC_GENERIC DEBUG] Loading idle sprite: " + idleSpritePath +
                                " rawWxH=" + raw.getWidth() + "x" + raw.getHeight() + " cellWxH=" + cellW + "x" + cellH + " maxCols=" + maxCols + " rows=" + rows);
                    }
                    idleFrames = new Sprite[4][];
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

                        Sprite[] rowFrames = new Sprite[actualFrames[r]];
                        for (int f = 0; f < actualFrames[r]; f++) {
                            // Scalare pe inaltime: prefera umplerea inaltimii tile-ului
                            // pentru ca personajele sa foloseasca tot spatiul vertical.
                            float scale = (float) ts / (float) cropH2;
                            int dh = ts;
                            int dw = Math.max(1, Math.round(cropW * scale));
                            if (debugFruit) {
                                System.out.println("[NPC_GENERIC DEBUG] frame=" + f + " scale=" + scale + " dw=" + dw + " dh=" + dh);
                            }
                            // GPU-native crop+scale+bottom-align into a tile-sized transparent cell.
                            rowFrames[f] = raw.croppedBottomAligned(
                                f * cellW + cropMinX, r * cellH + cropMinY, cropW, cropH2, dw, dh, ts, ts);
                        }

                        if (r < dirMap.length) {
                            idleFrames[dirMap[r]] = rowFrames;
                        }
                    }

                    // Daca foaia are mai putin de 4 randuri de directii, completeaza directiile
                    // lipsa cu o copie a randului 0 (sprite cu directie unica).
                    if (rows < 4 && idleFrames[DIR_DOWN] != null) {
                        if (idleFrames[DIR_UP]    == null) idleFrames[DIR_UP]    = idleFrames[DIR_DOWN];
                        if (idleFrames[DIR_LEFT]  == null) idleFrames[DIR_LEFT]  = idleFrames[DIR_DOWN];
                        if (idleFrames[DIR_RIGHT] == null) idleFrames[DIR_RIGHT] = idleFrames[DIR_DOWN];
                    }
                }
            } catch (RuntimeException e) {
                System.out.println("NPC_Generic: Failed to load idle sprite '" + idleSpritePath + "': " + e.getMessage());
            }
        }
        } finally {
            Sprite.endPixelBatch();
        }
        imageLoaded = true;
    }

    /** Returneaza true daca orice pixel din dreptunghiul dat are alpha > 0. */
    private boolean hasVisiblePixel(Sprite img, int x, int y, int w, int h) {
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
    public void tickActivityState() {
        evaluateActivityState();
    }

    @Override
    public void setAction() {
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
     * Evalueaza toate starile de activitate in ordine. Ultima stare ale carei conditii
     * sunt toate satisfacute devine activa. La schimbarea starii: seteaza animatia, calculeaza
     * calea catre pozitie, suprascrie directia si setul de dialog.
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

        // Nicio stare nu s-a potrivit — resetare la valorile implicite
        if (candidate == null) {
            if (activeState != null) {
                activeState = null;
                lastStateId = null;
                currentActivity = null;
                activitySpriteNum = 1;
                activitySpriteCounter = 0;
                // Intoarcere la pozitia de spawn daca exista
                if (spawnCol >= 0 && spawnRow >= 0) {
                    walkToCol = spawnCol;
                    walkToRow = spawnRow;
                    onPath = true;
                }
            }
            return;
        }

        // Aceeasi stare ca inainte — nicio tranzitie necesara, dar aplica directia odata ajuns
        if (candidate.id != null && candidate.id.equals(lastStateId)) {
            if (!onPath && candidate.direction >= 0) {
                direction = candidate.direction;
            }
            return;
        }

        activeState = candidate;
        lastStateId = candidate.id;

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

        // Directie: aplicata dupa sosire (sau imediat daca nu e nevoie de deplasare)
        if (candidate.direction >= 0 && candidate.posCol < 0) {
            direction = candidate.direction;
            if (idleDirection < 0) idleDirection = candidate.direction;
        }

        // Suprascrie setul de dialog — suporta chei de dialog dupa nume
        if (candidate.dialogueName != null && dialogueNameMap != null) {
            Integer idx = dialogueNameMap.get(candidate.dialogueName);
            if (idx != null) walkToDialogueSet = idx;
        } else if (candidate.dialogueSet >= 0) {
            walkToDialogueSet = candidate.dialogueSet;
        }

        if (candidate.stationary) {
            staticNPC = true;
        }
    }

    /** Verifica daca TOATE conditiile unei stari sunt satisfacute. */
    private boolean matchesConditions(NPCActivityState state) {
        if (state.requiredQuestComplete != null && !state.requiredQuestComplete.isBlank()) {
            if (gp.questManager == null || !gp.questManager.isComplete(state.requiredQuestComplete)) {
                return false;
            }
        }
        if (state.requiredQuestActive != null && !state.requiredQuestActive.isBlank()) {
            if (gp.questManager == null) return false;
            if (!gp.questManager.hasQuest(state.requiredQuestActive)) return false;
            if (gp.questManager.isComplete(state.requiredQuestActive)) return false;
        }
        if (state.requiredFragments >= 0) {
            if (gp.memoryJournal == null || gp.memoryJournal.getCount() < state.requiredFragments) {
                return false;
            }
        }
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
        if (state.requiredStoryAct >= 0) {
            if (gp.storyAct < state.requiredStoryAct) {
                return false;
            }
        }
        if (state.requiredLevel >= 0) {
            if (gp.player == null || gp.player.level < state.requiredLevel) {
                return false;
            }
        }
        if (state.npcNotMet != null && !state.npcNotMet.isBlank()) {
            if (gp.metNPCs.contains(state.npcNotMet)) return false;
        }
        return true;
    }

    @Override
    public void speak() {
        facePlayer();
        syncQuestDrivenNpcState();

        // Re-evalueaza in caz ca starea quest-ului s-a schimbat de la ultimul tick
        evaluateActivityState();

        if (gp.questManager != null && objectId != null) {
            if (gp.questManager.executeStepForNpc(objectId, this)) {
                return;
            }
        }

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

        if (checkRequiredItemDialogue()) return;

        // Obtinere fragment de memorie: daca NPC-ul are unul si nu a fost colectat inca
        if (memoryFragmentId != null && !memoryFragmentClaimed) {
            boolean canClaim = true;
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

        // Lant de pasi (dialog cu pathfinding in mai multi pasi)
        if (!npcSteps.isEmpty()) {
            int dlg = (walkToDialogueSet >= 0) ? walkToDialogueSet : 0;
            startDialogue(this, dlg);
            if (walkToCol >= 0 && walkToRow >= 0) onPath = true;
            return;
        }

        // Dialog post-deplasare (NPC-ul a ajuns la destinatie)
        if (walkToCol < 0 && walkToDialogueSet >= 0) {
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

        // Implicit: avanseaza setul de dialog la fiecare interactiune
        dialogueSet++;
        String[][] d = ensureDialogues();
        if (dialogueSet >= d.length || d[dialogueSet] == null || d[dialogueSet][0] == null) {
            dialogueSet = 0; // reincepe de la inceput
        }
        startDialogue(this, dialogueSet);
    }

    /** Obtine fragmentul de memorie purtat de acest NPC si declanseaza flashback-ul. */
    private void claimFragment() {
        memoryFragmentClaimed = true;

        if (gp.memoryJournal != null) {
            gp.memoryJournal.collect(memoryFragmentId);
        }
        if ("frag_cave".equals(memoryFragmentId) && gp.questManager != null) {
            gp.questManager.progress("find_exit", 1);
        }

        if (gp.memoryFlashback != null && memoryFragmentText != null) {
            data.MemoryJournal.MemoryFragment frag = (gp.memoryJournal != null)
                    ? gp.memoryJournal.getFragment(memoryFragmentId) : null;
            if (frag != null) {
                gp.memoryFlashback.trigger(frag);
            }
        }
    }
}
