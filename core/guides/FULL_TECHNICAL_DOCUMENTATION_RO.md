# Echoes of the Heir — Documentație Tehnică Completă

Acest document descrie, de jos în sus, arhitectura tehnică completă a jocului **Echoes of the Heir**. În codul sursă, în numele pachetelor Java, în numele claselor (`MichiGame`, `GamePanel`) și în identificatorii Gradle ai proiectului, jocul încă poartă numele său istoric de dezvoltare, "Michi's Adventure" — acesta este numele intern folosit de-a lungul întregii baze de cod și va rămâne așa în fragmentele de cod citate aici (redenumirea claselor ar fi o operațiune separată, de refactorizare, nelegată de conținutul acestui document). În tot restul textului, orice referire la joc ca produs folosește însă numele curent, **Echoes of the Heir**.

Structura acestui document este deliberat **de jos în sus** ("bottom-up"): nu începe cu sistemul de build sau cu serverele, ci cu bucla principală de joc — `GamePanel` și `MichiGame` — pentru că acesta este punctul din care totul altceva pornește la runtime. De acolo, documentul urcă prin input, coliziune, entități, hărți, randare, iluminat, UI, quest-uri, salvare/încărcare, cloud și licențiere, apoi ajunge la cele două secțiuni majore — **multiplayer** (atât calea TCP server-autoritară, cât și calea BLE peer-to-peer) și **versiunea Android** — înainte de a coborî din nou spre serverele Python de backend și, în final, spre sistemul de build Gradle care produce cele trei module (core/desktop/android) dintr-o singură bază de cod.

Fiecare secțiune citează cod real din depozit, cu o cale de fișier și, unde e util, o referință de linie, urmată de o explicație a *motivului* pentru care codul este structurat așa cum este — nu doar a ceea ce face. Documentul vânează activ și narează poveștile de tip "problemă → cauză → soluție" găsite în codebase: pierderea contextului OpenGL pe Android, bug-ul de umbre din FBO-uri imbricate, vizibilitatea jucătorilor BLE blocată de o verificare TCP, alocările de memorie din calea fierbinte de randare, migrarea la autoritatea de server pentru economia multiplayer, și altele descoperite pe parcurs în comentariile codului și în istoricul git.

---

## Cuprins

