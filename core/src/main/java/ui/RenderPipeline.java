package ui;

import gfx.Color;
import gfx.Font;
import gfx.FontMetrics;
import gfx.GdxRenderer;
import gfx.Sprite;
import gfx.Stroke;
import gfx.geom.Rect;
import gfx.geom.Shape;
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
    // NOTE: the legacy Sprite worldFrame cache (reused for paused/menu states) is dropped
    // for the GPU port — drawWorldLayers re-renders each frame. A FrameBuffer-backed cache can be
    // re-added in the optimization pass if paused-state CPU cost ever matters (it won't on the GPU).
    private boolean worldCacheValid = false;

    private Sprite agroIndicator;
    private Sprite agroEnemyIndicator;

    ArrayList<Entity> entityList = new ArrayList<>(150);
    int entityListIndex = 0;

    // depthSortYOffset included here too (previously only the depth-tile interleave loop in
    // drawWorldLayers used it) so a manual nudge like IT_GrassPatch's "always draw behind everyone"
    // trick actually affects entity-vs-entity order, not just entity-vs-tile order.
    Comparator<Entity> renderSorter = (e1, e2) -> {
        int feet1 = e1.worldY + e1.solidArea.y + e1.solidArea.height + e1.depthSortYOffset;
        int feet2 = e2.worldY + e2.solidArea.y + e2.solidArea.height + e2.depthSortYOffset;
        return Integer.compare(feet1, feet2);
    };

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
        agroIndicator = util.ResourceCache.loadScaledImageIfPresent(
            "/res/effects/aggro_indicator.png", gp.tileSize / 2, gp.tileSize / 2);
        agroEnemyIndicator = util.ResourceCache.loadScaledImageIfPresent(
            "/res/effects/aggro_enemy.png", gp.tileSize, gp.tileSize);
    }

    public void invalidateWorldCache() {
        worldCacheValid = false;
    }

    public void drawCurrentState(GdxRenderer g2) {
        switch (gp.gameState) {
            case GamePanel.titleState:
                invalidateWorldCache();
                gp.ui.draw(g2);
                break;
            case GamePanel.levelUpState:
                // Level-up screen draws its own opaque overlay — skip expensive world render
                gp.ui.draw(g2);
                break;
            default:
                // GPU port: re-render the world directly each frame (no Sprite cache).
                drawWorldLayers(g2);
                drawWorldOverlays(g2);
                break;
        }
    }

    // Bloom is HIGH-only and only worth the capture cost when the shader path is live. Threshold keeps
    // only genuinely bright pixels (lights, fire, sparkles) glowing; intensity is the glow strength.
    // Threshold raised so ordinary bright pixels (skin, white/cream clothing on a player or NPC under
    // ambient light) stay below it and never bloom — only genuine light-source hotspots (which the
    // light shader boosts past 1.0 at their core) still glow. Keeps characters crisp, not "blurry".
    private static final float BLOOM_THRESHOLD = 0.97f;
    private static final float BLOOM_INTENSITY = 0.18f;

    private void drawWorldLayers(GdxRenderer g2) {
        // Capture the world+lighting into an offscreen target so bloom can read the finished scene, then
        // blit it back and add glow — all BEFORE the HUD (drawn later on the real screen, so it never
        // blooms). Only on HIGH with working bloom shaders; otherwise render straight to screen.
        // F8 (LightDebug.noBloom) disables the WHOLE post chain (scene capture + grade + rim + bloom):
        // if the dancing stops with it off, the bug lives in the capture/blit path, not the lighting.
        boolean bloom = gp.config.graphicsQuality == main.Config.GRAPHICS_HIGH && g2.bloomAvailable()
                && !gfx.shader.LightDebug.noBloom;
        if (bloom) g2.beginSceneCapture();

        int shakeX = gp.screenShake.getOffsetX();
        int shakeY = gp.screenShake.getOffsetY();
        if (shakeX != 0 || shakeY != 0) {
            g2.translate(shakeX, shakeY);
        }

        // Dialogue camera: pan (recenter on player+NPC midpoint) then zoom about screen center.
        // Wraps only the WORLD pass; cleared before the HUD/dialogue-box pass so the UI stays crisp.
        boolean dlgCam = gp.dlgZoom != 1f || gp.dlgPanX != 0f || gp.dlgPanY != 0f;
        if (dlgCam) {
            g2.translate(gp.dlgPanX, gp.dlgPanY);
            g2.setWorldZoom(gp.dlgZoom, gp.screenWidth / 2f, gp.screenHeight / 2f);
        }

        gp.tileM.prepareVisibleTiles();
        gp.tileM.drawBackground(g2);
        drawGroundShadows(g2);

        collectRenderableEntities();

        // OPTIMIZATION: Trim to actual count using subList().clear() — O(1) range remove
        // instead of repeated remove() which is O(n) for each element removed.
        if (entityList.size() > entityListIndex) {
            entityList.subList(entityListIndex, entityList.size()).clear();
        }
        entityList.sort(renderSorter);

        int tpCount = 0;
        if (gp.tileParticleEmitter != null) {
            tpCount = gp.tileParticleEmitter.prepareSortedIndices();
        }
        int tpIdx = 0;
        int depthTileCount = gp.tileM.getDepthTileCount();
        int depthTileIdx = 0;

        for (int i = 0; i < entityListIndex; i++) {
            Entity e = entityList.get(i);
            int entityY = e.worldY + gp.tileSize + e.depthSortYOffset;

            while (true) {
                float nextDepthTileY = depthTileIdx < depthTileCount ? gp.tileM.getDepthTileSortY(depthTileIdx) : Float.MAX_VALUE;
                float nextParticleY = tpIdx < tpCount ? gp.tileParticleEmitter.getSortY(tpIdx) : Float.MAX_VALUE;
                float nextY = Math.min(nextDepthTileY, nextParticleY);

                if (nextY > entityY) break;

                if (nextDepthTileY <= nextParticleY) {
                    g2.setAlpha(1f);
                    gp.tileM.drawDepthTile(g2, depthTileIdx);
                    depthTileIdx++;
                } else {
                    gp.tileParticleEmitter.drawSingle(g2, tpIdx);
                    tpIdx++;
                }
            }

            g2.setAlpha(1f);
            e.draw(g2);
        }

        while (depthTileIdx < depthTileCount || tpIdx < tpCount) {
            float nextDepthTileY = depthTileIdx < depthTileCount ? gp.tileM.getDepthTileSortY(depthTileIdx) : Float.MAX_VALUE;
            float nextParticleY = tpIdx < tpCount ? gp.tileParticleEmitter.getSortY(tpIdx) : Float.MAX_VALUE;

            if (nextDepthTileY <= nextParticleY) {
                g2.setAlpha(1f);
                gp.tileM.drawDepthTile(g2, depthTileIdx);
                depthTileIdx++;
            } else {
                gp.tileParticleEmitter.drawSingle(g2, tpIdx);
                tpIdx++;
            }
        }
        g2.setAlpha(1f);

        if (gp.mpClient != null && gp.mpClient.isConnected()) {
            gp.drawRemotePlayers(g2);
        }

        gp.drawLocalPlayerNametag(g2);

        gp.tileM.drawForeground(g2);

        drawBossHpBar(g2);

        clearRenderableEntities();

        drawNPCInteractIndicators(g2);
        drawEnemyAlertIndicators(g2);

        for (int i = 0; i < gp.damageNumbers.size(); i++) {
            entity.DamageNumber dn = gp.damageNumbers.get(i);
            if (dn.alive) dn.draw(g2);
        }

        gp.csManager.draw(g2);

        if (gp.HitBoxes) {
            drawHitboxDebug(g2);
        }

        if (gp.windPainter != null && gp.windPainter.isActive()) {
            gp.windPainter.draw(g2);
        }

        if (dlgCam) {
            g2.clearWorldZoom();
            g2.translate(-gp.dlgPanX, -gp.dlgPanY);
        }

        if (shakeX != 0 || shakeY != 0) {
            g2.translate(-shakeX, -shakeY);
        }

        boolean suppressLights = gp.gameState == GamePanel.cutsceneState
                && gp.csManager.suppressAmbientLights();
        if (!suppressLights) {
            gp.eManager.draw(g2);

            if (gp.mapShader != null) {
                gp.mapShader.drawAmbientParticles(g2);
                gp.mapShader.drawWeather(g2);
                gp.mapShader.drawColorGrading(g2);
                gp.mapShader.drawVignette(g2);
            }
        }

        gp.cloudLayer.draw(g2);
        gp.dustFogLayer.draw(g2);

        // Finish scene capture: blit the world back to screen and add the bloom glow (HIGH only). Must
        // run before the HUD (drawWorldOverlays) so UI text/panels stay crisp and un-bloomed.
        if (bloom) {
            g2.endSceneCaptureAndBloom(BLOOM_THRESHOLD, BLOOM_INTENSITY);
        }
    }

    /**
     * Flat ground shadows (see Entity.drawGroundShadow) as their own pass, drawn once on the ground
     * right after the background tiles and before ANY entity — never depth-sorted with the entities
     * themselves. They used to be drawn inline inside each caster's own draw() call, which put them at
     * that caster's depth-sort slot: whenever the player or an NPC stood "in front of" (later in the
     * depth order than) a tree, the tree's draw() ran after them and painted its shadow on top of their
     * sprite. Ground shadows conceptually belong to the ground, not the caster's silhouette, so they
     * must always render first, under everyone, regardless of depth order.
     */
    private void drawGroundShadows(GdxRenderer g2) {
        if (gp.iTile == null) return;
        for (entity.Entity it : gp.iTile) {
            if (it != null) it.drawGroundShadowPass(g2);
        }
    }

    private void drawWorldOverlays(GdxRenderer g2) {
        // Cinematic letterbox bars for dialogue — drawn over the (now-unzoomed) world but under the
        // HUD/dialogue box below. Height eases in/out with the dialogue camera via gp.dlgBars.
        if (gp.dlgBars > 0.01f) {
            int h = Math.round(GamePanel.DLG_BAR_MAX_H * gp.dlgBars);
            g2.setColor(new Color(0, 0, 0, 255));
            g2.fillRect(0, 0, gp.screenWidth, h);
            g2.fillRect(0, gp.screenHeight - h, gp.screenWidth, h);
        }

        gp.ui.draw(g2);

        if (gp.questManager != null && gp.gameState == GamePanel.playState) {
            gp.questManager.drawTracker(g2);
        }

        if (gp.questManager != null && gp.questManager.isLogOpen()) {
            gp.questManager.drawLog(g2);
        }

        if (gp.minimap != null && gp.gameState == GamePanel.playState) {
            gp.minimap.drawWorldMap(g2);
        }

        // Dancing-lights diagnostic overlay (F5 toggles; drawn last so it sits over everything).
        LightDebugHud.draw(g2, gp);
    }

    /**
     * Debug-only attack cone visualization, styled after Moonshire (Challacade)'s telegraph: a fan
     * of small rotated rectangular ticks (not continuous lines) radiating out from a gap around the
     * apex, plus a bright glow at the apex. Never drawn outside debug mode (gp.HitBoxes).
     */
    private static final int CONE_DEBUG_RAY_COUNT = 16;
    private static final float CONE_DEBUG_TICK_LEN = 0.62f;    // fraction of radius the tick spans
    private static final float CONE_DEBUG_TICK_INNER = 0.30f;  // fraction of radius before the tick starts (gap at apex)
    private static final float CONE_DEBUG_TICK_WIDTH = 3.5f;   // px, across the ray

    private void drawAttackConeDebug(GdxRenderer g2, gfx.geom.Cone cone) {
        double apexX = cone.apexX, apexY = cone.apexY;
        double radius = cone.radius;

        // Radiating tick marks (small rotated rectangles), evenly spaced across the sector.
        g2.setColor(new Color(255, 255, 255, 210));
        for (int i = 0; i <= CONE_DEBUG_RAY_COUNT; i++) {
            double t = -cone.halfAngle + (2 * cone.halfAngle) * i / (double) CONE_DEBUG_RAY_COUNT;
            double a = cone.centerAngle + t;
            double cosA = Math.cos(a), sinA = Math.sin(a);
            // Perpendicular unit vector (for the tick's width).
            double px = -sinA, py = cosA;

            double rInner = radius * CONE_DEBUG_TICK_INNER;
            double rOuter = radius * (CONE_DEBUG_TICK_INNER + CONE_DEBUG_TICK_LEN);
            double halfW = CONE_DEBUG_TICK_WIDTH / 2.0;

            double ix = apexX + cosA * rInner, iy = apexY + sinA * rInner;
            double ox = apexX + cosA * rOuter, oy = apexY + sinA * rOuter;

            int[] xs = {
                (int) Math.round(ix - px * halfW), (int) Math.round(ix + px * halfW),
                (int) Math.round(ox + px * halfW), (int) Math.round(ox - px * halfW)
            };
            int[] ys = {
                (int) Math.round(iy - py * halfW), (int) Math.round(iy + py * halfW),
                (int) Math.round(oy + py * halfW), (int) Math.round(oy - py * halfW)
            };
            g2.fill(new gfx.geom.IntPolygon(xs, ys, 4));
        }

        // Bright glow at the apex, like a small flash/spark — sits in the gap the ticks leave.
        int glowR = 7;
        g2.setAlpha(0.9f);
        g2.setColor(Color.WHITE);
        g2.fillOval((int) apexX - glowR, (int) apexY - glowR, glowR * 2, glowR * 2);
        g2.setAlpha(1f);

        g2.setColor(Color.WHITE);
        g2.drawString("ATTACK", (int) apexX, (int) apexY - glowR - 6);
    }

    /** Draw a filled+outlined octagon for entity e at its screen position. Uses hurtPolygon if set, else builds one from solidArea. */
    private void drawEntityOctagon(GdxRenderer g2, Entity e, int offX, int offY, Color fill, Color border, String label) {
        gfx.geom.IntPolygon poly = e.hurtPolygon;
        if (poly != null) {
            poly.translate(e.worldX + offX, e.worldY + offY);
            g2.setColor(fill);
            g2.fill(poly);
            g2.setColor(border);
            g2.draw(poly);
            poly.translate(-(e.worldX + offX), -(e.worldY + offY));
        } else {
            Rect r = e.solidArea;
            int cx = e.worldX + offX + r.x + r.width  / 2;
            int cy = e.worldY + offY + r.y + r.height / 2;
            int rx = r.width  / 2;
            int ry = r.height / 2;
            int cutX = (int)(rx * 0.5);
            int cutY = (int)(ry * 0.5);
            int[] xs = { cx-rx+cutX, cx+rx-cutX, cx+rx,       cx+rx,       cx+rx-cutX, cx-rx+cutX, cx-rx,       cx-rx       };
            int[] ys = { cy-ry,      cy-ry,      cy-ry+cutY,  cy+ry-cutY,  cy+ry,      cy+ry,      cy+ry-cutY,  cy-ry+cutY  };
            gfx.geom.IntPolygon tmp = new gfx.geom.IntPolygon(xs, ys, 8);
            g2.setColor(fill);
            g2.fill(tmp);
            g2.setColor(border);
            g2.draw(tmp);
        }
        if (label != null) {
            Rect r = e.solidArea;
            int lx = e.worldX + offX + r.x;
            int ly = e.worldY + offY + r.y;
            g2.setColor(fill);
            dbgLabel(g2, label, lx, ly, r.width);
        }
    }

    void drawHitboxDebug(GdxRenderer g2) {
        g2.setFont(DBG_FONT);
        Stroke    oldStroke = g2.getStroke();
        g2.setStroke(new Stroke(1.5f));

        int pwx = gp.getCamWorldX();
        int pwy = gp.getCamWorldY();
        int psx = gp.player.screenX;
        int psy = gp.player.screenY;

        // Player
        {
            drawEntityOctagon(g2, gp.player, -pwx + psx, -pwy + psy, DBG_PLAYER, DBG_PLAYER.darker(), "PLAYER");
            // Pink solid rectangle (actual AABB used for tile/wall collision)
            {
                Rect r = gp.player.solidArea;
                int rx = psx + r.x;
                int ry = psy + r.y;
                g2.setAlpha(0.25f);
                g2.setColor(new Color(255, 105, 180));
                g2.fillRect(rx, ry, r.width, r.height);
                g2.setAlpha(1f);
                g2.setColor(new Color(255, 20, 147));
                g2.drawRect(rx, ry, r.width, r.height);
            }
            if (gp.player.knockBack) {
                Rect r = gp.player.solidArea;
                int px = psx + r.x;
                int py = psy + r.y;
                g2.setColor(DBG_KNOCK);
                g2.setColor(Color.WHITE);
                g2.drawString("KB:" + gp.player.knockBackPower, px, py - 4);
                int cx = px + r.width / 2;
                int cy = py + r.height / 2;
                int vx = gp.player.knockBackVectorX * 24;
                int vy = gp.player.knockBackVectorY * 24;
                g2.setStroke(new Stroke(2f));
                g2.drawLine(cx, cy, cx + vx, cy + vy);
                g2.fillOval(cx + vx - 3, cy + vy - 3, 6, 6);
                g2.setStroke(new Stroke(1.5f));
            }
        }

        if (gp.player.attacking && gp.player.attackCone != null) {
            // attackCone is stored in world space (apex = player world center); translate into
            // screen space like the tile-collision-shapes debug loop below does.
            float oldTX = g2.getTranslateX(); float oldTY = g2.getTranslateY();
            g2.translate(-pwx + psx, -pwy + psy);
            drawAttackConeDebug(g2, gp.player.attackCone);
            g2.setTranslate(oldTX, oldTY);
        }

        // NPCs
        for (Entity n : gp.npc) {
            if (n == null) continue;
            drawEntityOctagon(g2, n, -pwx + psx, -pwy + psy, DBG_NPC, DBG_NPC.darker(), n.name != null ? n.name : "NPC");
            if (n.interactRange > 0) {
                int ncx = n.worldX - pwx + psx + n.solidArea.x + n.solidArea.width  / 2;
                int ncy = n.worldY - pwy + psy + n.solidArea.y + n.solidArea.height / 2;
                int rad = n.interactRange;
                Color rangeColor = new Color(255, 220, 60, 60);
                Color rangeBorder = new Color(255, 220, 60, 200);
                g2.setAlpha(1f);
                g2.setColor(rangeColor);
                g2.fillOval(ncx - rad, ncy - rad, rad * 2, rad * 2);
                g2.setColor(rangeBorder);
                g2.setStroke(new Stroke(1.5f));
                g2.drawOval(ncx - rad, ncy - rad, rad * 2, rad * 2);
                g2.setAlpha(1f);
                g2.setColor(new Color(255, 220, 60));
                g2.setFont(DBG_FONT);
                g2.drawString("range:" + rad, ncx - 18, ncy + rad + 12);
            }
        }

        // Monsters
        for (Entity m : gp.monster) {
            if (m == null) continue;
            drawEntityOctagon(g2, m, -pwx + psx, -pwy + psy, DBG_MONSTER, DBG_MONSTER.darker(), m.name != null ? m.name : "MON");
            if (m instanceof entity.Boss boss && boss.isAttackingNow() && boss.attackCone != null) {
                float oldTX = g2.getTranslateX(); float oldTY = g2.getTranslateY();
                g2.translate(-pwx + psx, -pwy + psy);
                drawAttackConeDebug(g2, boss.attackCone);
                g2.setTranslate(oldTX, oldTY);
            }
            if (m.knockBack) {
                Rect r = m.solidArea;
                int mx = m.worldX - pwx + psx + r.x;
                int my = m.worldY - pwy + psy + r.y;
                g2.setColor(DBG_KNOCK);
                g2.setColor(Color.WHITE);
                g2.drawString("KB:" + m.knockBackPower, mx, my - 4);
                int cx = mx + r.width / 2;
                int cy = my + r.height / 2;
                int vx = m.knockBackVectorX * 24;
                int vy = m.knockBackVectorY * 24;
                g2.setStroke(new Stroke(2f));
                g2.drawLine(cx, cy, cx + vx, cy + vy);
                g2.fillOval(cx + vx - 3, cy + vy - 3, 6, 6);
                g2.setStroke(new Stroke(1.5f));
            }
        }

        // Objects
        for (Entity o : gp.obj) {
            if (o == null) continue;
            drawEntityOctagon(g2, o, -pwx + psx, -pwy + psy, DBG_OBJECT, DBG_OBJECT.darker(), o.name != null ? o.name : "OBJ");
        }

        // Interactive tiles
        for (int i = 0; i < gp.iTile.length; i++) {
            if (gp.iTile[i] == null) continue;
            drawEntityOctagon(g2, gp.iTile[i], -pwx + psx, -pwy + psy, DBG_ITILE, DBG_ITILE.darker(), gp.iTile[i].name != null ? gp.iTile[i].name : "iTILE");
        }

        // Projectiles
        for (int i = 0; i < gp.projectilesList.size(); i++) {
            Entity proj = gp.projectilesList.get(i);
            if (proj == null) continue;
            drawEntityOctagon(g2, proj, -pwx + psx, -pwy + psy, DBG_PROJ, DBG_PROJ.darker(), proj.name != null ? proj.name : "PROJ");
        }

        if (gp.tileM.collisionShapes != null && !gp.tileM.collisionShapes.isEmpty()) {
            g2.setColor(DBG_COLLIDE);
            float oldTX = g2.getTranslateX(); float oldTY = g2.getTranslateY();
            g2.translate(-pwx + psx, -pwy + psy);
            for (Shape shape : gp.tileM.collisionShapes) {
                g2.fill(shape);
                g2.setColor(DBG_COLLIDE.darker());
                g2.draw(shape);
                g2.setColor(DBG_COLLIDE);
            }
            g2.setTranslate(oldTX, oldTY);
        }

        if (gp.eHandler != null) {
            gp.eHandler.drawEventDebug(g2, pwx, pwy, psx, psy, gp.tileSize);
        }

        g2.setStroke(oldStroke);
        g2.setAlpha(1f);
        drawHitboxLegend(g2);
    }

    /** Draws a semi-transparent legend box in the top-left corner. */
    private void drawHitboxLegend(GdxRenderer g2) {
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

        g2.setColor(new Color(0, 0, 0, 160));
        g2.fillRoundRect(bx, by, boxW, boxH, 8, 8);
        g2.setColor(new Color(200, 200, 200, 180));
        g2.drawRoundRect(bx, by, boxW, boxH, 8, 8);

        g2.setFont(new Font("Arial", Font.BOLD, 10));
        g2.setColor(Color.WHITE);
        g2.drawString("[H] HITBOX DEBUG", bx + pad, by + pad + 9);

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
    private void dbgLabel(GdxRenderer g2, String text, int rx, int ry, int rw) {
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
            // Wider margin: covers the tallest interactive tile sprite (IT_Tree, 4 tiles), so a
            // tree isn't culled based on just its 1-tile base position while its canopy is
            // still visible on screen.
            if (gp.iTile[i] != null) addToRenderList(gp.iTile[i], gp.tileSize * 4);
        }
    }

    private void addToRenderList(Entity entity) {
        addToRenderList(entity, gp.tileSize);
    }

    private void addToRenderList(Entity entity, int margin) {
        if (!gp.isEntityInViewport(entity, margin)) return;
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

    private static final int ALERT_DURATION = 60;

    private void drawEnemyAlertIndicators(GdxRenderer g2) {
        if (agroEnemyIndicator == null || gp.gameState != GamePanel.playState) return;
        int pwx = gp.player.worldX, pwy = gp.player.worldY;
        int psx = gp.player.screenX, psy = gp.player.screenY;
        int iw = agroEnemyIndicator.getWidth(), ih = agroEnemyIndicator.getHeight();
        for (Entity m : gp.monster) {
            if (m == null || m.alertTick <= 0 || m.dying) continue;
            float progress = m.alertTick / (float) ALERT_DURATION; // 1.0 → 0.0
            // Pop-out: grow 0→1.3 in first 30%, hold at 1.0 until 80%, fade out last 20%
            float scale;
            if (progress > 0.7f)      scale = (1.0f - progress) / 0.3f * 1.3f;  // grow in (progress 1→0.7)
            else if (progress > 0.2f) scale = 1.0f;                               // hold
            else                       scale = progress / 0.2f;                    // shrink out (progress 0.2→0)
            float alpha = progress < 0.15f ? progress / 0.15f : 1f;               // fade out last 15%
            if (scale <= 0f || alpha <= 0f) continue;
            int drawW = Math.max(1, (int)(iw * scale));
            int drawH = Math.max(1, (int)(ih * scale));
            int sx = m.worldX - pwx + psx;
            int sy = m.worldY - pwy + psy;
            int drawX = sx + (gp.tileSize - drawW) / 2;
            int drawY = sy - drawH - 6;
            g2.setAlpha(alpha);
            g2.drawImage(agroEnemyIndicator, drawX, drawY, drawW, drawH);
            g2.setAlpha(1f);
        }
    }

    /**
     * Draws the first in-range boss's HP bar, as a pass separate from the Y-sorted world entity
     * loop. Bosses used to draw their own bar inline inside Boss.draw(), which runs interleaved with
     * depth-sorted tiles (tall foliage/walls/overhangs) — a depth tile sorted after the boss could
     * paint over the bar. This overlay always runs after all world/foreground layers, so it can't be
     * covered by anything in the world.
     */
    private void drawBossHpBar(GdxRenderer g2) {
        for (Entity m : gp.monster) {
            if (m instanceof entity.Boss boss && boss.shouldShowHpBar()) {
                boss.drawBossHPBar(g2);
                return;
            }
        }
    }

    private void drawNPCInteractIndicators(GdxRenderer g2) {
        if (agroIndicator == null || gp.gameState != GamePanel.playState) return;
        int pwx = gp.getCamWorldX();
        int pwy = gp.getCamWorldY();
        int psx = gp.player.screenX;
        int psy = gp.player.screenY;
        int pcx = gp.player.getCenterX();
        int pcy = gp.player.getCenterY();
        int iw  = agroIndicator.getWidth();
        int ih  = agroIndicator.getHeight();
        for (Entity n : gp.npc) {
            if (n == null || n.interactRange <= 0) continue;
            int dx     = n.getCenterX() - pcx;
            int dy     = n.getCenterY() - pcy;
            boolean inRange = (dx * dx + dy * dy) <= (long) n.interactRange * n.interactRange;
            if (!inRange) continue;
            int sx = n.worldX - pwx + psx;
            int sy = n.worldY - pwy + psy;
            // centre the indicator horizontally above the NPC sprite, bobbing slightly
            int drawX = sx + (gp.tileSize - iw) / 2;
            int drawY = sy - ih - 4 + (int)(Math.sin(System.currentTimeMillis() / 300.0) * 3);
            g2.drawImage(agroIndicator, drawX, drawY);
        }
    }
}
