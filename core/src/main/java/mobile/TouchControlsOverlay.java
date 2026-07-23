package mobile;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
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

import gfx.SvgIcon;

import entity.Player;
import main.GamePanel;

/**
 * On-screen touch controls for Android: a floating movement joystick that spawns wherever a finger
 * lands on the left half of the screen, plus fixed action buttons on the right. Drives the same
 * fields as desktop keyboard/mouse ({@link main.KeyHandler}, {@link main.MouseHandler}), so
 * gameplay code needs no touch-specific changes.
 * Own {@link Stage}/{@link ScreenViewport}, drawn on top of the world camera.
 */
public class TouchControlsOverlay {

    private final GamePanel gp;
    private final Stage stage;
    /** Invisible catcher over the left half of the screen that spawns the floating joystick. */
    private Actor joystickZone;
    /** The joystick itself, drawn only while a finger is down. */
    private FloatingStick floatingStick;

    private static final float DEADZONE = 0.3f;
    private static final float PAD_SIZE = 220f;
    private static final float BUTTON_SIZE = 110f;
    private static final long ATTACK_HOLD_THRESHOLD_MS = 180L;
    private static final long INVENTORY_HOLD_THRESHOLD_MS = 180L;
    /** Hold this long on the skill button before the drag-to-pick grid opens. */
    private static final long SKILL_HOLD_THRESHOLD_MS = 250L;

    // The joystick reports through the same physical tokens the gamepad's left stick uses, so no
    // new bindings are needed and remapping keeps working. InputActions ignores unknown tokens
    // silently, so these must stay in sync with InputBindings' controller entries.
    private static final String TOKEN_UP    = "controller:axisLeftY-";
    private static final String TOKEN_DOWN  = "controller:axisLeftY+";
    private static final String TOKEN_LEFT  = "controller:axisLeftX-";
    private static final String TOKEN_RIGHT = "controller:axisLeftX+";

    private static final String ICON_DIR = "/res/ui/android/";
    /** Icons are rasterized at 2x button size so they stay crisp on high-DPI phone screens. */
    private static final int ICON_RASTER_SIZE = 220;

    private final List<TextButton> actionButtons = new ArrayList<>();

    private final TextButton dashButton;
    private final TextButton attackButton;
    private final TextButton shotButton;
    private final TextButton pauseButton;
    private final TextButton inventoryButton;
    private final TextButton skillButton;
    private final SkillRadial skillRadial;

    private interface Unlocked { boolean get(); }
    private interface Fire { void go(); }
    /** One castable skill: label shown in the radial, unlock test, and the key-flag it sets. */
    private record SkillEntry(String label, Unlocked unlocked, Fire cast) {}

    private final List<SkillEntry> skills = new ArrayList<>();

    // shotKeyPressed must go true-then-false within one input sample: Player.update() reads it
    // as "was the shoot key freshly pressed" (only cleared by KeyHandler.keyUp on desktop), so a
    // held-true value from a tap would otherwise fire every frame the cooldown allows.
    private boolean shotArmedThisFrame = false;
    private boolean attackButtonHeld = false;
    private long attackButtonPressStartedAt = 0L;
    private long inventoryButtonPressStartedAt = 0L;
    private long skillButtonPressStartedAt = 0L;
    /** True while the skill button is held down and the grid has not been opened/dismissed yet. */
    private boolean skillRadialArmed = false;

    /** True while a finger is down on the left half and the floating joystick is showing. */
    private boolean joystickActive = false;
    /** Pointer id that owns the joystick, or -1 when no finger is tracking it. */
    private int joystickPointer = -1;
    /** Stage-space point the finger first landed on — the joystick's neutral center. */
    private float joystickOriginX = 0f;
    private float joystickOriginY = 0f;
    /** Knob deflection in [-1, 1], computed from finger drift instead of by the Touchpad widget. */
    private float knobPercentX = 0f;
    private float knobPercentY = 0f;

