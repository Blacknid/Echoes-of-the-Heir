package platform;

import java.io.InputStream;
import java.util.Properties;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;

import data.LicenseManager;
import main.Main;

/**
 * Android equivalent of the license half of {@link main.Main#bootstrap()} (desktop's mandatory
 * self-update check has no Android equivalent — Play Store/APK reinstall covers that instead).
 *
 * <p>Cloud saves and multiplayer require a non-null {@code Main.LICENSE_KEY} plus a primed
 * {@link LicenseManager} cache (the server only checks that the RSA signature over
 * "key|machine_fp" verifies against the shared public key — it has no allow-list — so any
 * validly-signed pair works). One such pair is baked into the APK at build time as
 * {@code android/assets/license.properties} (see build_tools/generate_dev_license.py
 * --machine-fp) and loaded here with no user-facing gate.
 *
 * <p>Must run from {@link main.MichiGame#create()} (or later) — NOT from
 * {@code AndroidLauncher.onCreate()} before {@code initialize(...)} is called, since
 * {@code Gdx.app}/{@code Gdx.files} aren't wired up on the Android backend until libGDX's
 * context is live. Calling this before that point throws a NullPointerException on
 * {@code Gdx.files}, crashing the app on launch before any window opens.
 */
public final class AndroidLicense {

    private AndroidLicense() {}

    /** No-ops on every backend except Android. Safe to call unconditionally from create(). */
    public static void primeIfAndroid() {
        if (Gdx.app.getType() != Application.ApplicationType.Android) return;

        Properties props = new Properties();
        FileHandle fh = Gdx.files.internal("license.properties");
        if (!fh.exists()) {
            Gdx.app.error("AndroidLicense", "Bundled license.properties missing from assets — "
                + "cloud saves and multiplayer will be unavailable this session.");
            return;
        }
        try (InputStream is = fh.read()) {
            props.load(is);
        } catch (Exception e) {
            Gdx.app.error("AndroidLicense", "Failed to read bundled license.properties", e);
            return;
        }

        String key = props.getProperty("license_key", "").trim();
        String fp  = props.getProperty("machine_fp", "").trim();
        String sig = props.getProperty("signature", "").replaceAll("\\s", "");
        if (key.isEmpty() || fp.isEmpty() || sig.isEmpty()) {
            Gdx.app.error("AndroidLicense", "Bundled license.properties is incomplete.");
            return;
        }

        LicenseManager.primeForAndroid(key, fp, sig);
        Main.LICENSE_KEY = key;
        Gdx.app.log("AndroidLicense", "License primed for cloud save / multiplayer.");
    }
}
