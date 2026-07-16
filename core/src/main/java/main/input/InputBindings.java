package main.input;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for every game action and the physical inputs (keyboard key,
 * mouse button, controller button/axis) that trigger it. Java-side defaults below are
 * overlaid by res/data/keybindings.json, key by key, an action missing from the JSON
 * (or a missing/corrupt file) silently keeps its Java default.
 *
 * Token format: "key:W", "key:SHIFT_LEFT" (Input.Keys name), "mouse:left_click" /
 * "mouse:right_click", "controller:buttonA" / "controller:axisLeftY-" (symbolic
 * ControllerMapping field name, "-"/"+" suffix on axis tokens for deadzone-crossing
 * direction). Tokens are kept as plain strings here; resolving them to libGDX ints or
 * ControllerMapping fields happens in the physical-input adapters (KeyHandler,
 * MouseHandler, GamepadInputAdapter), not here.
 */
public final class InputBindings {

    // ── Gameplay actions ──────────────────────────────────────────────────────
    public static final String MOVE_UP    = "move_up";
    public static final String MOVE_DOWN  = "move_down";
    public static final String MOVE_LEFT  = "move_left";
    public static final String MOVE_RIGHT = "move_right";

    public static final String DASH     = "dash";
    // ATTACK = melee sword swing (facing-direction on controller/keyboard-ENTER, mouse-aim on
    // left-click). SHOOT = ranged arrow (always facing-direction; no aim). INTERACT = talk to
    // NPCs / advance dialogue / confirm inventory selection, historically shares physical
    // triggers with ATTACK (ENTER also interacts) but is tracked separately so a future rebind
    // can split them without code changes.
    public static final String ATTACK   = "attack";
    public static final String SHOOT    = "shoot";
    public static final String INTERACT = "interact";

    public static final String SKILL_SHOCKWAVE  = "skill_shockwave";
    public static final String SKILL_VOID_SNARE = "skill_void_snare";
    public static final String SKILL_FROST_NOVA = "skill_frost_nova";
    public static final String SKILL_OVERDRIVE  = "skill_overdrive";

    public static final String TELEPORT         = "teleport";
    public static final String PAUSE            = "pause";
    public static final String OPTIONS          = "options";
    public static final String CHARACTER_SCREEN = "character_screen";
    public static final String SKILL_TREE       = "skill_tree";
    public static final String JOURNAL          = "journal";
    public static final String QUEST_LOG        = "quest_log";
    public static final String MINIMAP          = "minimap";

    // ── Menu navigation actions ───────────────────────────────────────────────
    public static final String MENU_UP      = "menu_up";
    public static final String MENU_DOWN    = "menu_down";
    public static final String MENU_LEFT    = "menu_left";
    public static final String MENU_RIGHT   = "menu_right";
    public static final String MENU_CONFIRM = "menu_confirm";
    public static final String MENU_CANCEL  = "menu_cancel";

    private static final Map<String, List<String>> bindings = new HashMap<>();
    private static boolean loaded = false;

    private InputBindings() {}

    /** Clears the cached bindings so the next lookup re-reads Java defaults + JSON. */
    public static void invalidateCache() {
        bindings.clear();
        loaded = false;
    }

    /** Returns the physical trigger tokens bound to the given action, loading on first use. */
    public static List<String> tokensFor(String action) {
        if (!loaded) load();
        return bindings.getOrDefault(action, List.of());
    }

    /** Returns every action name that has at least one binding (defaults ∪ JSON overrides). */
    public static java.util.Set<String> allActions() {
        if (!loaded) load();
        return bindings.keySet();
    }

    private static void load() {
        loaded = true;
        loadDefaults();
        overlayFromJson();
    }

