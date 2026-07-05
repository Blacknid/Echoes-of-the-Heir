package desktop;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.files.FileHandle;

import main.GamePanel;
import main.MichiGame;

/**
 * Reproduces the REAL bug: drives actual input-driven movement (keyH.rightPressed) so the player walks
 * through the collision/camera path exactly like a human, and captures a strip of frames + the occluder
 * and light masks at each, at a chosen quality tier. Lets us SEE whether the light pool / cast shadow
 * slides relative to the scene as the player moves (the "lights/shadows move with me" report), instead
 * of the earlier harness that teleported worldX (which bypasses the real path).
 *
 * Run: ./gradlew :desktop:run -PmainClass=desktop.WalkLightTest --args="build/walk 2"
 * Writes build/walk_f0.png .. build/walk_f3.png (+ _occluder/_mask for the last).
 */
public final class WalkLightTest {

    private static final int SETTLE = 70;

    public static void main(String[] args) {
        final String outBase = args.length > 0 ? args[0] : "build/walk";
        final int quality = args.length > 1 ? Integer.parseInt(args[1]) : main.Config.GRAPHICS_HIGH;

        Lwjgl3ApplicationConfiguration cfg = new Lwjgl3ApplicationConfiguration();
        cfg.setTitle("WalkLightTest");
        cfg.setWindowedMode(MichiGame.BASE_W, MichiGame.BASE_H);
        cfg.useVsync(false);
        cfg.setForegroundFPS(60);

        new Lwjgl3Application(new MichiGame() {
            int frame = 0;
            boolean forced = false;
            int shot = 0;

            @Override public void render() {
                if (!forced && gp != null) {
                    gp.gameState = GamePanel.playState;
                    gp.config.graphicsQuality = quality;
                    // Place the player just LEFT of a solid wall column, on open ground, so walking toward
                    // it should show a stationary cast shadow anchored to the wall.
                    boolean[][] solid = gp.tileM.tileSolid;
                    if (solid != null) {
                        int ts = gp.tileSize;
                        outer:
                        for (int col = 6; col < solid.length - 6; col++) {
                            for (int row = 6; row < solid[col].length - 6; row++) {
                                if (solid[col][row]) continue;
                                boolean open = true;
                                for (int dc = -2; dc <= 2 && open; dc++)
                                    for (int dr = -2; dr <= 2 && open; dr++)
                                        if (solid[col + dc][row + dr]) open = false;
                                if (!open) continue;
                                boolean wallRight = solid[col + 3][row] || solid[col + 4][row];
                                if (!wallRight) continue;
                                gp.player.worldX = col * ts;
                                gp.player.worldY = row * ts;
                                break outer;
                            }
                        }
                    }
                    gp.eManager.setPlayerLightRadius(6);
                    environment.Lightning.DEBUG_LIGHT_PATH = true;
                    System.out.println("BLOOM_AVAILABLE=" + renderer.bloomAvailable()
                        + " quality=" + gp.config.graphicsQuality);
                    forced = true;
                }
                if (gp != null && gp.eManager != null) gp.eManager.pinnedFilterAlpha = 0.9f;

                // After settling, walk RIGHT (real input) for a while, grabbing frames as we go.
                if (forced && frame >= SETTLE) {
                    gp.keyH.rightPressed = true;
                }

                super.render();
                frame++;

                if (forced && frame >= SETTLE && (frame - SETTLE) % 8 == 0 && shot < 4) {
                    grab(outBase + "_f" + shot + ".png", shot == 3);
                    shot++;
                }
            }

            void grab(String path, boolean dumpMasks) {
                int w = Gdx.graphics.getBackBufferWidth();
                int h = Gdx.graphics.getBackBufferHeight();
                Pixmap raw = ScreenUtils.getFrameBufferPixmap(0, 0, w, h);
                Pixmap pm = new Pixmap(w, h, Pixmap.Format.RGBA8888);
                for (int y = 0; y < h; y++)
                    for (int x = 0; x < w; x++)
                        pm.drawPixel(x, y, raw.getPixel(x, h - 1 - y));
                raw.dispose();
                FileHandle fh = Gdx.files.local(path);
                PixmapIO.writePNG(fh, pm);
                pm.dispose();
                System.out.println("WALK_SHOT: " + fh.file().getAbsolutePath()
                    + " playerX=" + gp.player.worldX);
                if (dumpMasks && renderer != null) {
                    renderer.debugDumpOccluder(path.replace(".png", "_occluder.png"));
                    renderer.debugDumpLightMask(path.replace(".png", "_mask.png"));
                    renderer.debugDumpScene(path.replace(".png", "_scene.png"));
                    System.out.println("WALK_MASKS_DUMPED");
                    Gdx.app.exit();
                }
            }
        }, cfg);
    }
}
