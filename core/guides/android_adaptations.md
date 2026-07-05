# Android port — what changed and why

Branch: `feat/android-app`. Target devices: Samsung Galaxy S25 Ultra (primary), S24 Ultra
(must also work) — both high-end, landscape-friendly, 120Hz. No low-end-device compromises
were made; this is a straight "make it work great on flagship hardware" port.

The desktop build (`lwjgl3`) and the shared `core`/`ceva` game logic are **unaffected** for
existing players — every change below either only runs on the Android backend, or degrades to
the exact previous desktop behavior when `Gdx.app.getType() != Android`.

## 1. New Gradle module: `android/`

The user had already dropped an Android SDK directly into `android/` (`platforms/android-34`,
`build-tools/34.0.0`, `cmdline-tools/latest`, `platform-tools/`, `licenses/`) and uncommented
`include 'android'` in `settings.gradle`. What was missing was the actual module:

- **`android/build.gradle`** — `com.android.application` plugin (AGP `9.2.1`, added to root
  `build.gradle`'s `buildscript.dependencies` in the project's existing classic
  `apply plugin:` style, since there's no `pluginManagement` block for the modern `plugins{}`
  DSL). `compileSdk 34` / `targetSdk 34` / `minSdk 26` (Android 8.0 — comfortably below
  anything the S24/S25 Ultra ship). Depends on `:core`, `gdx-backend-android`,
  `gdx-controllers-android`. Natives are limited to **arm64-v8a only** — modern flagship
  Android hardware is arm64-only, so shipping 32-bit/x86 natives would only bloat the APK. A
  small `copyAndroidNatives` task unpacks the natives-artifact jars' `.so` files into
  `android/libs/arm64-v8a/` before packaging (the manual equivalent of what `gdx-setup`
  normally generates).
- **`android/AndroidManifest.xml`** — `INTERNET` permission only (multiplayer/cloud-save use
  raw TCP sockets, not HTTP, so Android's cleartext-HTTP policy doesn't apply). Landscape is
  locked via `android:screenOrientation="sensorLandscape"` — this is a top-down action game
  with a joystick + button cluster that needs width, and it matches the desktop 1280×720
  baseline aspect better than portrait.
- **`android/local.properties`** (gitignored, machine-specific) — `sdk.dir` points at
  `android/` itself, since that's where the SDK was installed, rather than a standalone SDK
  root like `%LOCALAPPDATA%\Android\Sdk`. **Important**: AGP reads this from the **project
  root**, not the module directory — it must be `C:\Michi-s-adventure\local.properties`, not
  `android/local.properties`.
- **`android/res/values/strings.xml` + `styles.xml`** — app name (`Michi\'s Adventure` — the
  apostrophe must be escaped or AAPT2 rejects the whole resource merge) and a
  fullscreen/no-title-bar theme.
- Placeholder launcher icon: `ceva/src/res/icon.png` (32×32) copied to
  `android/res/mipmap-hdpi/ic_launcher.png`. This is a stopgap — it's below Android's
  recommended launcher icon resolution (ideally 48–192px across density buckets) and should be
  replaced with a proper adaptive icon set before a real release.
- `.gitignore` gained entries for the SDK folders (large, machine-specific — never shipped in
  the repo), `android/libs/` (natives copy output), and root `local.properties`.

## 2. AndroidLauncher + AndroidBootstrap

`android/src/main/java/androidlauncher/AndroidLauncher.java` mirrors
`lwjgl3/DesktopLauncher.java`'s shape exactly: run a platform-specific bootstrap, then hand off
to the same `main.MichiGame` every backend shares. `AndroidApplicationConfiguration` sets
`useImmersiveMode = true`, disables the accelerometer/compass/gyroscope (unused — no
permissions/battery drain for nothing), and enables a wakelock so the screen doesn't sleep
mid-game.

`AndroidBootstrap.run()` replaces `main.Main.bootstrap()` (which does a mandatory
self-update-JAR check + Windows-registry-bound license load — neither has an Android
equivalent). See the licensing section below for what it does instead.

## 3. Licensing: online activation, shared with desktop

Licensing used to be split per-backend: desktop bound a signed `license.properties` to a
Windows registry machine fingerprint, and an earlier Android build baked a separately-signed
`license.properties` into the APK. Both were offline, RSA-signature schemes that trusted
whatever key+fingerprint pair verified — no server-side allow-list.

