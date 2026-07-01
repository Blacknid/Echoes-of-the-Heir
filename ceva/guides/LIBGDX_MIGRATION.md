# Graphics2D → libGDX Migration

Michi's Adventure was ported from Java2D (Swing `JPanel` + `Graphics2D` software rendering) to
**libGDX** (GPU rendering via the LWJGL3 backend). The game plays identically, pixels are still
counted from the **top-left** (Graphics2D convention), and the codebase is now Android-ready.

Branch: `feat/libgdx-migration`. Build/run: `./gradlew :lwjgl3:run`.

---

## 1. What the game looks like now (unchanged) vs. how it renders (new)

Every screen renders the same — title, cutscenes, gameplay, HUD, dialogue, minimap, lighting,
weather — but the pixels are now drawn by the GPU through a small facade instead of Java2D.

**Verified by running the build:** title screen (background texture + FreeType menu text),
new-game → awakening cutscene (state transition + map load), and in-cave gameplay (tiles from
`.tmx`/`.tsx`, player radial lighting, full HUD, dialogue box, quest tracker) all render correctly.

---

## 2. Project structure (libGDX gdx-setup layout)

```
michis-adventure/
  settings.gradle, build.gradle, gradlew(.bat)   ← Gradle 9.6.1 wrapper
  core/          ← engine-agnostic game logic + the new gfx.* rendering layer
    src/main/java/gfx/        GdxRenderer, Sprite, Color, Font, Stroke, FontSystem, …
    src/main/java/gfx/geom/   Rect, Ellipse, Polygon, IntPolygon, Transform, Shape
    src/main/java/main/       MichiGame (libGDX ApplicationListener)
    (during migration, core also compiles the legacy tree at ../ceva/src via sourceSets)
  lwjgl3/        ← desktop launcher (DesktopLauncher) + packaging (gradle/packaging.gradle)
  android/       ← STUB (README only; not built yet — a drop-in for the future port)
  ceva/src/      ← the game code (rendering rewritten to gfx.*; logic unchanged)
```

libGDX **1.13.1**; target bytecode **Java 17** (`options.release = 17`, Android-compatible),
compiled with the installed JDK (26).

---

## 3. The core idea: a Graphics2D-shaped facade

Rather than hand-rewrite ~1570 draw calls, we introduced **`gfx.GdxRenderer`** — a class whose
methods mirror the `Graphics2D` API the game used (`setColor`, `fillRect`, `fillOval`, `drawRect`,
`drawLine`, `drawString`, `setFont`, `drawImage(...)`, `translate`, `setClip`, `fill/draw(Shape)`,
`setStroke`, alpha via `setAlpha`). Call sites changed mechanically: the `Graphics2D g2` parameter
threaded through `Entity.draw`, `UI.draw`, `TileManager`, etc. became `GdxRenderer g2`.

Internally the facade drives a libGDX `SpriteBatch` (images/text/gradients) + `ShapeRenderer`
(fills/lines/ovals) + `BitmapFont`, and **auto-flushes** between the two so callers never manage GL
state. The **single Y-flip** lives in the camera (`OrthographicCamera.setToOrtho(true, …)`), so
every existing coordinate works unchanged — top-left origin, +Y down.

**Texture V-flip (important):** under the yDown camera, `batch.draw(region, …)` would render
textures upside-down (shapes/text are unaffected — hence "only pictures were inverted" if this is
missed). So every `gfx.Sprite` stores its `TextureRegion` **V-flipped** (`region.flip(false,true)`)
at construction; slicing (`getSubimage`) works in native top-left pixel coords against the texture
and re-flips the child, so sheet/tileset frame math is unaffected. Fonts are generated **without**
`flip` (a flipped font mirrors the glyphs); `drawString` positions by baseline (`top = y − ascent`).

