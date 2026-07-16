# Backend: servere și client

## Introducere

Echoes of the Heir are trei sisteme de rețea complet separate, ușor de confundat între ele pentru
că toate ating cuvântul „server”:

1. **Serverul de multiplayer** — un server Python (`asyncio`), pur TCP, la care se conectează
   clientul Java prin clasa **`main.MultiplayerClient`**. Acesta e subiectul principal al acestui
   document.
2. **Un subproces Java „authoritative engine”** — `server.EngineServer`, lansat de serverul Python
   ca proces copil, care reutilizează codul real al jocului (`main.HeadlessGame`) ca să arbitreze
   luptele, astfel încât clientul și serverul să nu ajungă niciodată să calculeze rezultate diferite.
3. **BLE (Bluetooth Low Energy) local multiplayer** — o cale complet separată, doar pe Android,
   fără TCP, fără criptare, gândită ca „invită un prieten” prin NFC + Bluetooth direct, nu prin
   internet.

Există și un al patrulea server, **serverul de salvări cloud** (`SERVERS/save_server`), folosit de
`data.CloudSaveService` pentru sincronizarea salvărilor și activarea licenței (vezi
[LICENSING_SISTEM_RO.md](LICENSING_SISTEM_RO.md)) — nu are treabă cu multiplayer-ul, dar are propriul
lui heartbeat, ceea ce e o sursă comună de confuzie (vezi secțiunea 4).

## 1. Serverul de multiplayer (Python, `SERVERS/multiplayer_server/server.py`)

Serverul **nu** e thread-per-client, e `asyncio` cooperativ, single-threaded la nivel de event loop:

- Clasa `GameServer` (linia 631) ține toată starea: dicționarul de clienți, harta/lumea, NPC-urile,
  ban-urile, rate limiter-ul.
- Ascultă conexiuni prin `asyncio.start_server(self.handle_client, self.host, self.port)`
  (`server.py:1629-1631`).
- Port implicit: **7777** (`DEFAULT_PORT`, `server.py:77`), suprascriptibil prin variabila de mediu
  `MICHI_MP_PORT`, prin `mp_config.json`, sau prin argumente CLI.
- Număr maxim de clienți simultani: **8** (`MAX_PLAYERS`, `server.py:94`), configurabil prin
  `"max_players"` în `mp_config.json`. Verificat la conectare (`server.py:960`) — peste limită,
  clientul primește `SERVER_FULL`.
- Rulează și un **dashboard HTTP de administrare** (`SERVERS/multiplayer_server/dashboard.py`) pe
  un port separat (implicit 8888).
- **Nu conține BLE** — BLE nu atinge niciodată acest server, e o cale complet paralelă.

### Threading pe server

Fiind `asyncio`, nu există thread-uri OS per client, ci corutine planificate pe același event loop:

- `handle_client(reader, writer)` e corutina lansată per conexiune acceptată.
  Face verificarea de rate-limit/capacitate, handshake-ul criptografic, apoi intră într-o buclă de
  citire:
  ```python
  msg = await asyncio.wait_for(client.recv_json(), timeout=PING_TIMEOUT)  # server.py:1063
  ```
  `PING_TIMEOUT = 15.0` secunde (`server.py:96`) — dacă nu vine niciun mesaj de la client (inclusiv
  ping-ul de keepalive) în acest interval, conexiunea e considerată moartă și e închisă.
- Fiecare conexiune are un obiect **`ClientConnection`** (`server.py:541-628`) cu:
  - o coadă `asyncio.Queue(maxsize=256)` pentru mesajele de trimis (`line 553`), golită de un
    **task de scriere** dedicat per client — `_writer_loop()` (`lines 560-569`), pornit prin
    `asyncio.create_task(...)` (`line 558`). Asta decuplează „pun un mesaj în coadă pentru
    broadcast” de scrierea efectivă pe socket.
  - `send_json()` (`line 580`) criptează (AES-GCM) și pune mesajul în coadă (non-blocant; dacă
    coada e plină, mesajul se aruncă și se loghează, `lines 588-591`).
  - `recv_json()` (`line 593`) citește o linie, verifică numărul de secvență, decriptează.
- **Broadcast**: `GameServer.broadcast_json(obj, exclude_id=-1)` (`server.py:717-720`) iterează
  peste toți clienții și cheamă `send_json` pe fiecare — sigur pentru concurență pentru că totul
  rulează pe același event loop.