That's been replaced with **`platform.LicenseActivation`** (online activation), which is
backend-agnostic and used identically by desktop and Android — there is no more per-platform
license class. `MichiGame#create()` calls
`Main.LICENSE_KEY = platform.LicenseActivation.ensureActivated()` once `Gdx` is live, on every
backend. First run: it calls the save server's `ACTIVATE` handshake, which issues a brand-new
license key and hands back an opaque `activation_id` plus an AES-GCM-encrypted blob of the
license key (encrypted with a random key the server keeps to itself). Only `activation_id` and
the encrypted blob are persisted locally (via `platform.GameStorage`, so it works identically in
an APK's private storage) — the plaintext license key is never written to disk. Every later run
calls `LOGIN` with the same pair; the server decrypts the blob and confirms it. If no save server
is reachable, `ensureActivated()` returns `null` and the game still runs, just without cloud
saves/multiplayer for that run — same behavior on every backend, no separate "no license gate on
mobile" carve-out needed anymore.

`MultiplayerClient`/`CloudSaveService` no longer read a machine fingerprint or signature at all —
they send `platform.License.getActivationId()`/`getEncBlob()` over the wire, and the servers
resolve the license key server-side.

## 4. Storage: `platform.GameStorage`

Six files did raw relative-path file I/O (`new File("config.txt")`,
`new FileWriter("save.dat")`, etc.), which doesn't work on Android — there's no writable
working directory, only app-private sandboxed storage via `Gdx.files.local(...)`.

**`core/src/main/java/platform/GameStorage.java`** (new) centralizes this: `reader/writer/
inputStream/outputStream/exists/delete(String relativeName)`. On desktop it delegates to plain
`File`-based streams exactly as before (byte-identical behavior — existing save files still
load). On Android it delegates to `Gdx.files.local(...)`. Call sites updated mechanically (one
import + one API swap each, no logic changes): `Config.java` (`config.txt`),
`ServerListManager.java` (`servers.txt`), `SaveLoad.java` (`save.dat`), `CloudSaveService.java`
(`save_servers.txt`, the local encrypted-save cache, the legacy AES key file), and
`MapManager.java`'s crash-log write (which now does a read-modify-write since
`GameStorage.outputStream` always truncates — there's no cross-backend "append" mode).

`WindField.java`'s `.windmap` **reads** stay on `Gdx.files.internal` (they're bundled
per-map asset data, already fixed as part of item 5 below); its **write** path is the
in-game wind-painting authoring tool, a desktop-only workflow — it already fails silently and
harmlessly on Android (no writable path in its search list resolves), so no change was needed
there.

## 5. Classpath resource loading → `Gdx.files.internal`

A much bigger issue than expected surfaced here: **12 separate call sites** (not just one) used
`Class.getResourceAsStream("/res/...")` to load JSON data, TMX/XML, and fonts — `ItemFactory`,
`NPCFactory`, `MonsterFactory`, `QuestManager` (x2), `SkillTree`, `Config`, `UI`,
`CutsceneManager`, `WindField`, `AssetValidator`, and the XML path in `ResourceCache`. This
works on desktop because the assets sit on the JVM classpath (inside the JAR), but Android
packages assets separately in the APK — they are **not** on the classpath, so every one of
these would silently return `null`/fail on a real device.

Fixed with a single shared choke point: **`ResourceCache.openClasspathStream(String path)`**
(new static method), which strips the leading `/` and delegates to `Gdx.files.internal(...)`.
All 12 call sites now route through it. This is also strictly more correct on desktop, since it
reuses the one loading path (`Gdx.files`) that's already proven to work identically across
backends, instead of a second, parallel classpath-based one.

## 6. Touch controls

**`core/src/main/java/mobile/TouchControlsOverlay.java`** (new) — a Scene2D `Stage` with its
own `ScreenViewport`, independent of the game's world camera, drawn on top of the game. It
never touches gameplay code directly; it drives the exact same public fields that keyboard and
mouse already drive:

- **Movement**: a `Touchpad` (bottom-left). Its knob position is thresholded each frame into
  `KeyHandler.upPressed/downPressed/leftPressed/rightPressed` — the same 4 booleans WASD sets,
  read continuously by `Player.update()`, so diagonals and dash-forcing behave identically to
  keyboard input.
- **Attack**: a tap sets `MouseHandler.gameX/gameY` to the player's exact screen center, then
  `leftClicked = true`. This deliberately reuses an existing fallback:
  `getAttackAngleFromMouse()` already falls back to `angleForDirection(player.direction)`
  whenever `dx == dy == 0` (previously only reachable via a keyboard-only attack with no mouse
  movement) — so touch attacks aim in the player's current facing direction with zero new aim
  UI needed.
