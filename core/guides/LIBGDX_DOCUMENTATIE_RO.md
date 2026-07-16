# Arhitectura motorului de joc

## Introducere

Motorul din spatele Echoes of the Heir este construit în jurul framework-ului **libGDX**, care
oferă fundația pentru randare, input, audio și acces la fișiere. Peste acest framework, jocul
adaugă un strat propriu (`gfx.*`) care organizează desenarea, geometria de coliziune și
fonturile într-un mod unitar, folosit de întreaga bază de cod. Acest document descrie această
arhitectură: ce este libGDX și ce rol are, cum este structurat proiectul, care sunt piesele
esențiale ale stratului grafic, cum sunt implementate efectele vizuale și cum ajută totul acest
lucru la portabilitatea jocului pe mai multe platforme.

## 1. Ce este libGDX

**libGDX** este un framework Java open-source pentru dezvoltarea de jocuri, care oferă un
singur API pentru randare grafică, input, audio și gestiunea fișierelor, rulabil pe mai multe
platforme (desktop, Android, iOS, web) fără a rescrie codul de joc pentru fiecare dintre ele.
Randarea se face pe **GPU**, prin OpenGL, prin backend-uri specifice fiecărei platforme (LWJGL3
pe desktop, `gdx-backend-android` pe Android).

Echoes of the Heir folosește libGDX ca strat unic de randare, input și audio pentru întregul
joc: ecranul de titlu, cutscene-uri, gameplay, HUD, dialog, minimap, iluminat și vreme sunt toate
desenate prin acest strat, pe branch-ul `feat/libgdx-migration`. Coordonatele au originea în
colțul stânga-sus, iar toată desenarea se face pe placa video, cu codul de bază portabil pe
Android.

## 2. Cum este structurat proiectul

```
echoes-of-the-heir/
  settings.gradle, build.gradle, gradlew(.bat)   ← Gradle wrapper
  core/          ← logica de joc + stratul de randare gfx.*
    src/main/java/gfx/        GdxRenderer, Sprite, Color, Font, Stroke, FontSystem, …
    src/main/java/gfx/geom/   Rect, Ellipse, Polygon, IntPolygon, Transform, Shape
    src/main/java/main/       MichiGame (libGDX ApplicationListener)
  lwjgl3/        ← lansatorul de desktop (DesktopLauncher) + pachetare
  android/       ← lansatorul Android (gdx-backend-android)
```

libGDX **1.13.1**, compilat pentru bytecode **Java 17** (compatibil Android).

Rulare/compilare: `./gradlew :lwjgl3:run` pentru joc, `./gradlew :lwjgl3:jar` pentru un JAR
executabil, `./gradlew :lwjgl3:installer` pentru instalatorul complet de Windows.

## 3. Piese esențiale ale sistemului grafic (`gfx.*`)

- **`GdxRenderer`** — fațada centrală prin care tot jocul desenează, indiferent de platformă.
  Combină un `SpriteBatch` (imagini, text, degradeuri) cu un `ShapeRenderer` (umpluri, linii,
  elipse) și comută automat între ele, astfel încât restul codului nu trebuie să gestioneze
  direct starea GPU-ului (OpenGL). Sistemul de coordonate (Y crescând în jos) este păstrat
  printr-o singură inversare aplicată la nivel de cameră (`OrthographicCamera`).
- **`Sprite`** — înfășoară o `TextureRegion` din libGDX. Permite decupare fără copiere
  (`getSubimage`), esențial pentru tăierea sprite sheet-urilor și a tileset-urilor.
- **Tipuri de valoare Android-safe** — `gfx.Color`, `gfx.Font`, `gfx.Stroke`,
  `gfx.geom.Rect/Shape/IntPolygon/Transform`, `gfx.RadialGradient`/`gfx.Gradient` — folosite peste
  tot în locul echivalentelor lor din `java.awt`, care nu există pe Android. Rezultatul: în tot
  fluxul de randare al jocului nu există nicio dependență de `java.awt`.
- **`gfx.geom`** — geometria de coliziune (dreptunghi, elipsă, poligon, polilinie) folosită de
  hărțile Tiled, verificată printr-un test de paritate (240.000 de verificări
  `intersects`/`contains`, 0 diferențe față de implementarea de referință) — coliziunile se
  comportă exact cum trebuie.
- **`FontSystem`** — generează fonturi FreeType (`.ttf`) randate ca `BitmapFont` +
  `GlyphLayout`.
- **`main.MichiGame`** — implementarea libGDX `ApplicationListener` care rulează bucla jocului:
  actualizează logica la 60 de actualizări/secundă, apoi desenează cadrul curent prin
  `GdxRenderer`.
- **`KeyHandler`/`MouseHandler`** — implementează `InputProcessor` din libGDX (înregistrate
  printr-un `InputMultiplexer`) pentru tastatură și mouse.
- **Audio prin `Gdx.audio`** — `Music` pentru piese în buclă, `Sound` pentru efecte, cu suport
  nativ pentru `.ogg`/`.mp3`/`.wav`.

## 4. Efecte grafice, GPU-native

Efectele vizuale ale jocului sunt implementate în stilul idiomatic libGDX:

- **Hit-flash / telegrafare atac** — tinting prin `drawImageTinted` (re-desenare siluetă via
  `batch.setColor`).
- **Vignetă / grading de culoare** — texturi de degrade precalculate ("baked") prin
  `GdxRenderer.bakeRadialGradient`, desenate în fiecare cadru.