- **Bucla globală de tick**: `tick_loop()` (`server.py:1147-1161`), o singură corutină
  (`asyncio.create_task(self.tick_loop())`, linia 1635), la **20 Hz** (`TICK_RATE = 20`,
  `server.py:95`, adică la fiecare 50ms). La fiecare tick, trimite fiecărui client poziția/animația
  celorlalți jucători (`to_update_dict()`, `lines 1153-1157`) — acesta e broadcast-ul de poziție
  server-autoritativ, sincron cu interpolarea Hermite din client (care presupune segmente de ~50ms,
  vezi secțiunea 3). Tot aici se scurg și chunk-urile de hartă în coadă, limitate la
  `max_chunks_per_tick` (implicit 4, `DEFAULT_MAX_CHUNKS_PER_TICK`, `server.py:81`).
- La deconectare (`finally`, `server.py:1134-1145`): clientul e scos din listă, statisticile sunt
  salvate, și se trimite un broadcast `player_leave`.

### Subprocesul Java authoritative (`server.EngineServer`)

`SERVERS/multiplayer_server/game_engine.py` (clasa `GameEngine`, `lines 44-85`) lansează
`core/src/main/java/server/EngineServer.java` ca subproces (`asyncio.create_subprocess_exec`),
comunicând prin JSON pe stdin/stdout. `EngineServer` rulează propria buclă (`run()`,
`EngineServer.java:95-136`) tot la 20Hz, citind non-blocant liniile disponibile pe stdin
(`in.ready()`, linia 103) la fiecare iterație, apoi apelând `gp.update()` — reutilizează efectiv
motorul de joc al clientului (`main.HeadlessGame`) ca arbitru de luptă, ca server-ul și clientul să
nu diveargă niciodată în calculul de damage/loot.

## 2. Clientul de multiplayer (`main.MultiplayerClient`)

Clasa principală: **`core/src/main/java/main/MultiplayerClient.java`** (~1550 linii), instanțiată o
singură dată per `GamePanel`, ca `gp.mpClient` (`GamePanel.java:323`).

- Conexiunea e **TCP simplu** prin `java.net.Socket`
  (`socket.connect(new InetSocketAddress(ip, port), 5000)`, `MultiplayerClient.java:186-193`), cu
  `setTcpNoDelay(true)` și timeout de citire de 15 secunde.
- Nu există discovery de rețea locală (LAN broadcast) — vezi secțiunea 5 pentru cum se conectează
  efectiv un client la un server.
- Punctul de intrare: `connect(ip, port, playerName, playerClass)`
  (`MultiplayerClient.java:106-167`) — complet asincron: pornește un thread `"MP-Connect"` care
  face activarea licenței, conectarea TCP, și handshake-ul criptografic (RSA + AES-GCM) înainte de
  a marca `connected = true`.
- În `Main.DEBUG_MODE` (`Main.java:7`), dacă ținta cerută nu e localhost și nu răspunde, clientul
  reîncearcă automat pe `127.0.0.1:7777` (`DEBUG_FALLBACK_HOST/PORT`, `MultiplayerClient.java:51-52`,
  logica la `lines 169-184`) — o comoditate doar pentru dezvoltare locală.

## 3. Thread-urile clientului (inclusiv heartbeat-ul)

La o conexiune reușită, `MultiplayerClient` pornește exact două thread-uri daemon permanente, plus
thread-ul tranzitoriu de conectare:

- **`"MP-Connect"`** (`MultiplayerClient.java:111`) — o singură trecere: activare licență, conectare
  TCP + handshake, apoi pornește cele două thread-uri de mai jos și se termină.
- **`"MP-Receive"`** (`lines 151-153`) — rulează `receiveLoop()` (linia 498), citește blocant cadre
  `DATA` criptate de pe socket cât timp `connected.get()` e adevărat, și le trimite către
  `handleMessage(json)` (linia 537). La orice excepție sau EOF, marchează `connected = false`
  (`lines 510-517`) — **nu există reconectare automată** în client; o deconectare e definitivă până
  când utilizatorul se reconectează manual din meniu.
