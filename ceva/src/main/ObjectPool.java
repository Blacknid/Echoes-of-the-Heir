package main;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Generic object pool for reducing garbage collection pressure by reusing objects.
 * Objects are recycled instead of destroyed, eliminating expensive allocations.
 */
@SuppressWarnings("unchecked")
public class ObjectPool<T> {
    private List<T> available = new ArrayList<>();
    // OPTIMIZATION: Use HashSet for O(1) contains/remove instead of O(n) linear scan
    private Set<T> inUse = new HashSet<>();
    private PoolableObject factory;
    private int expandSize;

    /**
     * Creates an object pool for a specific object type.
     * @param factory Callback that creates and resets new objects
     * @param initialSize Initial pool size
     * @param expandSize How many objects to create when pool runs empty
     */
    public ObjectPool(PoolableObject factory, int initialSize, int expandSize) {
        this.factory = factory;
        this.expandSize = expandSize;
        
        // Pre-allocate initial pool
        for (int i = 0; i < initialSize; i++) {
            available.add((T) factory.create());
        }
    }

    /**
     * Gets an object from the pool, expanding if necessary.
     */
    public T get() {
        if (available.isEmpty()) {
            // Expand pool when empty
            for (int i = 0; i < expandSize; i++) {
                available.add((T) factory.create());
            }
        }
        
        T obj = available.remove(available.size() - 1);
        inUse.add(obj);
        return obj;
    }

    /**
     * Returns an object to the pool for reuse.
     */
    public void release(T obj) {
        if (inUse.remove(obj)) {
            // Reset object to initial state
            if (obj instanceof Poolable) {
                ((Poolable) obj).reset();
            }
            available.add(obj);
        }
    }

    /**
     * Release multiple objects at once
     */
    public void releaseAll(List<T> objects) {
        for (T obj : objects) {
            release(obj);
        }
    }

    /**
     * Get current pool statistics
     */
    public int getAvailableCount() {
        return available.size();
    }

    public int getInUseCount() {
        return inUse.size();
    }

    /**
     * Interface for objects that can be pooled
     */
    public interface Poolable {
        void reset();
    }

    /**
     * Interface for creating pooled objects
     */
    public interface PoolableObject {
        Object create();
    }
}