- **Iluminat (`Lightning`)** — textură de lumină radială precalculată, desenată **aditiv** peste
  o umplere de întuneric, cu `setBlendMode(NORMAL/ADDITIVE/DSTOUT)`.
- **Vreme (ploaie/zăpadă/furtună)** — `setColor` + `fillRect` + `setAlpha`.
- **Rotire/oglindire tile-uri (GID-uri Tiled)** — `drawTileFlipped` (oglindire regiune + pași de
  90°).
- **Minimap/sprite-uri generate** — bake prin `Pixmap` → `Texture`.

## 5. Y-sorting și ordinea de desenare pe adâncime

Într-o hartă top-down, dacă entitățile s-ar desena într-o ordine fixă (pe layere sau pe lista de
existență), un perete sau un tufiș "din spate" ar putea fi desenat peste player chiar și când
acesta stă vizual în fața lui. `RenderPipeline` rezolvă asta printr-un Y-sort: totul se desenează
în ordinea poziției Y a bazei fiecărui obiect ("picioarele"), nu a colțului stânga-sus al
sprite-ului — cine are baza mai sus pe hartă (Y mai mic) se desenează primul, cine are baza mai
jos (Y mai mare) se desenează ultimul, deci deasupra celorlalte.

- **Sortarea entităților** (`RenderPipeline.renderSorter`) compară
  `worldY + solidArea.y + solidArea.height + depthSortYOffset` — baza hitbox-ului, nu vârful
  sprite-ului, altfel un perete la nivelul tălpilor ar fi comparat greșit cu capul player-ului.
- **Tile-uri cu adâncime** (pereți, tufișuri înalte, streșini) au propriul `sortY` și sunt
  intercalate direct în bucla de desenare a entităților (interclasare pe două liste deja
  ordonate, ca la merge sort), astfel încât player-ul poate trece corect fie prin fața, fie prin
  spatele unui obiect înalt, în funcție de poziția reală pe hartă.
- **Particulele de tile** (`TileParticleEmitter`) au și ele un `sortY` propriu și sunt
  intercalate în aceeași buclă, alături de tile-urile cu adâncime și de entități.
- **`depthSortYOffset`** (`Entity.java`) e o supapă manuală pentru cazurile unde poziția
  geometrică reală nu dă rezultatul vizual corect: player-ul primește un offset pozitiv când calcă
  prin iarbă înaltă (ca să rămână mereu vizibil deasupra ei), particulele de spargere primesc un
  offset negativ mare (ca să se deseneze mereu în spate), iar urmele de pași lăsate de player
  primesc un offset pozitiv mare (ca să rămână "pictate" deasupra tile-ului pe care au fost
  lăsate).
- **HP bar-ul de boss** e desenat separat, într-un pass propriu, după toate layerele lumii — nu
  mai participă la Y-sort, ca să nu poată fi acoperit de un tile cu adâncime sortat după boss.
- **Umbrele de sol** se desenează imediat după fundal, înainte de orice tile cu adâncime sau
  entitate, deci rămân mereu sub tot ce are volum vizual.

## 6. Cum ajută la portabilitate (în special Android)

Toată logica de randare, input și audio este complet portabilă pe Android — nu există
`java.awt`, `javax.swing` sau `javax.sound` nicăieri în aceste straturi. Asta face jocul rulabil
"ca atare" pe orice backend suportat de libGDX:

- **Un singur cod sursă, mai multe platforme** — modulul `core` conține toată logica de joc și
  stratul `gfx.*`, partajat neschimbat între modulul `lwjgl3` (desktop) și modulul `android`.
  `MichiGame` rulează identic pe ambele, doar lansatorul (`DesktopLauncher` vs.
  `AndroidLauncher`) diferă.
- **Modulul `android/`** are propriul `build.gradle` (plugin `com.android.application`,
  `compileSdk 34`, `minSdk 26`, natives doar `arm64-v8a` pentru hardware modern), generând deja
  un APK funcțional care ajunge până în gameplay.
- **Stocare portabilă** — `platform.GameStorage` centralizează citirea/scrierea fișierelor
  (config, salvări), delegând spre fișiere clasice pe desktop și spre `Gdx.files.local(...)` pe
  Android, unde nu există un director de lucru clasic.
- **Încărcare de resurse portabilă** — toate încărcările de resurse sunt rutate prin
  `Gdx.files.internal(...)`, singura metodă care funcționează identic și în interiorul unui JAR
  pe desktop, și într-un APK pe Android.
- **Input extins, nu înlocuit** — pe lângă tastatură/mouse, s-au adăugat controale tactile
  (`TouchControlsOverlay`, un `Stage` Scene2D) și suport de gamepad
  (`gdx-controllers-*`), ambele scriind în aceleași câmpuri partajate pe care le foloseau deja
  tastatura și mouse-ul, deci fără logică nouă de consum a input-ului.
- **Puțin cod rămâne specific desktop-ului** — auto-update-ul (relansare de proces) rămâne izolat
  curat și nu mai există pe Android; licențierea a fost mutată la activare online, identică pe
  toate platformele, prin `platform.LicenseActivation`.

Pe scurt: libGDX oferă un strat de abstractizare peste GPU și peste serviciile platformei
(fișiere, input, audio), permițând aceleiași baze de cod să ruleze neschimbată pe desktop și pe
Android, cu diferențe izolate doar acolo unde platformele chiar diferă structural (actualizări,
licențiere, stocare).
