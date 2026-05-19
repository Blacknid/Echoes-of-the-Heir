# Documentație Tehnică și de Design — *Michi's Adventure / Echoes of the Heir*

**Autori:** Ciuca Andrei-Corneliu, Lupu Iulian-Nicolae  
**Design:** Avram Dennis-Sebastian, Lupu Mirabela  
**Scenariu:** Lupu Cristian  
**Muzică:** Lupu Stefan  
**Data:** Mai 2026  
**Versiune document:** 1.0

---

## Cuprins

1. [Prezentare generală a proiectului](#1-prezentare-generală-a-proiectului)
2. [Arhitectura motorului de joc (Game Engine)](#2-arhitectura-motorului-de-joc-game-engine)
   - 2.1 [Structura pachetelor](#21-structura-pachetelor)
   - 2.2 [Descrierea detaliată a pachetelor](#22-descrierea-detaliată-a-pachetelor)
   - 2.3 [Clasa centrală — GamePanel](#23-clasa-centrală--gamepanel)
3. [Bucla principală de joc (Game Loop)](#3-bucla-principală-de-joc-game-loop)
   - 3.1 [Modelul fix UPS / variabil FPS](#31-modelul-fix-ups--variabil-fps)
   - 3.2 [V-Sync și controlul FPS](#32-v-sync-și-controlul-fps)
   - 3.3 [Stările jocului](#33-stările-jocului-game-states)
   - 3.4 [Camera](#34-camera)
   - 3.5 [Gestionarea evenimentelor in-lume — EventHandler](#35-gestionarea-evenimentelor-in-lume--eventhandler)
4. [Sistemul de entități (Entity System)](#4-sistemul-de-entități-entity-system)
5. [Personajul jucătorului (Player)](#5-personajul-jucătorului-player)
6. [Sistemul de combatere (Combat System)](#6-sistemul-de-combatere-combat-system)
7. [Inteligența artificială (AI)](#7-inteligența-artificială-ai)
8. [Sistemul de hărți și tile-uri](#8-sistemul-de-hărți-și-tile-uri)
9. [Sistemul de coliziuni](#9-sistemul-de-coliziuni)
10. [Sistemul de misiuni (Quest System)](#10-sistemul-de-misiuni-quest-system)
11. [Arborele de abilități (Skill Tree)](#11-arborele-de-abilități-skill-tree)
12. [Sistemul de salvare și încărcare](#12-sistemul-de-salvare-și-încărcare)
13. [Sistemul de mediu și efecte vizuale](#13-sistemul-de-mediu-și-efecte-vizuale)
14. [Sistemul audio](#14-sistemul-audio)
15. [Interfața cu utilizatorul (UI)](#15-interfața-cu-utilizatorul-ui)
16. [Sistemul multiplayer](#16-sistemul-multiplayer)
17. [Sistemul de licențiere și securitate](#17-sistemul-de-licențiere-și-securitate)
18. [Optimizări de performanță](#18-optimizări-de-performanță)
19. [Sistemul de actualizare automată](#19-sistemul-de-actualizare-automată)
20. [Povestea și narațiunea](#20-povestea-și-narațiunea)
21. [Structura proiectului și tehnologiile utilizate](#21-structura-proiectului-și-tehnologiile-utilizate)
22. [Concluzii](#22-concluzii)

---

## 1. Prezentare generală a proiectului

*Michi's Adventure*, cunoscut și sub titlul narativ *Echoes of the Heir*, reprezintă un joc de tip **Action RPG 2D top-down**, dezvoltat integral în limbajul de programare **Java**, utilizând biblioteca grafică **Java Swing / Java2D**. Proiectul constituie o implementare de la zero a unui motor de joc complet, fără dependențe externe de framework-uri comerciale de tip LibGDX sau Unity.

Jocul se desfășoară la o rezoluție internă fixă de **1280×720 pixeli** (raport de aspect 16:9), cu tile-uri de **32×32 pixeli nativi** scalate cu un factor de **2.0×**, rezultând tile-uri de **64×64 pixeli** la runtime. Simularea jocului rulează la o rată fixă de **60 actualizări pe secundă (UPS)**, independent de rata de cadre (FPS), asigurând astfel o comportare deterministă a fizicii și logicii de joc pe orice mașină.

###   

| Caracteristică | Detalii |
|---|---|
| Gen | Action RPG top-down 2D |
| Limbaj | Java SE (fără engine extern) |
| Rezoluție | 1280×720, scalabilă la fullscreen |
| Tile size | 32px nativi × 2.0 = 64px runtime |
| FPS țintă | 60 (VSync configurbil) |
| Dimensiune hartă maximă | 100×100 tile-uri (6400×6400 px) |
| Sistem de combatere | Combo sistem 3 pași cu abilități speciale |
| Salvare | Criptată local + cloud save opțional |
| Multiplayer | TCP/IP cu criptare AES-256-GCM |

---

## 2. Arhitectura motorului de joc (Game Engine)

### 2.1 Structura pachetelor

Motorul este organizat în pachete Java distincte, fiecare cu responsabilitate clară:

```
main/       — nucleul motorului (GamePanel, Main, KeyHandler, Config, CollisionChecker)
entity/     — sistemul de entități (Entity, Player, NPC_Generic, BossMonster, Projectile, Particle)
tile/       — managementul tile-urilor și hărților TMX (TileManager, interactiveTile)
map/        — logica hărților (MapManager, AssetSetter, EventHandler, MobSpawner, MapObjectLoader)
ai/         — inteligența artificială (PathFinder, Node)
data/       — date și persistență (SaveLoad, GameState, MonsterFactory, NPCFactory, LicenseManager)
audio/      — sistem audio (AudioManager, Sound, SFX)
ui/         — interfața utilizator (UI, RenderPipeline, Minimap, CutsceneManager, ScreenShake)
environment/— efecte de mediu (EnvironmentManager, Lightning, MapShaderManager, TileParticleEmitter)
object/     — obiectele din lume (OBJ_Chest, OBJ_Door, OBJ_Potion, OBJ_Key etc.)
util/       — utilitare (ResourceCache, ObjectPool, UtilityTool)
update/     — actualizare automată (UpdateClient, Updater)
```

### 2.2 Descrierea detaliată a pachetelor

#### `main/`
Conține nucleul dur al motorului. `GamePanel` este clasa centrală care extinde `JPanel` și implementează `Runnable`. `Main` creează fereastra `JFrame` nedecorated, setează proprietățile JVM pentru accelerare hardware și lansează thread-ul de joc. `KeyHandler` implementează `KeyListener` și expune câmpuri booleene pentru fiecare acțiune (`upPressed`, `dashPressed`, `shockwavePressed` etc.), inclusiv un sistem propriu de **menu key-repeat** cu delay inițial de 10 cadre și rată de repetare de 4 cadre, eliminând dependența de rata de repetare a sistemului de operare. `Config` centralizează toate constantele de configurare (tile size, scale, calitate grafică, FPS target) și le persistă în `config.txt`.

`CollisionChecker` gestionează toate testele de coliziune folosind un **spatial grid** de celule de 128 px. `AssetValidator` verifică integritatea resurselor la startup. `QuestManager` gestionează misiunile active. `SkillTree` parsează `skilltree.json` și menține starea de deblocare a nodurilor. `ServerListManager` persistă lista serverelor multiplayer în `servers.txt` cu formatul `name|ip|port`. `MpMapStreamer` preia hărți de pe serverul multiplayer prin chunk-uri comprimate GZIP.

#### `entity/`
Ierarhia completă de entități. `Entity` este clasa de bază cu toate câmpurile comune (poziție, coliziune, statistici, efecte de status, animații). `Player` extinde `Entity` cu logica de input, combo sistem, abilități active și pasive, animații de lovire/moarte și camera cu lerp. `NPC_Generic` extinde `Entity` cu logica de dialog, activitate și navigare bazată pe waypoint-uri. `BossMonster` și `BOSS_WitheredTree` implementează comportamente AI în faze. `Projectile` implementează interfața `Poolable` pentru reutilizare prin `ObjectPool`. `DamageNumber`, `Particle` și `BossSwingEffect` sunt efecte vizuale tranzitorii gestionate prin pool-uri.

#### `tile/`
`TileManager` parsează fișierele TMX prin DOM (`javax.xml`), construiește tablouri de GID-uri per strat, rezolvă animațiile de tile-uri, straturile de imagini cu parallax, opacitatea și tinta per strat, și exportă forme de coliziune (`collisionShapes`, `collisionBounds`, `lightOccluderRects`). Constantele de flip (`GID_FLIP_H = 0x80000000L`, `GID_FLIP_V = 0x40000000L`, `GID_FLIP_D = 0x20000000L`) sunt aplicate la randare prin `AffineTransform`. `interactiveTile` reprezintă tile-uri distrugibile (`IT_Pot`, `IT_Coins`).

#### `map/`
`MapManager` menține registrul de hărți și persistența stării entităților între tranziții. `MapObjectLoader` parcurge layer-ele de obiecte din TMX și instanțiază entitățile prin factory-uri. `EventHandler` gestionează toate evenimentele declanșate de poziția jucătorului: `MapTransition`, `HealingPool`, `DamageTrap`, `DialogueTrigger`, `LevelGate`, `Checkpoint`, `QuestTrigger`, `CameraShake`, `MemoryGate`, `ThoughtTrigger`, `FragmentTrigger`, zone de apă (`waterZones`) și zone de spawn (`SpawnZoneData`). `MobSpawner` generează inamici dinamic din zone `MobSpawnerZone` definite în Tiled. `AssetSetter` deleghează încărcarea entităților și a evenimentelor la `MapObjectLoader`.

#### `data/`
`SaveLoad` criptează/decriptează fișierele de salvare cu AES-128-CBC. `GameState` este clasa `Serializable` ce conține tot statul persistent al jocului. `MonsterFactory` și `NPCFactory` instanțiază entități din `monsters.json` și `npcs.json`. `ItemFactory` creează obiecte de inventar din `items.json`. `LicenseManager` verifică licența RSA-2048 și menține watchdog-ul. `CloudSaveService` implementează sincronizarea cu serverul de salvare. `MemoryJournal` stochează și sortează fragmentele de memorie după `storyOrder`.

#### `audio/`
`AudioManager` centralizează redarea prin două instanțe `Sound` (muzică cu loop / efecte one-shot). Volumul este controlat pe scara 0–5. `SFX` definește toate constantele pentru indexurile fișierelor audio.

#### `ui/`
`RenderPipeline` implementează pipeline-ul de randare stratificat cu Z-sorting bazat pe picioare. `UI` randează HUD-ul, meniurile și dialogurile. `Minimap` randează minimapa circulară cu vignette radial. `CutsceneManager` gestionează scenele cinematice cu efect typewriter și pan de cameră. `ScreenShake` aplică un offset aleatoriu matricei de transformare. `ThoughtBubble` afișează gânduri non-blocante ale jucătorului.

#### `environment/`
`EnvironmentManager` gestionează ciclul zi/noapte și sistemul meteorologic. `Lightning` aplică masca de umbră cu gradient radial pentru iluminarea nocturnă. `MapShaderManager` implementează shader-ele water shimmer (tabelă LUT sinusoidal), ambient particles, vignette, color grading și sepia mode. `TileParticleEmitter` generează particule de suprafață la pașii jucătorului. `MemoryFlashback` declanșează scena sepia cu typewriter la colectarea unui fragment.

#### `object/`
Toate obiectele din lume au clase dedicate: `OBJ_Chest` (ladă cu loot), `OBJ_Door` (ușă cu destinație configurabilă), `OBJ_Torch` (sursă de lumină), `OBJ_Tower` (turn defensiv), `OBJ_Tent` (punct de recuperare), `OBJ_Potion` (consumabil), `OBJ_Key` / `OBJ_Gem` / `OBJ_Coins` / `OBJ_Heart` / `OBJ_ManaCrystal` / `OBJ_Arrow` (colectabile).

#### `util/`
`ObjectPool<T>` generic pentru reutilizarea obiectelor `Poolable`. `ResourceCache` — cache thread-safe pentru `BufferedImage` și `Document` XML. `UtilityTool` — metode utilitare pentru scalarea imaginilor și alte operații comune.

#### `update/`
`UpdateClient` verifică serverul de patch-uri la fiecare pornire. `Updater` aplică patch-urile ZIP după ce procesul-părinte se închide.

---

### 2.3 Clasa centrală — `GamePanel`

`GamePanel` extinde `javax.swing.JPanel` și implementează `Runnable`, constituind nucleul motorului. Aceasta deține referințe la toate subsistemele și coordonează inițializarea, actualizarea și randarea întregii aplicații.

**Constante de configurare ale ferestrei:**

```java
public final int screenWidth  = 1280;   // rezoluție fixă
public final int screenHeight = 720;    // raport 16:9
public final int maxWorldCol  = 100;    // dimensiune maximă hartă
public final int maxWorldRow  = 100;
private static final int TARGET_UPS = 60; // simulare fixă
```

**Butoane de control fereastră (window control buttons):** Fereastra este mereu `undecorated`, iar butoanele de minimizare, maximizare și închidere sunt desenate direct pe panel, cu dimensiunea `WCB_SIZE = 18 px`, distanțate de `WCB_GAP = 4 px` de la marginea superioară (`WCB_TOP = 5`). Starea de hover este urmărită printr-un câmp `volatile Point wcbHover` actualizat din thread-ul Swing.

**Moduri de grafică:** `Config` expune trei niveluri de calitate grafică:
- `GRAPHICS_LOW (0)` — lumini pătrate, fără umbre
- `GRAPHICS_MEDIUM (1)` — lumini circulare, fără umbre
- `GRAPHICS_HIGH (2)` — lumini circulare cu umbre (implicit)

**Debug panel (F9):** `debugMenuOpen` activează un panou de debug cu `debugMapList` pentru teleportare rapidă la orice hartă, plus opțiuni de reload live pentru NPC-uri, monștri și obiecte fără recompilare (`pendingReloadNPCs`, `pendingReloadMonsters`, `pendingReloadObjects` — câmpuri `volatile` consumate de game loop la începutul `update()`).

Subsistemele instanțiate direct de `GamePanel`:

- `TileManager` — parsarea și randarea tile-urilor TMX
- `KeyHandler` — capturarea evenimentelor de tastatură
- `AudioManager` — redarea muzicii și efectelor sonore
- `CollisionChecker` — detectarea coliziunilor cu spatialgrid
- `AssetSetter` — plasarea obiectelor și NPC-urilor pe hartă
- `MapObjectLoader` — încărcarea entităților din fișierele Tiled TMX
- `UI` — randarea interfeței utilizator
- `EventHandler` — gestionarea evenimentelor de tip trigger pe hartă
- `CutsceneManager` — scenele cinematice
- `PathFinder` — algoritmul A* pentru deplasarea inamicilor
- `EnvironmentManager` — ciclul zi/noapte și meteo
- `MapShaderManager` — efecte vizuale de tip shader
- `ScreenShake` — efectul de tremur al ecranului
- `MobSpawner` — generarea dinamică a inamicilor
- `SaveLoad` — salvarea și încărcarea progresului
- `QuestManager` — sistemul de misiuni
- `MemoryJournal` — jurnalul de fragmente de memorie
- `RenderPipeline` — pipeline-ul de randare separat de logică
- `MapManager` — gestionarea tranzițiilor de hartă

---

## 3. Bucla principală de joc (Game Loop)

### 3.1 Modelul fix UPS / variabil FPS

Bucla principală de joc utilizează un model de tip **fixed timestep** pentru actualizarea logicii și **uncapped / VSync** pentru randare, separând astfel rata de simulare de rata de afișare:

```
TARGET_UPS = 60     (actualizări pe secundă — fixe)
FPS = 60            (cadre randate pe secundă — variabile)
```

Implementarea se bazează pe calcul temporal cu nanoseunde (`System.nanoTime()`), asigurând că logica de joc avansează cu exact 1/60 secundă per tick, indiferent de viteza hardware-ului. Dacă randarea este mai lentă decât simularea, actualizările se acumulează și se procesează consecutiv (*catch-up loop*).

### 3.2 V-Sync și controlul FPS

Bucla suportă trei moduri de randare, configurabile din meniu:

| Mod | Comportament |
|---|---|
| `vSyncOn = true` | Randarea este sincronizată cu rata de refresh a monitorului (`monitorRefreshRate`, detectat la startup) |
| `vSyncOn = false, fpsTarget = 60` | Randare la target fix de 60 FPS, cu sleep bazat pe `System.nanoTime()` |
| `vSyncOn = false, fpsTarget = 0` | Randare neînglobată (uncapped) — afișare maximă posibilă |

`maxFPS` reține FPS-ul maxim atins în sesiunea curentă, util pentru profilare. `tickCounter` este un contor monoton de actualizări, utilizat pentru operații ce nu trebuie executate la fiecare cadru (ex: verificări de stagnare AI, actualizări meteo).

### 3.3 Stările jocului (Game States)

`GamePanel` menține o mașină de stări prin variabila `gameState`, cu constantele:

| Constantă | Valoare | Descriere |
|---|---|---|
| `titleState` | 0 | Ecranul de titlu / meniu principal |
| `playState` | 1 | Joc activ — single player |
| `pauseState` | 2 | Jocul este în pauză |
| `dialogueState` | 3 | Dialog activ cu un NPC |
| `characterState` | 4 | Ecranul de personaj / inventar |
| `optionsState` | 5 | Meniu opțiuni |
| `gameOverState` | 6 | Ecranul de Game Over |
| `cutsceneState` | 7 | Scenă cinematică în rulare |
| `transitionState` | 8 | Tranziție între hărți (fade) |
| `levelUpState` | 9 | Alegerea bonusului la level-up |
| `skillTreeState` | 10 | Arborele de abilități |
| `multiplayerPlayState` | 11 | Joc activ — multiplayer |
| `journalState` | 12 | Jurnalul de fragmente de memorie |

Fiecare stare determină ce subsisteme sunt actualizate și randate în cadrul fiecărei iterații a buclei de joc.

### 3.4 Camera

Camera urmărește jucătorul prin interpolere liniară (lerp), cu un factor `CAM_LERP = 0.15f`, asigurând o mișcare fluidă. Sistemul suportă și **camera lock** pentru scene cinematice, prin care camera poate fi ancorată la coordonate fixe ale lumii (tile) fără a mai urma jucătorul:

```java
public void lockCamera(int tileCol, int tileRow) {
    cameraWorldX = tileCol * tileSize;
    cameraWorldY = tileRow * tileSize;
    cameraLocked = true;
}
```

Câmpurile `camScreenX` / `camScreenY` din `Player` sunt tipul `float` și interpolare cadru-cu-cadru față de ținta calculată. La fiecare `update()`, camera verifică limitele lumii (`worldWidth`, `worldHeight`) și clampează poziția pentru a nu ieși din hartă.

### 3.5 Gestionarea evenimentelor in-lume — `EventHandler`

`EventHandler` înregistrează toate evenimentele din stratul `Events` al fișierelor TMX și le evaluează la fiecare actualizare prin compararea hitbox-ului jucătorului cu dreptunghiurile pixel-precise din Tiled. Tipurile de evenimente suportate:

| Tip eveniment | Comportament |
|---|---|
| `MapTransition` | Schimbă harta activă; rezolvă `spawnId` sau coordonate fixe |
| `HealingPool` | Restaurează HP/MP la apăsarea `ENTER`; salvează progresul |
| `DamageTrap` | Aplică daune la pas; opțional `repeatable` |
| `DialogueTrigger` | Afișează un mesaj de dialog; opțional `oneShot` |
| `LevelGate` | Blochează trecerea sub `minLevel` |
| `Checkpoint` | Salvare silențioasă + restaurare HP/MP fără prompt |
| `QuestTrigger` | Avansează un quest cu o cantitate specificată |
| `CameraShake` | Declanșează `ScreenShake` cu intensitate configurabilă |
| `MemoryGate` | Blochează trecerea până la colectarea unui fragment de memorie |
| `ThoughtTrigger` | Afișează un gând non-blocant al jucătorului prin `ThoughtBubble` |
| `FragmentTrigger` | Acordă un fragment de memorie la intrare în zonă |
| `WaterZone` | Zona de apă care reduce viteza jucătorului |

Câmpul `canTouchEvent` previne declanșarea repetată imediată a aceluiași eveniment atâta timp cât jucătorul rămâne în aceeași celulă; evenimentul se rearmează la deplasarea pe o celulă diferită.

---

## 4. Sistemul de entități (Entity System)

### 4.1 Clasa de bază `Entity`

Toate obiectele interactive din lumea jocului — jucătorul, NPC-urile, inamicii, proiectilele și particulele — derivă din clasa de bază `Entity`. Aceasta implementează un model de compoziție prin câmpuri publice, oferind flexibilitate maximă pentru toate tipurile de entități.

**Atribute principale ale clasei `Entity`:**

```
worldX, worldY          — poziția în spațiul lumii (pixeli)
solidArea               — dreptunghiul de coliziune (Rectangle)
attackArea              — zona de atac (Rectangle)
type                    — tipul entității (TYPE_PLAYER, TYPE_NPC, TYPE_MONSTER etc.)
alive, dying            — starea de viață
direction               — direcția curentă (DIR_DOWN=0, DIR_LEFT=1, DIR_RIGHT=2, DIR_UP=3)
speed, defaultSpeed     — viteza curentă și cea implicită
maxLife, life           — punctele de viață maxime și curente
attack, defense         — statisticile de atac și apărare
exp                     — experiența acordată la moarte
invincible              — flag de invincibilitate temporară (i-frames)
knockBack               — flag de knockback vectorial
```

### 4.2 Sistemul de animație

Entitățile utilizează un sistem de animație bazat pe **sprite sheet-uri** stocate în tablouri bidimensionale:

```java
BufferedImage[][] walkFrames;    // animație de mers [direcție][cadru]
BufferedImage[][] idleFrames;    // animație de repaus [direcție][cadru]
BufferedImage[][] attackFrames;  // animație de atac - pasul 0 al combo-ului
BufferedImage[][] attackFrames2; // animație de atac - pasul 1
BufferedImage[][] attackFrames3; // animație de atac - pasul 2
```

Pe lângă animațiile standard, NPC-urile suportă **animații de activitate** (activity animations) — seturi numite de animații (ex: "forge", "sweep", "sleep") definite în JSON și activate dinamic în funcție de starea NPC-ului.

Avansarea cadrelor se face printr-un contor de tick-uri (`spriteCounter`), incrementat la fiecare actualizare, cu schimbarea cadrului la fiecare `animationFrameInterval` tick-uri.

### 4.3 Efecte de status

`Entity` implementează un set bogat de efecte de status aplicabile oricărei entități:

| Efect | Câmpuri | Descriere |
|---|---|---|
| **Slowed** | `slowed`, `slowedTimer` | Reduce viteza de mișcare la jumătate |
| **Rooted** | `rooted`, `rootedTimer` | Blochează mișcarea complet |
| **Phasing** | `phasing`, `phasingCycleCounter` | Alterne invulnerabilitate ciclică |
| **Frontal Armor** | `frontalArmor` | Blochează 50% din loviturile frontale |

### 4.4 Tipuri de entități

| Constantă | Valoare | Rol |
|---|---|---|
| `TYPE_PLAYER` | 0 | Personajul jucătorului |
| `TYPE_NPC` | 1 | Personaje non-jucător |
| `TYPE_MONSTER` | 2 | Inamici |
| `TYPE_SWORD` | 3 | Armă de tip sabie (echipament) |
| `TYPE_BOOK` | 4 | Carte / armă magică (echipament) |
| `TYPE_SHIELD` | 5 | Scut (echipament) |
| `TYPE_CONSUMABLE` | 6 | Obiecte consumabile (poțiuni etc.) |
| `TYPE_PICKUP_ONLY` | 7 | Obiecte ce pot fi colectate |
| `TYPE_OBSTACLE` | 8 | Obstacole interactive |

---

## 5. Personajul jucătorului (Player)

### 5.1 Caracteristici generale

Clasa `Player` extinde `Entity` și conține întreaga logică a personajului controlat de jucător. Inventarul este implementat ca o `ArrayList<Entity>` cu capacitate maximă de **20 de sloturi**.

Deplasarea jucătorului se realizează pe 4 direcții (sus, jos, stânga, dreapta), cu intrare preluată prin `KeyHandler`. Camera urmărește jucătorul prin variabilele `camScreenX` / `camScreenY`, interpolate cu factorul `CAM_LERP = 0.15f`.

**Cutia de coliziune** a jucătorului este calculată proporțional cu dimensiunea tile-ului, asigurând comportament corect la orice factor de scală:

```java
solidArea.x      = tileSize * 20 / 64;  // 20 px la 64px/tile
solidArea.y      = tileSize * 22 / 64;  // 22 px la 64px/tile
solidArea.width  = tileSize * 24 / 64;  // 24 px lățime
solidArea.height = tileSize * 22 / 64;  // 22 px înălțime
```

**Valorile implicite la pornire** (setate de `setDefaultValues()`):
- `defaultSpeed = 5` pixeli/tick
- `maxLife = 3`, `maxMana = 3`
- `strenght = 2`, `dexterity = 1`
- `level = 100`, `skillPoints = 100` (valori de debug; resetate la nouă partidă)

**Colectabile de inventar:** `hasKey`, `hasArtefact`, `hasGem` — contori separați pentru obiectele speciale ce nu ocupă sloturi de inventar standard.

**Trail de atac (swing afterimages):** Un buffer circular de `TRAIL_SIZE = 4` poziții (`trailWorldX[]`, `trailWorldY[]`) stochează ultimele poziții ale jucătorului în timpul animației de atac, randând afterimage-uri semi-transparente pentru feedback vizual de viteză.

**Animație de idle:** După `idleStartDelayFrames = 120` cadre (2 secunde) fără mișcare, jucătorul intră în animația de idle cu un contor de frame `idleFrameInterval = 10`, care oscilează bidirecțional (`idleFrameDirection = ±1`) pentru un efect de respirație.

### 5.2 Sistemul de animații de moarte și lovire

Jucătorul dispune de animații dedicate pentru lovire și moarte, separate de cele de combatere, stocate în:

```java
BufferedImage[][] hitFrames;    // animație la primirea loviturii [direcție][cadru]
BufferedImage[][] deathFrames;  // animație de moarte [direcție][cadru]
```

Moartea parcurge o secvență controlată:
1. `playerDying = true` — activează animația de moarte
2. Animația avansează cu `DEATH_ANIM_SPEED = 10` tick-uri / cadru
3. Ultimul cadru este ținut pe ecran `DEATH_HOLD_DELAY = 60` tick-uri (1 secundă)
4. Tranziție la `gameOverState`

### 5.3 Abilitățile speciale ale jucătorului

Jucătorul poate debloca și utiliza abilități speciale prin arborele de abilități:

| Abilitate | Constantă cooldown | Durata | Descriere |
|---|---|---|---|
| **Swift Evade (Dash)** | `dashCooldownMax = 38` (~0.63s) | `dashDuration = 10` cadre | Evitare rapidă cu efect de afterimage |
| **Shockwave** | `SHOCKWAVE_COOLDOWN_MAX = 150` | — | Undă de șoc radială în jurul jucătorului |
| **Void Snare** | `VOID_SNARE_COOLDOWN_MAX = 220` | — | Capcană de tip lasso ce imobilizează inamicul |
| **Frost Nova** | `FROST_NOVA_COOLDOWN_MAX = 200` | — | Explozie de gheață cu efect de slow |
| **Overdrive** | `OVERDRIVE_COOLDOWN_MAX = 420` | `OVERDRIVE_DURATION = 180` cadre | Creșterea temporară a puterii de atac |

**Abilități pasive** deblocabile din arborele de abilități:
- `soulReaperUnlocked` — Soul Reaper: bonus la atac
- `berserkerFuryUnlocked` — Berserker Fury: bonus la atac la HP scăzut
- `shadowStepUnlocked` — Shadow Step: invizibilitate la dash
- `manaSiphonUnlocked` — Mana Siphon: regenerare mana la atac
- `manaShieldUnlocked` — Mana Shield: absorbire daunelor cu mana
- `thornsUnlocked` — Thorns: reflectare parțială a daunelor
- `secondWindUnlocked` — Second Wind: recuperare automată la HP critic
- `vampiricStrikeUnlocked` — Vampiric Strike: furt de HP la atac
- `lastStandUnlocked` — Last Stand: bonus masiv de atac la HP critic
- `undyingWillUnlocked` — Undying Will: supraviețuire automată la o lovitură letală

---

## 6. Sistemul de combatere (Combat System)

### 6.1 Sistemul combo de 3 pași

Combaterea corp la corp implementează un **lanț de combo de 3 pași** (light → light → heavy):

```java
private int comboStep = 0;       // pasul curent: 0, 1, 2
private int comboWindow = 0;     // cadre rămase pentru a lansa atacul următor
private static final int COMBO_WINDOW_MAX = 20;  // fereastra de combo
private boolean attackBuffered = false; // input buffer pentru apăsări rapide
```

Fiecare pas al combo-ului utilizează un set diferit de cadre de animație (`attackFrames`, `attackFrames2`, `attackFrames3`) și poate aplica daune diferite. Pasul 2 (heavy) produce un efect de hitstop global (`globalHitstopTimer`) ce îngheață toate entitățile pentru câteva cadre, conferind greutate vizuală loviturii finale.

**Input buffering:** Dacă jucătorul apasă tasta de atac în timp ce animația precedentă se execută, apăsarea este stocată în `attackBuffered` și procesată imediat ce fereastra de combo se deschide, permițând combo-uri fluide.

### 6.2 Anticiparea atacului (Wind-up)

Fiecare atac include o scurtă **anticipare** (wind-up) la primul cadru, realizată prin `anticipationTimer`, care ține animația pe cadrul inițial înainte de a declanșa hitbox-ul. Această tehnică transmite jucătorului feedback vizual înainte ca lovitура să fie executată.

Suplimentar față de anticipare, jucătorul are un **offset vizual de animație** (`animOffsetX`, `animOffsetY`) — o deplasare vizuală în direcția atacului care nu afectează hitbox-ul, creând iluzia că personajul se „apleacă" în lovitură. Valoarea acestui offset este calculată în funcție de direcția atacului și decrementată treptat în cadrele următoare.

### 6.3 Knockback vectorial

Loviturile aplică un **knockback vectorial** bazat pe direcție, nu pe o axă fixă:

```java
public int knockBackVectorX = 0;
public int knockBackVectorY = 0;
public double knockBackRemaining = 0;  // distanța rămasă de parcurs
```

Vectorul de knockback este calculat din poziția sursei loviturii față de poziția entității lovite, asigurând o respingere naturală în orice direcție.

### 6.4 Flash-ul la lovire și telegrafarea atacurilor

- **Hit Flash:** La primirea unei lovituri, entitatea este acoperită cu un overlay alb semi-transparent pentru `HIT_FLASH_DURATION = 6` cadre, semnalizând vizual daunele.
- **Attack Telegraph:** Inamicii afișează un flash roșu (`attackWindupFlash`) înainte de a ataca, oferind jucătorului timp de reacție.
- **Damage Numbers:** Valorile numerice ale daunelor sunt afișate în lume prin obiectele `DamageNumber`, gestionate de un `ObjectPool<DamageNumber>`.

### 6.5 Proiectile

Clasa `Projectile` extinde `Entity` și implementează interfața `Poolable` pentru reutilizare prin `ObjectPool`. Proiectilele verifică coliziunile cu inamicii și cu NPC-urile speciale (ex: "Eye"), aplicând daune și generând particule la impact.

---

## 7. Inteligența artificială (AI)

### 7.1 Algoritmul A* — `PathFinder`

Deplasarea inamicilor ce urmăresc jucătorul este gestionată de clasa `PathFinder`, care implementează algoritmul **A\* (A-star)** pentru găsirea drumului optim pe grila de tile-uri a hărții.

**Optimizări implementate:**

1. **PriorityQueue** pentru selecția nodului cu costul minim:  
   ```java
   PriorityQueue<Node> openQueue = new PriorityQueue<>(64,
       Comparator.comparingInt((Node n) -> n.fCost).thenComparingInt(n -> n.gCost));
   ```  
   Complexitate: **O(log n)** per operație, față de O(n) la o scanare liniară.

2. **Resetare selectivă** a nodurilor atinse (`touchedNodes`):  
   La fiecare căutare nouă, se resetează doar nodurile vizitate în căutarea anterioară (tipic < 200 noduri), nu întreg grid-ul (100×100 = 10.000 noduri).

3. **Cache de traiectorie per entitate:** Fiecare entitate stochează waypoint-urile calculatei căi (`waypointCols[]`, `waypointRows[]`) și le recalculează doar când destinația se schimbă, evitând execuția A* la fiecare cadru.

4. **Lazy collision checks:** Verificările de coliziune pentru nodurile din queue sunt efectuate lazily la momentul expanderii, nu la înregistrare.

5. **Detector de stagnare** (`pathStallCounter`): Dacă entitatea nu avansează la waypoint-ul următor în `PATH_STALL_LIMIT = 10` cadre, traiectoria este recalculată forțat.

**Structura nodului A\*:**
```
gCost  — costul de la start la nod curent
hCost  — costul euristic de la nod la destinație (Manhattan distance)
fCost  — gCost + hCost
parent — nodul anterior pentru reconstrucția traseului
```

### 7.2 Comportamentele AI ale monștrilor

Sistemul `DataDrivenMonster` implementează mai multe comportamente AI selectabile din JSON prin câmpul `aiBehavior`:

| Comportament | Descriere |
|---|---|
| `melee_chase` | Urmărire directă a jucătorului + atac corp la corp |
| `ranged_archer` | Menținere distanță + tragere cu proiectile |
| `patrol` | Patrulare pe o traiectorie fixă |
| `guard` | Stație fixă cu raza de detecție |
| `boss_tree` | Comportament specializat al boss-ului Withered Tree |

### 7.3 Comportamentul de fugă (Flee)

Entitățile pot intra în starea de fugă (`fleeing = true`) la HP scăzut sau la detectarea unui pericol. Viteza de fugă este mai mare decât cea normală, iar durata este limitată de `fleeDuration` cadre.

### 7.4 Boss-ul Withered Tree — AI în 3 faze

`BOSS_WitheredTree` implementează un sistem de combatere în **3 faze**, cu tranziții la **66%** și **33%** din HP maxim:

| Fază | Sprite set | Comportament adăugat |
|---|---|---|
| Faza 1 — Guardian | Ent1 | Atac corp la corp (Melee Swing) + Stomp AOE |
| Faza 2 — Wrath | Ent2 | Root Barrage (erupții de rădăcini) + Leaf Bolt |
| Faza 3 — Blight | Ent3 | Whirlwind Fury (combo rotativ multi-hit) + Triple Bolt + Thorn Ring |

Repertoriul complet de atacuri:
- **Melee Swing:** lovitură de ramuri la distanță scurtă
- **Ground Stomp:** undă de șoc AOE cu inel în expansiune
- **Root Barrage:** erupții de rădăcini îndreptate spre jucător (Faza 2+)
- **Whirlwind Fury:** combo rotativ rapid multi-hit (Faza 3)
- **Leaf Bolt:** proiectil teledirijat ranged
- **Triple Bolt:** 3 proiectile simultane
- **Thorn Ring:** inel de spini în jurul boss-ului

---

## 8. Sistemul de hărți și tile-uri

### 8.1 Formatul TMX (Tiled Map Editor)

Hărțile jocului sunt create cu editorul **Tiled** și exportate în format **TMX** (XML). `TileManager` parsează fișierele TMX la runtime folosind `javax.xml.parsers.DocumentBuilder`, construind grile de GID-uri (Global Tile IDs) pentru fiecare strat.

**Caracteristici suportate ale formatului TMX:**
- Multiple straturi de tile-uri (background, foreground, collision)
- Tile-uri animate (cu durate per cadru în milisecunde)
- Flip orizontal, vertical și diagonal (biți înalți ai GID-ului: `GID_FLIP_H`, `GID_FLIP_V`, `GID_FLIP_D`)
- Straturi de imagini (image layers) cu suport pentru parallax
- Forme de coliziune (poligoane, dreptunghiuri) per tile
- Proprietăți personalizate la nivel de hartă, strat și obiect
- Multiple tileset-uri per hartă

**Optimizarea `AlphaComposite`:** `TileManager` menține un `HashMap<Float, AlphaComposite>` pre-populat cu valorile comune de opacitate. La fiecare cadru cu straturi semi-transparente, `cachedAlpha(float alpha)` returnează instanța existentă în loc să aloce una nouă, eliminând **~880 alocații/cadru** la hărțile cu straturi de ceață sau imagini cu opacitate.

**Straturi de imagini cu parallax:** `ImageLayerData` stochează `parallaxX`, `parallaxY` și `opacity` per strat. La randare, offsetul stratului este calculat ca `worldX - cameraX * parallaxX`, creând efectul de deplasare diferențiată față de camera. Straturile cu `foreground = true` sunt randate după entități, cele cu `foreground = false` — înainte.

**Culoarea de fundal a hărții:** Câmpul `mapBackgroundColor` este citit din atributul `backgroundcolor` al elementului `<map>` din TMX și aplicat ca fundal al ferestrei de randare, asigurând că zonele în afara hărții au culoarea corectă (implicit `(20, 18, 22)` — negru-maro).

**Offseturi pentru hărți infinite:** `mapOffsetPixelsX` / `mapOffsetPixelsY` deplasează întregul grid de tile-uri după normalizarea coordonatelor din hărțile infinite Tiled (care pot genera GID-uri la coordonate negative).

### 8.2 Gestionarea tranzițiilor de hartă — `MapManager`

`MapManager` gestionează registrul de hărți (id → cale TMX), starea curentă, tranzițiile și persistența entităților între hărți.

**Funcționalități cheie:**

- **Registru de hărți:** `Map<String, String> mapRegistry` — asociere id-uri logice cu căile fișierelor TMX
- **Puncte de spawn numite:** Porțile și trigger-ele de tranziție referențiază spawn point-uri prin id (ex: `"village_entrance"`) în loc de coordonate fixe
- **Persistența stării entităților:** La schimbarea hărții, starea obiectelor, NPC-urilor și monștrilor este salvată în `savedObjects / savedNPCs / savedMonsters / savedITiles`, restaurată la revenire
- **Proprietăți Tiled la nivel de hartă:** `ambientLight`, `weather`, `actTitle`, `dialogueTrigger`, `defaultSpawn` — citite din blocul `<properties>` al fișierului TMX

### 8.3 Loader-ul de obiecte din Tiled — `MapObjectLoader`

`MapObjectLoader` parcurge layerele de obiecte din fișierele TMX și instanțiază entitățile corespunzătoare prin factory-urile de date (`MonsterFactory`, `NPCFactory`). Proprietățile personalizate ale obiectelor Tiled sunt citite și aplicate entităților create.

**Tipuri de obiecte suportate:** SpawnPoint, NPC, Monster, BossMonster, OBJ_Chest, OBJ_Door, OBJ_Torch, OBJ_Tower, OBJ_Tent, IT_Pot, IT_Coins, LevelGate, MapTransition, HealingPool, DamageTrap, DialogueTrigger, Checkpoint, QuestTrigger, CameraShake, MobSpawnerZone, WaterZone, SpawnZone, MemoryGate, ThoughtTrigger, FragmentTrigger, ImageLayer.

### 8.4 Tile-uri interactive — `interactiveTile`

`interactiveTile` reprezintă tile-uri cu care jucătorul poate interacționa: borcane spargibile (`IT_Pot`), monede (`IT_Coins`). La distrugere, tile-ul este înlocuit cu versiunea distrusă, iar obiectele recompensă sunt generate în lume.

### 8.5 Sistemul de spawn dinamic — `MobSpawner`

`MobSpawner` generează inamici periodic în zone de spawn definite în Tiled (rectangles de tip `MobSpawnerZone`). Tipul de inamic generat variază în funcție de ciclul zi/noapte și de condițiile meteorologice:

- **Ziua:** inamici standard
- **Noaptea / Furtună:** inamici mai agresivi (ex: Skeleton Archers)

Intervalele de spawn: **600 cadre ziua** (10 secunde) și **360 cadre noaptea** (6 secunde).

### 8.6 Cache de resurse — `ResourceCache`

`ResourceCache` implementează un cache global thread-safe pentru imagini (`BufferedImage`) și documente XML parsate (`Document`), evitând re-parsarea costisitoare la fiecare tranziție de hartă.

În modul de dezvoltare (`DEBUG_MODE`), cache-ul poate fi configurat să citească direct fișierele `.tmx` din directorul sursă, permițând reîncărcarea hărților la runtime fără recompilare.

---

## 9. Sistemul de coliziuni

### 9.1 `CollisionChecker` cu spatial grid

`CollisionChecker` implementează verificarea coliziunilor dintre entități și formele de coliziune din TMX. Optimizarea principală este un **spatial grid** (grid spațial) care împarte lumea în celule de `GRID_CELL_SIZE = 128` pixeli:

```
Lume: 6400×6400 px → grid: 50×50 celule de 128×128 px
```

La verificarea coliziunilor, se consultă numai celulele din vecinătatea imediată a entității, reducând numărul de verificări de la O(n) per entitate la **O(k)**, unde k este numărul de forme din celulele apropiate (tipic 5–15, față de sute sau mii la scanare completă).

**Deduplicarea rezultatelor** (generational counter): Un tablou `seenGen[]` cu un contor de generație globală elimină duplicatele O(1) fără a necesita curățare explicită a array-ului.

### 9.2 Tipuri de coliziuni verificate

| Verificare | Metodă |
|---|---|
| Entitate vs. forme TMX | `checkTile(entity)` |
| Entitate vs. obiecte | `checkObject(entity, player)` |
| Entitate vs. NPC-uri | `checkEntity(entity, npc[])` |
| Entitate vs. monștri | `checkEntity(entity, monster[])` |
| Entitate vs. jucător | `checkPlayer(entity)` |
| Rectangle vs. forme TMX | `rectHitsCollision(rect)` |

---

## 10. Sistemul de misiuni (Quest System)

### 10.1 Arhitectura sistemului

`QuestManager` implementează un sistem de misiuni bazat pe date, cu definiții stocate în `res/data/quests.json`. Filosofia de design este separarea clară între **definiția misiunii** (JSON) și **logica misiunii** (Java), permițând adăugarea de noi misiuni fără modificări de cod.

### 10.2 Formatele de misiuni

Sistemul suportă două formate:

**Misiuni cu pași (step quests)** — pentru fluxuri complexe cu secvențe NPC:
```json
{
  "id": "help_soldier",
  "name": "Help the Wounded Soldier",
  "steps": [
    { "action": "talk",    "npc": "soldier", "dialogue": "intro", "give": "wooden_sword" },
    { "action": "deliver", "npc": "soldier", "item": "bandage",  "consume": true }
  ],
  "rewardCoins": 25,
  "chainQuestId": "meet_soldier_later"
}
```

**Misiuni simple (flat quests)** — pentru obiective de tip colectare/ucidere:
```json
{
  "id": "kill_10_mummies",
  "name": "Clear the Ruins",
  "target": 10,
  "rewardCoins": 50,
  "rewardItemId": "iron_sword"
}
```

### 10.3 Acțiunile pașilor de misiuni

| Acțiune | Descriere |
|---|---|
| `talk` | NPC-ul afișează dialogul specificat; avansare automată; poate acorda un obiect |
| `deliver` | Jucătorul trebuie să dețină obiectul specificat; consumare + avansare la succes |
| `collect` | Avansare prin apeluri la `progress()` (ex: colectare obiecte) |
| `kill` | Avansare la uciderea monștrilor specificați |
| `go` | Avansare la ajungerea pe o hartă specificată |

### 10.4 Înlănțuirea misiunilor (Quest Chaining)

La completarea unei misiuni, câmpul `chainQuestId` determină misiunea care se activează automat. Aceasta permite construcția de lanțuri narative lungi fără intervenție manuală a jucătorului.

### 10.5 Condiții prealabile și auto-start

- `prerequisite` — id-ul misiunii care trebuie completată înainte
- `autoStart: true` — misiunea se activează automat la pornirea jocului
- Misiunile pot fi pornite și din Tiled prin obiecte de tip `QuestTrigger` sau din cod prin `questManager.startQuest(id)`

---

## 11. Arborele de abilități (Skill Tree)

### 11.1 Structura `SkillTree`

`SkillTree` gestionează un arbore de noduri de abilități, definit în `res/data/skilltree.json`. Fiecare nod (`SkillNode`) conține:

```java
String id;          // identificator unic
String name;        // numele abilității
String description; // descrierea efectului
int cost;           // puncte de abilitate necesare
int col, row;       // poziția în grila UI
String requires;    // id-ul nodului prerechizit
boolean unlocked;   // starea de deblocare
```

### 11.2 Navigarea UI

Arborele este randat ca o **listă verticală scrollabilă** (similar inventarului Minecraft), cu o fereastră vizibilă de dimensiune fixă. `scrollOffset` și `selectedIndex` sunt sincronizate automat pentru a menține în view elementul selectat curent.

### 11.3 Puncte de abilitate

Jucătorul pornește cu `skillPoints = 20` puncte și câștigă puncte suplimentare la fiecare level-up. Fiecare abilitate are un cost în puncte și poate necesita deblocarea prealabilă a unui alt nod (câmpul `requires`).

---

## 12. Sistemul de salvare și încărcare

### 12.1 Salvare locală cu criptare AES-128

Progresul jocului este salvat local în fișiere binare criptate cu **AES-128 în modul CBC cu PKCS5Padding**. Fiecare salvare include un **IV (Initialization Vector)** generat aleatoriu cu `SecureRandom`, stocat în primii 16 octeți ai fișierului:

```
Format fișier: [16 octeți IV][ciphertext AES-CBC]
```

Aceasta asigură că fișierele de salvare sunt rezistente la analize și nu pot fi modificate manual fără cunoașterea cheii.

### 12.2 Datele salvate

Sistemul salvează:
- Statisticile jucătorului (HP, MP, atac, apărare, nivel, experiență)
- Inventarul (toate obiectele)
- Echipamentul activ
- Starea misiunilor (`QuestState[]`)
- Obiectele deschise (lăzi, uși) — identificate prin id și hartă
- Progresul boss-ilor (`boss1Defeated` ... `boss4Defeated`)
- Fragmentele de memorie colectate (`MemoryJournal`)
- Starea arborelui de abilități (noduri deblocate)
- Configurațiile de joc (volum, tastatură, fullscreen)

**Structura completă a clasei `GameState` (serializată):**

```java
// Poziție
int playerX, playerY, playerZ;
int direction;
String mapID;

// Statistici
int level, maxHealth, health, maxMana, mana;
int strength, dexterity, exp, nextLevelExp, coin;

// Abilități
int skillPoints;
boolean dashUnlocked, shockwaveUnlocked, voidSnareUnlocked,
        frostNovaUnlocked, overdriveUnlocked;

// Inventar
ArrayList<String> itemNames;
ArrayList<Integer> itemAmounts;
int currentWeaponSlot, currentShieldSlot;

// Fragmente de memorie
ArrayList<String> collectedFragmentIds;
int totalFragmentsCollected;

// Progres boss-i și poveste
boolean boss1Defeated, boss2Defeated, boss3Defeated, boss4Defeated;
int storyAct;    // 0=tutorial, 1=shatterLake, 2=ashenWoods,
                 // 3=citadel, 4=gallery, 5=frame
int endingChosen; // 0=none, 1=confront, 2=sacrifice, 3=forgive

// Porți permanente deschise
ArrayList<String> openedGates;

// Misiuni
ArrayList<String> questIds, questNames, questDescriptions;
ArrayList<Integer> questProgress, questTargets;
ArrayList<Integer> questCurrentSteps, questStepProgress;
ArrayList<String> dialogueChoicesMade;

// Timestamp pentru ordonare cloud save
long timestamp;
```

**`ItemFactory` — crearea obiectelor din JSON:** Toate obiectele de inventar cu statistici standard (arme, scuturi, consumabile, colectabile) sunt instanțiate prin `ItemFactory.create(gp, id)` din definițiile JSON în `res/data/items.json`. Câmpul `type` din JSON este mapat la constantele `Entity` prin `TYPE_MAP` (`"sword"→3`, `"book"→4`, `"shield"→5`, `"consumable"→6` etc.). Obiectele cu comportament complex (`OBJ_Door`, `OBJ_Chest`, `OBJ_Key`) păstrează clase Java dedicate.

### 12.3 Cloud Save — `CloudSaveService`

`CloudSaveService` implementează sincronizarea salvărilor cu un server remote, utilizând un protocol securizat:

**Criptografia utilizată:**
- **RSA-OAEP-SHA256** — handshake la conexiune (legat de licență)
- **AES-256-GCM (AEAD)** — criptare autentificată a fiecărui mesaj după handshake
- **HKDF-SHA256** — derivarea cheilor de sesiune
- **Contoare de secvență** pe fiecare direcție (client→server, server→client), legate în AAD — protecție anti-replay

**Descoperirea serverului:**  
Clientul citește lista de servere din `save_servers.txt` (câte un endpoint pe linie), testând fiecare cu un mesaj `PING`. La indisponibilitatea tuturor serverelor, salvarea este stocată **offline criptat** în `local_save.dat`, cu o cheie derivată din `(license_key, machine_fingerprint)`.

**Upload automat:** La reconectare, salvarea offline este uploadată automat și ștearsă local.

---

## 13. Sistemul de mediu și efecte vizuale

### 13.1 Ciclul zi/noapte — `EnvironmentManager`

Ciclul zi/noapte constă în 4 stări (`day`, `dusk`, `night`, `dawn`), cu tranziții graduale controlate de `filterAlpha`. Un ciclu complet durează `dayDuration = 10800` cadre (3 minute la 60 FPS).

Hărțile pot suprascrie ciclul prin proprietatea Tiled `ambientLight`, care ancorează valoarea `pinnedFilterAlpha`.

### 13.2 Sistemul meteorologic

`EnvironmentManager` implementează 4 stări meteo: `WEATHER_CLEAR`, `WEATHER_RAIN`, `WEATHER_STORM`, `WEATHER_SNOW`. Tranziția între stări se realizează gradual prin `weatherIntensity` (0.0 → 1.0), cu viteza `WEATHER_FADE_SPEED = 0.008f` (~2 secunde până la intensitate maximă).

Meteo influențează spawn-ul de inamici (ploaia și furtuna activează monștri mai agresivi) și vizibilitatea (furtuna reduce vizibilitatea prin efecte de particule dense).

### 13.3 Iluminarea prin mască — `Lightning`

Efectul de iluminare nocturnă este implementat prin o **mască de umbră** (`BufferedImage`) aplicată peste întreaga scenă randată. Masca conține un gradient radial în jurul jucătorului (raza configurabilă `playerLightRadius` în tile-uri), simbol al luminii torței. Sursele de lumină suplimentare (torțe, obiecte luminoase) adaugă cercuri de lumină în mască.

### 13.4 Shader-ul de hartă — `MapShaderManager`

`MapShaderManager` implementează efecte vizuale de tip shader prin Java2D:

- **Water shimmer:** Animație de tip undă pe suprafețele de apă, utilizând o tabelă de look-up sinusoidal (`SIN_TABLE`) pre-calculată pentru a evita apelurile `Math.sin()` per tile per cadru.
- **Ambient particles:** 35 particule flotante cu vânt dinamic, simulate cu o viteză de vânt (`windX`, `windY`) ce variază gradual.
- **Vignette:** Gradient radial întunecat la marginile ecranului.
- **Color grading:** Suprapunere de tint cald pentru atmosferă.
- **Sepia mode:** Mod sepie pentru scenele de flashback (`MemoryFlashback`).

### 13.5 Efectul de screen shake — `ScreenShake`

`ScreenShake` generează un offset translat aleatoriu aplicat matricei de transformare la randare, cu intensitate și durată configurabile. Shake-ul este utilizat la lovituri puternice, explozii și tranzițiile de boss.

### 13.6 Emițătorul de particule de tile — `TileParticleEmitter`

`TileParticleEmitter` generează particule de praf la pașii jucătorului pe anumite suprafețe (iarbă, nisip, piatră), adăugând feedback tactil de suprafață.

---

## 14. Sistemul audio

### 14.1 Arhitectura `AudioManager`

`AudioManager` centralizează gestionarea audio prin două instanțe `Sound` — una pentru muzică (cu loop) și una pentru efecte sonore (one-shot):

```java
private final Sound music = new Sound();
private final Sound se = new Sound();
```

### 14.2 Controlul volumului

Volumul este controlat pe o scară 0–5 pentru atât muzică cât și efecte sonore, configurat din meniul Options și salvat în fișierul de configurare. Metoda `applyConfig()` aplică setările încărcate din fișier.

### 14.3 Constante SFX — clasa `SFX`

Toate constantele pentru fișierele audio sunt definite în clasa `SFX` ca întregi statici finali, referențiând indexuri în tablourile de fișiere audio. Aceasta asigură siguranța tipizată și centralizeaza gestionarea resurselor audio.

---

## 15. Interfața cu utilizatorul (UI)

### 15.1 Pipeline-ul de randare — `RenderPipeline`

`RenderPipeline` separă logica de randare de `GamePanel`, implementând un **pipeline stratificat**:

1. Tile-uri background
2. Tile-uri interactive
3. Obiecte de pe hartă (sortate Z — după poziția picioarelor: `worldY + solidArea.y + solidArea.height`)
4. NPC-uri (sortate Z)
5. Jucătorul (sortat Z cu restul entităților)
6. Monștri (sortați Z)
7. Proiectile și particule
8. Tile-uri foreground (peste entități)
9. Efecte de mediu (iluminare, weather, shadere)
10. HUD (bară HP/MP, inventar, minimap)
11. Overlay-uri (dialoguri, meniuri, cutscene-uri)

**Z-sorting:** Entitățile sunt sortate după coordonata picioarelor (baza solidArea), realizând corect ocultarea perspectivei top-down — entitățile mai jos pe ecran se randează deasupra celor mai sus.

### 15.2 Minimap — `Minimap`

Minimapa este de tip circular, inspirată din *Don't Starve Together*. Randează tile-urile ca culori plate (nu imagini), clipate la forma unui cerc cu vignette radial și bordură ornamentală. Suportă și modul **hartă full-screen** cu overlay de locații importante.

**Codul de culori al minimapei:**
- Verde închis — iarbă
- Albastru — apă
- Maro — structuri și clădiri
- Punct galben — jucătorul
- Punct roșu — monștri
- Punct verde deschis — NPC-uri
- Punct auriu — lăzi

### 15.3 Sistemul de dialoguri și gânduri

`ThoughtBubble` afișează monolog interior non-blocant al jucătorului (gânduri declanșate de trigger-e Tiled), suprapus peste scena jocului fără a întrerupe gameplay-ul.

Dialogurile cu NPC-urile trec jocul în `dialogueState`, blocând mișcarea și acțiunile jucătorului.

### 15.4 Scenele cinematice — `CutsceneManager`

`CutsceneManager` gestionează scenele cinematice pre-programate: scena de "trezire" (`awakening`) și scena finală cu creditele (`ending`). Funcționalitățile includ:
- **Typewriter effect:** textul apare caracter cu caracter cu viteza `TYPEWRITER_SPEED = 4` cadre / caracter
- **Camera pan:** camera se deplasează cinematografic folosind interpolere
- **Fade in/out** cu `AlphaComposite`

### 15.5 `ScreenShake` la impact

La lovituri puternice (combo final, atacuri boss), `globalHitstopTimer` îngheață toate entitățile pentru câteva cadre, combinat cu `ScreenShake`, conferind feedback vizual de impact puternic.

---

## 16. Sistemul multiplayer

### 16.1 Arhitectura client TCP — `MultiplayerClient`

`MultiplayerClient` implementează clientul de joc multiplayer utilizând un protocol TCP securizat cu **criptare end-to-end**. Conexiunea la server se realizează prin IP/port specificat manual (Direct Connect) sau dintr-o listă salvată.

**Modelul de securitate al protocolului:**
- **Handshake:** RSA-OAEP-SHA256 cu cheie publică embeddată, legat de licența jucătorului
- **Sesiune:** AES-256-GCM (AEAD) — fiecare cadru este un mesaj GCM criptat
- **Derivare chei:** HKDF-SHA256 cu info vectori distincti pentru cheile de livrare și sesiune
- **Anti-replay:** Contoare de secvență (`sendSeq`, `recvSeq`) legate în AAD, plus timestamp și nonce verificate de server
- **Rate limiting:** Mesajele de poziție sunt trimise la fiecare `SEND_INTERVAL = 3` cadre (20 actualizări/secundă)

### 16.2 Streaming de hartă — `MpMapStreamer`

`MpMapStreamer` gestionează sincronizarea hărții în modul multiplayer — serverul transmite starea lumii (tile-uri, obiecte) care este aplicată local pe clientul jucătorului, permițând jocul pe hărți hostate de server fără ca acestea să fie distribuite local.

**Protocolul de streaming:**
1. **`world_info`** — serverul trimite un envelope JSON cu dimensiunile hărții, grila de chunk-uri, lista de straturi, spawn-ul implicit și un **skeleton TMX** (TMX original cu toate blocurile `<data>` goale). Skeleton-ul este preîncărcat în `ResourceCache` pentru ca `TileManager` și `MapObjectLoader` să îl preia prin calea obișnuită, fără modificări în codul single-player.
2. **`chunk`** — blob-uri de tile-uri per `(layerIdx, cx, cy)` comprimate GZIP, cu GID-uri little-endian uint32 ce păstrează biții de flip Tiled. Chunk-urile sunt aplicate direct în `mapLayers` / `mapFlipLayers` ale `TileManager`-ului activ.

**Prioritizarea chunk-urilor:** Chunk-urile sunt solicitate în ordinea distanței față de poziția curentă a jucătorului (ineluri concentrice), astfel lumea se completează mai rapid în jurul jucătorului. Serverul impune o rată maximă de chunk-uri pe tick; clientul poate pune în coadă toate requesturile odată.

**Stare atomică:** `skeletonLoaded` și `worldReady` sunt `AtomicBoolean`; `chunksReceived` este `AtomicInteger`. Aceste câmpuri sunt citite din thread-ul EDT fără sincronizare suplimentară.

### 16.3 Gestionarea listei de servere — `ServerListManager`

`ServerListManager` persistă lista serverelor multiplayer salvate în fișierul `servers.txt`, câte un server per linie în formatul `name|ip|port`. La pornire, lista este încărcată automat; adăugarea sau eliminarea unui server produce salvare imediată pe disc. Interfața de conectare afișează această listă și permite Direct Connect (introducere manuală IP/port).

### 16.4 Randarea jucătorilor remote

`GamePanel` gestionează o `ConcurrentHashMap<Integer, RemotePlayerState>` ce stochează stările jucătorilor conectați. Aceștia sunt randați cu name tag-uri, bare de HP și o tentă distinctivă albastră.

### 16.4 Serverul multiplayer (Python)

Pe latura serverului (directorul `SERVERS/multiplayer_server/`), serverul este implementat în **Python** (`server.py`) cu suport pentru:
- Verificarea licențelor prin `license_verify.py`
- Persistența lumii prin `world.py`
- Gestionarea hărților (`maps/`)
- Configurare prin `mp_config.json`

---

## 17. Sistemul de licențiere și securitate

### 17.1 `LicenseManager` — licențiere RSA-2048

`LicenseManager` implementează un sistem de licențiere bazat pe semnătură digitală **RSA-2048 PKCS#1v15 SHA-256**, cu legare la mașina utilizatorului (machine binding):

**Formatul fișierului `license.properties`:**
```
license_key=XXXXXXXX-YYYY
machine_fp=<16 caractere hex — SHA-256(Windows MachineGuid)[:8 bytes]>
signature=<base64(RSA-2048 PKCS#1v15 SHA-256 over "license_key|machine_fp")>
```

La pornire:
1. Fișierul `license.properties` este localizat relativ la JAR-ul executabil
2. Cheia RSA publică embeddată verifică semnătura
3. `machine_fp` este verificat față de amprenta hardware curentă
4. Dacă verificarea reușește, `LICENSE_KEY` este setat și cloud save / multiplayer se activează

### 17.2 Watchdog de licență

Un thread de tip watchdog (`startWatchdog(60)`) verifică periodic (la fiecare 60 secunde) integritatea fișierului de licență față de starea sa inițial verificată. La detectarea oricărei modificări (swap de fișier, editare, migrare de mașină), `LICENSE_KEY` este anulat, dezactivând funcțiile online fără a închide jocul.

### 17.3 `AssetValidator`

`AssetValidator` verifică integritatea resurselor jocului la startup, detectând fișiere lipsă sau corupte care ar putea cauza crash-uri runtime.

---

## 18. Optimizări de performanță

Proiectul aplică sistematic principii de optimizare pentru a menține 60 FPS stabil pe hardware-ul țintă.

### 18.1 Object Pools — `ObjectPool<T>`

`ObjectPool` implementează un pool generic de obiecte reutilizabile, eliminând presiunea de garbage collection pentru entitățile create/distruse frecvent:

- `ObjectPool<Projectile>` — proiectile
- `ObjectPool<Particle>` — particule
- `ObjectPool<DamageNumber>` — numere de daune

La eliberare (`release()`), obiectul este resetat (`reset()`) și returnat în pool, fără alocare de memorie nouă.

### 18.2 ResourceCache

`ResourceCache` cache-uiește `BufferedImage`-urile și documentele XML parsate, evitând re-decodarea imaginilor și re-parsarea XML la fiecare tranziție de hartă.

### 18.3 Pre-alocarea constantelor grafice

Toate culorile (`Color`), fonturile (`Font`), stroke-urile (`BasicStroke`) și kompozitele (`AlphaComposite`) utilizate în randare sunt alocate **static final**, evitând alocările per cadru care saturează garbage collector-ul:

```java
private static final Color HP_BAR_BG = new Color(35, 35, 35);
private static final Color HP_BAR_FG = new Color(255, 0, 30);
```

### 18.4 Tabele de look-up sinusoidal

`MapShaderManager` utilizează o tabelă de look-up de 1024 intrări (`SIN_TABLE[]`) pentru funcțiile trigonometrice, evitând `Math.sin()` / `Math.cos()` per tile per cadru.

### 18.5 Accelerare hardware OpenGL

La startup, `Main.java` activează pipeline-ul hardware accelerat Java2D cu proprietăți de sistem:

```java
System.setProperty("sun.java2d.opengl", "True");
System.setProperty("sun.java2d.accthreshold", "0");
System.setProperty("sun.java2d.managedimages", "true");
System.setProperty("sun.java2d.translaccel", "true");
```

### 18.6 Spatial Grid pentru coliziuni

`CollisionChecker` utilizează un grid spațial pentru verificarea coliziunilor, reducând complexitatea de la O(n) la O(k) cu k ≪ n.

### 18.7 Optimizarea A* prin PriorityQueue și resetare selectivă

Algoritmul A* utilizează `PriorityQueue` pentru O(log n) la selecția nodului optim și resetează doar nodurile vizitate, nu întreaga grilă.

### 18.8 Evitarea alocărilor în randare

- `RenderPipeline.entityList` este pre-alocată cu capacitate estimată (150 entități)
- `HitFlashBuffer` și `Graphics2D`-ul asociat sunt re-utilizate între cadre
- Comparatoarele (`Comparator<Entity>`) sunt definite o singură dată ca câmpuri ale clasei

---

## 19. Sistemul de actualizare automată

### 19.1 `UpdateClient` — verificare la startup

La fiecare pornire a jocului, `UpdateClient.checkAndApply()` consultă un server de patch-uri (configurat în `update_servers.txt`) pentru a verifica dacă există o versiune nouă. Verificarea folosește un manifest JSON semnat cu o pereche de chei RSA dedicată patch-urilor.

### 19.2 `Updater` — aplicare patch

Dacă un update este disponibil, `UpdateClient` descarcă patch-ul (format ZIP) și lansează `Updater` ca proces Java separat, care:
1. Așteaptă ca procesul-părinte (jocul) să se închidă
2. Reconstruiește JAR-ul prin overlay-ul fișierelor din `add/` și `replace/` ale patch-ului
3. Elimină fișierele listate în `delete` din patch
4. Lansează din nou jocul cu JAR-ul actualizat

Fișierele sensibile (`license.properties`, `save_servers.txt`, `local_save.dat`) nu sunt niciodată atinse de updater.

---

## 20. Povestea și narațiunea

### 20.1 Premisa

Jocul se petrece în **Canvas Realm** — o lume picturală în care sufletele prinse de **The Canvas Curse** sunt întemniţate, cu amintirile șterse. Jucătorul (personajul adoptat al unui rege) a absorbit blestemul destinat tatălui său, sacrificându-se pentru a-l salva. Fratele adoptiv, **Prințul Aldren**, motivat de invidie, a lansat blestemul și a ucis regele pentru a șterge martori.

### 20.2 Sistemul de fragmente de memorie — `MemoryJournal`

**Fragmentele de memorie** (`MemoryFragment`) sunt mecanica narativă centrală. Există trei tipuri:

| Tip | Sursă | Număr estimat |
|---|---|---|
| **Echo Fragments** | NPC-uri (dialog + condiție) | 12–15 |
| **Dark Fragments** | Boss-uri (la înfrângerea lor) | 4 |
| **Hidden Fragments** | Explorare, puzzle-uri | 4–6 |

La colectare, `MemoryFlashback` declanșează o scenă sepia cu text typewriter — o „memorie" a trecutului jucătorului apare pe ecran pentru 2–4 secunde. `MemoryJournal` stochează fragmentele și le afișează în ordinea **cronologică a poveștii** (câmpul `storyOrder`), indiferent de ordinea colectării.

### 20.3 Actele poveștii

Jocul este structurat în **6 acte** (`storyAct`):

| Act | Locație | Descriere |
|---|---|---|
| 0 | Tutorial | Trezirea în Canvas Realm |
| 1 | Shatter Lake | Lacul sfărâmat — prima zonă |
| 2 | Ashen Woods | Pădurile cenușii |
| 3 | Citadel | Cetatea — zona finală |
| 4 | Gallery | Galeria memoriilor |
| 5 | The Frame | Confruntarea finală |

### 20.4 Finalurile multiple

Jocul oferă **3 finaluri** (`endingChosen`): confruntare directă cu Aldren, sacrificiu pentru a-i cere iertare, sau iertare necondiționată. Fiecare final produce o scenă cinematică distinctă.

---

## 21. Structura proiectului și tehnologiile utilizate

### 21.1 Tehnologii și biblioteci

| Tehnologie | Utilizare |
|---|---|
| **Java SE** (standard edition) | Limbajul principal de implementare |
| **Java Swing / AWT** | Fereastra, panoul de joc, input |
| **Java2D (Graphics2D)** | Randarea completă a jocului |
| **javax.imageio** | Decodarea imaginilor (PNG, JPG) |
| **javax.xml** (DOM parser) | Parsarea fișierelor TMX/TSX Tiled |
| **javax.crypto** (JCE) | AES-CBC, AES-GCM, RSA-OAEP |
| **javax.sound.sampled** | Redarea audio (WAV, OGG) |
| **java.net.Socket** | Conexiuni TCP pentru multiplayer și cloud save |
| **Tiled Map Editor** | Crearea hărților (export TMX/XML) |
| **Python 3** | Serverele backend (multiplayer, save, patch) |

### 21.2 Configurarea compilării

**Compilare manuală:**
```bash
javac -d bin -sourcepath src src/main/Main.java
```

**Task-uri VS Code (tasks.json):**
- `Sync Resources` — copiază `src/res/` în `bin/res/` cu `robocopy`
- `Compile` — compilează sursele Java
- `Build and Run` — compilare + lansare cu parametri JVM optimizați:
  ```
  java -Xms256m -Xmx1g -XX:+UseG1GC -Dsun.java2d.opengl=True -cp bin main.Main
  ```

### 21.3 Parametrii JVM la runtime

| Parametru | Valoare | Scop |
|---|---|---|
| `-Xms256m` | 256 MB | Heap inițial |
| `-Xmx1g` | 1 GB | Heap maxim |
| `-XX:+UseG1GC` | activat | Garbage collector G1 — latențe mici |
| `-Dsun.java2d.opengl` | True | Pipeline hardware accelerat |

### 21.4 Factory-uri bazate pe date

Atât `MonsterFactory` cât și `NPCFactory` adoptă paradigma **data-driven design**: toate definițiile de entități (statistici, sprite-uri, comportament AI, dialoguri) sunt stocate în fișiere JSON:
- `res/data/monsters.json`
- `res/data/npcs.json`
- `res/data/quests.json`
- `res/data/skilltree.json`
- `res/data/fragments.json`
- `res/data/items.json`

Aceasta permite adăugarea de conținut nou (monștri, NPC-uri, misiuni, obiecte) **fără modificări de cod Java**, reducând ciclul de iterație al design-ului.

### 21.5 Fișierele de configurare și date persiste

| Fișier | Locație | Conținut |
|---|---|---|
| `config.txt` | lângă JAR | Setări grafice, volum, fereastră, VSync |
| `servers.txt` | lângă JAR | Lista serverelor multiplayer salvate (`name\|ip\|port`) |
| `save_servers.txt` | lângă JAR | Lista serverelor cloud save (un endpoint/linie) |
| `update_servers.txt` | lângă JAR | Lista serverelor de patch-uri |
| `local_save.dat` | lângă JAR | Salvare offline criptată AES (fallback cloud save) |
| `license.properties` | lângă JAR | Cheia de licență, amprenta mașinii, semnătura RSA |
| `res/build.properties` | în JAR | Versiunea jocului și numărul de build |

### 21.6 Structura fișierelor JSON de conținut

**`items.json`** — Câmpuri per obiect: `id`, `name`, `type` (`sword`/`book`/`shield`/`consumable`/`pickupOnly`/`buffs`/`ending`), `description`, `attackValue`, `defenseValue`, `knockBackPower`, `useCost`, `stackable`, `attackAreaW`, `attackAreaH`, plus calea spre sprite. Obiectele simple sunt 100% data-driven; obiectele complexe (chei, lăzi, uși) au clase Java dedicate.

**`monsters.json`** — Câmpuri per monstru: `id`, `name`, `maxLife`, `attack`, `defense`, `speed`, `exp`, `aiBehavior` (`melee_chase`/`ranged_archer`/`patrol`/`guard`/`boss_tree`), plus configurări specifice comportamentului (raza de detecție, intervalul de atac, proiectil etc.).

**`npcs.json`** — Câmpuri per NPC: `id`, `name`, sprite-uri, dialoguri indexate, misiunile asociate, activitățile animate (ex: `"forge"`, `"sweep"`, `"sleep"`) cu setul de animație corespunzător.

**`quests.json`** — Suportă două formate: *step quests* cu array `steps` (acțiuni `talk`/`deliver`/`collect`/`kill`/`go`) și *flat quests* cu câmpuri `target`, `rewardCoins`, `rewardItemId`. Câmpul `chainQuestId` înlănțuie misiunile automat la completare.

**`skilltree.json`** — Array de noduri cu `id`, `name`, `description`, `cost`, `col`, `row`, `requires`. Parserul din `SkillTree` folosește o implementare JSON hand-written fără dependențe externe.

**`fragments.json`** — Array de fragmente de memorie cu `id`, `name`, `text[]` (1–5 linii), `storyOrder` (poziția cronologică în poveste), `source` (`npc`/`boss`/`exploration`).

### 21.7 Cerințe de sistem

#### Cerințe minime

| Componentă | Specificație |
|---|---|
| **Sistem de operare** | Windows 10 (64-bit) |
| **Procesor** | Intel Core i3-6100 / AMD Ryzen 3 1200 (2 nuclee, 3.0 GHz) |
| **Memorie RAM** | 2 GB |
| **Placă video** | GPU integrat cu suport OpenGL 2.0 (Intel HD 4000 sau echivalent) |
| **Stocare** | 500 MB spațiu liber |
| **Java Runtime** | Java SE 17 sau mai nou (JRE 64-bit) |
| **Rețea** | Nu este necesară (single player offline) |

> La configurația minimă, jocul rulează la 60 FPS cu setările implicite, însă unele efecte vizuale (shader apă, particule ambientale) pot fi dezactivate din meniul Options pentru performanță maximă.

#### Cerințe recomandate

| Componentă | Specificație |
|---|---|
| **Sistem de operare** | Windows 10 / 11 (64-bit) |
| **Procesor** | Intel Core i5-8400 / AMD Ryzen 5 2600 (4+ nuclee, 3.5+ GHz) |
| **Memorie RAM** | 4 GB |
| **Placă video** | GPU dedicat cu suport OpenGL 3.3+ (NVIDIA GTX 750 Ti / AMD RX 560 sau echivalent) |
| **Stocare** | 1 GB spațiu liber (SSD recomandat pentru timpi de încărcare reduși) |
| **Java Runtime** | Java SE 21 LTS (JRE 64-bit) |
| **Rețea** | Conexiune stabilă la internet pentru multiplayer și cloud save (minim 5 Mbps) |

> La configurația recomandată, jocul rulează constant la 60 FPS cu toate efectele vizuale activate (iluminare nocturnă, shader apă, particule, weather), cu timpi de tranziție între hărți sub 0.5 secunde.

---

## 22. Concluzii

### 22.1 Realizările tehnice principale

Proiectul *Michi's Adventure / Echoes of the Heir* demonstrează implementarea unui motor de joc de complexitate semnificativă în Java pur, fără dependențe externe de tipul framework-urilor de joc. Printre realizările tehnice notabile se numără:

1. **Motorul de joc complet** — bucla fixă UPS/FPS, mașina de stări, camera cu lerp, fereastra undecorated cu butoane custom
2. **Pipeline de randare stratificat** cu Z-sorting bazat pe picioare și suport pentru tileset-uri multiple cu flip și animație
3. **Algoritmul A\*** cu optimizări avansate (PriorityQueue, resetare selectivă, cache per entitate)
4. **Sistem de combatere** fluid cu combo 3 pași, input buffering, knockback vectorial și hitstop global
5. **Sistem de abilități** extensibil din JSON cu arbore de prerequisite-uri
6. **Sistem de misiuni cu pași** bazat integral pe date (JSON + Tiled)
7. **Salvare criptată** (AES-128-CBC local) cu cloud backup (AES-256-GCM + RSA-OAEP)
8. **Multiplayer TCP securizat** cu criptare end-to-end și protecție anti-replay
9. **Licențiere RSA-2048** cu machine binding și watchdog de integritate
10. **Actualizare automată** prin patch-uri ZIP semnate
11. **Efecte vizuale** — iluminare nocturnă prin mască, shader-e apă, vignette, particule, weather, sepia mode
12. **Design data-driven** — conținut extensibil fără modificări Java

### 22.2 Concluzii de design

Separarea strictă a preocupărilor (logică de joc, randare, date, rețea) prin pachete bine delimitate și factory-uri bazate pe date au contribuit la mentenabilitatea proiectului. Adoptarea paradigmei data-driven pentru monștri, NPC-uri, misiuni și arborele de abilități a permis iterații rapide de design fără recompilare.

Optimizările de performanță aplicate consecvent — object pools, spatial grid, cache de resurse, pre-alocarea constantelor grafice, accelerare hardware — asigură rularea la 60 FPS stabil chiar pe hardware-ul de clasă medie, demonstrând că Java poate susține un game loop performant cu abordarea corectă.

---

*Documentație generată din analiza codului sursă al proiectului Michi's Adventure — Mai 2026.*