1. [Bucla Principală a Jocului: GamePanel și MichiGame](#1-bucla-principala-a-jocului-gamepanel-si-michigame)
2. [Input: Tastatură, Mouse, Acțiuni Abstracte](#2-input-tastatura-mouse-actiuni-abstracte)
3. [Coliziune și Fizică de Bază](#3-coliziune-si-fizica-de-baza)
4. [Entități: Player, Entity, NPC, Boss](#4-entitati-player-entity-npc-boss)
5. [Tile-uri și Hărți](#5-tile-uri-si-harti)
6. [Randare: de la Graphics2D la libGDX](#6-randare-de-la-graphics2d-la-libgdx)
7. [Iluminat, Umbre și Efecte de Mediu](#7-iluminat-umbre-si-efecte-de-mediu)
8. [UI, Meniuri, Dialog și Cutscene-uri](#8-ui-meniuri-dialog-si-cutscene-uri)
9. [Quest-uri și Skill Tree](#9-quest-uri-si-skill-tree)
10. [Salvare/Încărcare Locală și Recuperarea Pierderii Contextului GL](#10-salvareincarcare-locala-si-recuperarea-pierderii-contextului-gl)
11. [Salvări în Cloud și Licențiere](#11-salvari-in-cloud-si-licentiere)
12. [Multiplayer — Crearea și Funcționarea](#12-multiplayer--crearea-si-functionarea)
13. [Serverele Backend (Python)](#13-serverele-backend-python)
14. [Versiunea Android — Cum Funcționează](#14-versiunea-android--cum-functioneaza)
15. [Sistemul de Build (Gradle)](#15-sistemul-de-build-gradle)
16. [Actualizări și Patch-uri](#16-actualizari-si-patch-uri)
17. [Sumare ale Fluxurilor Complete](#17-sumare-ale-fluxurilor-complete)

---

# 1. Bucla Principală a Jocului: GamePanel și MichiGame

## 1.1 De Ce Aici Începe Documentul

Toate celelalte sisteme din acest joc — randare, input, coliziune, quest-uri, multiplayer — sunt, în cele din urmă, apelate din exact două locuri: `main.MichiGame` (punctul de intrare libGDX) și `main.GamePanel` (starea și logica jocului pe care `MichiGame` o conduce). Orice altă parte a codebase-ului poate fi înțeleasă cel mai bine urmărind cum ajunge să fie apelată din bucla acestor două clase, motiv pentru care documentul pornește de aici, nu de la sistemul de build.

## 1.2 `MichiGame` — Punctul de Intrare libGDX

`core/src/main/java/main/MichiGame.java` extinde `com.badlogic.gdx.ApplicationAdapter` — clasa de bază pe care orice backend libGDX (LWJGL3 pe desktop, backend-ul Android) o conduce prin propriul ciclu de viață (`create()` → `render()` repetat la fiecare cadru → `dispose()`). Documentația proprie a clasei explică decizia centrală de orientare a coordonatelor:

> "Jocul folosește coordonate în stil Graphics2D: (0,0) în stânga-sus, +Y în jos. Facem flip O SINGURĂ DATĂ aici printr-un `OrthographicCamera` dimensionat după fereastră cu marginea de sus la y=0, astfel încât fiecare coordonată de desenare existentă funcționează neschimbată."

Acesta este un detaliu arhitectural esențial moștenit din migrarea de la Java2D (vezi Secțiunea 6): în loc să inverseze fiecare coordonată din cele ~1570 de puncte de apel de desenare din tot jocul, s-a ales să se inverseze camera o singură dată, la sursă.

### `create()` — Ce Se Întâmplă La Pornire

```java
// core/src/main/java/main/MichiGame.java
@Override
public void create() {
    // Must run first: on Android (singleTask launch mode) a "restart" can reuse the process,
    // so util.ResourceCache's static image caches may still hold Sprites/Textures tied to the
    // previous, now-dead GL context — see ResourceCache.resetImages() for why that corrupts
    // rendering (wrong texture bound to a recycled GL id) if not cleared before anything loads.
    util.ResourceCache.resetImages();

    new Thread(() -> main.Main.LICENSE_KEY = platform.LicenseActivation.ensureActivated(),
        "LicenseActivation").start();

    camera = new OrthographicCamera();
    syncCamera(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());

    fonts = new FontSystem();
    registerFace("Pixeloid Sans", "res/fonts/Pixeloid Sans.ttf");
    registerFace("m5x7", "res/fonts/m5x7.ttf");
    fonts.setDefaultFace("Pixeloid Sans");

    renderer = new GdxRenderer(camera, fonts);

    gp = new GamePanel();
    gp.config.loadConfig();
    gp.applyFpsTarget(gp.config.fpsTarget);
    gp.setupGame();
    gp.startGameThread(); // no-op now; kept for API parity

    InputMultiplexer mux = new InputMultiplexer();
    boolean isAndroid = Gdx.app.getType() == Application.ApplicationType.Android;
    if (isAndroid) {
        touchOverlay = new TouchControlsOverlay(gp);
        mux.addProcessor(touchOverlay.getStage());
    }
    mux.addProcessor(gp.keyH);
    mux.addProcessor(gp.mouseH);
    Gdx.input.setInputProcessor(mux);

    Controllers.addListener(new GamepadInputAdapter(gp));
}
```

Câteva decizii demne de remarcat aici:

- **`ResourceCache.resetImages()` rulează primul, înainte de orice altceva.** Motivul e specific Android (detaliat pe larg în Secțiunea 10.2 și în Secțiunea 14): modul de lansare `singleTask` al Android poate reutiliza un proces existent la o "repornire" a activității, ceea ce lasă cache-urile statice de imagini legate de un context GL deja mort. Golirea lor înainte ca orice sistem să înceapă să încarce texturi este soluția.
- **Activarea licenței rulează pe un thread separat, nu blocant.** Comentariul din cod explică direct de ce: `ensureActivated()` face un handshake real de rețea cu timeout-uri de ordinul secundelor per server încercat, iar blocarea `create()` pe acest apel a lăsat fereastra să pară "Not Responding" câteva secunde la fiecare pornire. `LICENSE_KEY` este `volatile` tocmai pentru că e citit mai târziu, după ce fereastra e deja sus, de acțiuni pe care jucătorul le declanșează manual (salvare cloud, prieteni, multiplayer).
- **`InputMultiplexer`-ul înregistrează suprapunerea de control tactil ÎNAINTEA lui `KeyHandler`/`MouseHandler`, doar pe Android.** Acest lucru contează pentru ordinea de consum a evenimentelor: dacă jucătorul apasă pe un buton al joystick-ului virtual, `Stage`-ul overlay-ului trebuie să consume acea atingere înainte ca `MouseHandler` să o interpreteze greșit ca pe o comandă de atac în lume (vezi Secțiunea 2.4 și Secțiunea 14.4).
- **`Controllers.addListener(...)` se înregistrează necondiționat**, pe orice backend — este un no-op inofensiv dacă nu e conectat niciun gamepad, ceea ce înseamnă că desktop-ul primește suport pentru controller Xbox/PlayStation "gratuit", prin același adaptor folosit pe Android.

### Scalarea în Pixeli Întregi ("Moonshire-style")

```java
// core/src/main/java/main/MichiGame.java
private void syncCamera(int deviceW, int deviceH) {
    double fitRatio = Math.min(deviceW / (double) BASE_W, deviceH / (double) BASE_H);
    int pixelScale = Math.max(1, (int) Math.floor(fitRatio));

    int logicalW = deviceW / pixelScale;
    int logicalH = deviceH / pixelScale;
    int usedW = logicalW * pixelScale;
    int usedH = logicalH * pixelScale;
    int marginX = (deviceW - usedW) / 2;
    int marginY = (deviceH - usedH) / 2;

    camera.setToOrtho(true, logicalW, logicalH); // yDown = true, box sized in logical px
    camera.update();
    Gdx.gl.glViewport(marginX, marginY, usedW, usedH);
    if (renderer != null) renderer.setWorldViewport(marginX, marginY, usedW, usedH);

    if (gp != null) {
        gp.applyNewResolution(logicalW, logicalH, pixelScale, marginX, marginY, deviceW, deviceH);
    }
}
```

Documentația metodei explică filozofia: se alege cel mai mare multiplicator întreg (`pixelScale`) pe care fereastra curentă îl poate susține complet pe ambele axe, iar arta pixel se desenează întotdeauna la acea mărire exactă — niciodată scalată fracționar, ceea ce ar produce neclaritate. Spațiul rămas (dacă fereastra nu e un multiplu exact al rezoluției de bază 1280×720) **nu** devine bandă neagră (letterboxing); devine pur și simplu pixeli logici suplimentari, pe care sistemul de decupare a tile-urilor (Secțiunea 5) îi transformă în tile-uri de hartă suplimentare vizibile la margini. Consecința practică notată în comentariu: o fereastră "2x" rămâne adesea la scala 1x pentru că bara de titlu a sistemului de operare fură câțiva pixeli din înălțime — dar modul fullscreen, fără decorațiuni, atinge curat 2x/3x. Acesta e un compromis intenționat: harta de bază (1280×720) nu este niciodată tăiată, doar extinsă.

### `render()` — Ce Rulează La Fiecare Cadru

```java
// core/src/main/java/main/MichiGame.java
@Override
public void render() {
    gfx.Color bg = (gp != null && gp.mapManager != null && gp.mapManager.mapBackgroundColor != null)
        ? gp.mapManager.mapBackgroundColor : new gfx.Color(8, 6, 14);
    Gdx.gl.glClearColor(bg.getRed() / 255f, bg.getGreen() / 255f, bg.getBlue() / 255f, 1f);
    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

    if (gp == null) return;

    if (Gdx.input.isKeyJustPressed(com.badlogic.gdx.Input.Keys.F5))  gfx.shader.LightDebug.hud        = !gfx.shader.LightDebug.hud;
    // ... F6-F10 comută alte switch-uri de debug pentru shader-ul de iluminat ...

    if (touchOverlay != null) touchOverlay.act(Gdx.graphics.getDeltaTime());

    gp.stepUpdates(Gdx.graphics.getDeltaTime());

    renderer.begin(gp.screenWidth, gp.screenHeight);
    gp.draw(renderer);
    if (gp.memoryFlashback != null && gp.memoryFlashback.isActive()) {
        gp.memoryFlashback.draw(renderer);
    }
    renderer.end();

    if (touchOverlay != null) touchOverlay.draw();
}
```

Structura e simplă și liniară: șterge ecranul → sondează comutatoarele de debug pentru pipeline-ul de iluminat (F5–F10, vezi Secțiunea 7) → actualizează overlay-ul tactil dacă există → avansează simularea cu pas fix → randează un cadru. Observați că overlay-ul tactil își consumă/scrie propriile câmpuri în `KeyHandler`/`MouseHandler` **înainte** ca `stepUpdates()` să le citească în acel cadru — exact ca și cum un eveniment fizic de tastă/mouse ar fi sosit deja.

## 1.3 `GamePanel` — Inima Simulării

`core/src/main/java/main/GamePanel.java` (1501 linii) nu mai este, de la migrarea la libGDX, un `JPanel` Swing sau un `Runnable` care rulează pe propriul thread. Comentariul de la începutul clasei este explicit:

> "libGDX port: GamePanel is no longer a Swing JPanel/Runnable. It holds all game state and logic; the libGDX MichiGame ApplicationListener drives it (fixed-timestep update() + per-frame draw(GdxRenderer)). Window/fullscreen/resize is handled by Gdx.graphics; input by KeyHandler/MouseHandler (libGDX InputProcessors)."

`GamePanel` este, în schimb, un obiect pasiv de stare + logică — conține referințe la **toate** subsistemele jocului (peste 30 de câmpuri de tip manager/handler) și expune două metode pe care `MichiGame` le conduce din exterior: `stepUpdates(deltaSeconds)` și `draw(GdxRenderer)`.

### Compoziția Subsistemelor

```java
// core/src/main/java/main/GamePanel.java
public TileManager tileM = new TileManager(this);
public KeyHandler keyH = new KeyHandler(this);
public MouseHandler mouseH = new MouseHandler(this);
public main.input.InputActions actions = new main.input.InputActions();
public AudioManager audio = new AudioManager();
public CollisionChecker cChecker = new CollisionChecker(this);
public AssetSetter aSetter = new AssetSetter(this);
public MapObjectLoader mapObjectLoader = new MapObjectLoader(this);
public UI ui = new UI(this);
public EventHandler eHandler = new EventHandler(this);
public Config config = new Config(this);
public CutsceneManager csManager = new CutsceneManager(this);
public BossIntroCutscene bossIntroCutscene = new BossIntroCutscene(this);
public PathFinder pFinder = new PathFinder(this);
public EnvironmentManager eManager = new EnvironmentManager(this);
public environment.WindField windField = new environment.WindField(this);
public environment.WindPainter windPainter = new environment.WindPainter(this);
// ... cloudLayer, dustFogLayer, fireflyLayer, tensionBeats, mapShader, tileParticleEmitter,
//     screenShake, mobSpawner, saveLoad ...
public Player player = new Player(this, keyH);
public Entity obj[] = new Entity[100];
public Entity npc[] = new Entity[20];
public Entity monster[] = new Entity[20];
public interactiveTile iTile[] = new interactiveTile[100];
```

Fiecare subsistem primește `this` (referința la `GamePanel`) în constructor — un tipar de "god object" clasic pentru jocuri 2D de această scară, dar unul deliberat: permite oricărui sistem să acceseze oricare altul (`gp.player`, `gp.tileM`, `gp.mapManager`) fără indirecție suplimentară, cu prețul unui cuplaj mare. Pentru un joc de dimensiunea aceasta (peste 45.000 de linii), acest compromis a fost acceptat din capul locului, conform stilului codului.

**O notă despre dimensiunea array-ului de NPC-uri**, ilustrativă pentru genul de bug pe care acest document îl vânează activ:

```java
// core/src/main/java/main/GamePanel.java
// 20, not 10 — Canvas_Village_rework.tmx alone places 11 NPC_Generic objects; the old cap of 10
// silently dropped the 11th (MapObjectLoader logs a warning, but it's easy to miss), so nothing
// appeared for it even though its JSON/sprite/placement were all otherwise correct.
public Entity npc[] = new Entity[20];
```

Limita fixă de 10 NPC-uri per hartă a fost suficientă multă vreme, până când o singură hartă (satul canvas) a plasat 11 obiecte `NPC_Generic` — al 11-lea a fost eliminat silențios (doar un avertisment în consolă, ușor de ratat), deși configurația lui JSON, sprite-ul și poziționarea erau toate corecte. Soluția a fost dublarea limitei, nu adăugarea unei validări suplimentare — o soluție pragmatică pentru o clasă de bug ("prea multe entități pentru array-ul fix") care poate reapărea ușor pe măsură ce hărțile devin mai populate.

### `setupGame()` — Inițializarea de o Singură Dată

`setupGame()` rulează exact o dată, la pornirea aplicației, și orchestrează încărcarea inițială: validarea asset-urilor (`AssetValidator`), descoperirea hărților (`mapManager.discoverMaps()`), plasarea obiectelor/NPC-urilor/monștrilor din harta implicită, încărcarea sistemului de vânt pentru hartă, construirea pool-urilor de obiecte pentru proiectile/particule, și — critic pentru arhitectura de server autoritar (Secțiunea 12) — verificări explicite `if (!Headless.isEnabled())` în jurul oricărui sistem exclusiv-GPU:

```java
// core/src/main/java/main/GamePanel.java
// Shaders, vignette bake, gradient textures — all GL, all purely visual. The server skips it.
if (!Headless.isEnabled()) {
    mapShader = new MapShaderManager(this);
    mapShader.setup();
}
// ...
// Purely visual: the minimap bakes a terrain texture, which is GPU work the simulation never
// consults. The authoritative server skips it (see main.Headless) and leaves the field null —
// safe because every caller already null-checks it (KeyHandler, RenderPipeline, reloadMap).
if (!Headless.isEnabled()) {
    minimap = new Minimap(this);
    minimap.bakeTerrainImage();
}
```

Acest tipar — `GamePanel` construit identic, dar cu sistemele GPU sărite condiționat — este mecanismul exact care permite ca **același** `GamePanel` să ruleze atât ca joc single-player randat, cât și ca motor autoritar fără fereastră pe server (detaliat pe larg în Secțiunea 12.4). Clasa `main.Headless` (44 de linii) este comutatorul global:

```java
// core/src/main/java/main/Headless.java
/**
 * Global switch: is this JVM running the game with no window and no GPU?
 *
 * The authoritative game server runs the SAME simulation classes as the client —
 * Player, Entity, monster AI, CollisionChecker, PathFinder, QuestManager. That is the whole
 * point: one implementation of the rules, so the server and the client can never disagree
 * about them, and so nothing has to be ported or kept in sync.
 */
public final class Headless {
    private static volatile boolean enabled = false;
    public static void enable() {
        enabled = true;
        util.ResourceCache.setHeadless(true);
    }
    public static boolean isEnabled() { return enabled; }
}
```

`main.HeadlessGame` (122 linii) este bootstrap-ul care folosește acest comutator pentru a produce un `GamePanel` complet funcțional, fără fereastră și fără context GL:

```java
// core/src/main/java/main/HeadlessGame.java
public static synchronized GamePanel boot(int targetTps) {
    Headless.enable();
    if (app == null) {
        HeadlessApplicationConfiguration cfg = new HeadlessApplicationConfiguration();
        cfg.updatesPerSecond = targetTps;
        app = new HeadlessApplication(new com.badlogic.gdx.ApplicationAdapter() {}, cfg);
    }
    GamePanel gp = new GamePanel();
    gp.setupGame();
    return gp;
}
```

Documentația clasei rezumă motivația în două fraze care merită citate direct:

> "Two implementations of one rulebook always drift apart, and every place they drift is a desync or an exploit. One implementation cannot disagree with itself."

Acest principiu — o singură implementare a regulilor jocului, partajată între client și server — este firul roșu al întregii arhitecturi de multiplayer server-autoritar din Secțiunea 12.

### Bucla de Actualizare cu Pas Fix (Fixed-Timestep)

```java
// core/src/main/java/main/GamePanel.java
private static final int TARGET_UPS = 60;
private double updateAccumulator = 0;

public void stepUpdates(float deltaSeconds) {
    final double updateInterval = 1.0 / TARGET_UPS;
    updateAccumulator += deltaSeconds;
    if (updateAccumulator > 5 * updateInterval) updateAccumulator = 5 * updateInterval; // cap
    while (updateAccumulator >= updateInterval) {
        update();
        updateAccumulator -= updateInterval;
    }
    // FPS counter
    fpsFrameCount++;
    fpsTimerNs += (long) (deltaSeconds * 1_000_000_000L);
    if (fpsTimerNs >= 1_000_000_000L) {
        currentFPS = fpsFrameCount;
        if (currentFPS > maxFPS) maxFPS = currentFPS;
        fpsFrameCount = 0;
        fpsTimerNs = 0;
    }
}
```

Acesta este un acumulator clasic de pas fix (fixed-timestep). Ideea: rata de **simulare** (fixată la 60 de actualizări pe secundă) este complet decuplată de rata de **randare** (orice suportă afișajul — 60Hz, 120Hz, 240Hz). Fără această decuplare, comportamentul jocului (viteza jucătorului, fizica de coliziune, cooldown-urile de abilități) ar varia în funcție de rata de cadre a mașinii pe care rulează.

**Plafonul `5 * updateInterval` pe acumulator** există specific pentru a preveni o "spirală a morții": fără el, o pauză lungă (un breakpoint de debugger, o pauză mare de garbage collection, o schimbare de fereastră pe Android) ar lăsa acumulatorul cu o restanță uriașă de timp neprocesat, iar cadrul următor ar încerca să ruleze sute de apeluri `update()` pentru a recupera — ceea ce durează și mai mult, rămâne și mai în urmă, iar jocul nu-și mai revine niciodată. Limitarea restanței la echivalentul a 5 cadre de simulare înseamnă că jocul pare pur și simplu să încetinească scurt după o pauză, în loc să se blocheze definitiv încercând să recupereze timpul real pierdut.

### `update()` — Ce Se Actualizează, În Ce Ordine, și De Ce

Metoda `update()` (aproximativ 285 de linii) este locul unde toată logica de joc per-tick se execută. Structura ei merită înțeleasă în ordine, pentru că ordinea contează:

1. **Contorul de tick** (`tickCounter++`) — folosit pentru eșantionare periodică (de exemplu, actualizarea IA-ului monștrilor îndepărtați o dată la 4 cadre, nu la fiecare cadru).
2. **Tranziția muzicii de titlu** — pornește/oprește muzica temei principale exact la intrarea/ieșirea din ecranul de titlu, într-un singur loc (nu la fiecare punct din cod care ar putea schimba starea în/din titlu).
3. **Reîncărcările de debug în așteptare** — comutatoarele de debug apăsate din UI setează flag-uri `volatile`, dar reîncărcarea efectivă a hărții/NPC-urilor/monștrilor/obiectelor se execută aici, pe thread-ul buclei de joc, nu pe thread-ul UI, pentru a evita condiții de cursă (race conditions).
4. **Flashback-ul de memorie**, dacă e activ, **îngheață tot restul** și face `return` timpuriu.
5. **Bula de gânduri** (`thoughts.update()`), tastatura (`keyH.update()`), animațiile UI — rulează întotdeauna.
6. **Auto-alăturarea prin NFC** (Secțiunea 14.5) — verificată la fiecare tick, nu doar o dată la pornire, pentru ca o atingere repetată în timp ce jocul stă deja pe ecranul de titlu să funcționeze (Android `singleTask` redirecționează prin `onNewIntent`, nu printr-un `onCreate` nou).
7. **Logica specifică stării jocului**: dialogul are propriul tick de typewriter separat de randare (comentariul explică de ce: la 400 FPS, dacă viteza typewriter-ului ar fi legată de randare, dialogul ar deveni dependent de FPS și ar satura CPU-ul cu apeluri `wrapText()`, înfometând thread-ul principal).
8. **Blocul mare de simulare a lumii** — rulează în `playState`, `dialogueState`, `characterState`, `cutsceneState` și `shopState`, nu doar în `playState`: lumea continuă să se simuleze (NPC-uri, monștri, particule, vânt, iluminat) chiar dacă meniul de inventar e deschis sau rulează o cutscenă, astfel încât jocul nu îngheață vizual în fundal — doar `player.update()` este condițional pe `playState`, ca jucătorul să nu se poată mișca/acționa cât timp un meniu are controlul.
9. **Hit-stop global** — când `globalHitstopTimer > 0`, doar particulele/screenShake/damage numbers continuă să se actualizeze; restul lumii îngheață pentru un impact vizual de tip "freeze frame".

Un exemplu instructiv al blocului de simulare a lumii, care arată tratarea diferențiată a entităților în funcție de distanța față de cameră:

```java
// core/src/main/java/main/GamePanel.java
for ( int i = 0 ; i < monster.length ; i++ ) {
    if ( monster[i] != null ) {
        if ( monster[i].alive && !monster[i].dying ) {
            if (isEntityInViewport(monster[i], tileSize * 2)) {
                monster[i].update();
            } else if (isEntityInViewport(monster[i], tileSize * 6)
                       && (tickCounter & 3) == 0
                       && bossIntroCutscene.getBoss() != monster[i]) {
                // Distant monsters: run AI every 4 frames to prevent snap-teleport on re-entry.
                monster[i].setAction();
            }
        }
        // ...
    }
}
```

Monștrii aflați chiar în viewport se actualizează complet, în fiecare cadru. Monștrii ceva mai departe (până la 6 tile-uri) rulează doar decizia de IA (`setAction()`) o dată la 4 cadre — suficient pentru ca, atunci când camera ajunge la ei, să nu "teleporteze" brusc la poziția corectă, dar fără costul complet al actualizării la fiecare cadru pentru entități care nu sunt încă vizibile pe deplin.

### Iluminatul Se Configurează Aici, Nu în `Lightning`

O observație importantă de arhitectură: sursele de lumină nu se acumulează persistent în `Lightning` — se **golesc și se reconstruiesc complet la fiecare tick**, din `GamePanel.update()`:

```java
// core/src/main/java/main/GamePanel.java
if (eManager.lightning != null) {
    eManager.lightning.clearLights();
    eManager.lightning.addLight(
        player.worldX + tileSize / 2, player.worldY + tileSize / 2,
        tileSize * 4, PLAYER_GLOW_COLOR, 0.25f);
    float flickerBase = System.nanoTime() * 0.000000003f;
    addColoredGlows(obj, flickerBase, 0f);
    addColoredGlows(npc, flickerBase, 100f);
    addRemotePlayerGlows();
    fireflyLayer.addLights(eManager.lightning);
}
```

Acest tipar ("golește tot, adaugă tot din nou") este simplu de raționat despre el (nicio lumină "veche" nu poate rămâne agățată dacă sursa ei a dispărut) cu prețul de a reconstrui lista în fiecare cadru — un cost acceptabil dat fiind numărul mic de surse de lumină active simultan. Detaliile complete ale pipeline-ului de iluminat sunt în Secțiunea 7.

### Sincronizarea Jucătorilor la Distanță ca Entități Reale

```java
// core/src/main/java/main/GamePanel.java
/**
 * Rebuilds {@link #remotePlayerEntities} from the live session state (TCP {@link #mpClient} and/or
 * BLE {@link #bleSession}) every tick, so remote players are real {@link entity.Entity} instances —
 * not just draw-only rectangles — for any system (currently: lighting) that wants to treat them as
 * such.
 */
private void syncRemotePlayerEntities() {
    // ...
    if (mpClient != null && mpClient.isConnected()) {
        for (var entry : mpClient.remotePlayers.entrySet()) {
            String key = "tcp:" + entry.getKey();
            // ...
        }
    }
    if (bleSession != null && bleSession.isActive()) {
        for (var entry : bleSession.remotePlayers.entrySet()) {
            String key = "ble:" + entry.getKey();
            // ...
        }
    }
}
```

Namespace-ul cu prefix (`"tcp:0"` vs `"ble:0"`) este esențial pentru corectitudine: ID-urile de jucător ale ambelor transporturi pornesc de la 0, deci fără prefix un jucător TCP #0 și un jucător BLE #0 s-ar suprascrie reciproc dacă cele două transporturi ar fi vreodată active simultan din greșeală. Acest mecanism unificat de sincronizare este exact ce a permis, mai târziu, ca sistemul de iluminat să trateze un jucător la distanță ca pe o sursă de lumină obișnuită — vezi povestea bug-ului de vizibilitate BLE din Secțiunea 12.7, care a avut o cauză complet diferită (randarea, nu sincronizarea de date).

### `draw()` — Randarea Unui Cadru

```java
// core/src/main/java/main/GamePanel.java
public void draw(GdxRenderer g2) {
    renderPipeline.drawCurrentState(g2);

    if (memoryFlashback != null && memoryFlashback.isActive()) {
        memoryFlashback.draw(g2);
    }

    if(keyH.showDebugText) {
        g2.beginDeviceSpace();
        // ... FPS, hartă curentă, coordonate lume, coloană/rând tile ...
        g2.endDeviceSpace();
    }

    if (debugMenuOpen) drawDebugMenu(g2);
}
```

Toată munca reală de randare a lumii/UI-ului este delegată către `RenderPipeline.drawCurrentState()` (Secțiunea 6.3). `GamePanel.draw()` în sine se ocupă doar de suprapunerile globale care nu aparțin niciunui strat specific: overlay-ul flashback-ului de memorie, textul de debug (desenat explicit în **spațiul de dispozitiv**, nu în spațiul logic scalat, ca să rămână clar chiar la mărire fracționară a ferestrei — vezi `beginDeviceSpace()`/`endDeviceSpace()` în Secțiunea 6.2), și panoul de meniu de debug.

### Mașina de Stări a Jocului

`GamePanel` definește starea jocului ca un set de constante întregi, nu ca un `enum` — o alegere pragmatică pentru compatibilitate retroactivă cu cod vechi și cu formatul de salvare:

```java
// core/src/main/java/main/GamePanel.java
public int gameState;
public static final int titleState = 0;
public static final int playState = 1;
public static final int pauseState = 2;
public static final int dialogueState = 3;
public static final int characterState = 4; //inventory state
public static final int optionsState = 5;
public static final int gameOverState = 6;
public static final int cutsceneState = 7;
public static final int transitionState = 8;
public static final int levelUpState = 9;
public static final int skillTreeState = 10;
public static final int multiplayerPlayState = 11;
public static final int journalState = 12;
public static final int shopState = 13;
```

Fiecare valoare determină atât ce se actualizează în `update()` cât și ce se desenează în `RenderPipeline.drawCurrentState()` (Secțiunea 6.3) — este mașina de stări centrală a întregului joc.

### Camera de Dialog: Zoom și Pan Subtil

Un detaliu de "senzație" (feel) demn de menționat: în timpul dialogului, camera nu rămâne statică — face un zoom-in subtil și un recentrare care încadrează jucătorul și NPC-ul:

```java
// core/src/main/java/main/GamePanel.java
public float dlgZoom = 1f, dlgZoomTarget = 1f;
public float dlgPanX = 0f, dlgPanTargetX = 0f;
public float dlgPanY = 0f, dlgPanTargetY = 0f;
public float dlgBars = 0f, dlgBarsTarget = 0f;              // 0..1 letterbox bar progress
public static final float DLG_ZOOM     = 1.3f;
public static final float DLG_LERP     = 0.12f;
public static final int   DLG_BAR_MAX_H = 28;

public void updateDialogueCamera() {
    dlgZoom += (dlgZoomTarget - dlgZoom) * DLG_LERP;
    dlgPanX += (dlgPanTargetX - dlgPanX) * DLG_LERP;
    dlgPanY += (dlgPanTargetY - dlgPanY) * DLG_LERP;
    dlgBars += (dlgBarsTarget - dlgBars) * DLG_LERP;
}
```

Toate cele patru valori converg exponențial (interpolare liniară repetată, "lerp") către țintele lor la fiecare tick, indiferent dacă dialogul e activ sau tocmai s-a încheiat (țintele revin la neutru, iar valorile ies din zoom lin). Aplicarea efectivă (translate + zoom pe `GdxRenderer`) se întâmplă în `RenderPipeline` la momentul randării.

### Camera Blocată (Cutscene-uri)

```java
// core/src/main/java/main/GamePanel.java
public boolean cameraLocked  = false;
public int     cameraWorldX  = 0;
public int     cameraWorldY  = 0;

public int getCamWorldX() { return cameraLocked ? cameraWorldX : player.worldX; }
public int getCamWorldY() { return cameraLocked ? cameraWorldY : player.worldY; }
```

Documentația acestor câmpuri explică distincția arhitecturală importantă: **poziția pe ecran a jucătorului** (`player.screenX/screenY`, punctul fix de ancorare) nu se schimbă niciodată — doar **ce poziție din lume** se mapează la acel punct de ancorare se schimbă, în timpul unei cutscene blocate pe cameră (vezi `ui.BossIntroCutscene`). Orice loc din cod care desenează ceva relativ la "cameră" trebuie să citească `getCamWorldX()`/`getCamWorldY()`, nu direct `player.worldX`/`worldY` — o distincție subtilă dar critică pentru ca introducerile de boss-uri (Secțiunea 8) să poată panorama camera departe de jucător fără să-l teleporteze de fapt.

## 1.4 `Main.java` și `Config.java`

`main/Main.java` (14 linii) e o mică pungă de stare statică globală, redusă drastic față de rolul ei istoric (când desktop-ul avea propriul `bootstrap()` cu verificare de licență legată de registry). Acum conține doar:

```java
// core/src/main/java/main/Main.java
public class Main {
    public static final boolean OFFLINE_MODE = false;
    public static final boolean DEBUG_MODE = true;
    public static volatile String LICENSE_KEY = null;  // Set by platform.LicenseActivation.ensureActivated()

    public static void invalidateLicense() {
        LICENSE_KEY = null;
    }
}
```

`Config.java` (140 linii) gestionează două lucruri distincte: **constantele de scalare de randare** (`originalTileSize`, `scale`, `tileSize` derivat) definite static la nivel de clasă, și **preferințele persistente ale jucătorului** (rezoluție, volum, vsync, calitate grafică) citite/scrise prin `platform.GameStorage` (Secțiunea 10.1) într-un fișier text simplu `config.txt`:

```java
// core/src/main/java/main/Config.java
public static final int GRAPHICS_LOW    = 0;
public static final int GRAPHICS_MEDIUM = 1;
public static final int GRAPHICS_HIGH   = 2;
public int graphicsQuality = GRAPHICS_HIGH;
```

Această constantă `graphicsQuality` este citită direct de `environment.Lightning` pentru a alege între cele trei niveluri ale pipeline-ului de iluminat (Secțiunea 7).

Versiunea jocului se citește static, o singură dată la încărcarea clasei, din același `build.properties` pe care Gradle îl folosește pentru a marca versiunea de proiect (Secțiunea 15) și pe care `patch_server` îl compară la verificarea de actualizări (Secțiunea 16):

```java
// core/src/main/java/main/Config.java
static { loadBuildProperties(); }

private static void loadBuildProperties() {
    try (InputStream is = util.ResourceCache.openClasspathStream("/res/build.properties")) {
        if (is != null) {
            Properties props = new Properties();
            props.load(is);
            gameVersion = props.getProperty("version", "2.0");
            buildNumber = Integer.parseInt(props.getProperty("build", "0"));
        }
    } catch (Exception e) { /* ... */ }
}
```

Faptul că Gradle, clientul care rulează, și serverul de patch-uri citesc toate același fișier `build.properties` (fie direct, fie prin acest cod) este ceea ce garantează că numărul de versiune afișat în joc nu poate diverge niciodată de versiunea pe care Gradle a construit-o efectiv.

---

# 2. Input: Tastatură, Mouse, Acțiuni Abstracte

## 2.1 Trei Straturi: Fizic → Acțiune → Gameplay

Sistemul de input al Echoes of the Heir este structurat pe trei straturi distincte, nu pe unul singur:

1. **Stratul fizic** — `KeyHandler` (implementează `com.badlogic.gdx.InputProcessor`), `MouseHandler` (la fel), `mobile.GamepadInputAdapter` (implementează `ControllerListener`). Fiecare traduce evenimente hardware brute (apăsare de tastă, click de mouse, buton de controller) fie direct în câmpuri booleene vechi (`upPressed`, `dashPressed`), fie în token-uri simbolice trimise mai departe.
2. **Stratul de acțiuni abstracte** — `main.input.InputBindings` (73 linii) și `main.input.InputActions` (183 linii). Acesta este singurul loc din codebase unde se declară "ce fizic declanșează ce acțiune de joc", complet separat de logica de gameplay.
3. **Stratul de gameplay** — `Player.update()`, `KeyHandler`-ul de dispatch pe stare, `Menu`, care citesc fie câmpurile booleene vechi, fie (din ce în ce mai mult, pentru cod nou) `gp.actions.isDown(...)`/`consumePressed(...)`.

Această despărțire pe straturi este motivul pentru care gamepad-ul, tastatura și controalele tactile de pe Android pot coexista fără nicio logică de exclusivitate: toate cele trei surse scriu în același set de câmpuri/acțiuni partajate, iar oricare dintre ele a fost folosită ultima "câștigă" în acel cadru — nu există un "mod de input activ" pe care jocul trebuie să-l urmărească separat.

## 2.2 `InputBindings` — Sursa Unică de Adevăr Pentru Legături

```java
// core/src/main/java/main/input/InputBindings.java
public static final String MOVE_UP    = "move_up";
public static final String ATTACK   = "attack";
public static final String SHOOT    = "shoot";
public static final String INTERACT = "interact";
// ...

private static void loadDefaults() {
    bindings.put(MOVE_UP, List.of("key:W", "key:UP", "controller:buttonDpadUp", "controller:axisLeftY-"));
    bindings.put(DASH,    List.of("key:SHIFT_LEFT", "key:SHIFT_RIGHT", "controller:buttonR1"));
    bindings.put(ATTACK,  List.of("key:ENTER", "controller:buttonX"));
    bindings.put(SHOOT,   List.of("key:F", "controller:buttonR2"));
    // ...
}
```

Fiecare acțiune poate avea mai multe token-uri fizice legate simultan (de exemplu `MOVE_UP` răspunde la tasta W, săgeata sus, D-pad-ul controller-ului, ȘI axa stângă a stick-ului). Valorile implicite din Java sunt suprascrise, acțiune cu acțiune, de `res/data/keybindings.json` — un fișier lipsă sau corupt nu e o eroare, doar înseamnă că fiecare acțiune păstrează valoarea implicită din Java.

Un detaliu arhitectural relevant, documentat direct în comentariul clasei: token-urile de tastatură nu sunt string-urile umane produse de `Input.Keys.toString()` (de exemplu "L-Shift", "Escape"), ci numele constantelor statice reflectate din `Input.Keys` (`SHIFT_LEFT`, `ESCAPE`) — pentru că acestea sunt cele care se potrivesc cu formatul `"key:SHIFT_LEFT"` folosit în JSON. Amestecarea celor două formate ar face ca orice căutare pentru o tastă cu nume compus să eșueze silențios.

### O Poveste Reală de Bug: Dublul Atac de la Click de Mouse

Comentariul din `InputBindings.java` narează un bug real și motivul pentru care a fost evitată o soluție aparent evidentă:

```java
// core/src/main/java/main/input/InputBindings.java
// mouse:left_click is intentionally NOT bound to ATTACK — Player.fireMouseAttackIfRequested()
// already reads mouseH.leftClicked directly and attacks toward the cursor (mouse-aim), a
// completely separate mechanism from ATTACK's facing-direction swing. Double-binding the
// click here made one physical click arm BOTH paths: the facing-direction swing fired
// immediately, but the mouse-aim swing's own !attacking guard blocked it that same frame
// and left mouseH.leftClicked sitting true, unconsumed — so it fired a SECOND, delayed
// attack toward wherever the cursor had drifted to by the time the first swing's animation
// finished.
```

Povestea, reconstruită: cineva a încercat să lege click-ul stânga de mouse și de acțiunea abstractă `ATTACK` (atac spre direcția în care e orientat personajul) — părea rezonabil, click-ul ar trebui să atace. Dar `Player` avea deja propria sa cale separată pentru atacul cu ochire de mouse (`fireMouseAttackIfRequested()`, citind direct `mouseH.leftClicked`). Cu ambele legate, un singur click fizic "arma" ambele căi: atacul cu direcție fixă pornea imediat, dar garda proprie a atacului cu ochire de mouse (`!attacking`) îl bloca în același cadru — lăsând `mouseH.leftClicked` neconsumat. La cadrul următor, acea valoare rămasă adevărată declanșa un **al doilea** atac, întârziat, spre orice poziție ajunsese cursorul între timp. Soluția a fost simplă odată identificată cauza: click-ul de mouse rămâne complet în afara sistemului de acțiuni abstracte pentru atac, gestionat exclusiv de calea proprie `Player`.

## 2.3 `InputActions` — Stare Per-Cadru cu Ref-Counting

```java
// core/src/main/java/main/input/InputActions.java
public void setPhysical(String token, boolean down) {
    ensureIndex();
    List<String> actions = tokenToActions.get(token);
    if (actions == null) return;
    for (String action : actions) {
        int count = heldCount.getOrDefault(action, 0);
        if (down) {
            if (count == 0) pendingPress.put(action, true);
            heldCount.put(action, count + 1);
        } else {
            heldCount.put(action, Math.max(0, count - 1));
        }
    }
}
```

Documentația clasei explică de ce starea "ținută apăsat" este un contor (ref-count), nu un simplu boolean: o acțiune poate fi legată de mai multe token-uri fizice deodată (de exemplu `MOVE_UP` = tasta W SAU săgeata sus), deci eliberarea uneia dintre cele două taste ținute nu trebuie să șteargă acțiunea cât timp cealaltă e încă apăsată. `isDown(action)` verifică `heldCount > 0`; `consumePressed(action)` este un semnal de o singură dată, care se auto-șterge după citire, indiferent cât timp fizic rămâne tasta apăsată — util pentru acțiuni de tip "apasă o dată" (deschide inventarul) spre deosebire de "ține apăsat" (mișcare).

`clearAllPending()` este apelat din `GamePanel.update()` la fiecare tranziție de stare a jocului, cu un motiv documentat explicit:

```java
// core/src/main/java/main/input/InputActions.java
/** Clears every one-shot pending-press flag ... Call this on every game-state transition: a key press
 *  that triggered the transition (e.g. E closing the inventory) may have been read via a
 *  raw key check rather than consumePressed(), leaving its action's pending-press flag
 *  stale — left unconsumed, the next isDown/consumePressed poll in the new state would
 *  fire on it as if it were a fresh press, e.g. instantly reopening the screen just closed. */
```

## 2.4 `KeyHandler` — Dispatch pe Stare de Joc

`KeyHandler` implementează `InputProcessor` complet (`keyDown`, `keyUp`, `keyTyped`, etc.), dar rolul lui central este `dispatchToState(code)`:

```java
// core/src/main/java/main/KeyHandler.java
private void dispatchToState(int code) {
    if (gp.gameState == GamePanel.titleState) {
        handleTitleState(code);
    }
    else if (gp.gameState == GamePanel.playState) {
        handlePlayState(code);
    }
    else if (gp.gameState == GamePanel.pauseState) {
        handlePauseState(code);
    }
    else if (gp.gameState == GamePanel.dialogueState || gp.gameState == GamePanel.cutsceneState) {
        // ...
    }
    // ... câte o metodă handle*State pentru fiecare valoare gameState
}
```

Fiecare `handleXState` citește starea de acțiune prin `gp.actions.consumePressed(...)`, **nu** parametrul `code` primit — cu excepția câtorva afordanțe genuin specifice tastaturii (BACKSPACE pentru ștergere text, TAB pentru a cicla printr-un câmp text, tastele de debug), care sunt no-op intenționat pentru un apel provenit de la controller (comentariul din cod notează că `code` devine `CONTROLLER_CALL` (-1) în acel caz, o valoare care nu se potrivește niciunei constante reale `Input.Keys`).

Această metodă `dispatchToState` este **partajată** între evenimentele reale de tastatură (`keyDown`) și evenimentele de buton de gamepad (`onControllerButton`), care nu trec niciodată prin callback-ul `keyDown` al libGDX — `ControllerListener` e o sursă de evenimente complet separată. Partajarea aceleiași metode de dispatch garantează că un buton A de controller și tasta ENTER produc exact același comportament în orice ecran de meniu.

### Modul de Debug: Ctrl+D, Ctrl+W, F5–F10

```java
// core/src/main/java/main/KeyHandler.java
// Ctrl+D — enable / disable debug mode entirely
if (/* Ctrl+D */) {
    gp.debugModeEnabled = !gp.debugModeEnabled;
    if (!gp.debugModeEnabled && gp.debugMenuOpen) gp.toggleDebugMenu();
}
// Ctrl+W — toggle the Wind Painter (debug mode only)
if (/* Ctrl+W */) {
    if (gp.debugModeEnabled && gp.windPainter != null) gp.windPainter.toggle();
}
if (gp.debugModeEnabled) {
    if (code == Input.Keys.T) { showDebugText = !showDebugText; }
    if (code == Input.Keys.F5) { gp.toggleDebugSepia(); }
    // ...
}
```

**Ctrl+D** este comutatorul general care activează modul de debug (necesar înainte ca orice altă tastă de debug să funcționeze — inclusiv `H` pentru panoul de debug, care afișează explicit un mesaj în consolă dacă e apăsat înainte de Ctrl+D). **Ctrl+W** deschide Pictorul de Vânt (`WindPainter`, Secțiunea 7.4), disponibil doar în modul de debug. Separat de acest lanț, `MichiGame.render()` sondează necondiționat tastele **F5–F10** pentru a comuta switch-urile de debug ale pipeline-ului de iluminat (`gfx.shader.LightDebug.hud/freezeTime/noDetail/noBloom/noShadows/noRim`) — aceste comutatoare funcționează indiferent de starea jocului, pentru că sunt sondate direct în `MichiGame`, nu prin `KeyHandler`.

### Repetiție de Meniu Fără Delay-ul OS

```java
// core/src/main/java/main/KeyHandler.java
// Menu navigation key-repeat (bypasses slow OS repeat delay). Sourced from
// gp.actions.isDown(MENU_*) every poll (see pollMenuDirections()), so keyboard AND
// controller d-pad/stick both drive this identically — no separate gamepad menu-nav
// code needed anywhere in this file.
private boolean menuUp, menuDown, menuLeft, menuRight;
private static final int MENU_INITIAL_DELAY = 10; // frames before first repeat (~167ms)
private static final int MENU_REPEAT_RATE = 4;    // frames between repeats (~67ms)
```

Navigarea în meniuri (sus/jos/stânga/dreapta) implementează propria repetiție de tastă la nivel de joc, în loc să se bazeze pe repetiția implicită a sistemului de operare — aceasta din urmă are un delay inițial prea mare pentru o navigare de meniu confortabilă. Faptul că această logică citește `gp.actions.isDown(MENU_*)` (nivelul de acțiune abstractă) în loc de stare brută de tastă este exact motivul pentru care gamepad-ul obține "gratuit" aceeași repetiție de navigare, fără cod separat.

## 2.5 `MouseHandler` — Ochirea Atacului și Click-urile de Meniu

`MouseHandler` (681 linii) implementează `InputProcessor` și gestionează atât ochirea atacului liber (unghiul de atac urmărește cursorul, independent de direcția cardinală `direction` a personajului — vezi Secțiunea 4.2) cât și click-urile de UI/inventar. Câmpurile sale principale — `gameX`, `gameY`, `leftClicked` — sunt citite direct de `Player.fireMouseAttackIfRequested()` pentru a calcula unghiul de atac spre poziția cursorului convertită în coordonate de lume.

## 2.6 Cum Se Mapează Controalele Tactile Peste Același Sistem

Pe Android, `mobile.TouchControlsOverlay` (302 linii) — un `Scene2D Stage` separat, cu propriul `ScreenViewport`, independent de camera lumii jocului — nu introduce nicio cale nouă de gameplay. În schimb, scrie direct în aceleași câmpuri pe care tastatura și mouse-ul le scriu deja:

- **Mișcarea**: un `Touchpad` (`Touchpad(10f, skin)`, cu o zonă moartă `DEADZONE = 0.3f`) traduce poziția manetei virtuale în cele patru booleene `gp.keyH.upPressed/downPressed/leftPressed/rightPressed` — exact aceleași patru booleene pe care WASD le setează, citite continuu de `Player.update()`.
- **Atacul**: o atingere scurtă (sub 180ms) setează `gp.mouseH.gameX/gameY` la centrul exact al ecranului jucătorului, apoi `leftClicked = true` — reutilizând deliberat un fallback existent: `getAttackAngleFromMouse()` revine deja la `angleForDirection(player.direction)` ori de câte ori `dx == dy == 0` (anterior accesibil doar printr-un atac doar-de-tastatură fără mișcare de mouse) — deci atacurile tactile ochesc automat spre direcția curentă a jucătorului, fără nicio interfață nouă de ochire.
- **Tragerea la distanță (SHOOT)**: singura excepție de la tiparul "setează și lasă" — `shotKeyPressed` e în mod normal golit de `KeyHandler.keyUp()` la eliberarea fizică a tastei, deci overlay-ul setează explicit `true` apoi `false` peste granița a exact un cadru per atingere, ca să nu tragă la fiecare cadru cât timp cooldown-ul permite.
- **Abilitățile** (dash, shockwave, void snare, frost nova, overdrive): fiecare buton tactil este ascuns complet (nu doar dezactivat vizual) până când flag-ul corespunzător `Player.*Unlocked` e adevărat, reverificat în fiecare cadru — pentru că abilitățile se pot debloca în mijlocul unei sesiuni prin skill tree.

Detaliile complete ale controalelor tactile — inclusiv de ce Stage-ul overlay-ului trebuie înregistrat *primul* în `InputMultiplexer` — sunt tratate pe larg în Secțiunea 14.4.

---

# 3. Coliziune și Fizică de Bază

## 3.1 `CollisionChecker` — Grilă Spațială Peste Un Amestec de Forme

`main/CollisionChecker.java` (652 linii) rezolvă coliziunea împotriva a două surse combinate de "solid" din hartă: **coliziune bazată pe tile** (dacă stratul de tile-uri de coliziune e configurat) și **coliziune bazată pe forme** (dreptunghiuri, dreptunghiuri rotite, elipse, poligoane provenite din straturile de obiecte din Tiled — vezi Secțiunea 5.4). Ambele căi sunt verificate în aceeași metodă.

### Grila Spațială — De la O(n) la O(k)

```java
// core/src/main/java/main/CollisionChecker.java
// OPTIMIZATION: Spatial grid for collision rectangles
private static final int GRID_CELL_SIZE = 128; // pixels per grid cell (2 tiles)
private ArrayList<Integer>[] spatialGrid;

@SuppressWarnings("unchecked")
public void updateCollisionRectsCache() {
    int shapeCount = gp.tileM.collisionShapes.size();
    gridCols = (gp.worldWidth / GRID_CELL_SIZE) + 2;
    gridRows = (gp.worldHeight / GRID_CELL_SIZE) + 2;
    spatialGrid = new ArrayList[gridCols * gridRows];
    for (int i = 0; i < shapeCount; i++) {
        Rect r = gp.tileM.collisionBounds.get(i);
        int minCellX = Math.max(0, r.x / GRID_CELL_SIZE);
        // ... bagă indexul formei i în fiecare celulă a grilei pe care o suprapune ...
    }
}
```

Fiecare formă de coliziune din hartă (posibil sute pe o hartă mare) este indexată o dată, la încărcarea hărții, într-o grilă spațială cu celule de 128 pixeli (2 tile-uri). O interogare de coliziune (`rectHitsCollision`, `checkTile`) nu mai scanează liniar toate formele — calculează doar celulele de grilă pe care dreptunghiul interogat le suprapune și verifică doar formele indexate acolo. Comentariul din cod notează explicit câștigul: de la O(n) (scanare completă) la O(k), unde k e numărul de forme din celulele vecine — folosit în special de `PathFinder` (Secțiunea 4), care altfel ar rula o scanare O(n) completă pentru fiecare nod A* evaluat.

### Coliziune "Măturată" (Swept) Pentru Tile-uri

```java
// core/src/main/java/main/CollisionChecker.java
public void checkTile(Entity entity) {
    tempRect.x = entity.worldX + entity.solidArea.x;
    tempRect.y = entity.worldY + entity.solidArea.y;
    tempRect.width = entity.solidArea.width;
    tempRect.height = entity.solidArea.height;

    // Swept collision: extend rect from current to predicted position
    switch(entity.direction) {
        case Entity.DIR_UP:    tempRect.y -= entity.speed; tempRect.height += entity.speed; break;
        case Entity.DIR_DOWN:  tempRect.height += entity.speed; break;
        case Entity.DIR_LEFT:  tempRect.x -= entity.speed; tempRect.width += entity.speed; break;
        case Entity.DIR_RIGHT: tempRect.width += entity.speed; break;
    }
    entity.collisionOn = false;
    // ... verifică tile-uri blocante + forme de coliziune în dreptunghiul extins ...
}
```

Dreptunghiul de coliziune al entității este **extins** în direcția mișcării cu exact `entity.speed` pixeli înainte de testul de intersecție — o formă simplă de coliziune "măturată" (swept collision) care previne tunelarea printr-un perete subțire la viteze mari, fără a necesita un algoritm complet de detectare a coliziunii continue. `tempRect` este un obiect `Rect` reutilizat (câmp de instanță, nu alocat per apel) — încă o optimizare pentru calea fierbinte, evitând presiunea asupra colectorului de gunoi la fiecare tick de fiecare entitate.

### Familia de Metode `check*`

| Metodă | Scop |
|---|---|
| `checkTile(entity)` | Coliziune măturată împotriva tile-urilor blocante + formelor din hartă, în direcția curentă de mișcare a entității |
| `checkTileNext(entity, nextX, nextY)` | Coliziune la o poziție viitoare explicită (nu măturată de la poziția curentă) — folosită de IA pentru a testa mutări candidate înainte de a le comite |
| `checkObject(entity, player)` | Coliziune/interacțiune cu obiecte din lume (cufere, uși, poțiuni) |
| `checkEntity(entity, target[])` | Coliziune dreptunghi-cu-dreptunghi împotriva unui array de entități (NPC-uri, monștri) |
| `checkEntityCone(cone, target[])` | Testează un singur con de atac (Secțiunea 4.2) împotriva unui array de entități — prima lovitură găsită |
| `checkEntityConeAll(cone, target[])` | Ca mai sus, dar returnează **toate** entitățile lovite (pentru atacuri în arie) |
| `checkPlayer(entity)` | Verificare specifică de coliziune monstru-vs-jucător |

Coliziunea în con (`gfx.geom.Cone`, Secțiunea 6.4) este mecanismul din spatele hitbox-ului de atac cu ochire liberă al jucătorului — nu un dreptunghi static "în fața" personajului, ci un sector circular orientat după unghiul real de atac (fie ochit cu mouse-ul, fie implicit spre direcția cardinală).

### `isSolidAt` — O Reparație Pentru Tunelarea Prin Obiecte La Lovitura de Atac

```java
// core/src/main/java/main/CollisionChecker.java
/**
 * Would entity's solidArea, placed at the explicit position (nextX, nextY), overlap any
 * solid (collision == true) obj/iTile/npc/monster? Unlike checkObject/checkEntity (which predict
 * one step of the entity's own speed in its current facing direction), this takes an arbitrary
 * target position — needed for the attack lunge (applyAttackKick), which pushes the player by a
 * variable, eased distance each frame rather than a fixed per-tick speed. Without this, the lunge
 * only checked static tile collision (checkTileNext) and could push the player straight through a
 * solid interactive tile/object (a chest, a pot, an unbroken destructible) it was attacking.
 */
public boolean isSolidAt(Entity entity, int nextX, int nextY) { /* ... */ }
```

Aceasta e o poveste tipică de bug de coliziune: "lunge"-ul de atac al jucătorului (o mică propulsie înainte, în timpul animației de swing) folosea inițial doar verificarea statică de tile-uri, pentru că fiecare cadru al lunge-ului mișcă jucătorul cu o distanță variabilă, cu easing — nu cu viteza fixă per-tick pe care restul metodelor `check*` o presupun. Rezultatul: jucătorul putea trece direct prin obiecte solide interactive (un cufăr, o oală nespartă) în timp ce ataca. Soluția a fost o nouă metodă dedicată, care acceptă o poziție-țintă explicită în loc să deducă mișcarea din viteza curentă a entității.

---

# 4. Entități: Player, Entity, NPC, Boss

## 4.1 `Entity` — Clasa de Bază

`core/src/main/java/entity/Entity.java` (1766 linii) este clasa de bază pentru absolut orice obiect din lume: jucătorul, NPC-urile, monștrii, boss-ii, obiectele, particulele, proiectilele. Structura ei se împarte în: declarații de câmpuri (tip, stare de animație, stare de luptă, câmpuri de dialog/quest, câmpuri de magazin, câmpuri de fragment de memorie), ciclul de viață (`update()`/`draw()`), pathfinding (`searchPath`, `followWaypoints`, `directChase`), rezolvarea animației, și utilitare de încărcare a spritesheet-urilor.

### Câmpuri Cheie

- `worldX, worldY` — poziția în pixeli în lume; `direction` — una din cele 4 constante cardinale (`DIR_DOWN`, `DIR_LEFT`, `DIR_RIGHT`, `DIR_UP`); `solidArea` — dreptunghiul de coliziune.
- `hurtPolygon` — un `IntPolygon` opțional în formă de octogon (setat prin `setOctagonHurt`), folosit preferențial față de `solidArea` pentru hitbox-urile de lovitură corp-la-corp când e prezent.
- `type` — constante `TYPE_PLAYER`..`TYPE_UTILITY`.
- `walkFrames`/`idleFrames`/`attackFrames`/`attackFrames2/3` — array-uri `Sprite[][]` indexate pe direcție, plus `activityAnimations: HashMap<String, Sprite[][]>` pentru animațiile custom ale NPC-urilor bazate pe date (Secțiunea 4.3).
- `dialogueNameMap: HashMap<String,Integer>` — mecanismul care rezolvă chei de dialog numite (de exemplu `"intro"`, `"thanks"`, folosite în QUEST_GUIDE.md/NPC_JSON_GUIDE.md) la indexul de set numeric corespunzător.

### Knockback: Impuls Rapid, Nu Alunecare cu Viteză Constantă

```java
// core/src/main/java/entity/Entity.java
/**
 * Advances an in-progress knockback by one frame: fast burst that decays every frame instead of
 * a constant-speed slide, stopping once the velocity trails off or a collision is hit.
 */
protected boolean tickKnockback() {
    // ...
    // Sub-pixel accumulation: at high decay the per-frame step can be well under 1px, so we
    // carry the fractional remainder forward instead of losing it to int truncation
    knockBackAccumX += knockBackVelX;
    // ...
}
```

Knockback-ul folosește `KNOCKBACK_DECAY = 0.80f` și `KNOCKBACK_BURST_MULTIPLIER = 3.2f` (un multiplicator de viteză inițială calibrat astfel încât distanța totală parcursă să se potrivească cu vechea alunecare la viteză constantă). Deoarece la o decădere mare pasul per-cadru poate fi bine sub 1 pixel, poziția fracționară rămasă este acumulată în loc să fie pierdută prin trunchiere la întreg — altfel un knockback ar putea părea că se oprește instant din cauza rotunjirii repetate la zero.

Un detaliu subtil, documentat direct în comentariul din cod, despre ordinea de verificare a stărilor de control:

```java
// core/src/main/java/entity/Entity.java
// handling knockback first — check collision before moving to prevent wall phasing
if (tickKnockback()) {
    // i-frames must still count down during knockback, otherwise a fresh hit re-arming
    // knockback every swing (see Player.damageMonster) freezes invincible permanently and
    // the target can never be damaged again after the first hit.
```

Dacă timpul de invincibilitate (i-frames) s-ar opri din numărătoare cât timp entitatea e în knockback, iar fiecare nouă lovitură ar re-arma un nou knockback (deci ar re-întrerupe numărătoarea), invincibilitatea nu s-ar mai epuiza niciodată — o țintă lovită o dată ar deveni efectiv invulnerabilă pentru totdeauna, pentru că nu ar mai putea fi lovită din nou ca să iasă din bucla de knockback.

### Sistemul de Umbre: Cârligul `castsShadow()`/`drawOccluder()`

Acesta este exact punctul de integrare pe care pipeline-ul de iluminat din Secțiunea 7 îl folosește pentru a determina ce aruncă umbră:

```java
// core/src/main/java/entity/Entity.java
/**
 * Draw this entity's silhouette into the shadow-occluder mask (Stage 2 lighting). The occluder pass
 * clears to transparent and draws every caster's current sprite in solid black; the light shader then
 * ray-marches this mask so lit pixels behind a silhouette fall into shadow. We draw the SAME frame at
 * the SAME screen rect as draw(), so the cast shadow matches the visible pose.
 */
public void drawOccluder(GdxRenderer g2) {
    if (!castsShadow()) return;
    Sprite s = resolveCurrentSprite();
    // ...
    g2.drawImageTinted(s, drawX, drawY, drawW, drawH, Color.BLACK, 1f);
}
```

`castsShadow()` returnează implicit `true` pentru tipurile `PLAYER`/`NPC`/`MONSTER`, sau dacă flag-ul boolean `castsShadow` a fost setat explicit (folosit de obiecte decorative precum copaci/lăzi care optează să arunce umbră). `resolveCurrentSprite()` este partajată între `draw()` și `drawOccluder()`, garantând că umbra aruncată se potrivește întotdeauna cu poza/orientarea vizibilă — nu poate exista o discrepanță unde umbra arată o poză diferită de sprite-ul afișat.

Un comentariu explică și de ce vechea umbră de tip "pată ovală" a fost eliminată complet:

```java
// core/src/main/java/entity/Entity.java
// Shadows are a consequence of light: the sprite's own pixels are drawn into the occluder
// mask (drawOccluder) and the light shader ray-marches them. No hardcoded blob shadow — the
// old oval drop-shadow was a fake "shadow object" independent of any light and is removed.
```

Aceasta reflectă o schimbare de filozofie: în loc de o umbră falsă, desenată necondiționat sub fiecare entitate indiferent de starea de iluminare, umbrele devin acum o **consecință reală** a existenței luminii și a ocluderelor — dacă nu e nicio lumină apropiată, nu apare nicio umbră, exact cum s-ar întâmpla fizic.

### Efecte de Tentă GPU-Native (Telegraph de Atac, Hit Flash)

```java
// core/src/main/java/entity/Entity.java
// ATTACK TELEGRAPH: tint sprite red when about to attack.
// GPU-native: redraw the sprite tinted red at telegraphAlpha — the sprite's own alpha
// masks the tint to its silhouette (equivalent to the old SRC_ATOP buffer composite).
```

Vechea implementare Java2D compunea o imagine intermediară cu regula `AlphaComposite.SRC_ATOP` pentru a colora doar pixelii nenuli ai sprite-ului. Varianta libGDX obține exact același efect vizual redesenând sprite-ul cu o tentă (culoare + alpha) direct la momentul randării, prin `drawImageTinted` — canalul alpha propriu al sprite-ului maschează automat tenta la silueta lui, fără nicio imagine intermediară alocată.

### Recompensele de Moarte și Autoritatea de Server

```java
// core/src/main/java/entity/Entity.java
// In multiplayer, XP, coins and the level-up are the server's to grant: it credits
// them on the authoritative kill and pushes back player_stats (with leveledUp when a
// level was gained). Awarding them here too would let a client mint its own XP/gold,
// which is exactly what we're closing — so skip the local award and let the server's
// player_stats be the only thing that changes these.
boolean serverOwnsRewards = gp.multiplayerMode
        && gp.mpClient != null && gp.mpClient.isConnected();
```

Acest fragment concretizează, la nivel de cod, exact principiul de autoritate de server discutat pe larg în Secțiunea 12: clientul verifică dacă e conectat la un server autoritar și, dacă da, **nu** acordă local XP/monede — le lasă exclusiv pe seama următorului mesaj `player_stats` primit de la server.

### Pathfinding: Cache de Drum cu Detectare de Blocaj

```java
// core/src/main/java/entity/Entity.java
// Reuse cached path while the goal tile hasn't changed
if (goalCol == pathCacheGoalCol && goalRow == pathCacheGoalRow
        && waypointIdx < waypointCount
        && pathStallCounter < PATH_STALL_LIMIT) {
    followWaypoints();
    return;
}
```

O entitate care urmărește o țintă (de obicei jucătorul) nu recalculează drumul A* la fiecare tick — reutilizează calea cache-uită cât timp tile-ul țintă nu s-a schimbat și entitatea nu pare blocată (`pathStallCounter < PATH_STALL_LIMIT`). Dacă entitatea rămâne blocată suficient de mult (contorul de "de negăsit" trece de `UNREACHABLE_GIVE_UP_LIMIT`), IA renunță la urmărire și revine la starea de idle/hoinăreală, în loc să împingă la nesfârșit împotriva unui obstacol.

## 4.2 `Player` — Aprofundare

`core/src/main/java/entity/Player.java` (2740 linii) este cel mai mare fișier din codebase și conține toată logica specifică jucătorului: mișcare, statistici, inventar, luptă, abilități, animație.

### Mișcare Bazată pe Fizică, Nu pe Viteză Constantă

Comentariile din cod documentează direct formula fizică folosită:

```java
// core/src/main/java/entity/Player.java
// Physics-based movement: F_D = 0.5 * rho * v^2 * C_D * A
// rho=1.225 kg/m^3, C_D=1.0, A=0.5 m^2, mass=60 kg — scaled to pixel/frame units.
private static final float PLAYER_MASS  = 60f;    // kg
private static final float DRAG_K       = 1.44f;  // 0.5 * rho * C_D * A, tuned to pixel space
private static final float DRIVE_ACCEL  = 0.4f;   // px/frame^2 drive force per unit mass while key held
// Pure quadratic drag (k*v^2) vanishes as v->0, so the glide never actually finishes decelerating
// and the player coasts for seconds. A flat minimum brake keeps the stop crisp at low speed.
private static final float MIN_STOP_DRAG = 0.18f;  // px/frame^2 floor applied only while coasting
```

Mișcarea jucătorului nu e o simplă atribuire de viteză constantă cât timp o tastă e apăsată — este modelată ca un corp fizic supus unei forțe de propulsie (`DRIVE_ACCEL`, aplicată cât timp tasta e ținută) și unei rezistențe la înaintare pătratice (`DRAG_K * v²`, formula standard de rezistență aerodinamică `F_D = ½·ρ·C_D·A·v²`). Comentariul notează un detaliu fin de tuning: o rezistență pur pătratică se apropie asimptotic de zero pe măsură ce viteza scade, deci fără o frână minimă suplimentară (`MIN_STOP_DRAG`) jucătorul ar "pluti" vizibil câteva secunde bune după eliberarea tastei, în loc să se oprească răspicat.

### Sistemul de Vânt Ca Forță Reală

```java
// core/src/main/java/entity/Player.java
// Wind: a real force vector sampled from the map's WindField, applied as F/mass per frame.
// Only the component along the player's movement axis is used (tailwind faster / headwind
// slower) — the sideways component is discarded so the player is never pushed off-course.
private static final float WIND_FORCE_SCALE = 9.0f;
private final Rect inertiaArea = new Rect();
```

Zona de inerție (`inertiaArea`) — hitbox-ul pe care vântul îl împinge — este mai mare decât `solidArea` de coliziune, dimensionată să acopere corpul vizibil al personajului (aproximativ 44×52 pixeli la un tile de 64px), pentru un echilibru vizual: vântul împinge un "corp de om" plauzibil, nu doar picioarele. Detaliile complete ale sistemului de vânt (fișier `.windmap`, pictorul de vânt, formatul binar) sunt în Secțiunea 7.4.

### Atacul cu Ochire Liberă și Conul de Atac

```java
// core/src/main/java/entity/Player.java
// Free-aim attack angle (radians, atan2 convention) — independent of the cardinal `direction`
// field. `direction` stays cardinal for body sprite/frontal-armor/knockback/AI; `attackAngle`
// drives the cone hitbox, the rotated slice VFX, and the attack kick.
private double attackAngle = 0.0;
public gfx.geom.Cone attackCone;
private static final double ATTACK_CONE_RADIUS_SCALE = 1.35; // × tileSize
private static final double ATTACK_CONE_HALF_ANGLE = Math.toRadians(55);
```

O distincție de arhitectură esențială: `direction` (cardinal, 4 valori) rămâne folosit pentru sprite-ul corpului, armura frontală, direcția de knockback și IA, în timp ce `attackAngle` (unghi continuu, convenție `atan2`) e complet independent și pilotează hitbox-ul conic de atac, efectul vizual de tăietură rotită, și impulsul de "lunge" al atacului. Această separare permite jucătorului să lovească spre orice unghi (ochit cu mouse-ul) fără ca sprite-ul corpului să trebuiască să aibă 360 de cadre de orientare — doar 4.

### Nivelarea: Formula Exactă

```java
// core/src/main/java/entity/Player.java
public void checkLevelUp() {
    while (exp >= nextLevelExp) {
        exp -= nextLevelExp;  // subtract — XP bar resets each level
        level++;
        nextLevelExp = 4 + level * 3;  // linear growth: 7, 10, 13, 16, 19...
        skillPoints++;
        life = Math.min(life + 2, maxLife);
        triggerLevelUpEffects();
        generateLevelUpChoices();
        gp.gameState = GamePanel.levelUpState;
    }
}
```

Formula `nextLevelExp = 4 + level * 3` este liniară și simplă intenționat — și, critic, este **duplicată** aproape identic în Python pe partea de server (`SERVERS/multiplayer_server/server.py`, vezi Secțiunea 12.4), cu comentariul propriu al serverului notând explicit "matches Player.java: 7, 10, 13, ...". Cele două implementări nu sunt partajate ca o singură sursă de adevăr — sunt menținute manual sincronizate, un cost de întreținere cunoscut și acceptat, pentru ca predicția locală a clientului (afișată instant, înainte ca serverul să confirme) să nu difere vizibil de decizia finală a serverului.

**Alegerea statisticilor la nivelare în multiplayer** exclude deliberat viteza din lista de opțiuni oferite:

```java
// core/src/main/java/entity/Player.java
/**
 * Build the level-up options. In multiplayer the pick is authorised server-side, and the
 * server only grants the four stats it tracks — "speed" has no server representation, so it is
 * excluded from the offered pool rather than offered and silently dropped.
 */
private void generateLevelUpChoices(boolean multiplayer) {
    java.util.List<String[]> pool = new java.util.ArrayList<>(java.util.Arrays.asList(
        new String[]{"maxLife",   "+1 Max HP",    "1"},
        new String[]{"strenght",  "+1 Strength",  "1"},
        new String[]{"dexterity", "+1 Dexterity", "1"},
        new String[]{"maxMana",   "+1 Max Mana",  "1"}
    ));
    if (!multiplayer) {
        pool.add(new String[]{"speed", "+1 Speed", "1"});
    }
    // ...
}
```

Aceasta e o consecință directă a migrării la autoritate de server: dacă serverul nu are o reprezentare pentru statistica "viteză" în modelul lui de jucător, oferirea acelei opțiuni clientului ar produce o alegere care pare să reușească vizual dar care se pierde silențios la următoarea sincronizare cu serverul — deci opțiunea e eliminată din start, nu doar ignorată după alegere.

## 4.3 `NPC_Generic` — NPC-uri Bazate Complet pe Date

`core/src/main/java/entity/NPC_Generic.java` (501 linii) este clasa din spatele fluxului descris în NPC_JSON_GUIDE.md: adăugarea unui NPC nou necesită zero cod Java, doar o intrare JSON și un obiect Tiled cu `type = NPC_Generic` + `npcId = ...`.

### Mașina de Stări de Activitate

Fiecare `NPCActivityState` are un `animationKey`, `direction`, poziție de pathfind opțională, un set de dialog, și o listă de condiții combinate prin AND: `requiredQuestComplete`, `requiredQuestActive`, `requiredFragments`, `requiredBoss`, `requiredStoryAct`, `requiredLevel`, plus `npcNotMet` (un `objectId` care nu trebuie să fie deja în `gp.metNPCs`).

```java
// core/src/main/java/entity/NPC_Generic.java
public void evaluateActivityState() {
    NPCActivityState candidate = null;
    for (NPCActivityState state : activityStates) {
        if (matchesConditions(state)) {
            candidate = state;
        }
    }
    // ultima stare care se potrivește câștigă — nu prima
}
```

Regula "ultima potrivire câștigă" (nu prima) e regula documentată deja în NPC_JSON_GUIDE.md, dar codul o confirmă exact: bucla parcurge toate stările în ordine și reține ultima potrivire, ceea ce înseamnă că starea implicită trebuie plasată **prima** în listă și stările tot mai specifice trebuie plasate **după** ea.

### NPC-uri Găzduite pe Server: `speak()` Ca Dispatch pe Straturi de Prioritate

```java
// core/src/main/java/entity/NPC_Generic.java
/**
 * True from the moment we send npc_interact until the server's npc_dialogue comes back.
 * Needed because the state flip into dialogueState is what normally stops Player.update()
 * from calling speak() again — and for a server-driven NPC that flip is a network round-trip
 * away. Without this, every frame the player holds Enter fires another npc_interact, so a
 * single conversation floods the server and re-opens the box on each reply.
 */
public boolean awaitingServerDialogue = false;
```

Aceasta este o poveste reală de bug rezolvat prin proiectare: în single-player, apăsarea și ținerea tastei ENTER nu declanșează conversații repetate pentru că prima apăsare schimbă imediat starea jocului în `dialogueState`, iar `Player.update()` nu mai apelează `speak()` din nou. Dar pentru un NPC găzduit pe server, acea schimbare de stare depinde de un răspuns de rețea care nu sosește instant — fără un flag explicit `awaitingServerDialogue`, fiecare cadru cu ENTER ținut ar trimite un nou `npc_interact` către server, inundându-l și redeschizând cutia de dialog la fiecare răspuns.

Ordinea de dispatch în `speak()` este strict ierarhică: (1) verificare NPC găzduit pe server (trimite `npc_interact` și oprește orice procesare locală) → (2) sincronizarea stării condus de quest → (3) `questManager.executeStepForNpc` → (4) salutul de magazin → (5) darul de la prima interacțiune → (6) dialogul condiționat de obiect deținut → (7) revendicarea fragmentului de memorie → (8) lanțul de pași al NPC-ului → (9) dialogul post-mers → (10) implicit: ciclează setul de dialog următor.

Un detaliu subtil pentru NPC-urile găzduite pe server care participă la quest-uri: quest-urile rulează în continuare pe client (Secțiunea 9), iar a vorbi cu un NPC este exact modul în care un pas de tip `talk` avansează — dar clientul nu poate reda el însuși acea replică (un NPC găzduit pe server nu deține text de dialog local). Soluția: clientul rulează pasul de quest pentru **efectele** lui (dă obiectul, avansează contorul), capturează numele dialogului cerut, și lasă serverul să caute textul efectiv:

```java
// core/src/main/java/entity/NPC_Generic.java
if (gp.questManager != null && objectId != null) {
    gp.questManager.executeStepForNpc(objectId, this);
}
gp.mpClient.sendNpcInteract(serverNpcId, serverDialogueRequest);
```

### Optimizarea de Încărcare a Foilor de Sprite: De la 12 Secunde la Instant

```java
// core/src/main/java/entity/NPC_Generic.java
public void getImage() {
    if (imageLoaded) return;
    // Batch CPU pixel reads: getRGB()/croppedBottomAligned() below are called thousands of
    // times per sheet; without batching each call re-decodes the whole PNG (~12s on class
    // select). The batch decodes each source texture once and disposes it at the end.
    Sprite.beginPixelBatch();
    // ...
}
```

Acesta este un exemplu clasic de bug de performanță "ascuns" — fiecare apel individual de citire de pixel (`getRGB()`) redecoda întregul PNG sursă dacă nu era prins într-un lot, iar un spritesheet de NPC putea necesita mii de astfel de citiri la încărcare (pentru decuparea cadrelor individuale). Rezultatul măsurat a fost o pauză de aproximativ 12 secunde la selectarea unei clase/începerea unei hărți cu mulți NPC-uri. Soluția — `Sprite.beginPixelBatch()`/`endPixelBatch()` — decodează fiecare textură sursă o singură dată, apoi servește toate citirile de pixeli individuale din acea decodare cache-uită în memorie, eliminând complet redecodarea repetată.

## 4.4 `Boss` — Clasa de Bază Pentru Boss-i

`core/src/main/java/entity/Boss.java` (726 linii) oferă un cadru comun pentru boss-i: animație pilotată de sprite (idle/mers/atac/lovit/moarte), o bară de viață etichetată cu numele, și un atac corp-la-corp telegrafat simplu. Documentația proprie a clasei rezumă modelul de extensie:

> "To add a new boss: extend this class, load your sprite sheets with loadDirectionalSheet(...) into walkFrames/idleFrames/attackFrames/hurtFrames/deathFrames, set the stat fields, and override setAction() for custom AI (or just call chaseAndAttack(range) for simple melee chasers)."

### Sistemul de Faze

```java
// core/src/main/java/entity/Boss.java
private void checkPhase2Transition() {
    if (phase2Active || phase2HealthFraction <= 0f || dying) return;
    if (life > maxLife * phase2HealthFraction) return;
    phase2Active = true;
    if (phase2Name != null) name = phase2Name;
    if (phase2FullHeal) life = maxLife;
    attackCooldown = Math.max(1, Math.round(attackCooldown * phase2AttackCooldownMultiplier));
    if (summonMonsterId != null) summonTimer = randomInterval(summonIntervalMinTicks, summonIntervalMaxTicks);
    if (teleportIntervalMinTicks > 0) teleportTimer = randomInterval(teleportIntervalMinTicks, teleportIntervalMaxTicks);
}
```

Tranziția la faza 2 se întâmplă o singură dată, la un prag de HP configurabil (`phase2HealthFraction`, corespunde direct proprietății Tiled `phase2Threshold` din TILED_ENTITY_GUIDE.md). Fiecare comportament al fazei 2 este opțional și activat individual: redenumire, vindecare completă, multiplicator de cooldown al atacurilor (`< 1` = atacă mai des), invocarea de sub-monștri, teleportare periodică.

**Invocarea de sub-monștri** respectă o regulă anti-lovitură-ieftină documentată explicit:

```java
// core/src/main/java/entity/Boss.java
// Summons never land within this many tiles of the player (Chebyshev distance), even if that
// spot is otherwise a valid ring tile around the boss — spawning right on top of the player
// feels like a cheap surprise hit rather than a fair "something new joined the fight" moment.
private static final int SUMMON_MIN_PLAYER_DIST_TILES = 2;
```

**Teleportarea** reutilizează animația de moarte existentă în ambele sensuri, în loc să necesite artă dedicată:

```java
// core/src/main/java/entity/Boss.java
/**
 * Teleport = deathFrames played forward in place (vanish), then a new spot is picked near the
 * player, then deathFrames played backward at the new spot (reappear) — reusing the death
 * animation both ways instead of needing dedicated teleport art.
 */
```

**Prevenirea daunelor de contact**: spre deosebire de `Entity` de bază, `Boss` suprascrie `checkCollision()` pentru a nu produce daune la simplul contact cu jucătorul — un boss rănește doar prin atacurile lui telegrafate, niciodată doar prin atingere:

```java
// core/src/main/java/entity/Boss.java
@Override
public void checkCollision() {
    collisionOn = false;
    gp.cChecker.checkTile(this);
    gp.cChecker.checkObject(this, false);
    gp.cChecker.checkEntity(this, gp.npc);
    gp.cChecker.checkEntity(this, gp.monster);
    gp.cChecker.checkEntity(this, gp.iTile);
    gp.cChecker.checkPlayer(this);   // notă: fără efect secundar de daună de contact, spre deosebire de Entity
}
```

**Bara de viață a boss-ului** se desenează într-o trecere separată, după toate straturile de lume, nu inline în interiorul propriului `draw()`:

```java
// core/src/main/java/entity/Boss.java
/**
 * Drawn as a separate overlay pass (see RenderPipeline) after all world/depth-tile layers, not
 * inline inside draw() — draw() runs interleaved with Y-sorted depth tiles (tall foliage, walls,
 * overhangs that draw in front of entities standing behind them), so a bar emitted from inside it
 * could get painted over by a depth tile sorted after this boss.
 */
```

Boss-ii pot fi și "împachetați" în Tiled prin proprietatea `bossId` (Secțiunea 5.3), care creează un wrapper `BossMonster` compus dintr-un monstru de bază plus pragul/viteza fazei 2.

## 4.5 `Particle` — Sistemul de Particule cu Pooling

`core/src/main/java/entity/Particle.java` (380 linii) extinde `Entity` și implementează `util.ObjectPool.Poolable` — contractul de pooling cere o metodă `reset()` care golește fiecare câmp, apelată când o particulă se întoarce în pool. Sistemul suportă 10 stiluri vizuale distincte (scântei, praf, stropi, debris rotitor de imagine, stampă de impact etc.), fiecare cu propria fizică (rată de decădere a vitezei, gravitație) și propriul cod de desenare.

Un exemplu instructiv de fixare de bug legat de sortarea pe adâncime, pentru particulele de tip "impact" (o stampă statică la un punct fix din lume):

```java
// core/src/main/java/entity/Particle.java
// renderSorter/tile-interleave both key off "bottom edge" (tiles use worldY + tileSize; see
// TileManager's sortY). worldY here is the sprite's TOP-LEFT corner (centerY - size/2), so
// solidArea.height = size makes the sort key resolve to the stamp's BOTTOM edge
// (centerY + size/2) — at least as deep as the wall tile it's stamped on, so it draws in
// front of that tile instead of being hidden behind it (the class-default 48px box put the
// key well above the tile's own bottom-edge key, which is why it drew underneath).
```

Acest bug apărea pentru că sistemul de sortare pe adâncime (Y-sort, Secțiunea 5.3 și 6.3) cheie mereu pe "marginea de jos" a unei entități, dar dimensiunea implicită a cutiei de coliziune a clasei de particule (48px) plasa cheia de sortare mult deasupra marginii de jos reale a tile-ului pe care particula era "ștampilată" — rezultatul vizual era o particulă de impact desenată în spatele zidului pe care tocmai îl lovise, în loc de deasupra lui. Soluția a fost ajustarea `solidArea.height` a particulei specific pentru stilul de stampă, astfel încât cheia de sortare calculată să corespundă exact poziției vizuale a stampei.

---

# 5. Tile-uri și Hărți

## 5.1 `TileManager` — Parser TMX Propriu, Nu `TmxMapLoader` al libGDX

`core/src/main/java/tile/TileManager.java` (2021 linii) analizează fișierele `.tmx` de Tiled cu un **parser XML/DOM scris manual** (`org.w3c.dom` + parcurgere manuală de elemente), **nu** cu `TmxMapLoader` al libGDX. Motivul, deși nu e afirmat într-un singur comentariu explicit, reiese clar din amploarea vocabularului propriu de proprietăți custom pe care jocul îl citește din Tiled: bucket-uri de randare (`render` enum), `sortYOffset`, sisteme de șabloane de coliziune (`collisionTemplate`), flag-uri `depthSort`/`foreground`/`background` la nivel de tile individual ȘI la nivel de strat, `reflectsLight`, gestionare de hărți infinite cu extragere de flag-uri de flip din GID, și compunere de paralaxă/tentă/offset prin elemente `<group>` imbricate — un set de capacități pentru care `TmxMapLoader` nu oferă suport de prim rang. Acest lucru este de altfel consecvent cu decizia documentată direct în memoria de migrare la libGDX: parserul TMX/TSX custom a fost păstrat deliberat, tocmai pentru a păstra reîncărcarea live în modul de dezvoltare, proprietățile custom per-tile/strat, flag-urile de flip GID, și felierea animațiilor — funcționalități care ar fi trebuit reconstruite de la zero peste API-ul generic al `TmxMapLoader`.

### Cele Trei Bucket-uri de Randare

Sistemul de "bucket"-uri de randare (`background`, `depth`, `foreground`), deja documentat în TILED_ENTITY_GUIDE.md din perspectiva autorului de hărți, este determinat în cod **exclusiv** de proprietăți Tiled explicite — niciodată prin euristici bazate pe numele stratului sau al tileset-ului:

```java
// core/src/main/java/tile/TileManager.java
/**
 * Log the classification of every loaded layer for debugging.
 * Classification is determined ONLY by explicit Tiled properties:
 *   - "render" enum property (RenderBucket): background | depth | foreground
 *   - Legacy boolean properties: background, depthSort, foreground
 * No name-based heuristics. Layers without a render property default to background.
 */
```

Ordinea de prioritate, atunci când mai multe surse de configurare intră în conflict, e strict definită: `render` per-tile explicit > flag-uri booleene legacy per-tile > `render` per-strat explicit > flag-uri booleene legacy per-strat > implicit (`background`). `logLayerClassification()` chiar emite un avertisment când un strat ajunge, prin absența oricărei configurări, la comportamentul implicit — o decizie deliberată de a face configurarea greșită vizibilă în consolă, nu silențioasă.

## 5.2 Y-Sorting: Mecanismul Complet, De la Proprietatea Tiled la Cheia de Sortare

Documentul anterior (versiunea în limba engleză) descrie deja povestea de nivel înalt a Y-sorting-ului pentru ziduri (finalizat în commit-ul `dc32f54e`). Cercetarea în codul curent developing arată exact mecanismul, cu detalii suplimentare:

```java
// core/src/main/java/tile/TileManager.java
// sortY = bottom edge of this tile + sortYOffset.
// sortYOffset values are in GAME pixels (not Tiled pixels) — no scaling needed.
// For multi-row structures, set sortYOffset on the TOP-row tiles
// so their sortY matches the bottom row's sortY.
int sortYOff = (gidToSortYOffset != null && gid < gidToSortYOffset.length) ? gidToSortYOffset[gid] : 0;
visibleTile.sortY = worldY + tileSize + sortYOff;
```

Regula practică de autorat, direct din comentariu: pentru o structură pe mai multe rânduri (un zid înalt de 2 tile-uri, de exemplu), tile-ul de pe **rândul de sus** trebuie să primească un `sortYOffset` astfel încât `sortY` lui calculat să se potrivească exact cu `sortY`-ul tile-ului de pe **rândul de jos** — menținând structura întreagă ca o singură unitate coerentă de adâncime în sortare.

O regulă suplimentară, nedocumentată explicit în ghidul pentru autori de hărți dar prezentă în cod, este auto-activarea sortării pe adâncime pentru orice tile cu un `sortYOffset` nenul:

```java
// core/src/main/java/tile/TileManager.java
gidToSortYOffset[gid] = ts.tiles[i].sortYOffset;
// Tiles with sortYOffset automatically become depth-sorted
if (ts.tiles[i].sortYOffset != 0 && !ts.tiles[i].background) {
    gidToDepthSort[gid] = true;
}
```

Cu alte cuvinte, setarea unui `sortYOffset` nenul pe un tile îl face implicit sortat pe adâncime, chiar dacă proprietatea `depthSort` nu a fost setată explicit — un exemplu de proprietate care implică o alta.

### Sortarea Pe Adâncime Se Unifică Între Tile-uri, Particule și Entități

Cercetarea a confirmat un fir comun important: `TileManager.sortY` (marginea de jos plus offset) și trucul folosit de clasa `Particle` pentru stampele de impact (`solidArea.height = size`, astfel încât cheia de sortare calculată să corespundă marginii de jos reale a stampei — vezi Secțiunea 4.5) urmăresc **aceeași convenție de cheie de sortare**: "marginea de jos" a fiecărui element. Aceasta e ceea ce permite ca tile-urile, particulele și entitățile să se interleave corect într-o singură trecere de sortare/merge în `RenderPipeline` (Secțiunea 6.3), fără cazuri speciale.

## 5.3 Optimizări de Performanță în `TileManager`

Cercetarea codului curent confirmă și extinde memoria `render-hotpath-optimizations`:

- **Pool de obiecte `VisibleTileDraw`** — evită presiunea asupra colectorului de gunoi la reconstruirea listei de tile-uri vizibile la fiecare cadru.
- **Urmărire "murdară" (dirty tracking) pe trei niveluri** în `prepareVisibleTiles()`: (1) nimic nu s-a schimbat deloc (interval de vizibilitate ȘI poziție de cameră identice) → sare complet peste reconstruire; (2) același interval de tile-uri vizibile, dar camera s-a mișcat sub-tile → doar recalculează `screenX/screenY` din coordonatele de lume stocate, fără reconstruire completă și fără resortare; (3) reconstruire completă doar când intervalul de coloane/rânduri chiar s-a schimbat.
- **Array-uri de căutare directă indexate pe GID** (`gidToTile`, `gidToRenderOrder`, `gidToDepthSort`, `gidToForeground`, `gidToBackground`, `gidToSortYOffset`, `gidToReflectsLight`) — înlocuiesc ceea ce comentariile din cod indică drept scanări liniare per-tile ale tileset-ului, folosite anterior.
- **`reflectiveTilePositions`** — un array plat, împachetat, `[col0,row0,col1,row1,...]` al fiecărei poziții de tile care conține un tile `reflectsLight`, construit o singură dată la încărcarea hărții:

```java
// core/src/main/java/tile/TileManager.java
/**
 * Packed [col, row] tile positions on the current map that contain at least one tile with
 * reflectsLight = true. ... Lightning iterates this list every frame instead of scanning the
 * full visible viewport, which collapses the reflective-highlight pass from O(visibleTiles * layers)
 * to O(reflectiveTiles). Even with hundreds of reflective tiles per map, this is typically <1% of
 * the previous work.
 */
```

- **Un bug de memorie reparat**: cache-ul static `tilesetFrameCache` (imagini de tile pre-scalate, partajate între hărți) e golit explicit la începutul fiecărui `loadTilesets()`:

```java
// core/src/main/java/tile/TileManager.java
// Free all cached scaled tile images from the previous map before loading the new one.
// Without this, the static cache accumulates hundreds of MB and causes OutOfMemoryError
// when entering a large map (e.g. Canvas Village) after a smaller one.
```

Fără această golire, cache-ul static creștea nemărginit pe măsură ce jucătorul traversa hărți succesive, până la a produce `OutOfMemoryError` la intrarea pe o hartă mare (satul canvas fiind exemplul citat) după una mai mică.

- **Un bug de drift de animație reparat**: temporizatorul de animație de tile a trecut de la o aproximare de "17ms pe tick" la constanta exactă:

```java
// core/src/main/java/tile/TileManager.java
// 60 UPS → exactly 1000/60 ≈ 16.667 ms per tick. Using the exact float value (not the old
// 17 ms approximation) removes the ~2%/frame drift so animated tiles cycle at their true rate.
final float MS_PER_TICK = 1000f / 60f;
```

- **Patch-uire in-place pentru tile-uri animate**, în loc de reconstrucție completă la fiecare avans de cadru de animație:

```java
// core/src/main/java/tile/TileManager.java
/**
 * OPTIMIZATION: Instead of forcing a full visible-tile rebuild when any animation
 * advances (old behaviour), patch the `image` field of already-visible animated tiles
 * in place. Water animating at 10 FPS no longer triggers 10 full rebuilds per second.
 */
```

## 5.4 Coliziune: Straturi de Forme și Șabloane Reutilizabile

`loadCollisionLayer()` citește elementele `<objectgroup>` ale căror nume se află în `collisionObjectLayers` (implicit `{"Collision"}`, configurabil prin proprietatea de hartă `collisionObjectLayers`). Analiza se face în două treceri: prima colectează **șabloanele de coliziune** (obiecte cu `isCollisionTemplate=true`, indexate după nume) — acestea nu devin ele însele forme de coliziune; a doua construiește formele reale, clonând forma unui șablon numit la poziția fiecărui obiect-instanță (proprietatea `collisionTemplate` care referențiază numele șablonului).

Suportă dreptunghi (cu rotație → aplatizat la poligon prin `Transform.rect`), elipsă, poligon, și polilinie. Polilinia are propria poveste de bug rezolvat:

```java
// core/src/main/java/tile/TileManager.java
// --- Polyline — stroked into one thin quad PER SEGMENT, added directly to the collision list.
// (A single offset-outline ring self-intersects on long/closed paths, which no fill can render
// correctly — that was the glitched blue hitbox overlay. Per-segment convex quads stroke any
// path cleanly.)
```

Aceasta este exact aceeași poveste narată în `gfx/geom/Transform.java` (Secțiunea 6.4): abordarea inițială pentru contur de polilinie — un singur inel de vârfuri deplasate (offset) — se auto-intersecta pe drumuri lungi sau închise, iar umplerea unui asemenea inel picta întreaga zonă interioară, nu doar conturul (bug-ul "dreptunghiul albastru acoperă toată harta"). Soluția a fost stroke-uirea fiecărui segment individual ca propriul lui patrulater subțire convex, adăugat direct la lista de coliziune — o abordare care funcționează corect pentru orice tip de drum, deschis sau închis.

### Umbrele Aruncate de Tile-uri Solide

```java
// core/src/main/java/tile/TileManager.java
/**
 * Only depth-sorted tiles standing on a SOLID (collision) cell cast — i.e. walls, pillars, tree
 * trunks, statues, the upright scenery you genuinely can't see through. Walkable ground clutter
 * (cave rubble, grass, floor detail) is deliberately excluded: it sits on the same plane as the
 * light, so casting from it would bury the player in their own surroundings' shadow. Gating on
 * collision is both physically sensible ("a wall blocks light, a pebble doesn't") and gives clean,
 * readable shadows.
 */
```

Aceasta este exact povestea narată și în memoria `shader-lighting-pipeline` (Secțiunea 7.2): fără gate-uirea pe coliziune, cercetarea inițială arăta ocluderul complet gol pe hărți construite din tile-uri, nu entități — soluția a fost `TileManager.drawDepthOccluders()`, care randează fiecare tile vizibil, sortat pe adâncime, a cărui celulă e solidă, ca siluetă neagră.

## 5.5 `MapObjectLoader` — De la Obiecte Tiled la Entități Java

`core/src/main/java/map/MapObjectLoader.java` (1527 linii) convertește straturile de obiecte din Tiled în entități de joc. Documentația proprie a clasei este ea însăși o referință exhaustivă de proprietăți (nume/tip/implicit pentru proprietăți de hartă, entitate, monstru, NPC, obiect, per-tile, strat, tip de eveniment, zonă de monștri) — sursă pentru multe dintre tabelele deja prezente în TILED_ENTITY_GUIDE.md.

### Rezolvarea Numelui de Grup de Obiecte

```java
// core/src/main/java/map/MapObjectLoader.java (rezumat)
// "objects"/"object" → "Objects", "monsters"/"monster"/"mobs"/"mob"/"enemies" → "Monsters", etc.
```

Potrivirea de alias e case-insensitive, exact cum cere TILED_ENTITY_GUIDE.md — orice combinație de litere mari/mici pentru "Monsters"/"monsters"/"MONSTERS" funcționează, plus o listă de alias-uri (`mobs`, `mob`, `enemies` → Monsters).

### Rezolvarea Tipului de Obiect: Un Lanț de Fallback-uri

```java
// core/src/main/java/map/MapObjectLoader.java
// Tiled 1.9+ uses "class"; older versions use "type"
String type = obj.getAttribute("class");
if (type == null || type.isEmpty()) type = obj.getAttribute("type");
if (type == null || type.isEmpty()) type = getStringProperty(obj, "type", "");
if (type == null || type.isEmpty()) type = obj.getAttribute("name");
```

Acest lanț de patru încercări reflectă evoluția reală a formatului Tiled între versiuni: Tiled 1.9+ a mutat identificatorul de tip al obiectului din atributul `type` în atributul `class`, deci codul verifică ambele, apoi o eventuală proprietate custom `type`, și în cele din urmă cade pe numele obiectului — pentru compatibilitate maximă indiferent de versiunea de Tiled folosită la autorat.

### NPC-urile Sunt Sărite Complet În Multiplayer

```java
// core/src/main/java/map/MapObjectLoader.java
// Multiplayer: the server hosts the NPCs (it reads the same objectgroup
// out of its own copy of the map) and pushes them to us as npc_spawn
// packets. Spawning them here as well would double them up — and the
// local copies would run their own dialogue/shop logic, which is exactly
// the authority we moved to the server.
if (gp.multiplayerMode) continue;
```

Acesta e un exemplu clar al principiului de autoritate de server aplicat la nivelul de încărcare a hărții: în multiplayer, `MapObjectLoader` nu creează deloc obiecte NPC din stratul Tiled — serverul citește propria lui copie a aceleiași hărți și trimite `npc_spawn` prin protocolul de rețea (Secțiunea 12). Dacă clientul ar crea și el copii locale, acestea ar rula propria lor logică de dialog/magazin, exact autoritatea pe care mutarea pe server încearcă să o elimine.

### Împachetarea Boss-ilor și Scalarea Pentru Multiplayer Local (BLE)

```java
// core/src/main/java/map/MapObjectLoader.java
int bossId = getIntProperty(obj, "bossId", 0);
if (bossId > 0) {
    float threshold  = getFloatProperty(obj, "phase2Threshold", 0.5f);
    int   speedBoost = getIntProperty(obj, "phase2SpeedBoost", 1);
    m = new BossMonster(gp, m, bossId, threshold, speedBoost);
}
// Local multiplayer boss scaling: +40% HP / +15% ATK per player beyond the first, so a
// fight stays proportionally challenging with a full BLE session just as it would with
// more human damage output.
boolean isBoss = bossId > 0 || m instanceof entity.BOSS_WitheredTree;
if (isBoss && gp.bleSession != null) {
    int extraPlayers = gp.bleSession.totalPlayerCount() - 1;
    if (extraPlayers > 0) {
        m.maxLife = (int) (m.maxLife * (1 + 0.4 * extraPlayers));
        // ... +15% ATK per jucător suplimentar ...
    }
}
```

Aceasta este o decizie de design demnă de remarcat: scalarea de dificultate a boss-ilor pentru mai mulți jucători există **doar** pe calea BLE locală (unde nu există un server central autoritar care să echilibreze lupta), nu pe calea TCP server-autoritară — pentru că pe server, echilibrarea luptei e deja gestionată prin faptul că fiecare lovitură trece prin arbitrul autoritar (Secțiunea 12.4), deci nu are nevoie de o compensare artificială de HP/ATK pe partea de client.

### Lanțul de Pași al NPC-ului: Configurare cu Valori Implicite Inteligente

```java
// core/src/main/java/map/MapObjectLoader.java
// step<N>_dialogue is optional — defaults to N so steps 0/1/2 automatically play
// dialogue sets 0/1/2 from the NPC class's setDialogue() without any Tiled config.
for (int si = 0; si < 20; si++) {
    int sCol = getIntProperty(obj, "step" + si + "_walkToCol", -2);
    int sRow = getIntProperty(obj, "step" + si + "_walkToRow", -2);
    int sDlg = getIntProperty(obj, "step" + si + "_dialogue",  -2);
    if (sCol == -2 && sRow == -2 && sDlg == -2) break;
    if (sDlg < 0) sDlg = si;
    // ...
}
```

Sistemul de "pași" al NPC-urilor de tip gardian (`step<N>_walkToCol/Row/dialogue` din TILED_ENTITY_GUIDE.md) are o valoare implicită inteligentă pentru `dialogue`: dacă autorul hărții nu specifică explicit un set de dialog pentru un pas, sistemul folosește pur și simplu indexul pasului însuși — astfel încât un lanț simplu de 3 pași "funcționează" fără nicio configurare suplimentară de dialog, doar prin plasarea coordonatelor de destinație pentru fiecare pas.

## 5.6 `EventHandler` — Declanșatoare Bazate pe Pixeli Exacți, Nu Pe Tile

`core/src/main/java/map/EventHandler.java` (919 linii) gestionează toate evenimentele declanșate de jucător: `MapTransition`, `HealingPool`, `DamageTrap`, `DialogueTrigger`, `ThoughtTrigger`, `LevelGate`, `MemoryGate`, `Checkpoint`, `QuestTrigger`, `FragmentTrigger`, `BossIntroTrigger`, `CameraShake`, `SpawnZone`, `WaterZone`, și puncte de spawn numite.

Un detaliu important de precizie: detectarea declanșatoarelor este **exact pe pixel** (intersecție AABB), nu pe grilă de tile — fiecare tip de eveniment este stocat ca un `PixelEvent<T>` (un `Rect hitbox` plus un payload tipizat), iar `checkEvent()` intersectează dreptunghiul exact al jucătorului (derivat din `solidArea`) cu fiecare listă.

### Ordinea Fixă de Prioritate

```java
// core/src/main/java/map/EventHandler.java
// Map transitions checked last so gameplay events fire first
for (PixelEvent<MapTransition> pe : mapTransitions) {
    if (playerRect.intersects(pe.hitbox)) {
        // ...
    }
}
```

Ordinea de verificare e fixă prin ordinea de iterare: bazine de vindecare → capcane de daună → porți de nivel → porți de memorie → declanșatoare de dialog → declanșatoare de gând (neblocante) → puncte de verificare → declanșatoare de quest → declanșatoare de fragment → declanșatoare de introducere boss → tremurături de cameră → **tranzițiile de hartă verificate ultimele**, deliberat, astfel încât evenimentele de gameplay să se declanșeze primele.

### Debounce-ul de Atingere

```java
// core/src/main/java/map/EventHandler.java
public void checkEvent() {
    int xDist = Math.abs(gp.player.worldX - previousEventX);
    int yDist = Math.abs(gp.player.worldY - previousEventY);
    if (Math.max(xDist, yDist) >= gp.tileSize) canTouchEvent = true;
    if (!canTouchEvent) return;
    // ...
}
```

Odată ce un eveniment se declanșează, `canTouchEvent` devine fals până când jucătorul se mută la cel puțin un tile distanță de poziția la care s-a declanșat ultima dată — previne un declanșator să se activeze la fiecare cadru cât timp jucătorul stă nemișcat în zona lui.

### Persistența Porților Permanente

O `LevelGate` cu `permanentOpen=true` este reținută pentru totdeauna după prima trecere reușită (cu obiectul cerut consumat), printr-un `Set<String>` (`gp.openedGates`) indexat după `"idHartă:coloană:rând"` — ocolind toate verificările viitoare de cerințe pentru acea poartă specifică.

### Zonele de Spawn: Poligoane, Nu Doar Dreptunghiuri

`updateSpawnZones()` suportă atât zone dreptunghiulare cât și **zone poligonale** (`IntPolygon`), cu respingerea pozițiilor candidate prea aproape de jucător (sub 3 tile-uri) sau suprapuse peste un monstru deja viu, încercând până la 20 de poziții candidate per interval de tick înainte de a renunța la acel ciclu de generare.

## 5.7 `MapManager` — Registru, Tranziții, Persistență Per-Hartă

`core/src/main/java/map/MapManager.java` (543 linii) a fost extras din `GamePanel` pentru a reduce complexitatea clasei-god, conform propriei documentații.

### Descoperirea Hărților: Trei Niveluri de Fallback

```java
// core/src/main/java/map/MapManager.java (rezumat)
// 1. Dev-source scan (ResourceCache.getDevSourceDir()) — reîncărcare live fără rebuild în dezvoltare
// 2. Packaged runtime via Gdx.files.internal(...).list() — funcționează uniform pe desktop-jar și Android-APK
// 3. Classpath fallback (listClasspathDir) — enumerare manuală bazată pe classloader, pentru serverul headless
```

Al treilea nivel de fallback este necesar specific pentru serverul autoritar de multiplayer fără fereastră (`main.HeadlessGame`, Secțiunea 12.4), care nu are un folder de assets împachetat împotriva căruia să apeleze `Gdx.files.internal()` — trebuie să enumere fișierele `.tmx` direct prin classloader, gestionând atât adrese URL de tip `file:` cât și `jar:`.

### `safeSpawn` — Oglindă Exactă a Logicii Serverului

```java
// core/src/main/java/map/MapManager.java
/**
 * Return a collision-free tile near (col, row), searching in expanding rings.
 * Mirrors the authoritative multiplayer server's world.safe_spawn() so a map whose
 * default spawn tile happens to sit inside collision doesn't leave the player stuck
 * in a wall on new-game start.
 */
```

Aceasta este o dovadă directă a principiului "o singură implementare a regulilor" extins dincolo de motorul de joc partajat: chiar și un algoritm mic, ajutător (căutare în inele expandate pentru un tile fără coliziune) este reimplementat manual în Python pe server, dar cu grijă documentată să se comporte identic — pentru ca un jucător să nu rămână blocat într-un zid la începerea unui joc nou, indiferent dacă rulează single-player sau se alătură unui server.

### Tranziția de Hartă: Rezistentă la Erori

`changeMap()` este împachetat într-un `try/catch (Throwable)` la nivel de top, care jurnalizează în `crash_log.txt` (citire-modificare-scriere pentru a păstra intrările anterioare, din moment ce `GameStorage.outputStream` trunchiază întotdeauna) în loc să lase o eroare (TMX corupt, asset lipsă) să omoare întreaga aplicație în mijlocul unei tranziții:

```java
// core/src/main/java/map/MapManager.java
} catch (Throwable t) {
    // A failure here (bad TMX, missing asset, etc.) used to propagate up through the
    // render loop and kill the whole app mid-transition (black screen, music cut).
    // Log the full trace so the real cause is visible, and keep the app alive.
```

Secvența completă de tranziție: încarcă straturile de tile-uri → încarcă coliziunea → inițializează harta de lumină pe tile-uri → încarcă proprietățile hărții → actualizează cache-ul de dreptunghiuri de coliziune → re-coace minimap-ul → **salvează starea entităților hărții de IEȘIRE** → golește toate array-urile de entități → curăță proiectile/particule/straturi meteo → invalidează cache-ul de umbre → resetează `EventHandler` → încarcă câmpul de vânt → restaurează sau generează din nou entitățile pentru harta de INTRARE → încarcă evenimentele → rezolvă punctul de spawn numit → poziționează jucătorul, blochează camera → memorează poziția de respawn la moarte → afișează mesajul de declanșator de dialog în așteptare / cardul de titlu de act → notifică `questManager` de intrarea pe hartă.

### Persistența Entităților Per-Hartă

Fiecare hartă vizitată anterior are propriul instantaneu complet de array de entități (`savedObjects`/`savedNPCs`/`savedMonsters`/`savedITiles`, toate `Map<String, Entity[]>`), restaurat identic la revenire — cufere deja deschise rămân deschise, monștri uciși rămân uciși, chiar dacă jucătorul a plecat și s-a întors pe acea hartă de mai multe ori în aceeași sesiune.

---

# 6. Randare: de la Graphics2D la libGDX

## 6.1 Povestea Migrării

Echoes of the Heir a fost portat de la Java2D (randare software prin `Graphics2D`, într-un `JPanel` Swing) la libGDX (randare pe GPU, prin backend-ul LWJGL3 pe desktop). Documentul `LIBGDX_MIGRATION.md` afirmă direct rezultatul: "jocul se joacă identic, pixelii încă se numără din stânga-sus (convenția Graphics2D), și baza de cod e acum pregătită pentru Android."

Decizia centrală de design a acestei migrări a fost: **nu rescrie logica de desenare, ci construiește o fațadă care imită forma API-ului vechi**. Documentația proprie a clasei `gfx/GdxRenderer.java` afirmă obiectivul direct:

> "Fațadă de randare în forma Graphics2D susținută de libGDX. Cele ~1570 de puncte de apel `g2.draw*` din tot jocul devin `r.draw*` cu ACELEAȘI nume/semnături de metode, astfel încât portarea este mecanică și păstrează comportamentul."

În loc să atingă 1.570 de puncte de apel din tot jocul pentru a învăța un API de randare complet nou, fiecare punct de apel și-a păstrat forma exactă — doar parametrul `Graphics2D g2` a devenit `GdxRenderer g2`, iar renderer-ul de dedesubt s-a schimbat complet.

### Flip-ul de Coordonate, Făcut O Singură Dată

Un detaliu esențial: în loc să inverseze coordonata Y la fiecare din cele 1.570 de puncte de apel, echipa a ales să inverseze camera o singură dată:

```
OrthographicCamera.setToOrtho(true, ...)  // yDown = true
```

Acest lucru a expus însă o capcană subtilă, documentată explicit în `LIBGDX_MIGRATION.md`:

> "Cu `setToOrtho(true, …)` (yDown) al acestui proiect, `SpriteBatch` emite coordonate de textură V pentru o lume yUp, deci un `TextureRegion` neinversat se randează cu susul în jos, și glife neinversate randează fiecare literă cu susul în jos."

Soluția a fost aplicată **o dată per subsistem, împreună**: `gfx.Sprite` inversează regiunea lui de afișare (`region.flip(false, true)`), păstrând în același timp coordonatele native stânga-sus pentru felierea CPU (`getSubimage`/`getRGB`); `gfx.FontSystem` generează fonturile cu `p.flip = true`. Documentul avertizează explicit împotriva unei capcane de depanare reală întâlnită în practică:

> "NU 'reparați' inversarea prin flip-uirea doar a unuia dintre Sprite/Font (o încercare anterioară a făcut asta, iar simptomele s-au reflectat înainte-înapoi: flip doar la regiune → imaginile drepte dar literele încă inversate, și vice-versa). Ambele trebuie flip-uite, sau niciunul."

## 6.2 `GdxRenderer` — Mecanismele Fațadei

`gfx/GdxRenderer.java` (1171 linii) conduce intern un `SpriteBatch` (patrulatere texturate) + `ShapeRenderer` (umpluturi/linii) + `OrthographicCamera`, comutând automat între ele.

### Comutare Automată de Batch

```java
// gfx/GdxRenderer.java (structură)
private enum Mode { NONE, BATCH, SHAPE_FILL, SHAPE_LINE }
```

`SpriteBatch` și `ShapeRenderer` nu pot fi active simultan în libGDX — fațada golește (flush) transparent orice e activ și comută, astfel încât codul apelant nu gestionează niciodată singur starea GL. Aceasta e exact ce permite ca vechiul cod, care amesteca liber apeluri de tip `drawImage` cu `fillRect`, să continue să funcționeze neschimbat.

### Moduri de Blending Personalizate

```java
// gfx/GdxRenderer.java
// BLEND_NORMAL, BLEND_ADDITIVE, BLEND_DSTOUT
```

`setBlendMode()` golește batch-ul curent, apoi setează direct funcția de blending GL atât pe `batch` cât și pe starea GL brută. Blending-ul aditiv alimentează efectele de strălucire (surse de lumină, VFX de nivelare); DST_OUT "perforează găuri" în overlay-ul de întuneric pentru sursele de lumină pe calea legacy (LOW), descrisă în Secțiunea 7.

### Reutilizarea Obiectelor Scratch — Eliminarea Alocărilor Per-Cadru

Aceasta este pe deplin documentată în memoria `render-hotpath-optimizations`, iar cercetarea codului curent o confirmă cu detalii exacte:

```java
// gfx/GdxRenderer.java
private final Matrix4 xformScratch = new Matrix4();

private void applyTransform() {
    Matrix4 t = xformScratch.idt().translate(tx, ty, 0);
    if (zoom != 1f) {
        t.translate(pivotX, pivotY, 0).scale(zoom, zoom, 1f).translate(-pivotX, -pivotY, 0);
    }
    batch.setTransformMatrix(t);
    shapes.setTransformMatrix(t);
}

private final com.badlogic.gdx.graphics.Color scratchColor = new com.badlogic.gdx.graphics.Color();

private com.badlogic.gdx.graphics.Color gdxColor() {
    return scratchColor.set(
        color.getRed() / 255f, color.getGreen() / 255f, color.getBlue() / 255f,
        (color.getAlpha() / 255f) * alpha);
}
```

Înainte de această optimizare, cea mai mare sursă de gunoi generat per cadru era un `new com.badlogic.gdx.graphics.Color` la fiecare `fillRect`/`fillOval`/`drawLine` (22 de puncte de apel distincte), un `new Matrix4` la fiecare `translate`/`zoom`, un `new TextureRegion` la fiecare tile inversat, și 9 alocări de `Sprite`+regiune per panou de UI per cadru. Soluția, sistematică: `scratchColor` reutilizat (copiat de `ShapeRenderer.setColor`, nu reținut), `xformScratch` rezidit cu `.idt()` la fiecare apel de transformare în loc de alocat din nou, `flipScratch` (o singură instanță `TextureRegion` reutilizată pentru fiecare desenare inversată/oglindită/rotită), și `nineSliceCache` (un `IdentityHashMap<Sprite, Sprite[]>`) care memorează cele 9 felii ale unui panou nine-slice per textură sursă, calculate o singură dată.

### FBO-uri Offscreen: Trei Ținte Separate

```java
// gfx/GdxRenderer.java — câmpuri (rezumat)
private FrameBuffer lightFbo;      // masca de întuneric / lumină
private FrameBuffer occluderFbo;   // siluetele proiectoarelor de umbră
private FrameBuffer sceneFbo;      // captura de scenă folosită de bloom
```

Fiecare framebuffer are propria pereche `begin*Mask()`/`end*Mask()`, plus metode de debug pentru extragerea conținutului lor ca PNG (`debugDumpOccluder`, `debugDumpScene`, `debugDumpLightMask`) — instrumente esențiale, după cum arată istoricul de depanare din Secțiunea 7, pentru a diagnostica bug-uri de orientare/aliniere invizibile la ochiul liber în timpul jocului normal.

### `rebindActiveTarget()` — Fixarea Bug-ului de FBO Imbricat

Acesta este exact bug-ul narat în memoria `shader-lighting-pipeline`, iar cercetarea confirmă implementarea exactă curentă:

```java
// gfx/GdxRenderer.java
private void rebindActiveTarget() {
    if (sceneCaptureActive && sceneFbo != null) {
        sceneFbo.begin();
        Gdx.gl.glViewport(0, 0, sceneFboW, sceneFboH);
    } else {
        Gdx.gl.glViewport(worldVpX, worldVpY, worldVpW, worldVpH);
    }
}
```

Problema originală: `FrameBuffer.begin()/end()` al libGDX **nu se comportă ca o stivă** — `end()` re-leagă întotdeauna framebuffer-ul **implicit** (ecranul), nu orice FBO era activ înainte de imbricare. Pipeline-ul de iluminat imbrică randările `lightFbo`/`occluderFbo` **în interiorul** capturii de scenă pentru bloom (`sceneFbo`); fiecare `end()` imbricat redirecționa silențios desenarea înapoi pe ecran, astfel încât overlay-ul de întuneric ajungea direct pe ecran, iar apoi blit-ul scenei capturate pentru bloom îl **suprascria** — rezultatul vizibil era o scenă complet luminoasă, cu iluminatul "dispărut" pe calitatea HIGH. Soluția, `rebindActiveTarget()`, este apelată după fiecare `end()` de FBO imbricat: re-leagă `sceneFbo` dacă o captură de scenă e activă, altfel restaurează viewport-ul lumii.

### O Poveste de Depanare: "Luminile Dansează" — Patru Cauze Diferite, Un Singur Simptom

Un fragment de cod comentat rezumă chiar în sursă o poveste de investigare pe mai multe runde:

```java
// gfx/GdxRenderer.java
// FBO color texture; drawn full-screen, NEVER V-flipped. PROOF (OffCenterLightTest, a probe
// light 250px ABOVE the player): with the old "flip when not capturing" logic the probe's pool
// rendered 250px BELOW the player on the non-capture path — the whole mask was vertically
// MIRRORED about the screen center. The mirror was invisible in every previous verification
// because those all watched the PLAYER'S OWN pool, which sits at the screen center — and the
// mirror maps the center to itself.
```

Aceasta este o lecție metodologică importantă, demnă de reținut: patru cauze radical diferite au produs, pe rând, exact același simptom vizibil ("luminile/umbrele par să se miște când camera se mișcă") — (1) auto-umbrirea jucătorului de propria lui lumină, (2) transformarea lumii aplicată din greșeală peste blit-ul măștii de lumină în spațiu de ecran, (3) o mască de lumină/umbră scrisă cu orientare Y inversată față de restul pipeline-ului, și (4) o oglindire verticală completă a măștii pe calea fără captură de scenă. Fiecare a fost depanată separat, iar a patra a fost cea mai insidioasă: bug-ul era complet invizibil în verificările anterioare pentru simplul motiv că lumina proprie a jucătorului stă mereu în centrul ecranului (camera cu zonă moartă îl menține acolo), iar o oglindire față de centrul ecranului **mapează centrul pe el însuși** — deci lumina jucătorului părea mereu corectă, indiferent de bug. Soluția metodologică (documentată explicit în memorie) a fost construirea unui harness de testare dedicat cu o sursă de lumină offset, plasată artificial la 250 pixeli deasupra jucătorului — abia atunci oglindirea a devenit vizibilă și verificabilă automat.

### `beginDeviceSpace()`/`endDeviceSpace()`

Aceste metode comută temporar la o proiecție 1:1, nescalată, în pixeli de dispozitiv — specific pentru ca textul de debug și elementele HUD critice să rămână clare, în loc să fie mărite (și neclare) de aceeași scală întreagă `pixelScale` folosită pentru arta pixel a lumii jocului (Secțiunea 1.2). Panoul de debug al `GamePanel` (Secțiunea 1.3) și textul FPS folosesc explicit acest mecanism.

## 6.3 `RenderPipeline` — Orchestratorul Central de Randare

`ui/RenderPipeline.java` (712 linii) este locul unde toate straturile individuale de randare se combină într-un singur cadru coerent.

### `drawCurrentState()` — Dispatch pe Starea Jocului

```java
// ui/RenderPipeline.java (structură)
public void drawCurrentState(GdxRenderer g2) {
    if (gp.gameState == GamePanel.titleState) {
        gp.ui.draw(g2);   // doar UI — invalidează cache-ul de lume
        return;
    }
    if (gp.gameState == GamePanel.levelUpState) {
        gp.ui.draw(g2);   // doar UI — ecranul de nivelare desenează propriul overlay opac
        return;
    }
    drawWorldLayers(g2);
    drawWorldOverlays(g2);
}
```

Fiecare altă stare a jocului (inclusiv `pauseState`, `dialogueState`, `shopState`) randează lumea dedesubt — meniul de pauză sau cutia de dialog se suprapun peste o lume încă vizibilă, nu peste un ecran gol.

### Sortarea pe Adâncime: Bucla de Merge Pe Trei Căi

```java
// ui/RenderPipeline.java (drawWorldLayers, simplificat)
for (int i = 0; i < entityListIndex; i++) {
    Entity e = entityList.get(i);
    int entityY = e.worldY + gp.tileSize + e.depthSortYOffset;
    while (true) {
        float nextDepthTileY = depthTileIdx < depthTileCount ? gp.tileM.getDepthTileSortY(depthTileIdx) : Float.MAX_VALUE;
        float nextParticleY = tpIdx < tpCount ? gp.tileParticleEmitter.getSortY(tpIdx) : Float.MAX_VALUE;
        float nextY = Math.min(nextDepthTileY, nextParticleY);
        if (nextY > entityY) break;
        if (nextDepthTileY <= nextParticleY) {
            g2.setAlpha(1f);
            gp.tileM.drawDepthTile(g2, depthTileIdx);
            depthTileIdx++;
        } else {
            gp.tileParticleEmitter.drawSingle(g2, tpIdx);
            tpIdx++;
        }
    }
    g2.setAlpha(1f);
    e.draw(g2);
}
```

Aceasta este implementarea completă a mecanismului de merge descris conceptual în Secțiunea 5.2: trei liste **deja sortate independent** — tile-uri de adâncime, particule de tile, și entități — sunt combinate într-o singură trecere, epuizând orice element ale cărui chei de sortare sunt la sau sub cea a entității curente înainte de a desena acea entitate. Acesta e mecanismul exact prin care un zid se desenează corect în fața sau în spatele jucătorului, în funcție de care dintre cei doi are cheia de sortare mai mică ("mai jos" pe ecran) în acel moment.

### Umbrele de Sol: O Trecere Separată, Nu Inline

```java
// ui/RenderPipeline.java
/**
 * Flat ground shadows (see Entity.drawGroundShadow) as their own pass, drawn once on the ground
 * right after the background tiles and before ANY entity — never depth-sorted with the entities
 * themselves. They used to be drawn inline inside each caster's own draw() call, which put them at
 * that caster's depth-sort slot: whenever the player or an NPC stood "in front of" (later in the
 * depth order than) a tree, the tree's draw() ran after them and painted its shadow on top of their
 * sprite.
 */
```

Aceasta e încă o poveste de bug de sortare pe adâncime, cu o soluție structurală: umbrele plate de sol obișnuiau să fie desenate inline, în interiorul apelului `draw()` al fiecărui proiector de umbră — ceea ce le plasa exact la slotul de sortare pe adâncime al acelui proiector. Rezultatul: de fiecare dată când jucătorul sau un NPC stătea "în fața" (mai târziu în ordinea de adâncime decât) un copac, `draw()`-ul copacului rula după al lor și picta umbra copacului **peste** sprite-ul jucătorului/NPC-ului. Soluția a fost extragerea umbrelor de sol într-o trecere complet separată, desenată o singură dată, imediat după tile-urile de fundal și înainte de orice entitate — niciodată sortată pe adâncime împreună cu entitățile.

### Povestea Bug-ului de Vizibilitate BLE

Aceasta este exact fixarea descrisă în memoria `ble-multiplayer-visibility`, confirmată cu codul curent exact:

```java
// ui/RenderPipeline.java
// Draw remote players for either transport: the license-bound TCP client (multiplayerMode)
// OR a local BLE "invite player" session. The BLE path populates its own remotePlayers map
// but is never TCP-connected, so gating solely on mpClient.isConnected() hid every BLE
// remote player (host and guests saw only themselves). GamePanel.drawRemotePlayers already
// guards each source independently, so an empty/inactive source contributes nothing.
boolean tcpConnected = gp.mpClient != null && gp.mpClient.isConnected();
boolean bleActive = gp.bleSession != null && gp.bleSession.isActive();
if (tcpConnected || bleActive) {
    gp.drawRemotePlayers(g2);
}
```

Povestea completă a acestui bug, inclusiv verificarea pe hardware real (Galaxy S25 Ultra ca gazdă, un A37 ca oaspete), este narată pe larg în Secțiunea 12.7 — pe scurt, calea de date BLE era complet corectă (`BleMultiplayerSession` își popula corect propria hartă `remotePlayers`), dar singurul punct de apel al desenării jucătorilor la distanță era condiționat exclusiv de `mpClient.isConnected()` (clientul TCP legat de licență). O sesiune BLE-only nu conectează niciodată acel client TCP, deci apelul de desenare era sărit complet, iar fiecare jucător la distanță prin BLE era invizibil — deși datele lui de poziție soseau perfect corect.

### Împachetarea de Captură de Scenă Pentru Bloom

```java
// ui/RenderPipeline.java (structură)
// gate: HIGH quality + g2.bloomAvailable() + not LightDebug.noBloom
g2.beginSceneCapture(...);
// ... desenează toate straturile de lume ...
g2.endSceneCaptureAndBloom(BLOOM_THRESHOLD, BLOOM_INTENSITY);
```

Pragul de bloom (`BLOOM_THRESHOLD = 0.97f`) este deliberat setat foarte sus — astfel încât luminozitatea obișnuită a pielii/hainelor să nu producă niciodată bloom, doar hotspot-urile de lumină amplificate explicit de shader.

### Camera de Dialog: Aplicare la Momentul Randării

Zoom-ul și pan-ul de dialog (calculate în `GamePanel.updateDialogueCamera()`, Secțiunea 1.3) se aplică efectiv aici, învelind doar trecerea de lume:

```java
// ui/RenderPipeline.java (structură)
g2.translate(gp.dlgPanX, gp.dlgPanY);
g2.setWorldZoom(gp.dlgZoom, gp.screenWidth / 2f, gp.screenHeight / 2f);
// ... desenează lumea ...
// resetează transformarea înainte de HUD
```

Barele de tip letterbox (întunecare sus/jos în timpul dialogului) sunt desenate separat, în `drawWorldOverlays()`, folosind `gp.dlgBars` (înălțime interpolată lin între 0 și `DLG_BAR_MAX_H`).

## 6.4 `gfx.geom.*` — Înlocuitorii Pentru `java.awt.geom`

Pachetul `core/src/main/java/gfx/geom/` conține 7 clase care reproduc semantica lui `java.awt.geom` fără nicio dependență de AWT (esențial pentru portabilitatea pe Android, care nu are `java.awt`):

| Clasă | Echivalentul AWT | Note |
|---|---|---|
| `Shape` | interfața `Shape` | Contract: `intersects`, `contains`, `getBounds2D()` |
| `Rect` | `Rectangle`/`Rectangle2D.Double` | Câmpuri publice int x/y/width/height |
| `Ellipse` | `Ellipse2D` | `contains`/`intersects` normalizează în spațiul de cerc unitate |
| `Polygon` | `Path2D`/`Area` aplatizat | Testare `contains()` prin ray-casting even-odd |
| `IntPolygon` | `java.awt.Polygon` | Mutabil, folosit pentru hurt-box-urile entităților |
| `Transform` | `AffineTransform` + `createTransformedShape` | Doar translate-apoi-rotire |
| `Cone` | (nou, fără echivalent AWT) | Sector circular pentru hitbox-uri de atac |

Documentul `LIBGDX_MIGRATION.md` notează că aceste forme au fost **verificate pentru paritate** printr-un test dedicat de desktop (`lwjgl3.GeomParityTest`), comparând rezultatele cu `java.awt.geom` real peste **240.000 de sondaje aleatoare `intersects`/`contains` — 0 nepotriviri**. Coliziunea se comportă exact ca înainte de migrare.

### O Poveste de Bug: Poliliniile Care "Acoperă Toată Harta"

```java
// gfx/geom/Transform.java
/**
 * Stroke a polyline into one thin quad PER SEGMENT (each returned as its own Polygon),
 * rather than a single offset-outline ring. The single-ring approach in polyline() collapses
 * for large or CLOSED paths (e.g. a room-perimeter loop): its vertex ring ends up enclosing the whole
 * interior, so filling it paints the entire area (the "blue covers the whole map" collision bug).
 * Per-segment quads stroke correctly for any path — an open wall, or a closed loop that becomes a
 * ring of thin wall strips.
 */
```

Abordarea inițială pentru contur de polilinie folosea un singur inel de vârfuri deplasate cu o distanță fixă (offset) de la linia originală. Pentru drumuri lungi sau, mai grav, pentru drumuri **închise** (un perimetru de cameră, de exemplu), acest inel de vârfuri ajungea să se auto-intersecteze, iar rezultatul umplut acoperea întreaga zonă interioară a poligonului, nu doar conturul lui subțire — vizual, tot dreptunghiul de coliziune "albastru" (culoarea de debug) acoperea harta întreagă. Soluția a fost renunțarea la ideea de "un singur contur" în favoarea stroke-uirii **fiecărui segment individual** ca propriul lui patrulater subțire, independent — abordare care funcționează corect pentru orice tip de drum.

### `Cone` — Hitbox-ul de Atac Ca Sector Circular

```java
// gfx/geom/Cone.java (rezumat)
// apex + radius + centerAngle + halfAngle (radiani, convenție atan2)
```

`Cone` este modelat intern ca un `IntPolygon` (un evantai de triunghiuri cu 10 segmente de arc), astfel încât să reutilizeze dispatch-ul existent `fill(Shape)`/`draw(Shape)` din `GdxRenderer` fără cod nou de desenare — dar suprascrie `contains()` cu matematică exactă de sector (test de distanță + unghi) în loc să se bazeze pe aproximarea even-odd a evantaiului, pentru precizie garantată la testele de coliziune de luptă.

---

# 7. Iluminat, Umbre și Efecte de Mediu

## 7.1 Arhitectura Pe Niveluri (Tiers)

Pipeline-ul de iluminat al Echoes of the Heir este un pipeline de shadere GLSL, structurat pe trei niveluri de calitate (`Config.graphicsQuality`), astfel încât să se degradeze grațios pe hardware mai slab și să revină complet la o cale alternativă dacă compilarea shader-ului eșuează pe un anumit GPU/driver:

```java
// core/src/main/java/main/Config.java
public static final int GRAPHICS_LOW    = 0;
public static final int GRAPHICS_MEDIUM = 1;
public static final int GRAPHICS_HIGH   = 2;
```

Documentația proprie a `gfx/shader/ShaderPipeline.java` afirmă garanția de siguranță centrală a întregului pipeline:

> "fiecare shader este compilat la construire și, dacă ORICARE dintre ele eșuează la compilare sau linkare pe GPU/driver-ul curent, `isAvailable()` returnează false iar apelanții revin la calea de lumină legacy bazată pe textură coaptă... Nicio excepție nu scapă, jocul nu se blochează niciodată din cauza unui shader."

### Cele Trei Niveluri, În Detaliu

- **LOW** — deloc pipeline de shadere. `environment/Lightning.java` folosește o căutare BFS (breadth-first-search) de flood-fill pe tile-uri pentru a determina care tile-uri sunt luminate, apoi "perforează găuri" pentru sursele de lumină într-o textură de întuneric coaptă, folosind modul de blending `DST_OUT`.
- **MEDIUM** — folosește calea de shader, dar compilată cu o variantă de umbră ieftină, obținută prin prefixarea sursei shader cu directive de preprocesor:

```java
// gfx/shader/ShaderPipeline.java
private static final String CHEAP_DEFINES = "#define SHADOW_STEPS 12\n#define CHEAP 1\n";
/** MED/mobile variant of the light shader: same source compiled with fewer shadow-march steps and
 *  the organic-noise detail stripped (CHEAP_DEFINES). Falls back to lightShader if it fails to
 *  compile, so the cheap tier can never lose lighting. */
private final ShaderProgram lightShaderCheap;
```

- **HIGH** — pipeline-ul complet: ray-march de umbră în 32 de pași, cu detaliu organic de zgomot, plus o trecere de bloom prin captură de scenă, gradare de culoare, și rim lighting per-sprite.

Un detaliu elegant: shader-ul MEDIUM **nu** e un shader separat scris de la zero — e literalmente aceeași sursă GLSL, recompilată cu `SHADOW_STEPS` redus de la 32 la 12 și zgomotul organic dezactivat prin `#ifdef`. Aceasta garantează că cele două niveluri rămân sincronizate comportamental prin construcție, nu prin disciplină de menținere manuală.

### Auto-Vindecare la Compilare: Fișier → Fallback Baked-In

```java
// gfx/shader/ShaderPipeline.java
private static ShaderProgram compile(String vertFile, String vertBake, String fragPath, String fragBake) {
    String fragFile = src(fragPath, fragBake);
    ShaderProgram p = new ShaderProgram(vertFile, fragFile);
    if (p.isCompiled()) return p;
    Gdx.app.error("ShaderPipeline", "shader file '" + fragPath
        + "' compiled with errors — falling back to baked-in source. Log: " + p.getLog());
    ShaderProgram fb = new ShaderProgram(vertBake, fragBake);
    if (fb.isCompiled()) { p.dispose(); return fb; }
    fb.dispose();
    return p;
}
```

Shaderele trăiesc ca fișiere editabile `.vert`/`.frag` sub `core/assets/res/shaders/`, dar codul Java păstrează și o copie identică, "coaptă" (baked-in), ca șiruri de caractere constante în `ShaderSources.java`. Dacă versiunea din fișier eșuează la compilare — de exemplu pentru că cineva a editat greșit un `.frag` în timpul dezvoltării — sistemul se "auto-vindecă", reîncercând cu varianta coaptă înainte de a renunța complet. Acest mecanism a fost verificat activ: stricarea intenționată a `light.frag` a produs jurnalizarea erorii urmată de căderea reușită înapoi pe varianta coaptă, confirmată funcțională.

## 7.2 `Lightning.draw()` — Alegerea Căii, Cadru cu Cadru

```java
// core/src/main/java/environment/Lightning.java
if (currentMaxDarkness <= 0.001f) { gfx.shader.LightDebug.tier = "off (no darkness)"; return; }

boolean shaderLit = false;
if (gp.config.graphicsQuality != Config.GRAPHICS_LOW) {
    shaderLit = drawShaderLighting(g2, currentMaxDarkness,
            playerWorldX, playerWorldY, playerScreenX, playerScreenY,
            screenWidth, screenHeight);
}
if (!shaderLit) {
    gfx.shader.LightDebug.tier = "baked (shader unavailable)";
    g2.beginLightMask();
    g2.setBlendMode(GdxRenderer.BLEND_NORMAL);
    g2.setColor(nightColor);
    g2.setAlpha(currentMaxDarkness);
    g2.fillRect(0, 0, screenWidth, screenHeight);
    g2.setAlpha(1f);
    g2.setBlendMode(GdxRenderer.BLEND_DSTOUT);
    // ... drawLight(...) / drawEntityArrayLights(...) perforează găuri pentru fiecare sursă ...
    g2.endLightMask();
    g2.drawLightMask();
}
```

Un detaliu important, ușor de ratat: harta nu e deloc întunecată dacă `currentMaxDarkness` e sub un prag minim — jocul sare peste tot pipeline-ul de iluminat, complet, când nu există deloc întuneric de aplicat (o hartă de zi, luminoasă). Acesta a fost, de fapt, sursa unei confuzii reale de depanare (documentată în memoria `shader-lighting-pipeline`): un tester raporta "iluminatul nu funcționează pe HIGH" în timp ce de fapt harta de start pur și simplu nu avea `ambientLight` aplicat corect la momentul respectiv — nu o problemă de shader, ci o problemă de "nu există întuneric de arătat".

## 7.3 Masca de Ocluzori: Cine Aruncă Umbră și De Ce Jucătorul Nu

```java
// core/src/main/java/environment/Lightning.java
// The player is NOT drawn into the occluder mask. The player carries the primary light centered
// on itself, so its silhouette would ray-march-shadow its OWN light pool — a black player-shaped
// hole with starburst streaks (the "shadows mixed up" bug). LIGHT_EXCLUDE can't fully prevent it
// because the player body spans a large fraction of its own (small) light radius. Excluding the
// player from the mask kills it definitively; other casters (NPCs, monsters, objects, trees) are
// offset from the lights that matter, so they cast correctly and get rim light.
```

Umbrele sunt proiectate pe o mască de ocluzori siluetă: fiecare tile de pe ecran situat pe o celulă solidă de coliziune (via `TileManager.drawDepthOccluders()`, Secțiunea 5.4), plus orice NPC/monstru/obiect marcat `castsShadow()` (Secțiunea 4.1), este desenat ca siluetă neagră într-un buffer offscreen. Sprite-ul propriu al jucătorului este exclus **deliberat** din această mască — dacă nu ar fi, propria lumină a jucătorului (centrată pe poziția lui) ar proiecta o umbră a jucătorului asupra lui însuși, producând o "gaură" neagră în formă de jucător cu dungi radiante de tip starburst, radiind chiar din propriile picioare.

În mod similar, orice entitate care **emite** lumină e exclusă din masca de ocluzori pentru propria ei rază — aceeași logică de auto-umbrire se aplică oricărei surse de lumină purtătoare (un NPC strălucitor, o torță purtată).

## 7.4 Semnătura Grilei de Lumină: Optimizarea "Stând Nemișcat, Cost Zero"

```java
// core/src/main/java/environment/Lightning.java
private long lightGridSignature(int playerCX, int playerCY, int playerRadPx,
                                boolean isLow, boolean useTileShadows) {
    int ts = gp.tileSize;
    long h = 1125899906842597L; // prim mare, sămânță
    h = h * 31 + (playerCX / ts);
    h = h * 31 + (playerCY / ts);
    h = h * 31 + (playerRadPx / ts);
    h = h * 31 + (isLow ? 1 : 0);
    h = h * 31 + (useTileShadows ? 1 : 0);
    h = accumulateArraySignature(h, gp.obj, ts);
    h = accumulateArraySignature(h, gp.npc, ts);
    h = accumulateArraySignature(h, gp.monster, ts);
    return h;
}
```

Această funcție calculează un hash care sumarizează poziția-de-tile, raza și flag-urile de calitate ale **fiecărei** lumini active, la fiecare cadru. Dacă acel hash este neschimbat față de cadrul anterior, întregul pas costisitor de marcare a iluminării tile-urilor/ray-cast este sărit complet — ceea ce înseamnă că un jucător stând nemișcat într-un meniu sau dialog costă practic nimic pentru calculul de iluminat. `clearShadowCaches()` resetează semnătura stocată explicit la fiecare schimbare de hartă/geometrie, forțând o recalculare completă atunci când e efectiv necesară.

## 7.5 O Cronologie a Depanării: Patru Bug-uri Diferite, Un Simptom Comun

Memoria `shader-lighting-pipeline` documentează o secvență fascinantă de investigație, demnă de rezumat aici ca lecție de metodologie de depanare pentru orice inginer care lucrează la acest sistem în viitor. Simptomul raportat a fost, în esență, mereu același: **"luminile/umbrele par să se miște greșit atunci când camera se mișcă sau jucătorul merge"**. Patru cauze radical diferite au produs acest simptom, pe rând, în sesiuni separate de depanare:

1. **Auto-umbrirea jucătorului** (rezolvat prin excluderea jucătorului din masca de ocluzori, Secțiunea 7.3).
2. **Transformarea lumii aplicată peste blit-ul măștii de lumină** — `GdxRenderer.drawLightMask()` desena masca de lumină pe tot ecranul în timp ce batch-ul purta încă transformarea de lume (translatarea de shake al camerei); din moment ce masca e calculată în pixeli de ecran, aplicarea translatării de lume peste patrulaterul ei full-screen deplasa bazinul de lumină exact cu distanța de shake — dar **doar** în cadrele în care camera trema efectiv, ceea ce explică semnătura "doar când camera se mișcă". Soluția a fost neutralizarea transformării batch-ului specific pentru blit-ul măștii.
3. **Orientare Y greșită a proiecțiilor FBO-urilor de mască** — `beginOccluderMask()`/`beginLightMask()` foloseau o proiecție ortografică y-UP, în timp ce shaderul de umbră presupunea orientarea scenei (y-DOWN). Rezultatul: fiecare umbră calculată prin ray-march provenea dintr-o lume de ocluzori răsturnată cu susul în jos — umbrele se desprindeau vizibil de proiectoarele lor, alunecau vertical în timpul mersului, și "înconjurau" complet NPC-urile în loc să cadă în spatele lor.
4. **Oglindirea verticală completă a măștii pe calea fără captură de scenă** (detaliată în Secțiunea 6.2) — invizibilă în verificările anterioare pentru că orice test se concentra involuntar pe lumina proprie a jucătorului, care stă mereu în centrul ecranului, punct pe care o oglindire îl mapează pe el însuși.

Regula operațională reținută din această secvență, utilă pentru orice viitoare regresie similară: verificați, în ordine, (1) transformarea aplicată la blit-ul măștii, (2) orientarea de flip a FBO-ului, și (3) auto-umbrirea jucătorului — și, esențial, **verificați întotdeauna cu o sursă de lumină offset**, niciodată doar cu lumina proprie a jucătorului, pentru că centrul ecranului e invariant la oglindire și poate masca un bug real.

## 7.6 Frumusețea Umbrelor: De la "Blob-uri" la Umbre Fizic Plauzibile

Codul conține și o "trecere de frumusețe" documentată — o serie de ajustări fine care au transformat umbrele de la o aproximare grosolană la ceva vizual convingător:

- **Ocluderele au margini netezite**: textura de culoare a `occluderFbo` e filtrată liniar, plus shaderul aplică `occ = smoothstep(0.20, 0.85, alpha)` — siluete rotunde, moi, fără "ștampile" pătrate de tile.
- **Estomparea în lungime** (length taper): umbrele lungi, întinse, se "topesc" treptat în loc să rămână la intensitate maximă pe toată lungimea lor — o umbră de contact aproape de obiect rămâne întunecată, dar coada ei lungă se estompează, evitând aspectul de "umbră supradimensionată".
- **Lumini purtătoare excluse din propriile umbre**: orice entitate cu `lightSource && lightRadius > 0` e omisă din array-ul de proiectoare — o torță/NPC strălucitor așezat chiar în centrul propriei lumini nu-și mai "înghite" singur lumina.

## 7.7 `TileParticleEmitter` și `MapShaderManager`

`environment/TileParticleEmitter.java` (488 linii) gestionează particulele de pași și frunze căzătoare printr-un pool fix (`MAX_PARTICLES = 200`) cu alocator de tip stivă liberă și compactare prin swap-remove — tiparul clasic de particule pooled, fără gunoi generat per cadru. Un detaliu de optimizare demn de remarcat: lista sortată de particule **persistă între cadre**, nu se recalculează de la zero:

```java
// environment/TileParticleEmitter.java
* OPTIMIZATION: The sorted list is preserved across frames. Each call:
*   1. Compacts dead entries out (O(n))
*   2. Appends newly-activated particles (O(new))
*   3. Insertion-sorts — because particles drift only a few pixels per frame,
*      the list is almost-sorted from last frame, so insertion sort runs in
*      effectively O(n) instead of O(n²) on random data.
```

Deoarece particulele se deplasează doar câțiva pixeli pe cadru, lista rămâne "aproape sortată" de la un cadru la altul — un insertion sort pe o listă aproape sortată rulează efectiv în O(n), nu în O(n²) cum ar rula pe date complet aleatoare. Aceeași emisie de particule alimentează bucla de merge din `RenderPipeline` descrisă în Secțiunea 6.3.

`environment/MapShaderManager.java` (484 linii) gestionează efecte vizuale suplimentare (denumite istoric "shader-like", deși multe sunt de fapt CPU/vectoriale): scânteierea apei (printr-un LUT de sinus), vignetă (gradient radial copt), gradare de culoare (mod sepia pentru flashback-uri, overlay cald de zi), și sistemul complet de vreme (ploaie, zăpadă, furtună cu fulgere) — toate cu LOD pe 3 niveluri bazat pe FPS (mai puține particule sub 30fps, și mai puține sub 45fps). Un detaliu curat de curățenie de cod: sistemul de particule ambientale a fost dezactivat complet, iar codul care le actualiza a fost și el sărit explicit, pentru a nu irosi CPU calculând particule care nu se mai desenează niciodată.

## 7.8 `WindField` și `WindPainter` — Sistemul de Vânt

Sistemul de vânt, deja documentat din perspectiva autorului de conținut în WIND_SYSTEM_GUIDE.md, are un mecanism intern precis.

### Formatul de Fișier `.windmap`

```
int   magic   = 0x57494E44  ("WIND")
int   version = 1
int   cols
int   rows
float[rows*cols] interleaved: { strength, rainBonus, angle } per cell (row-major)
```

Fiecare hartă are un grid **continuu** (nu cuantizat la tile-uri Tiled) de trei valori per celulă: `strength` (coeficient de vânt 0-1), `rainBonus` (întărire suplimentară doar cât timp plouă), și `angle` (direcție în radiani). Încărcarea încearcă mai întâi o resursă pe classpath (`/res/maps/<id>.windmap`), apoi fișiere sursă alternative — și tolerează o nepotrivire de dimensiune de grilă (dacă harta a fost redimensionată în Tiled între timp) prin clamping, jurnalizând doar un avertisment, nu o eroare fatală.

### Rafalele (Gusts): Vântul Nu Se Inversează Niciodată Instant

```java
// environment/WindField.java
private static final float GUST_MIN          = 0.70f; // dip to 70% before changing direction
private static final float GUST_RECOVER_RATE = 0.010f;
private static final float GUST_ROTATE_RATE  = 0.03f;
private static final int   GUST_CHANGE_MIN   = 240;    // 4s @60fps
private static final int   GUST_CHANGE_MAX   = 720;    // 12s @60fps
private static final float GUST_MAX_SWING    = 0.9f;   // radiani maximi pentru o singură balansare
```

Peste câmpul pictat, întreaga hartă "răsuflă" printr-o direcție globală animată care oscilează lent. O mașină de stări în doi pași controlează asta: când direcția urmează să se schimbe, intensitatea globală mai întâi **coboară** la `GUST_MIN` (70%), **apoi** se rotește spre noul unghi țintă, **apoi** revine la intensitatea maximă — astfel încât vântul nu se inversează niciodată instantaneu, ci trece printr-un moment de "calm relativ" înainte de a-și schimba direcția.

### Proiecția Doar Pe Axa de Mișcare

```java
// core/src/main/java/entity/Player.java
private float windForceAlongMovement(boolean left, boolean right, boolean up, boolean down) {
    if (gp.windField == null) return 0f;
    int sampleX = worldX + inertiaArea.x + inertiaArea.width  / 2;
    int sampleY = worldY + inertiaArea.y + inertiaArea.height / 2;
    float wfx = gp.windField.sampleX(sampleX, sampleY);
    float wfy = gp.windField.sampleY(sampleX, sampleY);
    // ... proiectat pe direcția de mișcare curentă, componenta perpendiculară aruncată ...
}
```

Fizica jucătorului proiectează forța de vânt eșantionată **doar** pe axa de mișcare curentă a jucătorului — vântul din spate accelerează, vântul din față încetinește, dar componenta perpendiculară e aruncată complet, astfel încât vântul nu împinge niciodată jucătorul lateral, în afara controlului lui.

### `WindPainter` — Unealta de Autorat În-Joc

Accesibil prin Ctrl+D (activare mod debug) apoi Ctrl+W, `WindPainter` (292 linii) pictează direct peste harta vie: tragere cu click stânga pictează vânt sub o perie rotundă moale (direcția e chiar direcția de tragere a mouse-ului), click dreapta șterge, rotița de mouse ajustează raza periei, tasta `R` comută canalul de "întărire de ploaie", tasta `F` blochează o direcție fixă controlată de săgeți, iar Ctrl+S salvează direct în fișierul `.windmap` al hărții curente.

---

# 8. UI, Meniuri, Dialog și Cutscene-uri

## 8.1 `UI.java` — Cel Mai Mare Fișier Din Codebase

`core/src/main/java/ui/UI.java` (5073 linii) este cel mai mare fișier din întregul proiect. Nu e practic (și nici util) să se documenteze fiecare linie — în schimb, această secțiune descrie structura lui majoră și responsabilitățile fiecărei zone.

### Dispatch-ul Central

```java
// core/src/main/java/ui/UI.java (structură)
public void draw(GdxRenderer g2) {
    if (gp.gameState == GamePanel.titleState)      drawTitleScreen();
    if (gp.gameState == GamePanel.playState) { drawPlayerLife(); drawMessage(); drawLevelUpBanner(); drawInteractionPrompt(); gp.thoughts.draw(); }
    if (gp.gameState == GamePanel.pauseState)      { drawPlayerLife(); drawPauseScreen(); }
    if (gp.gameState == GamePanel.dialogueState)   drawDialogueScreen();
    if (gp.gameState == GamePanel.characterState)  { drawCharacterScreen(); drawInventory(); }
    if (gp.gameState == GamePanel.optionsState)    drawOptionsScreen();
    if (gp.gameState == GamePanel.gameOverState)   drawGameOverScreen();
    if (gp.gameState == GamePanel.transitionState) drawTransition(g2);
    if (gp.gameState == GamePanel.levelUpState)    { drawPlayerLife(); drawLevelUpScreen(); }
    if (gp.gameState == GamePanel.skillTreeState)  { drawPlayerLife(); drawSkillTreeScreen(); }
    if (gp.gameState == GamePanel.cutsceneState)   { drawDialogueScreen(); gp.thoughts.draw(); }
    if (gp.gameState == GamePanel.journalState)    drawJournalScreen();
    if (gp.gameState == GamePanel.shopState)       drawShopScreen();
    if (actTitleTimer > 0)                          drawActTitle();
}
```

Nu este un `switch`, ci o secvență plată de condiționale `if` — o alegere pragmatică pentru un fișier de această dimensiune, unde câteva stări (HP, mesaje) trebuie desenate în combinație cu ecranul principal al stării respective.

### Hartă a Secțiunilor Majore (După Intervale de Linii Aproximative)

| Interval | Secțiune |
|---|---|
| 1–140 | Câmpuri: fonturi, handle-uri de sprite, sistemul de culori-marker pentru nine-slice UI.png (`UI_MARK_*`), record-ul `PanelTheme` + teme per fereastră (`THEME_DEFAULT/DIALOGUE/JOURNAL/OPTIONS/CHARACTER/INVENTORY/LEVELUP/SKILLTREE/PAUSE/HUD/SHOP`), coadă de mesaje, stare de listă de prieteni, stare Join-Game (BLE) |
| 820–934 | **Dispatch-ul `draw()`** |
| 1057–1409 | `drawPlayerLife()`, `drawAbilityBar()`, `drawHpBar()`, `drawStatBar()` |
| 1410–1541 | `drawMessage()`, `drawInteractionPrompt()` |
| 1559–1928 | `drawTitleScreen()` (~370 linii — cel mai mare ecran individual; include un încărcător de fundal animat pe cadre) |
| 1929–2187 | `drawMultiplayerBrowser()`, `drawMultiplayerInput()` |
| 2188–2364 | `drawPauseScreen()` |
| 2365–2620 | `updateDialogueState()`, `drawDialogueScreen()`, `drawDialogueChoices()` |
| 2621–2744 | `drawJournalScreen()` |
| 2745–2842 | `drawGameOverScreen()` |
| 2864–3217 | `drawCharacterScreen()`, `drawInventory()` (grilă de sloturi, etichete de cantitate stivuite, cursor de selecție) |
| 3580–3885 | `drawShopScreen()`, `drawShopGrid()`, `drawShopTooltip()` |
| 4179–4334 | `drawLevelUpScreen()`, `drawLevelUpBanner()` |
| 4335–4695 | `drawSkillTreeScreen()` (~360 linii — al doilea cel mai mare ecran individual) |
| 4696–4872 | **Sistemul de panouri/butoane nine-slice**: `drawSubWindow`, `drawPanel` (bake de schimb de paletă + cache), `drawButton` |
| 4873–5073 | `drawFriendsList()`, `drawJoinGameScreen()` (fluxul de invitație BLE prin NFC) |

Notă: tracker-ul și jurnalul de quest-uri nu sunt dispecerizate din `UI.draw()` — trăiesc în `RenderPipeline.drawWorldOverlays()`, care apelează direct `gp.questManager.drawTracker()`/`drawLog()`.

### Sistemul Nine-Slice cu Schimb de Paletă

Un mecanism reutilizabil demn de remarcat: în loc de zeci de imagini de panou UI diferite pentru fiecare temă de culoare, jocul folosește **o singură** imagine sursă `UI.png`/`Button.png`, pictată cu culori-marker fixe, remapate per fereastră printr-un "bake de schimb de paletă" ținut în cache:

```java
// core/src/main/java/ui/UI.java (structură)
public void drawPanel(int x, int y, int width, int height, PanelTheme theme) {
    long key = paletteKey(theme.main(), theme.shadow(), theme.highlight(), theme.highlight2());
    Sprite themed = uiPanelCache.get(key);
    if (themed == null) {
        themed = GdxRenderer.bakePaletteSwap(uiPanelRaw, UI_MARKERS,
                new Color[]{ theme.main(), theme.shadow(), theme.highlight(), theme.highlight2() });
        uiPanelCache.put(key, themed);
    }
    g2.drawNineSlice(themed, x, y, width, height);
}
```

Fiecare temă (dialog, inventar, opțiuni, magazin, etc.) obține propria variantă de culoare a aceleiași texturi sursă, coaptă o singură dată și memorată — nu o textură separată per temă autorată manual, ci o singură sursă de artă redistribuită programatic.

## 8.2 `Menu` — Sistem Declarativ de Meniu

`ui/Menu.java` (330 linii) oferă un constructor declarativ de listă de elemente de meniu: `Menu.of(title, theme).button(...).toggle(...).selector(...).slider(...)`. Documentația proprie a clasei explică motivația centrală:

> "Fără sincronizare de index de array între codul de desenare (UI.java) și codul de input (KeyHandler.java) ca înainte."

Detaliul cheie de implementare: **testarea de coliziune a mouse-ului este autoritară din geometria desenată efectiv**. `drawItems()` înregistrează dreptunghiul exact al fiecărui rând, în fiecare cadru, în array-uri (`rectX/Y/W/H`); `itemAt(px,py)`/`sliderValueAt()`/`selectorArrowAt()` interoghează toate acele dreptunghiuri deja înregistrate. Aceasta garantează matematic că starea de hover/click nu poate niciodată să devieze de la ceea ce e efectiv desenat pe ecran — o clasă întreagă de bug-uri ("clic-ul funcționează pe un buton vizual diferit de cel apăsat") devine imposibilă prin construcție, nu doar improbabilă.

Acest sistem e folosit direct de meniurile de Setări și Pauză, și de widget-urile partajate `drawMedievalToggle`/`drawMedievalSlider` din `UI.java`.

## 8.3 `Minimap` — Textura de Teren Coaptă și Aproximarea de Clipare Circulară

`ui/Minimap.java` (318 linii) coace întreaga hartă o singură dată per hartă (`bakeTerrainImage()`, la `BAKE_PIXELS_PER_TILE = 4` pixeli per tile) într-un `Pixmap`, cache-uit după ID-ul hărții. Fiecare GID de tile e eșantionat pentru culoarea lui reprezentativă direct din arta reală a tile-ului (`sampleTileColor(gid)`), nu prin euristici hardcodate per tileset.

Un detaliu de portare demn de menționat: OpenGL nu are un mecanism nativ de clipare circulară (scissor test-ul GL e întotdeauna dreptunghiular), deci clipul circular al vechii implementări Java2D (`Ellipse2D`) nu poate fi reprodus direct. Soluția: terenul pătrat complet e desenat, apoi o vinietă radială coaptă (opacă/neagră la margine, cache-uită per rază) plus inele concentrice de bordură **maschează vizual** colțurile pătrate — "vizual echivalent cu vechiul clip `Ellipse2D`", conform comentariului din cod, deși mecanismul de dedesubt e complet diferit.

## 8.4 `CutsceneManager` — Secvențe Scriptate Pe Faze

`ui/CutsceneManager.java` (561 linii) implementează secvențe scriptate ca o mașină de stări per-fază, avansată printr-un contor de cadre (`counterReached(target)`).

### `scene_awakening()` — Introducerea Jocului

O secvență de 11 faze: inițializare/blocare cameră → fade-in întunecat → pauză → typewriter "..." → typewriter "Unde... sunt?" → titlu de act "ACT I: The Awakening" → fade-out întunecat (pornind muzica) → panoramare lină a camerei spre jucător (interpolare exponențială, `panSpeed = 0.03f`) → fade-in al jucătorului → curățenie finală (deblochează camera, revine la `playState`, afișează titlul de act, primul monolog interior, pornește quest-urile automate). Are o "gaură de scăpare" (`skipAwakening()`) care execută direct pașii de curățenie, pentru jucătorii care apasă Escape/Enter în timpul introducerii.

### `scene_ending()` — Genericul de Final

O altă secvență de 11 faze: oprește muzica, forțează vremea la PLOAIE, redă fanfara de victorie, fade-in negru cu particule de ploaie desenate în spațiul de ecran, text "To Be Continued", cardul de titlu **"Echoes of the Heir"** — numele curent al jocului apare literalmente în interiorul secvenței de generic finale codificate în joc — apoi credite defilante (cu antete de secțiune colorate auriu, precum "Directed by", "Programmed by"), defilare pentru aproximativ 2500 de cadre, repetarea cardului de titlu, apoi resetarea vremii și revenirea la ecranul de titlu.

---

# 9. Quest-uri și Skill Tree

## 9.1 `QuestManager` — Implementarea Din Spatele QUEST_GUIDE.md

`core/src/main/java/main/QuestManager.java` (1059 linii) implementează exact sistemul descris în QUEST_GUIDE.md. Coexistă două modele de quest: **contor plat legacy** (`current`/`target`, incrementat prin `progress(id, amount)`) și **bazat pe pași** (`QuestStep[] steps`, `currentStep`, `stepProgress`), cu pași de tip `talk | deliver | collect | kill | go`.

Definițiile de quest se încarcă o singură dată din `res/data/quests.json`, printr-un parser JSON scris manual (fără bibliotecă) care urmărește manual adâncimea de acolade/paranteze/ghilimele — un tipar consecvent regăsit și în `SkillTree` și `InputBindings`. Un detaliu de confort pentru dezvoltare:

```java
// core/src/main/java/main/QuestManager.java
// In DEBUG_MODE, read directly from the source folder so edits to
// quests.json are picked up immediately without a resource sync step.
if (Main.DEBUG_MODE) {
    File devFile = new File(System.getProperty("user.dir") + "/core/assets/res/data/quests.json");
    // ...
}
```

### Evitarea Unei Notificări Duble La Pornire

```java
// core/src/main/java/main/QuestManager.java
public QuestManager(GamePanel gp) {
    this.gp = gp;
    loadRegistry();
    // Do NOT call startAutoQuests() here — quests are started by the
    // awakening cutscene on New Game, or restored from save on Load Game.
    // Calling it here would queue a "New quest" notification that fires
    // a second time once the cutscene ends, causing a duplicate.
}
```

Acesta e un exemplu simplu dar instructiv de bug de ordonare temporală, prevenit deliberat prin comentariu: dacă constructorul ar porni automat quest-urile marcate `autoStart`, iar cutscena de trezire ar face-o din nou mai târziu, jucătorul ar vedea notificarea "quest nou" de două ori.

### Execuția Pasului de Quest La Vorbirea Cu Un NPC

```java
// core/src/main/java/main/QuestManager.java
case "deliver" -> {
    if (step.item != null) {
        int idx = gp.player.searchItemInInventory(step.item);
        if (idx == 999) {
            if (step.failDialogue != null) npc.startNamedDialogue(npc, step.failDialogue);
            else npc.startDialogue(npc, 0);
            return true;
        }
        if (step.consume && idx < gp.player.inventory.size()) {
            entity.Entity item = gp.player.inventory.get(idx);
            if (item.amount > 1) item.amount--;
            else gp.player.inventory.remove(idx);
        }
    }
    // ... acordă recompensa, redă dialogul de succes, avansează pasul ...
}
```

### Auto-Pornirea La Prima Conversație

```java
// core/src/main/java/main/QuestManager.java
// No active quest step for this NPC — auto-start quests whose first step
// is a "talk" action targeting this NPC (prerequisite must be met).
for (int r = 0, rn = registry.size(); r < rn; r++) {
    QuestDef def = registry.get(r);
    // ...
    if (!npcId.equals(firstStep.get("npc"))) continue;
    if (!"talk".equals(firstStep.getOrDefault("action", "talk"))) continue;
    String prereq = strVal(def.props, "prerequisite", "");
    if (!prereq.isEmpty() && !isComplete(prereq)) continue;
    addQuest(defId);
    return executeStepForNpc(npcId, npc);
}
```

Această buclă implementează exact regula documentată în QUEST_GUIDE.md ("Auto-Start on First Talk"): dacă niciun pas activ nu vizează deja acest NPC, sistemul caută printre toate definițiile de quest neîncepute unul al cărui **prim** pas e de tip `talk` și vizează acest NPC specific — dacă găsește unul (și prerechizita, dacă există, e îndeplinită), îl pornește pe loc și execută imediat pasul 0.

### Legătura Cu Multiplayer-ul

```java
// core/src/main/java/main/QuestManager.java
/** Every tracked quest as {id -> complete?}. Used by MultiplayerClient's progress_sync to
 *  tell the server which quests we hold, since NPC states there gate on them. */
public java.util.Map<String, Boolean> questCompletionMap() {
    // ...
}
```

Această hartă e exact ce `MultiplayerClient` trimite periodic serverului prin mesajul `progress_sync` (Secțiunea 12.2) — mecanismul prin care progresul de quest, simulat local pe client, informează serverul astfel încât NPC-urile găzduite acolo să poată condiționa dialogul și disponibilitatea magazinului pe baza lui.

## 9.2 `SkillTree` — Arborele de Abilități

`core/src/main/java/main/SkillTree.java` (249 linii) încarcă `res/data/skilltree.json` într-un array `SkillNode[]` (id, nume, descriere, cost, poziție de grilă `col`/`row`, și `requires` — id-ul unui nod prerechizit). Spre deosebire de `QuestManager`, care sare silențios peste un fișier lipsă, `SkillTree` **eșuează dur** dacă fișierul lipsește sau se analizează la zero noduri:

```java
// core/src/main/java/main/SkillTree.java
private SkillNode[] loadFromJson() {
    try (InputStream is = util.ResourceCache.openClasspathStream("/res/data/skilltree.json")) {
        if (is == null) throw new RuntimeException("skilltree.json not found in resources");
        // ...
        if (list.isEmpty()) throw new RuntimeException("skilltree.json parsed 0 nodes");
        // ...
    }
}
```

### Deblocarea: Cost, Prerechizite, "Dezvăluire" Progresivă

```java
// core/src/main/java/main/SkillTree.java
public boolean canUnlock(Player player, int idx) {
    if (idx < 0 || idx >= nodes.length) return false;
    if (!isRevealed(idx)) return false;
    SkillNode n = nodes[idx];
    if (n.unlocked) return false;
    if (player.skillPoints < n.cost) return false;
    if (n.requires == null) return true;
    int reqIdx = findIndexById(n.requires);
    return reqIdx >= 0 && nodes[reqIdx].unlocked;
}

public boolean unlock(Player player, int idx) {
    if (!canUnlock(player, idx)) return false;
    SkillNode n = nodes[idx];
    player.skillPoints -= n.cost;
    n.unlocked = true;
    revealMaxColCache = -1;
    player.applySkillNodeEffect(n.id);
    return true;
}
```

Deblocarea unui nod verifică patru condiții AND: nodul e "dezvăluit" (coloana lui e la sau sub `getRevealMaxCol()`), nu e deja deblocat, jucătorul are suficiente puncte de abilitate, și (dacă nodul are o prerechizită) nodul prerechizit e deja deblocat. `getRevealMaxCol()` (cache-uit, invalidat la fiecare deblocare) limitează coloanele selectabile la `coloanaCeaMaiDepărtatăDeblocată + 1` — arborele se "dezvăluie" progresiv de la stânga la dreapta, jucătorul neputând sări direct la nodurile avansate fără să deblocheze rândul anterior. Efectul concret al deblocării (`player.applySkillNodeEffect(n.id)`) trăiește în `Player`, nu în `SkillTree` — separarea de responsabilități între "regulile arborelui" și "ce face fiecare nod".

### Cursorul Direcțional Prin Scor Ponderat

```java
// core/src/main/java/main/SkillTree.java
public void moveSelection(Player player, int dx, int dy) {
    // ...
    for (int i = 0; i < nodes.length; i++) {
        // ...
        int score = Math.abs(colDiff) * 10 + Math.abs(rowDiff) * 8;
        if (score < bestScore) { bestScore = score; best = i; }
    }
}
```

Navigarea pe direcție (sus/jos/stânga/dreapta) în interfața arborelui nu se limitează la mișcare pe o axă exactă — folosește o funcție de scor ponderat pentru a alege "cel mai apropiat nod în acea direcție generală" dintre toate nodurile situate strict în direcția cerută, oferind o senzație de navigare mai naturală decât o grilă strictă.

### Deblocarea de Abilități În Multiplayer

```java
// core/src/main/java/main/KeyHandler.java
if (gp.multiplayerMode && gp.mpClient != null && gp.mpClient.isConnected()) {
    // In multiplayer the server owns the skill points and the unlocked set: send the
    // intent and let it decide. The node is only marked unlocked and its effect applied
    // when the server replies skill_result ok (see MultiplayerClient.handleSkillResult),
    // so a client can't grant itself a skill it can't afford.
    SkillTree st = gp.player.skillTree;
    int idx = st.selectedIndex;
    if (idx >= 0 && idx < st.getNodes().length) gp.mpClient.sendSkillUnlock(st.getNodes()[idx].id);
}
```

În multiplayer, `KeyHandler` nu apelează niciodată `unlockSelected()` direct — trimite doar intenția (`sendSkillUnlock`) și așteaptă răspunsul `skill_result` al serverului înainte ca nodul să fie efectiv marcat deblocat local. Detaliile complete ale acestei migrări la autoritate de server sunt în Secțiunea 12.4.

---

# 10. Salvare/Încărcare Locală și Recuperarea Pierderii Contextului GL

## 10.1 Formatul de Salvare Locală

`core/src/main/java/data/SaveLoad.java` (1163 linii) criptează fișierul de salvare local cu **AES-128-CBC** și un IV aleator de 16 octeți adăugat la începutul textului cifrat:

```java
// core/src/main/java/data/SaveLoad.java (rezumat)
private static final byte[] SAVE_KEY = { 0x4D,0x69,0x63,0x68,0x69,0x41,0x64,0x76,
                                          0x65,0x6E,0x74,0x75,0x72,0x65,0x32,0x30 }; // "MichiAdventure20"
// Layout fișier: [IV pe 16 octeți][text cifrat]
Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
```

Cheia de 16 octeți codifică literal ASCII-ul "MichiAdventure20" — un rest istoric al numelui vechi de dezvoltare, păstrat neschimbat, pentru că schimbarea lui ar invalida toate salvările existente ale jucătorilor. Formatul serializat în sine este un bloc de text plat `cheie=valoare`, scris prin interfața agnostică de platformă `platform.GameStorage` (Secțiunea 14.3) — deliberat simplu și ușor de comparat (diff) de un om, mai degrabă decât un format binar sau JSON.

**Câmpurile salvate** confirmă acoperirea completă documentată deja parțial în QUEST_GUIDE.md: statistici de jucător, perechi de inventar, progres per-quest (`quests.N.id/name/desc/current/target/step/stepProgress`), stări de obiecte de hartă (`obj.N.name/worldX/worldY/opened/loot`), fragmente de memorie colectate, boss-i învinși (`boss1Defeated`..`boss4Defeated`), progres al poveștii (`storyAct`, `endingChosen`), porți deschise, NPC-uri întâlnite, stoc de magazin, și noduri de skill tree deblocate.

### O Poveste de Bug: Ordinea de Reîncărcare a Hărții La Load

```java
// core/src/main/java/data/SaveLoad.java (comentariu, rezumat)
// MAP RELOAD — load the saved map's TMX + entities BEFORE applying saved state on top, otherwise
// [...] That mismatch is what caused: player teleported to wrong spot, drawn under tiles, and
// unable to move more than a couple of tiles.
```

Acest comentariu narează un bug real de ordonare: dacă starea salvată a jucătorului (poziție, inventar) era aplicată **înainte** ca harta salvată să fie efectiv încărcată din TMX, jucătorul se trezea teleportat într-o poziție greșită (calculată relativ la o altă hartă, încă implicit încărcată), desenat sub tile-uri, și incapabil să se miște mai mult de câteva tile-uri (blocat în geometria de coliziune a hărții greșite). Soluția a fost impunerea ordinii stricte: mai întâi harta, apoi starea salvată deasupra ei.

### Reconcilierea Cloud vs. Local

```java
// core/src/main/java/data/SaveLoad.java (rezumat)
// fetch() tratează salvarea din cloud ca autoritară doar dacă e efectiv mai nouă decât cea de pe disc
if (state.timestamp >= localSaveTimestamp()) { /* aplică */ }
```

Aceasta e documentată explicit ca o fixare deliberată împotriva unui bug de tip "cloud-ul învechit suprascrie local-ul mai nou" — o salvare cloud e aplicată doar dacă marcajul ei temporal e la sau după marcajul temporal al salvării locale.

## 10.2 Pierderea Contextului GL: Specific Android

Aceasta este exact povestea investigată și documentată pe larg în memoria `gl-context-loss-recovery`, iar cercetarea codului curent confirmă fiecare detaliu.

### Descoperirea: Recuperarea Automată Există Deja Pentru Majoritatea Texturilor

Investigația inițială a pornit de la presupunerea că jocul nu are deloc recuperare de pierdere de context, dar s-a dovedit mai nuanțat: **libGDX recuperează deja automat, gratuit, texturile susținute de fișier**. Orice `Texture` construit dintr-un `FileHandle` (acoperă cache-ul principal de sprite/tileset al lui `util.ResourceCache` — `imageCache`/`scaledImageCache`/`spriteAtlas`, plus paginile de glife FreeType `BitmapFont`) primește `FileTextureData` cu `managed=true` implicit, se înregistrează în `Texture.managedTextures`, iar `AndroidGraphics.onSurfaceCreated()` apelează `Texture.invalidateAllTextures()` → `reload()` → redecodează direct din `FileHandle`. Zero cod suplimentar necesar. La fel pentru FBO-urile (`lightFbo`/`occluderFbo`/`sceneFbo` din `GdxRenderer`) — libGDX recreează obiectele GL, iar pierderea conținutului nu contează aici pentru că toate trei sunt redesenate complet la fiecare cadru oricum.

### Bug-ul Real, Mai Îngust: Texturile Coapte Din Pixmap

```java
// gfx/GdxTextureUtil.java
public final class GdxTextureUtil {
    public static Texture managedFromPixmap(Pixmap pm) {
        return new Texture(new PixmapTextureData(pm, pm.getFormat(), false, false, true));
    }
}
```

`new Texture(Pixmap)` — calea "evidentă" de a construi o textură dintr-un pixmap generat procedural — codifică hardcodat `managed = false` (spre deosebire de calea bazată pe fișier), deci nu se înregistrează niciodată pentru reîncărcare automată. După o recreare de context Android (trecere în fundal, multitasking), aceste texturi ar deveni goale sau ar afișa date corupte. Au fost identificate **8 puncte de apel**: `gfx/FontSystem.java` (pixel alb), `gfx/GdxRenderer.java` (bake de remapare culoare hartă + `bakeRadialGradient`), `gfx/Sprite.java` (`croppedBottomAligned`), `entity/Projectile.java` (`rotateImage`), `tile/IT_Pot.java` (sprite procedural de oală), `ui/Minimap.java` (teren copt + vinietă), `mobile/TouchControlsOverlay.java` (textura albă a Skin-ului).

Soluția: constructorul `PixmapTextureData` cu 5 argumente, cu `managed=true` — înregistrează textura cu mecanismul de recuperare al libGDX în același mod în care o textură susținută de fișier se înregistrează automat. La fel de important, `Pixmap`-ul sursă e deliberat **nu eliberat (disposed)**:

> "Caveat (this is the trap to avoid if touching this again): `reload()` re-uploads from the *same* Pixmap object reference (no re-decode), so the source Pixmap must stay alive and must NOT be disposed — `disposePixmap=true` + `managed=true` together is an unguarded footgun in libGDX (reload() would touch freed native memory)."

Toate cele 8 puncte de apel și-au eliminat apelurile `pm.dispose()` corespunzător.

### Jumătatea Complementară: Reutilizarea de Proces Fără Reutilizare de Context

```java
// core/src/main/java/main/MichiGame.java — create()
// Must run first: on Android (singleTask launch mode) a "restart" can reuse the process,
// so ResourceCache's static image caches may still hold Sprites/Textures tied to the
// previous, now-dead GL context.
util.ResourceCache.resetImages();
```

Modul de lansare `singleTask` al Android poate reporni interfața jocului reutilizând același proces al sistemului de operare, ceea ce înseamnă că cache-urile statice supraviețuiesc chiar dacă contextul GL la care se referă e deja mort. `ResourceCache.resetImages()` șterge fiecare referință de imagine din cache **fără a apela `dispose()`** pe texturile de dedesubt — o omisiune intenționată, nu o scăpare:

> "Disposing them would just make GL calls against invalid/reused handles... a GL texture ID is just an integer scoped to a context; once that context is destroyed, calling `glDeleteTextures` on a stale ID either no-ops or, worse, deletes an unrelated texture that a fresh context has since recycled onto that same integer ID."

Un ID de textură GL e doar un întreg cu domeniu de valabilitate limitat la un context; odată ce acel context e distrus, apelarea `glDeleteTextures` pe un ID expirat fie nu face nimic, fie, mai rău, șterge o textură fără legătură pe care un context nou a reciclat-o de atunci pe același ID întreg. Împreună, cele două mecanisme reprezintă strategia completă: `PixmapTextureData` gestionat permite mecanismului de recuperare încorporat al libGDX să prindă ce poate, iar golirea defensivă a cache-ului la fiecare apel `create()` prinde tot restul (inclusiv cazul limită complet de reutilizare de proces) prin simpla renunțare la referințele expirate și lăsarea fiecărui sistem să-și reconstruiască leneș texturile din sursă data viitoare când are nevoie de ele.

---

# 11. Salvări în Cloud și Licențiere

## 11.1 `CloudSaveService` — Handshake Identic Cu Multiplayer-ul

`core/src/main/java/data/CloudSaveService.java` (1034 linii) încarcă `GameState`-ul către `SERVERS/save_server` folosind **exact același model de criptografie** ca și clientul de multiplayer (Secțiunea 13.1 are detaliul criptografic complet): handshake RSA-OAEP-SHA256, HKDF-SHA256 pentru derivarea cheilor (`LICENSE_PEPPER`, `DELIVERY_INFO = "michi-delivery-v2"`, `DELIVERY_AAD = "MichiCloudSession"`), AES-256-GCM pentru cadrele per-mesaj, cu contoare de secvență per-direcție înglobate în datele asociate AEAD — o structură `Session` identică ca formă cu cea din `MultiplayerClient`.

### Descoperirea Serverului și Reziliența Offline

Descoperirea serverului citește `save_servers.txt` (câte un `host[:port]` pe linie), revenind la o listă fixă hardcodată dacă fișierul lipsește sau e gol — același tipar de fișier/fallback ca în `LicenseActivation`.

```java
// core/src/main/java/data/CloudSaveService.java (rezumat)
// local_save.dat: magic('M','I','C','H')(4) | version(1)=2 | nonce(12) | ciphertext+tag
// key = HKDF-SHA256(license_or_anon, salt=machineFingerprint(), info="michi-local-v2", 32)
```

Dacă niciun server de salvare nu e accesibil, starea jocului e totuși criptată — folosind o cheie derivată din amprenta mașinii (`machineFingerprint()`: un hash SHA-256 peste numele de utilizator, hostname, și toate adresele MAC ne-loopback/ne-virtuale) — și pusă în cache local. Există chiar și o **cale de migrare de la formatul v1 legacy** — salvările vechi foloseau chei AES-256-CBC în text clar stocate în `local_aes.key`, decriptate și apoi șterse odată migrate la formatul v2.

### Heartbeat și Sincronizare Automată

Un thread de fundal pinguiește pool-ul de servere la fiecare 10 secunde, comută flag-ul `serverOnline`, sincronizează orice salvare locală în așteptare, și declanșează un callback `onReconnect` — exact callback-ul pe care `FriendsListManager` (Secțiunea 12.6) îl folosește pentru a-și drena propria coadă de cereri de prietenie amânate.

### Disciplina de Threading

Comentariile din cod sunt explicite: preluarea de rețea în sine e sigură de rulat în afara thread-ului de randare, dar aplicarea stării preluate trebuie să se întâmple pe thread-ul de randare, pentru că face asta reconstruiește entitățile vii și re-coace textura de minimap — ambele necesitând un context GL viu.

## 11.2 `LicenseActivation` — Activare Online, Partajată Între Desktop și Android

`core/src/main/java/platform/LicenseActivation.java` (485 linii) e complet partajată între backend-uri — "nu mai există nicio clasă de licență specifică per-platformă", conform propriei documentații, de când licențierea s-a mutat integral pe partea de server.

### ACTIVATE vs. LOGIN

Prima rulare apelează `ACTIVATE`, care emite o cheie de licență nou-nouță și o returnează în text clar exact o singură dată (împachetată AEAD în tranzit), plus un `activation_id` persistent + un `enc_blob` criptat AES-GCM al cheii (criptat cu o cheie păstrată doar pe server). **Doar** `activation_id` + `enc_blob` sunt vreodată scrise pe disc (`activation.dat`, format `Properties`) — cheia în text clar nu e niciodată persistată. Fiecare rulare ulterioară folosește `LOGIN` cu aceeași pereche; serverul decriptează blob-ul și confirmă fără a redivulga cheia (cu excepția cazului în care un server mai nou re-livrează și cheia într-un răspuns pe 5 părți, necesar pentru că `CloudSaveService` derivă propria cheie de sesiune din ea la fiecare rulare).

### Cutia Separată Pentru Token-ul itch.io — O Reparație de Depășire de Capacitate RSA

```java
// core/src/main/java/platform/LicenseActivation.java (comentariu, rezumat)
// RSA-2048 with OAEP-SHA256 can only carry 190 bytes of plaintext... A real itch token pushed
// the envelope past the limit, rsaOaepEncrypt() threw, and the buyer's ACTIVATE was never even sent.
```

Aceasta e o poveste excelentă de bug real, descoperit și reparat: plicul principal de handshake RSA-OAEP e limitat la aproximativ 190 de octeți de text clar de propriile limite de dimensiune ale RSA-2048, iar câmpurile de timestamp/nonce consumă deja o parte din acel buget. Un token OAuth real de la itch.io a împins plicul peste limită — `rsaOaepEncrypt()` arunca o excepție, iar `ACTIVATE`-ul cumpărătorului nu era nici măcar trimis, deci prima activare eșua silențios pentru orice jucător real. Soluția a fost mutarea token-ului itch într-o cutie AES-GCM complet separată, cheiată de o valoare pe care ambele părți o pot deriva independent din nonce-urile de handshake deja schimbate (`itchKey = hkdf(clientNonce + serverNonce, serverNonce, ITCH_TOKEN_INFO, 32)`) — vezi și Secțiunea 13.4 pentru fluxul complet de licențiere itch.io.

### Rezolvarea Unei Condiții de Cursă La Pornire

```java
// core/src/main/java/platform/LicenseActivation.java (comentariu, rezumat)
// SETTLED is a CountDownLatch(1); pressing CONTINUE before activation landed used to see
// verifyCurrent()==false, silently skip the cloud download, and load a stale local save instead.
```

O condiție de cursă reală: din moment ce activarea licenței rulează pe un thread de fundal (Secțiunea 1.2), un jucător suficient de rapid care apasă CONTINUE înainte ca activarea să se termine ar fi văzut `verifyCurrent()` returnând fals, ar fi sărit silențios peste descărcarea din cloud, și ar fi încărcat o salvare locală învechită în loc de cea corectă. Soluția a fost un `CountDownLatch(1)` explicit, care blochează exact cât e nevoie pentru ca prima rezoluție de activare să se termine, fără a bloca inutil restul pornirii aplicației.

### O a Doua Reparație: Gate-uirea Greșită Pe `cachedKey`

```java
// core/src/main/java/platform/LicenseActivation.java (comentariu, rezumat)
// verifyCurrent() deliberately does NOT require cachedKey != null, because only first-run
// ACTIVATE ever discloses the plaintext key. Gating on cachedKey != null here silently marked
// every returning player unlicensed and disabled their cloud saves.
```

O a doua poveste de bug, complementară primei: dat fiind că doar `ACTIVATE` (prima rulare) dezvăluie vreodată cheia în text clar către client, o verificare care ar fi cerut `cachedKey != null` pentru a considera licența validă ar fi marcat silențios **fiecare jucător care revine** (a doua rulare și toate cele ulterioare) ca nelicențiat — dezactivându-le salvările cloud fără nicio eroare vizibilă. Soluția a fost eliminarea acelei cerințe din `verifyCurrent()`.

Fluxul complet de licențiere — de la cumpărarea pe itch.io, prin OAuth, până la emiterea cheii de licență de către server — este documentat integral în Secțiunea 13.4, împreună cu principiul director "itch.io e ușa, nu proprietarul".

---

# 12. Multiplayer — Crearea și Funcționarea

## 12.1 Prezentare Generală a Arhitecturii

Echoes of the Heir oferă **două** căi de multiplayer complet distincte, cu modele de încredere radical diferite:

| | **TCP (server-autoritar)** | **BLE (peer local)** |
|---|---|---|
| Clasa client | `main.MultiplayerClient` (1557 linii) | `main.BleMultiplayerSession` (395 linii) |
| Server | `SERVERS/multiplayer_server` (Python, cu subproces Java) | Niciunul — un telefon găzduiește direct |
| Necesită internet | Da | Nu |
| Necesită licență/cont | Da | Nu |
| Criptare | RSA-OAEP + AES-256-GCM + HKDF | Fără |
| Autoritate de luptă | Server (prin `engine.jar`) | Telefonul gazdă |
| Date de hartă transmise | Da, în bucăți (chunk-uri) comprimate | Nu — doar identificatorul hărții |
| Platformă | Desktop + Android | Doar Android |

Ambele transporturi alimentează, în cele din urmă, **aceleași sisteme din aval** (Secțiunea 1.3): `GamePanel.syncRemotePlayerEntities()` reconstruiește o singură hartă unificată de jucători la distanță la fiecare tick, indiferent care transport e activ, cu namespace `"tcp:<id>"` sau `"ble:<id>"` pentru a preveni orice coliziune de ID între cele două spații de identificatori.

## 12.2 `MultiplayerClient` — Protocolul TCP Complet

### Modelul de Securitate

Documentația proprie a clasei prezintă suita de cifruri direct:

> "Handshake legat de licență RSA-OAEP-SHA256, criptare per-cadru AES-256-GCM, derivare de chei HKDF-SHA256, contoare de secvență per-direcție legate în datele asociate AEAD (anti-replay)."

O cheie publică RSA-2048 identică e înglobată static și în `CloudSaveService` și în `LicenseActivation` — un singur keypair partajat pentru toate cele trei handshake-uri criptografice ale clientului.

### Handshake-ul, Pas Cu Pas

```java
// core/src/main/java/main/MultiplayerClient.java — performHandshake(), simplificat
// 1. Client -> "HELLO v2 <base64(clientNonce_16)>"
// 2. Server -> "OK <base64(serverNonce)> [fingerprint_hex]"
//    fingerprint = sha256(DER SubjectPublicKeyInfo)[:8 octeți hex] — permite unei chei
//    client vechi/depășite să eșueze cu un mesaj clar, în loc de un LOGIN respins opac.
// 3. Client -> "LOGIN <base64(RSA-OAEP(handshakeJson))> <activationId> <encBlobB64>"
//    handshakeJson = {"ts":..., "client_nonce":"...", "server_nonce":"...", "name":"...", "class":"..."}
//    NICIUN câmp de licență în acel JSON — serverul rezolvă licența prin activation_id/enc_blob
// 4. Server -> "AUTH_OK <base64(encSessionKey)>"
//            | "BANNED" | "USERNAME_TAKEN" | "AUTH_FAIL" | "LICENSE_SERVER_UNAVAILABLE"
```

Un detaliu important, ușor de ratat la prima citire: mesajul JSON de handshake **nu conține deloc** cheia de licență. Autentificarea se bazează exclusiv pe `activation_id` + `enc_blob`, exact perechea emisă o singură dată de `LicenseActivation` (Secțiunea 11.2) — serverul de multiplayer rezolvă cheia de licență retransmițând acea pereche către portul intern al `save_server` (Secțiunea 13.1, 13.2), fără să dețină propria bază de date de licențe.

La primirea `AUTH_OK`, clientul derivă cheia de sesiune identic cu modelul din `save_server`/`CloudSaveService`:

```java
// core/src/main/java/main/MultiplayerClient.java
byte[] deliveryKey = hkdf(concat(license.getBytes(UTF_8), LICENSE_PEPPER), serverNonce, DELIVERY_INFO, 32);
byte[] nonceForDelivery = Arrays.copyOfRange(clientNonce, 0, 12);
byte[] sk = aesGcmDecrypt(encSession, deliveryKey, nonceForDelivery, DELIVERY_AAD);
```

`LICENSE_PEPPER = "michi-license-pepper-v2"`, `DELIVERY_INFO = "michi-delivery-v2"`, `DELIVERY_AAD = "MichiMpSession"`.

### Formatul Cadrelor `DATA` și Anti-Redare

```java
// core/src/main/java/main/MultiplayerClient.java — sendEncrypted/recvEncrypted, simplificat
byte[] seqBytes = longBE(seq);              // 8 octeți, big-endian
byte[] aad = { DIR_C2S, seqBytes[0..7] };   // 9 octeți: octet de direcție + secvență
byte[] ct = aesGcmEncrypt(plaintext, sessionKey, nonce /* 12 octeți aleatori */, aad);
byte[] frame = concat(seqBytes, nonce, ct);
out.write("DATA " + Base64.encode(frame));
```

La primire, `recvSeq` (un `AtomicLong` care pornește de la 0) e comparat prin `getAndIncrement()` și trebuie să fie **exact egal** cu secvența înglobată în cadru — orice gol sau redare aruncă o eroare `"server seq mismatch"` și închide conexiunea. `DIR_S2C = 0x01`, `DIR_C2S = 0x02` — direcția e legată în datele asociate AEAD tocmai pentru ca un cadru capturat pe o direcție să nu poată fi niciodată redat înapoi pe direcția opusă.

### Scalarea de Coordonate: Server vs. Client

Un detaliu practic important: serverul lucrează cu `originalTileSize` (32px), în timp ce clientul randează la `gp.tileSize` (64px la scala implicită 2.0). Fiecare câmp de poziție care traversează firul e împărțit/înmulțit cu `coordScale = gp.tileSize / gp.originalTileSize` — o singură constantă de conversie aplicată consecvent la fiecare graniță de protocol.

### Toate Tipurile de Mesaje

**Trimise de client**: `move`, `chat`, `chunk_request`, `world_ready`, `progress_sync`, `ping`, `mob_damage`, `mob_death`, `npc_interact`, `npc_leave`, `shop_buy`, `shop_sell`, `level_choice`, `skill_unlock`.

**Primite/gestionate de client**: `welcome`, `world_info`, `chunk`, `pos_correction`, `trigger`, `map_change`, `player_join`, `player_leave`, `player_update`, `server_full`, `chat`, `chat_throttled`, `pong`, `kick`, `player_stats`, `mob_damage`, `mob_death`, `npc_spawn`, `npc_dialogue`, `npc_shop`, `shop_result`, `skill_result`, `skills_state`.

## 12.3 Interpolare Client-Side: Spline Hermite, Nu Predicție

O distincție de precizie importantă: acest sistem face **interpolare de entități la distanță**, nu predicție client-side locală cu reconciliere de server. Jucătorul local se mișcă instant ca răspuns la input; serverul intervine doar cu un mesaj `pos_correction` dacă nu e de acord cu poziția raportată de client (anti-cheat, nu compensare de lag). Pentru avatarele **altor** jucători, clientul trebuie să transforme instantanee periodice de poziție (la fiecare ~50ms) în mișcare continuă și lină.

```java
// core/src/main/java/main/MultiplayerClient.java — RemotePlayerState.evalSpline
public float[] evalSpline(long nowNs) {
    if (!spReady) return new float[]{ worldX, worldY };
    long elapsed = nowNs - spStartNs;
    float t = spDurationNs > 0 ? (float) elapsed / spDurationNs : 1f;
    if (t > 1.3f) t = 1.3f;   // permite un mic overshoot în loc de un snap dur
    float vsX = spVsX * spDurationNs, vsY = spVsY * spDurationNs;
    float veX = spVeX * spDurationNs, veY = spVeY * spDurationNs;
    float t2 = t*t, t3 = t2*t;
    float h00 = 2*t3 - 3*t2 + 1, h10 = t3 - 2*t2 + t, h01 = -2*t3 + 3*t2, h11 = t3 - t2;
    return new float[]{ h00*spPsX + h10*vsX + h01*spPeX + h11*veX,
                         h00*spPsY + h10*vsY + h01*spPeY + h11*veY };
}
```

Fiecare nouă actualizare de poziție devine punctul final al unui segment de **spline Hermite** cubic. Detaliul critic, deja documentat conceptual în versiunea în engleză a acestui document, se confirmă exact în cod: viteza de **pornire** a noului segment nu e resetată la zero, ci e eșantionată din locul unde se află **curba de viteză a segmentului anterior** exact la momentul actual (via `hermiteVelocity`/`hermiteVelocityY`, care implementează derivata exactă `dP/dt` a formulei Hermite). Aceasta face interpolarea continuă la nivel de derivată întâi (C1): poziția și viteza rămân ambele continue peste granițele segmentelor. Fără această potrivire de viteză de ieșire, fiecare nouă actualizare ar produce o "smucitură" vizibilă în mișcarea unui jucător la distanță, exact la momentul primirii fiecărui pachet nou.

Durata segmentului e fixată la `1_000_000_000L / 20` nanosecunde (50ms), potrivindu-se exact cu intervalul de broadcast al serverului.

## 12.4 Autoritatea de Server: Povestea Migrării `server-authority`

### Istoricul Git

Un merge explicit marchează finalizarea acestei migrări arhitecturale majore: `0d4354eb Merge branch 'server-authority'`. Investigarea istoricului git developing arată o secvență de commit-uri, toate din aceeași zi (2026-07-15), fiecare narând un pas distinct al migrării de încredere dinspre client spre server:

**`17dc3c7d` — "Run the game simulation headlessly, so the server can host it"** — commit-ul fundațional. În loc să despartă baza de cod sau să porteze regulile jocului în Python, aceleași clase client rulează acum fără randare. Din mesajul commit-ului: "the simulation never reads a pixel. The only thing it asks of a sprite is its width and height." Acest commit a introdus exact mecanismul descris în Secțiunea 1.3 (`Headless`, `HeadlessGame`).

**`282efa9f` — "Make skill unlocks server-authoritative; credit kill XP server-side"** — "Skills now work the way gold already does: the client asks, the server decides." A introdus `skilltree.py`, care re-validează pe server fiecare regulă pe care `SkillTree.canUnlock` (Secțiunea 9.2) o impune deja pe client, împotriva stării deținute de server. XP-ul e creditat server-side din definițiile de monștri partajate, dar — notabil — "matematica de nivelare rămâne client-side deocamdată — aceasta e autoritate de XP, nu încă autoritate de nivel" (prevestind commit-ul următor).

**`18c7e825` — "Move level-up authority to the server so XP, gold and skill points only change there"** — commit-ul care încheie arcul narativ. Comentariul de cod pe care acest commit l-a introdus e citat direct în Secțiunea 12.4 de mai jos (server.py). Funcția `credit_kill()` a serverului rulează acum exact bucla de nivelare portată din `Player.checkLevelUp` (Secțiunea 4.2): `level++`, `nextLevelExp = 4 + level*3`, +1 punct de abilitate, +2 vindecare, punând alegerile de statistică în așteptare (`pending_level_choices`). Statistica "viteză" a fost eliminată din grupul de opțiuni oferite în multiplayer — exact fixarea deja documentată în Secțiunea 4.2.

**`4df054f3` — "server-over-bluetooth is now a feature, not a dream anymore."** (2026-07-10, mai devreme) — commit-ul de finalizare a funcționalității BLE, cu un mesaj de commit remarcabil de informal: "HOW IT WORKS: you have to be sure you turned NFC and bluetooth on. press pause -> host. on the other phones: join game and wait for bluetooth discovery." Este cel mai mare commit din întreaga secvență (50 de fișiere, +3289/-73 linii) — a introdus pachetele Android `ble`/`nfc` complete, `BleMultiplayerSession`, `FriendsListManager`, clasele de payload NFC, și clasa `RemotePlayerEntity`.

### Principiul Director, Direct Din Comentariul Sursă

```python
# SERVERS/multiplayer_server/server.py
# ── authoritative economy/progression ────────────────────────────────────
# XP, coins, level and skill points change HERE and nowhere else. The client is told the
# result via player_stats; it never gets to set these itself. A client that fakes any of them
# only fakes its own screen — the next player_stats overwrites the lie with the server's truth.
```

Ultima propoziție a acestui comentariu este perspectiva cheie pentru a înțelege *de ce* funcționează acest design: serverul nu trebuie să detecteze sau să blocheze un client trișor în timp real — trebuie doar să nu aibă niciodată încredere în starea raportată de client ca adevăr de bază. Un client modificat se poate minți pe sine cât vrea; chiar următorul broadcast autoritar suprascrie silențios minciuna, deci trișatul nu are niciun efect persistent și niciun avantaj față de ceilalți jucători.

### Anti-Cheat de Mișcare

```python
# SERVERS/multiplayer_server/server.py — _handle_move(), simplificat
max_step_px = max_tile_step_per_move * world.tilewidth
new_x = clamp(msg["x"], 0, world_width_px - 1)
dx = new_x - player.last_valid_x
if abs(dx) > max_step_px:
    new_x = player.last_valid_x + clamp(dx, -max_step_px, max_step_px)
if not world.is_box_walkable(new_x, new_y, player.hitbox):
    new_x, new_y = player.last_valid_x, player.last_valid_y
    send(player, "pos_correction", x=new_x, y=new_y)
```

Poziția e limitată la margini, distanța de mișcare per tick e plafonată, iar fiecare mișcare e verificată împotriva unui oracol de coliziune server-side construit din aceleași date de hartă TMX pe care clientul le folosește. Un client care raportează un salt imposibil sau încearcă să meargă printr-un zid e pur și simplu tras înapoi în tăcere — serverul nu trebuie să "detecteze trișarea" ca un caz special, pentru că mișcările ilegale pur și simplu nu sunt niciodată acceptate ca poziția autoritară în primul rând.

### Autoritatea de Luptă: Puntea `engine.jar`

```python
# SERVERS/multiplayer_server/server.py — _handle_mob_damage(), simplificat
ruling = await self.engine.mob_hit(mob_id, mob_type, damage, player_id)
if ruling is not None and "life" in ruling:
    life = clamp(ruling["life"], 0, 9999)          # numărul motorului suprascrie pretenția clientului
if ruling is not None and ruling.get("killed"):
    self._finish_mob_death(client, mob, ruling)
```

Și, explicit, un client nu poate declara unilateral o ucidere:

```python
# SERVERS/multiplayer_server/server.py — _handle_mob_death(), simplificat
if self.engine.available and mob.alive:
    log.debug("Ignoring unverified mob_death claim...")
    return
```

Dacă motorul rulează și încă consideră mob-ul viu, mesajul `mob_death` al unui client e pur și simplu logat și aruncat — singurul mod în care un mob moare de fapt e ca decizia motorului însuși să o spună.

## 12.5 Cum Reutilizează Java-ul de Bază Modul Headless: `EngineServer`

### De Ce Un Subproces Java

Întregul rost al faptului că `core` poate fi construit pe baza `gdx-backend-headless` (Secțiunea 15) este realizat aici: `engine.jar` (construit de task-ul Gradle `engineJar`, care produce fișierul direct în `SERVERS/multiplayer_server/engine.jar`, cu clasa principală `server.EngineServer`) rulează simularea reală `GamePanel` fără fereastră și fără GPU, ceea ce înseamnă că matematica de luptă (formule de daune, reacții de IA a mob-ilor, recompense XP) nu trebuie niciodată reimplementată în Python și menținută sincronizată manual cu versiunea Java.

```groovy
// core/build.gradle
// ── Authoritative gameplay engine as a single runnable jar. ──
// This is the "gameplay" server the Python multiplayer server launches as a child process and
// talks to over stdin/stdout (see server.EngineServer and SERVERS/multiplayer_server/game_engine.py).
tasks.register('engineJar', Jar) {
    archiveBaseName = 'engine'
    destinationDirectory = rootProject.file('SERVERS/multiplayer_server')
    from sourceSets.main.output
    manifest { attributes 'Main-Class': 'server.EngineServer' }
}
```

### `server.EngineServer` — Podul Java

`core/src/main/java/server/EngineServer.java` (205 linii) rulează ca un **proces copil** al serverului Python de multiplayer, comunicând prin stdin/stdout ca JSON delimitat de linii, un obiect per linie. Python deține rețeaua/criptografia/interdicțiile/consola de admin/streaming-ul de hartă; acest proces Java deține doar **regulile de luptă**, rulând "aceleași clase Java pe care clientul le rulează" (via `HeadlessGame`, Secțiunea 1.3) — "O singură carte de reguli, astfel încât serverul și clientul nu pot niciodată să nu fie de acord asupra unui rezultat."

**Protocolul exact**:

```
{"cmd":"ping","rid":N} → {"event":"pong","rid":N}
{"cmd":"mob_hit","rid":N,"mobId":M,"mobType":"slime","damage":D,"pid":P}
  → {"event":"mob_state","rid":N,"mobId":M,"life":L,"maxLife":ML,"alive":b,"dying":b,
     "applied":A,"killed":b,"exp":X,"pid":P}
{"cmd":"reset_mobs","rid":N} → {"event":"ok","rid":N}
Boot: {"event":"ready","tps":T,"map":"...","monsters":N}
```

Un detaliu de implementare demn de remarcat, care previne o clasă întreagă de bug-uri de coruperea protocolului:

```java
// core/src/main/java/server/EngineServer.java
PrintStream protocolOut = System.out;
System.setOut(System.err);
```

Această linie apucă adevăratul stdout pentru canalul de protocol **înainte** ca orice altceva să poată scrie în el, apoi redirecționează `System.out` global către stderr — astfel încât apeluri `println` rătăcite în codul de joc (există multe, moștenite din dezvoltarea single-player) nu pot corupe niciodată fluxul JSON pe care Python îl parsează. `emit()` e `synchronized` și golește (flush) fiecare linie; `log()` merge întotdeauna către `System.err`.

**Puntea Python** (`multiplayer_server/game_engine.py`):

```python
# SERVERS/multiplayer_server/game_engine.py — simplificat
class GameEngine:
    async def launch(self):
        self.proc = await asyncio.create_subprocess_exec(
            "java", "-jar", str(jar_path),
            stdin=PIPE, stdout=PIPE, stderr=PIPE,
        )
        # așteaptă până la 30s o linie {"event": "ready"} pe stdout

    async def mob_hit(self, mob_id, mob_type, damage, player_id):
        # trimite {"rid": N, "cmd": "mob_hit", ...} ca o linie de JSON pe stdin
        # așteaptă răspunsul corespunzător {"rid": N, ...} pe stdout, până la 2.0s
        # returnează None la timeout, blocare, sau jar lipsă — nu ridică niciodată excepție
```

Proprietatea critică de design este că **motorul e un upgrade de autoritate, niciodată o dependență obligatorie**: dacă `engine.jar` lipsește, Java nu e instalat, sau un apel expiră, `self.engine.available` devine `False`, iar serverul revine la a avea încredere în propriile pretenții de daune ale clientului, în loc să refuze să ruleze. O instalare Java defectă sau absentă degradează securitatea multiplayer, dar nu doboară niciodată serverul.

### `AuthoritativeMob` — Oglinda de Stare de Luptă a Serverului

`core/src/main/java/server/AuthoritativeMob.java` (77 linii) este mirror-ul propriu al serverului pentru starea de luptă a fiecărui mob, indexat după id. Trage `maxLife`/`defense`/`exp` din exact același `monsters.json` din care clientul randează (via `MonsterFactory.defStat`). `DAMAGE_CAP = 999` e un plafon dur pe o singură lovitură raportată — "purely to stop a client claiming a kill-in-one-hit". `applyHit()` scade apărarea identic cu formula proprie a clientului `Player` (`dmg = Math.max(1, dmg - defense)`).

### `Json` — Un Parser Minimal, Dedicat

`core/src/main/java/server/Json.java` (108 linii) e un parser JSON scris manual, deliberat minimal, folosit exclusiv pentru legătura motor↔părinte (nicio dependență de bibliotecă în jar-ul de server). Potrivește chei doar pe token-ul literal `"cheie":`, evitând o clasă de bug de coliziune de substring de care propriul cititor JSON al clientului e vulnerabil — "the same reason the wire protocol uses unambiguous camelCase ids like `mobId`".

### Streaming de Lume: `MpMapStreamer`

`core/src/main/java/main/MpMapStreamer.java` (421 linii) implementează un protocol pe două etape:

1. **`world_info`** — un plic JSON mic (dimensiuni, `chunk_size`, `chunks_x/y`, nume de straturi, spawn implicit, și un **TMX "schelet"** codificat base64 — TMX-ul original cu fiecare bloc `<data>` golit). Scheletul e preîncărcat în `ResourceCache` sub o cale virtuală `/res/maps/{mapId}.tmx`, astfel încât codul **existent** de `TileManager`/`MapObjectLoader` de single-player să încarce harta neschimbat.
2. **Mesajele `chunk`** — pentru fiecare `(indexStrat, cx, cy)`, blob-uri GID uint32 little-endian comprimate gzip (flag-urile de flip Tiled sunt păstrate în cei mai semnificativi 3 biți, `GID_MASK = 0x1FFFFFFFL`). Decodate și "petecuite" direct în `TileManager.mapLayers`/`mapFlipLayers` in-place — "Fără reîncărcare, fără uzură de alocare."

**Prioritizarea cererilor de chunk-uri** calculează distanța Chebyshev de la chunk-ul de spawn și cere fiecare chunk sortat de la cel mai apropiat — serverul impune un plafon per tick, deci clientul poate trimite toată coada dintr-o dată în siguranță.

**Disciplina de thread GL**: `applyWorldInfo` și `finishWorldLoad` sunt amânate explicit prin `Gdx.app.postRunnable`, pentru că încarcă texturi de tileset / coc minimap-ul / construiesc entități cu pixmap-uri susținute de GPU — comentariile din cod narează asta ca o clasă de bug real fixată ("ar fi altfel abortat JVM-ul ('No context is current') în momentul în care o hartă e streamuită").

**Reconcilierea cu TMX local**: pentru că `finishWorldLoad` reutilizează exact pipeline-ul de încărcare de hartă single-player (`gp.mapManager.registerMap` + `changeMap`), tileset-urile se rezolvă tot din JAR-ul/assets-urile locale — doar **plasarea** tile-urilor vine de la server. Aceasta e și motivul pentru care clientul TCP nu are nevoie de fișiere de hartă identice byte-cu-byte preinstalate: serverul e sursa unică de adevăr pentru datele de hartă peste multiplayer TCP.

## 12.6 NPC-uri Găzduite Pe Server

O secțiune de comentarii ampli în `MultiplayerClient.java` documentează arhitectura: în multiplayer, NPC-urile trăiesc **complet** pe server (`SERVERS/multiplayer_server/npc.py`); clientul e "un randerizator" — generează instanțe `NPC_Generic` doar de prezentare, trimite intenții (`npc_interact`, `shop_buy`, etc.), și afișează orice îi returnează serverul. Nu decide niciodată singur dialogul sau prețurile.

Deblocările de abilități și interacțiunile NPC/magazin urmează același tipar: datele de cost și prerechizite locuiesc într-un catalog JSON server-side; starea de dialog NPC, stocul de magazin, și aurul sunt toate păstrate server-side — un client modificat care își editează copia locală de date nu poate să-și acorde obiecte gratuite sau să deblocheze dialog pe care nu l-a câștigat.

## 12.7 `BleMultiplayerSession` — Multiplayer Local Prin Bluetooth Low Energy

`core/src/main/java/main/BleMultiplayerSession.java` (395 linii) e cadrat explicit, în documentația proprie a clasei, ca modul "invită un prieten fără licență" — deliberat separat și ușor, fără internet, fără cont, fără licență, fără criptare: "a same-room BLE link between two phones you just tapped together doesn't have the threat model a public TCP server does."

### Diferențe Cheie Față de Calea TCP

- **Fără date de hartă transmise deloc.** `hostMapId` doar numește un fișier de hartă deja prezent local pe ambele telefoane — contrastați cu calea TCP, care streamuiește date de hartă în bucăți, pentru că serverul nu poate presupune că doi jucători arbitrari au assets identice byte-cu-byte.
- **Format de transmisie text compact, separat prin bară verticală**, nu JSON — unitatea maximă de transmisie a Bluetooth LE e minusculă, deci verbozitatea are un cost real.
- **Dispozitivul gazdă e autoritar** pentru HP/moarte de mob în cadrul unei sesiuni, retransmițând fiecare lovitură aplicată tuturor oaspeților.

### Formatul Exact al Firului

```
gazdă -> oaspete:  W|id|mapId|col|row                 (bun venit)
                   J|id|name                          (oaspete alăturat)
                   L|id                                (oaspete plecat)
                   U|id|x|y|dir|sprite|atk|life|maxLife
                   D|mobId|life|maxLife|dmg
                   K|mobId
                   F|reason
oaspete -> gazdă:  H|token|name
                   U|x|y|dir|sprite|atk|life|maxLife   (fără id la început — conexiunea ÎNSĂȘI e slotul)
                   D|mobId|dmg
                   K|mobId
```

### Roluri: Gazdă vs. Oaspete

`localId = 0` e rezervat gazdei; sloturile de oaspete (`1..MAX_GUESTS`, `MAX_GUESTS = 5`) sunt asignate de gazdă la handshake. Gazda validează `sessionToken`-ul și capacitatea lobby-ului, răspunzând cu `F|bad_token` sau `F|lobby_full` la respingere.

### Handoff-ul NFC

O atingere NFC între cele două telefoane transportă token-ul de sesiune al gazdei și ID-ul hărții către oaspete; abia atunci BLE preia controlul efectiv. Acceptarea e dovedită doar prin sosirea liniei `W` (bun venit) a gazdei, nu doar prin callback-ul "canal gata de scriere" — o distincție documentată explicit în cod, pentru că un canal GATT gata de scriere nu garantează că gazda a acceptat efectiv handshake-ul.

### Relei de Daune Autoritare Ale Gazdei

```java
// core/src/main/java/main/BleMultiplayerSession.java (rezumat)
// "Host relays every applied hit to all guests so they converge on the same HP."
if (fromSlot == 0 && hosting) return; // gazda sare peste re-aplicarea propriei lovituri deja aplicate
```

Fiecare lovitură aplicată (fie de la jucătorul propriu al gazdei, fie retransmisă de la un oaspete) e ecoată înapoi către **toți** oaspeții prin `D|mobId|life|maxLife|dmg`, astfel încât toți clienții converg pe aceeași valoare de HP — fără un server central, dar cu o singură sursă de adevăr locală (gazda).

### Spline Hermite Reutilizat, Cu Tangente Zero

```java
// core/src/main/java/main/BleMultiplayerSession.java — applyUpdate, simplificat
rp.spVsX = 0; rp.spVsY = 0; rp.spVeX = 0; rp.spVeY = 0;   // tangente zero — degradează la lerp
rp.spStartNs = now;
rp.spDurationNs = 150_000_000L; // ~150ms, potrivit cadenței mai grosiere de actualizare BLE
```

Aceeași tehnică de interpolare spline Hermite din calea TCP e reutilizată, dar cu tangente zero (viteze de intrare/ieșire ambele zero) — ceea ce degradează matematic curba la o simplă interpolare liniară, potrivit dat fiind rata mai grosieră de actualizare BLE (150ms segment, față de 50ms pe TCP).

## 12.8 Povestea Bug-ului: Vizibilitatea Jucătorilor BLE Blocată De O Verificare TCP

Aceasta este exact investigația documentată în memoria `ble-multiplayer-visibility`, verificată pe hardware real (un Galaxy S25 Ultra ca gazdă, un A37 ca oaspete).

**Simptomul**: multiplayer-ul local BLE se conecta perfect (meniul de pauză arăta corect "2/5" jucători), dar nici gazda, nici oaspetele nu puteau vedea celălalt jucător mișcându-se sau apărând — fiecare vedea doar propria lui persoană.

**Investigația a confirmat că întreaga cale de date BLE era corectă**: `BleMultiplayerSession` își popula corect propria hartă `remotePlayers` (gazda prin `handleGuestUpdate`, oaspetele prin cazul `U`), `GamePanel.update()` apela `bleSession.update()`, iar `GamePanel.drawRemotePlayers(g2)` itera deja **ambele** hărți — `mpClient.remotePlayers` ȘI `bleSession.remotePlayers`.

**Cauza reală**: singurul apelant al lui `drawRemotePlayers` — în `ui/RenderPipeline.java` — era condiționat exclusiv de `gp.mpClient != null && gp.mpClient.isConnected()`. Acesta e clientul TCP **legat de licență**; o sesiune BLE-only "invită jucător" nu îl conectează niciodată, deci apelul de desenare era sărit complet, iar fiecare jucător la distanță prin BLE devenea invizibil.

**Fixarea aplicată**: lărgirea gate-ului la `tcpConnected || bleActive` (`bleActive = gp.bleSession != null && gp.bleSession.isActive()`) — sigură, pentru că `drawRemotePlayers` gardează deja fiecare sursă independent, deci sursa goală/inactivă nu contribuie cu nimic. Codul exact al fixării e citat în Secțiunea 6.3.

**Lecția metodologică**: bug-ul avea o cauză pur de randare, nu de transport de date — o distincție importantă pentru orice viitoare depanare de multiplayer în acest joc: verificați întotdeauna dacă datele ajung corect (aici, da) înainte de a presupune că problema e în protocol; uneori bug-ul e pur și simplu într-un `if` de randare care nu a fost actualizat să reflecte o a doua cale de transport nou-adăugată.

---

# 13. Serverele Backend (Python)

Toate cele trei servere locuiesc sub `SERVERS/` și sunt procese Python independente care partajează o singură imagine Docker, desfășurate împreună printr-un singur `docker-compose.yml`. Toate se bazează masiv pe biblioteca standard — singura dependență terță pe toate cele trei este biblioteca `cryptography`:

```
# SERVERS/requirements.txt
cryptography>=42,<46
```

## Prezentare Generală a Porturilor și Desfășurării

| Port | Serviciu | Protocol | Expus public? |
|---|---|---|---|
| 5005 | save_server | TCP, protocol personalizat RSA+AES-GCM | Da |
| 5105 | API intern de licențe save_server | TCP, protejat prin secret partajat | Nu — doar intern Docker |
| 5006 | patch_server | TCP, protocol text/binar personalizat | Da |
| 7777 | multiplayer_server | TCP (asyncio), protocol personalizat RSA+AES-GCM | Da |
| 8888 | dashboard admin multiplayer_server | HTTP | Nu — doar loopback, accesat prin tunel SSH |

```yaml
# SERVERS/docker-compose.yml (structură)
networks: { michi: {} }
services:
  save:         { ports: ["5005:5005"] }               # 5105 intenționat NEpublicat
  patch:        { ports: ["5006:5006"] }
  multiplayer:  { ports: ["7777:7777", "127.0.0.1:8888:8888"], depends_on: [save] }
```

Serverul de multiplayer ajunge la serverul de salvare prin numele său de serviciu Docker Compose (`save`), niciodată `127.0.0.1` — acest lucru permite ca portul intern de verificare a licenței să rămână nepublicat către lumea exterioară, fiind totuși accesibil de la un container la altul.

## 13.1 `save_server`: Salvări în Cloud și Emiterea de Licențe

**Runtime**: Python 3, `socket` simplu + `sqlite3` + `threading` (fără niciun framework web deloc — nu este Flask sau FastAPI; sunt două servere TCP brute scrise manual, într-un singur proces). Punctul de intrare este `server.py` (~1.555 linii).

### Pornire și Două Socket-uri de Ascultare

```python
# save_server/server.py — serve_forever(), simplificat
def serve_forever():
    cfg = load_config()
    init_db()                          # SQLite, mod WAL
    load_private_key()
    threading.Thread(target=serve_internal, daemon=True).start()  # port 5105
    # bucla principală de accept pe cfg["port"] (5005)
```

Două socket-uri, două audiențe:
- **Portul 5005 (public)** — clienți de joc: `PING`, handshake-ul `HELLO`/`ACTIVATE`/`LOGIN`, apoi cadre criptate de încărcare/descărcare a salvărilor.
- **Portul 5105 (doar intern, niciodată publicat în Docker Compose)** — un mic API folosit exclusiv de `multiplayer_server`: `CLAIM_USERNAME`, `CHECK_USERNAME`, `VERIFY_ACTIVATION`, `PING_LINK`. Ca măsură de apărare în profunzime, portul public refuză explicit orice linie care începe cu `"INTERNAL "`.

Fiecare conexiune acceptată rulează pe propriul thread, controlată de un `threading.BoundedSemaphore` (limită de concurență) și un limitator de rată de tip token-bucket per-IP.

### Criptografia, În Detaliu

**Algoritmi folosiți:** RSA-2048 cu padding OAEP (SHA-256) pentru plicul de handshake; HKDF-SHA256 pentru derivarea cheilor simetrice din secrete partajate; AES-256-GCM (un cifru AEAD) atât pentru livrarea cheii de sesiune cât și pentru tot traficul ulterior.

De ce această combinație, conceptual: RSA e lent și limitat ca dimensiune, dar nu necesită un secret pre-partajat — perfect pentru un handshake unic între două părți care nu au mai comunicat niciodată. AES-GCM e rapid și nelimitat ca dimensiune, dar are nevoie de o cheie partajată — perfect pentru masa sesiunii odată ce o cheie există. HKDF e modul standard de a transforma "niște material secret partajat" într-o cheie AES corect dimensionată, criptografic independentă. Aceasta e aceeași formă generală ca TLS, scrisă manual la o scară mult mai mică pentru că cele două capete (acest client specific, acest server specific) sunt cunoscute dinainte.

**Prima rulare vreodată (fără credențiale locale încă) — `ACTIVATE`:**

```
C -> "HELLO v2 <base64(client_nonce_16)>"
S -> "OK <base64(server_nonce_16)>"
C -> "ACTIVATE <base64(rsa_oaep_sha256(handshake_json))>"
S -> "AUTH_OK <base64(aesgcm(session_key,...))> <activation_id>
      <base64(nonce||aesgcm(license_key, key=enc_key, aad='MichiLicenseBlob'))>
      <base64(aesgcm(license_key, key=delivery_key, aad='MichiIssuedLicense'))>"
   | "AUTH_FAIL"
```

**Fiecare rulare ulterioară — `LOGIN`:**

```
C -> "HELLO v2 <base64(client_nonce_16)>"
S -> "OK <base64(server_nonce_16)>"
C -> "LOGIN <base64(rsa_oaep_sha256(handshake_json))> <activation_id> <base64(enc_blob)>"
S -> "AUTH_OK <base64(aesgcm(session_key, key=delivery_key, ...))> ..."
```

**Pasul de derivare a cheii care se întâmplă la fiecare `ACTIVATE`/`LOGIN` reușit:**

```python
# save_server/server.py — simplificat
delivery_key = hkdf(
    secret=license_key.encode("utf-8") + b"michi-license-pepper-v2",
    salt=server_nonce, info=b"michi-delivery-v2", length=32,
)
session_key = os.urandom(32)
enc_session = aesgcm_encrypt(session_key, key=delivery_key, nonce=client_nonce[:12], aad=b"MichiCloudSession")
```

Observați că `delivery_key` e derivat **din cheia de licență în sine**, sărat cu un șir "pepper" fix și nonce-ul per-handshake al serverului. Acest lucru e deliberat și elegant: serverul nu trebuie niciodată să stocheze o cheie de sesiune undeva, și — mai important — un client care nu deține de fapt o cheie de licență validă nu poate deriva `delivery_key`-ul corect, deci nu-și poate decripta propria cheie de sesiune chiar dacă a capturat cumva răspunsul `AUTH_OK`.

### Schema Bazei de Date

```sql
saves(license_key PK, save_data BLOB, game_timestamp, size_bytes, updated_at)
licenses(license_key PK, activation_id UNIQUE, enc_key_b64, created_at, itch_user_id)
events(id, ts, client_ip, license_key, status)
usernames(username PK, license_key UNIQUE, claimed_at, friend_id UNIQUE)
friends(license_key_a, license_key_b, requester_key, status, created_at)
```

`licenses.itch_user_id` poartă un index unic parțial (`WHERE itch_user_id IS NOT NULL`), care impune "cel mult o licență per cumpărător itch.io" chiar și sub o condiție de cursă unde două încercări de activare pentru același cont itch sosesc aproape simultan.

### Protecții Împotriva Redării și Falsificării

- `NonceCache` (TTL de 5 minute) respinge orice nonce de client deja văzut.
- Marcajul temporal al handshake-ului trebuie să fie în ±60 de secunde de ceasul serverului.
- Toate comparațiile de nonce/cheie API folosesc `hmac.compare_digest` (comparație în timp constant), eliminând un canal lateral de timing.
- Cadrele de sesiune poartă un contor de secvență strict monoton înglobat în AAD.

### Rezolvarea Conflictelor de Salvare

```python
# save_server/server.py — simplificat
if server_save.timestamp > incoming_save.timestamp:
    reply({"status": "SYNC", "data": server_save})
else:
    store(incoming_save)
```

Câștigă ultima scriere, după un marcaj temporal înglobat în datele de salvare în sine — nu momentul de sosire al încărcării.

## 13.2 `multiplayer_server`: Multiplayer în Timp Real Cu Autoritate de Server

**Runtime**: Python 3 `asyncio` (spre deosebire de modelul thread-per-conexiune al celorlalte două servere), pentru că serverul de multiplayer trebuie să ruleze o buclă continuă de tick de joc alături de potențial multe conexiuni simultane. Detaliile complete despre motivul subprocesului Java (`engine.jar`) și fluxul autorității de server sunt tratate integral în Secțiunea 12.4–12.5.

### Handshake-ul Către Licențe

```python
# multiplayer_server/server.py — simplificat
license_key = await self._save_server_verify_activation(activation_id, enc_blob)
if license_key is None:
    send("LICENSE_SERVER_UNAVAILABLE")   # distinct de AUTH_FAIL — pană de infrastructură, nu licență proastă
    return
```

Retransmite `(activation_id, enc_blob)` către portul intern al `save_server` (5105) și are încredere în orice `license_key` vine înapoi — serverul de multiplayer nu deține propria bază de date de licențe. Distincția dintre `LICENSE_SERVER_UNAVAILABLE` și `AUTH_FAIL` contează operațional: un jucător a cărui autentificare eșuează pentru că serverul de salvare e scurt timp indisponibil ar trebui să vadă "încearcă din nou în curând", nu "licența ta pare invalidă".

### Streaming de Lume Server-Side

`world.py` parsează același format de hartă Tiled `.tmx` pe care clientul îl folosește (prin `xml.etree.ElementTree` din biblioteca standard Python), împarte fiecare strat de tile-uri în bucăți comprimate gzip codificate base64, transmise clienților la cerere (Secțiunea 12.5), și construiește oracolul de accesibilitate folosit pentru anti-cheat-ul de mișcare.

### Persistență

Fără bază de date SQL proprie — statisticile și progresul jucătorilor sunt persistate într-un `player_data.json` plat, indexat după cheia de licență. `saves.db` (baza de date a save_server) e montat **doar-citire** pur pentru ca dashboard-ul admin să poată căuta nume de utilizator. Interdicțiile și starea de conexiune live sunt doar în memorie și se pierd la o repornire de server — un compromis deliberat de simplitate.

## 13.3 `patch_server`: Actualizări cu Diff Binar Semnat

**Runtime**: Python 3, thread-per-conexiune. Punctul de intrare `server.py` (~318 linii).

### Protocol

```
PING\n                    -> PONG\n
CHECK <current_version>\n -> UPTODATE\n
                              | UPDATE <to_version> <size_bytes> <sha256_hex> <sig_b64>\n
FETCH <from_version>\n    -> [dimensiune pe 8 octeți big-endian][octeți ZIP bruți de patch]
```

Șirurile de versiune (`"2.0.8"`) provin din `core/assets/res/build.properties` — exact același fișier pe care Gradle îl citește pentru a marca versiunea proiectului (Secțiunea 15) și pe care `Config.java` îl citește la runtime (Secțiunea 1.4).

### Semnare

```python
# patch_server/server.py și build_patch.py — funcție identică în ambele
def signature_payload(patch_sha256: bytes, from_version: str, to_version: str) -> bytes:
    return patch_sha256 + b"|" + from_version.encode("ascii") + b"|" + to_version.encode("ascii")
```

RSA-2048 cu padding PKCS#1 v1.5 peste SHA-256, semnând nu doar hash-ul patch-ului ci hash-ul concatenat cu ambele șiruri de versiune — legare care înfrânge atât un atac de retrogradare cât și o redare de patch nepotrivit.

### Verificare Client-Side

`desktop/src/main/java/desktop/update/Updater.java` (Secțiunea 16) descarcă patch-ul, recalculează independent hash-ul SHA-256, și verifică semnătura RSA împotriva unei chei publice compilate direct în binarul clientului.

## 13.4 Licențiere și Integrarea itch.io

### Principiul Director

`SERVERS/LICENSING.md` afirmă filozofia de design într-o linie care merită citată direct:

> **"itch.io este ușa, nu proprietarul."** Un jucător dovedește că a cumpărat jocul *o singură dată*, la prima lansare. Din acel moment licența aparține **serverului tău**, iar itch nu mai este contactat niciodată.

### Pasul 1: Dovedirea Cumpărării — OAuth itch.io (Doar Desktop)

```java
// desktop/src/main/java/desktop/itch/DesktopItchAuth.java
private static final String ITCH_CLIENT_ID_BAKED = "00477f3fb217b3b7fc21fb520c5a65b3";
private static final int REDIRECT_PORT = 34567;
```

Fluxul, doar la prima lansare: clientul pornește un `HttpServer` local legat de `127.0.0.1:34567`, deschide pagina OAuth itch.io în browser-ul implicit, jucătorul aprobă cererea, iar itch redirectează înapoi cu un `access_token` ca fragment de URL (rezolvat printr-o mică redirectare JavaScript injectată, pentru că browserele nu trimit fragmente de URL către un server prin HTTP).

### Pasul 2: Activare — Verificare Server-Side Împotriva API-ului itch

Token-ul OAuth nu ajunge niciodată la `save_server` în plicul principal RSA-OAEP (limitat la ~190 octeți) — călătorește în propria lui cutie AES-GCM separată. Serverul face două apeluri către API-ul itch, folosind cheia API a *dezvoltatorului*:

```python
# save_server/server.py — itch_verify_purchase(), simplificat
# 1. Identifică jucătorul folosind PROPRIUL SĂU token
GET https://itch.io/api/1/{player_oauth_token}/me
# 2. Întreabă itch, folosind CHEIA NOASTRĂ api, dacă acel user_id deține o cheie de descărcare
GET https://itch.io/api/1/{our_api_key}/game/{game_id}/download_keys?user_id={user_id}
```

Codul distinge explicit un "nu" definitiv de un rezultat neconcludent:

```python
# save_server/server.py
class ItchError(Exception):
    """itch.io nu a putut fi accesat / a răspuns cu date fără sens. Distinct de 'utilizatorul nu deține
    jocul', care este un NU definitiv mai degrabă decât un rezultat neconcludent — nu trebuie
    să acordăm o licență doar pentru că itch a fost întâmplător indisponibil."""
```

### Pasul 3: Emiterea Licenței

Odată confirmată proprietatea, serverul generează o cheie de licență nouă, un `activation_id`, și o `enc_key` doar-server. Din acest punct, clientul folosește exclusiv calea `LOGIN`, care nu contactează deloc itch.io.

### Ce Este Deliberat Absent Din Acest Model

- Nicio verificare DRM la fiecare lansare.
- Nicio amprentare de hardware/mașină înglobată în licența în sine, pe server (cache-ul offline al clientului derivă o cheie doar-locală dintr-o amprentă de mașină, dar asta e pur pentru fișierul cache local — Secțiunea 11.1).
- Nicio cerință de a fi mereu online.

---

# 14. Versiunea Android — Cum Funcționează

## 14.1 Prezentare Generală: Aceeași Bază de Cod, Trei Module

Portul Android nu e o bază de cod separată — e modulul `android/`, al treilea din cele trei module Gradle (`core`/`desktop`/`android`), care înfășoară exact același `main.MichiGame` partajat (Secțiunea 1.2) într-un `AndroidApplication`. Documentul `android_adaptations.md` afirmă direct scopul deciziei de targetare: hardware de gamă înaltă, fără compromisuri pentru dispozitive vechi — "Target devices: Samsung Galaxy S25 Ultra (primary), S24 Ultra (must also work) — both high-end, landscape-friendly, 120Hz. No low-end-device compromises were made."

Modulul `android/build.gradle` reflectă exact această alegere:

```groovy
// android/build.gradle
android {
    namespace 'com.michi.adventure'
    compileSdk 34
    defaultConfig {
        minSdk 26
        targetSdk 34
        versionName project.version.toString()
        ndk { abiFilters 'arm64-v8a' }
    }
    splits { abi { enable false } }
}
```

`minSdk 26` (Android 8.0) e comfortabil sub tot ce livrează S24/S25 Ultra; un singur filtru ABI `arm64-v8a` (aproape tot hardware-ul Android modern e exclusiv arm64) menține APK-ul mai mic și matricea de biblioteci native mai simplă, cu prețul de a nu suporta telefoane foarte vechi.

## 14.2 `AndroidLauncher` — Punctul de Intrare

```java
// android/src/main/java/androidlauncher/AndroidLauncher.java
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

        NfcFriend.set(new NfcFriendServiceImpl(this));
        BleMultiplayer.setHost(new BleHostServiceImpl(this));
        BleMultiplayer.setGuest(new BleGuestServiceImpl(this));
        BlePermissions.requestOnFirstBootIfNeeded(this);
        checkNfcLaunch(getIntent());

        initialize(new MichiGame(), config);
    }
    // ...
}
```

Structura mimează exact forma lansatorului desktop (`DesktopLauncher`): rulează un bootstrap specific platformei, apoi predă controlul aceluiași `main.MichiGame` pe care fiecare backend îl partajează. Documentația proprie a clasei notează un detaliu important de ordonare a ciclului de viață: `Gdx.app`/`Gdx.files` **nu există încă** în acest punct al ciclului de viață Android, deci configurarea specifică platformei care depinde de ele nu poate rula aici — trebuie amânată până când `MichiGame.create()` rulează, cu `Gdx` deja viu (exact motivul pentru care activarea licenței rulează din `create()`, nu din `AndroidLauncher.onCreate()`, Secțiunea 1.2).

Senzorii de accelerometru/busolă/giroscop sunt dezactivați explicit — nefolosiți, deci ar fi doar consum de baterie fără niciun beneficiu. Wakelock-ul e activat pentru că jocul e o acțiune văzută de sus care nu ar trebui să lase ecranul să adoarmă în mijlocul unei lupte.

## 14.3 Manifestul Android: Permisiuni și Motivul Fiecăreia

```xml
<!-- android/AndroidManifest.xml -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.NFC" />
<uses-feature android:glEsVersion="0x00020000" android:required="true" />
<uses-feature android:name="android.hardware.nfc.hce" android:required="false" />

<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-feature android:name="android.hardware.bluetooth_le" android:required="false" />
```

- **`INTERNET`** — necesară pentru salvările în cloud, activarea licenței, și multiplayer TCP.
- **`NFC` + `nfc.hce`** — alimentează fluxul "atinge telefoanele pentru a adăuga un prieten sau invita la o sesiune BLE".
- **`BLUETOOTH_ADVERTISE`/`_CONNECT`/`_SCAN`** — cele trei permisiuni "periculoase" la runtime cerute de Android 12+ pentru multiplayer BLE local, cerute leneș, doar când un jucător chiar începe să găzduiască sau să se alăture, nu în bloc la pornirea aplicației.
- **`usesPermissionFlags="neverForLocation"`** — o afirmație deliberată: acest joc nu derivă niciodată locația fizică a unui jucător din rezultatele scanării BLE (se conectează întotdeauna doar la o adresă specifică obținută printr-o atingere NFC), permițând sărirea peste permisiunea mult mai largă `ACCESS_FINE_LOCATION`.

`androidlauncher.ble.BlePermissions.requestOnFirstBootIfNeeded()` declanșează dialogul de permisiune al OS-ului exact o singură dată, urmărit prin `SharedPreferences`, astfel încât un jucător care revine nu e niciodată re-întrebat.

### Două Servicii HCE Separate — Prietenie Versus Invitație de Sesiune

Cercetarea manifestului curent relevă un detaliu mai avansat decât documentul original `android_adaptations.md`: există **două** servicii NFC Host Card Emulation distincte, fiecare cu propriul AID:

```xml
<!-- android/AndroidManifest.xml -->
<service android:name="androidlauncher.nfc.FriendHceService" ...>
    <!-- Emulates this device as an NFC tag carrying this player's add-friend identity -->
</service>
<service android:name="androidlauncher.nfc.Ndef4Service" ...>
    <!-- Standard NFC Forum Type-4 Tag emulation, active only while hosting a BLE session —
         its NDEF message's Android Application Record is what lets Android cold-launch a
         closed guest app straight to auto-join on tap. -->
</service>
```

`FriendHceService` emulează dispozitivul ca o etichetă NFC purtând identitatea de adăugare-prieten a jucătorului, activă permanent, trezită de rutarea AID a Android chiar și când aplicația e închisă. `Ndef4Service` e activ **doar** cât timp dispozitivul găzduiește o sesiune BLE — mesajul lui NDEF conține un Android Application Record (AAR) care permite Android să pornească din stare rece (cold-launch) o aplicație de oaspete complet închisă direct în modul de auto-alăturare, la o simplă atingere.

### O Poveste Reală de Conflict de Hardware: AID-ul NDEF Partajat

Comentariul din `AndroidLauncher.onResume()`/`onPause()` narează o descoperire reală, verificată pe hardware fizic:

```java
// android/src/main/java/androidlauncher/AndroidLauncher.java
/**
 * D2760000850101 (the standard NFC Forum NDEF Tag Application AID Ndef4Service uses) is not
 * exclusive to this app — Android ships a built-in "Embedded tag" system service
 * (com.android.nfc.ndef_nfcee) registered for the same AID on real devices, confirmed via
 * `adb shell dumpsys nfc` on both Galaxy S25 Ultra and A37 test hardware. With two services
 * eligible for one AID, Android's documented same-AID conflict resolution shows the user a
 * disambiguation dialog on every tap [...] instead of silently routing to us. Foreground
 * preference (CardEmulation#setPreferredService, cleared in onPause per its own contract)
 * overrides that conflict resolution while this Activity is frontmost [...] This cannot help
 * the genuinely-cold-launch case (app fully closed, no foreground Activity to call this from)
 * — that tap still goes through normal OS dispatch and may show the system chooser once; it's
 * an inherent platform limit of sharing the standard NDEF AID, not a bug here.
 */
@Override
protected void onResume() {
    super.onResume();
    CardEmulation ce = CardEmulation.getInstance(NfcAdapter.getDefaultAdapter(this));
    if (ce != null) {
        ce.setPreferredService(this, new ComponentName(this, Ndef4Service.class));
    }
}

@Override
protected void onPause() {
    super.onPause();
    CardEmulation ce = CardEmulation.getInstance(NfcAdapter.getDefaultAdapter(this));
    if (ce != null) {
        ce.unsetPreferredService(this);
    }
}
```

Povestea completă: AID-ul standard NFC Forum pentru etichete NDEF Type-4 (`D2760000850101`), pe care `Ndef4Service` îl folosește, **nu e exclusiv** acestui joc — Android livrează propriul lui serviciu de sistem "Embedded tag" (`com.android.nfc.ndef_nfcee`) înregistrat pentru **același** AID, confirmat direct pe hardware real (`adb shell dumpsys nfc`, pe ambele dispozitive de test, S25 Ultra și A37). Cu două servicii eligibile pentru un singur AID, mecanismul documentat al Android de rezolvare a conflictelor pentru același AID afișează utilizatorului un dialog de dezambiguizare la fiecare atingere — în loc să rutinizeze silențios către serviciul jocului. Soluția găsită: **preferința de prim-plan** (`CardEmulation.setPreferredService`), setată la `onResume()` și curățată explicit la `onPause()` (conform propriului contract al API-ului), suprascrie acea rezolvare de conflict cât timp această activitate e cea din prim-plan — astfel încât atingerile de tip INVITE PLAYER/JOIN GAME între două telefoane care au deja aplicația deschisă nu ating niciodată selectorul de sistem. Comentariul notează onest limita rămasă: acest lucru **nu** poate ajuta cazul de pornire complet rece (aplicație complet închisă, nicio activitate de prim-plan care să apeleze acest cod) — acea atingere trece în continuare prin dispatch-ul normal OS și poate arăta selectorul de sistem o dată; e o limită inerentă a platformei, de partajare a AID-ului standard, nu un bug al acestui joc.

## 14.4 Controalele Tactile: `TouchControlsOverlay`

`core/src/main/java/mobile/TouchControlsOverlay.java` (302 linii) e un `Scene2D Stage` propriu, cu propriul `ScreenViewport`, independent de camera lumii jocului, desenat deasupra jocului. Nu atinge niciodată codul de gameplay direct — pilotează exact aceleași câmpuri publice pe care tastatura și mouse-ul le pilotează deja (Secțiunea 2.6).

### Construirea Skin-ului Fără Nicio Dependență Nouă de Asset

```java
// core/src/main/java/mobile/TouchControlsOverlay.java (rezumat)
// buildMinimalSkin() — construiește un Skin funcțional dintr-un singur Pixmap alb 1x1,
// "no external .atlas/.json dependency"
```

Overlay-ul își construiește propriul `Skin` la runtime, la nevoie, dintr-un singur pixmap alb — fără nicio nouă dependență de pipeline de asset-uri. Rezultatul e funcțional, dar vizual simplu (dreptunghiuri colorate plat cu etichete text) — un placeholder pregătit pentru un skin/atlas HUD mai elaborat mai târziu, nu un design vizual finit.

### Fiecare Buton, Cartografiat Exact

- **Touchpad-ul de mișcare** (colț stânga-jos, zonă moartă `DEADZONE = 0.3f`) traduce poziția manetei virtuale în cele patru booleene `gp.keyH.upPressed/downPressed/leftPressed/rightPressed` — exact aceleași patru booleene pe care WASD le setează.
- **Butonul de atac** distinge o atingere scurtă de o ținere lungă printr-un prag de timp (`ATTACK_HOLD_THRESHOLD_MS = 180`): sub prag declanșează un atac unic prin `fireAttack()` (poziționează cursorul virtual la centrul ecranului jucătorului, reutilizând fallback-ul existent al ochirii cu direcție cardinală); peste prag setează `attackButtonHeld=true`, tradus în fiecare cadru în `gp.keyH.enterPressed=true`.
- **Butonul de tragere la distanță** e singura excepție de la tiparul "setează și lasă": pentru că `shotKeyPressed` e în mod normal golit de eliberarea fizică a tastei (`keyUp()`), overlay-ul setează explicit `true` apoi `false` peste granița exactă a unui cadru per atingere.
- **Butonul de dash** setează `gp.keyH.dashPressed = true` direct la atingere.
- **Butonul PAUSE** oglindește tasta P de pe desktop, tranziționând doar între `playState`/`pauseState` (gărduiește împotriva smulgerii jucătorului din dialog/cutscenă).
- **Butonul de inventar**: o atingere scurtă comută `characterState`; o ținere lungă (peste 180ms) deschide în schimb `skillTreeState`.
- **Cele 4 butoane de abilitate** (SHOCK/SNARE/FROST/OVRDR) — fiecare gardat de un predicat de tip `Unlocked` (`gp.player.shockwaveUnlocked` etc.), **ascuns**, nu doar dezactivat vizual, până când abilitatea corespunzătoare e deblocată, reverificat în fiecare cadru din moment ce abilitățile se pot debloca în mijlocul unei sesiuni prin skill tree.

### Ordinea de Înregistrare În `InputMultiplexer`

Cum a fost deja notat în Secțiunea 1.2, `Stage`-ul overlay-ului e plasat **primul** în `InputMultiplexer`, exact pentru ca atingerile pe propriile lui widget-uri (joystick, butoane de acțiune) să fie consumate înaintea propagării către `MouseHandler` ca "atingeri de lume" (care ar declanșa, de exemplu, un click greșit pe o poziție din joc chiar sub un buton virtual).

## 14.5 Suport Pentru Gamepad: `GamepadInputAdapter`

`core/src/main/java/mobile/GamepadInputAdapter.java` implementează `ControllerListener` al libGDX. Traduce fiecare margine (edge) de buton/axă fizică în token-uri simbolice `"controller:xxx"` alimentate direct în `gp.actions` (Secțiunea 2.3) — același sistem de acțiuni pe care tastatura/mouse-ul îl alimentează, deci nu există logică de gameplay specifică gamepad-ului dincolo de acest strat de traducere.

Un detaliu important pentru corectitudinea între diferite modele de controller: maparea buton→cod fizic nu e niciodată cache-uită — e recalculată prin `controller.getMapping()` la fiecare eveniment, pentru că "two gamepad models can report buttonA at different raw codes". Zona moartă a stick-ului (`STICK_DEADZONE = 0.35f`) urmărește starea de "traversare a pragului" pentru fiecare direcție de axă separat, raportând o margine fizică exact o dată per traversare, nu la fiecare callback continuu.

Fiind înregistrat necondiționat în `MichiGame.create()` (Secțiunea 1.2), pe orice backend, acest adaptor face ca desktop-ul să primească suport pentru controller Xbox/PlayStation "gratuit" — aceeași clasă de adaptare servește ambele platforme.

## 14.6 Pierderea Contextului GL Pe Android — Recapitulare Cu Context Android

Detaliile tehnice complete ale mecanismului de recuperare (`PixmapTextureData` gestionat, golirea de cache la `create()`) sunt tratate integral în Secțiunea 10.2. Contextul specific Android care face acest lucru necesar: Android poate distruge și recrea contextul GL al aplicației — la trecerea în fundal, la intrarea în split-screen, sau sub presiune de memorie — **fără a omorî procesul**. Acest lucru nu se întâmplă practic niciodată pe desktop (LWJGL3 aproape că nu pierde niciodată contextul), motiv pentru care acest întreg mecanism de recuperare nu poate fi testat decât pe un dispozitiv Android real (fundal/prim-plan aplicația în mijlocul unei sesiuni). La momentul cercetării, verificarea rămasă deschisă era dacă acest lucru a fost efectiv sursa corupției vizuale observate de un jucător pe ecranul gazdei în timpul unei sesiuni de multiplayer BLE — o confirmare care necesită testare directă pe dispozitiv.

## 14.7 BLE Este Primar Pe Android

Multiplayer-ul BLE (Secțiunea 12.7) este, prin natura lui, **exclusiv Android** — se bazează pe API-uri client/server GATT BLE și pe emulare NFC HCE, ambele disponibile doar pe backend-ul Android. `platform/BleHostService.java`/`BleGuestService.java` sunt interfețele; implementările reale (`androidlauncher.ble.BleHostServiceImpl`/`BleGuestServiceImpl`) există doar în sursa modulului `android`. Desktop-ul nu implementează niciodată aceste interfețe — `platform.BleMultiplayer.isHostingSupported()` returnează pur și simplu `false` acolo, pentru că nimeni nu apelează `setHost(...)`.

### O Particularitate de Hardware Documentată: Adresa MAC Placeholder

Documentația proprie a interfeței `BleGuestService` menționează o descoperire reală de hardware: `BluetoothAdapter.getAddress()` returnează placeholder-ul fix `02:00:00:00:00:00` aplicațiilor neprivilegiate, începând cu API 23 — confirmat prin eșecuri de conectare GATT cu status 147 pe Galaxy S25 Ultra/A37 în timpul dezvoltării. Din acest motiv, descoperirea unei sesiuni BLE se face prin **scanare de UUID de serviciu**, nu prin adresă MAC — motivul exact pentru care `NfcInvitePayload` (formatul `MICHIINVITE2|sessionToken|mapId|hostName`) exclude deliberat o adresă Bluetooth din payload-ul lui.

## 14.8 Activarea Licenței: Identică, Nu Specifică Platformei

Așa cum s-a stabilit deja în Secțiunea 11.2, `platform.LicenseActivation` e complet partajată între desktop și Android — nicio ramură specifică platformei. Singura diferență reală de flux e la **Pasul 1** al licențierii (Secțiunea 13.4): dovedirea cumpărării prin OAuth itch.io e disponibilă **doar** pe desktop, pentru că `platform.ItchAuthProvider` nu are nicio implementare Android — itch.io vinde licențe de joc desktop, nu licențe de aplicație mobilă. Pe Android, acest pas e pur și simplu indisponibil/sărit; restul fluxului de licențiere (activare server-side, stocare `activation_id`+`enc_blob`, `LOGIN` la rulările ulterioare) e identic pe ambele platforme.

## 14.9 Semnarea APK-ului Android vs. Împachetarea Desktop

```groovy
// android/build.gradle
def releaseKeystoreFile = rootProject.file('build_tools/keystore/michis-adventure-release.jks')
// Release signing: keystore lives in build_tools/keystore/ (see that dir for the password
// file). Every future release build must reuse this same key, or Android will refuse to
// treat it as an update to previously-installed copies.
```

Acest keystore există pentru exact un scop: semnarea de release a APK-ului Android. Android leagă identitatea și capacitatea de actualizare a unei aplicații de cheia care a semnat-o, nu doar de numele pachetului — pierderea acestui keystore ar însemna că fiecare jucător existent ar trebui să dezinstaleze și să reinstaleze pentru a primi orice actualizare viitoare. `.exe`-ul desktop construit de jpackage/Inno Setup nu are echivalent — nu e semnat de cod deloc (Secțiunea 15.3). Cele două platforme sunt gestionate de mecanisme complet fără legătură: Android are semnarea criptografică de release înglobată în modelul de instalare/actualizare al OS-ului; desktop nu are, și se distribuie nesemnat.

## 14.10 Ce Rămâne Neverificat Direct Pe Dispozitiv

Documentul `android_adaptations.md` notează onest o listă de elemente care nu pot fi confirmate decât pe hardware fizic, nu prin compilare sau emulare: senzația exactă a controalelor tactile (dimensiune deadzone joystick, lizibilitatea widget-urilor placeholder cu culoare plată peste gameplay), asocierea de gamepad Bluetooth/USB în practică, handshake-ul real de licență împotriva serverelor live de pe un dispozitiv Android real, corectitudinea orientării/modului imersiv, comportamentul wakelock, și pacing-ul de cadre la 120Hz. Iconița placeholder a lansatorului (32×32, sub rezoluția recomandată de Android) e de asemenea marcată explicit ca provizorie, de înlocuit înainte de orice lansare reală.

---

# 15. Sistemul de Build (Gradle)

## 15.1 Un Singur Codebase, Trei Module

`settings.gradle` declară topologia proiectului:

```groovy
// settings.gradle
rootProject.name = 'michis-adventure'
include 'core'
include 'desktop'
include 'android'
```

Aceasta e cea mai importantă decizie structurală din proiect. Modulul **core** conține întregul joc — fiecare sistem descris în secțiunile anterioare — și nu știe nimic despre dacă rulează pe un desktop Windows sau un telefon Samsung; e construit pe baza `gdx-backend-headless` (Secțiunea 1.3), care nu oferă nicio fereastră, niciun context GPU, niciun dispozitiv de intrare. **desktop** și **android** sunt deliberat subțiri: fiecare e doar un lansator care pornește `core` într-un backend de fereastră/grafică potrivit platformei, plus lipiciul specific platformei (BLE, NFC, itch OAuth, auto-actualizare) pe care platforma respectivă îl suportă în mod unic.

```groovy
// desktop/build.gradle
implementation project(':core')
implementation "com.badlogicgames.gdx:gdx-backend-lwjgl3:$gdxVersion"

// android/build.gradle
implementation project(':core')
implementation "com.badlogicgames.gdx:gdx-backend-android:$gdxVersion"
```

`implementation project(':core')` nu e o descărcare Maven — e Gradle conectând clasele compilate ale unui subproiect direct în classpath-ul altui subproiect, în cadrul aceluiași build. Acesta e mecanismul literal al partajării de cod, și e aceeași fundație pe care se sprijină tot ce e descris în Secțiunea 12.5 (`engineJar`) și Secțiunea 1.3 (`HeadlessGame`).

## 15.2 Versiunea, Centralizată Într-Un Singur Fișier

```groovy
// build.gradle (rădăcină)
ext.loadGameVersion = {
    def props = new Properties()
    def f = file('core/assets/res/build.properties')
    if (f.exists()) { f.withInputStream { props.load(it) } }
    return [version: props.getProperty('version', '2.0'), build: props.getProperty('build', '0')]
}
ext.gameVersion = loadGameVersion()
version = ext.gameVersion.version
```

`core/assets/res/build.properties` e citit simultan de Gradle (pentru versiunea proiectului), de `Config.java` (Secțiunea 1.4, la runtime, pentru afișarea versiunii în joc), și de `patch_server` (Secțiunea 13.3, pentru compararea versiunii clientului la verificarea de actualizări) — un singur fișier, citit din trei locuri diferite, garantează că versiunea raportată de Gradle, versiunea afișată în ecranul "Despre" al jocului, și versiunea pe care serverul de patch-uri o compară nu pot niciodată diverge.

## 15.3 Build-ul Desktop: LWJGL3, Fat Jar, Împachetare Nativă

```java
// desktop/src/main/java/desktop/DesktopLauncher.java
public class DesktopLauncher {
    public static void main(String[] args) {
        if (!DesktopBootstrap.bootstrap()) return;
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setWindowedMode(MichiGame.BASE_W, MichiGame.BASE_H);
        new Lwjgl3Application(new MichiGame(), config);
    }
}
```

`DesktopBootstrap.bootstrap()` rulează **înainte** ca vreo fereastră să se deschidă — apelează verificarea de actualizare a serverului de patch-uri (Secțiunea 16) și înregistrează implementarea desktop a interfeței OAuth itch (`DesktopItchAuth`, Secțiunea 13.4).

`desktop/build.gradle` produce un **fat jar** executabil de sine stătător (fiecare dependență înglobată într-o singură arhivă), apoi `gradle/packaging.gradle` înlănțuie trei task-uri suplimentare: `jpackageImage` (împachetează fat jar-ul cu o copie privată a runtime-ului Java, într-o imagine de aplicație Windows nativă — jucătorii nu au niciodată nevoie de Java instalat separat), `innoInstaller` (rulează Inno Setup pentru un installer prietenos, fără drepturi de administrator), și `installer` (task-ul umbrelă care le înlănțuie pe toate). Task-ul `jpackageImage` pregătește deliberat un director de intrare curat, specific pentru a evita un bug real documentat în scriptul de împachetare: îndreptarea directă a `jpackage --input` spre folderul de output cauza ca fiecare build să reîmpacheteze output-ul installer-ului din build-ul anterior în cel nou, crescând nemărginit dimensiunea installer-ului de la un build la altul.

`.exe`-ul desktop rezultat **nu e semnat de cod** — niciun certificat Authenticode nu apare nicăieri în scriptul de împachetare, deci Windows SmartScreen va afișa probabil un avertisment de "editor nerecunoscut" la prima rulare, un compromis cunoscut și acceptat.

## 15.4 Build-ul Android: Diferența Structurală Față de `core`

```groovy
// android/build.gradle
sourceSets {
    main {
        java.srcDirs = ['src/main/java/androidlauncher']
        assets.srcDirs = ['assets', '../core/assets']
    }
}
```

`java.srcDirs` restricționează sursa Java proprie a Android la `androidlauncher` — acest director conține **doar** cod de legătură unic pentru Android (server/client GATT BLE, servicii NFC HCE, `AndroidLauncher`), niciodată cod de gameplay. `assets.srcDirs = ['assets', '../core/assets']` e mecanismul real de partajare a assets-urilor: împachetatorul Android copiază `core/assets/**` textual în folderul de assets al APK-ului, păstrând exact structura de disc pe care codul jocului o așteaptă — astfel încât aceleași apeluri `Gdx.files.internal("res/...")` din `core` funcționează neschimbate indiferent dacă se rezolvă în raport cu classpath-ul jar-ului desktop sau cu folderul de assets al APK-ului Android.

Android are nevoie și de un task scris manual, `copyAndroidNatives`, pentru a ocoli o particularitate de împachetare: libGDX își distribuie fișierele native `.so` la rădăcina unui jar, dar mecanismul `jniLibs` al Android le așteaptă aranjate pe ABI (`libs/arm64-v8a/*.so`) — task-ul le reformatează, conectat automat înainte de orice task `merge*JniLibFolders*`.

## 15.5 Modelul Ports-and-Adapters

`core/src/main/java/platform/` definește interfețe fără niciun import specific platformei, astfel încât `core` compilează identic indiferent dacă va rula headless, pe desktop, sau pe Android:

| Interfață | Implementare Desktop | Implementare Android |
|---|---|---|
| `ItchAuthProvider` | `desktop.itch.DesktopItchAuth` | *(neimplementată)* |
| `NfcFriendService` | *(neimplementată)* | `androidlauncher.nfc.NfcFriendServiceImpl` |
| `BleHostService`/`BleGuestService` | *(neimplementată)* | `androidlauncher.ble.BleHostServiceImpl`/`BleGuestServiceImpl` |
| `LicenseActivation` | comună — clasă identică pe fiecare backend | comună |

`core` nu importă niciodată direct o clasă specifică platformei — apelează întotdeauna prin interfață/fațadă, iar fiecare metodă de fațadă e sigură la null când nimic nu a fost înregistrat. `LicenseActivation` e un contra-exemplu deliberat, unificat în `core` odată ce licențierea s-a mutat complet pe partea de server — clientul acum doar rulează un handshake pe socket identic pe fiecare backend.

---

# 16. Actualizări și Patch-uri

Verificarea de actualizări **nu** face parte din `core` — locuiește în întregime în modulul `desktop`, pentru că auto-actualizarea unei aplicații care rulează e inerent specifică platformei (aplicațiile Android se actualizează prin Play Store sau instalare manuală de APK, nu prin rescrierea propriilor fișiere instalate — Secțiunea 14.9).

`desktop/src/main/java/desktop/update/Updater.java` e un aplicator de patch-uri într-un proces extern, generat ca o **JVM separată** de jocul care rulează, specific pentru că un program nu poate suprascrie în siguranță propriul fișier jar în timp ce încă rulează:

```
java -cp game.jar desktop.update.Updater  game.jar  patch.zip  newVersion  parentPid
```

`Updater` așteaptă ca procesul original al jocului să iasă complet (`ProcessHandle.onExit()`), apoi aplică patch-ul descărcat (un set de intrări de fișier adăugare/înlocuire/ștergere) atomic peste jar, și relansează jocul. Nu atinge niciodată fișiere din afara jar-ului în sine — datele de licență, listele de servere, și fișierele de salvare sunt lăsate complet neatinse.

Protocolul complet cu care comunică (`CHECK`/`UPDATE`/`FETCH`, semnarea RSA legată de ambele șiruri de versiune) e documentat integral în Secțiunea 13.3.

---

# 17. Sumare ale Fluxurilor Complete

## Prima Lansare, Desktop, Flux Complet de Cumpărare

1. `DesktopLauncher.main()` rulează `DesktopBootstrap.bootstrap()`, care verifică serverul de patch-uri pentru actualizări (Secțiunea 16) și înregistrează `DesktopItchAuth` la interfața `ItchAuthProvider` (Secțiunea 15.5).
2. Fereastra LWJGL3 se deschide; `MichiGame.create()` resetează orice cache-uri de textură expirate (Secțiunea 10.2) și pornește `LicenseActivation.ensureActivated()` pe un thread de fundal (Secțiunea 1.2).
3. Niciun `activation.dat` local nu există încă, așa că clientul deschide pagina OAuth itch.io în browser-ul jucătorului (Secțiunea 13.4, Pasul 1).
4. Odată obținut un token de acces itch, clientul efectuează handshake-ul `HELLO`/`ACTIVATE` împotriva `save_server` (Secțiunea 13.1), trimițând token-ul itch în propria sa cutie AES-GCM.
5. `save_server` verifică proprietatea împotriva API-ului itch (Secțiunea 13.4, Pasul 2) și, dacă verificată, emite o cheie de licență, o criptează în `enc_blob`, și returnează `AUTH_OK`.
6. Clientul persistă `activation_id` + `enc_blob` în `activation.dat` și nu mai trebuie niciodată să contacteze itch.io din nou.
7. Gameplay-ul continuă; `GamePanel.setupGame()` încarcă harta implicită, plasează entitățile, pornește cutscena de trezire (Secțiunea 8.4). La salvare, `CloudSaveService` încarcă către `save_server` folosind un handshake nou derivat din cheia de licență acum cunoscută (Secțiunea 11.1).

## Fiecare Lansare Ulterioară

1. `LicenseActivation.ensureActivated()` găsește `activation.dat`, efectuează un handshake `LOGIN` (Secțiunea 13.1) — deloc contact cu itch.io.
2. Dacă niciun server de salvare nu e accesibil, clientul revine la o salvare pusă în cache local, criptată prin amprentă de mașină (Secțiunea 11.1) și pune în coadă o încărcare pentru când conectivitatea revine.

## Alăturarea la Multiplayer (TCP)

1. `MultiplayerClient` efectuează propriul handshake `HELLO`/`LOGIN` împotriva `multiplayer_server`, prezentând aceeași pereche `activation_id`/`enc_blob` (Secțiunea 12.2).
2. `multiplayer_server` retransmite acele credențiale către portul doar-intern al `save_server` pentru verificare (Secțiunea 13.2) în loc să-și păstreze propria bază de date de licențe.
3. Serverul trimite `world_info` (schelet TMX) urmat de mesaje `chunk` streamuite (Secțiunea 12.5); clientul reconstruiește harta prin exact pipeline-ul de încărcare de hartă single-player.
4. Odată autentificat, tot traficul de mișcare, luptă, și progresie curge prin sesiunea criptată; serverul impune limite de mișcare și limite de pas, deleagă deciziile de luptă către `engine.jar`-ul înglobat (Secțiunea 12.5), și tratează XP/aur/nivele ca stare deținută de server despre care clientului i se poate doar spune, niciodată să o seteze (Secțiunea 12.4).
5. Jucătorii la distanță sunt randați client-side folosind interpolare spline Hermite între instantanee de poziție autoritare periodice (Secțiunea 12.3).
6. NPC-urile sunt găzduite exclusiv pe server; clientul trimite `npc_interact` și afișează orice răspuns `npc_dialogue`/`npc_shop` primește (Secțiunea 12.6).

## Alăturarea la o Sesiune BLE (Doar Mobil)

1. Două dispozitive Android ating NFC pentru a schimba un token de sesiune și un ID de hartă (Secțiunea 12.7, permisiunile manifestului Android din Secțiunea 14.3).
2. Dispozitivul gazdă rulează un server GATT BLE local; oaspetele se conectează direct — fără internet, fără verificare de licență, fără criptare, din moment ce granița de încredere e proximitatea fizică, nu o rețea.
3. Starea de gameplay (poziție, HP mob) se sincronizează printr-un protocol compact separat prin bară verticală, cu gazda autoritară pentru lupta cu mob-i în cadrul acelei sesiuni (Secțiunea 12.7).
4. `RenderPipeline` desenează jucătorii la distanță BLE prin același gate lărgit (`tcpConnected || bleActive`) care a reparat bug-ul de vizibilitate documentat în Secțiunea 12.8.

## Primirea Unui Patch

1. Pe desktop, `UpdateClient` verifică la `patch_server` la pornire, comparând versiunea build-ului care rulează (citită din `core/assets/res/build.properties`, Secțiunea 15.2) cu manifestul serverului.
2. Dacă există un patch direct-salt, clientul îl descarcă, îi verifică hash-ul SHA-256 și semnătura RSA împotriva unei chei publice compilate în client, și generează o JVM `Updater` separată (Secțiunea 16).
3. `Updater` așteaptă ca procesul original să iasă, aplică patch-ul atomic peste jar-ul jocului, și relansează — toate fără a atinge vreodată datele de licență sau fișierele de salvare.

---

*Sfârșitul documentului. Pentru ghiduri suplimentare, specifice sistemelor de conținut (autorat de quest-uri, configurare de hărți Tiled, definirea NPC-urilor prin JSON, pictarea sistemului de vânt), consultați celelalte fișiere din `core/guides/`: `QUEST_GUIDE.md`, `TILED_ENTITY_GUIDE.md`, `NPC_JSON_GUIDE.md`, `WIND_SYSTEM_GUIDE.md`.*

