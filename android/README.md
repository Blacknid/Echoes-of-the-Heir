# android

Android launcher module (`gdx-backend-android`). `AndroidLauncher` wraps core's `MichiGame`
in an `AndroidApplication` activity — the same game loop and rendering as desktop.

Targets arm64-v8a, landscape, minSdk 26 / targetSdk 34.

## Build

Requires the Android SDK (`local.properties` → `sdk.dir`) and build tools 34.0.0.

```
./gradlew :android:assembleDebug
```

Produces a debug APK under `android/build/outputs/apk/debug/`.

## Notes

- Assets come from `core/assets` (shared with desktop) plus `android/assets` — no separate
  asset copy is maintained.
- Licensing (`platform.LicenseActivation.ensureActivated()`) is online, shared with desktop — it
  activates once against the save server and persists only an opaque `activation_id` + encrypted
  blob (no plaintext license, no per-machine signing key). Must run once `Gdx` is live (from
  `MichiGame#create()`, not `onCreate()`).
- No `java.awt`, `javax.swing`, `javax.sound`, `ProcessBuilder`, or registry calls exist in
  `core` outside the `platform` package — that's what keeps this module buildable.
