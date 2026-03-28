package entity;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * Interface for entities with sprite-based animation (walk, idle, attack cycles).
 * Entity.java implements this — fields remain in Entity for now.
 */
public interface IAnimated {
    BufferedImage getWalkFrame(int direction, int frameIndex);
    void draw(Graphics2D g2);
    int getSpriteNum();
    int getDirection();
}
