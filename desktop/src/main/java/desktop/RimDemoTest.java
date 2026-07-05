package desktop;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.files.FileHandle;

import gfx.shader.ShaderPipeline;

/**
 * Isolated rim-light + shadow verification: draws ONE test sprite (a solid disc with a soft alpha edge)
 * on a dark background, lit by a single OFFSET light, then runs the light mask + rim pass exactly like the
 * game and screenshots the result. No game, no map — a clean rig to confirm the rim shape/warmth/strength
 * and directional shadow are correct, decoupled from the game's camera framing.
 *
 * Run: {@code ./gradlew :desktop:run -PmainClass=desktop.RimDemoTest}
 * Output: {@code build/rim_demo.png}
 */
public final class RimDemoTest {
    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration cfg = new Lwjgl3ApplicationConfiguration();
        cfg.setTitle("RimDemoTest");
        cfg.setWindowedMode(640, 480);
        cfg.useVsync(false);

        new Lwjgl3Application(new com.badlogic.gdx.ApplicationAdapter() {
            ShaderPipeline pipe;
            Texture spriteTex;      // a test "sprite" with a soft circular alpha
            com.badlogic.gdx.graphics.g2d.SpriteBatch batch;
            com.badlogic.gdx.graphics.glutils.FrameBuffer sceneFbo, occFbo, lightFbo;
            int W = 640, H = 480, frame = 0;

            @Override public void create() {
                pipe = new ShaderPipeline();
                batch = new com.badlogic.gdx.graphics.g2d.SpriteBatch();
                // Build a test sprite: a filled disc, red-ish, soft alpha at the rim.
                int S = 128; Pixmap pm = new Pixmap(S, S, Pixmap.Format.RGBA8888);
                pm.setBlending(Pixmap.Blending.None);
                for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
                    float dx = (x - S / 2f), dy = (y - S / 2f);
                    float d = (float) Math.sqrt(dx * dx + dy * dy) / (S / 2f);
                    float a = d < 0.9f ? 1f : (d < 1f ? (1f - d) / 0.1f : 0f);
                    pm.setColor(0.75f, 0.22f, 0.20f, a); // a red cloak-ish blob
                    pm.drawPixel(x, y);
                }
                spriteTex = new Texture(pm); pm.dispose();
                sceneFbo = new com.badlogic.gdx.graphics.glutils.FrameBuffer(Pixmap.Format.RGBA8888, W, H, false);
                occFbo   = new com.badlogic.gdx.graphics.glutils.FrameBuffer(Pixmap.Format.RGBA8888, W, H, false);
                lightFbo = new com.badlogic.gdx.graphics.glutils.FrameBuffer(Pixmap.Format.RGBA8888, W, H, false);
            }

            @Override public void render() {
                frame++;
                float spriteX = 260, spriteY = 176, spriteW = 128, spriteH = 128;
                // Light offset to the sprite's upper-left so the rim should appear on the sprite's
                // upper-left contour and the shadow fall to the lower-right.
                float lightX = 200, lightY = 140, lightR = 320;

                // 1) occluder mask: sprite silhouette in black on transparent.
                occFbo.begin();
                Gdx.gl.glClearColor(0, 0, 0, 0); Gdx.gl.glClear(com.badlogic.gdx.graphics.GL20.GL_COLOR_BUFFER_BIT);
                batch.getProjectionMatrix().setToOrtho2D(0, 0, W, H); batch.begin();
                batch.setColor(0, 0, 0, 1);
                batch.draw(spriteTex, spriteX, H - spriteY - spriteH, spriteW, spriteH);
                batch.setColor(1, 1, 1, 1); batch.end();
                occFbo.end();

                // 2) scene: dark bg + the sprite at natural color.
                sceneFbo.begin();
                Gdx.gl.glClearColor(0.03f, 0.03f, 0.06f, 1f); Gdx.gl.glClear(com.badlogic.gdx.graphics.GL20.GL_COLOR_BUFFER_BIT);
                batch.begin();
                batch.draw(spriteTex, spriteX, H - spriteY - spriteH, spriteW, spriteH);
                batch.end();
                sceneFbo.end();

                // 3) light mask into lightFbo (one offset light), with shadows on.
                float[] lx={lightX}, ly={lightY}, lr={lightR}, cr={1f}, cg={0.86f}, cb={0.62f}, li={0.95f};
                float[] lwx={lightX}, lwy={lightY}; // static demo: world == screen (no camera scroll)
                lightFbo.begin();
                Gdx.gl.glClearColor(0,0,0,0); Gdx.gl.glClear(com.badlogic.gdx.graphics.GL20.GL_COLOR_BUFFER_BIT);
                pipe.renderLightMask(W, H, 0.02f,0.03f,0.07f, 0.92f, 1, lx,ly,lwx,lwy,lr,cr,cg,cb,li,
                        occFbo.getColorBufferTexture(), true, false);
                lightFbo.end();

                // 4) composite to screen: scene, then darkness mask over it, then rim, then bloom.
                Gdx.gl.glViewport(0,0,W,H);
                Gdx.gl.glClearColor(0,0,0,1); Gdx.gl.glClear(com.badlogic.gdx.graphics.GL20.GL_COLOR_BUFFER_BIT);
                batch.begin();
                batch.setColor(1,1,1,1);
                TextureRegion sc = new TextureRegion(sceneFbo.getColorBufferTexture()); sc.flip(false, true);
                batch.draw(sc, 0, 0, W, H);
                TextureRegion lm = new TextureRegion(lightFbo.getColorBufferTexture()); lm.flip(false, true);
                batch.draw(lm, 0, 0, W, H);
                batch.end();
                pipe.renderRim(sceneFbo.getColorBufferTexture(), occFbo.getColorBufferTexture(), 1.4f);
                pipe.renderBloom(sceneFbo.getColorBufferTexture(), W, H, 0.6f, 0.7f);

                if (frame == 8) {
                    Pixmap raw = com.badlogic.gdx.utils.ScreenUtils.getFrameBufferPixmap(0,0,W,H);
                    Pixmap out = new Pixmap(W, H, Pixmap.Format.RGBA8888);
                    for (int y=0;y<H;y++) for (int x=0;x<W;x++) out.drawPixel(x,y, raw.getPixel(x,H-1-y));
                    raw.dispose();
                    FileHandle fh = Gdx.files.local("build/rim_demo.png");
                    PixmapIO.writePNG(fh, out); out.dispose();
                    System.out.println("RIM_DEMO_WRITTEN: " + fh.file().getAbsolutePath());
                    Gdx.app.exit();
                }
            }
        }, cfg);
    }
}
