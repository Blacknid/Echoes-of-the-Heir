# Tehnologii folosite

Acest document listează, pe componente, tehnologiile, limbajele și bibliotecile folosite în dezvoltarea Echoes of the Heir (Michi's Adventure).

---

## Limbaje de programare

- **Java 17** — limbajul principal, folosit pentru întreg motorul de joc (modulele `core`, `desktop`, `android`) și pentru serverul autoritativ al motorului de gameplay (`engine.jar`).
- **Python 3 (asyncio)** — folosit pentru toate serverele de backend (multiplayer, salvări în cloud, licențiere, distribuție de patch-uri).
- **GLSL** — shadere scrise de la zero pentru iluminat, umbre (raymarching) și efecte de tip bloom.
- **JSON** — format declarativ pentru quest-uri, NPC-uri, obiecte, arbori de abilități și fișiere de configurare.

## Motor de joc și randare (client)

- **libGDX 1.13.1** — framework open-source peste care este construit întregul motor grafic, cu randare pe GPU prin OpenGL; oferă portabilitate desktop/Android din aceeași bază de cod.
  - `gdx-freetype` — randare de fonturi `.ttf` (Pixeloid Sans, m5x7).
  - `gdx-backend-lwjgl3` — backend-ul desktop (Windows/Linux/macOS).
  - `gdx-backend-android` — backend-ul nativ Android.
  - `gdx-backend-headless` — rulează logica jocului fără fereastră și fără context grafic; folosit atât pentru teste (`runHeadless`), cât și de motorul autoritativ de gameplay de pe server.
  - `gdx-controllers 2.2.4` — suport pentru gamepad-uri (Xbox/PlayStation), pe desktop și Android.
- **Straturi grafice proprii (`gfx.*`)** — randare, coliziuni, fonturi și efecte construite peste fundația libGDX, nu un motor gata făcut.
- **Shadere GLSL proprii** — pipeline de iluminat/umbre/bloom, cu două variante de calitate (completă, cu raymarching, și simplificată), comutate automat în funcție de setarea de calitate grafică.
- **Tiled** — editor extern folosit pentru construirea hărților (`.tmx`/`.tsx`), încărcate direct de motor.

## Build și organizare proiect

- **Gradle** (multi-modul) — orchestrează build-ul pentru cele trei componente:
  - `core` — logica jocului și stratul grafic, independent de platformă (bibliotecă Java).
  - `desktop` — lansator LWJGL3 + task-uri de împachetare (jpackage, Inno Setup).
  - `android` — lansator nativ Android (Android Gradle Plugin 9.2.1, `compileSdk 34`, `minSdk 26`, țintă arm64-v8a).
- **jpackage + Inno Setup** — generarea instalatorului `.exe` pentru Windows.
- **Sprite atlas packing** — task Gradle propriu (`gradle/assets.gradle`) pentru împachetarea sprite-urilor la build-time.

## Multiplayer online (server-autoritativ)

- **Python 3 + asyncio** — serverul de multiplayer (`SERVERS/multiplayer_server/server.py`), rulând la un tick rate de 20 Hz, pentru până la 8 jucători simultan pe aceeași hartă.
- **Protocol propriu, criptat** — schimb de chei RSA, sesiune criptată AES-GCM, protecție anti-replay (bibliotecă Python `cryptography`).
- **Subproces Java (`engine.jar`)** — serverul lansează motorul de gameplay compilat din `core`, rulat headless prin libGDX, ca arbitru autoritativ; astfel serverul și clientul rulează exact aceeași logică de joc, eliminând desincronizările.
- **Dashboard de administrare** — panou web intern (`dashboard.py`), accesibil doar prin loopback/tunel SSH.

## Multiplayer local (offline)

- **Bluetooth Low Energy (BLE)** — API-uri native Android; un telefon devine server GATT (gazdă), celălalt se conectează ca GATT central, fără server extern sau internet.
- **NFC (Near Field Communication)** — folosit pentru invitații la joc (transmiterea adresei BLE, a unui token de sesiune și a hărții curente) și pentru adăugarea rapidă de prieteni, printr-un simplu tap între telefoane.

## Servere cloud (salvări, licențiere, actualizări)

- **save_server** (Python/asyncio) — sincronizarea salvărilor în cloud și activarea licențelor; expune un port public (5005) și un API intern de licențiere (5105), izolat în rețeaua internă.
- **patch_server** (Python/asyncio) — distribuție de actualizări prin patch-uri semnate criptografic.
- **multiplayer_server** — vezi secțiunea de mai sus.
- **Docker + Docker Compose** — toate cele trei servere rulează ca servicii containerizate, pe o rețea `bridge` internă dedicată, cu stare persistentă (baze de date, loguri, configurări) montată de pe host.
- **cryptography (PyPI)** — bibliotecă Python folosită pentru toate operațiile criptografice (RSA, AES-GCM, semnarea patch-urilor).
- **SQLite** — stocarea salvărilor și a datelor de licențiere (`saves.db`).

## Instrumente și mediu de dezvoltare

- **Git / GitHub** — control de versiuni.
- **Visual Studio Code** — mediu principal de dezvoltare.
- **Licență MIT** — proiectul este licențiat open-source sub MIT.