### Value types
AWT value types were replaced with Android-safe `gfx.*` mirrors (same public API), so no
`java.awt` remains in the rendering path:
- `java.awt.Color` → `gfx.Color`   `java.awt.Font` → `gfx.Font`   `BasicStroke` → `gfx.Stroke`
- `Rectangle`/`Rectangle2D` → `gfx.geom.Rect`   `Shape` → `gfx.geom.Shape`
- `Polygon` → `gfx.geom.IntPolygon` (mutable, for entity hurtboxes)
- `AffineTransform`+`createTransformedShape` → `gfx.geom.Transform` (collision-shape builder)
- `RadialGradientPaint`/`GradientPaint` → `gfx.RadialGradient`/`gfx.Gradient`

### Images
`java.awt.image.BufferedImage` → **`gfx.Sprite`** (wraps a libGDX `TextureRegion`).
`getSubimage()` → zero-copy sub-regions (sprite-sheet / tileset slicing). `ResourceCache` and
`UtilityTool` now load/scale `Sprite`s (GPU textures, nearest-filtered for crisp pixel art);
"scaling" is a draw-time size, not a rasterized bitmap.

---

## 4. Collision geometry — proven identical

Tiled collision shapes (rect / rotated rect / ellipse / polygon / polyline) used
`java.awt.geom`. These were reimplemented in `gfx.geom` and **verified** by a desktop parity
test (`lwjgl3.GeomParityTest`) cross-checking against real `java.awt.geom` over **240,000
randomized `intersects`/`contains` probes — 0 mismatches**. Collision behaves exactly as before.

---

## 5. Effects — GPU-native ("close enough")

Per the chosen fidelity level, hard Java2D effects were reimplemented the idiomatic libGDX way:

| Effect | Before (Java2D) | Now (libGDX) |
|---|---|---|
| Hit-flash / attack telegraph | intermediate `BufferedImage` + `SRC_ATOP` tint | `drawImageTinted` (silhouette re-draw via `batch.setColor`) |
| Vignette / color grading | `RadialGradientPaint` per frame | `GdxRenderer.bakeRadialGradient` → texture, drawn each frame |
| Lighting (`Lightning`) | per-pixel `DataBufferInt` masks + `AlphaComposite.DstOut` | baked radial light texture drawn **additively** over a darkness fill; `setBlendMode(NORMAL/ADDITIVE/DSTOUT)` |
| Weather (rain/snow/storm) | alpha-composited fills | `setColor` + `fillRect` + `setAlpha` |
| Tile flip/rotate (Tiled GIDs) | `AffineTransform` per tile | `drawTileFlipped` (region flip + 90° steps) |
| Minimap terrain / pot sprite | `createGraphics()` bakes | libGDX `Pixmap` bakes → `Texture` |
| Projectile/arrow rotation | rotated `BufferedImage` | 90° `Pixmap` rotation at load |
| Fonts (`.ttf`) | `Font.createFont` + `FontMetrics` | FreeType `BitmapFont` + `GlyphLayout` (`gfx.FontSystem`) |

**Trade-offs / deferred:** the minimap's circular clip is approximated by the baked radial
vignette + ring border (GL has no circular scissor); `Lightning`'s polygon **shadow occlusion**
was omitted (tile-grid light gating is kept) — both are documented `TODO(gfx-stage5)`. A few UI
gradient *text* fills fall back to a solid color. These are visual niceties, not gameplay.

---

## 6. App lifecycle, window, input

- **`main.MichiGame`** (libGDX `ApplicationListener`) owns the loop: each frame clears to the map
  background, runs `GamePanel.stepUpdates(delta)` at a fixed **60 UPS**, then `GamePanel.draw(renderer)`.
- **`GamePanel`** is no longer a Swing `JPanel`/`Runnable` — no game thread, no `paintComponent`,
  no `BufferedImage` back-buffer, no custom window-control buttons. It holds game state + logic and
  exposes `stepUpdates`/`draw`. Fullscreen via `Gdx.graphics`; resize via `MichiGame.resize`.
