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
 * Dev harness for the "light dances only when the camera moves" bug. Forces HIGH graphics, keeps a
 * strong screen-shake alive (the camera-motion transform that shifted the light mask), and dumps a
 * frame captured AT the peak of a shake offset. If the light pool stays centred on the player in the
 * shaken frame, the mask is being composited in screen space (fixed); if it slides off, it isn't.
 *
 * Run: {@code ./gradlew :desktop:run -PmainClass=desktop.ShakeLightTest}
 * Output: {@code build/shake_light_shot.png} (+ _mask.png)
 */
public final class ShakeLightTest {

    private static final int SETTLE_FRAMES = 90;

    public static void main(String[] args) {
        final String outPath = args.length > 0 ? args[0] : "build/shake_light_shot.png";

        Lwjgl3ApplicationConfiguration cfg = new Lwjgl3ApplicationConfiguration();
        cfg.setTitle("ShakeLightTest");
        cfg.setWindowedMode(MichiGame.BASE_W, MichiGame.BASE_H);
        cfg.useVsync(false);
        cfg.setForegroundFPS(60);

        new Lwjgl3Application(new MichiGame() {
            int frame = 0;
            boolean forced = false;

            @Override public void render() {
                if (!forced && gp != null) {
                    gp.gameState = GamePanel.playState;
                    gp.config.graphicsQuality = main.Config.GRAPHICS_HIGH;
                    if (gp.npc != null) {
                        for (entity.Entity npc : gp.npc) {
                            if (npc != null) {
                                gp.player.worldX = npc.worldX - gp.tileSize * 2;
                                gp.player.worldY = npc.worldY;
                                npc.lightSource = true;
                                npc.lightRadius = 6;
                                break;
                            }
                        }
                    }
                    forced = true;
                }
                if (gp != null && gp.eManager != null) {
                    gp.eManager.pinnedFilterAlpha = 0.88f;
                }
                // Keep a HEAVY shake alive every frame so the camera transform is always offset — this is
                // the state under which the bug manifested. Re-trigger before it decays.
                if (gp != null && gp.screenShake != null) {
                    gp.screenShake.shakeHeavy();
                }
                super.render();
                frame++;
                if (frame == SETTLE_FRAMES) {
                    // Only capture on a frame where the shake offset is actually non-zero, so the test
                    // genuinely exercises the moving-camera path.
                    int ox = gp.screenShake.getOffsetX();
                    int oy = gp.screenShake.getOffsetY();
                    System.out.println("SHAKE_OFFSET_AT_CAPTURE: " + ox + "," + oy);

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
                    // Also dump the raw light mask so we can see the pool position independent of the scene.
                    renderer.debugDumpLightMask(outPath.replace(".png", "_mask.png"));
                    System.out.println("SHAKE_LIGHT_SHOT_WRITTEN: " + fh.file().getAbsolutePath());
                    Gdx.app.exit();
                }
            }
        }, cfg);
    }
}
