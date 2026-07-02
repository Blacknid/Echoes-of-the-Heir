package androidlauncher;

import android.os.Bundle;

import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;

import main.MichiGame;

/**
 * Android entry point (gdx-backend-android). Configures the Android-specific application
 * settings (immersive mode, wakelock, disabled sensors) then hands off to the shared
 * {@link MichiGame}, which owns the game loop/rendering on every backend and runs its own
 * platform-specific setup (license priming, touch overlay, etc.) once {@code Gdx} is live —
 * {@code Gdx.app}/{@code Gdx.files} do not exist yet at this point in the Android lifecycle,
 * so platform setup that depends on them cannot run here.
 */
public class AndroidLauncher extends AndroidApplication {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
        config.useImmersiveMode = true;
        config.useAccelerometer = false;
        config.useCompass = false;
        config.useGyroscope = false;
        config.useWakelock = true; // top-down action game — screen shouldn't sleep mid-play

        initialize(new MichiGame(), config);
    }
}
