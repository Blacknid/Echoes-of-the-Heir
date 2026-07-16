package main;

import gfx.geom.IntPolygon;
import gfx.geom.Rect;
import gfx.geom.Shape;
import java.util.ArrayList;
import java.util.Arrays;

import entity.Entity;

public class CollisionChecker {

    GamePanel gp;
    
    // OPTIMIZATION: Reuse Rect objects to avoid allocation overhead
    private Rect tempRect = new Rect();

    // OPTIMIZATION: Spatial grid for collision rectangles
    // Divides the world into cells; only check rects in nearby cells
    private static final int GRID_CELL_SIZE = 128; // pixels per grid cell (2 tiles)
    private ArrayList<Integer>[] spatialGrid;
    private int gridCols, gridRows;

    // OPTIMIZATION: Generation-counter dedup for getCollisionBoundsInRect.
    // Replaces O(n) contains() calls with O(1) array lookup; each query bumps the gen
    // so stale marks from previous calls auto-expire without touching the array.
    private int[] seenGen;
    private int currentGen = 0;

    public CollisionChecker(GamePanel gp) {
        this.gp = gp;
    }

    // OPTIMIZATION: Build spatial grid index for collision rectangles
    @SuppressWarnings("unchecked")
    public void updateCollisionRectsCache() {
        int shapeCount = gp.tileM.collisionShapes.size();

        // Allocate / grow dedup array to match shape count
        if (seenGen == null || seenGen.length < shapeCount) {
            seenGen = new int[Math.max(shapeCount, 64)];
            currentGen = 0;
        }

        // Build spatial grid using bounding boxes for broad-phase
        gridCols = (gp.worldWidth / GRID_CELL_SIZE) + 2;
        gridRows = (gp.worldHeight / GRID_CELL_SIZE) + 2;
        int totalCells = gridCols * gridRows;
        spatialGrid = new ArrayList[totalCells];
        
        for (int i = 0; i < shapeCount; i++) {
            Rect r = gp.tileM.collisionBounds.get(i);
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
     * Checks whether a Rect hits any collision shape using the spatial grid.
     * O(k) where k = shapes in nearby cells, vs the previous O(n) full scan.
     * Used by PathFinder to avoid iterating all collision bounds per A* node.
     */
    public boolean rectHitsCollision(Rect r) {
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
        ArrayList<Rect> rects = gp.tileM.collisionBounds;
        for (int i = 0, size = rects.size(); i < size; i++) {
            if (r.intersects(rects.get(i))) return true;
        }
        return false;
    }

    /**
     * Return a list of collision bounding rectangles that intersect the given rectangle.
     * Uses the spatial grid for fast broad-phase lookup when available.
     */
    public java.util.ArrayList<Rect> getCollisionBoundsInRect(Rect r) {
        java.util.ArrayList<Rect> out = new java.util.ArrayList<>();
        getCollisionBoundsInRect(r, out);
        return out;
    }

    /**
     * Fill an existing list with axis-aligned light occluder rectangles that intersect r.
 * Uses tileM.lightOccluderRects, a subset of collision shapes that are guaranteed to be
     * non-rotated rectangles, so their shape exactly matches their bounding box.
     * Only these are safe to use as shadow casters; polygon/ellipse/rotated bounding boxes
     * are too large and cause false shadow projections.
     * Simple linear scan is fine: called only when player moves or on map load.
     */
    public void getLightOccludersInRect(Rect r, ArrayList<Rect> out) {
        ArrayList<Rect> occ = gp.tileM.lightOccluderRects;
        for (int i = 0, n = occ.size(); i < n; i++) {
            Rect br = occ.get(i);
            if (br.intersects(r)) out.add(br);
        }
    }

    /**
     * Fill an existing list with collision bounding rectangles that intersect r.
     * Caller must clear the list before calling. Avoids any ArrayList allocation.
     *
     * OPTIMIZATION: Uses a generation-counter dedup (O(1) per shape) instead of
     * ArrayList.contains() (O(n) per shape), which previously made this method
     * O(n²) when many shapes span the query region.
     */
    public void getCollisionBoundsInRect(Rect r, java.util.ArrayList<Rect> out) {
        // Bump generation, every shapeIdx with seenGen[idx] != gen is "unseen"
        int gen = ++currentGen;
        // Handle overflow (wraps back to 0 and re-clears the array)
        if (gen == 0) {
            Arrays.fill(seenGen, 0);
            gen = ++currentGen;
        }

        if (spatialGrid != null && gridCols > 0) {
            int minCX = Math.max(0, r.x / GRID_CELL_SIZE);
            int minCY = Math.max(0, r.y / GRID_CELL_SIZE);
            int maxCX = Math.min(gridCols - 1, (r.x + r.width) / GRID_CELL_SIZE);
            int maxCY = Math.min(gridRows - 1, (r.y + r.height) / GRID_CELL_SIZE);
            java.util.ArrayList<Rect> bounds = gp.tileM.collisionBounds;
            int[] seen = seenGen;
            int seenLen = seen.length;
            for (int cy = minCY; cy <= maxCY; cy++) {
                for (int cx = minCX; cx <= maxCX; cx++) {
                    int idx = cy * gridCols + cx;
                    if (idx >= 0 && idx < spatialGrid.length && spatialGrid[idx] != null) {
                        java.util.ArrayList<Integer> cell = spatialGrid[idx];
                        for (int j = 0, n = cell.size(); j < n; j++) {
                            int shapeIdx = cell.get(j);
                            if (shapeIdx >= seenLen || seen[shapeIdx] == gen) continue;
                            seen[shapeIdx] = gen;
                            Rect br = bounds.get(shapeIdx);
                            if (br.intersects(r)) out.add(br);
                        }
                    }
                }
            }
        } else {
            java.util.ArrayList<Rect> rects = gp.tileM.collisionBounds;
            for (int i = 0, n = rects.size(); i < n; i++) {
                Rect br = rects.get(i);
                if (br.intersects(r)) out.add(br); // no dedup needed, linear scan visits each once
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

        // --- Shape collision (from Tiled objectgroup layers, rects, rotated rects, polygons, ellipses) ---
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

    /**
     * Would {@code entity}'s solidArea, placed at the explicit position (nextX, nextY), overlap any
     * solid (collision == true) obj/iTile/npc/monster? Unlike checkObject/checkEntity (which predict
     * one step of the entity's own speed in its current facing direction), this takes an arbitrary
 * target position, needed for the attack lunge (applyAttackKick), which pushes the player by a
     * variable, eased distance each frame rather than a fixed per-tick speed. Without this, the lunge
     * only checked static tile collision (checkTileNext) and could push the player straight through a
     * solid interactive tile/object (a chest, a pot, an unbroken destructible) it was attacking.
     */
    public boolean isSolidAt(Entity entity, int nextX, int nextY) {
        int entityX = nextX + entity.solidArea.x;
        int entityY = nextY + entity.solidArea.y;
        int entityR = entityX + entity.solidArea.width;
        int entityB = entityY + entity.solidArea.height;
        if (overlapsSolid(entity, gp.obj, entityX, entityY, entityR, entityB)) return true;
        if (overlapsSolid(entity, gp.iTile, entityX, entityY, entityR, entityB)) return true;
        if (overlapsSolid(entity, gp.npc, entityX, entityY, entityR, entityB)) return true;
        if (overlapsSolid(entity, gp.monster, entityX, entityY, entityR, entityB)) return true;
        return false;
    }

    private boolean overlapsSolid(Entity self, Entity[] arr, int entityX, int entityY, int entityR, int entityB) {
        if (arr == null) return false;
        for (Entity e : arr) {
            if (e == null || e == self || !e.collision) continue;
            int dx = self.worldX - e.worldX;
            int dy = self.worldY - e.worldY;
            if (dx * dx + dy * dy > COLLISION_RANGE_SQ) continue;
            int ex = e.worldX + e.solidArea.x;
            int ey = e.worldY + e.solidArea.y;
            if (entityX < ex + e.solidArea.width && entityR > ex &&
                entityY < ey + e.solidArea.height && entityB > ey) {
                return true;
            }
        }
        return false;
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

                // Check intersection, use hurtPolygon on the target if present, else fall back to AABB
                boolean hit;
                if (target[i].hurtPolygon != null) {
                    // Translate the local-space polygon to world space for this check
                    IntPolygon wp = target[i].hurtPolygon;
                    Rect attackRect = new Rect(
                        entityX, entityY, entity.solidArea.width, entity.solidArea.height);
                    // Temporarily translate polygon to world coords
                    wp.translate(target[i].worldX, target[i].worldY);
                    hit = wp.intersects(attackRect);
                    wp.translate(-target[i].worldX, -target[i].worldY);
                } else {
                    hit = entityX < targetX + target[i].solidArea.width &&
                          entityX + entity.solidArea.width > targetX &&
                          entityY < targetY + target[i].solidArea.height &&
                          entityY + entity.solidArea.height > targetY;
                }
                if (hit) {
                    if (target[i].collision) {
                        entity.collisionOn = true;
                    }
                    index = i;
                }
            }
        }

        return index;
    }

    /**
     * Cone-vs-entity overlap for the free-aim melee attack hitbox (replaces the old cardinal
     * {@code Rect} attack box). Broad-phase distance pre-filter, then narrow-phase: samples the
     * target's hurtPolygon vertices (or solidArea corners/center as a fallback) against
     * {@link gfx.geom.Cone#contains}, mirroring the vertex-sampling pattern {@code checkEntity}
     * already uses for polygon targets. Returns the first hit index, or 999 if none.
     */
    public int checkEntityCone(gfx.geom.Cone cone, Entity[] target) {
        int index = 999;

        for (int i = 0; i < target.length; i++) {
            if (target[i] == null) continue;
            if (target[i].type == Entity.TYPE_MONSTER &&
                (target[i].dying || !target[i].alive)) {
                continue;
            }

            double dx = cone.apexX - target[i].worldX;
            double dy = cone.apexY - target[i].worldY;
            double reach = cone.radius + Math.max(target[i].solidArea.width, target[i].solidArea.height);
            if (dx * dx + dy * dy > reach * reach) continue;

            boolean hit;
            if (target[i].hurtPolygon != null) {
                IntPolygon wp = target[i].hurtPolygon;
                wp.translate(target[i].worldX, target[i].worldY);
                hit = false;
                for (int v = 0; v < wp.npoints; v++) {
                    if (cone.contains(wp.xpoints[v], wp.ypoints[v])) { hit = true; break; }
                }
                if (!hit) {
                    // Cone apex/rim sample falling inside a target that's small relative to the cone.
                    Rect b = wp.getBounds();
                    hit = wp.contains(cone.apexX, cone.apexY) ||
                          cone.intersects(b.x, b.y, b.width, b.height) && wp.contains((b.x + b.width / 2.0), (b.y + b.height / 2.0));
                }
                wp.translate(-target[i].worldX, -target[i].worldY);
            } else {
                Rect r = target[i].solidArea;
                int tx = target[i].worldX + r.x;
                int ty = target[i].worldY + r.y;
                hit = cone.contains(tx, ty) || cone.contains(tx + r.width, ty) ||
                      cone.contains(tx, ty + r.height) || cone.contains(tx + r.width, ty + r.height) ||
                      cone.contains(tx + r.width / 2.0, ty + r.height / 2.0);
            }

            if (hit) {
                index = i;
                break;
            }
        }

        return index;
    }

    /**
     * Same hit test as {@link #checkEntityCone}, but collects every matching index instead of
 * stopping at the first, used for the melee attack cone so a single swing can hit all
     * monsters caught in it, not just the nearest one in array order.
     */
    public java.util.List<Integer> checkEntityConeAll(gfx.geom.Cone cone, Entity[] target) {
        java.util.List<Integer> hits = new java.util.ArrayList<>();

        for (int i = 0; i < target.length; i++) {
            if (target[i] == null) continue;
            if (target[i].type == Entity.TYPE_MONSTER &&
                (target[i].dying || !target[i].alive)) {
                continue;
            }

            double dx = cone.apexX - target[i].worldX;
            double dy = cone.apexY - target[i].worldY;
            double reach = cone.radius + Math.max(target[i].solidArea.width, target[i].solidArea.height);
            if (dx * dx + dy * dy > reach * reach) continue;

            boolean hit;
            if (target[i].hurtPolygon != null) {
                IntPolygon wp = target[i].hurtPolygon;
                wp.translate(target[i].worldX, target[i].worldY);
                hit = false;
                for (int v = 0; v < wp.npoints; v++) {
                    if (cone.contains(wp.xpoints[v], wp.ypoints[v])) { hit = true; break; }
                }
                if (!hit) {
                    Rect b = wp.getBounds();
                    hit = wp.contains(cone.apexX, cone.apexY) ||
                          cone.intersects(b.x, b.y, b.width, b.height) && wp.contains((b.x + b.width / 2.0), (b.y + b.height / 2.0));
                }
                wp.translate(-target[i].worldX, -target[i].worldY);
            } else {
                Rect r = target[i].solidArea;
                int tx = target[i].worldX + r.x;
                int ty = target[i].worldY + r.y;
                hit = cone.contains(tx, ty) || cone.contains(tx + r.width, ty) ||
                      cone.contains(tx, ty + r.height) || cone.contains(tx + r.width, ty + r.height) ||
                      cone.contains(tx + r.width / 2.0, ty + r.height / 2.0);
            }

            if (hit) hits.add(i);
        }

        return hits;
    }

    /**
 * Where (if anywhere) does the attack cone touch a solid, non-monster collision, a Tiled
     * collision shape/blocking tile, or a solid obj/iTile/npc? Monsters are deliberately excluded:
     * they already get their own knockback from a hit (see Player.damageMonster), this is only for
     * the "swing into a wall/object and bounce off it" case. Returns the cone's apex (attacker
     * position) if nothing hit, so callers should check the boolean return, not just the point.
 * Deliberately approximate (bounding-box tests, not exact polygon overlap), this only drives a
     * cosmetic push-back + impact VFX, not real physics, so precision isn't worth the complexity.
     */
    public boolean checkAttackEnvironmentHit(gfx.geom.Cone cone, gfx.geom.Rect outContactPoint) {
        Rect cb = cone.getBounds();

        // Blocking map tiles under the cone's bounding box.
        int ts = gp.tileSize;
        int leftCol = cb.x / ts, rightCol = (cb.x + cb.width) / ts;
        int topRow = cb.y / ts, bottomRow = (cb.y + cb.height) / ts;
        if (!gp.tileM.collisionTileLayers.isEmpty()) {
            for (int row = topRow; row <= bottomRow; row++) {
                for (int col = leftCol; col <= rightCol; col++) {
                    if (gp.tileM.isTileBlocking(col, row)) {
                        outContactPoint.x = (int) cone.apexX + (int) (Math.cos(cone.centerAngle) * cone.radius);
                        outContactPoint.y = (int) cone.apexY + (int) (Math.sin(cone.centerAngle) * cone.radius);
                        return true;
                    }
                }
            }
        }

        // Tiled collision shapes (rects/polygons/ellipses from objectgroup layers).
        ArrayList<Shape> shapes = gp.tileM.collisionShapes;
        for (int i = 0; i < shapes.size(); i++) {
            if (shapes.get(i).intersects(cb)) {
                outContactPoint.x = (int) cone.apexX + (int) (Math.cos(cone.centerAngle) * cone.radius);
                outContactPoint.y = (int) cone.apexY + (int) (Math.sin(cone.centerAngle) * cone.radius);
                return true;
            }
        }

        // Solid obj/iTile/npc (monsters excluded on purpose, see method doc).
        if (coneHitsSolidArray(cone, gp.obj, outContactPoint)) return true;
        if (coneHitsSolidArray(cone, gp.iTile, outContactPoint)) return true;
        if (coneHitsSolidArray(cone, gp.npc, outContactPoint)) return true;
        return false;
    }

    private boolean coneHitsSolidArray(gfx.geom.Cone cone, Entity[] arr, gfx.geom.Rect outContactPoint) {
        if (arr == null) return false;
        for (Entity e : arr) {
            if (e == null || !e.collision) continue;
            Rect r = e.solidArea;
            int ex = e.worldX + r.x, ey = e.worldY + r.y;
            if (cone.intersects(ex, ey, r.width, r.height)) {
                outContactPoint.x = ex + r.width / 2;
                outContactPoint.y = ey + r.height / 2;
                return true;
            }
        }
        return false;
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
