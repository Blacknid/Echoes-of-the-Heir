package ui;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * One row inside a {@link Menu}. A menu is declared as a flat list of these, and each item
 * knows how to be activated / adjusted — the {@link Menu} handles layout, drawing and
 * navigation. This replaces the old pattern of a {@code String[]} of labels in UI.java paired
 * with a hand-written {@code if (commandNum == i)} switch in KeyHandler.
 *
 * <p>Item kinds:
 * <ul>
 *   <li>{@link Kind#BUTTON}   — a plain button; Enter runs {@code onSelect}.</li>
 *   <li>{@link Kind#TOGGLE}   — on/off row (Full Screen, V-Sync…); Enter runs {@code onSelect},
 *       {@code boolGetter} supplies the current state for the checkbox.</li>
 *   <li>{@link Kind#SELECTOR} — ◀ value ▶ row (Graphics Low/Med/High); Left/Right run
 *       {@code onLeft}/{@code onRight}, {@code textGetter} supplies the shown value.</li>
 *   <li>{@link Kind#SLIDER}   — ◀ [bar] ▶ row (Music, Sound FX); Left/Right run
 *       {@code onLeft}/{@code onRight}, {@code intGetter}/{@code max} drive the bar.</li>
 * </ul>
 */
public class MenuItem {

    public enum Kind { BUTTON, TOGGLE, SELECTOR, SLIDER }

    public final Kind kind;
    public final String label;

    // Actions (any may be null depending on kind).
    public final Runnable onSelect; // BUTTON activate, TOGGLE flip
    public final Runnable onLeft;   // SELECTOR/SLIDER decrement
    public final Runnable onRight;  // SELECTOR/SLIDER increment

    // Value suppliers (any may be null depending on kind).
    public final BooleanSupplier boolGetter; // TOGGLE current state
    public final Supplier<String> textGetter; // SELECTOR current value text
    public final IntSupplier intGetter;       // SLIDER current value
    public final int max;                     // SLIDER max value

    /** When true a thin separator line is drawn above this row (used before "Back"). */
    public boolean separatorBefore = false;
    /** When true the label is centered rather than left-aligned (used for "Back"). */
    public boolean centered = false;
    /** Disabled rows are skipped by navigation and drawn dimmed. */
    public boolean enabled = true;

    private MenuItem(Kind kind, String label, Runnable onSelect, Runnable onLeft, Runnable onRight,
                     BooleanSupplier boolGetter, Supplier<String> textGetter, IntSupplier intGetter, int max) {
        this.kind = kind;
        this.label = label;
        this.onSelect = onSelect;
        this.onLeft = onLeft;
        this.onRight = onRight;
        this.boolGetter = boolGetter;
        this.textGetter = textGetter;
        this.intGetter = intGetter;
        this.max = max;
    }

    public static MenuItem button(String label, Runnable onSelect) {
        return new MenuItem(Kind.BUTTON, label, onSelect, null, null, null, null, null, 0);
    }

    public static MenuItem toggle(String label, BooleanSupplier state, Runnable onToggle) {
        return new MenuItem(Kind.TOGGLE, label, onToggle, null, null, state, null, null, 0);
    }

    public static MenuItem selector(String label, Supplier<String> value, Runnable onLeft, Runnable onRight) {
        return new MenuItem(Kind.SELECTOR, label, null, onLeft, onRight, null, value, null, 0);
    }

    public static MenuItem slider(String label, IntSupplier value, int max, Runnable onLeft, Runnable onRight) {
        return new MenuItem(Kind.SLIDER, label, null, onLeft, onRight, null, null, value, max);
    }

    // ── Fluent modifiers (return this) ───────────────────────────────────────
    public MenuItem separator()      { this.separatorBefore = true; return this; }
    public MenuItem centered()       { this.centered = true; return this; }
    public MenuItem enabled(boolean e){ this.enabled = e; return this; }

    /** Fire the primary action: activate a button / flip a toggle. No-op for selector/slider. */
    public void activate() {
        if (onSelect != null) onSelect.run();
    }

    public void pressLeft()  { if (onLeft  != null) onLeft.run(); }
    public void pressRight() { if (onRight != null) onRight.run(); }
}
