package ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.Comparator;

import entity.Entity;
import main.GamePanel;

/**
 * Handles all rendering: world drawing, entity sorting, hitbox debug, HUD overlays.
 * Extracted from GamePanel to separate rendering from game logic.
 */
public class RenderPipeline {

    private final GamePanel gp;

    // OPTIMIZATION: Pre-allocate entityList with estimated capacity
    ArrayList<Entity> entityList = new ArrayList<>(150);
    int entityListIndex = 0;

    // OPTIMIZATION: Define Comparator once
    Comparator<Entity> renderSorter = (e1, e2) -> {
        int feet1 = e1.worldY + e1.solidArea.y + e1.solidArea.height;
        int feet2 = e2.worldY + e2.solidArea.y + e2.solidArea.height;
        return Integer.compare(feet1, feet2);
    };

    // PRE-ALLOCATED DEBUG COLORS & FONT (avoid per-frame allocation in hitbox debug)
    private static final Color DBG_RED    = new Color(255, 0, 0, 128);
    private static final Color DBG_PURPLE = new Color(255, 0, 255, 128);
    private static final Color DBG_ORANGE = new Color(255, 100, 0, 128);
    private static final Color DBG_YELLOW = new Color(255, 255, 0, 128);
    private static final Color DBG_CYAN   = new Color(0, 255, 255, 128);
    private static final Color DBG_BLUE   = new Color(0, 0, 255, 128);
    private static final Font  DBG_FONT   = new Font("Arial", Font.PLAIN, 12);

    public RenderPipeline(GamePanel gp) {
        this.gp = gp;
    }

    public void drawCurrentState(Graphics2D g2) {
        switch (gp.gameState) {
            case GamePanel.titleState:
                gp.ui.draw(g2);
                break;
            case GamePanel.transitionState:
                drawWorldState(g2);
                break;
            case GamePanel.playState:
            case GamePanel.pauseState:
            case GamePanel.dialogueState:
            case GamePanel.characterState:
            case GamePanel.optionsState:
            case GamePanel.gameOverState:
            case GamePanel.cutsceneState:
            case GamePanel.skillTreeState:
            default:
                drawWorldState(g2);
                break;
        }
    }