    public TouchControlsOverlay(GamePanel gp) {
        this.gp = gp;
        this.stage = new Stage(new ScreenViewport());

        Skin skin = buildMinimalSkin();

        // Added before the stick so the stick draws on top of its own catcher.
        //
        // InputListener rather than ClickListener: this gesture wants none of the tap-square /
        // click-count machinery, and it must be strict about pointer identity. The joystick thumb
        // and an action-button finger are down at the same time constantly, so events for any
        // pointer other than the one that spawned the stick have to be ignored — otherwise
        // lifting the *other* finger would drop the joystick mid-walk.
        joystickZone = new Actor();
        // Draws nothing, but must still be *visible* to be touchable: Actor.hit() returns null for
        // any invisible actor before it ever checks the bounds, so an invisible catcher silently
        // swallows nothing and the joystick never spawns. act() owns the per-state toggle from here.
        joystickZone.setVisible(true);
        joystickZone.addListener(new com.badlogic.gdx.scenes.scene2d.InputListener() {
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                if (gp.gameState != GamePanel.playState) return false;
                if (joystickPointer != -1) return false;   // already tracking a finger
                joystickPointer = pointer;
                spawnJoystickAt(event.getStageX(), event.getStageY());
                return true;
            }

            @Override
            public void touchDragged(InputEvent event, float x, float y, int pointer) {
                if (pointer != joystickPointer) return;
                dragJoystickTo(event.getStageX(), event.getStageY());
            }

            @Override
            public void touchUp(InputEvent event, float x, float y, int pointer, int button) {
                if (pointer != joystickPointer) return;
                // Stage.cancelTouchFocusExcept() delivers a synthetic touchUp marked with
                // Integer.MIN_VALUE coordinates; a real finger lift always carries true ones.
                // Dropping the pointer without releasing lets the next drag re-acquire the stick
                // instead of stranding it. This fires when a system gesture claims the touch —
                // the left edge-gesture strip overlaps this zone — and is cheap insurance either way.
                if (event.getStageX() == Integer.MIN_VALUE && event.getStageY() == Integer.MIN_VALUE) {
                    joystickPointer = -1;   // focus is gone; let the next drag re-acquire it
                    return;
                }
                releaseJoystick();
            }
        });
        stage.addActor(joystickZone);

        floatingStick = new FloatingStick(skin);
        stage.addActor(floatingStick);

        //TODO: when reskinning the buttons, remember to change the button label refferences. it was done for understanding's sake

        dashButton = addActionButton(skin, "DASH", () -> gp.keyH.dashPressed = true);
        attackButton = addAttackButton(skin);
        shotButton = addActionButton(skin, "SHOT", () -> shotArmedThisFrame = true);
        pauseButton = addActionButton(skin, "PAUSE", this::pause); //pause, should be on top middle
        inventoryButton = addInventoryButton(skin);

        // The castable skills, in the order they appear in the hold-to-drag grid. Kept in sync
        // with Player.handleAbilityInputs() — each entry just raises the same key flag the
        // desktop hotkeys (Z/X/C/V) do, so the cast path is identical on both platforms.
        skills.add(new SkillEntry("SHOCK", () -> gp.player.shockwaveUnlocked, () -> gp.keyH.shockwavePressed = true));
        skills.add(new SkillEntry("SNARE", () -> gp.player.voidSnareUnlocked, () -> gp.keyH.voidSnarePressed = true));
        skills.add(new SkillEntry("FROST", () -> gp.player.frostNovaUnlocked, () -> gp.keyH.frostNovaPressed = true));
        skills.add(new SkillEntry("OVRDR", () -> gp.player.overdriveUnlocked, () -> gp.keyH.overdrivePressed = true));

        skillButton = addSkillButton(skin);
        // Added after every button so the grid draws on top of them while it is open.
        skillRadial = new SkillRadial(skin);
        stage.addActor(skillRadial);

