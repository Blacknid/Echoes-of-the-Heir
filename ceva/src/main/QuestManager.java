package main;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Quest-Step system.  Quests define WHAT HAPPENS; NPCs define WHO SAYS WHAT.
 *
 * <pre>{@code
 * {
 *   "id":           "help_soldier",
 *   "name":         "Help the Wounded Soldier",
 *   "description":  "A wounded soldier needs a bandage.",
 *   "prerequisite": "",
 *   "steps": [
 *     { "action": "talk",    "npc": "soldier", "dialogue": "intro", "give": "wooden_sword" },
 *     { "action": "deliver", "npc": "soldier", "item": "bandage", "consume": true,
 *       "dialogue": "thanks", "failDialogue": "waiting" }
 *   ],
 *   "rewardCoins":    25,
 *   "rewardItemId":   "",
 *   "rewardFragmentId": "",
 *   "chainQuestId":   "meet_soldier_later"
 * }
 * }</pre>
 *
 * Step actions:
 * <ul>
 *   <li><b>talk</b>    — NPC plays dialogue, optionally gives item.  Auto-advances.</li>
 *   <li><b>deliver</b> — Player must have item.  Consume + dialogue + advance on success.
 *                        Plays failDialogue when item is missing.</li>
 *   <li><b>collect</b> — Advance via {@link #progress} calls.  Step {@code count} = target.</li>
 *   <li><b>kill</b>    — Same as collect, tracked by monster-kill progress() calls.</li>
 *   <li><b>go</b>      — Same as collect, triggered by map events / triggers.</li>
 * </ul>
 *
 * Quests WITHOUT a {@code steps} array still work exactly as before
 * (flat current/target counter incremented by {@link #progress}).
 */
public class QuestManager {

    // ══════════════════════════════════════════════════════════════════════
    //  PUBLIC DATA CLASSES
    // ══════════════════════════════════════════════════════════════════════

    /** One step in a multi-step quest. */
    public static class QuestStep {
        public final String action;        // talk | deliver | collect | kill | go
        public final String npc;           // npc objectId (for talk / deliver)
        public final String dialogue;      // named dialogue to play on success
        public final String failDialogue;  // dialogue when player lacks required item
        public final String give;          // ItemFactory id to give the player
        public final String item;          // item required (deliver) or to collect
        public final String map;           // map id required (for go action)
        public final int    count;         // target count (collect / kill, default 1)
        public final boolean consume;      // consume the delivered item
        public final String description;   // step description shown in quest log

        QuestStep(Map<String, String> m) {
            this.action       = strVal(m, "action", "talk");
            this.npc          = m.get("npc");
            this.dialogue     = m.get("dialogue");
            this.failDialogue = m.get("failDialogue");
            this.give         = m.get("give");
            this.item         = m.get("item");
            this.map          = m.get("map");
            this.count        = intVal(m, "count", 1);
            this.consume      = "true".equals(m.get("consume"));
            this.description  = m.get("description");
        }
    }

    /** Read-only snapshot of a quest (used by SaveLoad). */
    public static class QuestState {
        public final String id;
        public final String name;
        public final String description;
        public final int current;
        public final int target;

        // Step tracking (≥ 0 for step-based quests, -1 for legacy)
        public final int currentStep;
        public final int stepProgress;

        public QuestState(String id, String name, String description,
                          int current, int target,
                          int currentStep, int stepProgress) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.current = current;
            this.target = target;
            this.currentStep = currentStep;
            this.stepProgress = stepProgress;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  QUEST REGISTRY (loaded once from quests.json)
    // ══════════════════════════════════════════════════════════════════════

    private static class QuestDef {
        Map<String, String> props = new HashMap<>();
        ArrayList<Map<String, String>> stepDefs = new ArrayList<>();
    }

    private static final ArrayList<QuestDef> registry = new ArrayList<>();
    private static boolean registryLoaded = false;

    private static void loadRegistry() {
        if (registryLoaded) return;
        registryLoaded = true;
        try (InputStream is = QuestManager.class.getResourceAsStream("/res/data/quests.json")) {
            if (is == null) { System.out.println("[QuestManager] quests.json not found"); return; }
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            parseJsonArray(sb.toString());
            System.out.println("[QuestManager] Loaded " + registry.size() + " quest definitions");
        } catch (Exception e) {
            System.out.println("[QuestManager] Error loading quests.json: " + e.getMessage());
        }
    }

    /** Look up a quest template by id. Returns null if not in the registry. */
    private static QuestDef findDef(String id) {
        loadRegistry();
        for (int i = 0, n = registry.size(); i < n; i++) {
            QuestDef def = registry.get(i);
            if (id.equals(def.props.get("id"))) return def;
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  INSTANCE STATE
    // ══════════════════════════════════════════════════════════════════════

    private final GamePanel gp;
    private final ArrayList<Quest> quests = new ArrayList<>();
    private boolean logOpen = false;

    // Display colours / fonts (cached, not allocated per frame)
    private static final Color BG              = new Color(15, 12, 20, 225);
    private static final Color BORDER          = new Color(180, 160, 100);
    private static final Color TEXT            = new Color(220, 215, 200);
    private static final Color COMPLETE        = new Color(80, 200, 80);
    private static final Color INCOMPLETE      = new Color(200, 180, 100);
    private static final Color NEW_QUEST       = new Color(255, 220, 80);
    private static final Color OBJECTIVE_COLOR = new Color(160, 210, 240);
    private static final Color SECTION_COLOR   = new Color(230, 200, 100);
    private static final Color DONE_TEXT       = new Color(100, 160, 100);
    private static final Color CARD_BG         = new Color(30, 25, 35, 180);
    private static final Color CARD_BORDER     = new Color(60, 55, 65, 200);
    private static final Color PROG_BG         = new Color(15, 10, 20, 255);
    private static final Color PROG_FILL       = new Color(90, 160, 200, 255);

    private static final java.awt.BasicStroke STROKE_2 = new java.awt.BasicStroke(2f);
    private static final java.awt.BasicStroke STROKE_1 = new java.awt.BasicStroke(1f);
    private static final Color DESC_COLOR    = new Color(160, 155, 145);
    private static final Color HINT_COLOR    = new Color(150, 150, 150);
    private Font fontPlain14, fontBold28, fontBold16, fontPlain20, fontPlain16, fontPlain14b;

    // Scroll state for the quest log
    private int logScrollOffset = 0;
    private int logScrollMax    = 0;

    /** Scroll the quest log. dir = -1 (up) or +1 (down). */
    public void scrollLog(int dir) {
        logScrollOffset = Math.max(0, Math.min(logScrollMax, logScrollOffset + dir * 22));
    }

    private void ensureFonts(Graphics2D g2) {
        if (fontPlain14 == null) {
            // Load the custom pixel font once. Integer sizes keep the font crisp.
            Font base;
            try {
                base = Font.createFont(Font.TRUETYPE_FONT,
                        QuestManager.class.getResourceAsStream("/res/fonts/Pixeloid Sans.ttf"));
            } catch (Exception e) {
                System.out.println("[QuestManager] Pixeloid Sans not found, using system font");
                base = g2.getFont();
            }
            float sf = gp.screenWidth / (float) Config.UI_BASE_W;
            fontPlain14  = base.deriveFont(Font.PLAIN, (float) Math.round(14f * sf));
            fontBold28   = base.deriveFont(Font.BOLD,  (float) Math.round(28f * sf));
            fontBold16   = base.deriveFont(Font.BOLD,  (float) Math.round(16f * sf));
            fontPlain20  = base.deriveFont(Font.PLAIN, (float) Math.round(20f * sf));
            fontPlain16  = base.deriveFont(Font.PLAIN, (float) Math.round(16f * sf));
            fontPlain14b = base.deriveFont(Font.PLAIN, (float) Math.round(14f * sf));
        }
    }

    // ── Constructor ──

    public QuestManager(GamePanel gp) {
        this.gp = gp;
        loadRegistry();
        startAutoQuests();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  PUBLIC API — adding / removing / progressing quests
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Start a quest by its id (looks it up in quests.json).
     * If the quest is already active it is silently ignored.
     * Checks prerequisite — quest won't start unless prerequisite is complete.
     */
    public void addQuest(String id) {
        QuestDef def = findDef(id);
        if (def == null) {
            System.out.println("[QuestManager] WARNING — quest id '" + id + "' not found in quests.json");
            return;
        }
        // Prerequisite check
        String prereq = strVal(def.props, "prerequisite", "");
        if (!prereq.isEmpty() && !isComplete(prereq)) {
            System.out.println("[QuestManager] Prerequisite '" + prereq + "' not met for '" + id + "'");
            return;
        }
        String name = strVal(def.props, "name", id);
        String desc = strVal(def.props, "description", "");
        int target  = intVal(def.props, "target", 1);
        QuestStep[] steps = buildSteps(def);
        addQuestInternal(id, name, desc, target, steps, 0, 0, 0, true);
    }

    /**
     * Start a quest with explicit parameters (backward-compatible).
     * Used by Tiled QuestDefinition events and legacy code.
     */
    public void addQuest(String id, String name, String description, int target) {
        addQuestInternal(id, name, description, target, null, 0, 0, 0, true);
    }

    /** Restore a quest from a save file (old format, no step data). */
    public void restoreQuest(String id, String name, String description, int target, int current) {
        restoreQuest(id, name, description, target, current, -1, 0);
    }

    /** Restore a quest from a save file (new format with step data). */
    public void restoreQuest(String id, String name, String description,
                             int target, int current,
                             int savedStep, int savedStepProgress) {
        QuestDef def = findDef(id);
        QuestStep[] steps = (def != null) ? buildSteps(def) : null;
        int stepIdx  = (savedStep >= 0 && steps != null) ? savedStep : 0;
        int stepProg = (savedStep >= 0) ? savedStepProgress : 0;
        addQuestInternal(id, name, description, target, steps, current, stepIdx, stepProg, false);
    }

    /** Remove a quest entirely (active or completed). Returns true if it was found. */
    public boolean removeQuest(String id) {
        for (int i = 0; i < quests.size(); i++) {
            if (quests.get(i).id.equals(id)) {
                quests.remove(i);
                return true;
            }
        }
        return false;
    }

    /** Remove all active quests (used before loading a save). */
    public void clearQuests() {
        quests.clear();
    }

    /** Check whether a quest with this id is currently tracked (complete or not). */
    public boolean hasQuest(String id) {
        for (int i = 0, n = quests.size(); i < n; i++) {
            if (quests.get(i).id.equals(id)) return true;
        }
        return false;
    }

    /** Check if a quest is complete. Returns false if quest doesn't exist. */
    public boolean isComplete(String id) {
        for (int i = 0, n = quests.size(); i < n; i++) {
            Quest q = quests.get(i);
            if (q.id.equals(id)) return q.isComplete();
        }
        return false;
    }

    /**
     * Increment progress for a quest.
     * <ul>
     *   <li>Step-based quests: applies to the current collect/kill/go step.</li>
     *   <li>Legacy quests: increments the flat counter.</li>
     * </ul>
     * Returns {@code true} if the quest <i>just now</i> became complete.
     */
    public boolean progress(String id, int amount) {
        for (int i = 0, n = quests.size(); i < n; i++) {
            Quest q = quests.get(i);
            if (!q.id.equals(id) || q.isComplete()) continue;

            if (q.steps != null) {
                // Step-based: progress only applies to collect / kill / go steps
                if (q.currentStep >= q.steps.length) return false;
                QuestStep step = q.steps[q.currentStep];
                String a = step.action;
                if ("collect".equals(a) || "kill".equals(a) || "defeat".equals(a) || "go".equals(a)) {
                    q.stepProgress = Math.min(q.stepProgress + amount, step.count);
                    if (q.stepProgress >= step.count) {
                        q.currentStep++;
                        q.stepProgress = 0;
                        if (q.isComplete()) {
                            gp.ui.addMessage("Quest complete: " + q.name + "!", COMPLETE);
                            grantRewards(q.id);
                            chainNext(q.id);
                            return true;
                        }
                    }
                }
                return false;
            }

            // Legacy counter-based
            q.current = Math.min(q.current + amount, q.target);
            if (q.isComplete()) {
                gp.ui.addMessage("Quest complete: " + q.name + "!", COMPLETE);
                grantRewards(q.id);
                chainNext(q.id);
                return true;
            }
            return false;
        }
        return false;
    }

    /**
     * Called whenever the player enters a new map.
     * Automatically completes any active {@code go} step whose {@code map} matches {@code mapId}.
     */
    public void notifyMapEntered(String mapId) {
        if (mapId == null) return;
        for (int i = 0, n = quests.size(); i < n; i++) {
            Quest q = quests.get(i);
            if (q.isComplete()) continue;

            // Step-based quest with a go step
            if (q.steps != null && q.currentStep < q.steps.length) {
                QuestStep step = q.steps[q.currentStep];
                if ("go".equals(step.action) && mapId.equalsIgnoreCase(step.map)) {
                    q.currentStep++;
                    q.stepProgress = 0;
                    if (q.isComplete()) {
                        gp.ui.addMessage("Quest complete: " + q.name + "!", COMPLETE);
                        grantRewards(q.id);
                        chainNext(q.id);
                    }
                }
            }
        }
    }

    /**
     * Execute the current quest step for a given NPC.
     * Called from {@code NPC_Generic.speak()}.
     *
     * @param npcId  the NPC's objectId (matches step {@code "npc"} field)
     * @param npc    the NPC entity (used for dialogue / item giving)
     * @return true if a step was executed (dialogue was started)
     */
    public boolean executeStepForNpc(String npcId, entity.Entity npc) {
        if (npcId == null) return false;

        for (int i = 0, n = quests.size(); i < n; i++) {
            Quest q = quests.get(i);
            if (q.isComplete() || q.steps == null || q.currentStep >= q.steps.length) continue;

            QuestStep step = q.steps[q.currentStep];
            if (step.npc == null || !step.npc.equals(npcId)) continue;

            switch (step.action) {
                case "talk" -> {
                    // Give item if specified
                    if (step.give != null && !step.give.isBlank()) {
                        entity.Entity item = data.ItemFactory.create(gp, step.give);
                        if (item != null) gp.player.canObtainItem(item);
                    }
                    // Play dialogue
                    if (step.dialogue != null) {
                        npc.startNamedDialogue(npc, step.dialogue);
                    } else {
                        npc.startDialogue(npc, 0);
                    }
                    // Advance step
                    q.currentStep++;
                    q.stepProgress = 0;
                    checkCompletion(q);
                    return true;
                }
                case "deliver" -> {
                    // Check player has the required item
                    if (step.item != null) {
                        int idx = gp.player.searchItemInInventory(step.item);
                        if (idx == 999) {
                            // Player doesn't have the item — play fail dialogue
                            if (step.failDialogue != null) {
                                npc.startNamedDialogue(npc, step.failDialogue);
                            } else {
                                npc.startDialogue(npc, 0);
                            }
                            return true;
                        }
                        // Consume the item
                        if (step.consume && idx < gp.player.inventory.size()) {
                            entity.Entity item = gp.player.inventory.get(idx);
                            if (item.amount > 1) item.amount--;
                            else gp.player.inventory.remove(idx);
                        }
                    }
                    // Give reward item if specified
                    if (step.give != null && !step.give.isBlank()) {
                        entity.Entity item = data.ItemFactory.create(gp, step.give);
                        if (item != null) gp.player.canObtainItem(item);
                    }
                    // Play dialogue
                    if (step.dialogue != null) {
                        npc.startNamedDialogue(npc, step.dialogue);
                    } else {
                        npc.startDialogue(npc, 0);
                    }
                    // Advance step
                    q.currentStep++;
                    q.stepProgress = 0;
                    checkCompletion(q);
                    return true;
                }
                // collect / kill / go steps are NOT executed via NPC talk
                default -> { /* no-op */ }
            }
        }

        // No active quest step for this NPC — auto-start quests whose first step
        // is a "talk" action targeting this NPC (prerequisite must be met).
        loadRegistry();
        for (int r = 0, rn = registry.size(); r < rn; r++) {
            QuestDef def = registry.get(r);
            String defId = def.props.get("id");
            if (defId == null || hasQuest(defId)) continue;
            if (def.stepDefs.isEmpty()) continue;
            Map<String, String> firstStep = def.stepDefs.get(0);
            if (!npcId.equals(firstStep.get("npc"))) continue;
            if (!"talk".equals(firstStep.getOrDefault("action", "talk"))) continue;
            // Check prerequisite
            String prereq = strVal(def.props, "prerequisite", "");
            if (!prereq.isEmpty() && !isComplete(prereq)) continue;
            // Auto-start and execute
            addQuest(defId);
            return executeStepForNpc(npcId, npc);
        }

        return false;
    }

    /** Get a read-only snapshot of all quests (for SaveLoad). */
    public ArrayList<QuestState> getQuestStates() {
        ArrayList<QuestState> states = new ArrayList<>();
        for (int i = 0, n = quests.size(); i < n; i++) {
            Quest q = quests.get(i);
            int step     = (q.steps != null) ? q.currentStep   : -1;
            int stepProg = (q.steps != null) ? q.stepProgress  : 0;
            states.add(new QuestState(q.id, q.name, q.description,
                    q.current, q.target, step, stepProg));
        }
        return states;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  LOG TOGGLE
    // ══════════════════════════════════════════════════════════════════════

    public void toggleLog() { logOpen = !logOpen; }
    public boolean isLogOpen() { return logOpen; }

    // ══════════════════════════════════════════════════════════════════════
    //  DRAWING
    // ══════════════════════════════════════════════════════════════════════

    /** Draw the current objective tracker (small HUD overlay). */
    public void drawTracker(Graphics2D g2) {
        Quest active = null;
        for (int i = 0, n = quests.size(); i < n; i++) {
            Quest q = quests.get(i);
            if (!q.isComplete()) { active = q; break; }
        }
        if (active == null) return;

        ensureFonts(g2);
        g2.setFont(fontPlain14);

        // Build tracker text
        String tracker;
        if (active.steps != null && active.currentStep < active.steps.length) {
            QuestStep step = active.steps[active.currentStep];
            if (step.description != null && !step.description.isEmpty()) {
                tracker = active.name + ": " + step.description;
            } else if (step.count > 1) {
                tracker = active.name + "  (" + active.stepProgress + "/" + step.count + ")";
            } else {
                tracker = active.name;
            }
        } else if (active.steps != null) {
            tracker = active.name;
        } else {
            tracker = active.name + "  (" + active.current + "/" + active.target + ")";
        }

        // Layout — mirror the pill dimensions from UI.drawPlayerLife()
        float sf    = gp.screenWidth / (float) Config.UI_BASE_W;
        int margin  = (int)(12 * sf);
        int pillH   = (int)(28 * sf);
        int pillGap = (int)(5 * sf);
        int maxW    = (int)(230 * sf);   // maximum tracker width (truncate beyond this)
        int padH    = (int)(8 * sf);     // horizontal text padding inside pill

        // Measure text; truncate with … if too wide
        java.awt.FontMetrics fm = g2.getFontMetrics();
        String display = tracker;
        int textW = fm.stringWidth(display);
        if (textW > maxW - padH * 2) {
            // Binary-ish shrink: strip from right until it fits
            while (display.length() > 3 && fm.stringWidth(display + "\u2026") > maxW - padH * 2) {
                display = display.substring(0, display.length() - 1).stripTrailing();
            }
            display = display + "\u2026";
            textW = fm.stringWidth(display);
        }
        int trackerW = Math.min(maxW, textW + padH * 2);

        // Position: right-aligned with the pill stack; vertically below the 3 pills + gap
        int x = gp.screenWidth - margin - trackerW;
        int y = margin + 3 * pillH + 2 * pillGap + (int)(6 * sf);

        // Draw background pill
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.82f));
        g2.setColor(BG);
        g2.fillRoundRect(x, y, trackerW, pillH, 8, 8);
        // Left accent line (quest gold)
        g2.setColor(INCOMPLETE);
        g2.fillRoundRect(x, y + (int)(4 * sf), (int)(3 * sf), pillH - (int)(8 * sf), 3, 3);
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f));

        // Text
        g2.setFont(fontPlain14);
        g2.setColor(INCOMPLETE);
        int textY = y + pillH / 2 + fm.getAscent() / 2 - 1;
        g2.drawString(display, x + padH, textY);
    }

    /** Draw the full quest log screen. */
    public void drawLog(Graphics2D g2) {
        if (!logOpen) return;

        ensureFonts(g2);

        final int W = 520, H = 500;
        final int x = (gp.screenWidth - W) / 2;
        final int y = (gp.screenHeight - H) / 2;
        final int TITLE_H   = 48;
        final int FOOTER_H  = 26;
        final int PADDING   = 16;
        final int contentTop    = y + TITLE_H;
        final int contentBottom = y + H - FOOTER_H;
        final int contentH      = contentBottom - contentTop;

        // ── Background ──
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.97f));
        g2.setColor(BG);
        g2.fillRoundRect(x, y, W, H, 18, 18);
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f));
        g2.setColor(BORDER);
        g2.setStroke(STROKE_2);
        g2.drawRoundRect(x + 2, y + 2, W - 4, H - 4, 16, 16);

        // ── Title bar ──
        g2.setColor(new Color(25, 20, 15, 240));
        g2.fillRect(x + 3, y + 3, W - 6, TITLE_H - 3);
        // thin gold separator under title
        g2.setColor(BORDER);
        g2.setStroke(STROKE_1);
        g2.drawLine(x + PADDING, y + TITLE_H, x + W - PADDING, y + TITLE_H);
        g2.setStroke(STROKE_2);

        g2.setFont(fontBold28);
        g2.setColor(NEW_QUEST);
        g2.drawString("Quest Log", x + PADDING + 2, y + 36);

        // quest count summary (top-right of title)
        int activeCount = 0, doneCount = 0;
        for (int i = 0; i < quests.size(); i++) {
            if (quests.get(i).isComplete()) doneCount++; else activeCount++;
        }
        g2.setFont(fontPlain14b);
        g2.setColor(DESC_COLOR);
        String summary = activeCount + " active  •  " + doneCount + " done";
        int sw = g2.getFontMetrics().stringWidth(summary);
        g2.drawString(summary, x + W - sw - PADDING, y + 36);

        // ── Clip content area ──
        java.awt.Shape oldClip = g2.getClip();
        g2.setClip(x + 4, contentTop, W - 8, contentH);

        int qy = contentTop + 10 - logScrollOffset;

        // ── ACTIVE QUESTS section ──
        if (activeCount > 0) {
            drawSectionHeader(g2, x, qy, W, PADDING, "\u25B6  ACTIVE QUESTS", SECTION_COLOR, 130);
            qy += 34;

            for (int i = 0; i < quests.size(); i++) {
                Quest q = quests.get(i);
                if (q.isComplete()) continue;

                // Determine objective and progress
                String objective = null;
                boolean hasProgress = false;
                int progC = 0, progT = 1;
                String badgeText = null;

                if (q.steps != null && q.currentStep < q.steps.length) {
                    QuestStep step = q.steps[q.currentStep];
                    if (step.description != null && !step.description.isEmpty()) {
                        objective = step.description;
                    } else if (step.count > 1) {
                        objective = String.format("Gather/Kill %d required", step.count);
                    } else {
                        objective = q.description;
                    }

                    // Progress bar values?
                    if (step.count > 1) {
                        hasProgress = true;
                        progC = q.stepProgress;
                        progT = step.count;
                    } else if (q.steps.length > 1) {
                        hasProgress = true;
                        progC = q.currentStep;
                        progT = q.steps.length;
                        badgeText = "Step " + (q.currentStep + 1) + " / " + q.steps.length;
                    }

                } else if (q.steps == null) {
                    objective = q.description;
                    if (q.target > 1) {
                        hasProgress = true;
                        progC = q.current;
                        progT = q.target;
                        badgeText = "Progress " + q.current + " / " + q.target;
                    }
                }

                // Card Height Calculation
                int cardH = 34;
                if (objective != null && !objective.isEmpty()) cardH += 22;
                if (hasProgress) cardH += 18;

                // Draw Card Background
                g2.setColor(CARD_BG);
                g2.fillRoundRect(x + PADDING, qy - 20, W - PADDING * 2, cardH, 12, 12);
                g2.setColor(CARD_BORDER);
                g2.setStroke(STROKE_1);
                g2.drawRoundRect(x + PADDING, qy - 20, W - PADDING * 2, cardH, 12, 12);
                g2.setStroke(STROKE_2);

                // Quest Title
                g2.setFont(fontPlain20);
                g2.setColor(TEXT);
                g2.drawString("\u2694  " + q.name, x + PADDING + 12, qy);

                qy += 6;

                // Objective
                if (objective != null && !objective.isEmpty()) {
                    g2.setFont(fontPlain14b);
                    g2.setColor(OBJECTIVE_COLOR);
                    g2.drawString("\u2192  " + objective, x + PADDING + 20, qy + 14);
                    qy += 22;
                }

                // Progress Bar
                if (hasProgress) {
                    qy += 4;
                    int bx = x + PADDING + 20;
                    int bw = W - PADDING * 2 - 40;
                    g2.setColor(PROG_BG);
                    g2.fillRoundRect(bx, qy, bw, 6, 3, 3);
                    int fillW = (int) ((double) progC / progT * bw);
                    if (fillW > 0) {
                        g2.setColor(PROG_FILL);
                        g2.fillRoundRect(bx, qy, fillW, 6, 3, 3);
                    }
                    g2.setColor(CARD_BORDER);
                    g2.setStroke(STROKE_1);
                    g2.drawRoundRect(bx, qy, bw, 6, 3, 3);
                    g2.setStroke(STROKE_2);

                    if (badgeText == null) badgeText = progC + " / " + progT;
                    g2.setFont(fontPlain14b);
                    g2.setColor(INCOMPLETE);
                    int tw = g2.getFontMetrics().stringWidth(badgeText);
                    g2.drawString(badgeText, bx + bw - tw, qy - 6);
                    qy += 14;
                }

                qy += 26; // Space after card
            }
            qy += 6;
        }

        // ── COMPLETED section ──
        if (doneCount > 0) {
            drawSectionHeader(g2, x, qy, W, PADDING, "\u2713  COMPLETED (" + doneCount + ")", COMPLETE, 150);
            qy += 30;

            for (int i = 0; i < quests.size(); i++) {
                Quest q = quests.get(i);
                if (!q.isComplete()) continue;
                g2.setFont(fontPlain16);
                g2.setColor(DONE_TEXT);
                g2.drawString("\u2714  " + q.name, x + PADDING + 14, qy);
                qy += 28;
            }
        }

        // Update scroll max
        logScrollMax = Math.max(0, (qy + logScrollOffset) - contentBottom + 8);

        g2.setClip(oldClip);

        // ── Footer ──
        g2.setColor(new Color(20, 16, 12, 230));
        g2.fillRect(x + 3, contentBottom, W - 6, FOOTER_H - 1);
        g2.setColor(BORDER);
        g2.setStroke(STROKE_1);
        g2.drawLine(x + PADDING, contentBottom, x + W - PADDING, contentBottom);
        g2.setStroke(STROKE_2);
        g2.setFont(fontPlain14b);
        g2.setColor(HINT_COLOR);
        String hint = logScrollMax > 0 ? "\u2191 \u2193 to scroll  \u2022  Q to close" : "Q to close";
        int hw = g2.getFontMetrics().stringWidth(hint);
        g2.drawString(hint, x + (W - hw) / 2, contentBottom + 17);
    }

    /** Helper: draws a section header with label and a horizontal rule. */
    private void drawSectionHeader(Graphics2D g2, int x, int qy, int W, int pad,
                                    String label, Color col, int lineStart) {
        g2.setFont(fontBold16);
        g2.setColor(col);
        g2.drawString(label, x + pad, qy + 14);
        g2.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 80));
        g2.setStroke(STROKE_1);
        g2.drawLine(x + pad + lineStart, qy + 10, x + W - pad, qy + 10);
        g2.setStroke(STROKE_2);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  INTERNAL HELPERS
    // ══════════════════════════════════════════════════════════════════════

    /** Start all quests marked "autoStart": true in the registry. */
    private void startAutoQuests() {
        for (QuestDef def : registry) {
            if ("true".equals(def.props.get("autoStart"))) {
                String id = def.props.get("id");
                // bypass prerequisite for auto-starts
                String name = strVal(def.props, "name", id);
                String desc = strVal(def.props, "description", "");
                int target  = intVal(def.props, "target", 1);
                QuestStep[] steps = buildSteps(def);
                addQuestInternal(id, name, desc, target, steps, 0, 0, 0, true);
            }
        }
    }

    /** Build runtime QuestStep[] from a QuestDef. Returns null if no steps. */
    private static QuestStep[] buildSteps(QuestDef def) {
        if (def == null || def.stepDefs.isEmpty()) return null;
        QuestStep[] steps = new QuestStep[def.stepDefs.size()];
        for (int i = 0; i < def.stepDefs.size(); i++) {
            steps[i] = new QuestStep(def.stepDefs.get(i));
        }
        return steps;
    }

    private void addQuestInternal(String id, String name, String description, int target,
                                   QuestStep[] steps, int current,
                                   int currentStep, int stepProgress,
                                   boolean announce) {
        // If already tracked, just update metadata
        for (int i = 0, n = quests.size(); i < n; i++) {
            Quest q = quests.get(i);
            if (q.id.equals(id)) {
                q.name = name;
                q.description = description;
                q.target = Math.max(1, target);
                q.current = Math.max(0, Math.min(current, q.target));
                return;
            }
        }
        Quest q = new Quest(id, name, description, Math.max(1, target));
        q.steps = steps;
        q.current = Math.max(0, Math.min(current, q.target));
        q.currentStep = currentStep;
        q.stepProgress = stepProgress;
        quests.add(q);
        if (announce) gp.ui.addMessage("New quest: " + name, NEW_QUEST);
    }

    /** Check if quest just completed and handle rewards + chaining. */
    private void checkCompletion(Quest q) {
        if (q.isComplete()) {
            gp.ui.addMessage("Quest complete: " + q.name + "!", COMPLETE);
            grantRewards(q.id);
            chainNext(q.id);
        }
    }

    /** Grant rewards defined in quests.json when a quest completes. */
    private void grantRewards(String questId) {
        QuestDef def = findDef(questId);
        if (def == null) return;

        int coins = intVal(def.props, "rewardCoins", 0);
        if (coins > 0) {
            gp.player.coin += coins;
            gp.ui.addMessage("Received " + coins + " coins!", NEW_QUEST);
        }

        String itemId = strVal(def.props, "rewardItemId", "");
        if (!itemId.isEmpty()) {
            entity.Entity rewardItem = data.ItemFactory.create(gp, itemId);
            if (rewardItem != null) {
                if (gp.player.canObtainItem(rewardItem)) {
                    gp.ui.addMessage("Received " + rewardItem.name + "!", Color.WHITE);
                } else {
                    gp.ui.addMessage("Inventory full!", Color.RED);
                }
            }
        }

        String fragId = strVal(def.props, "rewardFragmentId", "");
        if (!fragId.isEmpty() && gp.memoryJournal != null) {
            gp.memoryJournal.addById(fragId);
        }
    }

    /** Auto-start the next quest in the chain (if configured). */
    private void chainNext(String questId) {
        QuestDef def = findDef(questId);
        if (def == null) return;
        String nextId = strVal(def.props, "chainQuestId", "");
        if (!nextId.isEmpty()) {
            // Chaining bypasses prerequisite check — use addQuest directly
            QuestDef nextDef = findDef(nextId);
            if (nextDef != null) {
                String name = strVal(nextDef.props, "name", nextId);
                String desc = strVal(nextDef.props, "description", "");
                int target  = intVal(nextDef.props, "target", 1);
                QuestStep[] steps = buildSteps(nextDef);
                addQuestInternal(nextId, name, desc, target, steps, 0, 0, 0, true);
            } else {
                System.out.println("[QuestManager] Chain target '" + nextId + "' not found in quests.json");
            }
        }
    }

    // ── Internal Quest data ──

    private static class Quest {
        String id;
        String name;
        String description;
        int current;
        int target;

        QuestStep[] steps;        // null = legacy counter-based quest
        int currentStep = 0;      // which step we're on (0-indexed)
        int stepProgress = 0;     // progress within current step (for collect/kill/go)

        Quest(String id, String name, String description, int target) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.current = 0;
            this.target = target;
        }

        boolean isComplete() {
            if (steps != null) return currentStep >= steps.length;
            return current >= target;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  JSON PARSING
    // ══════════════════════════════════════════════════════════════════════

    private static String strVal(Map<String, String> m, String key, String fallback) {
        String v = m.get(key);
        return (v == null || v.isEmpty()) ? fallback : v;
    }

    private static int intVal(Map<String, String> m, String key, int fallback) {
        String v = m.get(key);
        if (v == null) return fallback;
        try { return Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static void parseJsonArray(String json) {
        registry.clear();
        json = json.trim();
        if (!json.startsWith("[") || !json.endsWith("]")) return;
        json = json.substring(1, json.length() - 1).trim();

        int depth = 0, start = -1;
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{') { if (depth == 0) start = i; depth++; }
            else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) { parseQuestObject(json.substring(start + 1, i)); start = -1; }
            }
        }
    }

    private static void parseQuestObject(String obj) {
        QuestDef def = new QuestDef();

        // Extract "steps": [ ... ] array before flat key-value parsing
        int stepsIdx = obj.indexOf("\"steps\"");
        if (stepsIdx >= 0) {
            int bracketStart = obj.indexOf('[', stepsIdx);
            if (bracketStart >= 0) {
                int bracketEnd = findMatchingBracket(obj, bracketStart);
                if (bracketEnd >= 0) {
                    String stepsJson = obj.substring(bracketStart + 1, bracketEnd);
                    parseStepArray(stepsJson, def.stepDefs);
                    // Remove the steps section so it doesn't interfere with flat parsing
                    obj = obj.substring(0, stepsIdx) + obj.substring(bracketEnd + 1);
                }
            }
        }

        parseKeyValues(obj, def.props);
        if (def.props.containsKey("id")) registry.add(def);
    }

    /** Parse an array of step objects: { ... }, { ... } */
    private static void parseStepArray(String json, ArrayList<Map<String, String>> steps) {
        int i = 0;
        while (i < json.length()) {
            // Find opening brace (skip strings)
            int braceStart = -1;
            boolean inStr = false;
            for (int j = i; j < json.length(); j++) {
                char c = json.charAt(j);
                if (c == '"' && (j == 0 || json.charAt(j - 1) != '\\')) { inStr = !inStr; continue; }
                if (!inStr && c == '{') { braceStart = j; break; }
            }
            if (braceStart < 0) break;
            int braceEnd = findMatchingBrace(json, braceStart);
            if (braceEnd < 0) break;

            Map<String, String> stepMap = new HashMap<>();
            parseKeyValues(json.substring(braceStart + 1, braceEnd), stepMap);
            steps.add(stepMap);
            i = braceEnd + 1;
        }
    }

    private static int findMatchingBrace(String text, int open) {
        int depth = 0;
        boolean inStr = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && (i == 0 || text.charAt(i - 1) != '\\')) { inStr = !inStr; continue; }
            if (inStr) continue;
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    private static int findMatchingBracket(String text, int open) {
        int depth = 0;
        boolean inStr = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && (i == 0 || text.charAt(i - 1) != '\\')) { inStr = !inStr; continue; }
            if (inStr) continue;
            if (c == '[') depth++;
            else if (c == ']') { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    private static void parseKeyValues(String text, Map<String, String> map) {
        int i = 0;
        while (i < text.length()) {
            int ks = text.indexOf('"', i);       if (ks < 0) break;
            int ke = text.indexOf('"', ks + 1);  if (ke < 0) break;
            String key = text.substring(ks + 1, ke);
            int colon = text.indexOf(':', ke);    if (colon < 0) break;
            int vs = colon + 1;
            while (vs < text.length() && text.charAt(vs) == ' ') vs++;

            String value;
            if (vs < text.length() && text.charAt(vs) == '"') {
                int ve = vs + 1;
                while (ve < text.length()) {
                    char vc = text.charAt(ve);
                    if (vc == '\\') { ve += 2; continue; }
                    if (vc == '"') break;
                    ve++;
                }
                if (ve >= text.length()) break;
                value = text.substring(vs + 1, ve)
                        .replace("\\n", "\n").replace("\\t", "\t")
                        .replace("\\\"", "\"").replace("\\\\", "\\");
                i = ve + 1;
            } else {
                int ve = vs;
                while (ve < text.length() && text.charAt(ve) != ',' && text.charAt(ve) != '}') ve++;
                value = text.substring(vs, ve).trim();
                i = ve + 1;
            }
            map.put(key, value);
        }
    }
}
