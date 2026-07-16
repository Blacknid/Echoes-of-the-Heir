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
 * Dev harness: boots the real game, forces it straight into playState (the initial map is already
 * loaded by setupGame), lets the scene settle for a fixed number of frames so lighting/shadows are
 * active, grabs the framebuffer, and writes a PNG, then exits. Lets us actually LOOK at the GLSL
 * lighting + sprite shadows without clicking through the title screen.
 *
 * Run: {@code ./gradlew :desktop:run -PmainClass=desktop.LightingScreenshotTest}
 * Output: {@code build/lighting_shot.png}
 */
public final class LightingScreenshotTest {

    private static final int SETTLE_FRAMES = 90; // ~1.5s at 60fps: fade-ins, light tick, etc.

    public static void main(String[] args) {
        final String outPath = args.length > 0 ? args[0] : "build/lighting_shot.png";
        // Optional 2nd arg: quality tier (0=LOW/legacy, 1=MEDIUM, 2=HIGH). Default HIGH.
        final int quality = args.length > 1 ? Integer.parseInt(args[1]) : main.Config.GRAPHICS_HIGH;

        Lwjgl3ApplicationConfiguration cfg = new Lwjgl3ApplicationConfiguration();
        cfg.setTitle("LightingScreenshotTest");
        cfg.setWindowedMode(MichiGame.BASE_W, MichiGame.BASE_H);
        cfg.useVsync(false);
        cfg.setForegroundFPS(60);

        new Lwjgl3Application(new MichiGame() {
            int frame = 0;
            boolean forced = false;

            @Override public void render() {
                // Force gameplay once gp exists so the world (not the title) is what we screenshot.
                if (!forced && gp != null) {
                    gp.gameState = GamePanel.playState;
                    gp.config.graphicsQuality = quality;
                    // Shadow showcase: find an OPEN spot (non-solid tile with open space around it) that
                    // has a solid pillar/wall a few tiles to one side, so the player's light casts a clean
                    // directional shadow off distinct scenery instead of being buried in a rock alcove.
                    boolean[][] solid = gp.tileM.tileSolid;
                    if (solid != null) {
                        int ts = gp.tileSize;
                        outer:
                        for (int col = 4; col < solid.length - 4; col++) {
                            for (int row = 4; row < solid[col].length - 4; row++) {
                                if (solid[col][row]) continue;
                                // require a 3-tile open bubble around the player
                                boolean open = true;
                                for (int dc = -3; dc <= 3 && open; dc++)
                                    for (int dr = -3; dr <= 3 && open; dr++)
                                        if (solid[col + dc][row + dr]) open = false;
                                if (!open) continue;
                                // require a solid pillar 4-6 tiles to the right (a caster to shadow)
                                boolean caster = solid[col + 4][row] || solid[col + 5][row] || solid[col + 6][row];
                                if (!caster) continue;
                                gp.player.worldX = col * ts;
                                gp.player.worldY = row * ts;
                                break outer;
                            }
                        }
                    }
                    gp.eManager.setPlayerLightRadius(7);
                    forced = true;
                }
                // Pin a strong night darkness (survives the env update, unlike filterAlpha) so the
                // lighting + cast shadows + bloom are dramatic for the screenshot (this map is near
                // fully-lit, ambientLight=0.92).
                if (gp != null && gp.eManager != null) {
                    gp.eManager.pinnedFilterAlpha = 0.88f;
                }
                // CAMERA-DRIFT TEST (bug #2): after settling, slide the player right a few px/frame so the
                // camera scrolls. A correctly-anchored cast shadow stays locked to its wall; a mis-flipped
                // mask would make the shadow slide at ~2x. We grab a 2nd frame mid-drift to compare.
                if (forced && frame > SETTLE_FRAMES && frame < SETTLE_FRAMES + 40) {
                    gp.player.worldX += 4;
                }
                super.render();
                frame++;
                if (frame == SETTLE_FRAMES || frame == SETTLE_FRAMES + 30) {
                    String path = (frame == SETTLE_FRAMES) ? outPath : outPath.replace(".png", "_drift.png");
                    int w = Gdx.graphics.getBackBufferWidth();
                    int h = Gdx.graphics.getBackBufferHeight();
                    Pixmap raw = ScreenUtils.getFrameBufferPixmap(0, 0, w, h);
                    // The GL framebuffer origin is bottom-left, so a raw grab is upside-down. Flip it
                    // vertically into a new pixmap so the saved PNG matches what's on screen.
                    Pixmap pm = new Pixmap(w, h, Pixmap.Format.RGBA8888);
                    for (int y = 0; y < h; y++) {
                        for (int x = 0; x < w; x++) {
                            pm.drawPixel(x, y, raw.getPixel(x, h - 1 - y));
                        }
                    }
                    raw.dispose();
                    FileHandle fh = Gdx.files.local(path);
                    PixmapIO.writePNG(fh, pm);
                    pm.dispose();
                    System.out.println("LIGHTING_SHOT_WRITTEN: " + fh.file().getAbsolutePath());
                    // Also dump the occluder silhouette mask + darkness mask for diagnosis.
                    try {
                        gfx.GdxRenderer r = renderer;
                        if (r != null) {
                            r.debugDumpOccluder(path.replace(".png", "_occluder.png"));
                            r.debugDumpLightMask(path.replace(".png", "_mask.png"));
                            System.out.println("DIAG_MASKS_WRITTEN");
                        }
                    } catch (Throwable t) { System.out.println("DIAG_DUMP_FAIL: " + t); }
                    if (frame == SETTLE_FRAMES + 30) Gdx.app.exit();
                }
            }
        }, cfg);
    }
}
