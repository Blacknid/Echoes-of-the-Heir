package ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Stroke;
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
    private static final Color DBG_PLAYER  = new Color(  0, 255,   0, 160); // green  — player solid
    private static final Color DBG_ATTACK  = new Color(255, 100,   0, 160); // orange — attack box
    private static final Color DBG_NPC     = new Color( 80, 140, 255, 160); // blue   — NPC solid
    private static final Color DBG_MONSTER = new Color(255, 220,   0, 160); // yellow — monster solid
    private static final Color DBG_OBJECT  = new Color(255,  80,  80, 160); // red    — objects
    private static final Color DBG_ITILE   = new Color(  0, 230, 230, 160); // cyan   — interactive tiles
    private static final Color DBG_PROJ    = new Color(255,   0, 180, 160); // pink   — projectiles
    private static final Color DBG_COLLIDE = new Color( 60,  80, 220, 140); // blue   — TMX collision shapes
    private static final Color DBG_KNOCK   = new Color(200,   0, 255, 160); // purple — knockback overlay
    private static final Font  DBG_FONT    = new Font("Arial", Font.BOLD, 10);

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

        // SCREEN SHAKE
        int shakeX = gp.screenShake.getOffsetX();
        int shakeY = gp.screenShake.getOffsetY();
        if (shakeX != 0 || shakeY != 0) {
            g2.translate(shakeX, shakeY);
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

        // UNDO SHAKE
        if (shakeX != 0 || shakeY != 0) {
            g2.translate(-shakeX, -shakeY);
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
        g2.setFont(DBG_FONT);
        Stroke    oldStroke = g2.getStroke();
        Composite oldComp   = g2.getComposite();
        g2.setStroke(new BasicStroke(1.5f));

        int pwx = gp.player.worldX;
        int pwy = gp.player.worldY;
        int psx = gp.player.screenX;
        int psy = gp.player.screenY;

        // ── PLAYER solid area (green) ────────────────────────────────────────
        {
            Rectangle r = gp.player.solidArea;
            int px = psx + r.x;
            int py = psy + r.y;
            g2.setColor(DBG_PLAYER);
            g2.fillRect(px, py, r.width, r.height);
            g2.setColor(DBG_PLAYER.darker());
            g2.drawRect(px, py, r.width, r.height);
            dbgLabel(g2, "PLAYER", px, py, r.width);

            // knockback vector arrow
            if (gp.player.knockBack) {
                g2.setColor(DBG_KNOCK);
                g2.fillRect(px, py, r.width, r.height);
                g2.setColor(Color.WHITE);
                g2.drawString("KB:" + gp.player.knockBackPower, px, py - 4);
                int cx = px + r.width / 2;
                int cy = py + r.height / 2;
                int vx = gp.player.knockBackVectorX * 24;
                int vy = gp.player.knockBackVectorY * 24;
                g2.setStroke(new BasicStroke(2f));
                g2.drawLine(cx, cy, cx + vx, cy + vy);
                g2.fillOval(cx + vx - 3, cy + vy - 3, 6, 6);
                g2.setStroke(new BasicStroke(1.5f));
            }
        }

        // ── PLAYER attack hitbox (orange) ────────────────────────────────────
        if (gp.player.attacking) {
            g2.setColor(DBG_ATTACK);
            int ts = gp.tileSize;
            int attackWorldX = pwx, attackWorldY = pwy;
            int aw = 0, ah = 0;
            switch (gp.player.direction) {
                case Entity.DIR_UP:    aw = ts - 16; ah = ts + 16; attackWorldX += 8;    attackWorldY -= ts + 16; break;
                case Entity.DIR_DOWN:  aw = ts - 16; ah = ts + 16; attackWorldX += 8;    attackWorldY += ts;      break;
                case Entity.DIR_LEFT:  aw = ts + 16; ah = ts - 16; attackWorldX -= ts + 16; attackWorldY += 8;   break;
                case Entity.DIR_RIGHT: aw = ts + 16; ah = ts - 16; attackWorldX += ts;   attackWorldY += 8;       break;
            }
            int asx = attackWorldX - pwx + psx;
            int asy = attackWorldY - pwy + psy;
            g2.fillRect(asx, asy, aw, ah);
            g2.setColor(Color.WHITE);
            g2.drawString("ATTACK", asx, asy - 3);
        }

        // ── NPCs (blue) ──────────────────────────────────────────────────────
        g2.setColor(DBG_NPC);
        for (Entity n : gp.npc) {
            if (n == null) continue;
            Rectangle r = n.solidArea;
            int nx = n.worldX - pwx + psx + r.x;
            int ny = n.worldY - pwy + psy + r.y;
            g2.fillRect(nx, ny, r.width, r.height);
            g2.setColor(DBG_NPC.darker());
            g2.drawRect(nx, ny, r.width, r.height);
            g2.setColor(DBG_NPC);
            dbgLabel(g2, n.name != null ? n.name : "NPC", nx, ny, r.width);
        }

        // ── MONSTERS (yellow, knockback in purple) ───────────────────────────
        for (Entity m : gp.monster) {
            if (m == null) continue;
            Rectangle r = m.solidArea;
            int mx = m.worldX - pwx + psx + r.x;
            int my = m.worldY - pwy + psy + r.y;
            g2.setColor(DBG_MONSTER);
            g2.fillRect(mx, my, r.width, r.height);
            g2.setColor(DBG_MONSTER.darker());
            g2.drawRect(mx, my, r.width, r.height);
            g2.setColor(DBG_MONSTER);
            dbgLabel(g2, m.name != null ? m.name : "MON", mx, my, r.width);
            if (m.knockBack) {
                g2.setColor(DBG_KNOCK);
                g2.fillRect(mx, my, r.width, r.height);
                g2.setColor(Color.WHITE);
                g2.drawString("KB:" + m.knockBackPower, mx, my - 4);
                int cx = mx + r.width / 2;
                int cy = my + r.height / 2;
                int vx = m.knockBackVectorX * 24;
                int vy = m.knockBackVectorY * 24;
                g2.setStroke(new BasicStroke(2f));
                g2.drawLine(cx, cy, cx + vx, cy + vy);
                g2.fillOval(cx + vx - 3, cy + vy - 3, 6, 6);
                g2.setStroke(new BasicStroke(1.5f));
            }
        }

        // ── OBJECTS (red) ────────────────────────────────────────────────────
        g2.setColor(DBG_OBJECT);
        for (Entity o : gp.obj) {
            if (o == null) continue;
            Rectangle r = o.solidArea;
            int ox = o.worldX - pwx + psx + r.x;
            int oy = o.worldY - pwy + psy + r.y;
            g2.fillRect(ox, oy, r.width, r.height);
            g2.setColor(DBG_OBJECT.darker());
            g2.drawRect(ox, oy, r.width, r.height);
            g2.setColor(DBG_OBJECT);
            dbgLabel(g2, o.name != null ? o.name : "OBJ", ox, oy, r.width);
        }

        // ── INTERACTIVE TILES (cyan) ─────────────────────────────────────────
        g2.setColor(DBG_ITILE);
        for (int i = 0; i < gp.iTile.length; i++) {
            if (gp.iTile[i] == null) continue;
            Rectangle r = gp.iTile[i].solidArea;
            int ix = gp.iTile[i].worldX - pwx + psx + r.x;
            int iy = gp.iTile[i].worldY - pwy + psy + r.y;
            g2.fillRect(ix, iy, r.width, r.height);
            g2.setColor(DBG_ITILE.darker());
            g2.drawRect(ix, iy, r.width, r.height);
            g2.setColor(DBG_ITILE);
            dbgLabel(g2, gp.iTile[i].name != null ? gp.iTile[i].name : "iTILE", ix, iy, r.width);
        }

        // ── PROJECTILES (pink) ───────────────────────────────────────────────
        g2.setColor(DBG_PROJ);
        for (int i = 0; i < gp.projectilesList.size(); i++) {
            Entity proj = gp.projectilesList.get(i);
            if (proj == null) continue;
            Rectangle r = proj.solidArea;
            int prx = proj.worldX - pwx + psx + r.x;
            int pry = proj.worldY - pwy + psy + r.y;
            g2.fillRect(prx, pry, r.width, r.height);
            g2.setColor(DBG_PROJ.darker());
            g2.drawRect(prx, pry, r.width, r.height);
            g2.setColor(DBG_PROJ);
            dbgLabel(g2, proj.name != null ? proj.name : "PROJ", prx, pry, r.width);
        }

        // ── TMX COLLISION SHAPES (dark blue, filled) ─────────────────────────
        if (gp.tileM.collisionShapes != null && !gp.tileM.collisionShapes.isEmpty()) {
            g2.setColor(DBG_COLLIDE);
            AffineTransform oldTransform = g2.getTransform();
            g2.translate(-pwx + psx, -pwy + psy);
            for (Shape shape : gp.tileM.collisionShapes) {
                g2.fill(shape);
                g2.setColor(DBG_COLLIDE.darker());
                g2.draw(shape);
                g2.setColor(DBG_COLLIDE);
            }
            g2.setTransform(oldTransform);
        }

        // ── EVENT ZONES (drawn as outlines with coloured labels) ─────────────
        if (gp.eHandler != null) {
            gp.eHandler.drawEventDebug(g2, pwx, pwy, psx, psy, gp.tileSize);
        }

        // ── LEGEND (top-left corner) ─────────────────────────────────────────
        g2.setStroke(oldStroke);
        g2.setComposite(oldComp);
        drawHitboxLegend(g2);
    }

    /** Draws a semi-transparent legend box in the top-left corner. */
    private void drawHitboxLegend(Graphics2D g2) {
        String[] labels = {
            "PLAYER", "ATTACK", "NPC",   "MONSTER",
            "OBJECT", "iTILE",  "PROJ",  "COLLIDE",
            "WARP",   "HEAL",   "TRAP",  "DIAL",
            "GATE",   "SAVE",   "QUEST", "SHAKE",
            "MEM",    "ZONE",   "SP"
        };
        Color[] colors = {
            DBG_PLAYER,               DBG_ATTACK,               DBG_NPC,                  DBG_MONSTER,
            DBG_OBJECT,               DBG_ITILE,                DBG_PROJ,                 DBG_COLLIDE,
            new Color(255,   0, 255), new Color(  0, 220,  80), new Color(220,  40,  40), new Color(255, 230,   0),
            new Color(255, 140,   0), new Color(  0, 220, 220), new Color(180,  80, 255), new Color(220, 220, 220),
            new Color( 60,  80, 255), new Color(255, 160,   0), new Color(160, 255,  80)
        };

        int swatchSize = 11;
        int rowH       = 14;
        int pad        = 6;
        int cols       = 2;
        int rows       = (int) Math.ceil((double) labels.length / cols);
        int colW       = 90;
        int boxW       = cols * colW + pad * 2;
        int boxH       = rows * rowH + pad * 2 + 14; // +14 for title row
        int bx         = 6;
        int by         = 6;

        // background
        g2.setColor(new Color(0, 0, 0, 160));
        g2.fillRoundRect(bx, by, boxW, boxH, 8, 8);
        g2.setColor(new Color(200, 200, 200, 180));
        g2.drawRoundRect(bx, by, boxW, boxH, 8, 8);

        // title
        g2.setFont(new Font("Arial", Font.BOLD, 10));
        g2.setColor(Color.WHITE);
        g2.drawString("[H] HITBOX DEBUG", bx + pad, by + pad + 9);

        // entries
        g2.setFont(DBG_FONT);
        for (int i = 0; i < labels.length; i++) {
            int col = i % cols;
            int row = i / cols;
            int ex  = bx + pad + col * colW;
            int ey  = by + pad + 14 + row * rowH;
            g2.setColor(colors[i]);
            g2.fillRect(ex, ey, swatchSize, swatchSize);
            g2.setColor(Color.WHITE);
            g2.drawString(labels[i], ex + swatchSize + 3, ey + swatchSize - 1);
        }
    }

    /** Draws a small centred label above a hitbox rectangle. */
    private void dbgLabel(Graphics2D g2, String text, int rx, int ry, int rw) {
        FontMetrics fm = g2.getFontMetrics(DBG_FONT);
        int tw = fm.stringWidth(text);
        int tx = rx + (rw - tw) / 2;
        int ty = ry - 2;
        g2.setColor(new Color(0, 0, 0, 140));
        g2.fillRect(tx - 1, ty - 9, tw + 2, 10);
        g2.setColor(Color.WHITE);
        g2.setFont(DBG_FONT);
        g2.drawString(text, tx, ty);
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
