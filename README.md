# Echoes of the Heir

**Echoes of the Heir** este un joc 2D de tip RPG (Role Playing Game), cu elemente de aventură și puzzle-uri, dezvoltat integral de la zero — inclusiv motorul de joc — de doi elevi de liceu.

## Autori

- **Ciucă Andrei Corneliu**
- **Lupu Iulian Nicolae**

**Unitate de învățământ:** Liceul „Atanasie Marienescu” Lipova
**Profesor coordonator:** Prof. Recheștean Dorina

## Descriere generală

Jocul este un RPG cu elemente de aventură și puzzle-uri. Scopul jocului este de a ajunge la finalul fiecărei hărți, de a colecta obiecte și de a învinge inamici, urmărind povestea lui Michi, cu hărți multiple, NPC-uri și inamici.

Ideea proiectului a pornit de la o observație simplă: majoritatea jocurilor RPG 2D independente rămân experiențe strict single-player, iar componenta socială — jocul alături de un prieten — este fie absentă, fie condiționată de conturi, internet stabil și configurări greoaie. Echoes of the Heir a fost gândit din start ca un joc care poate fi jucat atât online, cu prietenii, cât și offline, față în față, fără parole, email-uri sau username-uri de reținut și fără conexiune la internet — pur și simplu apropiind două telefoane.

Jocul include și un sistem de licențiere online, care permite utilizarea jocului doar pe un număr limitat de dispozitive per achiziție.

---

## Ce cuprinde jocul

- **Poveste și lume proprii** — hărți multiple, NPC-uri, quest-uri și inamici, toate definite declarativ (nu hardcodate în motor).
- **Motor grafic propriu**, construit peste libGDX, cu iluminat dinamic, umbre randate pe GPU și efecte de tip bloom, adaptabil pe trei nivele de calitate grafică (de la telefoane mai modeste până la desktop-uri performante).
- **Multiplayer online**, server-autoritativ, cu până la 8 jucători simultan pe aceeași hartă.
- **Multiplayer local prin Bluetooth (BLE) pentru utilizatorii de versiune mobile**, complet offline, fără cont și fără internet.
- **Invitație la joc prin tap NFC** între telefoane — fără introducerea manuală de IP-uri sau coduri.
- **Listă de prieteni** și reconectare automată pentru salvările din cloud.
- **Sistem de licențiere online**, legat de achiziția jocului.
- **Sistem de actualizări automate (patch server)** — jocul se auto-actualizează prin patch-uri binare semnate criptografic, fără a necesita reinstalare completă.
- **Suport pentru mod-uri în Lua**, cu un API dedicat și izolare față de sistemele critice (cont, salvări, server).
- **Sistem de anti-cheat/monitorizare a anomaliilor** pe partea de server, cu semnalarea conturilor suspecte într-un dashboard de administrare.

---

## Inovații aduse

### Multiplayer local prin BLE, fără internet

Pe lângă multiplayer-ul clasic prin server, jocul oferă o cale complet separată de a juca împreună, construită pentru situația foarte comună în care doi prieteni sunt în aceeași cameră, dar nu au (sau nu pot folosi) o conexiune la internet stabilă. Un telefon devine „gazdă" și pornește un server Bluetooth Low Energy local (rol GATT server), celălalt se conectează direct la el (rol GATT central). Nu este nevoie de cont, de licență sau de criptare complexă — sesiunea trăiește strict pe distanța de acoperire Bluetooth, exact cât timp cei doi jucători sunt aproape unul de celălalt.

### Invitație prin tap NFC, nu prin adrese

În loc ca jucătorii să introducă manual adrese sau coduri de sesiune, invitația la o partidă locală se face printr-un simplu tap NFC între cele două telefoane: gazda transmite prin NFC adresa sa Bluetooth, un token de sesiune și harta pe care se joacă, iar telefonul invitat se conectează automat la sesiunea BLE corespunzătoare. Practic, „a te alătura unui prieten" devine un gest fizic — apropii telefoanele — nu un formular de rețea. Același mecanism NFC este folosit și pentru adăugarea rapidă de prieteni în joc, printr-un simplu tap între conturi. Acest feature permite
jucătorilor să poată să interacționeze pe aceeași lume, fără restricțiile impuse de firewall-uri sau de rețea. 

### Două căi de multiplayer, o singură experiență

