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
 * MIRROR TEST for the "light pools leave their source and move with the player" bug.
 *
 * Hypothesis: the light mask is composited VERTICALLY MIRRORED about the screen center on some
 * tier. The player's own light sits at screen center (dead-zone camera), so mirror(center)=center
 * and every previous "pool is centred on the player" verification PASSED while every off-center
 * light rendered in the wrong place, and slid vertically while walking (mirrored camera motion).
 *
 * Method: register one static test light 250px ABOVE the player (world coords) and one 250px to the
 * RIGHT, then screenshot. If the top light's pool renders BELOW the player, the mask is y-mirrored.
 * The right light checks x (should be unaffected). Run for both HIGH (scene capture path) and MED
 * (direct-to-screen path):
 *   ./gradlew :desktop:run -PmainClass=desktop.OffCenterLightTest --args="build/mirror 2"
 */
public final class OffCenterLightTest {

    public static void main(String[] args) {
        final String outBase = args.length > 0 ? args[0] : "build/mirror";
        final int quality = args.length > 1 ? Integer.parseInt(args[1]) : main.Config.GRAPHICS_HIGH;

        Lwjgl3ApplicationConfiguration cfg = new Lwjgl3ApplicationConfiguration();
        cfg.setTitle("OffCenterLightTest");
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
                    gp.eManager.setPlayerLightRadius(4);
                    forced = true;
                }
                if (gp != null && gp.eManager != null && gp.eManager.lightning != null) {
                    gp.eManager.pinnedFilterAlpha = 0.9f;
                    // Probe light as a TORCH ENTITY 250px ABOVE the player (registered addLight()
                    // probes get wiped by clearLights() during update, entity lights don't).
                    // Its pool must render exactly on the torch; if it renders BELOW the player
                    // instead, the light mask is vertically mirrored.
                    if (gp.obj[gp.obj.length - 1] == null) {
                        entity.Entity l = new entity.Entity(gp);
                        l.name = "MirrorProbe"; l.type = entity.Entity.TYPE_UTILITY;
                        l.lightSource = true; l.lightRadius = 3; l.collision = false;
                        gp.obj[gp.obj.length - 1] = l;
                    }
                    entity.Entity torch = gp.obj[gp.obj.length - 1];
                    torch.worldX = gp.player.worldX;
                    torch.worldY = gp.player.worldY - 250;
                }

                super.render();
                frame++;

                if (forced && frame == 90) {
                    int w = Gdx.graphics.getBackBufferWidth();
                    int h = Gdx.graphics.getBackBufferHeight();
                    Pixmap raw = ScreenUtils.getFrameBufferPixmap(0, 0, w, h);
                    Pixmap pm = new Pixmap(w, h, Pixmap.Format.RGBA8888);
                    for (int y = 0; y < h; y++)
                        for (int x = 0; x < w; x++)
                            pm.drawPixel(x, y, raw.getPixel(x, h - 1 - y));
                    raw.dispose();
                    FileHandle fh = Gdx.files.local(outBase + "_q" + quality + ".png");
                    PixmapIO.writePNG(fh, pm);
                    pm.dispose();
                    System.out.println("MIRROR_SHOT: " + fh.file().getAbsolutePath()
                        + " playerScreen=(" + gp.player.screenX + "," + gp.player.screenY + ")"
                        + " GREEN light should be 250px ABOVE player, BLUE 250px RIGHT");
                    Gdx.app.exit();
                }
            }
        }, cfg);
    }
}
