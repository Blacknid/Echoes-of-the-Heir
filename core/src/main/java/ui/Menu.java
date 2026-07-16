package ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import gfx.Color;
import gfx.Font;
import gfx.FontMetrics;
import gfx.GdxRenderer;
import ui.UI.PanelTheme;

/**
 * A declarative menu: build it as a flat list of {@link MenuItem}s, then let it draw and navigate
 * itself. Adding a button is one line ({@code .button("Quit", () -> ...)}), no array-index sync
 * between the draw code (UI.java) and the input code (KeyHandler.java) like before.
 *
 * <p>Usage:
 * <pre>
 * Menu m = Menu.of("Options", THEME_OPTIONS)
 *     .button("Resume", () -> gp.gameState = playState)
 *     .button("Quit",   () -> System.exit(0));
 * // draw:     m.draw(ui, g2, x, y, w, h);
 * // navigate: m.moveDown(); m.activate(); ...
 * </pre>
 *
 * <p>Layout is automatic: rows are stacked, buttons stretch to the window width, and if the rows
 * don't fit the given height the row height/gap shrink proportionally so nothing spills past the
 * window. Drawing delegates to {@link UI#drawButton} (nine-slice Button.png with vector fallback)
 * and reuses UI's cached fonts/colors, so no new per-frame allocation.
 */
public class Menu {

    private String title;
    private final PanelTheme theme;
    private final List<MenuItem> items = new ArrayList<>();
    private int selected = 0;

    // Layout config (design values at sf=1.0; all overridable via setters).
    private int itemHeight = 46;
    private int gap = 8;
    private int padding = 22;
    private int titleSize = 32;
    private int itemSize = 24;
    private int maxButtonWidth = Integer.MAX_VALUE; // clamp button width; centered when narrower than content

    /** Called on every successful navigation move (e.g. to play a select SFX). May be null. */
    private Runnable onNavigate;

    // Last-drawn button rects (one per item, parallel to items), recorded by drawItems() every
    // frame. This is the single source of truth for mouse hit-testing, MouseHandler queries
    // itemAt(x,y) instead of recomputing geometry that can drift from the draw code.
    private int[] rectX = new int[0], rectY = new int[0], rectW = new int[0], rectH = new int[0];
    private int rectCount = 0;

    // Slider control geometry, shared by drawRow (rendering) and sliderBarRect (mouse mapping) so
    // a click on the bar lands on the value the player sees. Matches drawMedievalSlider's bar size.
    private static final int SLIDER_BAR_W = 150;
    private static final int SLIDER_BAR_H = 18;
    private static final int CTRL_RIGHT_INSET = 18; // right edge of a control column inside a button

    private Menu(String title, PanelTheme theme) {
        this.title = title;
        this.theme = theme;
    }

    public static Menu of(String title, PanelTheme theme) {
        return new Menu(title, theme);
    }

    // ── Fluent build API ─────────────────────────────────────────────────────
    public Menu item(MenuItem it)  { items.add(it); return this; }
    public Menu button(String label, Runnable onSelect) { return item(MenuItem.button(label, onSelect)); }
    public Menu toggle(String label, BooleanSupplier state, Runnable onToggle) { return item(MenuItem.toggle(label, state, onToggle)); }
    public Menu selector(String label, Supplier<String> value, Runnable onLeft, Runnable onRight) { return item(MenuItem.selector(label, value, onLeft, onRight)); }
    public Menu slider(String label, IntSupplier value, int max, Runnable onLeft, Runnable onRight) { return item(MenuItem.slider(label, value, max, onLeft, onRight)); }

    public Menu title(String t)          { this.title = t; return this; }
    public Menu itemHeight(int h)        { this.itemHeight = h; return this; }
    public Menu gap(int g)               { this.gap = g; return this; }
    public Menu padding(int p)           { this.padding = p; return this; }
    public Menu titleSize(int s)         { this.titleSize = s; return this; }
    public Menu itemSize(int s)          { this.itemSize = s; return this; }
    public Menu maxButtonWidth(int w)    { this.maxButtonWidth = w; return this; }
    public Menu onNavigate(Runnable r)   { this.onNavigate = r; return this; }