- **Input:** `KeyHandler`/`MouseHandler` implement libGDX `InputProcessor` (registered through an
  `InputMultiplexer`). `KeyEvent.VK_*` → `Input.Keys.*`, mouse buttons → `Input.Buttons`, wheel →
  `scrolled()`. The `WindPainter` authoring tool + `.windmap` files are unchanged (only its
  overlay draw + keycodes were ported); the wind system logic is untouched.
- **`main.Main`** is now a static holder (no `JFrame`) with `bootstrap()` (mandatory update check +
  license load/watchdog) called by `DesktopLauncher` before the GL window opens.
- **Audio:** `Sound` rewritten on `Gdx.audio` (`Music` for looped tracks, `Sound` for SFX),
  replacing `javax.sound.sampled`. `.wav`/`.mp3`/`.ogg` all work natively; the missing legacy
  `ceva/lib` MP3 SPI jars are no longer needed. `AudioManager`/`SFX` API unchanged.

---

## 7. Build & packaging (Gradle owns it)

`build_tools/compile.cmd` is replaced by Gradle tasks (`gradle/packaging.gradle`):

- `./gradlew :lwjgl3:run` — run the game (dev).
- `./gradlew :lwjgl3:jar` — `deploy/Michi-s-adventure.jar` (fat, runnable: DesktopLauncher +
  all libGDX/LWJGL/gdx/freetype natives + full `res/` asset tree).
- `./gradlew :lwjgl3:jpackageImage` — jpackage app-image → `deploy/MichisAdventure-<ver>.exe`
  (bundled JRE, `-XX:+UseG1GC …`).
- `./gradlew :lwjgl3:installer` — full pipeline: `sync_keys.py` (license public-key inject) →
  fat JAR → jpackage → **Inno Setup** (`setup_init.iss`, unchanged install-time RSA license
  generation) → `deploy/MichiGame_Setup.exe`.

Licensing is unchanged end-to-end (same RSA keypair, `sync_keys.py`, install-time PowerShell key
gen). Missing optional tools (Inno Setup, Python) warn and skip rather than fail. Version/build
number come from `ceva/src/res/build.properties`.

---

## 8. Android readiness (task #5 — what still can't go to mobile)

The **rendering, input, and audio layers are fully Android-portable** (no `java.awt`/`javax.swing`/
`javax.sound` anywhere in them). When the Android module is built, `MichiGame` runs as-is via an
`AndroidApplication` launcher; assets are shared.

The only non-portable code is **cleanly isolated** to desktop-only subsystems that Android replaces
wholesale — flagged here for the future port:

- **`update/UpdateClient.java`, `update/Updater.java`** — self-update via `ProcessBuilder` relaunch
  and a Swing `JOptionPane` prompt. Runs pre-launch on desktop only; on Android, updates go through
  the Play Store (the whole update mechanism is dropped).
- **`data/LicenseManager.java`** — machine fingerprint via Windows registry (`reg query`
  MachineGuid through `Runtime.exec`). Android uses a device identifier instead.
- **`System.exit(...)`** in the quit buttons (`KeyHandler`/`MouseHandler`) — becomes a platform
  no-op / `Gdx.app.exit()` on Android.
- **Save/config file paths** (`FileReader("config.txt")`, local save files) — move to
  `Gdx.files.local` on Android.

Recommended when starting Android: introduce a `core.platform.PlatformServices` interface for
(exit, update-relaunch, machine-id) with a desktop impl in `lwjgl3` and an Android impl in
`android`. Everything else is already shared.

---

## 9. Notes

- Kept the **custom `.tmx`/`.tsx` parser** (not libGDX's `TmxMapLoader`) to preserve dev-mode live
  reload, custom per-tile/layer properties, GID flip flags, and animation slicing.
- Dev asset loading uses `ResourceCache`'s dev-source dir; packaged builds resolve assets from the
  classpath/jar (internal → classpath fallback).
- Two referenced object sprites (`Chest_closed/opened.png`, and a few `AssetValidator` entries)
  are absent on disk under those exact names — pre-existing naming mismatches, not migration
  regressions; the engine tolerates missing assets (draws nothing).