- **`"MP-Keepalive"`** (`lines 155-157`) — **acesta e heartbeat-ul de multiplayer** și **este încă
  actual/folosit**, `keepaliveLoop()` (`lines 520-531`):
  ```java
  private void keepaliveLoop() {
      while (connected.get()) {
          try {
              Thread.sleep(3000);
              if (connected.get()) sendEncrypted("{\"type\":\"ping\"}");
          } catch (Exception e) { break; }
      }
  }
  ```
  Interval: **3 secunde**. Trimite un `{"type":"ping"}` la nivel de aplicație; serverul răspunde cu
  `{"type":"pong"}`, tratat ca no-op în client (linia 660). Acest ping există separat de timeout-ul
  de citire de 15s al serverului (`PING_TIMEOUT`, secțiunea 1) — clientul trimite ping la fiecare 3s
  tocmai ca acel timeout de 15s să nu se declanșeze niciodată în timpul jocului normal.
- Trimiterea stării de joc (poziție, progres) **nu** are thread propriu — e condusă de bucla de joc:
  `MultiplayerClient.update()` (`lines 225-233`) e apelată o dată per tick de joc din
  `GamePanel.update()` (`GamePanel.java:908-909`), dar trimite efectiv doar la fiecare al 3-lea tick
  (`SEND_INTERVAL = 3`, linia 94), prin `sendPlayerState()` și `sendProgressIfChanged()`.
- Un thread anonim de „watcher” e pornit din `KeyHandler.connectToServer()`
  (`KeyHandler.java:701-736`), care nu face parte din `MultiplayerClient` — sondează
  `mpClient.isConnected()`/`isConnecting()`/`isWorldReady()` la fiecare 100-150ms ca să conducă UI-ul
  prin fluxul „handshake (timeout 6s) → streaming de lume (timeout 60s) → intrare în playState”.
- Streaming-ul de hartă/lume (`main.MpMapStreamer`, 421 linii) nu are thread propriu — e condus din
  aceeași buclă de receive/update, dar aplică chunk-urile prin `Gdx.app.postRunnable(...)` din
  `MP-Receive` (`MultiplayerClient.java:726-730`) ca apelurile OpenGL să rămână pe thread-ul de
  randare.

### Nu confunda cu heartbeat-ul de cloud save

`data.CloudSaveService` are propriul lui heartbeat, complet separat: `startHeartbeat()`
(`lines 121-141`) pornește un thread numit `"CloudSave-Heartbeat"` care face ping către serverul de
salvări cloud la fiecare **10 secunde** (`HEARTBEAT_INTERVAL_MS = 10_000`, linia 99), pentru a urmări
`serverOnline` și a declanșa `onReconnect` (folosit de `FriendsListManager`). **Nu are legătură cu
multiplayer-ul de joc** — dacă cineva întreabă de „heartbeat-ul clientului”, verifică din context
dacă se referă la `MP-Keepalive` (multiplayer, 3s) sau la `CloudSave-Heartbeat` (salvări/prieteni,
10s).

## 4. Protocolul de comunicare

Protocol **JSON peste TCP**, cu un plic (envelope) binar/base64 pentru criptare — nu Kryonet, nu
protobuf, nu vreo librărie externă de serializare.

### Handshake (linii în text clar)

Documentat în `server.py:5-36` și oglindit în `MultiplayerClient.performHandshake`
(`lines 354-432`):

1. `C→S: HELLO v2 <base64(client_nonce_16)>`
2. `S→C: OK <base64(server_nonce_16)> <fingerprint_cheie_publică_server>` (sau
   `RATE_LIMIT`/`SERVER_FULL`/`BANNED`/`USERNAME_TAKEN`)
3. `C→S: LOGIN <base64(RSA-OAEP-SHA256(handshake_json))> <activation_id> <base64(enc_blob)>` —
   `handshake_json` conține `ts, client_nonce, server_nonce, name, class`. Cheia de licență brută
   nu circulă niciodată pe rețea; serverul verifică `activation_id`/`enc_blob` intern, prin
   `save_server`.
4. `S→C: AUTH_OK <base64(AES-GCM(session_key))>` (sau
   `BANNED`/`USERNAME_TAKEN`/`AUTH_FAIL`/`LICENSE_SERVER_UNAVAILABLE`).

Cheia publică RSA e împachetată direct în client (`RSA_PUBLIC_KEY_B64`,
`MultiplayerClient.java:55-62`); cheia de sesiune se derivează prin HKDF-SHA256
(`DELIVERY_INFO = "michi-delivery-v2"`).

### Cadre de sesiune (după handshake)

