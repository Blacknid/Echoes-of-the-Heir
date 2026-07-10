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
import com.badlogic.gdx.utils.TimeUtils;
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
    private static final long ATTACK_HOLD_THRESHOLD_MS = 180L;
    private static final long INVENTORY_HOLD_THRESHOLD_MS = 180L;

    private final List<TextButton> actionButtons = new ArrayList<>();
    private final List<AbilitySlot> abilitySlots = new ArrayList<>();

    private final TextButton dashButton;
    private final TextButton attackButton;
    private final TextButton shotButton;
    private final TextButton pauseButton;
    private final TextButton inventoryButton;

    private interface Unlocked { boolean get(); }
    private interface Fire { void go(); }
    private record AbilitySlot(TextButton button, Unlocked unlocked) {}

    // shotKeyPressed must go true-then-false within one input sample: Player.update() reads it
    // as "was the shoot key freshly pressed" (only cleared by KeyHandler.keyUp on desktop), so a
    // held-true value from a tap would otherwise fire every frame the cooldown allows.
    private boolean shotArmedThisFrame = false;
    private boolean attackButtonHeld = false;
    private long attackButtonPressStartedAt = 0L;
    private long inventoryButtonPressStartedAt = 0L;

    public TouchControlsOverlay(GamePanel gp) {
        this.gp = gp;
        this.stage = new Stage(new ScreenViewport());

        Skin skin = buildMinimalSkin();
        touchpad = new Touchpad(10f, skin);
        touchpad.setSize(PAD_SIZE, PAD_SIZE);
        stage.addActor(touchpad);

        //TODO: reskin the buttons and add icons. for ability buttons, make a "hold and drag" gesture
        //TODO: when reskinning the buttons, remember to change the button label refferences. it was done for understanding's sake

        dashButton = addActionButton(skin, "DASH", () -> gp.keyH.dashPressed = true);
        attackButton = addAttackButton(skin);
        shotButton = addActionButton(skin, "SHOT", () -> shotArmedThisFrame = true);
        pauseButton = addActionButton(skin, "PAUSE", this::pause); //pause, should be on top middle
        inventoryButton = addInventoryButton(skin);

        addAbilityButton(skin, "SHOCK", () -> gp.player.shockwaveUnlocked, () -> gp.keyH.shockwavePressed = true);
        addAbilityButton(skin, "SNARE", () -> gp.player.voidSnareUnlocked, () -> gp.keyH.voidSnarePressed = true);
        addAbilityButton(skin, "FROST", () -> gp.player.frostNovaUnlocked, () -> gp.keyH.frostNovaPressed = true);
        addAbilityButton(skin, "OVRDR", () -> gp.player.overdriveUnlocked, () -> gp.keyH.overdrivePressed = true);

        layout();
    }

    /** Repositions widgets for the current screen size; call again on resize. **/
    public void layout() {
        int w = Gdx.graphics.getWidth();
        int h = Gdx.graphics.getHeight();

        //touchpad's position (bottom left, libgdx counts from bottom left)
        touchpad.setPosition(40f, 40f);

        //setting up action buttons layout
        attackButton.setBounds(w - BUTTON_SIZE - 40f, 40f, BUTTON_SIZE, BUTTON_SIZE);
        dashButton.setBounds(w - BUTTON_SIZE * 2 - 60f, 40f, BUTTON_SIZE, BUTTON_SIZE);
        shotButton.setBounds(w - BUTTON_SIZE * 3 - 80f, 40f, BUTTON_SIZE, BUTTON_SIZE);
        pauseButton.setBounds(w / 2f - BUTTON_SIZE / 2f, h - 40f - BUTTON_SIZE, BUTTON_SIZE, BUTTON_SIZE);
        inventoryButton.setBounds(w - BUTTON_SIZE - 40f, 40f + BUTTON_SIZE + 20f, BUTTON_SIZE, BUTTON_SIZE);
    }

    public Stage getStage() {
        return stage;
    }

    /** Called once per frame from MichiGame.render(), after gp.stepUpdates() reads input. */
    public void act(float delta) {
        applyMovement();
        refreshAbilityVisibility();
        stage.act(delta);

        if (attackButtonHeld) {
            gp.keyH.enterPressed = true;
            gp.mouseH.leftClicked = false;
        } else {
            gp.keyH.enterPressed = false;
        }

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

    // Mirrors desktop's P-key: only pauses from active play, only unpauses from pause itself,
    // so a stray tap can't yank the player out of dialogue/menus/cutscenes into pauseState.
    private void pause() {
        if (gp.gameState == GamePanel.playState) {
            gp.gameState = GamePanel.pauseState;
        } else if (gp.gameState == GamePanel.pauseState) {
            gp.gameState = GamePanel.playState;
        }
    }

    private void toggleInventory() {
        if (gp.gameState == GamePanel.characterState) {
            gp.gameState = GamePanel.playState;
        } else if (gp.gameState == GamePanel.playState) {
            gp.gameState = GamePanel.characterState;
        }
    }

    private void openSkillTree() {
        gp.gameState = GamePanel.skillTreeState;
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


    //adding buttons to the actionButton array and attatching a listener
    private TextButton addActionButton(Skin skin, String label, Fire onTap) {
        TextButton b = new TextButton(label, skin);
        b.addListener(new ClickListener() {
            @Override public void clicked(InputEvent event, float x, float y) { onTap.go(); }
        });
        stage.addActor(b);
        actionButtons.add(b);
        return b;
    }

    private TextButton addAttackButton(Skin skin) {
        TextButton b = new TextButton("ATK", skin);
        b.addListener(new ClickListener() {
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                attackButtonHeld = true;
                attackButtonPressStartedAt = TimeUtils.millis();
                return true;
            }

            @Override
            public void touchUp(InputEvent event, float x, float y, int pointer, int button) {
                long heldForMs = TimeUtils.millis() - attackButtonPressStartedAt;
                if (heldForMs < ATTACK_HOLD_THRESHOLD_MS) {
                    fireAttack();
                }
                attackButtonHeld = false;
            }
        });
        stage.addActor(b);
        actionButtons.add(b);
        return b;
    }

    private TextButton addInventoryButton(Skin skin) {
        TextButton b = new TextButton("INV", skin);
        b.addListener(new ClickListener() {
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                inventoryButtonPressStartedAt = TimeUtils.millis();
                return true;
            }

            @Override
            public void touchUp(InputEvent event, float x, float y, int pointer, int button) {
                long heldForMs = TimeUtils.millis() - inventoryButtonPressStartedAt;
                if (heldForMs < INVENTORY_HOLD_THRESHOLD_MS) {
                    toggleInventory();
                } else {
                    openSkillTree();
                }
            }
        });
        stage.addActor(b);
        actionButtons.add(b);
        return b;
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
        skin.add("white", gfx.GdxTextureUtil.managedFromPixmap(pixmap));

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

    //for refering to the button by its label, makes the code a little more readable
    private TextButton getButtonByLabel(String label) {
        for (TextButton b : actionButtons) {
            if (b.getText().toString().equals(label)) {
                return b;
            }
        }
        return null; // Return null if no button matches that label
    }
}
