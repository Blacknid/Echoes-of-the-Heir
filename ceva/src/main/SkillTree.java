package main;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import entity.Player;

public class SkillTree {

    public static class SkillNode {
        public final String id;
        public final String name;
        public final String description;
        public final int cost;
        public final int col;
        public final int row;
        public final String requires;
        public boolean unlocked;

        public SkillNode(String id, String name, String description, int cost, int col, int row, String requires) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.cost = cost;
            this.col = col;
            this.row = row;
            this.requires = requires;
            this.unlocked = false;
        }
    }

    private final SkillNode[] nodes;
    private final Map<String, Integer> nodeIndexById = new HashMap<>();
    private int revealMaxColCache = -1;
    public int selectedIndex = 0;

    // ── Minecraft-style scrolling list state ──
    // The list is rendered as a vertical scrollable menu; only a window of entries
    // is visible at a time. scrollOffset is the index of the row at the top of
    // the visible window and is kept in sync with selectedIndex by the UI.
    public int scrollOffset = 0;

    public SkillTree() {
        nodes = loadFromJson();
        for (int i = 0; i < nodes.length; i++) {
            nodeIndexById.put(nodes[i].id, i);
        }
    }

    /** Load skill nodes from res/data/skilltree.json. */
    private SkillNode[] loadFromJson() {
        try (InputStream is = getClass().getResourceAsStream("/res/data/skilltree.json")) {
            if (is == null) throw new RuntimeException("skilltree.json not found in resources");
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);

            ArrayList<SkillNode> list = parseSkillArray(sb.toString());
            if (list.isEmpty()) throw new RuntimeException("skilltree.json parsed 0 nodes");
            System.out.println("[SkillTree] Loaded " + list.size() + " nodes from JSON");
            return list.toArray(new SkillNode[0]);
        } catch (Exception e) {
            throw new RuntimeException("[SkillTree] Failed to load skilltree.json: " + e.getMessage(), e);
        }
    }

    private ArrayList<SkillNode> parseSkillArray(String json) {
        ArrayList<SkillNode> result = new ArrayList<>();
        json = json.trim();
        if (!json.startsWith("[") || !json.endsWith("]")) return result;
        json = json.substring(1, json.length() - 1).trim();

        int depth = 0; int start = -1;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') { if (depth == 0) start = i; depth++; }
            else if (c == '}') { depth--; if (depth == 0 && start >= 0) {
                SkillNode n = parseNode(json.substring(start + 1, i));
                if (n != null) result.add(n);
                start = -1;
            }}
        }
        return result;
    }

    private SkillNode parseNode(String obj) {
        Map<String, String> m = new HashMap<>();
        int i = 0;
        while (i < obj.length()) {
            int ks = obj.indexOf('"', i); if (ks < 0) break;
            int ke = obj.indexOf('"', ks + 1); if (ke < 0) break;
            String key = obj.substring(ks + 1, ke);
            int colon = obj.indexOf(':', ke); if (colon < 0) break;
            int vs = colon + 1;
            while (vs < obj.length() && obj.charAt(vs) == ' ') vs++;
            String val;
            if (vs < obj.length() && obj.charAt(vs) == '"') {
                int ve = obj.indexOf('"', vs + 1); if (ve < 0) break;
                val = obj.substring(vs + 1, ve); i = ve + 1;
            } else if (vs < obj.length() && obj.substring(vs).startsWith("null")) {
                val = null; i = vs + 4;
            } else {
                int ve = vs;
                while (ve < obj.length() && obj.charAt(ve) != ',' && obj.charAt(ve) != '}') ve++;
                val = obj.substring(vs, ve).trim(); i = ve + 1;
            }
            m.put(key, val);
        }
        String id = m.get("id");
        if (id == null) return null;
        String name = m.getOrDefault("name", id);
        String desc = m.getOrDefault("description", "");
        int cost = intVal(m, "cost", 1);
        int col = intVal(m, "col", 0);
        int row = intVal(m, "row", 0);
        String req = m.get("requires");
        return new SkillNode(id, name, desc, cost, col, row, req);
    }

    private static int intVal(Map<String, String> m, String key, int fallback) {
        String v = m.get(key); if (v == null) return fallback;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    public SkillNode[] getNodes() {
        return nodes;
    }

    /** Reset all nodes to locked state. Called on New Game. */
    public void reset() {
        for (SkillNode n : nodes) {
            n.unlocked = false;
        }
        revealMaxColCache = -1;
        selectedIndex = 0;
        scrollOffset = 0;
    }

    /** Mark a node as unlocked by ID without applying player stat effects. Used when restoring save state. */
    public void markUnlocked(String id) {
        Integer idx = nodeIndexById.get(id);
        if (idx == null) return;
        nodes[idx].unlocked = true;
        revealMaxColCache = -1;
    }

    public int findIndexById(String id) {
        if (id == null) return -1;
        Integer index = nodeIndexById.get(id);
        return index != null ? index : -1;
    }

    public boolean canUnlock(Player player, int idx) {
        if (idx < 0 || idx >= nodes.length) return false;
        if (!isRevealed(idx)) return false;
        SkillNode n = nodes[idx];
        if (n.unlocked) return false;
        if (player.skillPoints < n.cost) return false;
        if (n.requires == null) return true;

        int reqIdx = findIndexById(n.requires);
        return reqIdx >= 0 && nodes[reqIdx].unlocked;
    }

    public boolean unlockSelected(Player player) {
        return unlock(player, selectedIndex);
    }

    public boolean unlock(Player player, int idx) {
        if (!canUnlock(player, idx)) return false;
        SkillNode n = nodes[idx];
        player.skillPoints -= n.cost;
        n.unlocked = true;
        revealMaxColCache = -1;
        player.applySkillNodeEffect(n.id);
        return true;
    }

    public int getRevealMaxCol() {
        if (revealMaxColCache >= 0) {
            return revealMaxColCache;
        }
        int farthestUnlockedCol = 0;
        for (SkillNode n : nodes) {
            if (n.unlocked && n.col > farthestUnlockedCol) {
                farthestUnlockedCol = n.col;
            }
        }
        revealMaxColCache = farthestUnlockedCol + 1;
        return revealMaxColCache;
    }

    public boolean isRevealed(int idx) {
        if (idx < 0 || idx >= nodes.length) return false;
        return nodes[idx].col <= getRevealMaxCol();
    }

    /**
     * Linear cursor movement for the scrolling list UI.
     * delta = +1 moves down one entry, -1 moves up; clamped to valid range.
     */
    public void moveCursor(int delta) {
        int ni = selectedIndex + delta;
        if (ni < 0) ni = 0;
        if (ni >= nodes.length) ni = nodes.length - 1;
        selectedIndex = ni;
    }

    public void moveSelection(Player player, int dx, int dy) {
        int from = selectedIndex;
        SkillNode cur = nodes[from];

        int best = from;
        int bestScore = Integer.MAX_VALUE;

        for (int i = 0; i < nodes.length; i++) {
            if (i == from) continue;
            SkillNode n = nodes[i];
            int colDiff = n.col - cur.col;
            int rowDiff = n.row - cur.row;

            if (dx != 0) {
                if (dx > 0 && colDiff <= 0) continue;
                if (dx < 0 && colDiff >= 0) continue;
            }
            if (dy != 0) {
                if (dy > 0 && rowDiff <= 0) continue;
                if (dy < 0 && rowDiff >= 0) continue;
            }

            int score = Math.abs(colDiff) * 10 + Math.abs(rowDiff) * 8;
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }

        selectedIndex = best;
    }

    public void moveSelection(int dx, int dy) {
        moveSelection(null, dx, dy);
    }
}
