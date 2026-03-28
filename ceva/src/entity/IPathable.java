package entity;

/**
 * Interface for entities that use A* pathfinding and chase behavior.
 * Entity.java implements this — fields remain in Entity for now.
 */
public interface IPathable {
    void searchPath(int goalCol, int goalRow);
    boolean isOnPath();
    void setOnPath(boolean onPath);
    int getSpeed();
    void setSpeed(int speed);
}
