package tile;

import gfx.GdxRenderer;
import gfx.Sprite;
import main.GamePanel;
import util.ResourceCache;

/**
 * Portcullis-style gate, sliced from a single 5-frame strip (Gate.png): frame 0 is fully closed
 * (grate down, solid), frame 4 fully open (grate raised). Opened/closed remotely by an IT_Lever
 * sharing the same "gateId". Placed from a Tiled rectangle object (native art is 64x96) so its
 * drawn position/size always matches whatever rectangle was drawn in Tiled, instead of snapping
 * to a single tile cell like the other 1-tile interactive tiles.
 */
public class IT_Gate extends interactiveTile {

    private static final String SHEET_PATH = "/res/tiles/DUNGEON/Gate.png";
    private static final int FRAME_COUNT = 5;
    private static final int TICKS_PER_FRAME = 6;
    private static final int NATIVE_W = 64;
    private static final int NATIVE_H = 96;

    // The stone archway border is decorative and always solid; only this inset region (the arch
    // opening the portcullis grate rises out of, measured from the native 64x96 art) is where the
    // player actually walks through, so the hitbox only blocks that interior, not the whole rect.
    private static final int INTERIOR_X = 14;
    private static final int INTERIOR_Y = 34;
    private static final int INTERIOR_W = 37;
    private static final int INTERIOR_H = NATIVE_H - INTERIOR_Y;

    private static Sprite[] frames;
    private static boolean loadAttempted = false;

    public final String gateId;
    private final int drawW, drawH;

    private int frameIndex = 0;
    private int frameTicks = 0;
    private boolean opening = false;
    private boolean closing = false;

    public IT_Gate(GamePanel gp, int worldX, int worldY, int drawW, int drawH, String gateId) {
        super(gp, worldX / gp.tileSize, worldY / gp.tileSize);
        this.worldX = worldX;
        this.worldY = worldY;
        this.drawW = drawW;
        this.drawH = drawH;
        this.gateId = gateId;

        solidArea.x      = INTERIOR_X * drawW / NATIVE_W;
        solidArea.y      = INTERIOR_Y * drawH / NATIVE_H;
        solidArea.width  = INTERIOR_W * drawW / NATIVE_W;
        solidArea.height = INTERIOR_H * drawH / NATIVE_H;
        solidAreaDefaultX = solidArea.x;
        solidAreaDefaultY = solidArea.y;

        loadFramesIfNeeded();
        collision = true;
    }

    private static void loadFramesIfNeeded() {
        if (loadAttempted) return;
        loadAttempted = true;
        Sprite sheet = ResourceCache.loadImageIfPresent(SHEET_PATH);
        if (sheet == null) return;
        int frameW = sheet.getWidth() / FRAME_COUNT;
        frames = new Sprite[FRAME_COUNT];
        for (int i = 0; i < FRAME_COUNT; i++) {
            frames[i] = sheet.getSubimage(i * frameW, 0, frameW, sheet.getHeight());
        }
    }

    public void open() {
        if (opening || frameIndex == FRAME_COUNT - 1) return;
        opening = true;
        closing = false;
        frameTicks = 0;
    }

    public void close() {
        if (closing || frameIndex == 0) return;
        closing = true;
        opening = false;
        frameTicks = 0;
    }

    @Override
    public void update() {
        super.update();
        if (opening) {
            frameTicks++;
            if (frameTicks >= TICKS_PER_FRAME) {
                frameTicks = 0;
                frameIndex++;
                if (frameIndex >= FRAME_COUNT - 1) {
                    frameIndex = FRAME_COUNT - 1;
                    opening = false;
                    collision = false;
                }
            }
        } else if (closing) {
            // Stays solid through the whole closing swing, not just once fully shut, so the
            // player can't walk through a gate that's still lowering.
            collision = true;
            frameTicks++;
            if (frameTicks >= TICKS_PER_FRAME) {
                frameTicks = 0;
                frameIndex--;
                if (frameIndex <= 0) {
                    frameIndex = 0;
                    closing = false;
                }
            }
        }
    }

    @Override
    public void draw(GdxRenderer g2) {
        if (frames == null || offscreen()) return;
        Sprite sprite = frames[frameIndex];
        if (sprite == null) return;
        g2.drawImage(sprite, screenX(), screenY(), drawW, drawH);
    }

    private int screenX() { return worldX - gp.player.worldX + gp.player.screenX; }
    private int screenY() { return worldY - gp.player.worldY + gp.player.screenY; }

    private boolean offscreen() {
        return worldX + drawW <= gp.player.worldX - gp.player.screenX ||
               worldX >= gp.player.worldX + (gp.screenWidth - gp.player.screenX) ||
               worldY + drawH <= gp.player.worldY - gp.player.screenY ||
               worldY >= gp.player.worldY + (gp.screenHeight - gp.player.screenY);
    }
}