    public void clearItems() { items.clear(); if (selected >= items.size()) selected = 0; }
    public int size() { return items.size(); }
    public boolean isEmpty() { return items.isEmpty(); }
    public List<MenuItem> items() { return items; }

    public int getSelected() { return selected; }
    public void setSelected(int i) {
        if (items.isEmpty()) { selected = 0; return; }
        selected = ((i % items.size()) + items.size()) % items.size();
    }
    public MenuItem selectedItem() {
        return (selected >= 0 && selected < items.size()) ? items.get(selected) : null;
    }

    // ── Navigation ───────────────────────────────────────────────────────────
    public void moveUp()   { step(-1); }
    public void moveDown() { step(+1); }

    private void step(int dir) {
        if (items.isEmpty()) return;
        int n = items.size();
        int i = selected;
        for (int c = 0; c < n; c++) {
            i = ((i + dir) % n + n) % n;
            if (items.get(i).enabled) { selected = i; if (onNavigate != null) onNavigate.run(); return; }
        }
    }

    /** Fire the selected item's primary action (button / toggle). */
    public void activate() {
        MenuItem it = selectedItem();
        if (it != null && it.enabled) it.activate();
    }

    public void pressLeft()  { MenuItem it = selectedItem(); if (it != null && it.enabled) it.pressLeft(); }
    public void pressRight() { MenuItem it = selectedItem(); if (it != null && it.enabled) it.pressRight(); }

    // ── Drawing ──────────────────────────────────────────────────────────────
    /**
     * Draw the window panel, optional title, and all rows inside (x,y,w,h). Rows auto-fit: if they
     * don't fit the height, itemHeight/gap shrink proportionally so nothing overflows the window.
     */
    public void draw(UI ui, GdxRenderer g2, int x, int y, int w, int h) {
        ui.drawPanel(x, y, w, h, theme);
        drawItems(ui, g2, x, y, w, h);
    }

    /**
     * Render the title + rows into (x,y,w,h) WITHOUT drawing the window panel. Use this when the
     * caller already drew a panel (e.g. the Options screen shares one panel across sub-screens).
     */
    public void drawItems(UI ui, GdxRenderer g2, int x, int y, int w, int h) {
        int contentTop = y + padding;
        if (title != null && !title.isEmpty()) {
            g2.setFont(ui.cachedFontFor(Font.BOLD, titleSize));
            FontMetrics fm = g2.getFontMetrics();
            int tw = fm.stringWidth(title);
            int tx = x + w / 2 - tw / 2;
            int ty = contentTop + fm.getAscent();
            g2.setColor(ui.cachedColorFor(0, 0, 0, 150));
            g2.drawString(title, tx + 2, ty + 2);
            g2.setColor(theme.highlight2());
            g2.drawString(title, tx, ty);
            contentTop = ty + fm.getDescent() + 12;
        }

        int n = items.size();
        if (n == 0) return;

        int available = (y + h - padding) - contentTop;
        int ih = itemHeight;
        int ig = gap;
        int needed = n * ih + (n - 1) * ig;
        if (needed > available && needed > 0) {
            // Shrink to fit: scale row height and gap down together, clamped to a readable minimum.
            float scale = (float) available / needed;
            ih = Math.max(20, (int) (ih * scale));
            ig = Math.max(2, (int) (ig * scale));
        }

        int btnW = Math.min(w - padding * 2, maxButtonWidth);
        int btnX = x + (w - btnW) / 2;

        ensureRectCapacity(n);
        rectCount = n;
        int rowY = contentTop;
        for (int i = 0; i < n; i++) {
            MenuItem it = items.get(i);
            if (it.separatorBefore) {
                g2.setColor(ui.cachedColorFor(120, 100, 60, 90));
                g2.fillRect(btnX + 10, rowY - ig / 2 - 1, btnW - 20, 1);
            }
            drawRow(ui, g2, it, i == selected, btnX, rowY, btnW, ih);
            // Record the exact rect so mouse hit-testing matches what was drawn (see itemAt).
            rectX[i] = btnX; rectY[i] = rowY; rectW[i] = btnW; rectH[i] = ih;
            rowY += ih + ig;
        }
    }

