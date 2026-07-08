package tile;

import entity.Entity;
import gfx.GdxRenderer;
import gfx.Sprite;
import main.GamePanel;
import util.ResourceCache;

/**
 * Wall lever, sliced from a single 5-frame strip (Lever.png): frame 0 is the resting/off pose,
 * frame 4 fully pulled/on. Hitting it with an attack toggles it and opens/closes every IT_Gate
 * sharing its "targetId".
 */
public class IT_Lever extends interactiveTile {

    private static final String SHEET_PATH = "/res/tiles/DUNGEON/Lever.png";
    private static final int FRAME_COUNT = 5;
    private static final int TICKS_PER_FRAME = 4;
    private static final int RETRIGGER_COOLDOWN = 20;

    private static Sprite[] frames;
    private static boolean loadAttempted = false;

    private final String targetId;

    private int frameIndex = 0;
    private int frameTicks = 0;
    private boolean pulling = false;
    private boolean on = false;
    private int cooldown = 0;

    public IT_Lever(GamePanel gp, int col, int row, String targetId) {
        super(gp, col, row);
        this.targetId = targetId;
        loadFramesIfNeeded();
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

    @Override
    public void update() {
        super.update();
        if (cooldown > 0) cooldown--;

        if (pulling) {
            frameTicks++;
            if (frameTicks >= TICKS_PER_FRAME) {
                frameTicks = 0;
                if (on) {
                    frameIndex++;
                    if (frameIndex >= FRAME_COUNT - 1) { frameIndex = FRAME_COUNT - 1; pulling = false; }
                } else {
                    frameIndex--;
                    if (frameIndex <= 0) { frameIndex = 0; pulling = false; }
                }
            }
        }
    }

    @Override
    public void onAttackHit(Entity player) {
        if (cooldown > 0 || pulling) return;
        on = !on;
        pulling = true;
        frameTicks = 0;
        cooldown = RETRIGGER_COOLDOWN;

        for (int i = 0; i < gp.iTile.length; i++) {
            if (gp.iTile[i] instanceof IT_Gate gate && gate.gateId.equals(targetId)) {
                if (on) gate.open(); else gate.close();
            }
        }
    }

    @Override
    public void draw(GdxRenderer g2) {
        if (frames == null || offscreen()) return;
        Sprite sprite = frames[frameIndex];
        if (sprite == null) return;
        g2.drawImage(sprite, screenX(), screenY(), gp.tileSize, gp.tileSize);
    }

    private int screenX() { return worldX - gp.getCamWorldX() + gp.player.screenX; }
    private int screenY() { return worldY - gp.getCamWorldY() + gp.player.screenY; }

    private boolean offscreen() {
        return worldX + gp.tileSize <= gp.getCamWorldX() - gp.player.screenX ||
               worldX - gp.tileSize >= gp.getCamWorldX() + (gp.screenWidth - gp.player.screenX) ||
               worldY + gp.tileSize <= gp.getCamWorldY() - gp.player.screenY ||
               worldY - gp.tileSize >= gp.getCamWorldY() + (gp.screenHeight - gp.player.screenY);
    }
}