- **Dash / abilities**: tapping sets `dashPressed`/`shockwavePressed`/`voidSnarePressed`/
  `frostNovaPressed`/`overdrivePressed` true for one frame; `Player.update()` already
  self-clears each of these after consuming them (same as a key release), so no new
  consumption logic was needed. Ability buttons are **hidden** (not just greyed out) until the
  corresponding `Player.*Unlocked` flag is set, re-checked every frame since skills can unlock
  mid-session via the skill tree.
- **Ranged shot**: the one exception — `shotKeyPressed` is normally cleared by
  `KeyHandler.keyUp()` on a physical key release, so the overlay explicitly sets it `true` then
  `false` across one frame boundary per tap, to avoid it firing every frame the cooldown
  allows.
- The overlay is constructed and wired into the input chain only when
  `Gdx.app.getType() == ApplicationType.Android` (checked once in `MichiGame.create()`); its
  `Stage` is placed **first** in the `InputMultiplexer` so taps on its own widgets don't also
  register as world-taps in `MouseHandler`.

No external `.atlas`/`.skin` asset was introduced — the overlay builds a minimal solid-color
`Skin` at runtime from a 1×1 pixmap, so it has no new asset-pipeline dependency. **This means
the current look is functional but plain (flat-colored rectangles with text labels)** — it's a
placeholder ready for a proper HUD skin/atlas later, not a finished visual design.

## 7. Gamepad support (Bluetooth/USB)

Added `gdx-controllers-core` to `core/build.gradle` (shared API), `gdx-controllers-android` to
the Android module, and `gdx-controllers-desktop` to `lwjgl3` (so desktop also gets Xbox/PS
controller support for free, via the same shared adapter).

**`core/src/main/java/mobile/GamepadInputAdapter.java`** (new) implements `ControllerListener`,
registered unconditionally in `MichiGame.create()` via `Controllers.addListener(...)` (a
harmless no-op if nothing is paired). Uses each controller's semantic `ControllerMapping`
(cross-controller button/axis IDs like `buttonA`, `axisLeftX`, `buttonDpadUp`) rather than raw
indices, so it works consistently across different gamepad models: left stick / d-pad →
movement booleans, A → attack, B → dash, X → shot, Y/L1/R1/L2 → the four abilities (gated on
unlock state exactly like the touch buttons). Coexists with touch and keyboard with zero
exclusivity logic — all three input sources just set the same shared fields, so whichever the
player is actively using "wins" each frame.

## 8. Small fixes

- **`MouseHandler.java`**'s title-screen QUIT action used `System.exit(0)`, which is
  discouraged/unreliable on Android. Replaced with `Gdx.app.exit()` everywhere (not just on
  Android) — it lets libGDX run its own `dispose()` sequence first and maps to
  `Activity.finish()` on Android instead of hard-killing the process.

## Verification performed

- `./gradlew :android:assembleDebug` — **succeeds**, produces a ~188MB debug APK at
  `android/build/outputs/apk/debug/android-debug.apk`. Confirms the module configures,
  compiles, merges manifest/resources/assets, and packages correctly.
- `./gradlew :core:compileJava` and `./gradlew :lwjgl3:compileJava` — both succeed, confirming
  none of the shared edits (`LicenseManager`, `GameStorage`, `ResourceCache`, `MichiGame`,
  `MouseHandler`) broke desktop compilation.
- `./gradlew :lwjgl3:run` — **smoke-tested**: the desktop game launches, loads config, connects
  to the update-check fallback path (times out gracefully, as expected with no server
  reachable), loads a map with full collision-shape parsing, and reaches gameplay — confirming
  no runtime regression from the shared code changes.

## What's NOT verified (needs a real device)

No emulator or physical device was available in this environment. The following can only be
confirmed by installing the APK on the S25 Ultra (or S24 Ultra):

- **Touch control feel** — joystick deadzone/sizing, button layout/spacing on an actual 6.8"
  panel, whether the flat-color placeholder widgets are legible over gameplay.
- **Gamepad pairing** — Bluetooth/USB controller connection and the exact button mapping feel
  in practice (different controllers may report `ControllerMapping` differently).
- **The actual license handshake** against the live multiplayer/save servers from a real
  Android device (this was only verified structurally — that `verifyCurrent()` no longer
  touches the filesystem and that the baked file parses — not against a running server).
- **Orientation/immersive-mode correctness**, wakelock behavior, and frame pacing at 120Hz.
- **The placeholder launcher icon** — functional but low-resolution; should be replaced with a
  proper multi-density adaptive icon before any real release.

## Installing on a device

```
./gradlew :android:installDebug   # with a device/emulator connected via adb
```
or manually push `android/build/outputs/apk/debug/android-debug.apk` and install it.