        applyIcons();
        layout();
    }

    /** Repositions widgets for the current screen size; call again on resize. **/
    public void layout() {
        int w = Gdx.graphics.getWidth();
        int h = Gdx.graphics.getHeight();

        // The joystick has no fixed home any more — it spawns wherever the finger lands. Its
        // catcher owns the left half of the screen; the right half stays free so taps there fall
        // through to the action buttons and to MouseHandler as world-taps.
        joystickZone.setBounds(0f, 0f, w / 2f, h);

        //setting up action buttons layout
        attackButton.setBounds(w - BUTTON_SIZE - 40f, 40f, BUTTON_SIZE, BUTTON_SIZE);
        dashButton.setBounds(w - BUTTON_SIZE * 2 - 60f, 40f, BUTTON_SIZE, BUTTON_SIZE);
        shotButton.setBounds(w - BUTTON_SIZE * 3 - 80f, 40f, BUTTON_SIZE, BUTTON_SIZE);
        pauseButton.setBounds(w / 2f - BUTTON_SIZE / 2f, h - 40f - BUTTON_SIZE, BUTTON_SIZE, BUTTON_SIZE);
        inventoryButton.setBounds(w - BUTTON_SIZE - 40f, 40f + BUTTON_SIZE + 20f, BUTTON_SIZE, BUTTON_SIZE);
        // Second row on the right, left of the inventory button: reachable with the same thumb
        // without covering the attack/dash/shot row it sits above.
        skillButton.setBounds(w - BUTTON_SIZE * 2 - 60f, 40f + BUTTON_SIZE + 20f, BUTTON_SIZE, BUTTON_SIZE);

        skillRadial.setBounds(0f, 0f, w, h);
    }

    /**
     * Swap the placeholder text labels for the rasterized SVG icons. Any icon that fails to load
     * leaves its button as-is, so a missing/renamed asset degrades to the readable text label
     * rather than an invisible button.
     */
    private void applyIcons() {
        setIcon(shotButton, "pocket-bow.svg");
        setIcon(attackButton, "sai.svg");
        setIcon(skillButton, "skills.svg");
    }

    private void setIcon(TextButton button, String file) {
        Drawable icon = SvgIcon.drawable(ICON_DIR + file, ICON_RASTER_SIZE);
        if (icon == null) return;

        // The icon is rasterized white, so tinting is what gives it the UI's gold; the pressed
        // state brightens rather than swapping in a second raster.
        Drawable up = ((TextureRegionDrawable) icon).tint(new Color(0.87f, 0.71f, 0.39f, 1f));
        Drawable down = ((TextureRegionDrawable) icon).tint(new Color(1f, 0.93f, 0.75f, 1f));

        // Keep the text label as the accessible name but stop drawing it — the icon replaces it.
        button.getLabel().setVisible(false);

        // TextButton has no image slot of its own, so the icon rides along as a child actor
        // inset from the button's background, and follows it whenever layout() moves it.
        button.addActor(new IconOverlay(button, up, down));
    }

    /**
     * Draws a button's icon centered and inset inside it, picking the up/down drawable from the
     * button's own pressed state. Exists because {@link TextButton} (unlike {@code ImageTextButton})
     * has no image slot, and adding one would mean restyling every button in this overlay.
     */
    private static final class IconOverlay extends Actor {
        private static final float INSET = 0.22f;   // fraction of the button size trimmed per edge

        private final TextButton owner;
        private final Drawable up;
        private final Drawable down;

        IconOverlay(TextButton owner, Drawable up, Drawable down) {
            this.owner = owner;
            this.up = up;
            this.down = down;
            setTouchable(com.badlogic.gdx.scenes.scene2d.Touchable.disabled);
        }

        @Override
        public void draw(com.badlogic.gdx.graphics.g2d.Batch batch, float parentAlpha) {
            Drawable d = (owner.isPressed() && down != null) ? down : up;
            if (d == null) return;

            // Button is a Table with transform disabled, so the batch is NOT transformed into the
            // button's space. Instead Group.drawChildren() temporarily zeroes the parent's x/y and
            // adds the parent's position onto each *child's* x/y for the duration of the call. So
            // during this draw owner.getX() reads 0 and getX() carries the button's real screen
            // position — which is why centering on owner.getX() alone piles every icon at the
            // screen origin. Offsetting from getX()/getY() is what tracks the button.
            float size = Math.min(owner.getWidth(), owner.getHeight()) * (1f - 2f * INSET);
            float x = getX() + (owner.getWidth() - size) / 2f;
            float y = getY() + (owner.getHeight() - size) / 2f;
            d.draw(batch, x, y, size, size);
        }
    }

    public Stage getStage() {
        return stage;
    }

    private boolean wasInPlayState = false;

    /** Called once per frame from MichiGame.render(), after gp.stepUpdates() reads input. */
    public void act(float delta) {
        boolean play  = gp.gameState == GamePanel.playState;
        boolean pause = gp.gameState == GamePanel.pauseState;

        // Buttons only exist where they mean something: gameplay controls in playState, the
        // pause button also on the pause screen so it can unpause. Everywhere else (title,
        // dialogue, menus...) the whole overlay hides — invisible actors don't consume taps,
        // so menu taps fall through to MouseHandler instead of dying on an invisible joystick.
        // The joystick's own visibility is owned by the spawn/release gesture, not by state — all
        // that's gated here is whether its catcher is listening at all.
        joystickZone.setVisible(play);
        dashButton.setVisible(play);
        attackButton.setVisible(play);
        shotButton.setVisible(play);
        inventoryButton.setVisible(play || gp.gameState == GamePanel.characterState);
        skillButton.setVisible(play);
        pauseButton.setVisible(play || pause);

        if (play) {
            applyMovement();
            // Promote a sustained press into the drag-to-pick grid. Done here rather than on a
            // timer so the threshold is checked against real frames, and so leaving playState
            // mid-hold (below) can cancel it.
            if (skillRadialArmed && !skillRadial.isOpen()
                    && TimeUtils.millis() - skillButtonPressStartedAt >= SKILL_HOLD_THRESHOLD_MS) {
                skillRadial.open();
            }
        } else {
            // Leaving playState mid-drag must not leave a movement key latched true, nor strand
            // the floating joystick on screen with no finger holding it.
            if (wasInPlayState) {
                releaseJoystick();
                attackButtonHeld = false;
                skillRadialArmed = false;
                skillRadial.close();
            }
        }
        wasInPlayState = play;

        stage.act(delta);

        if (play) {
            if (attackButtonHeld) {
                gp.keyH.enterPressed = true;
                gp.mouseH.leftClicked = false;
            } else {
                gp.keyH.enterPressed = false;
            }
        }

        if (shotArmedThisFrame) {
            gp.keyH.shotKeyPressed = true;
            shotArmedThisFrame = false;
        } else if (play) {
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
        // No finger down reads as a neutral stick, so every direction token gets released.
        float kx = joystickActive ? knobPercentX : 0f;
        float ky = joystickActive ? knobPercentY : 0f;

        // Y is up-positive here (screen-space convention); the game's world is y-down, so an
        // upward drag (ky > 0) means MOVE_UP.
        setMoveToken(TOKEN_RIGHT, kx >  DEADZONE);
        setMoveToken(TOKEN_LEFT,  kx < -DEADZONE);
        setMoveToken(TOKEN_UP,    ky >  DEADZONE);
        setMoveToken(TOKEN_DOWN,  ky < -DEADZONE);
    }

    /** Tracks which movement tokens this overlay currently holds down, so it only sends edges. */
    private final java.util.Map<String, Boolean> heldMoveTokens = new java.util.HashMap<>();

    /**
     * Drive a movement action through {@link main.input.InputActions}, exactly as the gamepad's
     * left stick does.
     *
     * <p>Writing {@code keyH.upPressed} and friends directly does not work: {@code KeyHandler.update()}
     * calls {@code pollGameplayActions()} every tick, which unconditionally reassigns all four
     * movement booleans from {@code gp.actions.isDown(...)}. On Android no physical key is held, so
     * those writes were erased a few microseconds after being made and the player never moved.
     *
     * <p>{@code setPhysical} refcounts presses, so this must send edges only — a per-frame "true"
     * would inflate the count and leave the direction stuck down forever after release.
     */
    private void setMoveToken(String token, boolean down) {
        boolean was = heldMoveTokens.getOrDefault(token, false);
        if (was == down) return;
        heldMoveTokens.put(token, down);
        gp.actions.setPhysical(token, down);
    }

    /**
     * Drop the joystick centered on the finger, clamped so it stays fully on screen even when the
     * touch lands near an edge. The knob starts centered (no movement) — the player's initial
     * touch point is the neutral origin, and movement is measured as drift away from it.
     */
    private void spawnJoystickAt(float stageX, float stageY) {
        float w = Gdx.graphics.getWidth();
        float h = Gdx.graphics.getHeight();

        joystickOriginX = Math.max(PAD_SIZE / 2f, Math.min(stageX, w - PAD_SIZE / 2f));
        joystickOriginY = Math.max(PAD_SIZE / 2f, Math.min(stageY, h - PAD_SIZE / 2f));

        // FloatingStick draws from joystickOriginX/Y directly, so there is nothing to reposition.
        floatingStick.setVisible(true);

        joystickActive = true;
        knobPercentX = 0f;
        knobPercentY = 0f;
    }

    /**
     * Track the finger as an offset from where it first landed, normalized to [-1, 1] over the
     * pad's radius so the existing {@link #DEADZONE} thresholds keep their meaning. Dragging past
     * the edge saturates rather than clamping the finger, which is what makes a floating stick
     * feel like it follows you.
     */
    private void dragJoystickTo(float stageX, float stageY) {
        if (!joystickActive) return;

        float radius = PAD_SIZE / 2f;
        float dx = (stageX - joystickOriginX) / radius;
        float dy = (stageY - joystickOriginY) / radius;

        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > 1f) { dx /= len; dy /= len; }

        knobPercentX = dx;
        knobPercentY = dy;
    }

    /** Finger lifted: hide the stick and stop all movement this frame. */
    private void releaseJoystick() {
        joystickActive = false;
        joystickPointer = -1;
        knobPercentX = 0f;
        knobPercentY = 0f;
        floatingStick.setVisible(false);
        // Release every token this overlay still holds, otherwise the refcount in InputActions
        // stays above zero and the player keeps walking after the finger is gone.
        setMoveToken(TOKEN_UP, false);
        setMoveToken(TOKEN_DOWN, false);
        setMoveToken(TOKEN_LEFT, false);
        setMoveToken(TOKEN_RIGHT, false);
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

    /**
     * The skill button: a tap opens the skill tree screen, a hold opens the drag-to-pick grid of
     * unlocked skills over the button. The choice is made on release, mirroring how the inventory
     * button already distinguishes tap from hold.
     */
    private TextButton addSkillButton(Skin skin) {
        TextButton b = new TextButton("SKILL", skin);
        b.addListener(new ClickListener() {
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                skillButtonPressStartedAt = TimeUtils.millis();
                skillRadialArmed = true;
                return true;
            }

            @Override
            public void touchDragged(InputEvent event, float x, float y, int pointer) {
                if (!skillRadial.isOpen()) return;
                // x/y are button-local; the radial lives in stage coordinates.
                Vector2 stagePos = event.getListenerActor().localToStageCoordinates(new Vector2(x, y));
                skillRadial.updateHighlight(stagePos.x, stagePos.y);
            }

            @Override
            public void touchUp(InputEvent event, float x, float y, int pointer, int button) {
                skillRadialArmed = false;
                long heldForMs = TimeUtils.millis() - skillButtonPressStartedAt;

                if (skillRadial.isOpen()) {
                    // Released over a cell: cast it. Released outside every cell: just dismiss,
                    // so an accidental hold can be cancelled by dragging away.
                    SkillEntry picked = skillRadial.pickedSkill();
                    skillRadial.close();
                    if (picked != null) picked.cast().go();
                } else if (heldForMs < SKILL_HOLD_THRESHOLD_MS) {
                    openSkillTree();
                }
            }
        });
        stage.addActor(b);
        actionButtons.add(b);
        return b;
    }

    /** Skills the player has actually unlocked, in declaration order. */
    private List<SkillEntry> unlockedSkills() {
        List<SkillEntry> out = new ArrayList<>();
        for (SkillEntry s : skills) {
            if (s.unlocked().get()) out.add(s);
        }
        return out;
    }

    /**
     * The floating movement stick: a translucent base ring with a knob that tracks the finger.
     *
     * <p>Replaces libGDX's {@link Touchpad} widget, which cannot express this gesture. A Touchpad
     * only starts tracking once a drag arrives *inside* its bounds, so a stick spawned under a
     * finger that is already down would sit at neutral until the finger moved again; and its knob
     * position is private, so it cannot be driven from outside either. Since the spawn gesture has
     * to own the math anyway (see {@link #dragJoystickTo}), drawing two circles here is both
     * simpler and exactly what the interaction needs.
     */
    private final class FloatingStick extends Actor {

        private final Drawable base;
        private final Drawable knob;

        FloatingStick(Skin skin) {
            this.base = circle(skin, new Color(0.3f, 0.3f, 0.3f, 0.35f));
            this.knob = circle(skin, new Color(0.9f, 0.9f, 0.9f, 0.9f));
            setTouchable(com.badlogic.gdx.scenes.scene2d.Touchable.disabled);
            setVisible(false);
        }

        @Override
        public void draw(com.badlogic.gdx.graphics.g2d.Batch batch, float parentAlpha) {
            if (!joystickActive) return;

            base.draw(batch, joystickOriginX - PAD_SIZE / 2f, joystickOriginY - PAD_SIZE / 2f,
                      PAD_SIZE, PAD_SIZE);

            // The knob rides at the current deflection, which is already clamped to the unit
            // circle, so it can never leave the base ring.
            float knobSize = PAD_SIZE * 0.4f;
            float kx = joystickOriginX + knobPercentX * (PAD_SIZE / 2f) - knobSize / 2f;
            float ky = joystickOriginY + knobPercentY * (PAD_SIZE / 2f) - knobSize / 2f;
            knob.draw(batch, kx, ky, knobSize, knobSize);
        }
    }

    /**
     * The hold-to-open grid of unlocked skills. Drawn over the skill button as a compact grid of
     * labelled cells; the cell under the dragging finger is highlighted and cast on release.
     *
     * <p>It is a plain non-touchable {@link Actor} rather than a set of real buttons on purpose:
     * the gesture is one continuous press that starts on the skill button, so that button's
     * listener owns the pointer for the whole drag. Scene2d would not deliver the drag to sibling
     * actors anyway, so the grid only needs to draw itself and answer "which cell is at this
     * point" — hit-testing it here keeps the whole gesture in one place.
     */
    private final class SkillRadial extends Actor {

        private static final float CELL = 96f;
        private static final float CELL_GAP = 8f;
        private static final int COLUMNS = 2;

        private final Drawable cellBg;
        private final Drawable cellHighlight;
        private final BitmapFont font = new BitmapFont();

        private boolean open = false;
        private List<SkillEntry> entries = new ArrayList<>();
        private int highlighted = -1;
        /** Grid origin in stage coordinates (bottom-left of the whole grid). */
        private float gridX, gridY;

        SkillRadial(Skin skin) {
            this.cellBg = tint(skin, new Color(0.12f, 0.12f, 0.18f, 0.88f));
            this.cellHighlight = tint(skin, new Color(0.87f, 0.71f, 0.39f, 0.85f));
            setTouchable(com.badlogic.gdx.scenes.scene2d.Touchable.disabled);
            setVisible(false);
        }

        boolean isOpen() { return open; }

        /** Opens the grid above the skill button, laid out for the currently unlocked skills. */
        void open() {
            entries = unlockedSkills();
            if (entries.isEmpty()) return;   // nothing unlocked yet: hold does nothing

            int rows = (entries.size() + COLUMNS - 1) / COLUMNS;
            float gridW = COLUMNS * CELL + (COLUMNS - 1) * CELL_GAP;
            float gridH = rows * CELL + (rows - 1) * CELL_GAP;

            // Sit the grid just above the button and centered on it, then clamp into the screen so
            // it stays fully visible on short screens / near the right edge.
            gridX = skillButton.getX() + (skillButton.getWidth() - gridW) / 2f;
            gridY = skillButton.getY() + skillButton.getHeight() + CELL_GAP;
            gridX = Math.max(8f, Math.min(gridX, getWidth() - gridW - 8f));
            gridY = Math.max(8f, Math.min(gridY, getHeight() - gridH - 8f));

            highlighted = -1;
            open = true;
            setVisible(true);
        }

        void close() {
            open = false;
            highlighted = -1;
            setVisible(false);
        }

        /** Marks whichever cell contains the given stage-space point, or none. */
        void updateHighlight(float stageX, float stageY) {
            highlighted = -1;
            for (int i = 0; i < entries.size(); i++) {
                float cx = cellX(i), cy = cellY(i);
                if (stageX >= cx && stageX <= cx + CELL && stageY >= cy && stageY <= cy + CELL) {
                    highlighted = i;
                    return;
                }
            }
        }

        /** The skill under the finger at release, or null if the finger was outside the grid. */
        SkillEntry pickedSkill() {
            return (highlighted >= 0 && highlighted < entries.size())
                ? entries.get(highlighted) : null;
        }

        private float cellX(int i) {
            return gridX + (i % COLUMNS) * (CELL + CELL_GAP);
        }

        private float cellY(int i) {
            // Fill top-down so the reading order matches the declaration order, while libGDX's
            // origin stays at the bottom-left.
            int rows = (entries.size() + COLUMNS - 1) / COLUMNS;
            int row = i / COLUMNS;
            return gridY + (rows - 1 - row) * (CELL + CELL_GAP);
        }

        @Override
        public void draw(com.badlogic.gdx.graphics.g2d.Batch batch, float parentAlpha) {
            if (!open) return;
            for (int i = 0; i < entries.size(); i++) {
                float cx = cellX(i), cy = cellY(i);
                Drawable bg = (i == highlighted) ? cellHighlight : cellBg;
                bg.draw(batch, cx, cy, CELL, CELL);

                String label = entries.get(i).label();
                font.setColor(i == highlighted ? Color.BLACK : Color.WHITE);
                com.badlogic.gdx.graphics.g2d.GlyphLayout gl =
                    new com.badlogic.gdx.graphics.g2d.GlyphLayout(font, label);
                font.draw(batch, label, cx + (CELL - gl.width) / 2f, cy + (CELL + gl.height) / 2f);
            }
        }
    }


    /** Builds a tiny solid-color Skin so this overlay has no external .atlas/.json dependency. */
    private static Skin buildMinimalSkin() {
        Skin skin = new Skin();
        Pixmap pixmap = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pixmap.setColor(1f, 1f, 1f, 1f);
        pixmap.fill();
        skin.add("white", gfx.GdxTextureUtil.managedFromPixmap(pixmap));

        // A filled white disc for the floating stick's base and knob. Baked once at a size big
        // enough that scaling it down to either role stays smooth; the 1x1 "white" above would
        // only ever give square controls.
        int r = 64;
        Pixmap disc = new Pixmap(r * 2, r * 2, Pixmap.Format.RGBA8888);
        disc.setBlending(Pixmap.Blending.None);
        disc.setColor(1f, 1f, 1f, 0f);
        disc.fill();
        disc.setColor(1f, 1f, 1f, 1f);
        disc.fillCircle(r, r, r - 1);
        Texture discTex = gfx.GdxTextureUtil.managedFromPixmap(disc);
        discTex.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        skin.add("circle", discTex);

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

    /** Same as {@link #tint} but round — used for the floating stick's base and knob. */
    private static Drawable circle(Skin skin, Color c) {
        TextureRegion region = new TextureRegion(skin.get("circle", Texture.class));
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
