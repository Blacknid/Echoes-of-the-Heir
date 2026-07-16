package mobile;

import com.badlogic.gdx.controllers.Controller;
import com.badlogic.gdx.controllers.ControllerListener;
import com.badlogic.gdx.controllers.ControllerMapping;

import main.GamePanel;
import main.KeyHandler;

/**
 * Bluetooth/USB gamepad support. Every physical button/axis edge is translated into a symbolic
 * "controller:xxx" token (see {@link main.input.InputBindings}) and reported to
 * {@link main.input.InputActions}, the same action system keyboard and mouse feed, so
 * d-pad/stick/face buttons drive movement, gameplay actions, AND menu navigation uniformly,
 * with no separate gamepad-only logic needed beyond this translation layer. To rebind a
 * controller button, edit res/data/keybindings.json, nothing in this file needs to change.
 *
 * <p>{@code ControllerMapping} fields are per-controller-instance (two gamepad models can report
 * buttonA at different raw codes), so resolution happens here, per event, via reverse lookup
 * against {@code controller.getMapping()}, never cached/baked in at JSON-load time.
 *
 * <p>Registered unconditionally in {@code MichiGame.create()} via
 * {@code Controllers.addListener(...)}; harmless no-op if nothing is paired.
 */
public class GamepadInputAdapter implements ControllerListener {

    private static final float STICK_DEADZONE = 0.35f;

    private final GamePanel gp;

    // Axes report continuous values, not discrete down/up events, these caches let axisMoved
    // detect deadzone-crossing transitions and report a physical edge only once per crossing,
    // instead of every callback while held past the deadzone (which would repeatedly increment
    // InputActions' held-count without a matching decrement).
    private boolean leftXNegDown, leftXPosDown, leftYNegDown, leftYPosDown;

    public GamepadInputAdapter(GamePanel gp) {
        this.gp = gp;
    }

    @Override
    public void connected(Controller controller) {
        com.badlogic.gdx.Gdx.app.log("Gamepad", "Connected: " + controller.getName());
    }

    @Override
    public void disconnected(Controller controller) {
        KeyHandler k = gp.keyH;
        k.upPressed = k.downPressed = k.leftPressed = k.rightPressed = false;
    }

    @Override
    public boolean buttonDown(Controller controller, int buttonCode) {
        String token = tokenForButton(controller.getMapping(), buttonCode);
        if (token == null) return false;
        gp.actions.setPhysical(token, true);
        // Button presses never go through libGDX's keyDown callback (ControllerListener is a
        // separate event source from InputProcessor), so nothing would otherwise re-run
        // KeyHandler's per-state dispatch tree, this is what lets a controller press of, say,
        // Cross/X actually reach handleTitleState's MENU_CONFIRM check instead of the recorded
        // action just sitting unconsumed in gp.actions forever.
        gp.keyH.onControllerButton();
        return false;
    }

    @Override
    public boolean buttonUp(Controller controller, int buttonCode) {
        String token = tokenForButton(controller.getMapping(), buttonCode);
        if (token == null) return false;
        gp.actions.setPhysical(token, false);
        return false;
    }

    @Override
    public boolean axisMoved(Controller controller, int axisCode, float value) {
        ControllerMapping m = controller.getMapping();

        if (axisCode == m.axisLeftX) {
            boolean pos = value > STICK_DEADZONE;
            boolean neg = value < -STICK_DEADZONE;
            if (pos != leftXPosDown) {
                gp.actions.setPhysical("controller:axisLeftX+", pos);
                leftXPosDown = pos;
                if (pos) gp.keyH.onControllerAxisDirection();
            }
            if (neg != leftXNegDown) {
                gp.actions.setPhysical("controller:axisLeftX-", neg);
                leftXNegDown = neg;
                if (neg) gp.keyH.onControllerAxisDirection();
            }
        } else if (axisCode == m.axisLeftY) {
            // Most gamepad APIs report +Y as down (opposite of the touchpad's screen-space
            // convention), which already matches the game's yDown world: pushing the stick
            // down (+Y) should mean "moving down".
            boolean pos = value > STICK_DEADZONE;
            boolean neg = value < -STICK_DEADZONE;
            if (pos != leftYPosDown) {
                gp.actions.setPhysical("controller:axisLeftY+", pos);
                leftYPosDown = pos;
                if (pos) gp.keyH.onControllerAxisDirection();
            }
            if (neg != leftYNegDown) {
                gp.actions.setPhysical("controller:axisLeftY-", neg);
                leftYNegDown = neg;
                if (neg) gp.keyH.onControllerAxisDirection();
            }
        }
        return false;
    }

    /** Reverse-looks-up a raw button code against the controller's own mapping, returning the
     *  symbolic "controller:xxx" token InputBindings/InputActions key off of, or null if the
     *  code doesn't match any known face/shoulder/d-pad/stick button. Add a new bindable button
     *  here (and it becomes rebindable in keybindings.json with zero other code changes). */
    private static String tokenForButton(ControllerMapping m, int buttonCode) {
        if (buttonCode == m.buttonA) return "controller:buttonA";
        if (buttonCode == m.buttonB) return "controller:buttonB";
        if (buttonCode == m.buttonX) return "controller:buttonX";
        if (buttonCode == m.buttonY) return "controller:buttonY";
        if (buttonCode == m.buttonL1) return "controller:buttonL1";
        if (buttonCode == m.buttonL2) return "controller:buttonL2";
        if (buttonCode == m.buttonR1) return "controller:buttonR1";
        if (buttonCode == m.buttonR2) return "controller:buttonR2";
        if (buttonCode == m.buttonBack) return "controller:buttonBack";
        if (buttonCode == m.buttonStart) return "controller:buttonStart";
        if (buttonCode == m.buttonLeftStick) return "controller:buttonLeftStick";
        if (buttonCode == m.buttonRightStick) return "controller:buttonRightStick";
        if (buttonCode == m.buttonDpadUp) return "controller:buttonDpadUp";
        if (buttonCode == m.buttonDpadDown) return "controller:buttonDpadDown";
        if (buttonCode == m.buttonDpadLeft) return "controller:buttonDpadLeft";
        if (buttonCode == m.buttonDpadRight) return "controller:buttonDpadRight";
        return null;
    }
}