`DATA <base64(seq_8 || nonce_12 || AESGCM-ciphertext+tag)>`, cu AAD =
`direction_byte(0x01=S→C, 0x02=C→S) || seq_8` (`server.py:34-36`; client în
`sendEncrypted`/`recvEncrypted`, `MultiplayerClient.java:438-480`; server în
`ClientConnection.send_json`/`recv_json`, `server.py:580-618`). Contoarele de secvență
monotone per direcție sunt verificate strict — o nepotrivire = conexiunea e închisă (protecție
anti-replay).

### Tipuri de mesaje (câmpul `"type"`)

Nu există o ierarhie de clase „Packet” sau un enum — mesajele sunt string-uri JSON construite manual
(`StringBuilder` în Java, `dict`/`json.dumps` în Python), parsate fără librărie externă
(`extractString/extractInt/extractDouble/extractBool`, `MultiplayerClient.java:1043-1096`).

**Client → server:** `move` (poziție/direcție/sprite/atac), `progress_sync` (quest-uri, boss-uri,
fragmente, NPC-uri întâlnite, act de poveste), `chat`, `chunk_request`, `world_ready`, `ping`,
`mob_damage`/`mob_death`, `npc_interact`/`npc_leave`, `shop_buy`/`shop_sell`, `skill_unlock`,
`level_choice`.

**Server → client:** `welcome` (id, spawn, lista de jucători), `world_info`/`chunk` (streaming de
hartă, date de tile în base64), `pos_correction` (corecție anti-cheat de poziție), `trigger` (zone
de trigger pe hartă), `map_change`, `player_join`/`player_leave`/`player_update` (cu `x,y,dir,sprite,
attacking,life,maxLife` plus `vx,vy` pentru interpolarea spline), `server_full`,
`chat`/`chat_throttled`, `pong`, `kick`, `player_stats` (statistici autoritative, inclusiv
`pendingLevelChoices`), `mob_damage`/`mob_death`, `npc_spawn`/`npc_dialogue`/`npc_shop`/
`shop_result`, `skill_result`/`skills_state`.

**NPC-urile și economia sunt complet server-autoritative** (documentat explicit,
`MultiplayerClient.java:1192-1202`): clientul doar randează NPC-urile și trimite intenții
(`npc_interact`, `shop_buy`, `shop_sell`); dialogul, stocul și aurul sunt decise pe server.

**Scalarea coordonatelor**: serverul lucrează în spațiul original de tile de 32px; clientul
înmulțește/împarte cu `coordScale = gp.tileSize/gp.originalTileSize` (de obicei 2) la trimitere/
primire.

**Interpolare**: clientul folosește o spline Hermite cubică per jucător la distanță
(`RemotePlayerState`, `MultiplayerClient.java:1499-1556`), alimentată cu viteza trimisă de server
(`vx,vy`) și presupunerea de segment fix de 50ms (tick-ul serverului).

## 5. Ciclul de viață al conexiunii

**Nu există discovery/broadcast de LAN.** Un client se poate alătura unui server în trei moduri:

1. **Conectare directă**: IP + port introduse manual în UI, declanșate din
   `KeyHandler.java:665-667` → `connectToServer(ip, port)` (`lines 676-736`).
2. **Listă de servere salvate**: `main.ServerListManager` (67 linii) — o listă simplă într-un
   fișier text `servers.txt` (format `name|ip|port` pe linie), expusă ca `gp.serverList` — o agendă
   personală, nu discovery; utilizatorul trebuie deja să cunoască IP-ul serverului.
3. **Invitație BLE + NFC** (secțiunea 6) — cel mai apropiat lucru de un „lobby”, dar strict de
   proximitate locală, nu descoperire prin internet/LAN.

**Fluxul de conectare** (`KeyHandler.java:676-736`): parsează portul, apelează
`gp.resetSession()` pentru a curăța orice stare anterioară de singleplayer/multiplayer, apelează
`mpClient.connect(ip, port, username, "Fighter")`, setează `multiplayerMode = true`, apoi un thread
watcher așteaptă până la 6 secunde handshake-ul și până la 60 de secunde streaming-ul complet al
lumii (`isWorldReady()`) înainte de a trece `gameState` în `playState`.

