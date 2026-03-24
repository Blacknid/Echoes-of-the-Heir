package main;

import entity.Entity;
import java.awt.Rectangle;
import java.util.ArrayList;

public class CollisionChecker {

    GamePanel gp;
    
    // OPTIMIZATION: Reuse Rectangle objects to avoid allocation overhead
    private Rectangle tempRect = new Rectangle();
    private int collisionRectsSize = 0; // Cache the size

    // OPTIMIZATION: Spatial grid for collision rectangles
    // Divides the world into cells; only check rects in nearby cells
    private static final int GRID_CELL_SIZE = 128; // pixels per grid cell (2 tiles)
    private ArrayList<Integer>[] spatialGrid;
    private int gridCols, gridRows;

    public CollisionChecker(GamePanel gp) {
        this.gp = gp;
    }

    // OPTIMIZATION: Build spatial grid index for collision rectangles
    @SuppressWarnings("unchecked")
    public void updateCollisionRectsCache() {
        collisionRectsSize = gp.tileM.collisionRects.size();
        
        // Build spatial grid
        gridCols = (gp.worldWidth / GRID_CELL_SIZE) + 2;
        gridRows = (gp.worldHeight / GRID_CELL_SIZE) + 2;
        int totalCells = gridCols * gridRows;
        spatialGrid = new ArrayList[totalCells];
        
        for (int i = 0; i < collisionRectsSize; i++) {
            Rectangle r = gp.tileM.collisionRects.get(i);
            int minCellX = Math.max(0, r.x / GRID_CELL_SIZE);
            int minCellY = Math.max(0, r.y / GRID_CELL_SIZE);
            int maxCellX = Math.min(gridCols - 1, (r.x + r.width) / GRID_CELL_SIZE);
            int maxCellY = Math.min(gridRows - 1, (r.y + r.height) / GRID_CELL_SIZE);
            
            for (int cy = minCellY; cy <= maxCellY; cy++) {
                for (int cx = minCellX; cx <= maxCellX; cx++) {
                    int idx = cy * gridCols + cx;
                    if (idx >= 0 && idx < totalCells) {
                        if (spatialGrid[idx] == null) {
                            spatialGrid[idx] = new ArrayList<>(4);
                        }
                        spatialGrid[idx].add(i);
                    }
                }
            }
        }
    }

    // OPTIMIZATION: Use spatial grid for tile collision checks
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

        entity.collisionOn = false;
        
        // Use spatial grid if available
        if (spatialGrid != null && gridCols > 0) {
            int minCX = Math.max(0, tempRect.x / GRID_CELL_SIZE);
            int minCY = Math.max(0, tempRect.y / GRID_CELL_SIZE);
            int maxCX = Math.min(gridCols - 1, (tempRect.x + tempRect.width) / GRID_CELL_SIZE);
            int maxCY = Math.min(gridRows - 1, (tempRect.y + tempRect.height) / GRID_CELL_SIZE);
            
            for (int cy = minCY; cy <= maxCY && !entity.collisionOn; cy++) {
                for (int cx = minCX; cx <= maxCX && !entity.collisionOn; cx++) {
                    int idx = cy * gridCols + cx;
                    if (idx >= 0 && idx < spatialGrid.length && spatialGrid[idx] != null) {
                        ArrayList<Integer> cell = spatialGrid[idx];
                        for (int j = 0; j < cell.size(); j++) {
                            if (tempRect.intersects(gp.tileM.collisionRects.get(cell.get(j)))) {
                                entity.collisionOn = true;
                                break;
                            }
                        }
                    }
                }
            }
        } else {
            // Fallback to linear scan
            for (int i = 0; i < collisionRectsSize; i++) {
                if (tempRect.intersects(gp.tileM.collisionRects.get(i))) {
                    entity.collisionOn = true;
                    break;
                }
            }
        }
    }

    // OPTIMIZATION: Use spatial grid for next-position collision checks
    public void checkTileNext(Entity entity, int nextX, int nextY) {
        entity.collisionOn = false;
    
        tempRect.x = nextX + entity.solidArea.x;
        tempRect.y = nextY + entity.solidArea.y;
        tempRect.width = entity.solidArea.width;
        tempRect.height = entity.solidArea.height;
        
        if (spatialGrid != null && gridCols > 0) {
            int minCX = Math.max(0, tempRect.x / GRID_CELL_SIZE);
            int minCY = Math.max(0, tempRect.y / GRID_CELL_SIZE);
            int maxCX = Math.min(gridCols - 1, (tempRect.x + tempRect.width) / GRID_CELL_SIZE);
            int maxCY = Math.min(gridRows - 1, (tempRect.y + tempRect.height) / GRID_CELL_SIZE);
            
            for (int cy = minCY; cy <= maxCY && !entity.collisionOn; cy++) {
                for (int cx = minCX; cx <= maxCX && !entity.collisionOn; cx++) {
                    int idx = cy * gridCols + cx;
                    if (idx >= 0 && idx < spatialGrid.length && spatialGrid[idx] != null) {
                        ArrayList<Integer> cell = spatialGrid[idx];
                        for (int j = 0; j < cell.size(); j++) {
                            if (tempRect.intersects(gp.tileM.collisionRects.get(cell.get(j)))) {
                                entity.collisionOn = true;
                                break;
                            }
                        }
                    }
                }
            }
        } else {
            for (int i = 0; i < collisionRectsSize; i++) {
                if (tempRect.intersects(gp.tileM.collisionRects.get(i))) {
                    entity.collisionOn = true;
                    break;
                }
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
                    if (target[i].collision) {
                        entity.collisionOn = true;
                    }
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
