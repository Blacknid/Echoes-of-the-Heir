# Michi's Adventure — Full Technical Documentation

This document explains the entire system end to end: the three backend servers, the game client (desktop and mobile), the build system that produces both, and the itch.io licensing model that ties them together. It is written in the order that makes the dependencies easiest to follow: first the build system (because "desktop" and "mobile" are not two codebases, they're one codebase built two ways), then the client that build system produces, then the servers that client talks to, and finally the licensing model that spans all of it.

Every section quotes real code from the repository with a file path and line reference, followed by an explanation of *why* the code is shaped the way it is — not just what it does.

---

## Table of Contents

1. [The Build System](#1-the-build-system)
2. [The Client (Core Game Module)](#2-the-client-core-game-module)
3. [The Servers](#3-the-servers)
4. [Licensing and itch.io Integration](#4-licensing-and-itchio-integration)
5. [End-to-End Flow Summaries](#5-end-to-end-flow-summaries)

---

# 1. The Build System

## What Gradle Is, In This Project's Terms

Gradle is a build-automation tool. It reads a set of build scripts (here, plain Groovy `build.gradle` files), figures out a dependency graph of **tasks** ("compile this," "download that library," "package this jar"), and runs only the tasks that need re-running based on what changed since last time. Nobody on this project needs Gradle pre-installed: the repo ships `gradlew` / `gradlew.bat` wrapper scripts plus `gradle/wrapper/gradle-wrapper.properties`, which pins the exact version:

```properties
# gradle/wrapper/gradle-wrapper.properties
distributionUrl=https\://services.gradle.org/distributions/gradle-9.6.1-bin.zip
```

Running `./gradlew anything` bootstraps Gradle **9.6.1** automatically on first use. This matters because a mismatched Gradle version between two developers' machines is a classic source of "works on my machine" bugs — pinning it in the repo removes that variable entirely.

## Why One Codebase, Not Two

`settings.gradle` declares the project topology:

```groovy
// settings.gradle
rootProject.name = 'michis-adventure'

include 'core'
include 'desktop'
include 'android'
```

This is the single most important structural decision in the project. It tells Gradle "this is one build made of three modules." The **core** module contains the entire game — every system described in Section 2 — and knows nothing about whether it's running on a Windows desktop or a Samsung phone. **desktop** and **android** are deliberately thin: each is just a *launcher* that boots `core` inside a platform-appropriate window/graphics backend, plus whatever platform-specific glue (BLE, NFC, itch OAuth, self-updating) that platform uniquely supports.

The root `build.gradle` centralizes everything that must stay identical across modules:

```groovy
// build.gradle
allprojects {
    apply plugin: 'eclipse'
    apply plugin: 'idea'

    ext {
        appName       = 'MichisAdventure'
        appJarName    = 'Michi-s-adventure.jar'
        mainClassName = 'desktop.DesktopLauncher'
        gameMainClass = 'main.Main'

        gdxVersion            = '1.13.1'
        gdxControllersVersion = '2.2.4'
    }

    repositories {
        mavenCentral()
        google()
        maven { url 'https://oss.sonatype.org/content/repositories/snapshots/' }
    }
}

ext.loadGameVersion = {
    def props = new Properties()
    def f = file('core/assets/res/build.properties')
    if (f.exists()) { f.withInputStream { props.load(it) } }
    return [version: props.getProperty('version', '2.0'),
            build:   props.getProperty('build', '0')]
}
ext.gameVersion = loadGameVersion()
version = ext.gameVersion.version
```

Two things are worth calling out:

- **`gdxVersion` is declared once** and referenced as `$gdxVersion` from both `desktop/build.gradle` and `android/build.gradle`. Bumping libGDX to a new release is a one-line change here instead of a hunt-and-replace across modules that could easily drift out of sync.
- **`loadGameVersion()`** reads `core/assets/res/build.properties` — the same file the running game reads at runtime to display its own version number, and the same file the patch server's manifest matches against (see Section 3.3). Gradle's reported project version and the in-game "About" screen version can never disagree, because they're the same file.

The `buildscript {}` block pulls in the Android Gradle Plugin (`com.android.tools.build:gradle:9.2.1`), which is what makes `apply plugin: 'com.android.application'` available inside `android/build.gradle` at all — this is Gradle configuring its own tooling before it touches project code.

## `core`: The Shared, Headless Foundation

`core/build.gradle` uses the `java-library` plugin (not `application` — it isn't runnable on its own) and depends on libGDX's **headless** backend:

```groovy
// core/build.gradle (dependency block, paraphrased from research)
api "com.badlogicgames.gdx:gdx:$gdxVersion"
api "com.badlogicgames.gdx:gdx-freetype:$gdxVersion"
api "com.badlogicgames.gdx:gdx-backend-headless:$gdxVersion"
```

This is a deliberate choice, not an oversight: `core` is built against `gdx-backend-headless`, which provides no window, no GPU context, and no input devices. That means `core` can compile and run its entire game-simulation logic — physics, AI, quest state, combat — in an environment with **zero rendering capability**. This single design decision is what makes two very different things possible later: (1) the multiplayer server can run the exact same gameplay code as an authoritative referee (Section 3.2) without a display, and (2) neither `desktop` nor `android` has to reimplement any game logic — they only add a *rendering backend* on top.

`desktop` and `android` each layer a real backend on top of the shared `core` output:

```groovy
// desktop/build.gradle
implementation project(':core')
implementation "com.badlogicgames.gdx:gdx-backend-lwjgl3:$gdxVersion"
implementation "com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-desktop"

// android/build.gradle
implementation project(':core')
implementation "com.badlogicgames.gdx:gdx-backend-android:$gdxVersion"
natives "com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-arm64-v8a"
```

`implementation project(':core')` is not a Maven download — it's Gradle wiring one sub-project's compiled classes directly into another sub-project's classpath, within the same build. This is the literal mechanism of code sharing: `desktop` and `android` both get the entire game for free, and only need to supply a `gdx-backend-*` implementation (LWJGL3 windowing/OpenGL for desktop, the Android GL/lifecycle backend for mobile) plus native binaries matched to their platform (`natives-desktop` vs. `natives-arm64-v8a`).

## Desktop Build: LWJGL3, the Fat Jar, and Native Packaging

The desktop launcher is a small `main()`:

```java
// desktop/src/main/java/desktop/DesktopLauncher.java
public class DesktopLauncher {
    public static void main(String[] args) {
        for (String a : args) {
            if ("--version".equals(a)) { /* print version, return */ }
        }
        if (!DesktopBootstrap.bootstrap()) {
            return;
        }
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("Michi's Adventure");
        config.setWindowedMode(MichiGame.BASE_W, MichiGame.BASE_H);
        new Lwjgl3Application(new MichiGame(), config);
    }
}
```

`DesktopBootstrap.bootstrap()` runs **before** any window opens. It calls `UpdateClient.checkAndApply()` (the patch-server update check, Section 3.3) and registers the desktop implementation of the itch OAuth interface: `platform.ItchAuthProvider.set(new DesktopItchAuth())`. Only after both of those succeed does the LWJGL3 window get created and `MichiGame` (the shared `core` entry point, Section 2.2) starts running.

`desktop/build.gradle` overrides the default `jar` task to produce a self-contained runnable **fat jar**:

```groovy
// desktop/build.gradle (paraphrased)
jar {
    archiveFileName = appJarName          // "Michi-s-adventure.jar"
    manifest { attributes 'Main-Class': mainClassName }
    from { configurations.runtimeClasspath.collect { it.isDirectory() ? it : zipTree(it) } }
    exclude 'META-INF/*.SF', 'META-INF/*.DSA', 'META-INF/*.RSA'
}
apply from: rootProject.file('gradle/packaging.gradle')
```

A fat jar bundles every dependency's `.class` files into one archive, so the game can be distributed as a single file without asking the player to also manage a classpath of separate library jars. The `exclude` lines strip leftover code-signing manifests from bundled dependency jars — those signatures were computed against the original jar's contents and become invalid (and can even crash the JVM's verifier) once merged into a different archive.

`gradle/packaging.gradle` (applied at the bottom of `desktop/build.gradle`) then chains three more tasks to go from "runnable jar" to "installer a non-technical player can double-click":

1. **`jpackageImage`** — invokes the JDK's own `jpackage` tool to bundle the fat jar together with a private copy of the Java runtime into a native Windows app-image. Players never need Java installed separately. It stages a clean input directory first specifically to avoid a real bug the packaging script's own comments describe: pointing `jpackage --input` directly at the `deploy/` output folder caused every build to re-bundle the *previous* build's installer output inside the new one, so the installer size grew without bound across builds.
2. **`innoInstaller`** — runs Inno Setup's `ISCC.exe` compiler against `build_tools/setup_init.iss` to produce a friendly `MichiGame_Setup.exe` installer (per-user install, no admin rights required — `PrivilegesRequired=lowest`).
3. **`installer`** — the umbrella task chaining `jar → jpackageImage → innoInstaller`.

Both `jpackage` and `ISCC.exe` are checked for existence and the build **warns rather than fails** if either is missing — `:desktop:jar` alone still works on a machine that only has a JDK, without Inno Setup installed. Note also that the resulting desktop `.exe` is **not code-signed** (no Authenticode certificate appears anywhere in the packaging script or the Inno Setup script), which means Windows SmartScreen will likely show an "unrecognized publisher" warning on first run — a known, accepted tradeoff rather than a bug.

## Mobile (Android) Build: How It Diverges From Desktop

`android/build.gradle` targets recent, high-end hardware deliberately, not the broadest possible device range:

```groovy
// android/build.gradle
android {
    namespace 'com.michi.adventure'
    compileSdk 34
    defaultConfig {
        minSdk 26
        targetSdk 34
        versionName project.version.toString()   // same value core/desktop use
        ndk { abiFilters 'arm64-v8a' }
    }
    splits { abi { enable false } }
}
```

`minSdk 26` (Android 8.0) and a single `arm64-v8a` ABI filter are explained directly in the build file's own header comment: the game targets Samsung Galaxy S24/S25-class hardware specifically, so there is no need to also ship 32-bit or x86 native libraries for older/low-end devices. This keeps the APK smaller and the native-library matrix simpler at the cost of not supporting very old phones — an intentional scope decision, not an oversight.

### Where Android's Module Layout Actually Diverges From `core`

```groovy
// android/build.gradle
sourceSets {
    main {
        manifest.srcFile 'AndroidManifest.xml'
        java.srcDirs = ['src/main/java/androidlauncher']
        res.srcDirs = ['res']
        jniLibs.srcDirs = ['libs']
        assets.srcDirs = ['assets', '../core/assets']
    }
}
```

- **`java.srcDirs`** restricts Android's own Java source to `android/src/main/java/androidlauncher` — this directory contains *only* Android-unique glue code (BLE GATT server/client, NFC HCE services, the `AndroidLauncher` entry point). It never contains gameplay code; gameplay lives entirely in `core`.
- **`res.srcDirs = ['res']`** points at Android's structured resource system (`android/res/` — drawables, launcher icon mipmaps, `values/strings.xml`, NFC service-descriptor XML like `apduservice.xml`). This is a completely different mechanism from `core/assets/res/`, which is just a folder of game asset files (sprites, tilemaps, JSON, fonts) read via libGDX's own classpath-based file API (`Gdx.files.internal(...)`) — not compiled by Android's resource compiler at all.
- **`assets.srcDirs = ['assets', '../core/assets']`** is the actual mechanism of asset sharing: Android's asset packager copies `core/assets/**` verbatim into the APK's `assets/` folder, preserving the exact on-disk `res/...` layout the game code expects. Because of this, the same `Gdx.files.internal("res/...")` calls in `core` work unmodified whether they're resolving against the desktop jar's classpath or the Android APK's asset folder — no asset-loading code needs a platform-specific branch.

Android also needs a hand-written task to work around a packaging quirk of libGDX's native libraries:

```groovy
// android/build.gradle (paraphrased)
configurations { natives }
dependencies {
    natives "com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-arm64-v8a"
    natives "com.badlogicgames.gdx:gdx-freetype-platform:$gdxVersion:natives-arm64-v8a"
}
task copyAndroidNatives { /* unzips natives-*.jar contents into libs/arm64-v8a/*.so */ }
tasks.matching { it.name.contains('merge') && it.name.contains('JniLibFolders') }
     .configureEach { dependsOn copyAndroidNatives }
```

libGDX ships its native `.so` files at the root of a jar, but Android's `jniLibs` mechanism expects them laid out per-ABI (`libs/arm64-v8a/*.so`). `copyAndroidNatives` reshapes them into the layout Android expects, and is wired to run automatically before any `merge*JniLibFolders*` task via a name-matching hook, since it isn't auto-generated the way it would be by libGDX's own `gdx-setup` scaffolding tool.

### Android Manifest: Permissions and Why Each One Exists

```xml
<!-- android/AndroidManifest.xml -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.NFC" />
<uses-feature android:name="android.hardware.nfc.hce" android:required="false" />

<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-feature android:name="android.hardware.bluetooth_le" android:required="false" />
```

- **`INTERNET`** — required for cloud saves, license activation, and TCP multiplayer, mirroring exactly what the desktop client needs.
- **`NFC` + `nfc.hce`** (Host Card Emulation) — powers the "tap phones together to add a friend or invite to a BLE session" flow. An NFC tap hands off a BLE host's address so a guest can join without any manual pairing UI.
- **`BLUETOOTH_ADVERTISE` / `_CONNECT` / `_SCAN`** — the three "dangerous" runtime permissions Android 12+ (API 31+) requires for local BLE multiplayer. These are requested lazily, only when a player actually starts hosting or joining a BLE session (via `androidlauncher.ble.BlePermissions`), not blanket-requested at app launch — minimizing the permission prompt a new player sees before they even know what the feature is for.
- **`usesPermissionFlags="neverForLocation"`** on the scan permission is a specific, deliberate assertion: this app never derives a player's physical location from BLE scan results (it only ever connects to a specific address obtained via an NFC tap). Declaring that lets the app skip requesting the much broader `ACCESS_FINE_LOCATION` permission that BLE scanning historically implied on older Android versions.

`BlePermissions.requestOnFirstBootIfNeeded()` fires the OS permission dialog exactly once, tracked via `SharedPreferences`, so a returning player is never re-prompted.

### The Ports-and-Adapters Pattern: How Platform Code Actually Plugs In

`core/src/main/java/platform/` defines interfaces with zero platform-specific imports, so `core` compiles identically whether it will run headless, on desktop, or on Android:

| Interface | Purpose | Desktop implementation | Android implementation |
|---|---|---|---|
| `ItchAuthProvider` | itch.io purchase-proof OAuth | `desktop.itch.DesktopItchAuth` | *(not implemented — unavailable on mobile)* |
| `NfcFriendService` | NFC tap-to-add-friend | *(not implemented — no NFC hardware)* | `androidlauncher.nfc.NfcFriendServiceImpl` |
| `BleHostService` / `BleGuestService` | Local BLE multiplayer | *(not implemented)* | `androidlauncher.ble.BleHostServiceImpl` / `BleGuestServiceImpl` |
| `LicenseActivation` | Cloud license activation/login | shared — identical class on every backend | shared |

Each launcher registers its implementations once, at startup:

```java
// android/src/main/java/androidlauncher/AndroidLauncher.java (paraphrased)
NfcFriend.set(new NfcFriendServiceImpl(this));
BleMultiplayer.setHost(new BleHostServiceImpl(this));
BleMultiplayer.setGuest(new BleGuestServiceImpl(this));
```

`core` never imports a platform-specific class directly — it only ever calls through the interface/facade (`platform.BleMultiplayer.startHosting(...)`, `platform.ItchAuthProvider.tokenOrNull()`), and every facade method is null-safe when nothing was registered (`BleMultiplayer.isHostingSupported()` simply returns `false` on desktop, since desktop never calls `setHost`). This is why `desktop`'s source tree has no `ble`/`nfc` packages and `android`'s has no `itch`/`update` packages — the split isn't arbitrary, it tracks exactly which capabilities each OS actually exposes: desktop uniquely has `java.awt.Desktop` (for browser-based OAuth) and a loopback `HttpServer` (to catch the OAuth redirect) plus self-updating via a spawned second JVM; Android uniquely has NFC HCE and BLE GATT client/server APIs.

`LicenseActivation` is a deliberate counter-example, and its own class documentation explains why: it used to require per-platform machine-fingerprinting code, but was unified into `core` once licensing moved fully server-side (Section 4) — the client now just runs a socket handshake identical on every backend, so there was no longer any reason to keep it platform-specific.

## Android Signing vs. Desktop Packaging: Two Different Keys, Two Different Purposes

It is easy to conflate "code signing" across platforms, but this project uses two entirely separate mechanisms:

```groovy
// android/build.gradle
def releaseKeystoreFile = rootProject.file('build_tools/keystore/michis-adventure-release.jks')
def releaseKeystorePasswordFile = rootProject.file('build_tools/keystore/michis-adventure-release.jks.passwd.txt')
if (releaseKeystoreFile.exists() && releaseKeystorePasswordFile.exists()) {
    def releaseKeystorePassword = releaseKeystorePasswordFile.text.trim()
    signingConfigs {
        release {
            storeFile releaseKeystoreFile
            storePassword releaseKeystorePassword
            keyAlias 'michis-adventure'
            keyPassword releaseKeystorePassword
        }
    }
    buildTypes { release { signingConfig signingConfigs.release } }
}
```

This keystore (`build_tools/keystore/michis-adventure-release.jks`) exists for exactly one purpose: **Android APK release signing.** Android ties an app's identity and updatability to the key that signed it, not just its package name — the build file's own comment states this plainly: every future release build must reuse this same key, or Android will refuse to treat a new APK as an update to a copy players already installed. Losing this keystore would mean every existing player would have to uninstall and reinstall to get any future update.

The desktop `.exe` built by `jpackage`/Inno Setup has **no equivalent** — no Authenticode certificate is referenced anywhere in `packaging.gradle` or `setup_init.iss`. Desktop distribution and Android distribution are handled by unrelated mechanisms: Android has cryptographic release signing baked into the OS's install/update model; desktop does not, and ships unsigned.

## itch.io Distribution: What Actually Happens

There is **no `.itch.toml` file and no `butler` (itch's CLI push tool) anywhere in this repository** — this project does not automate pushing builds to itch.io from Gradle or CI. The game is uploaded to itch.io manually, outside of any script in this repo. itch.io's *only* role at runtime is as a one-time purchase-verification OAuth provider — proving a given player actually bought the game — which is covered in full in Section 4. There is also no CI/CD pipeline at all: no `.github` directory, no workflow YAML files anywhere except the servers' own `docker-compose.yml`. Every packaging step described above (jpackage, Inno Setup, Android signing) is run manually by the developer.

---

# 2. The Client (Core Game Module)

Everything in this section lives under `core/src/main/java`, is compiled once, and runs — with the platform differences described in Section 1 — on both desktop and Android.

## 2.1 Package Structure

```
core/src/main/java/
├── ai/            A* pathfinding for monster/NPC movement
├── audio/         Gdx.audio wrapper
├── data/          SaveLoad, CloudSaveService, GameState, Item/Monster/NPC factories
├── entity/        Entity base class, Player, bosses, NPCs, projectiles, particles
├── environment/   Lightning (shadow/lighting compositor), weather/atmosphere layers
├── gfx/           GdxRenderer (the Graphics2D-shaped facade), shaders, geometry
├── main/          MichiGame (entry point), GamePanel (game state), networking, input, config
├── map/           TMX map loading, event handling, mob spawning
├── mobile/        Touch controls / gamepad adapter overlays
├── object/        World objects — chests, doors, keys, potions, torches
├── platform/      The ports-and-adapters interfaces described in Section 1
├── server/        The headless authoritative-engine entry point (Section 3.2)
├── tile/          Tile rendering and interactive tiles
├── ui/            RenderPipeline, menus, minimap, cutscenes
└── util/          Object pooling, the resource/asset cache
```

The `server/` package deserves a special note: it lets the **exact same `GamePanel` simulation** that runs the single-player game also run headlessly as the multiplayer server's authoritative referee. GL-only systems (the shader pipeline, minimap baking, the render pipeline) are simply skipped when `Headless.isEnabled()` — the game logic underneath doesn't know or care whether it's being rendered.

## 2.2 The Main Game Loop

`main/MichiGame.java` is the libGDX entry point:

```java
// main/MichiGame.java
public class MichiGame extends ApplicationAdapter {
    public static final int BASE_W = 1280;
    public static final int BASE_H = 720;

    public void create() {
        util.ResourceCache.resetImages();   // see 2.8 — GL context-loss safety
        LicenseActivation.ensureActivated(); // background thread, non-blocking
        // build OrthographicCamera, FontSystem, GdxRenderer, GamePanel...
        gp.setupGame();
    }
}
```

`syncCamera()` implements integer pixel scaling: `pixelScale = floor(min(deviceWidth/1280, deviceHeight/720))`. Pixel art is always drawn at a whole-number magnification — never fractionally scaled, which would blur it — and any leftover fractional screen space simply becomes extra visible tiles at the edges rather than black letterboxing.

`render()` runs every frame:

```java
public void render() {
    // clear to the map's background color
    // poll F5–F10 lighting debug toggles
    gp.stepUpdates(Gdx.graphics.getDeltaTime());
    renderer.begin(...);
    gp.draw(renderer);
    renderer.end();
}
```

`GamePanel.stepUpdates()` is a classic fixed-timestep accumulator:

```java
// main/GamePanel.java
private static final int TARGET_UPS = 60;

public void stepUpdates(float deltaSeconds) {
    final double updateInterval = 1.0 / TARGET_UPS;
    updateAccumulator += deltaSeconds;
    if (updateAccumulator > 5 * updateInterval) updateAccumulator = 5 * updateInterval;
    while (updateAccumulator >= updateInterval) {
        update();
        updateAccumulator -= updateInterval;
    }
}
```

Decoupling simulation rate (fixed at 60 updates/second) from rendering rate (whatever the display supports) means gameplay behaves identically regardless of frame rate. The `5 * updateInterval` cap on the accumulator exists specifically to prevent a "spiral of death": without it, a long pause (a debugger breakpoint, a big garbage-collection stall) would leave the accumulator holding a huge backlog, and the very next frame would try to run hundreds of `update()` calls to catch up — which takes even longer, falls further behind, and the game never recovers. Capping the backlog means the game simply appears to briefly slow down after a stall, instead of locking up trying to catch up to real time.

## 2.3 Rendering: The Graphics2D-Shaped Facade

`gfx/GdxRenderer.java` exists to make the historical migration from Java's `Graphics2D` (the old Swing-based renderer) to libGDX **mechanical rather than a rewrite**. Its own class documentation states the goal directly:

> "Graphics2D-shaped rendering facade backed by libGDX. The ~1570 `g2.draw*` call sites across the game become `r.draw*` with the SAME method names/signatures, so the port is mechanical and behavior-preserving."

This is why the facade pattern exists at all: rather than touching 1,570 call sites across the whole game to learn a new rendering API, every call site kept its exact shape, and only the renderer underneath it changed. Key mechanics:

- **Automatic batch switching** — libGDX's `SpriteBatch` (textured quads) and `ShapeRenderer` (fills/lines) can't be active simultaneously; the facade transparently flushes whichever is active and switches, so calling code never manages GL state itself.
- **Custom blend modes** replace `java.awt.AlphaComposite` rules: additive blending for glow effects, DST_OUT for "punching holes" into the darkness overlay (see 2.4).
- **Offscreen framebuffers (FBOs)** back the multi-pass lighting/shadow/bloom pipeline: one for the darkness mask, one for shadow-caster silhouettes, one for scene capture used by bloom.
- **`beginDeviceSpace()` / `endDeviceSpace()`** temporarily swap to a 1:1, unmagnified device-pixel projection specifically so debug text and HUD elements stay crisp rather than being blown up (and blurred) by the same integer `pixelScale` used for game-world pixel art.

## 2.4 The Lighting, Shadow, and Bloom Pipeline

This is a GLSL shader pipeline, tiered by a `graphicsQuality` setting so it degrades gracefully on weaker hardware, and falls back completely if shader compilation fails on a given GPU/driver.

```java
// main/Config.java
public static final int GRAPHICS_LOW = 0, GRAPHICS_MEDIUM = 1, GRAPHICS_HIGH = 2;
```

`gfx/shader/ShaderPipeline.java`'s own documentation explains the safety guarantee:

> "every shader is compiled at construction and, if ANY of them fails to compile or link on the current GPU/driver, `isAvailable()` returns false and callers fall back to the legacy baked-texture lighting path... No exceptions escape; the game never crashes because of a shader."

The three tiers:

- **LOW** — no shader pipeline at all. `environment/Lightning.java` uses a breadth-first-search tile flood-fill to determine which tiles are lit, and punches "holes" for light sources into a baked darkness texture using the DST_OUT blend mode described above.
- **MEDIUM** — uses the shader path, but compiled with a cheap shadow variant (`#define SHADOW_STEPS 12`, `#define CHEAP 1`) — a 12-step shadow ray-march with no organic noise detail.
- **HIGH** — the full pipeline: a 32-step shadow ray-march, a scene-capture bloom pass, color grading, and per-sprite rim lighting.

`Lightning.draw()` chooses between the shader path and the legacy path per frame:

```java
// environment/Lightning.java
boolean isLow = (gp.config.graphicsQuality == Config.GRAPHICS_LOW);
boolean shaderLit = false;
if (gp.config.graphicsQuality != Config.GRAPHICS_LOW) {
    shaderLit = drawShaderLighting(g2, currentMaxDarkness, ...);
}
if (!shaderLit) {
    // legacy baked DST_OUT darkness-hole path — used on LOW, or if the shader failed to compile
}
```

Shadows are cast against a silhouette **occluder mask**: every on-screen tile sitting on a solid (collision) grid cell, plus any NPC/monster/object/interactive-tile flagged `castsShadow()`, is drawn as black silhouette into an offscreen buffer, and the light shader ray-marches through that mask per pixel to determine what's lit. The player's own sprite is deliberately excluded from this mask — if it weren't, the player's own light (centered on their position) would cast a shadow of the player onto themselves, producing a visible starburst artifact radiating from their own feet.

A performance detail worth noting: `lightGridSignature` hashes every active light's tile position, radius, and quality flags every frame. If that hash is unchanged from the previous frame, the entire expensive tile-lighting/ray-cast pass is skipped — meaning a player standing still in a menu or dialogue costs essentially nothing for lighting.

## 2.5 Y-Sorting: Why Walls Draw in the Right Order

A recent change (`dc32f54e`) finished Y-sorting for wall tiles — the mechanism is worth explaining because it's a common source of visual bugs in top-down games (a player standing "in front of" a wall incorrectly rendering behind it, or vice versa).

`ui/RenderPipeline.java` sorts entities by their *feet* position, not their sprite origin:

```java
// ui/RenderPipeline.java
Comparator<Entity> renderSorter = (e1, e2) -> {
    int feet1 = e1.worldY + e1.solidArea.y + e1.solidArea.height + e1.depthSortYOffset;
    int feet2 = e2.worldY + e2.solidArea.y + e2.solidArea.height + e2.depthSortYOffset;
    return Integer.compare(feet1, feet2);
};
```

Sorting by feet (the bottom of the collision box) rather than by sprite top-left is what makes "standing in front of" visually correct: an entity whose feet are lower on screen is nearer the camera in a top-down perspective, and should draw on top of anything whose feet are higher up.

The tricky part is that map tiles — walls in particular — are visually taller than a single grid cell, so their top edge overlaps the row of tiles "behind" them. If a wall tile were sorted by its top-left origin, the player could incorrectly draw behind a wall they're actually standing in front of. `tile/TileManager.java` solves this with a per-tile authored property in Tiled (the map editor), not a heuristic:

```java
// tile/TileManager.java
int sortYOff = (gidToSortYOffset != null && gid < gidToSortYOffset.length) ? gidToSortYOffset[gid] : 0;
visibleTile.sortY = worldY + tileSize + sortYOff;
```

`sortYOffset` is a small per-tile pixel nudge, authored directly on the tileset in Tiled, so that a multi-row wall structure has its **top-row** tile's sort key match its **bottom-row** tile's sort key — keeping a tall wall behaving as one coherent depth unit instead of the top half and bottom half sorting independently and splitting the wall visually mid-sprite. `RenderPipeline.drawWorldLayers()` then interleaves the depth-sorted tile list with the depth-sorted entity list in a single merge pass, draining any depth tile whose sort key is at or below the current entity's before drawing that entity — this is what makes a wall correctly draw in front of or behind the player depending on which one is "lower" on screen at that moment.

## 2.6 Networking: TCP Multiplayer Client

`main/MultiplayerClient.java` connects to `SERVERS/multiplayer_server` (Section 3.2) over raw TCP using a hand-rolled, cryptographically authenticated protocol — no external networking library, no WebSocket, no HTTP. The class's own documentation lays out the cipher suite:

> RSA-OAEP-SHA256 license-bound handshake, AES-256-GCM per-frame encryption, HKDF-SHA256 key derivation, per-direction sequence counters bound into the AEAD associated data (anti-replay).

The handshake:

```java
// main/MultiplayerClient.java — performHandshake(), simplified
// 1. HELLO -> server replies with a nonce + its RSA key fingerprint
// 2. LOGIN: RSA-OAEP-encrypted JSON { timestamp, nonces, name, class }
//    plus (activation_id, enc_blob) obtained from platform.LicenseActivation —
//    never the raw license key itself
// 3. AUTH_OK: an AES session key, wrapped via an HKDF-derived delivery key
```

After the handshake, every message is JSON, hand-parsed (no Gson/Jackson at this layer), wrapped inside an encrypted frame:

```
DATA <base64([8-byte big-endian sequence][12-byte nonce][AES-GCM ciphertext])>
AAD = {direction byte, sequence bytes}
```

Binding the sequence counter into the AEAD associated data means a captured frame can't be replayed later (the sequence would be stale and rejected) and frames can't be silently reordered by a network intermediary.

### Client-Side Interpolation, Not Prediction

A subtlety worth being precise about: this system does **remote-entity interpolation**, not local client-side prediction with server reconciliation. The local player moves instantly in response to input; the server only intervenes with a `pos_correction` message if it disagrees with where the client says it is (this is anti-cheat, not lag compensation — see Section 3.2). For *other* players' avatars, though, the client needs to turn periodic position snapshots (every ~50ms, matching the server's broadcast interval) into smooth continuous motion:

```java
// main/MultiplayerClient.java — player_update handling, simplified
long segDurNs = 1_000_000_000L / 20; // 50ms, matches server broadcast interval
float curVxPxNs = hermiteVelocity(rp, nowNs);   // sample the OLD spline's velocity at "now"
rp.spPsX = pos[0]; rp.spPsY = pos[1];           // new segment starts where the old one currently is
rp.spVsX = curVxPxNs;                            // ...with matching velocity (no visible jerk)
rp.spPeX = newX; rp.spVeX = rawVx / segDurNs;    // new segment ends at the freshly received position
```

Each new position update becomes the endpoint of a **Hermite spline** segment, and — critically — the new segment's *starting* velocity is sampled from wherever the *previous* segment's velocity curve currently is, not reset to zero. This is what makes the interpolation C1-continuous (position and velocity both continuous across segment boundaries): without matching the outgoing velocity, every new update would cause a visible "snap" or jerk in a remote player's motion.

## 2.7 Networking: BLE (Bluetooth Low Energy) Multiplayer

`main/BleMultiplayerSession.java`, layered on `platform/Ble{Host,Guest}Service.java`, is a deliberately separate, lightweight alternative to the TCP path — described in its own class documentation as a "license-free invite a friend" mode. Key differences from the TCP protocol:

- **No internet, no account, no license, no encryption** — appropriate for a same-room, physically-tapped-together connection (an NFC tap carries the host's session token and map ID to the guest, then BLE takes over).
- **No map data is transmitted at all.** Since both phones already have identical map files bundled in their own APKs, only the map's *identifier* is sent — contrast this with the TCP path, which streams chunked map data because the server can't assume two arbitrary players have byte-identical assets (see 3.2's map-streaming description).
- **Wire format is compact, pipe-delimited text**, not JSON — Bluetooth LE's maximum transmission unit is tiny, so verbosity has a real cost. Examples: `W|id|mapId|col|row` (welcome), `U|id|x|y|dir|sprite|atk|life|maxLife` (position update), `D|mobId|life|maxLife|dmg` (mob damage).
- The **host device is authoritative** for mob HP/death within a BLE session and relays every applied hit to all connected guests, so hit points stay consistent across every phone in the session even though there's no central server.
- The same Hermite-spline interpolation technique from the TCP path is reused, just with a longer, fixed 150ms segment duration matching BLE's slower update cadence, and zero tangent — which degrades the curve to plain linear interpolation, appropriate given the coarser update rate.

Both transports feed into the same downstream systems: `GamePanel.syncRemotePlayerEntities()` rebuilds a single map of remote players every tick from whichever transport is active (namespaced `"tcp:<id>"` or `"ble:<id>"` so the two can never collide), which is what lets systems like the lighting engine treat a remote player as a normal light-emitting `Entity` regardless of which network layer put them there.

## 2.8 Save/Load and GL Context-Loss Recovery

### Local Save Format

`data/SaveLoad.java` encrypts the local save file with AES-128-CBC and a random 16-byte IV prepended to the ciphertext, written through the platform-agnostic `platform.GameStorage` interface. The serialized format itself is a flat `key=value` text blob — player stats, inventory pairs, per-quest progress, map object states (has this chest been opened?), defeated bosses, story progress, and shop stock — deliberately simple and human-diffable rather than a binary or JSON format.

### GL Context Loss (Android-Specific)

This is the concrete answer to a class of bug that is easy to miss and painful to debug: Android can destroy and recreate the app's GL context — on backgrounding, entering split-screen, or under memory pressure — **without killing the process**. Any GPU texture built the "obvious" way, `new Texture(pixmap)`, is *unmanaged*: libGDX's own automatic context-recovery pass doesn't know it needs re-uploading, so every procedurally generated texture (light falloff gradients, palette-swapped UI panels, the baked minimap image) would silently render as blank or garbage after the context comes back.

```java
// gfx/GdxTextureUtil.java
public final class GdxTextureUtil {
    public static Texture managedFromPixmap(Pixmap pm) {
        return new Texture(new PixmapTextureData(pm, pm.getFormat(), false, false, true));
    }
}
```

The fix is the 5-argument `PixmapTextureData` constructor with `managed = true`, which registers the texture with libGDX's recovery mechanism the same way a file-backed texture is automatically registered — and, just as important, the source `Pixmap` is deliberately **not disposed**, because libGDX's reload hook needs the original pixel data still in memory to re-upload from; disposing it would leave `reload()` touching freed native memory.

The complementary half of this fix handles **process reuse without context reuse** — Android's `singleTask` launch mode can restart the game's UI while reusing the same OS process, meaning static caches survive even though the GL context they refer to is already dead:

```java
// main/MichiGame.java — create()
// Must run first: on Android (singleTask launch mode) a "restart" can reuse the process,
// so ResourceCache's static image caches may still hold Sprites/Textures tied to the
// previous, now-dead GL context.
util.ResourceCache.resetImages();
```

`ResourceCache.resetImages()` clears every cached image reference **without calling `dispose()`** on the underlying textures — and its own comment explains why that omission is intentional, not an oversight:

> Disposing them would just make GL calls against invalid/reused handles... a GL texture ID is just an integer scoped to a context; once that context is destroyed, calling `glDeleteTextures` on a stale ID either no-ops or, worse, deletes an unrelated texture that a fresh context has since recycled onto that same integer ID.

Together, these two mechanisms are the complete strategy: managed `PixmapTextureData` lets libGDX's built-in recovery catch what it can, and the defensive cache flush at every `create()` call catches everything else (including the full process-reuse edge case) by simply discarding stale references and letting every system lazily rebuild its textures from source the next time it needs them.

## 2.9 Cloud Saves

`data/CloudSaveService.save(gameState, licenseKey, ...)` uploads the same `GameState` to `SERVERS/save_server` using the identical RSA-OAEP + AES-256-GCM + HKDF handshake pattern as the multiplayer client (Section 3.1 has the full cryptographic detail — client and server implement the same protocol independently, in Java and Python respectively). A few client-side behaviors are worth calling out:

- **Server discovery** reads `save_servers.txt` (one `host[:port]` per line, `#` for comments) and falls back to a hardcoded list if that file is missing, trying each candidate with a `PING` until one responds.
- **Offline resilience**: if no save server is reachable at all, the game state is still encrypted — using a key derived from `(license_key, machine_fingerprint)` instead of a server-issued key — and cached locally to `local_save.dat`. It uploads automatically the next time a server becomes reachable.
- **Conflict resolution on load** compares timestamps: a cloud save is only applied if its embedded timestamp is at or after the local save's timestamp, so a stale cloud copy can never silently overwrite newer local progress.
- **Threading discipline** is explicit in the code comments: the network fetch itself is safe to run off the render thread, but applying the fetched state must happen on the render thread, because doing so rebuilds live entities and re-bakes the minimap texture — both of which require a live GL context.

## 2.10 Quest System

Quests are entirely data-driven, defined in `/res/data/quests.json` and interpreted by `main/QuestManager.java`:

```json
{
  "id": "help_soldier",
  "name": "Help the Wounded Soldier",
  "steps": [
    { "action": "talk", "npc": "soldier", "dialogue": "intro", "give": "wooden_sword" },
    { "action": "deliver", "npc": "soldier", "item": "bandage", "consume": true,
      "dialogue": "thanks", "failDialogue": "waiting" }
  ],
  "rewardCoins": 25, "chainQuestId": "meet_soldier_later"
}
```

`QuestManager`'s own documentation states the guiding design principle directly: **"Quests define WHAT HAPPENS; NPCs define WHO SAYS WHAT"** — quest logic and dialogue content are kept in separate files so either can change without touching the other. Step actions include `talk` (auto-advances after dialogue, can grant an item), `deliver` (requires and optionally consumes an item), and counter-based actions like `collect`/`kill`/`go`.

In multiplayer, quest progress is still simulated **client-side** even though combat and economy are server-authoritative (Section 3.2) — the client periodically reports its quest-completion map, defeated bosses, and story progress to the server via a `progress_sync` message so server-hosted NPCs can gate dialogue and shop availability on it. This is a known, explicitly-flagged interim trust boundary: the code comments describe it as the server "taking the client's word for it" on story state, with moving quest/XP tracking fully server-side named as a deliberate next step — worth understanding as a design-in-progress rather than a settled architecture.

## 2.11 Patch/Update Checking

Update-checking is **not** part of `core` — it lives entirely in the `desktop` module, because self-updating a running application is inherently platform-specific (Android apps update through the Play Store or manual APK install, not by rewriting their own installed files). `desktop/src/main/java/desktop/update/Updater.java` is a small external-process patch applier, spawned as a **separate JVM** by the running game specifically because a program can't safely overwrite its own jar file while it's still running:

```
java -cp game.jar desktop.update.Updater  game.jar  patch.zip  newVersion  parentPid
```

`Updater` waits for the original game process to fully exit (`ProcessHandle.onExit()`), then applies the downloaded patch (an add/replace/delete file-entry set) atomically over the jar, and relaunches the game. It never touches files outside the jar itself — license data, server lists, and save files are left completely alone. The full protocol this talks to is described in Section 3.3.

---

# 3. The Servers

All three servers live under `SERVERS/` and are independent Python processes sharing one Docker image, deployed together via a single `docker-compose.yml`. All are stdlib-heavy — the only third-party dependency across all three is the `cryptography` library:

```
# SERVERS/requirements.txt
cryptography>=42,<46
```

## Port and Deployment Overview

| Port | Service | Protocol | Publicly exposed? |
|---|---|---|---|
| 5005 | save_server | TCP, custom RSA+AES-GCM protocol | Yes |
| 5105 | save_server internal license API | TCP, shared-secret gated | No — Docker-internal only |
| 5006 | patch_server | TCP, custom text/binary protocol | Yes |
| 7777 | multiplayer_server | TCP (asyncio), custom RSA+AES-GCM protocol | Yes |
| 8888 | multiplayer_server admin dashboard | HTTP | No — loopback only, reached via SSH tunnel |

```yaml
# SERVERS/docker-compose.yml (structure)
networks: { michi: {} }
services:
  save:         { ports: ["5005:5005"] }               # 5105 intentionally NOT published
  patch:        { ports: ["5006:5006"] }
  multiplayer:  { ports: ["7777:7777", "127.0.0.1:8888:8888"], depends_on: [save] }
```

The multiplayer server reaches the save server by its Docker Compose service name (`save`), never `127.0.0.1` — this is what lets the internal license-verification port stay unpublished to the outside world while still being reachable container-to-container.

## 3.1 `save_server`: Cloud Saves and License Issuance

**Runtime**: Python 3, plain `socket` + `sqlite3` + `threading` (no web framework at all — this is not Flask or FastAPI; it is two hand-written raw TCP servers in one process). Entry point is `server.py` (~1,555 lines).

### Startup and Two Listening Sockets

```python
# save_server/server.py — serve_forever(), simplified
def serve_forever():
    cfg = load_config()
    init_db()                          # SQLite, WAL mode
    load_private_key()
    threading.Thread(target=serve_internal, daemon=True).start()  # port 5105
    # main accept loop on cfg["port"] (5005)
```

Two sockets, two audiences:
- **Port 5005 (public)** — game clients: `PING`, the `HELLO`/`ACTIVATE`/`LOGIN` handshake, then encrypted save upload/download frames.
- **Port 5105 (internal only, never published in Docker Compose)** — a small API used exclusively by `multiplayer_server`: `CLAIM_USERNAME`, `CHECK_USERNAME`, `VERIFY_ACTIVATION`, `PING_LINK`. As a defense-in-depth measure, the public port explicitly refuses any line beginning with `"INTERNAL "`, so even a client that somehow learns the internal command syntax cannot invoke it through the public port.

Each accepted connection runs on its own thread, gated by a `threading.BoundedSemaphore` (concurrency cap) and a per-IP token-bucket rate limiter.

### The Cryptography, In Full

This is the most detail-dense part of the entire system, and it's worth walking through carefully because it's the mechanism that makes offline play, cloud saves, and license portability all work together safely.

**Algorithms used:** RSA-2048 with OAEP padding (SHA-256) for the handshake envelope; HKDF-SHA256 for deriving symmetric keys from shared secrets; AES-256-GCM (an AEAD cipher — Authenticated Encryption with Associated Data) for both the session key delivery and all subsequent traffic. All come from Python's `cryptography` library.

Why this combination, conceptually: RSA is slow and size-limited, but doesn't require a pre-shared secret — perfect for a one-time handshake between two parties who've never talked before. AES-GCM is fast and unlimited in size, but needs a shared key — perfect for the bulk of the session once a key exists. HKDF is the standard way to turn "some shared secret material" (here, nonces and the license key itself) into a properly-sized, cryptographically independent AES key. This is the same overall shape as TLS, hand-rolled at a much smaller scale because the two endpoints (this specific client, this specific server) are known in advance and the game only needs to protect its own traffic, not be a general-purpose secure channel.

**First-ever run (no local credentials yet) — `ACTIVATE`:**

```
C -> "HELLO v2 <base64(client_nonce_16)>"
S -> "OK <base64(server_nonce_16)>"
C -> "ACTIVATE <base64(rsa_oaep_sha256(handshake_json))>"
     handshake_json = { "ts", "client_nonce", "server_nonce", ... itch token, see Section 4 }
S -> "AUTH_OK <base64(aesgcm(session_key,...))> <activation_id>
      <base64(nonce||aesgcm(license_key, key=enc_key, aad='MichiLicenseBlob'))>
      <base64(aesgcm(license_key, key=delivery_key, aad='MichiIssuedLicense'))>"
   | "AUTH_FAIL"
```

**Every later run — `LOGIN`** (the client now holds `activation_id` and an encrypted blob from the first run, but critically does **not** hold the plaintext license key on disk after the very first response):

```
C -> "HELLO v2 <base64(client_nonce_16)>"
S -> "OK <base64(server_nonce_16)>"
C -> "LOGIN <base64(rsa_oaep_sha256(handshake_json))> <activation_id> <base64(enc_blob)>"
S -> "AUTH_OK <base64(aesgcm(session_key, key=delivery_key, ...))> ..."
   | "AUTH_FAIL"
```

**Every session frame after the handshake:**

```
wire = "DATA <base64(seq_8 || nonce_12 || ciphertext || tag_16)>"
AAD  = direction_byte (0x01 server→client, 0x02 client→server) || seq_8_big_endian
```

The core primitives:

```python
# save_server/server.py
def hkdf(secret: bytes, salt: bytes, info: bytes, length: int = 32) -> bytes:
    return HKDF(algorithm=hashes.SHA256(), length=length, salt=salt, info=info).derive(secret)

def aesgcm_encrypt(plaintext: bytes, key: bytes, nonce: bytes, aad: bytes) -> bytes:
    if len(nonce) != 12:
        raise ValueError("AES-GCM nonce must be 12 bytes")
    return AESGCM(key).encrypt(nonce, plaintext, aad)
```

The key-derivation step that happens on every successful `ACTIVATE`/`LOGIN` is the crux of the whole scheme:

```python
# save_server/server.py — simplified
delivery_key = hkdf(
    secret=license_key.encode("utf-8") + b"michi-license-pepper-v2",
    salt=server_nonce,
    info=b"michi-delivery-v2",
    length=32,
)
session_key = os.urandom(32)
enc_session = aesgcm_encrypt(session_key, key=delivery_key, nonce=client_nonce[:12], aad=b"MichiCloudSession")
```

Notice that `delivery_key` is derived **from the license key itself**, salted with a hardcoded "pepper" string and the server's per-handshake nonce. This is deliberate and elegant: the server never has to store a session key anywhere, and — more importantly — a client that does not actually hold a valid license key cannot derive the correct `delivery_key`, and therefore cannot decrypt its own session key even if it somehow captured the `AUTH_OK` response. Possession of the license key is what unlocks the session, cryptographically, not just a database lookup.

**License key format:**

```python
# save_server/server.py
_LICENSE_CHARSET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

def _random_license_key() -> str:
    prefix = "".join(secrets.choice(_LICENSE_CHARSET) for _ in range(8))
    suffix = "".join(secrets.choice(_LICENSE_CHARSET) for _ in range(4))
    return f"{prefix}-{suffix}"     # e.g. MICHI001-2DF3
```

`secrets.choice` (not `random.choice`) is used specifically because it's cryptographically secure — a license key needs to be unguessable, not just statistically well-distributed.

Each issued license also gets a random 32-byte `enc_key`, generated and held **only on the server**, used to encrypt the plaintext license key into the `enc_blob` the client persists to disk as `activation.dat`. This is the reason the client only ever stores `activation_id` + an encrypted blob after the first run, never the plaintext key: if a player's disk were compromised, the stolen file is useless without the server-side `enc_key` to decrypt it back into a usable license string.

### Database Schema

SQLite, WAL mode, `saves.db`:

```sql
saves(license_key PK, save_data BLOB, game_timestamp, size_bytes, updated_at)
licenses(license_key PK, activation_id UNIQUE, enc_key_b64, created_at, itch_user_id)
events(id, ts, client_ip, license_key, status)      -- audit log of every connection outcome
usernames(username PK, license_key UNIQUE, claimed_at, friend_id UNIQUE)
friends(license_key_a, license_key_b, requester_key, status, created_at)
```

`licenses.itch_user_id` carries a **partial unique index** (`WHERE itch_user_id IS NOT NULL`), which enforces "at most one license per itch.io purchaser" even under a race condition where two activation attempts for the same itch account arrive at nearly the same instant — the database itself rejects the second one rather than relying on application-level locking to catch it.

`usernames.friend_id` is a short, separately-random opaque token (`secrets.token_urlsafe(9)`), deliberately distinct from both the license key and the username — used for NFC add-friend taps specifically so that a sniffed NFC payload only ever leaks a friend-request token, never anything that resolves back to a player's license.

### Replay and Tamper Protections

- `NonceCache` (5-minute TTL) rejects any client nonce it has already seen, preventing a captured handshake from being replayed later.
- The handshake's embedded timestamp must be within ±60 seconds of the server's clock, bounding how long a captured-but-not-yet-decrypted handshake could remain useful even before nonce reuse is checked.
- All nonce and API-key comparisons use `hmac.compare_digest`, a constant-time comparison — an ordinary `==` string comparison can leak timing information about *how many leading bytes matched*, which is a real (if slow) side channel for guessing a secret byte-by-byte; constant-time comparison closes that off.
- Session frames carry a strict monotonic sequence counter baked into the AEAD associated data, so a captured frame can't be replayed or reordered without failing authentication.

### Save Conflict Resolution

```python
# save_server/server.py — simplified
if server_save.timestamp > incoming_save.timestamp:
    reply({"status": "SYNC", "data": server_save})   # tell the client to pull, don't accept the upload
else:
    store(incoming_save)
```

Last-write-wins by an embedded timestamp inside the save data itself (not the upload's arrival time) — this is what stops an offline device with stale progress from clobbering newer progress that was already synced from elsewhere.

## 3.2 `multiplayer_server`: Real-Time Multiplayer With Server Authority

**Runtime**: Python 3 `asyncio` (unlike the thread-per-connection model of the other two servers), because the multiplayer server needs to run a continuous game tick loop alongside potentially many simultaneous connections. It also spawns a **Java subprocess** — `engine.jar`, built by the `:core:engineJar` Gradle task described in Section 1 — to get authoritative rulings on combat using the exact same gameplay code the client itself runs.

### Why a Java Subprocess

The entire point of `core` being buildable against `gdx-backend-headless` (Section 1) is realized here: `engine.jar` runs the real `GamePanel` simulation with no window and no GPU, which means combat math (damage formulas, mob AI reactions, XP rewards) never has to be reimplemented in Python and kept in sync with the Java version by hand. The bridge is a small, deliberately simple protocol:

```python
# multiplayer_server/game_engine.py — simplified
class GameEngine:
    async def launch(self):
        self.proc = await asyncio.create_subprocess_exec(
            "java", "-jar", str(jar_path),
            stdin=PIPE, stdout=PIPE, stderr=PIPE,
        )
        # wait up to 30s for a {"event": "ready"} line on stdout

    async def mob_hit(self, mob_id, mob_type, damage, player_id):
        # send {"rid": N, "cmd": "mob_hit", ...} as one line of JSON on stdin
        # await the matching {"rid": N, ...} response on stdout, up to 2.0s
        # returns None on timeout, crash, or missing jar — never raises
```

Newline-delimited JSON over stdin/stdout needs no encryption or authentication of its own — it's a private pipe between a parent and its own child process, not exposed to the network. The critical design property is that **the engine is an authority upgrade, never a hard dependency**: if `engine.jar` is missing, Java isn't installed, or a call times out, `self.engine.available` becomes `False` and the server falls back to trusting the client's own damage claims rather than refusing to run. This means a broken or absent Java installation degrades multiplayer security, but never takes the server down.

### Handshake

Structurally identical in shape to `save_server`'s (same RSA-OAEP → HKDF → AES-GCM pattern), but with one important difference: **the multiplayer server holds no license database of its own.**

```python
# multiplayer_server/server.py — simplified
license_key = await self._save_server_verify_activation(activation_id, enc_blob)
if license_key is None:
    send("LICENSE_SERVER_UNAVAILABLE")   # distinct from AUTH_FAIL — infra outage, not a bad license
    return
```

It forwards `(activation_id, enc_blob)` to `save_server`'s internal port (5105, using the shared `MICHI_INTERNAL_API_KEY` secret) and trusts whatever `license_key` comes back. Distinguishing `LICENSE_SERVER_UNAVAILABLE` from `AUTH_FAIL` matters operationally: a player whose login fails because the save server is briefly down should see "try again shortly," not "your license appears invalid" — conflating the two would generate confused bug reports and support requests for what is really just an infrastructure hiccup.

### Server Authority: The Core of the `server-authority` Merge

The git history shows a deliberate, staged migration of trust away from the client. The guiding principle is stated directly in a code comment:

```python
# multiplayer_server/server.py
# ── authoritative economy/progression ────────────────────────────────────
# XP, coins, level and skill points change HERE and nowhere else. The client is told the
# result via player_stats; it never gets to set these itself. A client that fakes any of them
# only fakes its own screen — the next player_stats overwrites the lie with the server's truth.
```

That comment's last sentence is the important insight for understanding *why* this design works: the server doesn't need to detect or block a cheating client in real time — it just needs to never trust client-reported state as ground truth. A modified client can lie to itself all it wants; the very next authoritative broadcast silently overwrites the lie, so cheating has no persistent effect and no advantage over other players.

**Movement anti-cheat:**

```python
# multiplayer_server/server.py — _handle_move(), simplified
max_step_px = max_tile_step_per_move * world.tilewidth
new_x = clamp(msg["x"], 0, world_width_px - 1)
dx = new_x - player.last_valid_x
if abs(dx) > max_step_px:
    new_x = player.last_valid_x + clamp(dx, -max_step_px, max_step_px)
if not world.is_box_walkable(new_x, new_y, player.hitbox):
    new_x, new_y = player.last_valid_x, player.last_valid_y
    send(player, "pos_correction", x=new_x, y=new_y)
```

Position is bounds-clamped, per-tick movement distance is capped, and every move is checked against a server-side collision oracle built from the same TMX map data the client uses. A client that reports an impossible jump or tries to walk through a wall simply gets silently snapped back — the server doesn't need to "detect cheating" as a special case, because illegal moves are just never accepted as the authoritative position in the first place.

**Combat authority**, via the engine bridge:

```python
# multiplayer_server/server.py — _handle_mob_damage(), simplified
ruling = await self.engine.mob_hit(mob_id, mob_type, damage, player_id)
if ruling is not None and "life" in ruling:
    life = clamp(ruling["life"], 0, 9999)          # engine's number overrides the client's claim
if ruling is not None and ruling.get("killed"):
    self._finish_mob_death(client, mob, ruling)
```

And explicitly, a client cannot unilaterally declare a kill:

```python
# multiplayer_server/server.py — _handle_mob_death(), simplified
if self.engine.available and mob.alive:
    log.debug("Ignoring unverified mob_death claim...")
    return
```

If the engine is running and it still considers the mob alive, a client's `mob_death` message is simply logged and discarded — the only way a mob actually dies is the engine's own ruling saying so.

**XP, gold, and leveling** live entirely server-side:

```python
# multiplayer_server/server.py — PlayerState, simplified
def credit_kill(self, exp_reward, coin_reward):
    self.exp += max(0, exp_reward)
    self.coin += max(0, coin_reward)
    return self._apply_level_ups()

def _apply_level_ups(self):
    while self.exp >= self.next_level_exp:
        self.exp -= self.next_level_exp
        self.level += 1
        self.next_level_exp = 4 + self.level * 3   # matches the client's own Player.java formula
        self.skill_points += 1
```

The comment `# matches Player.java: 7, 10, 13, ...` is a small but important detail: the leveling curve is intentionally duplicated in two languages rather than shared, because the server's authoritative copy and the client's display copy need to agree closely enough that a player doesn't see visibly wrong numbers between "what the server just told me" and "what I locally predicted before the server responded" — keeping the formulas in sync by hand is a known maintenance cost the project has accepted.

**Skill unlocks and NPC/shop interactions** follow the identical pattern: cost and prerequisite data live in a server-side JSON catalog, checked against the player's server-held skill points; NPC dialogue state, shop stock, and gold are all held server-side, with `npc.py`'s own documentation stating the reason plainly — a modified client that edits its local copy of `npcs.json` or its save file should not be able to grant itself free items or unlock dialogue it hasn't earned, and keeping that state server-side is what prevents it.

### World Streaming

`world.py` parses the same Tiled `.tmx` map format the client uses (via Python's stdlib `xml.etree.ElementTree` — no external map-parsing library), chunks each tile layer into gzip-compressed, base64-encoded pieces streamed to clients on demand, and builds the same walkability oracle used for movement anti-cheat above. Streaming chunks (rather than shipping a whole map up front) is also why the client and server don't need byte-identical map files pre-installed — the server is the single source of truth for map data over TCP multiplayer, unlike the BLE path (Section 2.7), which relies on both devices already having the same bundled assets.

### Persistence

No SQL database of its own — player stats and progress are persisted to a flat `player_data.json`, keyed by license key. `saves.db` (save_server's database) is mounted **read-only** purely so the admin dashboard can look up usernames; the multiplayer server never writes to it. Bans and live connection state are in-memory only and are lost on a server restart — a deliberate simplicity tradeoff given the operational scale this project runs at.

## 3.3 `patch_server`: Signed Binary-Diff Updates

**Runtime**: Python 3, thread-per-connection (same style as `save_server`, not asyncio). Entry point `server.py` (~318 lines), backed by a detailed design document (`PATCH_SERVER.md`) that was checked against the running code during research and found to match.

### Protocol

```
PING\n                    -> PONG\n
CHECK <current_version>\n -> UPTODATE\n
                              | UPDATE <to_version> <size_bytes> <sha256_hex> <sig_b64>\n
                              | ERROR <msg>\n
FETCH <from_version>\n    -> [8-byte big-endian size][raw patch ZIP bytes]
                              | ERROR <msg>\n
```

Version strings look like `"2.0.8"` (major.minor.build), sourced from `core/assets/res/build.properties` — the exact same file Gradle reads to stamp the project version (Section 1), so the version the running game reports to the patch server is guaranteed to match the version Gradle actually built. Patch lookup is a **direct-hop match only**: the manifest needs an entry whose `from` equals the client's reported version and whose `to` equals the manifest's current `latest_version` — there's no multi-version chaining, so a client that's fallen many versions behind receives one patch to the latest version directly (assuming the manifest was built that way) rather than a sequence of intermediate patches.

### Signing

```python
# patch_server/server.py and build_patch.py — identical function in both
def signature_payload(patch_sha256: bytes, from_version: str, to_version: str) -> bytes:
    return patch_sha256 + b"|" + from_version.encode("ascii") + b"|" + to_version.encode("ascii")
```

RSA-2048 with PKCS#1 v1.5 padding over SHA-256, signing not just the patch's hash but the hash **concatenated with both version strings**. This binding is what defeats two specific attacks: a downgrade attack (an attacker can't take a validly-signed patch and relabel its version string, because the signature covers the version strings themselves) and a mismatched-patch replay (a signature computed for "2.0.7 → 2.0.8" cannot be reused to authorize a "2.0.7 → 2.0.9" patch, even if the attacker somehow produced identical patch bytes).

### Offline Build Tool

`build_patch.py` is a manual, offline tool (not part of the running server) that:

1. Diffs two whole-jar zip files entry-by-entry, producing `add[]` / `replace[]` / `delete[]` lists — unchanged file entries are skipped entirely, which is what keeps patches small even for a large game jar.
2. Packages the diff into a patch ZIP (`manifest.json` plus the actual changed/added file bytes; deletions are listed by path only, with no bytes needed).
3. Signs `SHA-256(patch_bytes) || from_version || to_version` with the server's RSA private key.
4. Updates the live `manifest.json` with the new entry and bumps `latest_version`.

The running server re-reads `manifest.json` on every single connection (not cached at startup), so publishing a new patch via `build_patch.py` takes effect immediately for the next client that checks — no server restart required.

### Client-Side Verification

`desktop/src/main/java/desktop/update/Updater.java` (Section 2.11) is the consumer: it downloads the patch, independently recomputes the SHA-256 hash, and verifies the RSA signature against a public key compiled directly into the client binary — meaning a compromised or malicious patch server (or a man-in-the-middle on an unencrypted connection) cannot serve a tampered patch that the client would accept, since it has no way to produce a valid signature without the private key that never leaves the server.

### Persistence

No database — `patch_server` is entirely file-based: `manifest.json` plus a `patches/*.zip` directory plus the RSA keypair files.

## 3.4 Shared Configuration

| Environment Variable | Purpose | Used By |
|---|---|---|
| `MICHI_INTERNAL_API_KEY` | Shared secret gating save_server's internal port | save_server, multiplayer_server |
| `MICHI_ITCH_API_KEY` | Developer's itch.io API key (server-only, never shipped to clients) | save_server |
| `MICHI_ITCH_GAME_ID` | Numeric itch.io game ID | save_server |
| `MICHI_ITCH_OWNER_USER_IDS` | Comma-separated itch numeric user IDs that bypass the purchase check | save_server |
| `MICHI_ITCH_OWNER_SECRET` | Self-generated secret enabling owner activation without going through itch OAuth at all | save_server |
| `MICHI_ADMIN_PASSWORD` | Multiplayer admin dashboard login | multiplayer_server |
| `MICHI_SAVE_SERVER_HOST` / `_PORT` | Where multiplayer_server reaches save_server's internal API | multiplayer_server |

All three servers' RSA private keys are currently committed directly into the repository (`server_private_key.pem`, `patch_private_key.pem`). The project's own `SERVERS/LICENSING.md` explicitly flags this as a pre-launch rotation item — worth being aware of if this repository is ever made public or shared beyond the current development context, since possession of these keys would let someone impersonate the servers to a real client.

---

# 4. Licensing and itch.io Integration

This section pulls together how a purchase on itch.io becomes a working, offline-capable license inside the game — spanning client code (`platform.LicenseActivation`, `desktop.itch.DesktopItchAuth`) and server code (`save_server`).

## The Guiding Principle

`SERVERS/LICENSING.md` states the design philosophy in one line worth quoting directly, because it explains every downstream decision in this section:

> **"itch.io is the door, not the landlord."** A player proves they bought the game *once*, at first launch. From then on the license belongs to **your** server, and itch is never contacted again — the game keeps working offline, without the itch app, forever.

This is a meaningfully different model from typical DRM: itch.io is used purely as a one-time identity/purchase oracle. It is never queried again after the first successful activation, which means the game has no runtime dependency on itch.io's servers, no periodic re-validation, and works fully offline (aside from the multiplayer/cloud-save features, which depend on this project's own servers, not itch's).

## Step 1: Proving Purchase — itch.io OAuth (Desktop-Only)

`core/src/main/java/platform/ItchAuthProvider.java` is the platform interface; `desktop/src/main/java/desktop/itch/DesktopItchAuth.java` is its only implementation (there is no Android implementation — itch purchase verification is simply skipped/unavailable on mobile, since itch.io sells desktop game licenses, not mobile app licenses).

```java
// desktop/src/main/java/desktop/itch/DesktopItchAuth.java
private static final String ITCH_CLIENT_ID_BAKED = "00477f3fb217b3b7fc21fb520c5a65b3";
private static final String SCOPE = "profile:me";
private static final int AUTH_TIMEOUT_SECONDS = 180;
private static final int REDIRECT_PORT = 34567;
```

The flow, on first launch only:

1. The client starts a tiny local `HttpServer` bound to `127.0.0.1:34567`.
2. It opens `https://itch.io/user/oauth?client_id=...&scope=profile:me&response_type=token&redirect_uri=http://127.0.0.1:34567/` in the player's default browser (falling back through a shell-invoked browser launch, and finally a Swing dialog with the URL pre-copied to the clipboard, if automatic browser launching fails entirely).
3. The player logs into itch.io (if not already) and approves the OAuth request in their browser.
4. itch redirects back to the local loopback server, carrying an `access_token` — but as a URL **fragment** (`#access_token=...`), which browsers never send to a server over HTTP by design. The client works around this with a small injected JavaScript redirect that turns the fragment into a query parameter the loopback server can actually read.
5. The client waits up to 180 seconds for this round trip to complete.

**Why the redirect URI must match exactly:** `SERVERS/LICENSING.md` notes that itch validates `redirect_uri` with an exact string match when the OAuth application is registered in itch's developer dashboard — it does not accept an arbitrary port, which is why `REDIRECT_PORT = 34567` is a fixed constant baked into the client rather than a randomly chosen free port.

## Step 2: Activation — Server-Side Verification Against itch's API

The OAuth token obtained above never reaches `save_server` inside the main RSA-OAEP handshake envelope — that envelope is capped at roughly 190 bytes of plaintext by RSA-2048's own size limits, and the timestamp/nonce fields already consume most of that budget. Instead, it travels in its own separate AES-GCM box, keyed by a value both sides can independently derive from the handshake nonces they already exchanged:

```python
# save_server/server.py — simplified
itch_key = hkdf(secret=client_nonce + server_nonce, salt=server_nonce, info=b"michi-itchtoken-v2", length=32)
itch_token = aesgcm_decrypt(enc_itch_bytes, key=itch_key, nonce=client_nonce[:12], aad=b"MichiItchToken")
```

With the token in hand, the server makes two calls to itch's own API, using the *developer's* API key (never the player's):

```python
# save_server/server.py — itch_verify_purchase(), simplified
# 1. Identify the player using THEIR token:
GET https://itch.io/api/1/{player_oauth_token}/me
# 2. Ask itch, using OUR api key, whether that user_id owns a download key for our game:
GET https://itch.io/api/1/{our_api_key}/game/{game_id}/download_keys?user_id={user_id}
```

A response containing a `download_key` means the player owns the game; a 404 or an `"errors"` field means they don't — a clear, definitive "no." Critically, the code distinguishes that definitive "no" from an *inconclusive* result:

```python
# save_server/server.py
class ItchError(Exception):
    """itch.io could not be reached / answered nonsense. Distinct from 'user does not own
    the game', which is a definitive NO rather than an inconclusive result — we must not
    hand out a license just because itch happened to be down."""
```

If itch's API is unreachable or returns something unexpected, the server raises `ItchError` and refuses to activate (`ITCH_UNAVAILABLE`), rather than defaulting to granting a license. This is the safe failure direction: an outage on itch's end should never accidentally become a way to get a free license.

## Step 3: License Issuance

Once ownership is confirmed, the server generates a fresh license key, an `activation_id`, and a server-only `enc_key`, and returns the three-part `AUTH_OK` response described in Section 3.1. From this point forward, the client stores only `activation_id` and an encrypted blob (`activation.dat`) — never the plaintext license key — and every future launch uses the `LOGIN` path instead of `ACTIVATE`, which does **not** contact itch.io at all. This is the mechanism that fulfills the "itch is never contacted again" promise: the license, once issued, is entirely this project's own server's responsibility to validate on subsequent logins, using only the cryptographic material from the first activation.

## The Developer Bypass Problem

There's a structural quirk in itch's OAuth model that this project has to work around: itch's implicit-grant OAuth flow only ever issues an `access_token` to an account that purchased the game through the storefront. A developer's own account, testing their own game, can never complete this flow successfully — there is no purchase to verify. Two bypasses exist for this:

```python
# save_server/server.py — simplified
owner_secret = cfg.get("itch_owner_secret", "")
if owner_secret and oauth_token == f"ownerkey:{owner_secret}":
    return True, "owner (secret bypass)"
```

- **`MICHI_ITCH_OWNER_USER_IDS`** — a comma-separated allowlist of itch numeric user IDs that skip the purchase check once identified via a *real* itch token (so this still requires logging into a real itch account, just skips the download-key lookup).
- **`MICHI_ITCH_OWNER_SECRET`** — a self-generated secret, not related to itch at all. The client sends a specially formatted pseudo-token, `ownerkey:<secret>`, which the server matches directly with no itch API call whatsoever. On the client side, this secret is read from a local file (`owner_secret.dat`) placed next to the executable — a mechanism reserved for the developer's own testing and administration, not exposed through any normal player-facing flow.

## What's Deliberately *Not* In This Model

- **No DRM check on every launch.** Only `ACTIVATE` (first run) talks to itch; `LOGIN` (every run after) never does.
- **No hardware/machine fingerprinting baked into the license itself** on the server side — the license is tied to the itch purchase and the server-issued key material, not to a specific machine. (The client's own *offline* fallback cache, described in Section 2.9, does derive a local-only key from a machine fingerprint, but that's purely to protect the locally cached save file when no server is reachable — it's unrelated to license validity.)
- **No always-online requirement.** Once a license is issued, gameplay, saves, and even multiplayer authentication all depend only on this project's own servers — itch.io's uptime has zero bearing on whether an already-activated player can keep playing.

---

# 5. End-to-End Flow Summaries

## First Launch, Desktop, Full Purchase Flow

1. `DesktopLauncher.main()` runs `DesktopBootstrap.bootstrap()`, which checks the patch server for updates (Section 3.3) and registers `DesktopItchAuth` against the `ItchAuthProvider` interface (Section 1).
2. The LWJGL3 window opens; `MichiGame.create()` resets any stale texture caches and kicks off `LicenseActivation.ensureActivated()` on a background thread.
3. No local `activation.dat` exists yet, so the client opens the itch.io OAuth page in the player's browser (Section 4, Step 1).
4. Once an itch access token is obtained, the client performs the `HELLO`/`ACTIVATE` handshake against `save_server` (Section 3.1), sending the itch token in its own AES-GCM box.
5. `save_server` verifies ownership against itch's API (Section 4, Step 2) and, if verified, issues a license key, encrypts it into `enc_blob`, and returns `AUTH_OK`.
6. The client persists `activation_id` + `enc_blob` to `activation.dat` and never needs to contact itch.io again.
7. Gameplay proceeds; on save, `CloudSaveService` uploads to `save_server` using a fresh handshake derived from the now-known license key (Section 2.9).

## Every Subsequent Launch

1. `LicenseActivation.ensureActivated()` finds `activation.dat`, performs a `LOGIN` handshake (Section 3.1) — no itch.io contact at all.
2. If no save server is reachable, the client falls back to a locally cached, machine-fingerprint-encrypted save (Section 2.9) and queues an upload for when connectivity returns.

## Joining Multiplayer

1. `MultiplayerClient` performs its own `HELLO`/`LOGIN` handshake against `multiplayer_server`, presenting the same `activation_id`/`enc_blob` pair (Section 2.6).
2. `multiplayer_server` forwards those credentials to `save_server`'s internal-only port for verification (Section 3.2) rather than keeping its own license database.
3. Once authenticated, all movement, combat, and progression traffic flows through the encrypted session; the server enforces movement bounds and step limits, defers combat rulings to the embedded `engine.jar` (Section 3.2), and treats XP/gold/levels as server-owned state that the client can only ever be told about, never set.
4. Remote players are rendered client-side using Hermite-spline interpolation between periodic authoritative position snapshots (Section 2.6).

## Joining a BLE Session (Mobile Only)

1. Two Android devices tap NFC to exchange a session token and map ID (Section 2.7, Section 1's Android manifest permissions).
2. The host device runs a local BLE GATT server; the guest connects directly — no internet, no license check, no encryption, since the trust boundary is physical proximity, not a network.
3. Gameplay state (position, mob HP) synchronizes via a compact pipe-delimited protocol, with the host authoritative for mob combat within that session.

## Receiving a Patch

1. On desktop, `UpdateClient` checks in with `patch_server` on startup, comparing the running build's version (read from `core/assets/res/build.properties`, the same file Gradle stamps at build time) against the server's manifest.
2. If a direct-hop patch exists, the client downloads it, verifies its SHA-256 hash and RSA signature against a compiled-in public key, and spawns a separate `Updater` JVM.
3. `Updater` waits for the original process to exit, applies the patch atomically over the game jar, and relaunches — all without ever touching license data or save files.