**Deconectare**: `MultiplayerClient.disconnect()` (`lines 200-216`) — marchează
`connected`/`connecting` false, golește `remotePlayers`, deblochează orice așteptare de dialog NPC,
resetează contoarele de secvență și cheia de sesiune, resetează streamer-ul de hartă, și închide
socket-ul. Declanșată de: acțiune explicită a utilizatorului, mesaj `kick` de la server,
`server_full`, `map_change` (schimbarea de hartă cerută de server nu e gestionată live — clientul
pur și simplu se deconectează și arată un mesaj că e nevoie de reconectare), sau orice eroare de I/O
în bucla de recepție.

**Nu există reconectare automată** pe clientul de multiplayer — o dată deconectat, utilizatorul
trebuie să treacă din nou prin UI-ul de conectare (spre deosebire de `CloudSaveService`, care are
reconectare automată prin heartbeat — vezi secțiunea 3).

Pe server: ban-uri (`admin_ban`), rate limiting per IP (`IpRateLimiter`), și deconectare pe idle
bazată pe `PING_TIMEOUT` (secțiunea 1) sunt principalele controale ale ciclului de viață; există și
o consolă de admin (`_admin_console`) pentru comenzi de operator (kick/ban/teleport etc.).

## 6. BLE (Bluetooth Low Energy) — multiplayer local

O cale complet separată, doar pe Android, fără TCP, fără criptare, gândită ca „invită un prieten”:

- **Logica de sesiune** (independentă de platformă): `core/src/main/java/main/BleMultiplayerSession.java`
  (395 linii). Ideea: o alternativă la `MultiplayerClient` legat de licență — folosește NFC pentru a
  transmite adresa BLE a gazdei, un token de sesiune și id-ul hărții (`platform.NfcInvitePayload`),
  apoi o legătură BLE GATT directă. Fără internet, fără cont, fără licență, fără criptare, maxim
  **5 invitați** (`MAX_GUESTS`, linia 43).
- **Protocol**: linii text separate prin `|` (nu JSON): `W|id|mapId|col|row` (welcome),
  `J|id|name` (join), `L|id` (leave), `U|id|x|y|dir|sprite|atk|life|maxLife` (update poziție),
  `D|mobId|life|maxLife|dmg` (damage), `K|mobId` (kill), `F|reason` (sfârșit de sesiune/plin).
- **Abstractizare de platformă**: `platform.BleMultiplayer` — o fațadă statică legată de
  implementările `platform.BleHostService`/`platform.BleGuestService`.
- **Implementare Android** (BLE există doar pe Android, nu pe desktop):
  `android/src/main/java/androidlauncher/ble/`:
  - `BleHostServiceImpl.java` — rulează un `BluetoothGattServer` (rol periferic/GATT-server),
    face advertising prin `BluetoothLeAdvertiser`, limitează la `MAX_GUESTS` dispozitive conectate.
  - `BleGuestServiceImpl.java` — partea de scanare/conectare (rol central).
  - `BleProtocol.java` — definește `SERVICE_UUID` și UUID-urile de caracteristici.
  - `BlePermissions.java` — verificări de permisiuni runtime (Bluetooth/locație).
  - Nu există thread-uri proprii în implementările Android — callback-urile GATT vin pe thread-poolul
    intern al stack-ului Bluetooth Android, deci codul e condus de callback-uri, nu de bucle proprii
    de citire/scriere.
- **Stare partajată**: `BleMultiplayerSession.remotePlayers` reutilizează exact
  `MultiplayerClient.RemotePlayerState` (aceeași structură de interpolare spline ca la TCP) — ceea ce
  explică de ce cele două căi au fost ușor de confundat la randare.
- `update()` e apelat o dată per tick din `GamePanel.update()`, alături de `mpClient.update()`,
  cu aceeași limitare de trimitere la fiecare al 3-lea tick.

### Fix-ul de randare TCP/BLE

Randarea jucătorilor la distanță era condiționată doar de `mpClient.isConnected()`, ceea ce făcea
invizibili jucătorii conectați prin BLE. Fix-ul actual, în `ui/RenderPipeline.java:178-187`,
calculează ambele condiții independent și le combină prin OR:

```java
boolean tcpConnected = gp.mpClient != null && gp.mpClient.isConnected();
boolean bleActive = gp.bleSession != null && gp.bleSession.isActive();
if (tcpConnected || bleActive) {
    gp.drawRemotePlayers(g2);
}
```

`gp.drawRemotePlayers(...)` (`GamePanel.java:1440-1446`) randează independent din
`mpClient.remotePlayers` și, dacă `bleSession.isActive()`, din `bleSession.remotePlayers`.