Cele două căi de multiplayer — server online, versus BLE+NFC local, instant și fără cont — coexistă în același joc și sunt randate prin același sistem, astfel încât experiența jucătorului rămâne identică indiferent cum s-a conectat. Scopul a fost să nu punem jucătorii în fața unei alegeri „joc singur" sau „joc online cu cont", ci să le oferim și o a treia variantă, gândită special pentru momentul în care doi prieteni sunt pur și simplu unul lângă altul.

---

## Arhitectură și tehnologii

### Client (jocul propriu-zis)

Echoes of the Heir este scris integral în **Java 17** și construit peste **libGDX**, un framework open-source pentru dezvoltarea de jocuri, ales pentru randarea pe GPU prin OpenGL, portabilitatea pe mai multe platforme (desktop și Android, din același cod de bază) și controlul fin pe care îl oferă asupra motorului de joc. Spre deosebire de un motor gata construit, întreg stratul de randare, coliziune, fonturi și efecte grafice (`gfx.*`) a fost proiectat de la zero peste fundația oferită de libGDX.

Proiectul este organizat modular, pe trei componente Gradle:

| Modul | Rol |
|---|---|
| `core` | Logica jocului și stratul grafic, independent de platformă |
| `desktop` | Lansator LWJGL3, pentru Windows/Linux/macOS |
| `android` | Lansator nativ Android (arm64-v8a, minSdk 26, targetSdk 34) |

Hărțile sunt construite în editorul extern **Tiled** (`.tmx`/`.tsx`) și încărcate direct de motor, iar quest-urile, NPC-urile și obiectele sunt definite declarativ, în fișiere **JSON**, permițând extinderea conținutului fără a modifica motorul de bază.

Pentru iluminat, umbre și efecte de tip bloom, jocul folosește **shadere GLSL** scrise de la zero, cu două variante de calitate — una completă, cu umbre randate prin raymarching, și una simplificată, pentru dispozitive mai puțin performante — comutate automat în funcție de nivelul de calitate grafică ales.

### Multiplayer online

Componenta de multiplayer online rulează pe un **server Python (asyncio)**, care comunică cu clientul Java printr-un protocol propriu, criptat (schimb de chei RSA, sesiune criptată AES-GCM, protecție anti-replay), la un tick rate de **20 Hz**, pentru până la 8 jucători simultan pe aceeași hartă. Pentru a garanta că serverul și clientul nu ajung niciodată să calculeze rezultate diferite în luptă, serverul lansează un subproces Java (`engine.jar`) care reutilizează exact motorul de joc al clientului, rulat headless prin libGDX, ca arbitru autoritativ.

### Multiplayer local (BLE/NFC)

Multiplayer-ul local folosește API-urile native **Bluetooth Low Energy (BLE)** și **Near Field Communication (NFC)** de pe Android, fără nicio dependență de server sau de internet.

### Servere cloud (salvări, licențiere, actualizări)

| Server | Rol |
|---|---|
| `save_server` | Sincronizarea salvărilor în cloud și activarea licențelor; expune un port public (5005) și un API intern de licențiere (5105), izolat în rețeaua internă |
| `patch_server` | Distribuție de actualizări prin patch-uri binare, semnate criptografic (RSA-2048 + SHA-256) |
| `multiplayer_server` | Serverul autoritativ de joc descris mai sus, plus un dashboard de administrare accesibil doar prin loopback/tunel SSH |

Toate cele trei servere rulează ca servicii containerizate prin **Docker + Docker Compose**, pe o rețea internă dedicată, cu stare persistentă (baze de date, loguri, configurări) montată de pe host. Salvările și datele de licențiere sunt stocate în **SQLite**.

### Sistem de licențiere

Modelul de licențiere folosește **itch.io** ca punct unic de verificare a achiziției: la prima pornire, jucătorul se autentifică o singură dată prin OAuth în browser, iar jocul trimite un token către `save_server`, care verifică achiziția direct cu API-ul itch.io. Din acel moment, licența aparține serverului propriu — itch.io nu mai este contactat niciodată, iar jocul continuă să funcționeze offline, fără aplicația itch, la nesfârșit. Clientul nu stochează niciodată cheia de licență în clar, ci doar un `activation_id` și un bloc criptat pe care numai serverul de salvări îl poate descifra.

### Sistem de actualizări (patch server)

