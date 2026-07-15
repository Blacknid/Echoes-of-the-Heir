package main;

/**
 * Global switch: is this JVM running the game with no window and no GPU?
 *
 * <p>The authoritative game server runs the <em>same</em> simulation classes as the client —
 * {@code Player}, {@code Entity}, monster AI, {@code CollisionChecker}, {@code PathFinder},
 * {@code QuestManager}. That is the whole point: one implementation of the rules, so the server
 * and the client can never disagree about them, and so nothing has to be ported or kept in sync.
 *
 * <p>What the server does <em>not</em> have is an OpenGL context. Almost none of the game logic
 * cares — it never reads a pixel; the only thing it asks of a sprite is its width and height, so
 * it can slice a sheet into frames. So instead of splitting 45k lines of source in half, the
 * renderless build keeps every class and switches off the handful of places that actually touch
 * the GPU:
 *
 * <ul>
 *   <li>{@code ResourceCache.textureFrom} — the ONLY place a {@code Texture} is created from an
 *       asset. Headless, it reads the PNG header and returns a dimension-only
 *       {@link gfx.Sprite#headless} instead.</li>
 *   <li>{@code IT_Pot} / {@code Projectile.rotateImage} — procedural image generation, which the
 *       simulation never looks at. Headless, they return correctly-sized empty sprites.</li>
 *   <li>{@code GamePanel} — skips constructing the renderer, UI fonts, shaders and environment
 *       layers, none of which the simulation consults.</li>
 * </ul>
 *
 * <p>Set this <b>before</b> constructing {@link GamePanel} — its field initializers run at
 * construction time and consult the flag. {@link HeadlessGame} does that for you.
 */
public final class Headless {

    private static volatile boolean enabled = false;

    private Headless() {}

    /** Turn on renderless mode. Must be called before {@code new GamePanel()}. */
    public static void enable() {
        enabled = true;
        util.ResourceCache.setHeadless(true);
    }

    /** True when this JVM has no GPU/window — i.e. it is the authoritative server. */
    public static boolean isEnabled() { return enabled; }
}
