# android — STUB (not built yet)

This module is a placeholder so the Android port is a drop-in later. It is intentionally
**not** included in `settings.gradle` and has no build wired up.

When you're ready to port to Android:

1. Install the Android SDK and accept licenses.
2. Add the Android Gradle plugin + `com.badlogicgames.gdx:gdx-backend-android` deps here.
3. Add `include 'android'` to `settings.gradle`.
4. Create `AndroidLauncher extends AndroidApplication` that does
   `initialize(new main.MichiGame(), config)` — the same `MichiGame` the desktop
   `lwjgl3/DesktopLauncher` uses (all rendering already runs through libGDX).
5. Implement the Android side of `core`'s `platform.PlatformServices` interface
   (the desktop impl lives in `lwjgl3`). The non-portable pieces already isolated there:
   - audio (handled by `Gdx.audio` — already cross-platform)
   - process relaunch for self-update (desktop-only; Android updates via Play Store)
   - machine fingerprint (desktop reads the Windows registry; Android uses a device id)
   - `System.exit` / window controls (no-ops or platform equivalents on Android)
6. Assets are read from the libGDX assets root, so `core/assets` (the migrated `res/`)
   is shared as-is.

No `java.awt`, `javax.swing`, `javax.sound`, `ProcessBuilder`, or registry calls should
exist in `core` outside the `platform` package — that's the gate that keeps Android viable.
