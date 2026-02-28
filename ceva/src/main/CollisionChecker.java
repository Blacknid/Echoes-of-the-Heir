package main;

import java.awt.Rectangle;
import entity.Entity;

public class CollisionChecker {

    GamePanel gp;
    
    // OPTIMIZATION: Reuse Rectangle objects to avoid allocation overhead
    private Rectangle tempRect = new Rectangle();
    private int collisionRectsSize = 0; // Cache the size

    public CollisionChecker(GamePanel gp) {
        this.gp = gp;
    }

    // OPTIMIZATION: Use cached size to avoid calling size() repeatedly
    public void updateCollisionRectsCache() {
        collisionRectsSize = gp.tileM.collisionRects.size();
    }

    // OPTIMIZATION: Reuse Rectangle to avoid allocation
    public void checkTile(Entity entity) {

        tempRect.x = entity.worldX + entity.solidArea.x;
        tempRect.y = entity.worldY + entity.solidArea.y;
        tempRect.width = entity.solidArea.width;
        tempRect.height = entity.solidArea.height;

        // Predict movement
        switch(entity.direction) {
            case "up" -> tempRect.y -= entity.speed;
            case "down" -> tempRect.y += entity.speed;
            case "left" -> tempRect.x -= entity.speed;
            case "right" -> tempRect.x += entity.speed;
        }

        // Check against collision rectangles (use cached size)
        if (collisionRectsSize == 0) {
            collisionRectsSize = gp.tileM.collisionRects.size();
        }
        
        entity.collisionOn = false;
        for (int i = 0; i < collisionRectsSize; i++) {
            if (tempRect.intersects(gp.tileM.collisionRects.get(i))) {
                entity.collisionOn = true;
                break;
            }
        }
    }

    // OPTIMIZATION: Reuse Rectangle to avoid allocation
    public void checkTileNext(Entity entity, int nextX, int nextY) {
        entity.collisionOn = false;
    
        // Get entity bounds at the next position
        tempRect.x = nextX + entity.solidArea.x;
        tempRect.y = nextY + entity.solidArea.y;
        tempRect.width = entity.solidArea.width;
        tempRect.height = entity.solidArea.height;
        
        // Check collision against collision rectangles (use cached size)
        if (collisionRectsSize == 0) {
            collisionRectsSize = gp.tileM.collisionRects.size();
        }
        
        for (int i = 0; i < collisionRectsSize; i++) {
            if (tempRect.intersects(gp.tileM.collisionRects.get(i))) {
                entity.collisionOn = true;
                break;
            }
        }
    }

    // OPTIMIZATION: Reduce redundant coordinate modifications
    public int checkObject(Entity entity, boolean player) {
        int index = 999;

        for (int i = 0; i < gp.obj.length; i++) {
            if (gp.obj[i] != null) {
                // Setup entity bounds
                int entityX = entity.worldX + entity.solidArea.x;
                int entityY = entity.worldY + entity.solidArea.y;
                
                // Setup object bounds
                int objX = gp.obj[i].worldX + gp.obj[i].solidArea.x;
                int objY = gp.obj[i].worldY + gp.obj[i].solidArea.y;

                // Apply predicted movement
                switch(entity.direction) {
                    case "up" -> entityY -= entity.speed;
                    case "down" -> entityY += entity.speed;
                    case "left" -> entityX -= entity.speed;
                    case "right" -> entityX += entity.speed;
                }

                // Check intersection
                if (entityX < objX + gp.obj[i].solidArea.width &&
                    entityX + entity.solidArea.width > objX &&
                    entityY < objY + gp.obj[i].solidArea.height &&
                    entityY + entity.solidArea.height > objY) {
                    if (gp.obj[i].collision) entity.collisionOn = true;
                    if (player) index = i;
                }
            }
        }

        return index;
    }

    // OPTIMIZATION: Reduce redundant coordinate modifications
    public int checkEntity(Entity entity, Entity[] target) {
        int index = 999;

        for (int i = 0; i < target.length; i++) {
            if (target[i] != null && target[i] != entity) {
                // skip dead/dying monsters so player can walk through
                if (target[i].type == target[i].type_monster &&
                    (target[i].dying || !target[i].alive)) {
                    continue;
                }

                // Setup entity bounds
                int entityX = entity.worldX + entity.solidArea.x;
                int entityY = entity.worldY + entity.solidArea.y;
                
                // Setup target bounds
                int targetX = target[i].worldX + target[i].solidArea.x;
                int targetY = target[i].worldY + target[i].solidArea.y;

                // Apply predicted movement
                switch(entity.direction) {
                    case "up" -> entityY -= entity.speed;
                    case "down" -> entityY += entity.speed;
                    case "left" -> entityX -= entity.speed;
                    case "right" -> entityX += entity.speed;
                }

                // Check intersection
                if (entityX < targetX + target[i].solidArea.width &&
                    entityX + entity.solidArea.width > targetX &&
                    entityY < targetY + target[i].solidArea.height &&
                    entityY + entity.solidArea.height > targetY) {
                    entity.collisionOn = true;
                    index = i;
                }
            }
        }

        return index;
    }

    // OPTIMIZATION: Reduce redundant coordinate modifications
    public boolean checkPlayer(Entity entity) {
        boolean contactPlayer = false;

        // Setup entity bounds
        int entityX = entity.worldX + entity.solidArea.x;
        int entityY = entity.worldY + entity.solidArea.y;
        
        // Setup player bounds
        int playerX = gp.player.worldX + gp.player.solidArea.x;
        int playerY = gp.player.worldY + gp.player.solidArea.y;

        // Apply predicted movement
        switch(entity.direction) {
            case "up" -> entityY -= entity.speed;
            case "down" -> entityY += entity.speed;
            case "left" -> entityX -= entity.speed;
            case "right" -> entityX += entity.speed;
        }

        // Check intersection
        if (entityX < playerX + gp.player.solidArea.width &&
            entityX + entity.solidArea.width > playerX &&
            entityY < playerY + gp.player.solidArea.height &&
            entityY + entity.solidArea.height > playerY) {
            entity.collisionOn = true;
            contactPlayer = true;
        }

        return contactPlayer;
    }
}
