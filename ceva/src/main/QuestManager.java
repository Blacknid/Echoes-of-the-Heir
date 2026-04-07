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
 * Data-driven quest system.
 *
 * Quest templates live in  res/data/quests.json  so you can add, edit and
 * remove quests without touching Java code.  Each template looks like this:
 *
 * <pre>{@code
 * {
 *   "id":              "find_exit",          // unique key (required)
 *   "name":            "Find the Exit",      // display name
 *   "description":     "Escape the cave",    // one-liner shown in log
 *   "target":          1,                    // how many "progress" ticks to complete
 *   "autoStart":       true,                 // start automatically when the map loads
 *   "rewardCoins":     50,                   // coins given on completion (0 = none)
 *   "rewardItemId":    "iron_sword",         // ItemFactory id given on completion ("" = none)
 *   "rewardFragmentId":"frag_cave",          // MemoryJournal fragment id ("" = none)
 *   "chainQuestId":    "talk_to_elder"       // quest id auto-started on completion ("" = none)
 * }
 * }</pre>
 *
 * <b>How to add a new quest:</b>  just add an entry in quests.json.<br>
 * <b>How to start it:</b>  call {@code questManager.addQuest("my_quest_id")}
 * from code, or set {@code "autoStart": true} so it starts on its own.<br>
 * <b>How to progress it:</b>  call {@code questManager.progress("my_quest_id", 1)}.<br>
 * <b>How to remove it:</b>  call {@code questManager.removeQuest("my_quest_id")}.<br>
 *
 * Quests can also be defined in Tiled maps (type = QuestDefinition) — those
 * still work exactly as before through the 4-arg {@link #addQuest(String,String,String,int)} method.
 */
public class QuestManager {

    // ── Public snapshot returned by getQuestStates() (used by SaveLoad) ──

    public static class QuestState {
        public final String id;
        public final String name;
        public final String description;
        public final int current;
        public final int target;

        public QuestState(String id, String name, String description, int current, int target) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.current = current;
            this.target = target;
        }
    }

    // ── Quest registry (loaded once from quests.json) ──

    private static final ArrayList<Map<String, String>> registry = new ArrayList<>();
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

    /** Look up a quest template by id.  Returns null if not in the registry. */
    private static Map<String, String> findDef(String id) {
        loadRegistry();
        for (Map<String, String> def : registry) {
            if (id.equals(def.get("id"))) return def;
        }
        return null;
    }

    // ── Instance state ──

    private final GamePanel gp;
    private final ArrayList<Quest> quests = new ArrayList<>();
    private boolean logOpen = false;

    // Display colours / fonts (cached, not allocated per frame)
    private static final Color BG         = new Color(15, 12, 20, 210);
    private static final Color BORDER     = new Color(180, 160, 100);
    private static final Color TEXT       = new Color(220, 215, 200);
    private static final Color COMPLETE   = new Color(80, 200, 80);
    private static final Color INCOMPLETE = new Color(200, 180, 100);
    private static final Color NEW_QUEST  = new Color(255, 220, 80);

    private static final java.awt.BasicStroke STROKE_2 = new java.awt.BasicStroke(2f);
    private static final Color CHECKBOX_GRAY = new Color(100, 100, 100);
    private static final Color DESC_COLOR    = new Color(160, 155, 145);
    private static final Color HINT_COLOR    = new Color(150, 150, 150);
    private Font fontPlain14, fontBold28, fontPlain20, fontPlain16, fontPlain14b;

    private void ensureFonts(Graphics2D g2) {
        if (fontPlain14 == null) {
            Font base = g2.getFont();
            fontPlain14  = base.deriveFont(Font.PLAIN, 14f);
            fontBold28   = base.deriveFont(Font.BOLD, 28f);
            fontPlain20  = base.deriveFont(Font.PLAIN, 20f);
            fontPlain16  = base.deriveFont(16f);
            fontPlain14b = base.deriveFont(14f);
        }
    }

    // ── Constructor ──

    public QuestManager(GamePanel gp) {
        this.gp = gp;
        loadRegistry();             // ensure definitions are available
        startAutoQuests();          // start any quests marked autoStart
    }

    // ══════════════════════════════════════════════════════════════════════
    //  PUBLIC API — adding / removing / progressing quests
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Start a quest by its id (looks it up in quests.json).
     * If the quest is already active it is silently ignored.
     * If the id is not in the registry nothing happens (a warning is printed).
     */
    public void addQuest(String id) {
        Map<String, String> def = findDef(id);
        if (def == null) {
            System.out.println("[QuestManager] WARNING — quest id '" + id + "' not found in quests.json");
            return;
        }
        String name = strVal(def, "name", id);
        String desc = strVal(def, "description", "");
        int target  = intVal(def, "target", 1);
        addQuestInternal(id, name, desc, target, 0, true);
    }

    /**
     * Start a quest with explicit parameters (backward-compatible).
     * Used by Tiled QuestDefinition events and legacy code.
     */
    public void addQuest(String id, String name, String description, int target) {
        addQuestInternal(id, name, description, target, 0, true);
    }

    /** Restore a quest from a save file (no announcement). */
    public void restoreQuest(String id, String name, String description, int target, int current) {
        addQuestInternal(id, name, description, target, current, false);
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
     * Returns {@code true} if the quest <i>just now</i> became complete.
     * Automatically grants rewards and chains the next quest (if configured).
     */
    public boolean progress(String id, int amount) {
        for (int i = 0, n = quests.size(); i < n; i++) {
            Quest q = quests.get(i);
            if (q.id.equals(id) && !q.isComplete()) {
                q.current = Math.min(q.current + amount, q.target);
                if (q.isComplete()) {
                    gp.ui.addMessage("Quest complete: " + q.name + "!", COMPLETE);
                    grantRewards(q.id);
                    chainNext(q.id);
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    /** Get a read-only snapshot of all quests (for SaveLoad). */
    public ArrayList<QuestState> getQuestStates() {
        ArrayList<QuestState> states = new ArrayList<>();
        for (int i = 0, n = quests.size(); i < n; i++) {
            Quest q = quests.get(i);
            states.add(new QuestState(q.id, q.name, q.description, q.current, q.target));
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

    /** Draw the current objective tracker (small overlay under minimap). */
    public void drawTracker(Graphics2D g2) {
        Quest active = null;
        for (int i = 0, n = quests.size(); i < n; i++) {
            Quest q = quests.get(i);
            if (!q.isComplete()) { active = q; break; }
        }
        if (active == null) return;

        int x = gp.screenWidth - 180;
        int y = 150;
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.75f));
        g2.setColor(BG);
        g2.fillRoundRect(x, y, 168, 36, 8, 8);
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f));

        ensureFonts(g2);
        g2.setFont(fontPlain14);
        g2.setColor(INCOMPLETE);
        String tracker = active.name + "  (" + active.current + "/" + active.target + ")";
        g2.drawString(tracker, x + 8, y + 23);
    }

    /** Draw the full quest log screen. */
    public void drawLog(Graphics2D g2) {
        if (!logOpen) return;

        int w = 400, h = 350;
        int x = (gp.screenWidth - w) / 2;
        int y = (gp.screenHeight - h) / 2;

        g2.setColor(BG);
        g2.fillRoundRect(x, y, w, h, 16, 16);
        g2.setColor(BORDER);
        g2.setStroke(STROKE_2);
        g2.drawRoundRect(x + 3, y + 3, w - 6, h - 6, 12, 12);

        ensureFonts(g2);
        g2.setFont(fontBold28);
        g2.setColor(TEXT);
        g2.drawString("Quest Log", x + 20, y + 35);

        g2.setFont(fontPlain20);
        int qy = y + 65;
        for (int i = 0, n = quests.size(); i < n; i++) {
            Quest q = quests.get(i);
            boolean done = q.isComplete();
            g2.setColor(done ? COMPLETE : CHECKBOX_GRAY);
            g2.drawRect(x + 20, qy - 14, 16, 16);
            if (done) {
                g2.drawLine(x + 22, qy - 6, x + 28, qy);
                g2.drawLine(x + 28, qy, x + 34, qy - 12);
            }
            g2.setColor(done ? COMPLETE : TEXT);
            g2.drawString(q.name, x + 44, qy);

            String prog = q.current + "/" + q.target;
            g2.setColor(done ? COMPLETE : INCOMPLETE);
            g2.setFont(fontPlain16);
            g2.drawString(prog, x + w - 60, qy);
            g2.setFont(fontPlain20);

            g2.setColor(DESC_COLOR);
            g2.setFont(fontPlain14b);
            String[] descLines = q.description.split("\\n", -1);
            for (int dl = 0; dl < descLines.length; dl++) {
                g2.drawString(descLines[dl].trim(), x + 44, qy + 18 + dl * 16);
            }
            g2.setFont(fontPlain20);

            qy += 50 + (descLines.length - 1) * 16;
            if (qy > y + h - 30) break;
        }

        g2.setFont(fontPlain14b);
        g2.setColor(HINT_COLOR);
        g2.drawString("Press Q to close", x + w / 2 - 40, y + h - 12);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  INTERNAL HELPERS
    // ══════════════════════════════════════════════════════════════════════

    /** Start all quests marked "autoStart": true in the registry. */
    private void startAutoQuests() {
        for (Map<String, String> def : registry) {
            if ("true".equals(def.get("autoStart"))) {
                String id   = def.get("id");
                String name = strVal(def, "name", id);
                String desc = strVal(def, "description", "");
                int target  = intVal(def, "target", 1);
                addQuestInternal(id, name, desc, target, 0, true);
            }
        }
    }

    private void addQuestInternal(String id, String name, String description, int target, int current, boolean announce) {
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
        q.current = Math.max(0, Math.min(current, q.target));
        quests.add(q);
        if (announce) gp.ui.addMessage("New quest: " + name, NEW_QUEST);
    }

    /** Grant rewards defined in quests.json when a quest completes. */
    private void grantRewards(String questId) {
        Map<String, String> def = findDef(questId);
        if (def == null) return;

        // Coins
        int coins = intVal(def, "rewardCoins", 0);
        if (coins > 0) {
            gp.player.coin += coins;
            gp.ui.addMessage("Received " + coins + " coins!", NEW_QUEST);
        }

        // Item (from ItemFactory)
        String itemId = strVal(def, "rewardItemId", "");
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

        // Memory fragment
        String fragId = strVal(def, "rewardFragmentId", "");
        if (!fragId.isEmpty() && gp.memoryJournal != null) {
            gp.memoryJournal.addById(fragId);
        }
    }

    /** Auto-start the next quest in the chain (if configured). */
    private void chainNext(String questId) {
        Map<String, String> def = findDef(questId);
        if (def == null) return;
        String nextId = strVal(def, "chainQuestId", "");
        if (!nextId.isEmpty()) {
            addQuest(nextId);
        }
    }

    // ── Internal Quest data ──

    private static class Quest {
        String id;
        String name;
        String description;
        int current;
        int target;

        Quest(String id, String name, String description, int target) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.current = 0;
            this.target = target;
        }

        boolean isComplete() { return current >= target; }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  JSON PARSING  (same lightweight approach as ItemFactory)
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
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') { if (depth == 0) start = i; depth++; }
            else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) { parseObject(json.substring(start + 1, i)); start = -1; }
            }
        }
    }

    private static void parseObject(String obj) {
        Map<String, String> map = new HashMap<>();
        parseKeyValues(obj, map);
        if (map.containsKey("id")) registry.add(map);
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
