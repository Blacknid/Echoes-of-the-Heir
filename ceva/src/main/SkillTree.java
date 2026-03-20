package main;

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
    public int selectedIndex = 0;

    public SkillTree() {
        nodes = new SkillNode[] {
            new SkillNode("VITALITY_CORE", "Vitality Core", "+2 max HP and full heal", 1, 0, 0, null),
            new SkillNode("BLADE_MASTERY", "Blade Mastery", "+15% melee damage", 1, 1, 0, "VITALITY_CORE"),
            new SkillNode("AETHER_RESERVE", "Aether Reserve", "+2 max mana and full mana", 1, 1, 1, "VITALITY_CORE"),
            new SkillNode("WINDSTEP", "Windstep", "Unlocks Dodge Roll and reduces its cooldown", 1, 2, 0, "BLADE_MASTERY"),
            new SkillNode("PHASE_TUNING", "Phase Tuning", "Blink cooldown reduced", 1, 2, 1, "AETHER_RESERVE"),
            new SkillNode("IRON_WILL", "Iron Will", "Take 15% less damage", 2, 3, 0, "WINDSTEP"),
            new SkillNode("SHOCKWAVE", "Shockwave", "Unleash a melee burst around you", 1, 3, 1, "PHASE_TUNING"),
            new SkillNode("VOID_SNARE", "Void Snare", "Pull nearby enemies toward you", 2, 4, 0, "IRON_WILL"),
            new SkillNode("FROST_NOVA", "Frost Nova", "Freeze nearby enemies briefly", 2, 4, 1, "SHOCKWAVE"),
            new SkillNode("OVERDRIVE", "Overdrive", "Short buff: speed and melee damage", 2, 4, 2, "SHOCKWAVE")
        };
    }

    public SkillNode[] getNodes() {
        return nodes;
    }

    public int findIndexById(String id) {
        if (id == null) return -1;
        for (int i = 0; i < nodes.length; i++) {
            if (id.equals(nodes[i].id)) return i;
        }
        return -1;
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
        player.applySkillNodeEffect(n.id);
        return true;
    }

    public int getRevealMaxCol() {
        int farthestUnlockedCol = 0;
        for (SkillNode n : nodes) {
            if (n.unlocked && n.col > farthestUnlockedCol) {
                farthestUnlockedCol = n.col;
            }
        }
        // Nodes that are 2+ columns ahead stay hidden.
        return farthestUnlockedCol + 1;
    }

    public boolean isRevealed(int idx) {
        if (idx < 0 || idx >= nodes.length) return false;
        return nodes[idx].col <= getRevealMaxCol();
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
