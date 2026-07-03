package platform;

import java.io.InputStream;
import java.util.Properties;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;

import main.Main;

/**
 * Android equivalent of desktop's license load (no update check — Play Store/APK reinstall
 * covers that instead). Reads a signed license.properties baked into the APK
 * ({@code android/assets/license.properties}, see build_tools/generate_dev_license.py
 * --machine-fp) with no user-facing gate, and registers it with {@link License}.
 *
 * <p>Must run from {@code MichiGame#create()} or later, not from
 * {@code AndroidLauncher.onCreate()} before {@code initialize(...)} — {@code Gdx.files} isn't
 * wired up yet and this throws a NullPointerException before any window opens.
 */
public final class AndroidLicense implements LicenseCheck {

    private static final AndroidLicense INSTANCE = new AndroidLicense();

    private volatile String cachedKey;
    private volatile String cachedFp;
    private volatile String cachedSig;

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

        INSTANCE.cachedKey = key;
        INSTANCE.cachedFp  = fp;
        INSTANCE.cachedSig = sig;
        License.set(INSTANCE);
        Main.LICENSE_KEY = key;
        Gdx.app.log("AndroidLicense", "License primed for cloud save / multiplayer.");
    }

    // No on-disk file to re-check on Android — once primed, stays valid for the session.
    @Override public boolean verifyCurrent() { return cachedKey != null; }
    @Override public String getCachedMachineFp() { return cachedFp; }
    @Override public String getCachedSignature() { return cachedSig; }
    @Override public String getCachedKey() { return cachedKey; }
    @Override public boolean isTampered() { return false; }
}
