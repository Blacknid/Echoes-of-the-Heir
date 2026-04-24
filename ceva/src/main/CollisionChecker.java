package main;

import java.awt.Rectangle;
import java.awt.Shape;
import java.util.ArrayList;

import entity.Entity;

public class CollisionChecker {

    GamePanel gp;
    
    // OPTIMIZATION: Reuse Rectangle objects to avoid allocation overhead
    private Rectangle tempRect = new Rectangle();

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
        int shapeCount = gp.tileM.collisionShapes.size();
        
        // Build spatial grid using bounding boxes for broad-phase
        gridCols = (gp.worldWidth / GRID_CELL_SIZE) + 2;
        gridRows = (gp.worldHeight / GRID_CELL_SIZE) + 2;
        int totalCells = gridCols * gridRows;
        spatialGrid = new ArrayList[totalCells];
        
        for (int i = 0; i < shapeCount; i++) {
            Rectangle r = gp.tileM.collisionBounds.get(i);
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

    /**
     * Checks whether a Rectangle hits any collision shape using the spatial grid.
     * O(k) where k = shapes in nearby cells, vs the previous O(n) full scan.
     * Used by PathFinder to avoid iterating all collision bounds per A* node.
     */
    public boolean rectHitsCollision(Rectangle r) {
        if (spatialGrid != null && gridCols > 0) {
            int minCX = Math.max(0, r.x / GRID_CELL_SIZE);
            int minCY = Math.max(0, r.y / GRID_CELL_SIZE);
            int maxCX = Math.min(gridCols - 1, (r.x + r.width)  / GRID_CELL_SIZE);
            int maxCY = Math.min(gridRows - 1, (r.y + r.height) / GRID_CELL_SIZE);
            for (int cy = minCY; cy <= maxCY; cy++) {
                for (int cx = minCX; cx <= maxCX; cx++) {
                    int idx = cy * gridCols + cx;
                    if (idx >= 0 && idx < spatialGrid.length && spatialGrid[idx] != null) {
                        ArrayList<Integer> cell = spatialGrid[idx];
                        for (int j = 0, n = cell.size(); j < n; j++) {
                            if (gp.tileM.collisionShapes.get(cell.get(j)).intersects(r)) return true;
                        }
                    }
                }
            }
            return false;
        }
        // Fallback: linear scan of bounds rectangles
        ArrayList<Rectangle> rects = gp.tileM.collisionBounds;
        for (int i = 0, size = rects.size(); i < size; i++) {
            if (r.intersects(rects.get(i))) return true;
        }
        return false;
    }

    /**
     * Return a list of collision bounding rectangles that intersect the given rectangle.
     * Uses the spatial grid for fast broad-phase lookup when available.
     */
    public java.util.ArrayList<Rectangle> getCollisionBoundsInRect(Rectangle r) {
        java.util.ArrayList<Rectangle> out = new java.util.ArrayList<>();
        getCollisionBoundsInRect(r, out);
        return out;
    }

    /**
     * Fill an existing list with collision bounding rectangles that intersect r.
     * Caller must clear the list before calling. Avoids any ArrayList allocation.
     */
    public void getCollisionBoundsInRect(Rectangle r, java.util.ArrayList<Rectangle> out) {
        if (spatialGrid != null && gridCols > 0) {
            int minCX = Math.max(0, r.x / GRID_CELL_SIZE);
            int minCY = Math.max(0, r.y / GRID_CELL_SIZE);
            int maxCX = Math.min(gridCols - 1, (r.x + r.width) / GRID_CELL_SIZE);
            int maxCY = Math.min(gridRows - 1, (r.y + r.height) / GRID_CELL_SIZE);
            for (int cy = minCY; cy <= maxCY; cy++) {
                for (int cx = minCX; cx <= maxCX; cx++) {
                    int idx = cy * gridCols + cx;
                    if (idx >= 0 && idx < spatialGrid.length && spatialGrid[idx] != null) {
                        java.util.ArrayList<Integer> cell = spatialGrid[idx];
                        for (int j = 0, n = cell.size(); j < n; j++) {
                            int shapeIdx = cell.get(j);
                            Rectangle br = gp.tileM.collisionBounds.get(shapeIdx);
                            if (br.intersects(r) && !out.contains(br)) out.add(br);
                        }
                    }
                }
            }
        } else {
            java.util.ArrayList<Rectangle> rects = gp.tileM.collisionBounds;
            for (int i = 0, n = rects.size(); i < n; i++) {
                Rectangle br = rects.get(i);
                if (br.intersects(r) && !out.contains(br)) out.add(br);
            }
        }
    }

    // OPTIMIZATION: Use spatial grid for tile collision checks
    // Uses swept collision: extends the rect along the movement direction so that
    // walls between the current position and the destination are never skipped,
    // even at very high speeds (e.g. dash).
    public void checkTile(Entity entity) {

        tempRect.x = entity.worldX + entity.solidArea.x;
        tempRect.y = entity.worldY + entity.solidArea.y;
        tempRect.width = entity.solidArea.width;
        tempRect.height = entity.solidArea.height;

        // Swept collision: extend rect from current to predicted position
        switch(entity.direction) {
            case Entity.DIR_UP:    tempRect.y -= entity.speed; tempRect.height += entity.speed; break;
            case Entity.DIR_DOWN:  tempRect.height += entity.speed; break;
            case Entity.DIR_LEFT:  tempRect.x -= entity.speed; tempRect.width += entity.speed; break;
            case Entity.DIR_RIGHT: tempRect.width += entity.speed; break;
        }

        entity.collisionOn = false;

        // --- Tile-layer collision (only if collisionTileLayers is configured) ---
        if (!gp.tileM.collisionTileLayers.isEmpty()) {
            int ts = gp.tileSize;
            int leftCol   = tempRect.x / ts;
            int rightCol  = (tempRect.x + tempRect.width) / ts;
            int topRow    = tempRect.y / ts;
            int bottomRow = (tempRect.y + tempRect.height) / ts;
            for (int row = topRow; row <= bottomRow && !entity.collisionOn; row++) {
                for (int col = leftCol; col <= rightCol && !entity.collisionOn; col++) {
                    if (gp.tileM.isTileBlocking(col, row)) {
                        entity.collisionOn = true;
                    }
                }
            }
        }

        // --- Shape collision (from Tiled objectgroup layers — rects, rotated rects, polygons, ellipses) ---
        if (!entity.collisionOn) {
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
                                Shape shape = gp.tileM.collisionShapes.get(cell.get(j));
                                if (shape.intersects(tempRect)) {
                                    entity.collisionOn = true;
                                    break;
                                }
                            }
                        }
                    }
                }
            } else {
                ArrayList<Shape> shapes = gp.tileM.collisionShapes;
                for (int i = 0; i < shapes.size(); i++) {
                    if (shapes.get(i).intersects(tempRect)) {
                        entity.collisionOn = true;
                        break;
                    }
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

        // Tile-layer collision (only if collisionTileLayers is configured)
        if (!gp.tileM.collisionTileLayers.isEmpty()) {
            int ts = gp.tileSize;
            int leftCol   = tempRect.x / ts;
            int rightCol  = (tempRect.x + tempRect.width) / ts;
            int topRow    = tempRect.y / ts;
            int bottomRow = (tempRect.y + tempRect.height) / ts;
            for (int row = topRow; row <= bottomRow && !entity.collisionOn; row++) {
                for (int col = leftCol; col <= rightCol && !entity.collisionOn; col++) {
                    if (gp.tileM.isTileBlocking(col, row)) {
                        entity.collisionOn = true;
                    }
                }
            }
        }

        // Shape collision
        if (!entity.collisionOn) {
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
                                Shape shape = gp.tileM.collisionShapes.get(cell.get(j));
                                if (shape.intersects(tempRect)) {
                                    entity.collisionOn = true;
                                    break;
                                }
                            }
                        }
                    }
                }
            } else {
                ArrayList<Shape> shapes = gp.tileM.collisionShapes;
                for (int i = 0; i < shapes.size(); i++) {
                    if (shapes.get(i).intersects(tempRect)) {
                        entity.collisionOn = true;
                        break;
                    }
                }
            }
        }
    }

    // OPTIMIZATION: Reduce redundant coordinate modifications
    private static final int COLLISION_RANGE_SQ = 320 * 320; // ~5 tiles range squared

    public int checkObject(Entity entity, boolean player) {
        int index = 999;

        for (int i = 0; i < gp.obj.length; i++) {
            if (gp.obj[i] != null) {
                // Light sources and other non-interactive markers are never collidable
                if (gp.obj[i].lightSource) continue;

                // Quick distance pre-filter: skip objects far away
                int dx = entity.worldX - gp.obj[i].worldX;
                int dy = entity.worldY - gp.obj[i].worldY;
                if (dx * dx + dy * dy > COLLISION_RANGE_SQ) continue;

                // Setup entity bounds
                int entityX = entity.worldX + entity.solidArea.x;
                int entityY = entity.worldY + entity.solidArea.y;
                
                // Setup object bounds
                int objX = gp.obj[i].worldX + gp.obj[i].solidArea.x;
                int objY = gp.obj[i].worldY + gp.obj[i].solidArea.y;

                // Apply predicted movement
                switch(entity.direction) {
                    case Entity.DIR_UP    -> entityY -= entity.speed;
                    case Entity.DIR_DOWN  -> entityY += entity.speed;
                    case Entity.DIR_LEFT  -> entityX -= entity.speed;
                    case Entity.DIR_RIGHT -> entityX += entity.speed;
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
                if (target[i].type == Entity.TYPE_MONSTER &&
                    (target[i].dying || !target[i].alive)) {
                    continue;
                }

                // Quick distance pre-filter: skip entities far away
                int dx = entity.worldX - target[i].worldX;
                int dy = entity.worldY - target[i].worldY;
                if (dx * dx + dy * dy > COLLISION_RANGE_SQ) continue;

                // Setup entity bounds
                int entityX = entity.worldX + entity.solidArea.x;
                int entityY = entity.worldY + entity.solidArea.y;
                
                // Setup target bounds
                int targetX = target[i].worldX + target[i].solidArea.x;
                int targetY = target[i].worldY + target[i].solidArea.y;

                // Apply predicted movement
                switch(entity.direction) {
                    case Entity.DIR_UP    -> entityY -= entity.speed;
                    case Entity.DIR_DOWN  -> entityY += entity.speed;
                    case Entity.DIR_LEFT  -> entityX -= entity.speed;
                    case Entity.DIR_RIGHT -> entityX += entity.speed;
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
            case Entity.DIR_UP    -> entityY -= entity.speed;
            case Entity.DIR_DOWN  -> entityY += entity.speed;
            case Entity.DIR_LEFT  -> entityX -= entity.speed;
            case Entity.DIR_RIGHT -> entityX += entity.speed;
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