    private void drawWorldState(Graphics2D g2) {
        // OPTIMIZATION: AA off for pixel art
        g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_OFF);
        g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);

        // SMOOTH CAMERA + SCREEN SHAKE
        int camOffX = Math.round(gp.cameraX - (gp.player.worldX - gp.player.screenX));
        int camOffY = Math.round(gp.cameraY - (gp.player.worldY - gp.player.screenY));
        int shakeX = gp.screenShake.getOffsetX();
        int shakeY = gp.screenShake.getOffsetY();
        int totalOffX = camOffX + shakeX;
        int totalOffY = camOffY + shakeY;
        if (totalOffX != 0 || totalOffY != 0) {
            g2.translate(totalOffX, totalOffY);
        }

        gp.tileM.prepareVisibleTiles();
        gp.tileM.drawBackground(g2);

        collectRenderableEntities();

        // Trim to actual count so sort works on a real list (avoids subList heap alloc)
        while (entityList.size() > entityListIndex) entityList.remove(entityList.size() - 1);
        entityList.sort(renderSorter);

        // TILE PARTICLES
        int tpCount = 0;
        if (gp.tileParticleEmitter != null) {
            tpCount = gp.tileParticleEmitter.prepareSortedIndices();
        }
        int tpIdx = 0;
        int depthTileCount = gp.tileM.getDepthTileCount();
        int depthTileIdx = 0;

        // DRAW ENTITIES + TILE PARTICLES interleaved by Y (depth-correct)
        java.awt.Composite savedComp = g2.getComposite();
        for (int i = 0; i < entityListIndex; i++) {
            Entity e = entityList.get(i);
            int entityY = e.worldY + gp.tileSize;

            while (true) {
                float nextDepthTileY = depthTileIdx < depthTileCount ? gp.tileM.getDepthTileSortY(depthTileIdx) : Float.MAX_VALUE;
                float nextParticleY = tpIdx < tpCount ? gp.tileParticleEmitter.getSortY(tpIdx) : Float.MAX_VALUE;
                float nextY = Math.min(nextDepthTileY, nextParticleY);

                if (nextY > entityY) break;

                if (nextDepthTileY <= nextParticleY) {
                    g2.setComposite(savedComp);
                    gp.tileM.drawDepthTile(g2, depthTileIdx);
                    depthTileIdx++;
                } else {
                    gp.tileParticleEmitter.drawSingle(g2, tpIdx);
                    tpIdx++;
                }
            }

            g2.setComposite(savedComp);
            e.draw(g2);
        }

        while (depthTileIdx < depthTileCount || tpIdx < tpCount) {
            float nextDepthTileY = depthTileIdx < depthTileCount ? gp.tileM.getDepthTileSortY(depthTileIdx) : Float.MAX_VALUE;
            float nextParticleY = tpIdx < tpCount ? gp.tileParticleEmitter.getSortY(tpIdx) : Float.MAX_VALUE;

            if (nextDepthTileY <= nextParticleY) {
                g2.setComposite(savedComp);
                gp.tileM.drawDepthTile(g2, depthTileIdx);
                depthTileIdx++;
            } else {
                gp.tileParticleEmitter.drawSingle(g2, tpIdx);
                tpIdx++;
            }
        }
        g2.setComposite(savedComp);

        // FOREGROUND TILES
        gp.tileM.drawForeground(g2);

        clearRenderableEntities();

        // DAMAGE NUMBERS
        for (int i = 0; i < gp.damageNumbers.size(); i++) {
            entity.DamageNumber dn = gp.damageNumbers.get(i);
            if (dn.alive) dn.draw(g2);
        }

        // CUTSCENE
        gp.csManager.draw(g2);

        // DEBUG HITBOXES
        if (gp.HitBoxes) {
            drawHitboxDebug(g2);
        }

        // UNDO CAMERA + SHAKE
        if (totalOffX != 0 || totalOffY != 0) {
            g2.translate(-totalOffX, -totalOffY);
        }

        // ENVIRONMENT
        gp.eManager.draw(g2);

        // SHADER
        if (gp.mapShader != null) {
            gp.mapShader.drawAmbientParticles(g2);
            gp.mapShader.drawWeather(g2);
            gp.mapShader.drawColorGrading(g2);
            gp.mapShader.drawVignette(g2);
        }

        // UI
        gp.ui.draw(g2);

        // MINIMAP
        if (gp.minimap != null && gp.gameState == GamePanel.playState) {
            gp.minimap.draw(g2);
        }

        // QUEST TRACKER
        if (gp.questManager != null && gp.gameState == GamePanel.playState) {
            gp.questManager.drawTracker(g2);
        }

        // QUEST LOG OVERLAY
        if (gp.questManager != null && gp.questManager.isLogOpen()) {
            gp.questManager.drawLog(g2);
        }
    }

    void drawHitboxDebug(Graphics2D g2) {
        g2.setColor(DBG_RED);

        // PLAYER
        Rectangle r = gp.player.solidArea;
        int px = gp.player.screenX + r.x;
        int py = gp.player.screenY + r.y;
        g2.fillRect(px, py, r.width, r.height);
        if (gp.player.knockBack) {
            g2.setColor(DBG_PURPLE);
            g2.fillRect(px, py, r.width, r.height);
            g2.setColor(Color.WHITE);
            g2.setFont(DBG_FONT);
            g2.drawString(String.valueOf(gp.player.knockBackPower), px, py - 4);
            int cx = px + r.width/2;
            int cy = py + r.height/2;
            int vx = gp.player.knockBackVectorX * 2;
            int vy = gp.player.knockBackVectorY * 2;
            g2.drawLine(cx, cy, cx + vx, cy + vy);
            g2.fillOval(cx + vx - 2, cy + vy - 2, 4, 4);
            g2.setColor(DBG_RED);
        }

        // PLAYER ATTACK HITBOX
        if (gp.player.attacking) {
            g2.setColor(DBG_ORANGE);
            int ts = gp.tileSize;
            int attackWorldX = gp.player.worldX;
            int attackWorldY = gp.player.worldY;
            int aw = 0, ah = 0;
            switch(gp.player.direction) {
                case Entity.DIR_UP:    aw = ts - 16; ah = ts + 16; attackWorldX += 8; attackWorldY -= ts + 16; break;
                case Entity.DIR_DOWN:  aw = ts - 16; ah = ts + 16; attackWorldX += 8; attackWorldY += ts; break;
                case Entity.DIR_LEFT:  aw = ts + 16; ah = ts - 16; attackWorldX -= ts + 16; attackWorldY += 8; break;
                case Entity.DIR_RIGHT: aw = ts + 16; ah = ts - 16; attackWorldX += ts; attackWorldY += 8; break;
            }
            int screenX = attackWorldX - gp.player.worldX + gp.player.screenX;
            int screenY = attackWorldY - gp.player.worldY + gp.player.screenY;
            g2.fillRect(screenX, screenY, aw, ah);
        }

        // NPC
        g2.setColor(DBG_RED);
        for(Entity n : gp.npc) {
            if(n != null) {
                r = n.solidArea;
                int nx = n.worldX - gp.player.worldX + gp.player.screenX + r.x;
                int ny = n.worldY - gp.player.worldY + gp.player.screenY + r.y;
                g2.fillRect(nx, ny, r.width, r.height);
            }
        }

        // MONSTERS
        g2.setColor(DBG_YELLOW);
        for(Entity m : gp.monster) {
            if(m != null) {
                r = m.solidArea;
                int mx = m.worldX - gp.player.worldX + gp.player.screenX + r.x;
                int my = m.worldY - gp.player.worldY + gp.player.screenY + r.y;
                g2.fillRect(mx, my, r.width, r.height);
                if (m.knockBack) {
                    g2.setColor(DBG_PURPLE);
                    g2.fillRect(mx, my, r.width, r.height);
                    g2.setColor(Color.WHITE);
                    g2.setFont(DBG_FONT);
                    g2.drawString(String.valueOf(m.knockBackPower), mx, my - 4);
                    int cx = mx + r.width/2;
                    int cy = my + r.height/2;
                    int vx = m.knockBackVectorX * 2;
                    int vy = m.knockBackVectorY * 2;
                    g2.drawLine(cx, cy, cx + vx, cy + vy);
                    g2.fillOval(cx + vx - 2, cy + vy - 2, 4, 4);
                    g2.setColor(DBG_YELLOW);
                }
            }
        }

        // OBJECTS
        g2.setColor(DBG_RED);
        for(Entity o : gp.obj) {
            if(o != null) {
                r = o.solidArea;
                int ox = o.worldX - gp.player.worldX + gp.player.screenX + r.x;
                int oy = o.worldY - gp.player.worldY + gp.player.screenY + r.y;
                g2.fillRect(ox, oy, r.width, r.height);
            }
        }

        // INTERACTIVE TILES
        g2.setColor(DBG_CYAN);
        for(int i = 0; i < gp.iTile.length; i++) {
            if(gp.iTile[i] != null) {
                r = gp.iTile[i].solidArea;
                int ix = gp.iTile[i].worldX - gp.player.worldX + gp.player.screenX + r.x;
                int iy = gp.iTile[i].worldY - gp.player.worldY + gp.player.screenY + r.y;
                g2.fillRect(ix, iy, r.width, r.height);
            }
        }

        // COLLISION SHAPES
        if(gp.tileM.collisionShapes != null && !gp.tileM.collisionShapes.isEmpty()) {
            g2.setColor(DBG_BLUE);
            AffineTransform oldTransform = g2.getTransform();
            g2.translate(-gp.player.worldX + gp.player.screenX, -gp.player.worldY + gp.player.screenY);
            for(Shape shape : gp.tileM.collisionShapes) {
                g2.fill(shape);
            }
            g2.setTransform(oldTransform);
        }
    }

    private void collectRenderableEntities() {
        entityListIndex = 0;

        addToRenderList(gp.player);

        for (int i = 0; i < gp.npc.length; i++) {
            if (gp.npc[i] != null) addToRenderList(gp.npc[i]);
        }

        for (int i = 0; i < gp.obj.length; i++) {
            if (gp.obj[i] != null) addToRenderList(gp.obj[i]);
        }

        for (int i = 0; i < gp.monster.length; i++) {
            if (gp.monster[i] != null) addToRenderList(gp.monster[i]);
        }

        int projSize = gp.projectilesList.size();
        for (int i = 0; i < projSize; i++) {
            Entity proj = gp.projectilesList.get(i);
            if (proj != null) addToRenderList(proj);
        }

        int partSize = gp.particleList.size();
        for (int i = 0; i < partSize; i++) {
            Entity particle = gp.particleList.get(i);
            if (particle != null) addToRenderList(particle);
        }

        for (int i = 0; i < gp.iTile.length; i++) {
            if (gp.iTile[i] != null) addToRenderList(gp.iTile[i]);
        }
    }

    private void addToRenderList(Entity entity) {
        if (!gp.isEntityInViewport(entity, gp.tileSize)) return;
        if (entityListIndex < entityList.size()) {
            entityList.set(entityListIndex, entity);
        } else {
            entityList.add(entity);
        }
        entityListIndex++;
    }

    private void clearRenderableEntities() {
        for (int i = 0; i < entityListIndex; i++) {
            entityList.set(i, null);
        }
    }
}