    private static void loadDefaults() {
        bindings.put(MOVE_UP,    List.of("key:W", "key:UP", "controller:buttonDpadUp", "controller:axisLeftY-"));
        bindings.put(MOVE_DOWN,  List.of("key:S", "key:DOWN", "controller:buttonDpadDown", "controller:axisLeftY+"));
        bindings.put(MOVE_LEFT,  List.of("key:A", "key:LEFT", "controller:buttonDpadLeft", "controller:axisLeftX-"));
        bindings.put(MOVE_RIGHT, List.of("key:D", "key:RIGHT", "controller:buttonDpadRight", "controller:axisLeftX+"));

        bindings.put(DASH,     List.of("key:SHIFT_LEFT", "key:SHIFT_RIGHT", "controller:buttonR1"));
        // mouse:left_click is intentionally NOT bound to ATTACK, Player.fireMouseAttackIfRequested()
        // already reads mouseH.leftClicked directly and attacks toward the cursor (mouse-aim), a
        // completely separate mechanism from ATTACK's facing-direction swing. Double-binding the
        // click here made one physical click arm BOTH paths: the facing-direction swing fired
        // immediately, but the mouse-aim swing's own !attacking guard blocked it that same frame
        // and left mouseH.leftClicked sitting true, unconsumed, so it fired a SECOND, delayed
        // attack toward wherever the cursor had drifted to by the time the first swing's animation
        // finished. See ATTACK's doc comment above for why ENTER still legitimately drives both.
        bindings.put(ATTACK,   List.of("key:ENTER", "controller:buttonX"));
        bindings.put(SHOOT,    List.of("key:F", "controller:buttonR2"));
        bindings.put(INTERACT, List.of("key:ENTER", "controller:buttonA"));

        bindings.put(SKILL_SHOCKWAVE,  List.of("key:Z", "controller:buttonL1"));
        bindings.put(SKILL_VOID_SNARE, List.of("key:X", "controller:buttonY"));
        bindings.put(SKILL_FROST_NOVA, List.of("key:C", "controller:buttonL2"));
        bindings.put(SKILL_OVERDRIVE,  List.of("key:V", "controller:buttonRightStick"));

        bindings.put(TELEPORT,         List.of("key:SPACE"));
        bindings.put(PAUSE,            List.of("key:P", "controller:buttonStart"));
        bindings.put(OPTIONS,          List.of("key:ESCAPE", "controller:buttonB"));
        bindings.put(CHARACTER_SCREEN, List.of("key:E", "controller:buttonLeftStick"));
        bindings.put(SKILL_TREE,       List.of("key:K", "controller:buttonBack"));
        bindings.put(JOURNAL,          List.of("key:J"));
        bindings.put(QUEST_LOG,        List.of("key:Q"));
        bindings.put(MINIMAP,          List.of("key:M"));

        bindings.put(MENU_UP,      List.of("key:W", "key:UP", "controller:buttonDpadUp", "controller:axisLeftY-"));
        bindings.put(MENU_DOWN,    List.of("key:S", "key:DOWN", "controller:buttonDpadDown", "controller:axisLeftY+"));
        bindings.put(MENU_LEFT,    List.of("key:A", "key:LEFT", "controller:buttonDpadLeft", "controller:axisLeftX-"));
        bindings.put(MENU_RIGHT,   List.of("key:D", "key:RIGHT", "controller:buttonDpadRight", "controller:axisLeftX+"));
        bindings.put(MENU_CONFIRM, List.of("key:ENTER", "controller:buttonA"));
        bindings.put(MENU_CANCEL,  List.of("key:ESCAPE", "controller:buttonB"));
    }

    /** Overlays res/data/keybindings.json onto the defaults, action by action. Missing/corrupt
 * file is not an error, it just means every action keeps its Java default. */
    private static void overlayFromJson() {
        try (InputStream is = util.ResourceCache.openClasspathStream("/res/data/keybindings.json")) {
            if (is == null) return;
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            parseOverrides(sb.toString());
        } catch (Exception e) {
            System.out.println("[InputBindings] Error loading keybindings.json, using defaults: " + e.getMessage());
        }
    }

    private static void parseOverrides(String json) {
        json = json.trim();
        if (!json.startsWith("{")) return;
        json = json.substring(1, json.length() - 1).trim();

        int i = 0;
        while (i < json.length()) {
            int keyStart = json.indexOf('"', i);
            if (keyStart < 0) break;
            int keyEnd = json.indexOf('"', keyStart + 1);
            if (keyEnd < 0) break;
            String action = json.substring(keyStart + 1, keyEnd);

            int bracketStart = json.indexOf('[', keyEnd);
            if (bracketStart < 0) break;
            int bracketEnd = json.indexOf(']', bracketStart);
            if (bracketEnd < 0) break;

            List<String> tokens = new ArrayList<>();
            String inner = json.substring(bracketStart + 1, bracketEnd);
            int j = 0;
            while (j < inner.length()) {
                int qStart = inner.indexOf('"', j);
                if (qStart < 0) break;
                int qEnd = inner.indexOf('"', qStart + 1);
                if (qEnd < 0) break;
                tokens.add(inner.substring(qStart + 1, qEnd));
                j = qEnd + 1;
            }
            bindings.put(action, tokens);

            i = bracketEnd + 1;
        }
    }
}
