package data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Central storage for all collected Memory Fragments.
 * Maintains a master registry (loaded from fragments.json or registered at setup)
 * and tracks which fragments have been collected.
 * Fragments are displayed in story-chronological order regardless of collection order.
 */
public class MemoryJournal {

    // ── Inner class ──
    public static class MemoryFragment {
        public final String id;
        public final String name;
        public final String[] text;      // 1–5 lines of flashback narrative
        public final int storyOrder;     // position in the story timeline (lower = earlier)
        public final String source;      // "npc", "boss", "exploration"
        public boolean collected;

        public MemoryFragment(String id, String name, String[] text, int storyOrder, String source) {
            this.id = id;
            this.name = name;
            this.text = text;
            this.storyOrder = storyOrder;
            this.source = source;
            this.collected = false;
        }
    }

    // Master registry: ordered by storyOrder
    private final Map<String, MemoryFragment> registry = new LinkedHashMap<>();
    // Sorted view cache (rebuilt on registration changes)
    private final List<MemoryFragment> sortedFragments = new ArrayList<>();
    private boolean sortDirty = true;

    /** Register a fragment definition (call at setup or from Tiled loading). */
    public void registerFragment(String id, String name, String[] text, int storyOrder, String source) {
        if (id == null || registry.containsKey(id)) return;
        registry.put(id, new MemoryFragment(id, name, text, storyOrder, source));
        sortDirty = true;
    }

    /** Collect a fragment by ID. Returns the fragment if newly collected, null if already owned or unknown. */
    public MemoryFragment collect(String id) {
        MemoryFragment f = registry.get(id);
        if (f == null || f.collected) return null;
        f.collected = true;
        return f;
    }

    /** Collect a fragment by ID only (for save-load). Registers a placeholder if unknown. */
    public void addById(String id) {
        MemoryFragment f = registry.get(id);
        if (f != null) {
            f.collected = true;
        } else {
            // Fragment not yet registered (e.g. loaded before map setup).
            // Create a placeholder that will be filled in when the map loads.
            MemoryFragment placeholder = new MemoryFragment(id, id, new String[]{"..."}, 999, "unknown");
            placeholder.collected = true;
            registry.put(id, placeholder);
            sortDirty = true;
        }
    }

    /** Check if a fragment has been collected. */
    public boolean has(String id) {
        MemoryFragment f = registry.get(id);
        return f != null && f.collected;
    }

    /** Number of collected fragments. */
    public int getCount() {
        int count = 0;
        for (MemoryFragment f : registry.values()) {
            if (f.collected) count++;
        }
        return count;
    }

    /** Total registered fragments (the denominator for "12 / 20"). */
    public int getTotal() {
        return registry.size();
    }

    /** Get all fragments sorted by storyOrder (for journal display). */
    public List<MemoryFragment> getAllSorted() {
        if (sortDirty) {
            sortedFragments.clear();
            sortedFragments.addAll(registry.values());
            Collections.sort(sortedFragments, (a, b) -> Integer.compare(a.storyOrder, b.storyOrder));
            sortDirty = false;
        }
        return sortedFragments;
    }

    /** Get only collected fragments in story order. */
    public List<MemoryFragment> getCollectedSorted() {
        List<MemoryFragment> result = new ArrayList<>();
        for (MemoryFragment f : getAllSorted()) {
            if (f.collected) result.add(f);
        }
        return result;
    }

    /** Get collected fragment IDs (for serialization). */
    public List<String> getCollectedIds() {
        List<String> ids = new ArrayList<>();
        for (MemoryFragment f : registry.values()) {
            if (f.collected) ids.add(f.id);
        }
        return ids;
    }

    /** Get a fragment by ID (may be null). */
    public MemoryFragment getFragment(String id) {
        return registry.get(id);
    }

    /** Reset all collection state (for new game). */
    public void reset() {
        for (MemoryFragment f : registry.values()) {
            f.collected = false;
        }
    }
}
