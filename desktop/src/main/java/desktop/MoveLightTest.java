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
 * Dev harness: boots the real game into playState on HIGH, then TELEPORTS the player by a few tiles
 * every N frames, screenshotting after each move. If the shader lighting is camera-locked correctly,
 * the player's own light pool should stay centered on the player (screen-fixed) in every shot, and a
 * fixed torch/occluder should slide by the SAME amount the world scrolls. If lights "dance", the pool
 * will drift off the player between shots — captured here for diff.
 *
 * Run: ./gradlew :desktop:run -PmainClass=desktop.MoveLightTest
 * Output: build/move_0.png, build/move_1.png, build/move_2.png
 */
public final class MoveLightTest {
    public static void main(String[] args) {
        final int quality = args.length > 0 ? Integer.parseInt(args[0]) : main.Config.GRAPHICS_HIGH;

        Lwjgl3ApplicationConfiguration cfg = new Lwjgl3ApplicationConfiguration();
        cfg.setTitle("MoveLightTest");
        cfg.setWindowedMode(MichiGame.BASE_W, MichiGame.BASE_H);
        cfg.useVsync(false);
        cfg.setForegroundFPS(60);

        new Lwjgl3Application(new MichiGame() {
            int frame = 0, shot = 0;
            boolean forced = false;
            int baseX, baseY;

            @Override public void render() {
                if (!forced && gp != null) {
                    gp.gameState = GamePanel.playState;
                    gp.config.graphicsQuality = quality;
                    baseX = gp.player.worldX;
                    baseY = gp.player.worldY;
                    // Turn the first NPC into an offset torch so there's a fixed light + occluder to track.
                    if (gp.npc != null) for (entity.Entity npc : gp.npc) if (npc != null) {
                        npc.lightSource = true; npc.lightRadius = 6;
                        gp.player.worldX = npc.worldX - gp.tileSize * 3;
                        gp.player.worldY = npc.worldY;
                        baseX = gp.player.worldX; baseY = gp.player.worldY;
                        break;
                    }
                    forced = true;
                }
                if (gp != null && gp.eManager != null) gp.eManager.pinnedFilterAlpha = 0.88f;

                // Every 40 frames, nudge the player one tile right and screenshot.
                if (forced && frame > 20 && frame % 40 == 0 && shot < 3) {
                    gp.player.worldX = baseX + shot * gp.tileSize;
                    gp.player.snapCamera();
                }
                super.render();
                frame++;

                if (forced && frame > 20 && (frame % 40) == 5 && shot < 3) {
                    int w = Gdx.graphics.getBackBufferWidth();
                    int h = Gdx.graphics.getBackBufferHeight();
                    Pixmap raw = ScreenUtils.getFrameBufferPixmap(0, 0, w, h);
                    Pixmap pm = new Pixmap(w, h, Pixmap.Format.RGBA8888);
                    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++)
                        pm.drawPixel(x, y, raw.getPixel(x, h - 1 - y));
                    raw.dispose();
                    FileHandle fh = Gdx.files.local("build/move_" + shot + ".png");
                    PixmapIO.writePNG(fh, pm); pm.dispose();
                    System.out.println("MOVE_SHOT_WRITTEN: " + fh.file().getAbsolutePath()
                        + " playerX=" + gp.player.worldX + " screenX=" + gp.player.screenX);
                    shot++;
                    if (shot == 3) Gdx.app.exit();
                }
            }
        }, cfg);
    }
}
