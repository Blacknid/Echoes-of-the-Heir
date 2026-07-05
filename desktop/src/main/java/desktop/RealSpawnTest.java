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
 * Captures what the REAL game renders at the REAL spawn — NO teleport, NO darkness override, NO scene
 * hand-picking. Just: force playState at the configured quality, let it settle, screenshot. This shows
 * exactly what the player sees, so we stop being fooled by harnesses that relocate the player to a
 * flattering open spot. Args: out.png quality(0/1/2, default HIGH).
 */
public final class RealSpawnTest {
    private static final int SETTLE = 80;

    public static void main(String[] args) {
        final String outPath = args.length > 0 ? args[0] : "build/realspawn.png";
        final int quality = args.length > 1 ? Integer.parseInt(args[1]) : main.Config.GRAPHICS_HIGH;

        Lwjgl3ApplicationConfiguration cfg = new Lwjgl3ApplicationConfiguration();
        cfg.setTitle("RealSpawnTest");
        cfg.setWindowedMode(MichiGame.BASE_W, MichiGame.BASE_H);
        cfg.useVsync(false);
        cfg.setForegroundFPS(60);

        new Lwjgl3Application(new MichiGame() {
            int frame = 0;
            boolean forced = false;

            @Override public void render() {
                if (!forced && gp != null) {
                    gp.gameState = GamePanel.playState;
                    gp.config.graphicsQuality = quality;
                    environment.Lightning.DEBUG_LIGHT_PATH = true;
                    // Spawn at the MAP's real spawn tile (not the setupGame default, which can be a wall).
                    int col = gp.mapManager.defaultSpawnCol;
                    int row = gp.mapManager.defaultSpawnRow;
                    if (col > 0 || row > 0) {
                        gp.player.worldX = col * gp.tileSize;
                        gp.player.worldY = row * gp.tileSize;
                        gp.player.snapCamera();
                    }
                    System.out.println("SPAWN col=" + col + " row=" + row
                        + " worldXY=" + gp.player.worldX + "," + gp.player.worldY);
                    forced = true;
                }
                super.render();
                frame++;
                if (frame == SETTLE) {
                    int w = Gdx.graphics.getBackBufferWidth();
                    int h = Gdx.graphics.getBackBufferHeight();
                    Pixmap raw = ScreenUtils.getFrameBufferPixmap(0, 0, w, h);
                    Pixmap pm = new Pixmap(w, h, Pixmap.Format.RGBA8888);
                    for (int y = 0; y < h; y++)
                        for (int x = 0; x < w; x++)
                            pm.drawPixel(x, y, raw.getPixel(x, h - 1 - y));
                    raw.dispose();
                    FileHandle fh = Gdx.files.local(outPath);
                    PixmapIO.writePNG(fh, pm);
                    pm.dispose();
                    System.out.println("REALSPAWN_SHOT: " + fh.file().getAbsolutePath()
                        + " playerX=" + gp.player.worldX + " playerY=" + gp.player.worldY);
                    if (renderer != null) {
                        renderer.debugDumpOccluder(outPath.replace(".png", "_occluder.png"));
                        renderer.debugDumpLightMask(outPath.replace(".png", "_mask.png"));
                    }
                    Gdx.app.exit();
                }
            }
        }, cfg);
    }
}
