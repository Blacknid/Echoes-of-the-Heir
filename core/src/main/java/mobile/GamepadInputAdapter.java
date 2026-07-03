package mobile;

import com.badlogic.gdx.controllers.Controller;
import com.badlogic.gdx.controllers.ControllerListener;
import com.badlogic.gdx.controllers.ControllerMapping;

import main.GamePanel;
import main.KeyHandler;

/**
 * Bluetooth/USB gamepad support. Maps the left stick / d-pad to the same 4 movement booleans
 * the touch joystick and desktop WASD drive, and face/shoulder buttons to the same one-shot
 * action fields — no gameplay code changes, no exclusivity with touch or keyboard (all three
 * input sources just set the same {@link main.KeyHandler}/{@link main.MouseHandler} fields, so
 * whichever the player is holding "wins" each frame).
 *
 * <p>Registered unconditionally in {@code MichiGame.create()} via
 * {@code Controllers.addListener(...)}; harmless no-op if nothing is paired.
 */
public class GamepadInputAdapter implements ControllerListener {

    private static final float STICK_DEADZONE = 0.35f;

    private final GamePanel gp;

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
        ControllerMapping m = controller.getMapping();
        KeyHandler k = gp.keyH;

        if (buttonCode == m.buttonDpadUp)    k.upPressed = true;
        if (buttonCode == m.buttonDpadDown)  k.downPressed = true;
        if (buttonCode == m.buttonDpadLeft)  k.leftPressed = true;
        if (buttonCode == m.buttonDpadRight) k.rightPressed = true;

        if (buttonCode == m.buttonA) fireAttack();
        if (buttonCode == m.buttonB) k.dashPressed = true;
        if (buttonCode == m.buttonX) k.shotKeyPressed = true;
        if (buttonCode == m.buttonY && gp.player.overdriveUnlocked) k.overdrivePressed = true;
        if (buttonCode == m.buttonL1 && gp.player.shockwaveUnlocked) k.shockwavePressed = true;
        if (buttonCode == m.buttonR1 && gp.player.voidSnareUnlocked) k.voidSnarePressed = true;
        if (buttonCode == m.buttonL2 && gp.player.frostNovaUnlocked) k.frostNovaPressed = true;
        return false;
    }

    @Override
    public boolean buttonUp(Controller controller, int buttonCode) {
        ControllerMapping m = controller.getMapping();
        KeyHandler k = gp.keyH;

        if (buttonCode == m.buttonDpadUp)    k.upPressed = false;
        if (buttonCode == m.buttonDpadDown)  k.downPressed = false;
        if (buttonCode == m.buttonDpadLeft)  k.leftPressed = false;
        if (buttonCode == m.buttonDpadRight) k.rightPressed = false;
        // shotKeyPressed mirrors desktop's keyUp-clears-the-flag behavior (see KeyHandler).
        if (buttonCode == m.buttonX) k.shotKeyPressed = false;
        return false;
    }

    @Override
    public boolean axisMoved(Controller controller, int axisCode, float value) {
        ControllerMapping m = controller.getMapping();
        KeyHandler k = gp.keyH;

        if (axisCode == m.axisLeftX) {
            k.rightPressed = value > STICK_DEADZONE;
            k.leftPressed  = value < -STICK_DEADZONE;
        } else if (axisCode == m.axisLeftY) {
            // Most gamepad APIs report +Y as down (opposite of the touchpad's screen-space
            // convention), which already matches the game's yDown world: pushing the stick
            // down (+Y) should mean "moving down".
            k.downPressed = value > STICK_DEADZONE;
            k.upPressed   = value < -STICK_DEADZONE;
        }
        return false;
    }

    private void fireAttack() {
        int pcx = gp.player.screenX + gp.tileSize / 2;
        int pcy = gp.player.screenY + gp.tileSize / 2;
        gp.mouseH.gameX = pcx;
        gp.mouseH.gameY = pcy;
        gp.mouseH.leftClicked = true;
    }
}
