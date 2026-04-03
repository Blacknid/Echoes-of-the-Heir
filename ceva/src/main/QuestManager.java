package main;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.util.ArrayList;

/**
 * Simple quest tracking system with objective display.
 * Quests have a name, description, and numeric progress (current/target).
 */
public class QuestManager {

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

    private final GamePanel gp;
    private final ArrayList<Quest> quests = new ArrayList<>();
    private boolean logOpen = false;

    // Display
    private static final Color BG = new Color(15, 12, 20, 210);
    private static final Color BORDER = new Color(180, 160, 100);
    private static final Color TEXT = new Color(220, 215, 200);
    private static final Color COMPLETE = new Color(80, 200, 80);
    private static final Color INCOMPLETE = new Color(200, 180, 100);

    // Cached fonts and colors to avoid per-frame allocations
    private static final java.awt.BasicStroke STROKE_2 = new java.awt.BasicStroke(2f);
    private static final Color CHECKBOX_GRAY = new Color(100, 100, 100);
    private static final Color DESC_COLOR = new Color(160, 155, 145);
    private static final Color HINT_COLOR = new Color(150, 150, 150);
    private Font fontPlain14, fontBold28, fontPlain20, fontPlain16, fontPlain14b;

    private void ensureFonts(Graphics2D g2) {
        if (fontPlain14 == null) {
            Font base = g2.getFont();
            fontPlain14 = base.deriveFont(Font.PLAIN, 14f);
            fontBold28  = base.deriveFont(Font.BOLD, 28f);
            fontPlain20 = base.deriveFont(Font.PLAIN, 20f);
            fontPlain16 = base.deriveFont(16f);
            fontPlain14b = base.deriveFont(14f);
        }
    }

    public QuestManager(GamePanel gp) {
        this.gp = gp;
    }

    /** Add a new quest. */
    public void addQuest(String id, String name, String description, int target) {
        addQuest(id, name, description, target, 0, true);
    }

    public void restoreQuest(String id, String name, String description, int target, int current) {
        addQuest(id, name, description, target, current, false);
    }

    public void clearQuests() {
        quests.clear();
    }

    public boolean hasQuest(String id) {
        for (Quest q : quests) {
            if (q.id.equals(id)) return true;
        }
        return false;
    }

    public ArrayList<QuestState> getQuestStates() {
        ArrayList<QuestState> states = new ArrayList<>();
        for (Quest q : quests) {
            states.add(new QuestState(q.id, q.name, q.description, q.current, q.target));
        }
        return states;
    }

    private void addQuest(String id, String name, String description, int target, int current, boolean announce) {
        for (Quest q : quests) {
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
        if (announce) gp.ui.addMessage("New quest: " + name, new Color(255, 220, 80));
    }

    /** Increment progress for a quest by id. Returns true if quest just completed. */
    public boolean progress(String id, int amount) {
        for (Quest q : quests) {
            if (q.id.equals(id) && !q.isComplete()) {
                q.current = Math.min(q.current + amount, q.target);
                if (q.isComplete()) {
                    gp.ui.addMessage("Quest complete: " + q.name + "!", COMPLETE);
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    /** Check if a quest is complete. */
    public boolean isComplete(String id) {
        for (Quest q : quests) {
            if (q.id.equals(id)) return q.isComplete();
        }
        return false;
    }

    public void toggleLog() {
        logOpen = !logOpen;
    }

    public boolean isLogOpen() {
        return logOpen;
    }

    /** Draw the current objective tracker (small overlay under minimap). */
    public void drawTracker(Graphics2D g2) {
        // Find first incomplete quest
        Quest active = null;
        for (Quest q : quests) {
            if (!q.isComplete()) { active = q; break; }
        }
        if (active == null) return;

        int x = gp.screenWidth - 180;
        int y = 150; // below minimap area
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

        // Background
        g2.setColor(BG);
        g2.fillRoundRect(x, y, w, h, 16, 16);
        g2.setColor(BORDER);
        g2.setStroke(STROKE_2);
        g2.drawRoundRect(x + 3, y + 3, w - 6, h - 6, 12, 12);

        // Title
        ensureFonts(g2);
        g2.setFont(fontBold28);
        g2.setColor(TEXT);
        g2.drawString("Quest Log", x + 20, y + 35);

        // Quests
        g2.setFont(fontPlain20);
        int qy = y + 65;
        for (Quest q : quests) {
            boolean done = q.isComplete();
            // Checkbox
            g2.setColor(done ? COMPLETE : CHECKBOX_GRAY);
            g2.drawRect(x + 20, qy - 14, 16, 16);
            if (done) {
                g2.drawLine(x + 22, qy - 6, x + 28, qy);
                g2.drawLine(x + 28, qy, x + 34, qy - 12);
            }
            // Name
            g2.setColor(done ? COMPLETE : TEXT);
            g2.drawString(q.name, x + 44, qy);
            // Progress
            String prog = q.current + "/" + q.target;
            g2.setColor(done ? COMPLETE : INCOMPLETE);
            g2.setFont(fontPlain16);
            g2.drawString(prog, x + w - 60, qy);
            g2.setFont(fontPlain20);

            // Description
            g2.setColor(DESC_COLOR);
            g2.setFont(fontPlain14b);
            g2.drawString(q.description, x + 44, qy + 18);
            g2.setFont(fontPlain20);

            qy += 50;
            if (qy > y + h - 30) break;
        }

        // Close hint
        g2.setFont(fontPlain14b);
        g2.setColor(HINT_COLOR);
        g2.drawString("Press Q to close", x + w / 2 - 40, y + h - 12);
    }

    /** Internal quest data. */
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

        boolean isComplete() {
            return current >= target;
        }
    }
}