    private void ensureRectCapacity(int n) {
        if (rectX.length < n) {
            rectX = new int[n]; rectY = new int[n]; rectW = new int[n]; rectH = new int[n];
        }
    }

    /**
     * For screens that draw their own custom layout (not {@link #drawItems}) but still want the
     * Menu to own mouse hit-testing: call {@link #beginRects} once, then {@link #recordRect} for
     * each item as you draw it, so {@link #itemAt} matches your visuals. This is what keeps
     * hover/click accurate on EVERY menu, including the bespoke-styled ones.
     */
    public void beginRects() {
        ensureRectCapacity(items.size());
        rectCount = 0;
    }

    /** Record the drawn rect for the next item (call in item order during a custom draw). */
    public void recordRect(int x, int y, int w, int h) {
        if (rectCount >= rectX.length) ensureRectCapacity(rectCount + 1);
        rectX[rectCount] = x; rectY[rectCount] = y; rectW[rectCount] = w; rectH[rectCount] = h;
        rectCount++;
    }

    /**
     * Index of the item whose last-drawn button rect contains (px,py), or -1. Uses the rects
     * recorded by the most recent {@link #drawItems}/{@link #draw}, so hover/click hit-testing is
 * always in exact agreement with what the player sees, no duplicated, drift-prone geometry.
     * Coordinates are in the same space the menu was drawn in (game/UI space).
     */
    public int itemAt(int px, int py) {
        for (int i = 0; i < rectCount; i++) {
            if (px >= rectX[i] && px < rectX[i] + rectW[i]
             && py >= rectY[i] && py < rectY[i] + rectH[i]) {
                return i;
            }
        }
        return -1;
    }

    /**
     * If (px,py) is over item {@code i}'s slider bar (as last drawn), return the value 0..max the
     * click maps to; otherwise -1. Geometry is derived from the recorded row rect and the same
     * SLIDER_* constants drawRow uses, so clicks land on the value shown. Returns -1 if item i is
     * not a slider or hasn't been drawn yet.
     */
    public int sliderValueAt(int i, int px, int py) {
        if (i < 0 || i >= rectCount || i >= items.size()) return -1;
        MenuItem it = items.get(i);
        if (it.kind != MenuItem.Kind.SLIDER) return -1;
        int barX = rectX[i] + rectW[i] - CTRL_RIGHT_INSET - SLIDER_BAR_W;
        int barY = rectY[i] + rectH[i] / 2 - 10; // textCy - 10, textCy = rowY + rowH/2
        if (px < barX || px >= barX + SLIDER_BAR_W) return -1;
        if (py < barY || py >= barY + SLIDER_BAR_H) return -1;
        int max = Math.max(1, it.max);
        int val = Math.round((px - barX) / (float) SLIDER_BAR_W * max);
        return Math.max(0, Math.min(max, val));
    }

    // Selector arrow glyph half-width for hit-testing (drawRow draws "◀"/"▶" at valCx ∓ 40).
    private static final int SELECTOR_ARROW_HALF_W = 16;

