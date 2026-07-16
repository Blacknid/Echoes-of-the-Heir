package main.input;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Live per-frame state for the named actions declared in {@link InputBindings}. Physical-input
 * adapters (KeyHandler, MouseHandler, GamepadInputAdapter) report raw edges via
 * {@link #setPhysical}; gameplay/menu code reads {@link #isDown} (continuous) or
 * {@link #consumePressed} (one-shot, auto-clearing).
 *
 * An action can be bound to several physical tokens at once (e.g. move_up = W or UP), so held
 * state is a ref-count, not a boolean, releasing one of two held keys must not clear the
 * action while the other is still down.
 */
public final class InputActions {

    private final Map<String, Integer> heldCount = new HashMap<>();
    private final Map<String, Boolean> pendingPress = new HashMap<>();
    private final Map<String, List<String>> tokenToActions = new HashMap<>();
    private boolean indexBuilt = false;

    /** Called by physical-input adapters on every raw key/button/axis edge. */
    public void setPhysical(String token, boolean down) {
        ensureIndex();
        List<String> actions = tokenToActions.get(token);
        if (actions == null) return;
        for (String action : actions) {
            int count = heldCount.getOrDefault(action, 0);
            if (down) {
                if (count == 0) pendingPress.put(action, true);
                heldCount.put(action, count + 1);
            } else {
                heldCount.put(action, Math.max(0, count - 1));
            }
        }
    }

    /** True every frame the action is held down (movement, continuous checks). */
    public boolean isDown(String action) {
        return heldCount.getOrDefault(action, 0) > 0;
    }

    /** True at most once per physical press, regardless of how long it's held. */
    public boolean consumePressed(String action) {
        Boolean pending = pendingPress.get(action);
        if (pending == null || !pending) return false;
        pendingPress.put(action, false);
        return true;
    }

    /** Clears every one-shot pending-press flag (held-state is left alone, a key physically
 * still down should stay "down"). Call this on every game-state transition: a key press
 * that triggered the transition (e.g. E closing the inventory) may have been read via a
 * raw key check rather than consumePressed(), leaving its action's pending-press flag
 * stale, left unconsumed, the next isDown/consumePressed poll in the new state would
 * fire on it as if it were a fresh press, e.g. instantly reopening the screen just closed. */
    public void clearAllPending() {
        pendingPress.clear();
    }

    private void ensureIndex() {
        if (indexBuilt) return;
        indexBuilt = true;
        for (String action : InputBindings.allActions()) {
            for (String token : InputBindings.tokensFor(action)) {
                tokenToActions.computeIfAbsent(token, t -> new ArrayList<>()).add(action);
            }
        }
    }
}