La fiecare pornire, jocul verifică automat versiunea instalată față de cea publicată pe `patch_server`. Dacă există o actualizare, se descarcă doar un patch binar (diferența dintre JAR-ul vechi și cel nou, nu întregul joc), verificat prin hash SHA-256 și semnătură RSA-2048, apoi este aplicat de un proces separat (`Updater`) care înlocuiește atomic fișierul jocului și îl relansează. Dacă serverul de patch-uri nu este accesibil, jocul pornește normal (funcționează și offline).

### Mod-uri (Lua)

Jocul suportă mod-uri scrise în **Lua** (via LuaJ), încărcate automat dintr-un folder `/mods` de lângă executabil. Mod-urile pot adăuga monștri, obiecte, NPC-uri și comportamente de AI proprii, și pot interacționa cu jocul prin reflecție asupra claselor de gameplay — dar nu pot atinge sistemul de cont/login, serverele de multiplayer și salvări, salvările criptate sau cheile criptografice, care rămân complet izolate (a se vedea [`MODDING.md`](MODDING.md)).

--- 

## Cerințe de sistem

- Conexiune la internet — necesară pentru multiplayer online, salvări în cloud și activarea licenței (**nu** este necesară pentru multiplayer local prin BLE/NFC, odată ce licența a fost deja activată).
- Windows, Linux sau macOS (pentru versiunea desktop), sau Android 8.0 ori o versiune mai nouă (pentru versiunea mobilă).
- Bluetooth și NFC, pentru funcțiile de multiplayer local și adăugare rapidă de prieteni (versiunea Android).

---

## Componente care nu au fost realizate de autori

1. Sprite-urile/Tileset-urile din `/res/bosses`, `/res/consumables`, `/res/fonts`, `/res/monster`, `/res/NPC`, `/res/objects`, `/res/quest_items`, `/res/shields`, `/res/tiles` au fost preluate de pe site-ul [craftpix.net](https://craftpix.net/), folosind o licență de utilizare indie/comercială.
2. Sunetele din `/res/sound`, cu excepția folderelor `/piano_soundtrack` și `/Soundtracks`.
3. Biblioteca `jbsdiff-1.0.jar`, preluată din proiectul [jbsdiff](https://github.com/malensek/jbsdiff).

---

## Rolul fiecărui membru al echipei

**Ciucă Andrei Corneliu:**
- Dezvoltator interfață grafică, integrarea cu aplicația Tiled
- Crearea hărților
- Implementarea poveștii
- Dezvoltator engine

**Lupu Iulian Nicolae:**
- Dezvoltator server și sistem de licențiere
- Dezvoltator back-end
- Integrarea serverului cu sistemul de operare Linux
- Dezvoltator engine

---

## Documentație suplimentară

Documentație tehnică detaliată, în limba română, se găsește în [`core/guides/`](core/guides/), printre care:

- [`PREZENTARE_GENERALA_RO.md`](core/guides/PREZENTARE_GENERALA_RO.md) — prezentare generală a proiectului
- [`TEHNOLOGII_FOLOSITE_RO.md`](core/guides/TEHNOLOGII_FOLOSITE_RO.md) — lista completă a tehnologiilor folosite
- [`BACKEND_SERVERE_SI_CLIENT_RO.md`](core/guides/BACKEND_SERVERE_SI_CLIENT_RO.md) — arhitectura serverelor și a clientului
- [`LICENSING_SISTEM_RO.md`](core/guides/LICENSING_SISTEM_RO.md) — detalii despre sistemul de licențiere
- [`LIBGDX_DOCUMENTATIE_RO.md`](core/guides/LIBGDX_DOCUMENTATIE_RO.md) — documentație despre integrarea cu libGDX
- [`QUEST_GUIDE.md`](core/guides/QUEST_GUIDE.md), [`NPC_JSON_GUIDE.md`](core/guides/NPC_JSON_GUIDE.md), [`TILED_ENTITY_GUIDE.md`](core/guides/TILED_ENTITY_GUIDE.md) — ghiduri de conținut (quest-uri, NPC-uri, hărți)
- [`MODDING.md`](MODDING.md) — ghid complet pentru scrierea de mod-uri
- [`SERVERS/LICENSING.md`](SERVERS/LICENSING.md) — ghid de deployment pentru licențiere și multiplayer
- [`SERVERS/patch_server/PATCH_SERVER.md`](SERVERS/patch_server/PATCH_SERVER.md) — funcționarea internă a serverului de patch-uri

---

## Declarație

Autorii declară că proiectul reprezintă o creație proprie, iar toate resursele externe utilizate sunt menționate în prezentul document.