    /**
     * If (px,py) is over item {@code i}'s selector arrow (as last drawn), return -1 for the left
     * arrow, +1 for the right arrow, or 0 if not over either. Mirrors the valCx/±40 geometry
     * drawRow uses for SELECTOR so clicks land on the arrow the player sees. Returns 0 if item i is
     * not a selector or hasn't been drawn yet.
     */
    public int selectorArrowAt(int i, int px, int py) {
        if (i < 0 || i >= rectCount || i >= items.size()) return 0;
        MenuItem it = items.get(i);
        if (it.kind != MenuItem.Kind.SELECTOR) return 0;
        int ctrlRight = rectX[i] + rectW[i] - CTRL_RIGHT_INSET;
        int valCx = ctrlRight - 90;
        int rowY = rectY[i], rowH = rectH[i];
        if (py < rowY || py >= rowY + rowH) return 0;
        int leftCx = valCx - 40, rightCx = valCx + 40;
        if (px >= leftCx - SELECTOR_ARROW_HALF_W && px < leftCx + SELECTOR_ARROW_HALF_W) return -1;
        if (px >= rightCx - SELECTOR_ARROW_HALF_W && px < rightCx + SELECTOR_ARROW_HALF_W) return 1;
        return 0;
    }

    private void drawRow(UI ui, GdxRenderer g2, MenuItem it, boolean selected, int bx, int by, int bw, int bh) {
        // Button background (nine-slice Button.png, or vector fallback).
        ui.drawButton(bx, by, bw, bh, theme, selected && it.enabled);

        float pulse = ui.uiPulse();
        int textCy = by + bh / 2;

        // Label
        g2.setFont(ui.cachedFontFor(selected ? Font.BOLD : Font.PLAIN, itemSize));
        FontMetrics fm = g2.getFontMetrics();
        int labelBaseline = textCy + fm.getAscent() / 2 - 2;

        Color labelColor;
        if (!it.enabled)        labelColor = ui.cachedColorFor(110, 105, 95, 150);
        else if (selected)      labelColor = theme.highlight2();
        else                    labelColor = ui.cachedColorFor(210, 200, 180, 235);

        int labelX = it.centered
                ? bx + bw / 2 - fm.stringWidth(it.label) / 2
                : bx + 18;

        if (selected && it.enabled && !it.centered) {
            // Right-pointing caret nudged by the shared UI pulse. Drawn as a real triangle, not a
            // font glyph, Pixeloid Sans (this project's pixel font) has no arrow characters at all.
            int nudge = (int) (pulse * 3);
            g2.setColor(theme.highlight2());
            g2.fillTriangle(GdxRenderer.TriangleDir.RIGHT, bx + 4 + nudge, labelBaseline - 10, 10, 12);
        }
        g2.setColor(ui.cachedColorFor(0, 0, 0, 120));
        g2.drawString(it.label, labelX + 1, labelBaseline + 1);
        g2.setColor(labelColor);
        g2.drawString(it.label, labelX, labelBaseline);

        // Right-hand control column for toggle/selector/slider.
        int ctrlRight = bx + bw - CTRL_RIGHT_INSET;
        switch (it.kind) {
            case TOGGLE -> {
                int size = 22;
                ui.drawMedievalToggle(ctrlRight - size, textCy - size / 2,
                        it.boolGetter != null && it.boolGetter.getAsBoolean());
            }
            case SELECTOR -> {
                String val = it.textGetter != null ? it.textGetter.get() : "";
                g2.setFont(ui.cachedFontFor(Font.PLAIN, itemSize - 2));
                FontMetrics vfm = g2.getFontMetrics();
                int vw = vfm.stringWidth(val);
                int valCx = ctrlRight - 90;
                g2.setColor(selected ? theme.highlight2() : ui.cachedColorFor(200, 190, 170, 220));
                // Real triangles, not font glyphs, see the RIGHT-caret comment above.
                g2.fillTriangle(GdxRenderer.TriangleDir.LEFT, valCx - 46, labelBaseline - 10, 10, 12);
                g2.drawString(val, valCx - vw / 2, labelBaseline);
                g2.fillTriangle(GdxRenderer.TriangleDir.RIGHT, valCx + 36, labelBaseline - 10, 10, 12);
            }
            case SLIDER -> {
                ui.drawMedievalSlider(ctrlRight - SLIDER_BAR_W, textCy - 10,
                        it.intGetter != null ? it.intGetter.getAsInt() : 0, Math.max(1, it.max));
            }
            default -> { /* BUTTON: no control */ }
        }
    }
}
