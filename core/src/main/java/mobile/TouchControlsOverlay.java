package mobile;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.Touchpad;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.viewport.ScreenViewport;

import entity.Player;
import main.GamePanel;

/**
 * On-screen touch controls for Android: movement joystick (bottom-left) + action buttons
 * (bottom-right). Drives the same fields as desktop keyboard/mouse ({@link main.KeyHandler},
 * {@link main.MouseHandler}), so gameplay code needs no touch-specific changes.
 * Own {@link Stage}/{@link ScreenViewport}, drawn on top of the world camera.
 */
public class TouchControlsOverlay {

    private final GamePanel gp;
    private final Stage stage;
    private final Touchpad touchpad;

    private static final float DEADZONE = 0.3f;
    private static final float PAD_SIZE = 220f;
    private static final float BUTTON_SIZE = 110f;
    private static final float ABILITY_SIZE = 84f;

    private final List<TextButton> actionButtons = new ArrayList<>();
    private final List<AbilitySlot> abilitySlots = new ArrayList<>();

    private interface Unlocked { boolean get(); }
    private interface Fire { void go(); }
    private record AbilitySlot(TextButton button, Unlocked unlocked) {}

    // shotKeyPressed must go true-then-false within one input sample: Player.update() reads it
    // as "was the shoot key freshly pressed" (only cleared by KeyHandler.keyUp on desktop), so a
    // held-true value from a tap would otherwise fire every frame the cooldown allows.
    private boolean shotArmedThisFrame = false;

    public TouchControlsOverlay(GamePanel gp) {
        this.gp = gp;
        this.stage = new Stage(new ScreenViewport());

        Skin skin = buildMinimalSkin();
        touchpad = new Touchpad(10f, skin);
        touchpad.setSize(PAD_SIZE, PAD_SIZE);
        stage.addActor(touchpad);

        addActionButton(skin, "DASH", () -> gp.keyH.dashPressed = true);
        addActionButton(skin, "ATK", this::fireAttack);
        addActionButton(skin, "SHOT", () -> shotArmedThisFrame = true);

        addAbilityButton(skin, "SHOCK", () -> gp.player.shockwaveUnlocked, () -> gp.keyH.shockwavePressed = true);
        addAbilityButton(skin, "SNARE", () -> gp.player.voidSnareUnlocked, () -> gp.keyH.voidSnarePressed = true);
        addAbilityButton(skin, "FROST", () -> gp.player.frostNovaUnlocked, () -> gp.keyH.frostNovaPressed = true);
        addAbilityButton(skin, "OVRDR", () -> gp.player.overdriveUnlocked, () -> gp.keyH.overdrivePressed = true);

        layout();
    }

    /** Repositions widgets for the current screen size; call again on resize. */
    public void layout() {
        int w = Gdx.graphics.getWidth();
        touchpad.setPosition(40f, 40f);

        float x = w - 40f - BUTTON_SIZE;
        float y = 40f;
        for (int i = 0; i < actionButtons.size(); i++) {
            actionButtons.get(i).setBounds(x - i * (BUTTON_SIZE + 16f), y, BUTTON_SIZE, BUTTON_SIZE);
        }
        float abilityY = y + BUTTON_SIZE + 24f;
        for (int i = 0; i < abilitySlots.size(); i++) {
            abilitySlots.get(i).button().setBounds(
                w - 40f - (i + 1) * (ABILITY_SIZE + 12f), abilityY, ABILITY_SIZE, ABILITY_SIZE);
        }
    }

    public Stage getStage() {
        return stage;
    }

    /** Called once per frame from MichiGame.render(), after gp.stepUpdates() reads input. */
    public void act(float delta) {
        applyMovement();
        refreshAbilityVisibility();
        stage.act(delta);

        if (shotArmedThisFrame) {
            gp.keyH.shotKeyPressed = true;
            shotArmedThisFrame = false;
        } else {
            gp.keyH.shotKeyPressed = false;
        }
    }

    public void draw() {
        stage.draw();
    }

    public void dispose() {
        stage.dispose();
    }

    private void applyMovement() {
        float kx = touchpad.getKnobPercentX();
        float ky = touchpad.getKnobPercentY();
        gp.keyH.rightPressed = kx > DEADZONE;
        gp.keyH.leftPressed  = kx < -DEADZONE;
        // Touchpad Y is up-positive (screen-space convention); the game's yDown world uses
        // downPressed for +Y, so a downward drag (ky < 0) should mean "moving down".
        gp.keyH.upPressed    = ky > DEADZONE;
        gp.keyH.downPressed  = ky < -DEADZONE;
    }

    private void fireAttack() {
        Player p = gp.player;
        int pcx = p.screenX + gp.tileSize / 2;
        int pcy = p.screenY + gp.tileSize / 2;
        // Center the aim point exactly on the player: getAttackAngleFromMouse() falls back to
        // the player's current facing direction when dx==dy==0, so this reuses that fallback
        // instead of needing a separate aim affordance for touch.
        gp.mouseH.gameX = pcx;
        gp.mouseH.gameY = pcy;
        gp.mouseH.leftClicked = true;
    }

    private void addActionButton(Skin skin, String label, Fire onTap) {
        TextButton b = new TextButton(label, skin);
        b.addListener(new ClickListener() {
            @Override public void clicked(InputEvent event, float x, float y) { onTap.go(); }
        });
        stage.addActor(b);
        actionButtons.add(b);
    }

    private void addAbilityButton(Skin skin, String label, Unlocked unlocked, Fire onTap) {
        TextButton b = new TextButton(label, skin);
        b.addListener(new ClickListener() {
            @Override public void clicked(InputEvent event, float x, float y) {
                if (unlocked.get()) onTap.go();
            }
        });
        stage.addActor(b);
        // Hidden until unlocked (not just greyed out) so unearned abilities don't clutter the HUD.
        b.setVisible(unlocked.get());
        abilitySlots.add(new AbilitySlot(b, unlocked));
    }

    /** Re-checks unlock state each frame — abilities can be unlocked mid-session via the skill tree. */
    private void refreshAbilityVisibility() {
        for (AbilitySlot slot : abilitySlots) {
            slot.button().setVisible(slot.unlocked().get());
        }
    }

    /** Builds a tiny solid-color Skin so this overlay has no external .atlas/.json dependency. */
    private static Skin buildMinimalSkin() {
        Skin skin = new Skin();
        Pixmap pixmap = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pixmap.setColor(1f, 1f, 1f, 1f);
        pixmap.fill();
        skin.add("white", new Texture(pixmap));
        pixmap.dispose();

        Touchpad.TouchpadStyle padStyle = new Touchpad.TouchpadStyle();
        padStyle.background = tint(skin, new Color(0.3f, 0.3f, 0.3f, 0.35f));
        padStyle.knob = tint(skin, new Color(0.9f, 0.9f, 0.9f, 0.9f));
        skin.add("default", padStyle);

        TextButton.TextButtonStyle btnStyle = new TextButton.TextButtonStyle();
        btnStyle.up = tint(skin, new Color(0.25f, 0.25f, 0.3f, 0.55f));
        btnStyle.down = tint(skin, new Color(0.45f, 0.45f, 0.55f, 0.7f));
        btnStyle.font = new BitmapFont();
        skin.add("default", btnStyle);

        return skin;
    }

    private static Drawable tint(Skin skin, Color c) {
        TextureRegion region = new TextureRegion(skin.get("white", Texture.class));
        return new TextureRegionDrawable(region).tint(c);
    }
}
