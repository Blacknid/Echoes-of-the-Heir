#**SERVERS INSTRUCTIONS**

Toate serverele au fost construite pentru a rula pe un sistem de operare linux. Versiunea folosita pentru development a fost Raspberry PI os (ARM64). Acestea functioneaza pentru orice versiune/distributie de linux, nu depinde de arhitectura procesorului. Pentru a rula setup-urile avem nevoie de:

1. Python 3.11 sau mai nou
2. pip (package installer for Python)
3. Virtualenv (optional, insa pentru ultimele versiuni de python devine obligatoriu, din cauza conflictelor care se produc datorita instalarii de pachete global)
4. Bash
5. Cont de root (sau privilegii de administrator)
6. Conexiune la internet
7. Manager de instalari (am folosit apt, codul trebuie modificat pentru un alt manager. Pentru a instala pip, vezi "installing apt")

Acestea sunt necesitatile *de baza* pentru a rula oricare dintre servere. In caz in care apar noi dependente, vor fi amintite *sub fiecare rubrica*.

##**SAVE SERVER**







## Detailed Save Server Communication Scheme

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              CONNECTION ESTABLISHMENT                               │
├─────────────────────────────────────────────────────────────────────────────────────┤

   ┌─────────────┐                                              ┌─────────────┐
   │    CLIENT   │                                              │    SERVER   │
   │             │  1. TCP connect to save_servers.txt endpoint │   (port     │
   │             │ ────────────────────────────────────────────→│    5005)    │
   │             │                                              │              │
   │             │  2. Protocol banner                          │              │
   │             │ ←──────────────── "MICHISAVEv2\n" ────────────│              │
   │             │                                              │              │
   │             │  3. RSA-OAEP-SHA256 Handshake                │              │
   │             │     • Client generates ephemeral RSA keypair   │              │
   │             │     • Sends: base64(license) + " " +         │              │
   │             │              base64(client_rsa_pub)          │              │
   │             │ ────────────────────────────────────────────→│              │
   │             │                                              │              │
   │             │     • Server validates license structure     │              │
   │             │     • Gets/creates DB record (salt, pepper)  │              │
   │             │     • Generates: session_key (32 bytes)      │              │
   │             │                server_nonce (16 bytes)       │              │
   │             │     • Encrypts session_key with client RSA   │              │
   │             │                                              │              │
   │             │  4. Session key delivery                     │              │
   │             │ ←──────────────── base64(enc_session_key) +  │              │
   │             │                  " " + server_nonce ─────────│              │
   │             │                                              │              │
   │             │     • Client derives AES-GCM key:            │              │
   │             │       HKDF(secret=license+lic_pepper,        │              │
   │             │            salt=lic_salt+server_nonce,        │              │
   │             │            info="michi-delivery-v2")          │              │
   │             │                                              │              │
   │             │ ═══════════════════════════════════════════│══════════════│
   │             │        SECURE SESSION ESTABLISHED            │              │
   │             │          (AES-256-GCM for all frames)        │              │
   └─────────────┘                                              └─────────────┘


┌─────────────────────────────────────────────────────────────────────────────────────┐
│                                   UPLOAD FLOW (SAVE)                                │
├─────────────────────────────────────────────────────────────────────────────────────┤

   ┌─────────────┐                                              ┌─────────────┐
   │    CLIENT   │                                              │    SERVER   │
   │             │                                              │              │
   │  PREPARE:   │  1. Serialize GameState to JSON              │              │
   │  • Collect  │     {"timestamp": 1234567890,                │              │
   │    player   │      "player": {...},                        │              │
   │    position │      "inventory": [...],                     │              │
   │  • Include  │      "quests": {...},                        │              │
   │    quest    │      "current_map": "village"}               │              │
   │    state    │                                              │              │
   │  • Add      │                                              │              │
   │    timestamp│                                              │              │
   │             │  2. Encrypt JSON with AES-256-GCM             │              │
   │             │     • Generate random IV (12 bytes)           │              │
   │             │     • Encrypt: ciphertext + 16-byte auth tag  │              │
   │             │     • Format: IV (12) + ciphertext + tag (16) │              │
   │             │                                              │              │
   │             │  3. Build frame                               │              │
   │             │     "SAVE " + length + "\n" + aes_payload     │              │
   │             │                                              │              │
   │             │ ─────────────── 4. SEND ────────────────────→│              │
   │             │     [encrypted save data over wire]           │              │
   │             │                                              │              │
   │             │                           5. Server processes:│              │
   │             │                              • Decrypt AES    │              │
   │             │                                payload        │              │
   │             │                              • Validate JSON │              │
   │             │                                structure       │              │
   │             │                              • Check timestamp│              │
   │             │                                              │              │
   │             │  6. Conflict Resolution (timestamp-based)     │              │
   │             │                                              │              │
   │             │     IF incoming_ts >= existing_ts:            │              │
   │             │        • Store: UPDATE licenses SET            │              │
   │             │          save_data=?, timestamp=?             │              │
   │             │          WHERE license_key=?                  │              │
   │             │        • Return "SAVED\n"                     │              │
   │             │ ←────────────────────────────────────────────│              │
   │             │                                              │              │
   │             │     IF incoming_ts < existing_ts:             │              │
   │             │        (server has newer data)                │              │
   │             │        • Return "SYNC\n" + length + encrypted │              │
   │             │          (server's newer save data)           │              │
   │             │ ←──────────────── "SYNC 2456\n" + payload ──│              │
   │             │                                              │              │
   │             │  7. Client receives SAVED:                    │              │
   │             │     • Mark pendingUpload = false              │              │
   │             │     • Delete local_save.dat if exists         │              │
   │             │                                              │              │
   │             │  7b. Client receives SYNC:                    │              │
   │             │     • Decrypt server payload                  │              │
   │             │     • Overwrite local GameState               │              │
   │             │     • Show "synced with server" to user       │              │
   │             │                                              │              │
   │             │  8. If network fails at any step:             │              │
   │             │     • Encrypt to local_save.dat (fallback)    │              │
   │             │     • Set pendingUpload = true                │              │
   │             │     • Return success (offline mode)           │              │
   └─────────────┘                                              └─────────────┘


┌─────────────────────────────────────────────────────────────────────────────────────┐
│                                 DOWNLOAD FLOW                                       │
├─────────────────────────────────────────────────────────────────────────────────────┤

   ┌─────────────┐                                              ┌─────────────┐
   │    CLIENT   │                                              │    SERVER   │
   │             │                                              │              │
   │             │  1. Request download (after handshake)      │              │
   │             │     "DOWNLOAD\n"                            │              │
   │             │ ────────────────────────────────────────────→│              │
   │             │                                              │              │
   │             │                           2. Server checks DB:│              │
   │             │                              • SELECT save  │              │
   │             │                                data FROM    │              │
   │             │                                licenses     │              │
   │             │                                WHERE key=?  │              │
   │             │                                              │              │
   │             │     IF no save found:                       │              │
   │             │ ←──────────────── "NO_SAVE\n" ──────────────│              │
   │             │     → Client tries local_save.dat fallback    │              │
   │             │                                              │              │
   │             │     IF save exists:                         │              │
   │             │        • Read encrypted JSON from DB         │              │
   │             │        • Decrypt with server's storage key   │              │
   │             │        • Re-encrypt with session AES-GCM key │              │
   │             │        (session-specific encryption)         │              │
   │             │                                              │              │
   │             │ ←──────── 3. "OK " + length + "\n" + payload ─│              │
   │             │     [encrypted JSON over wire]                │              │
   │             │                                              │              │
   │             │  4. Client processes response:                │              │
   │             │     • Extract IV from beginning of payload   │              │
   │             │     • Decrypt AES-GCM using session key       │              │
   │             │     • Verify authentication tag              │              │
   │             │     • Parse JSON → GameState object           │              │
   │             │                                              │              │
   │             │  5. Post-download actions:                  │              │
   │             │     • pendingUpload = false (we're synced)   │              │
   │             │     • Delete local_save.dat (now redundant)  │              │
   │             │     • Return DownloadResult.ok(json)         │              │
   │             │                                              │              │
   │             │  6. If server unreachable or NO_SAVE:       │              │
   │             │     • Try loadLocalEncrypted(licenseKey)     │              │
   │             │     • Decrypt local_save.dat using           │              │
   │             │       HKDF(license, machine_fingerprint)       │              │
   │             │     • Return local data or empty state       │              │
   └─────────────┘                                              └─────────────┘


┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              OFFLINE FALLBACK MECHANISM                             │
├─────────────────────────────────────────────────────────────────────────────────────┤

   When server is unreachable (ConnectException or timeout):
   
   SAVE PATH:
   ┌─────────────┐
   │  GameState  │
   │  (memory)   │
   └──────┬──────┘
          │ 1. Serialize to JSON
          ▼
   ┌─────────────┐     2. Derive local key:       ┌─────────────┐
   │   encrypt   │────► HKDF(license_key,        │ local_save  │
   │  AES-256    │      machine_fingerprint,      │   .dat      │
   │    -GCM     │      info="michi-local-v1")    │ (encrypted) │
   └─────────────┘                                └─────────────┘
   
   DOWNLOAD PATH:
   ┌─────────────┐     1. Derive same key         ┌─────────────┐
   │ local_save  │◄───────────────────────────────│   license   │
   │   .dat      │                                │    + MAC    │
   └──────┬──────┘                                └─────────────┘
          │ 2. Decrypt
          ▼
   ┌─────────────┐
   │  GameState  │────► Return to game
   │  (memory)   │
   └─────────────┘
   
   PENDING SYNC:
   • pendingUpload flag set when local save is newer than server
   • On next successful connection: automatically re-uploads
   • Server conflict resolution handles timestamp comparison


┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              SECURITY LAYERS                                        │
├─────────────────────────────────────────────────────────────────────────────────────┤

   Per-License Security:
   ┌─────────────────┐
   │  license_key    │
   │  (client input) │
   └────────┬────────┘
            │
            ▼
   ┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
   │   license_salt  │────►│    HKDF-SHA256  │────►│  delivery_key  │
   │  (from server   │     │  (per-session)  │     │ (AES-256-GCM)  │
   │   DB, unique)   │     │                 │     │                │
   └─────────────────┘     │  secret: lic+   │     └────────────────┘
   ┌─────────────────┐     │  lic_pepper     │              │
   │  license_pepper │────►│  salt: salt+    │              │
   │  (from server   │     │  server_nonce   │              │
   │   DB, unique)   │     │  info: "michi-  │              │
   └─────────────────┘     │  delivery-v2"   │              │
                            └─────────────────┘              │
                                                               │
                            ┌─────────────────┐                │
                            │  server_nonce   │◄───────────────┘
                            │  (per-session,  │   (sent during
                            │   16 bytes)     │    handshake)
                            └─────────────────┘

   
   Data Flow Encryption:
   
   Client Memory ──► JSON ──► AES-GCM ──► Network ──► AES-GCM ──► JSON ──► Server DB
   (plaintext)      (text)    (encrypt)   (encrypted)  (decrypt)  (text)   (encrypted
                                                                              at rest)
```








### Arhitectura Generala

Save Server este un server TCP multi-threaded scris in Python 3.11+, conceput pentru stocarea si sincronizarea salvarilor de joc in cloud. Serverul ruleaza pe portul 5005 (default) si accepta conexiuni de la clienti Java printr-un protocol binar/text hibrid.

### Stack Criptografic

**1. RSA-OAEP-SHA256 (Handshake)**
- Cheie RSA-2048 bits generata de catre `generate_keys.py`
- Algoritm: RSA-OAEP cu MGF1-SHA256 pentru criptarea initiala
- Private key ramane exclusiv pe server (permisiuni 600 [pentru permisiuni in linux vezi asta](https://www.geeksforgeeks.org/linux-unix/set-file-permissions-linux/))
- Public key (Base64 DER) este embedded in clientul Java (`CloudSaveService.java`)
- Folosit doar in faza de handshake pentru a transmite securizat datele de autentificare 

**2. AES-256-GCM (Session Encryption)**
- Criptare simetrica AEAD pentru toate mesajele post-handshake
- Nonce de 12 bytes generat cu `os.urandom(12)` pentru fiecare mesaj
- Tag de autentificare de 16 bytes inclus automat de AES-GCM
- AAD (Additional Authenticated Data) include directia si sequence number pentru a preveni reordering
- Cheia de sesiune este rotita complet la fiecare conexiune noua

**3. HKDF-SHA256 (Key Derivation)**
- Derivare cheie conform RFC 5869
- Formula: `delivery_key = HKDF(secret=license_bytes + lic_pepper, salt=lic_salt + server_nonce, info="michi-delivery-v2", length=32)`
- Parametri unici per licenta (salt si pepper de 32 bytes fiecare)
- Rotatie automata la fiecare 5 logari reusite

### Protocolul de Handshake (v2)

```
1. C -> "HELLO v2 <base64(client_nonce_16)>"
2. S -> "OK <base64(server_nonce_16)>"
3. C -> "AUTH <base64(rsa_oaep_sha256(handshake_json))>"
   handshake_json = {
     "license": "XXXXXXXX-YYYY",
     "ts": <unix epoch seconds>,
     "client_nonce": <hex>,
     "server_nonce": <hex>
   }
4. S -> "AUTH_OK <base64(aesgcm(session_key))>"  sau  "AUTH_FAIL"
```

Validarile efectuate de server:
- **License validation**: Prefix (8 chars) + Suffix (4 chars) generat prin SHA256(salt + prefix)[0:4]
- **Timestamp window**: +/- 60 secunde fata de server time (prevenire replay atacuri vechi)
- **Nonce matching**: Client-ul trebuie sa returneze exact nonce-urile primite
- **Replay cache**: Nonce-urile deja folosite sunt respinse (TTL 300 secunde, cleanup automat la 4096 entries)

### Formatul Mesajelor Criptate

```
wire = "DATA " + base64(seq_8_bytes || nonce_12_bytes || ciphertext || tag_16_bytes)
AAD = direction_byte (0x01 S->C, 0x02 C->S) || seq_8_bytes_BE
```
- Sequence numbers pe 64-bit big-endian
- Fiecare directie are propriul sau contor (incepe de la 0)
- Orice mismatch de sequence number duce la terminarea sesiunii

### Baza de Date SQLite

**Tabela `saves`:**
```sql
license_key TEXT PRIMARY KEY
save_data BLOB NOT NULL
game_timestamp INTEGER	
size_bytes INTEGER
updated_at TEXT
```

**Tabela `licenses` (per-license crypto):**
```sql
license_key TEXT PRIMARY KEY
salt_hex TEXT NOT NULL      -- 64 hex chars (32 bytes)
pepper_hex TEXT NOT NULL    -- 64 hex chars (32 bytes)
login_count INTEGER         -- pentru rotation schedule
rotated_at TEXT
created_at TEXT
```

**Tabela `events` (audit log):**
- Inregistreaza fiecare conexiune cu IP, licenta si status final

### Mecanisme de Securitate

**Rate Limiting:**
- Per-IP sliding window: 30 conexiuni/minut default
- Cleanup automat al bucket-urilor expirate
- Raspuns "RATE_LIMIT\n" pentru IP-urile depasite

**Connection Limits:**
- `max_concurrent_connections`: 200 default
- Semaphore bounded pentru a preveni resource exhaustion
- Raspuns "BUSY\n" cand serverul e saturat

**Timeout-uri:**
- Handshake: 10 secunde
- Session: 30 secunde idle
- Max payload: 10 MB

**Anti-Vulnerabilitati Abordate:**
- **Replay attacks**: Nonce cache 5-minute TTL + timestamp validation
- **Man-in-the-middle**: RSA-OAEP pentru handshake + AES-GCM AEAD pentru session
- **License forgery**: HMAC-based license suffix cu salt server-side
- **Session hijacking**: Chei de sesiune unice per conexiune, niciodata reutilizate
- **Downgrade attacks**: Protocol tag "v2" hardcoded, respinge versiuni necunoscute
- **Timing attacks**: `hmac.compare_digest()` pentru toate compararile de secrete

### Sincronizare Date

Serverul implementeaza conflict resolution based on timestamp:
- Daca `existing_ts > incoming_ts`: returneaza SYNC cu datele serverului (clientul e in urma)
- Altfel: salveaza datele noi si returneaza SAVED
- Payload-urile sunt validate ca JSON valid inainte de persistare

### Dependente Python

```
cryptography>=41.0.0  # RSA, AES-GCM, HKDF
```

---












##**PATCH SERVER**

### Arhitectura Generala

Server de distributie patch-uri pentru self-updating JAR. Ruleaza pe portul 5006 (default) si ofera un protocol simplu pentru verificarea versiunii si descarcarea patch-urilor binare.

### Stack Criptografic

**RSA-2048 PKCS#1 v1.5 + SHA-256 (Patch Signing)**
- Cheie privata ramane exclusiv pe patch server
- Semnatura acopera: `SHA256(patch_bytes) || "|" || from_version || "|" || to_version`
- Binding la ambele versiuni pentru a preveni downgrade attacks
- Public key este embedded in `UpdateClient.java` (PATCH_PUBLIC_KEY_B64)

### Protocolul Wire Format

```
PING
  -> PONG

CHECK <current_version>
  -> UPTODATE
  -> UPDATE <to_version> <size_bytes> <sha256_hex> <signature_b64>

FETCH <from_version>
  -> 8 bytes big-endian uint64 (size), apoi size bytes de patch ZIP
  -> ERROR <msg>
```

### Formatul Patch-ului (ZIP)

```
patch.zip/
  manifest.json          {from, to, replace[], add[], delete[]}
  add/<entry>           fisiere noi in interiorul JAR
  replace/<entry>       bytes de inlocuire pentru fisiere modificate
```

### Manifest Schema

```json
{
  "latest_version": "1.2.3",
  "patches": [
    {
      "from": "1.1.0",
      "to": "1.2.3",
      "file": "patches/1.1.0_to_1.2.3.zip",
      "size_bytes": 123456,
      "sha256_hex": "abc123...",
      "signature_b64": "base64signature..."
    }
  ]
}
```

### Mecanisme de Securitate

**Signature Verification (Client-Side):**
- Clientul verifica semnatura RSA inainte de a aplica patch-ul
- Orice esec de verificare duce la rejectia patch-ului
- JAR-ul original este pastrat ca .bak pana la confirmarea succesului

**Anti-Vulnerabilitati Abordate:**
- **Malicious patch injection**: Semnatura RSA obligatorie, verificata cu cheia publica embedded
- **Downgrade attacks**: Semnatura include explicit versiunile from si to
- **Tampering**: SHA256 integrat in payload-ul semnat
- **Man-in-the-middle**: Chiar daca conexiunea e interceptata, patch-ul trebuie semnat corect
- **Partial update corruption**: Aplicare atomica cu Files.move() si rollback la .bak

**Rate Limiting:**
- 60 request-uri/minut per IP (default)
- Semaphore bounded: 32 conexiuni simultane (default)

**Alte Protectii:**
- Max patch size: 256 MB default (prevenire memory exhaustion)
- Path traversal protection: numele fisierelor sunt sanitizate
- Line length limit: 4096 bytes pentru comenzi text

### Generarea Patch-urilor

`build_patch.py` creaza diffs prin:
1. Compararea entry-by-entry intre old.jar si new.jar
2. Categorizare: add, replace, delete
3. Compresie ZIP pentru eficienta
4. Semnare automata cu cheia privata RSA

### Dependente Python

```
cryptography>=41.0.0  # RSA signing
cryptography.hazmat.primitives.asymmetric.rsa
```

---














##**MULTIPLAYER SERVER**

### Arhitectura Generala

Server multiplayer async/await bazat pe `asyncio`, reimplementat de la zero cu acelasi model de securitate ca save server. Suporta pana la 8 jucatori (default) cu tick rate de 20Hz pentru sincronizare pozitii.

### Stack Criptografic

Identic cu Save Server:
- **RSA-OAEP-SHA256** pentru handshake
- **AES-256-GCM** pentru toate frame-urile de gameplay
- **HKDF-SHA256** pentru derivarea cheilor
- **Per-license salt+pepper** cu rotatie la 5 logini

### Protocolul de Handshake (v2)

```
C -> "HELLO v2 <base64(client_nonce_16)>"
S -> "OK <base64(server_nonce_16)>"
C -> "AUTH <base64(rsa_oaep_sha256(handshake_json))>"
   handshake_json = {
     "license": "XXXXXXXX-YYYY",
     "ts": <unix epoch s>,
     "client_nonce": <hex>,
     "server_nonce": <hex>,
     "name": "<player name>",
     "class": "<player class>"
   }
S -> "AUTH_OK <base64(aesgcm(session_key))>"
```

### Formatul Frame-urilor de Gameplay

```
wire = "DATA <base64(seq_8 || nonce_12 || ciphertext || tag_16)>"
AAD = direction_byte (0x01 S->C, 0x02 C->S) || seq_8
plaintext = JSON (move, chat, ping, etc.)
```

### Mesaje JSON Acceptate

**Client -> Server:**
- `move`: x, y, dir, sprite, attacking, life, maxLife (toate clamped)
- `chat`: msg (max 200 chars, max 5 per 10 secunde)
- `ping`: heartbeat

**Server -> Client:**
- `welcome`: id, players[]
- `player_join`: id, name, class
- `player_leave`: id
- `player_update`: pozitii si stari (broadcast la 20Hz)
- `chat`: from, msg
- `pong`: raspuns la ping
- `kick`: reason (server shutdown)

### Server-Authoritative Validation

**Clampari Obligatorii:**
- `x`, `y`: -10^7 to 10^7 (prevenire overflow/teleport hacking)
- `life`: 0 to MAX_LIFE_CAP (9999 default)
- `maxLife`: 1 to MAX_LIFE_CAP
- `direction`: 0 to 7
- `sprite_num`: 0 to 32

**Sanitizare Input:**
- `name`: max 24 chars, doar alphanumeric + `_-. ` (regex whitelist)
- `class`: max 16 chars, doar alphanumeric
- `chat`: max 200 chars, newlines convertite la spatii

**Rate Limiting Chat:**
- Max 5 mesaje per 10 secunde per jucator
- Throttle notification catre client

### Baza de Date SQLite

**Tabela `licenses`:**
- Identica cu save server (salt, pepper, login_count, rotated_at)
- DB separat: `mp_licenses.db`
- Operatii async prin `run_in_executor()`

### Mecanisme de Securitate

**Connection Management:**
- Max players: 8 default (configurabil)
- Rate limit: 30 conexiuni/minut per IP
- Session idle timeout: 60 secunde
- Ping timeout: 15 secunde (detectare disconnect)

**Anti-Vulnerabilitati Abordate:**
- **Speed hacking**: Server authoritative position clamping
- **Health hacking**: Life clamped la MAX_LIFE_CAP (9999)
- **Chat spam**: Rate limiting per jucator
- **Character spoofing**: Name sanitizare whitelist stricta
- **Memory exhaustion**: Bounded write queues (256 mesaje max)
- **Replay attacks**: Aceleasi mecanisme ca save server (nonce cache, timestamps)
- **Position desync**: Server authoritative state, clientul doar sugereaza
- **DDoS**: Rate limiting per IP + connection cap + async architecture

**Async Architecture:**
- Foloseste `asyncio` pentru handling eficient al multor conexiuni
- Writer tasks separate pentru fiecare client (prevenire blocking)
- Cancelable tasks pentru cleanup clean la disconnect

### Tick Loop

```python
TICK_RATE = 20  # Hz
interval = 1.0 / TICK_RATE

while running:
    for each player:
        broadcast position update to all other players
    sleep(remaining_interval)
```

### Graceful Shutdown

- Handler pentru SIGINT/SIGTERM
- Notificare tuturor clientilor cu `{"type": "kick"}`
- Cleanup de socket-uri si tasks

### Dependente Python

```
cryptography>=41.0.0  # RSA, AES-GCM, HKDF
asyncio              # Core networking
```

---

##**SCHEMA DETALIATĂ - SERVER LICENSING (SAVE SERVER)**

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              LICENSING SERVER ARCHITECTURE                           │
├─────────────────────────────────────────────────────────────────────────────────────┤

┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              COMPONENTS OVERVIEW                                    │
├─────────────────────────────────────────────────────────────────────────────────────┤

   ┌─────────────────┐
   │   TCP Listener  │  Port 5005 (default)
   │   (socket)      │  BoundedSemaphore(200)
   └────────┬────────┘
            │
            ▼
   ┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
   │  Rate Limiter   │────►│  Nonce Cache    │────►│  Session Pool   │
   │  (per-IP 30/m)  │     │  (TTL 300s)     │     │  (threading)    │
   └─────────────────┘     └─────────────────┘     └────────┬────────┘
                                                             │
                                                             ▼
                                                  ┌─────────────────────┐
                                                  │   SQLite Database   │
                                                  │  - saves (BLOB)     │
                                                  │  - licenses (salt)  │
                                                  │  - events (audit)   │
                                                  └─────────────────────┘


┌─────────────────────────────────────────────────────────────────────────────────────┐
│                            LICENSE VALIDATION FLOW                                 │
├─────────────────────────────────────────────────────────────────────────────────────┤

   ┌─────────────┐                                              ┌─────────────┐
   │    CLIENT   │                                              │    SERVER   │
   │             │                                              │             │
   │  INPUT:     │  1. User enters license key                  │             │
   │  "ABCD1234- │     Format: XXXXXXXX-YYYY                     │             │
   │   EFGH"     │     • 8 chars prefix (alphanumeric)           │             │
   │             │     • 4 chars suffix (checksum)                │             │
   │             │                                              │             │
   │             │  2. Send HELLO with client nonce              │             │
   │             │     "HELLO v2 <base64(client_nonce_16)>"      │             │
   │             │ ────────────────────────────────────────────→│             │
   │             │                                              │             │
   │             │                           3. Server validates:│             │
   │             │                              • Protocol tag  │             │
   │             │                              • Nonce length   │             │
   │             │                                              │             │
   │             │  4. Server responds with nonce               │             │
   │             │ ←──────── "OK <base64(server_nonce_16)>" ─────│             │
   │             │                                              │             │
   │             │  5. Client builds handshake JSON              │             │
   │             │     {                                         │             │
   │             │       "license": "ABCD1234-EFGH",             │             │
   │             │       "ts": 1715123456,                        │             │
   │             │       "client_nonce": "<hex>",                 │             │
   │             │       "server_nonce": "<hex>"                  │             │
   │             │     }                                         │             │
   │             │                                              │             │
   │             │  6. Encrypt with server RSA public key       │             │
   │             │     RSA-OAEP-SHA256(handshake_json)            │             │
   │             │                                              │             │
   │             │  7. Send AUTH                                 │             │
   │             │     "AUTH <base64(encrypted_handshake)>"      │             │
   │             │ ────────────────────────────────────────────→│             │
   │             │                                              │             │
   │             │                           8. Server performs:│             │
   │             │                              a) Decrypt with  │             │
   │             │                                 RSA private   │             │
   │             │                              b) Parse JSON    │             │
   │             │                              c) Validate      │             │
   │             │                                 license format│             │
   │             │                              d) Compute suffix │             │
   │             │                                 checksum:     │             │
   │             │                                 SHA256(prefix  │             │
   │             │                                 + salt)[0:4]   │             │
   │             │                              e) Compare with   │             │
   │             │                                 provided suffix│             │
   │             │                              f) Check timestamp │             │
   │             │                                 (+/- 60s)     │             │
   │             │                              g) Verify nonces │             │
   │             │                                 match          │             │
   │             │                              h) Check replay   │             │
   │             │                                 cache         │             │
   │             │                                              │             │
   │             │     IF any validation fails:                 │             │
   │             │ ←──────── "AUTH_FAIL" ───────────────────────│             │
   │             │     → Connection terminated                   │             │
   │             │                                              │             │
   │             │     IF all validations pass:                 │             │
   │             │                              i) Get or create │             │
   │             │                                 license record│             │
   │             │                                 in DB:        │             │
   │             │                                 - salt (32B)  │             │
   │             │                                 - pepper (32B)│             │
   │             │                                 - login_count │             │
   │             │                              j) Derive delivery│             │
   │             │                                 key:          │             │
   │             │                                 HKDF(          │             │
   │             │                                   secret=license│             │
   │             │                                   + pepper,    │             │
   │             │                                   salt=salt    │             │
   │             │                                   + server_    │             │
   │             │                                   nonce,       │             │
   │             │                                   info="michi- │             │
   │             │                                   delivery-v2", │             │
   │             │                                   length=32)    │             │
   │             │                              k) Generate      │             │
   │             │                                 session_key    │             │
   │             │                                 (32 random)    │             │
   │             │                              l) Encrypt       │             │
   │             │                                 session_key    │             │
   │             │                                 with delivery_ │             │
   │             │                                 key using      │             │
   │             │                                 AES-GCM        │             │
   │             │                                              │             │
   │             │  9. Send AUTH_OK                             │             │
   │             │ ←──────── "AUTH_OK <base64(enc_session)>" ────│             │
   │             │                                              │             │
   │             │  10. Client decrypts session key             │             │
   │             │      a) Extract delivery key (same HKDF)      │             │
   │             │      b) Decrypt AES-GCM to get session_key    │             │
   │             │                                              │             │
   │             │ ══════════════════════════════════════════════════════════│════│
   │             │        LICENSE AUTHENTICATED - SESSION ESTABLISHED        │             │
   │             │        (All further messages encrypted with session_key) │             │
   └─────────────┘                                              └─────────────┘


┌─────────────────────────────────────────────────────────────────────────────────────┐
│                            PER-LICENSE CRYPTO ROTATION                              │
├─────────────────────────────────────────────────────────────────────────────────────┤

   Database Schema (licenses table):
   ┌─────────────────────────────────────────────────────────────────┐
   │ license_key  TEXT    PRIMARY KEY  ("ABCD1234-EFGH")              │
   │ salt_hex     TEXT    NOT NULL    (64 hex chars = 32 bytes)     │
   │ pepper_hex   TEXT    NOT NULL    (64 hex chars = 32 bytes)     │
   │ login_count  INTEGER              (tracks successful logins)    │
   │ rotated_at   TEXT                 (timestamp of last rotation)  │
   │ created_at   TEXT                 (initial registration time)   │
   └─────────────────────────────────────────────────────────────────┘

   Lifecycle:
   ┌─────────────────────────────────────────────────────────────────┐
   │  FIRST CONNECT                                                 │
   │  ────────────────                                               │
   │  1. Server checks DB for license_key                           │
   │  2. IF not found:                                              │
   │     • Generate random salt (os.urandom(32))                   │
   │     • Generate random pepper (os.urandom(32))                 │
   │     • Insert into DB with login_count=0                        │
   │     • Log: "New license registered: XXXX"                      │
   │  3. IF found:                                                  │
   │     • Retrieve existing salt, pepper, login_count              │
   │                                                                 │
   │  SUCCESSFUL SESSION END                                        │
   │  ──────────────────────                                        │
   │  1. Increment login_count by 1                                 │
   │  2. IF login_count % 5 == 0:                                   │
   │     • Generate NEW random salt (32 bytes)                      │
   │     • Generate NEW random pepper (32 bytes)                    │
   │     • Update DB with new values                               │
   │     • Update rotated_at timestamp                             │
   │     • Log: "Rotated salt/pepper for license XXX (login #N)"    │
   │  3. ELSE:                                                      │
   │     • Only update login_count                                  │
   │                                                                 │
   │  SECURITY IMPLICATIONS                                         │
   │  ──────────────────────                                        │
   │  • Each license has unique crypto material                     │
   │  • Rotation every 5 logins limits damage from compromise      │
   │  • Old sessions remain valid until disconnected                │
   │  • New connections get fresh delivery keys                     │
   │  • Rotation is server-side only, transparent to client         │
   └─────────────────────────────────────────────────────────────────┘


┌─────────────────────────────────────────────────────────────────────────────────────┐
│                            SECURITY LAYERS DETAIL                                   │
├─────────────────────────────────────────────────────────────────────────────────────┤

   Layer 1: License Structure Validation
   ┌─────────────────────────────────────────────────────────────────┐
   │ Format: XXXXXXXX-YYYY (13 chars total)                          │
   │                                                                 │
   │ Validation Steps:                                               │
   │  1. Length check: must be 13 characters                        │
   │  2. Separator check: position 8 must be "-"                    │
   │  3. Prefix check: positions 0-7 must be alphanumeric          │
   │  4. Suffix check: positions 9-12 must be alphanumeric          │
   │  5. Checksum validation:                                       │
   │     expected = SHA256(prefix + server_salt)[0:4].upper()       │
   │     return hmac.compare_digest(expected, suffix.upper())        │
   │                                                                 │
   │ Purpose: Prevents brute-force license generation               │
   │          Attacker needs server_salt to compute valid suffixes   │
   └─────────────────────────────────────────────────────────────────┘

   Layer 2: RSA-OAEP-SHA256 Handshake Encryption
   ┌─────────────────────────────────────────────────────────────────┐
   │ Algorithm: RSA with OAEP padding                              │
   │   • MGF1: SHA256                                                │
   │   • Hash: SHA256                                                │
   │   • Label: None                                                │
   │                                                                 │
   │ Key: RSA-2048 (generated by generate_keys.py)                  │
   │   • Private key: server_private_key.pem (chmod 600)            │
   │   • Public key: embedded in Java client                         │
   │                                                                 │
   │ Purpose: Protects license key and handshake data               │
   │          during initial connection establishment               │
   └─────────────────────────────────────────────────────────────────┘

   Layer 3: HKDF-SHA256 Key Derivation
   ┌─────────────────────────────────────────────────────────────────┐
   │ Formula:                                                        │
   │   delivery_key = HKDF(                                          │
   │     secret = license_bytes + lic_pepper,                         │
   │     salt = lic_salt + server_nonce,                             │
   │     info = b"michi-delivery-v2",                                │
   │     length = 32                                                 │
   │   )                                                              │
   │                                                                 │
   │ Components:                                                     │
   │   • license_bytes: UTF-8 encoded license key                   │
   │   • lic_pepper: 32-byte per-license random value (DB)           │
   │   • lic_salt: 32-byte per-license random value (DB)             │
   │   • server_nonce: 16-byte per-session random value              │
   │                                                                 │
   │ Purpose: Each session gets unique encryption key                │
   │          Per-license material adds compartmentalization          │
   │          Rotation limits impact of key compromise               │
   └─────────────────────────────────────────────────────────────────┘

   Layer 4: AES-256-GCM Session Encryption
   ┌─────────────────────────────────────────────────────────────────┐
   │ Algorithm: AES-GCM (Galois/Counter Mode)                        │
   │   • Key: 256 bits (session_key)                                │
   │   • Nonce: 12 bytes (random per message)                        │
   │   • Auth tag: 16 bytes (automatic)                              │
   │                                                                 │
   │ Frame Format:                                                   │
   │   wire = "DATA " + base64(seq_8 || nonce_12 || ciphertext || tag_16)│
   │   AAD = direction_byte (0x01=S→C, 0x02=C→S) || seq_8_BE         │
   │                                                                 │
   │ Sequence Numbers:                                               │
   │   • 64-bit big-endian counters                                  │
   │   • Separate counter per direction                              │
   │   • Starts at 0, increments per message                         │
   │   • Mismatch causes session termination                         │
   │                                                                 │
   │ Purpose: Confidentiality + integrity for all application data   │
   │          Sequence numbers prevent replay/reordering            │
   │          AAD binds frame to direction and position              │
   └─────────────────────────────────────────────────────────────────┘

   Layer 5: Anti-Replay Protection
   ┌─────────────────────────────────────────────────────────────────┐
   │ Nonce Cache:                                                    │
   │   • Stores client_nonce values from handshakes                 │
   │   • TTL: 300 seconds (5 minutes)                                │
   │   • Max entries: 4096 (auto-cleanup)                            │
   │   • Thread-safe with lock                                      │
   │                                                                 │
   │ Timestamp Window:                                               │
   │   • Handshake timestamp must be within +/- 60s of server time  │
   │   • Prevents replay of old captured handshakes                  │
   │                                                                 │
   │ Purpose: Prevents replay attacks using captured handshake data  │
   └─────────────────────────────────────────────────────────────────┘

   Layer 6: Rate Limiting & Connection Limits
   ┌─────────────────────────────────────────────────────────────────┐
   │ Per-IP Rate Limiter:                                            │
   │   • Sliding window: 60 seconds                                  │
   │   • Max connections: 30 per minute                              │
   │   • Response: "RATE_LIMIT\n"                                    │
   │                                                                 │
   │ Global Connection Limit:                                        │
   │   • BoundedSemaphore(200)                                       │
   │   • Response: "BUSY\n"                                          │
   │                                                                 │
   │ Purpose: Prevents DoS and resource exhaustion                  │
   └─────────────────────────────────────────────────────────────────┘


┌─────────────────────────────────────────────────────────────────────────────────────┐
│                            DATA PERSISTENCE FLOW                                     │
├─────────────────────────────────────────────────────────────────────────────────────┤

   UPLOAD SAVE DATA:
   ┌─────────────────┐
   │ Client GameState │
   │ (memory object) │
   └────────┬────────┘
            │ 1. Serialize to JSON
            ▼
   ┌─────────────────┐
   │   JSON String   │
   │ {"timestamp":..,│
   │  "player":{...}}│
   └────────┬────────┘
            │ 2. Base64 encode
            ▼
   ┌─────────────────┐     3. AES-GCM encrypt     ┌─────────────────┐
   │   Base64 String │───────────────────────────►│  Encrypted      │
   │   (data field)  │   with session_key        │  Payload        │
   └─────────────────┘                          └────────┬────────┘
                                                      │
                                                      ▼
                                           ┌─────────────────────┐
                                           │   JSON Message      │
                                           │ {"cmd":"UPLOAD",    │
                                           │  "data":"<base64>"} │
                                           └──────────┬──────────┘
                                                      │
                                                      ▼
                                           ┌─────────────────────┐
                                           │   DATA Frame        │
                                           │ "DATA <base64(...)>"│
                                           └──────────┬──────────┘
                                                      │
                                                      ▼
                                           ┌─────────────────────┐
                                           │   Network Wire      │
                                           │   (TCP)             │
                                           └──────────┬──────────┘
                                                      │
                                                      ▼
                                           ┌─────────────────────┐
                                           │   Server Receive    │
                                           │   Decrypt AES-GCM   │
                                           └──────────┬──────────┘
                                                      │
                                                      ▼
                                           ┌─────────────────────┐
                                           │   Validate JSON     │
                                           │   Extract timestamp │
                                           └──────────┬──────────┘
                                                      │
                                                      ▼
                                           ┌─────────────────────┐
                                           │   Check Conflict    │
                                           │   Compare timestamps │
                                           └──────────┬──────────┘
                                                      │
                                    ┌─────────────────┴─────────────────┐
                                    │                                   │
                                    ▼                                   ▼
                         ┌──────────────────┐              ┌──────────────────┐
                         │  Server newer     │              │  Client newer     │
                         │  (SYNC response)  │              │  (SAVED response) │
                         └────────┬─────────┘              └────────┬─────────┘
                                  │                                   │
                                  ▼                                   ▼
                         ┌──────────────────┐              ┌──────────────────┐
                         │  Send server data │              │  Store to DB      │
                         │  back to client   │              │  (saves table)    │
                         └──────────────────┘              └──────────────────┘


┌──────────────────────────────────────────────────────────────────────────────┐
│                            AUDIT LOGGING                                        │
├──────────────────────────────────────────────────────────────────────────────┤

   Events Table Schema:
   ┌─────────────────────────────────────────────────────────────────┐
   │ id          INTEGER PRIMARY KEY AUTOINCREMENT                   │
   │ ts          TEXT    NOT NULL    (UTC timestamp)                 │
   │ client_ip   TEXT    NOT NULL                                    │
   │ license_key TEXT    NOT NULL                                    │
   │ status      TEXT    NOT NULL    (e.g., "SAVED", "AUTH_FAIL")    │
   └─────────────────────────────────────────────────────────────────┘

   Logged Events:
   • PING (heartbeat, no auth)
   • AUTH_FAIL (various failure modes)
   • SAVED:<bytes> (successful upload)
   • SYNC_SERVER_NEWER (conflict resolution)
   • DOWNLOADED:<bytes> (successful download)
   • NO_SAVE (no save data exists)
   • RATE_LIMIT (IP throttled)
   • BUSY (server at capacity)

   Purpose: Forensics, debugging, abuse detection
```


##**SCHEMA DETALIATĂ - SERVER MULTIPLAYER**

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              MULTIPLAYER SERVER ARCHITECTURE                          │
├─────────────────────────────────────────────────────────────────────────────────────┤

┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              COMPONENTS OVERVIEW                                    │
├─────────────────────────────────────────────────────────────────────────────────────┤

   ┌─────────────────┐
   │  asyncio TCP   │  Port 7777 (default)
   │  Server         │  Max players: 8
   └────────┬────────┘
            │
            ▼
   ┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
   │  Rate Limiter   │────►│  Nonce Cache    │────►│  Player Manager │
   │  (per-IP 30/m)  │     │  (TTL 300s)     │     │  (max_players)  │
   └─────────────────┘     └─────────────────┘     └────────┬────────┘
                                                             │
                                                             ▼
                                                  ┌─────────────────────┐
                                                  │   Game Loop (20Hz)  │
                                                  │   - Position sync   │
                                                  │   - Chunk streaming │
                                                  │   - Trigger checks  │
                                                  └──────────┬──────────┘
                                                             │
                                    ┌────────────────────────┴────────────────────────┐
                                    │                                                 │
                                    ▼                                                 ▼
                          ┌─────────────────────┐                          ┌─────────────────────┐
                          │  World System       │                          │   SQLite Database   │
                          │  - TMX Maps         │                          │   - licenses        │
                          │  - Chunk streaming  │                          │   - salt/pepper     │
                          │  - Collision        │                          │   - login_count     │
                          └─────────────────────┘                          └─────────────────────┘


┌─────────────────────────────────────────────────────────────────────────────────────┐
│                            CONNECTION & HANDSHAKE FLOW                               │
├─────────────────────────────────────────────────────────────────────────────────────┤

   ┌─────────────┐                                              ┌─────────────┐
   │    CLIENT   │                                              │    SERVER   │
   │             │                                              │             │
   │  START:     │  1. TCP connect to multiplayer endpoint      │             │
   │  User joins │     (port 7777)                              │             │
   │  multiplayer│                                              │             │
   │  session    │                                              │             │
   │             │  2. Rate limit check (30/min per IP)         │             │
   │             │ ────────────────────────────────────────────→│             │
   │             │                                              │             │
   │             │                           IF rate limited:   │             │
   │             │ ←──────── "RATE_LIMIT\n" ─────────────────────│             │
   │             │     → Connection closed                      │             │
   │             │                                              │             │
   │             │  3. Server capacity check (max_players)       │             │
   │             │ ────────────────────────────────────────────→│             │
   │             │                                              │             │
   │             │                           IF server full:     │             │
   │             │ ←──────── "SERVER_FULL\n" ────────────────────│             │
   │             │     → Connection closed                      │             │
   │             │                                              │             │
   │             │  4. Send HELLO with client nonce              │             │
   │             │     "HELLO v2 <base64(client_nonce_16)>"      │             │
   │             │ ────────────────────────────────────────────→│             │
   │             │                                              │             │
   │             │                           5. Server validates:  │             │
   │             │                              • Protocol tag   │             │
   │             │                              • Nonce length   │             │
   │             │                                              │             │
   │             │  6. Server responds with nonce               │             │
   │             │ ←──────── "OK <base64(server_nonce_16)>" ─────│             │
   │             │                                              │             │
   │             │  7. Client builds handshake JSON              │             │
   │             │     {                                         │             │
   │             │       "license": "ABCD1234-EFGH",             │             │
   │             │       "ts": 1715123456,                       │             │
   │             │       "client_nonce": "<hex>",                │             │
   │             │       "server_nonce": "<hex>",                │             │
   │             │       "name": "PlayerName",                   │             │
   │             │       "class": "Fighter"                      │             │
   │             │     }                                         │             │
   │             │                                               │             │
   │             │  8. Encrypt with server RSA public key        │             │
   │             │     RSA-OAEP-SHA256(handshake_json)           │             │
   │             │                                               │             │
   │             │  9. Send AUTH                                 │             │
   │             │     "AUTH <base64(encrypted_handshake)>"      │             │
   │             │ ────────────────────────────────────────────→│             │
   │             │                                              │             │
   │             │                          10. Server performs:│             │
   │             │                              a) Decrypt with  │             │
   │             │                                 RSA private   │             │
   │             │                              b) Parse JSON    │             │
   │             │                              c) Validate      │             │
   │             │                                 license       │             │
   │             │                              d) Sanitize name │             │
   │             │                                 (whitelist)   │             │
   │             │                              e) Sanitize class│             │
   │             │                                 (alphanumeric)│             │
   │             │                              f) Check timestamp│             │
   │             │                                 (+/- 60s)     │             │
   │             │                              g) Verify nonces │             │
   │             │                              h) Check replay  │             │
   │             │                                 cache         │             │
   │             │                              i) Get/create    │             │
   │             │                                 license record│             │
   │             │                                 (async DB)     │             │
   │             │                              j) Derive delivery│             │
   │             │                                 key (HKDF)     │             │
   │             │                              k) Generate      │             │
   │             │                                 session_key    │             │
   │             │                              l) Encrypt       │             │
   │             │                                 session_key    │             │
   │             │                                 (AES-GCM)      │             │
   │             │                                              │             │
   │             │     IF validation fails:                     │             │
   │             │ ←──────── "AUTH_FAIL\n" ──────────────────────│             │
   │             │     → Connection closed                      │             │
   │             │                                              │             │
   │             │  11. Send AUTH_OK                            │             │
   │             │ ←──────── "AUTH_OK <base64(enc_session)>" ───│             │
   │             │                                              │             │
   │             │  12. Client decrypts session key             │             │
   │             │      (same HKDF + AES-GCM as save server)     │             │
   │             │                                              │             │
   │             │ ══════════════════════════════════════════════════════════│════│
   │             │        AUTHENTICATED - PLAYER SESSION ESTABLISHED           │             │
   └─────────────┘                                              └─────────────┘


┌─────────────────────────────────────────────────────────────────────────────────────┐
│                            PLAYER INITIALIZATION FLOW                                │
├─────────────────────────────────────────────────────────────────────────────────────┤

   ┌─────────────┐                                              ┌─────────────┐
   │    CLIENT   │                                              │    SERVER   │
   │             │                                              │             │
   │             │  13. Server allocates player ID               │             │
   │             │      (incrementing counter, starting at 1)    │             │
   │             │                                              │             │
   │             │  14. Server creates PlayerState:              │             │
   │             │      {                                         │             │
   │             │        player_id: <allocated>,                │             │
   │             │        name: <sanitized>,                     │             │
   │             │        player_class: <sanitized>,             │             │
   │             │        x: <spawn from TMX>,                   │             │
   │             │        y: <spawn from TMX>,                   │             │
   │             │        direction: 0,                          │             │
   │             │        sprite_num: 1,                         │             │
   │             │        attacking: false,                      │             │
   │             │        life: 6,                               │             │
   │             │        max_life: 6,                           │             │
   │             │        last_seen: <now>,                      │             │
   │             │        map_id: <active_map>,                  │             │
   │             │        chunks_sent: {},                        │             │
   │             │        chunk_requests: []                     │             │
   │             │      }                                         │             │
   │             │                                              │             │
   │             │  15. Send WELCOME message                     │             │
   │             │ ←──────── {"type":"welcome",                   │             │
   │             │            "id":<pid>,                         │             │
   │             │            "players":[...],                    │             │
   │             │            "spawn_x":<x>,                      │             │
   │             │            "spawn_y":<y>,                       │             │
   │             │            "map_id":"..."} ─────────────────────│             │
   │             │                                              │             │
   │             │  16. Send world info (TMX skeleton)            │             │
   │             │ ←──────── {"type":"world_info",                │             │
   │             │            "map_id":"...",                     │             │
   │             │            "width":<w>,                        │             │
   │             │            "height":<h>,                       │             │
   │             │            "tilewidth":<tw>,                   │             │
   │             │            "tileheight":<th>,                 │             │
   │             │            "layers":[...]} ────────────────────│             │
   │             │                                              │             │
   │             │  17. Broadcast PLAYER_JOIN to others          │             │
   │             │ ←──────── (not sent to joining player) ───────│             │
   │             │     Others receive:                           │             │
   │             │     {"type":"player_join",                    │             │
   │             │      "id":<pid>,                              │             │
   │             │      "name":"...",                            │             │
   │             │      "class":"..."}                           │             │
   │             │                                              │             │
   │             │  18. Client requests map chunks               │             │
   │             │     (as player moves around)                   │             │
   │             │                                              │             │
   │             │  19. Main gameplay loop begins                 │             │
   │             │     (client sends move/chat/ping)               │             │
   └─────────────┘                                              └─────────────┘


┌─────────────────────────────────────────────────────────────────────────────────────┐
│                            GAMEPLAY MESSAGE FLOW                                     │
├─────────────────────────────────────────────────────────────────────────────────────┤

   CLIENT → SERVER MESSAGES:
   ┌─────────────────────────────────────────────────────────────────┐
   │ MOVE:                                                            │
   │   {"type":"move",                                                │
   │    "x":<pixel_x>,                                               │
   │    "y":<pixel_y>,                                               │
   │    "dir":<0-7>,                                                 │
   │    "sprite":<0-32>,                                             │
   │    "attacking":<bool>,                                          │
   │    "life":<0-9999>,                                             │
   │    "maxLife":<1-9999>}                                         │
   │                                                                 │
   │ CHUNK_REQUEST:                                                  │
   │   {"type":"chunk_request",                                      │
   │    "layer":<layer_idx>,                                         │
   │    "cx":<chunk_x>,                                              │
   │    "cy":<chunk_y>}                                             │
   │                                                                 │
   │ WORLD_READY:                                                     │
   │   {"type":"world_ready"}                                        │
   │                                                                 │
   │ CHAT:                                                            │
   │   {"type":"chat",                                               │
   │    "msg":"<text, max 200 chars>"}                              │
   │                                                                 │
   │ PING:                                                            │
   │   {"type":"ping"}                                               │
   └─────────────────────────────────────────────────────────────────┘

   SERVER → CLIENT MESSAGES:
   ┌─────────────────────────────────────────────────────────────────┐
   │ WELCOME:                                                         │
   │   {"type":"welcome",                                            │
   │    "id":<player_id>,                                            │
   │    "players":[{...existing players...}],                        │
   │    "spawn_x":<x>,                                               │
   │    "spawn_y":<y>,                                               │
   │    "map_id":"<map_id>"}                                         │
   │                                                                 │
   │ WORLD_INFO:                                                      │
   │   {"type":"world_info",                                         │
   │    "map_id":"...",                                              │
   │    "width":<w>, "height":<h>,                                   │
   │    "tilewidth":<tw>, "tileheight":<th>,                         │
   │    "layers":[{"name":"...", "width":<w>, "height":<h>}]}        │
   │                                                                 │
   │ CHUNK_DATA:                                                      │
   │   {"type":"chunk_data",                                         │
   │    "layer":<layer_idx>,                                         │
   │    "cx":<chunk_x>,                                              │
   │    "cy":<chunk_y>,                                              │
   │    "data":[<tile_ids>]}                                         │
   │                                                                 │
   │ PLAYER_JOIN:                                                     │
   │   {"type":"player_join",                                        │
   │    "id":<pid>,                                                  │
   │    "name":"...",                                                │
   │    "class":"..."}                                               │
   │                                                                 │
   │ PLAYER_LEAVE:                                                    │
   │   {"type":"player_leave",                                       │
   │    "id":<pid>}                                                 │
   │                                                                 │
   │ PLAYER_UPDATE: (broadcast at 20Hz)                              │
   │   {"type":"player_update",                                      │
   │    "id":<pid>,                                                  │
   │    "x":<x>, "y":<y>, "dir":<dir>,                               │
   │    "sprite":<sprite>, "attacking":<bool>,                       │
   │    "life":<life>, "maxLife":<maxLife>}                          │
   │                                                                 │
   │ CHAT:                                                            │
   │   {"type":"chat",                                                │
   │    "from":"<player_name>",                                      │
   │    "msg":"<text>"}                                              │
   │                                                                 │
   │ PONG:                                                            │
   │   {"type":"pong"}                                               │
   │                                                                 │
   │ POS_CORRECTION:                                                  │
   │   {"type":"pos_correction",                                     │
   │    "x":<corrected_x>,                                           │
   │    "y":<corrected_y>,                                           │
   │    "reason":"collision"}                                        │
   │                                                                 │
   │ CHAT_THROTTLED:                                                  │
   │   {"type":"chat_throttled"}                                      │
   │                                                                 │
   │ KICK:                                                            │
   │   {"type":"kick",                                               │
   │    "reason":"..."}                                              │
   └─────────────────────────────────────────────────────────────────┘


┌─────────────────────────────────────────────────────────────────────────────────────┐
│                            SERVER-AUTHORITATIVE VALIDATION                           │
├─────────────────────────────────────────────────────────────────────────────────────┤

   Input Sanitization:
   ┌─────────────────────────────────────────────────────────────────┐
   │ NAME SANITIZATION:                                               │
   │   • Max length: 24 characters                                   │
   │   • Allowed chars: a-z, A-Z, 0-9, '_', '-', '.', ' '           │
   │   • Whitelist: set("abcdefghijklmnopqrstuvwxyz...")               │
   │   • Fallback: "Player" if empty/invalid                         │
   │                                                                 │
   │ CLASS SANITIZATION:                                             │
   │   • Max length: 16 characters                                   │
   │   • Allowed chars: alphanumeric only                            │
   │   • Fallback: "Fighter" if empty/invalid                        │
   │                                                                 │
   │ CHAT SANITIZATION:                                              │
   │   • Max length: 200 characters                                  │
   │   • Convert \r and \n to spaces                                 │
   │   • Rate limit: 5 messages per 10 seconds per player            │
   │   • Response: "chat_throttled" if exceeded                     │
   └─────────────────────────────────────────────────────────────────┘

   Value Clamping (Anti-Cheat):
   ┌─────────────────────────────────────────────────────────────────┐
   │ POSITION (x, y):                                                 │
   │   • Min: 0                                                       │
   │   • Max: map_width * tile_width - 1                             │
   │   • Max step per packet: 4 tiles (configurable)                │
   │   • Anti-teleport: clamp displacement from last_valid position │
   │                                                                 │
   │ DIRECTION:                                                       │
   │   • Min: 0                                                       │
   │   • Max: 7                                                       │
   │   • Represents 8 compass directions                             │
   │                                                                 │
   │ SPRITE_NUM:                                                      │
   │   • Min: 0                                                       │
   │   • Max: 32                                                      │
   │                                                                 │
   │ LIFE:                                                            │
   │   • Min: 0                                                       │
   │   • Max: 9999 (MAX_LIFE_CAP)                                    │
   │   • Prevents health hacking                                    │
   │                                                                 │
   │ MAX_LIFE:                                                        │
   │   • Min: 1                                                       │
   │   • Max: 9999 (MAX_LIFE_CAP)                                    │
   └─────────────────────────────────────────────────────────────────┘

   Collision Detection:
   ┌─────────────────────────────────────────────────────────────────┐
   │ Process for each MOVE packet:                                    │
   │                                                                 │
   │  1. Clamp x, y to map bounds                                    │
   │  2. Calculate displacement from last_valid_x, last_valid_y      │
   │  3. Clamp displacement to max_tile_step (anti-teleport)          │
   │  4. Calculate hitbox:                                           │
   │     • Hitbox width: player_hitbox_w (default 24px)              │
   │     • Hitbox height: player_hitbox_h (default 24px)            │
   │     • Hitbox offset x: (tile_width - hitbox_w) // 2            │
   │     • Hitbox offset y: tile_height - hitbox_h                  │
   │  5. Check if hitbox overlaps any solid tiles in TMX             │
   │  6. IF collision:                                                │
   │     • Snap back to last_valid_x, last_valid_y                   │
   │     • Send POS_CORRECTION to client                             │
   │     • Do NOT update position                                    │
   │  7. IF no collision:                                            │
   │     • Update player.x, player.y                                 │
   │     • Update last_valid_x, last_valid_y                         │
   │     • Check for trigger rectangles (enter/exit events)          │
   │     • Fire triggers if crossed                                  │
   └─────────────────────────────────────────────────────────────────┘


┌─────────────────────────────────────────────────────────────────────────────────────┐
│                            TICK LOOP (20Hz)                                            │
├─────────────────────────────────────────────────────────────────────────────────────┤

   ┌─────────────────────────────────────────────────────────────────┐
   │ INTERVAL = 1.0 / TICK_RATE = 0.05 seconds (50ms)                │
   │                                                                 │
   │ WHILE server_running:                                           │
   │   start_time = time.monotonic()                                 │
   │                                                                 │
   │   # Position Broadcast                                          │
   │   FOR each player in clients:                                   │
   │     update_msg = {                                              │
   │       "type": "player_update",                                  │
   │       "id": player.id,                                          │
   │       "x": player.x, "y": player.y,                             │
   │       "dir": player.direction,                                  │
   │       "sprite": player.sprite_num,                              │
   │       "attacking": player.attacking,                            │
   │       "life": player.life,                                      │
   │       "maxLife": player.max_life                                │
   │     }                                                           │
   │     FOR each other_player in clients (excluding self):         │
   │       other_player.send_json(update_msg)                        │
   │                                                                 │
   │   # Chunk Request Draining                                     │
   │   FOR each player in clients:                                   │
   │     max_chunks = cfg.max_chunks_per_tick (default 4)            │
   │     FOR i in range(max_chunks):                                 │
   │       IF player.chunk_requests is not empty:                    │
   │         request = player.chunk_requests.popleft()               │
   │         chunk_data = world.get_chunk(request.layer,            │
   │                                          request.cx,            │
   │                                          request.cy)            │
   │         player.send_json({                                      │
   │           "type": "chunk_data",                                 │
   │           "layer": request.layer,                               │
   │           "cx": request.cx,                                     │
   │           "cy": request.cy,                                     │
   │           "data": chunk_data                                    │
   │         })                                                      │
   │       ELSE:                                                      │
   │         BREAK                                                    │
   │                                                                 │
   │   elapsed = time.monotonic() - start_time                      │
   │   sleep_time = max(0.0, INTERVAL - elapsed)                     │
   │   await asyncio.sleep(sleep_time)                               │
   └─────────────────────────────────────────────────────────────────┘

   Purpose:
   • Synchronize player positions across all clients at 20Hz
   • Rate-limit chunk streaming to prevent writer queue overflow
   • Ensure consistent game state across all connected players


┌─────────────────────────────────────────────────────────────────────────────────────┐
│                            WORLD STREAMING SYSTEM                                     │
├─────────────────────────────────────────────────────────────────────────────────────┤

   Map Structure:
   ┌─────────────────────────────────────────────────────────────────┐
   │ TMX File (Tiled Map Editor format):                              │
   │   • width, height: dimensions in tiles                           │
   │   • tilewidth, tileheight: pixels per tile (default 32)         │
   │   • layers: array of tile layers                                │
   │   • objectgroup: trigger rectangles, spawn points               │
   │                                                                 │
   │ Chunk System:                                                    │
   │   • chunk_size: 32 tiles (configurable)                         │
   │   • Map divided into grid of chunks                             │
   │   • Client requests chunks as needed                            │
   │   • Server tracks chunks_sent per player                        │
   │   • Avoids re-sending already sent chunks                       │
   └─────────────────────────────────────────────────────────────────┘

   Chunk Request Flow:
   ┌─────────────┐                                              ┌─────────────┐
   │    CLIENT   │                                              │    SERVER   │
   │             │                                              │             │
   │  1. Client  │  2. Determine visible chunks based on       │             │
   │     loads   │     player position and viewport             │             │
   │     TMX     │                                              │             │
   │  skeleton   │                                              │             │
   │             │  3. For each needed chunk:                    │             │
   │             │     IF chunk not in chunks_sent:               │             │
   │             │       Send CHUNK_REQUEST                      │             │
   │             │       {"type":"chunk_request",                │             │
   │             │        "layer":<idx>,                         │             │
   │             │        "cx":<chunk_x>,                         │             │
   │             │        "cy":<chunk_y>}                        │             │
   │             │ ────────────────────────────────────────────→│             │
   │             │                                              │             │
   │             │  4. Server queues request (async)             │             │
   │             │     player.chunk_requests.append(request)      │             │
   │             │                                              │             │
   │             │  5. Tick loop drains queue (4 chunks/tick)    │             │
   │             │     a) Extract chunk data from TMX             │             │
   │             │     b) Send CHUNK_DATA                         │             │
   │             │        {"type":"chunk_data",                  │             │
   │             │         "layer":<idx>,                       │             │
   │             │         "cx":<cx>, "cy":<cy>,                 │             │
   │             │         "data":[<tile_ids>]}                  │             │
   │             │ ←────────────────────────────────────────────│             │
   │             │                                              │             │
   │             │  6. Client receives chunk data                │             │
   │             │     a) Add to local tile layer                 │             │
   │             │     b) Mark chunk as received                  │             │
   │             │     c) Render visible area                     │             │
   │             │                                              │             │
   │  7. As player moves, repeat chunk requests                    │
   └─────────────┘                                              └─────────────┘

   Benefits:
   • Bandwidth efficient: only send visible chunks
   • Fast initial load: skeleton TMX is small (~few KB)
   • Scalable: large maps don't require full download
   • Dynamic: chunks loaded/unloaded as player moves


┌─────────────────────────────────────────────────────────────────────────────────────┐
│                            SESSION MANAGEMENT                                         │
├─────────────────────────────────────────────────────────────────────────────────────┤

   Client Connection Lifecycle:
   ┌─────────────────────────────────────────────────────────────────┐
   │ INITIALIZATION:                                                  │
   │   • Create ClientConnection with reader, writer, session_key     │
   │   • Create PlayerState with allocated ID                         │
   │   • Start writer task (asyncio.Queue, maxsize 256)               │
   │   • Add to clients dict: clients[pid] = connection               │
   │                                                                 │
   │ MAIN LOOP (per client):                                          │
   │   WHILE server_running:                                          │
   │     • Wait for message with 15s timeout (ping timeout)          │
   │     • IF timeout:                                                │
   │       → Log "Player timed out"                                   │
   │       → Break loop (trigger disconnect)                          │
   │     • IF connection error:                                       │
   │       → Log "Player dropped"                                    │
   │       → Break loop                                               │
   │     • Process message based on type:                            │
   │       - move: validate, clamp, check collision                   │
   │       - chunk_request: queue for tick loop                       │
   │       - world_ready: log, no action                              │
   │       - chat: rate limit, broadcast if allowed                   │
   │       - ping: send pong                                          │
   │       - unknown: log debug, ignore                               │
   │     • Update player.last_seen                                   │
   │                                                                 │
   │ DISCONNECT:                                                      │
   │   • Cancel writer task                                           │
   │   • Close writer socket                                          │
   │   • Remove from clients dict                                     │
   │   • Increment login_count in DB (trigger rotation if needed)     │
   │   • Broadcast PLAYER_LEAVE to all other players                  │
   │   • Log disconnection                                            │
   └─────────────────────────────────────────────────────────────────┘

   Timeout Handling:
   ┌─────────────────────────────────────────────────────────────────┐
   │ PING_TIMEOUT: 15 seconds                                         │
   │   • If no message received within 15s, assume client dead        │
   │   • Force disconnect to free slot                                │
   │                                                                 │
   │ SESSION_IDLE_TIMEOUT: 60 seconds                               │
   │   • Currently not enforced in code (future enhancement)          │
   │   • Would kick inactive players                                 │
   │                                                                 │
   │ HANDSHAKE_TIMEOUT: 10 seconds                                    │
   │   • If handshake not complete in 10s, abort connection           │
   └─────────────────────────────────────────────────────────────────┘

   Graceful Shutdown:
   ┌─────────────────────────────────────────────────────────────────┐
   │ SIGNAL HANDLING (SIGINT, SIGTERM):                               │
   │   1. Set server_running = False                                  │
   │   2. Broadcast {"type":"kick", "reason":"server_shutdown"}      │
   │   3. Close all client connections                                │
   │   4. Close server socket                                        │
   │   5. Cancel all async tasks                                     │
   │   6. Log shutdown                                               │
   └─────────────────────────────────────────────────────────────────┘


┌─────────────────────────────────────────────────────────────────────────────────────┐
│                            ASYNC ARCHITECTURE                                          │
├─────────────────────────────────────────────────────────────────────────────────────┤

   Async Components:
   ┌─────────────────────────────────────────────────────────────────┐
   │ MAIN SERVER TASK:                                                │
   │   • asyncio.start_server()                                      │
   │   • Accepts incoming connections                                 │
   │   • Spawns handle_client() coroutine per connection             │
   │                                                                 │
   │ CLIENT HANDLER (per connection):                                │
   │   • Runs as async coroutine                                     │
   │   • Performs handshake (await readline, await drain)            │
   │   • Main message loop (await recv_json with timeout)            │
   │   • Non-blocking I/O throughout                                 │
   │                                                                 │
   │ WRITER TASK (per connection):                                   │
   │   • Separate asyncio.create_task() for each client              │
   │   • Reads from asyncio.Queue (maxsize 256)                       │
   │   • writer.write() + await writer.drain()                       │
   │   • Prevents blocking on slow clients                            │
   │   • QueueFull drops messages (log warning)                      │
   │                                                                 │
   │ TICK LOOP:                                                       │
   │   • Runs as async coroutine                                     │
   │   • Broadcasts positions at 20Hz                                │
   │   • Drains chunk requests (rate-limited)                         │
   │   • Uses asyncio.sleep() for timing                             │
   │                                                                 │
   │ DATABASE OPERATIONS:                                             │
   │   • SQLite is synchronous by default                             │
   │   • Use loop.run_in_executor() for DB calls                    │
   │   • Prevents blocking event loop                               │
   │   • Functions: _db_get_or_create(), _db_increment_and_rotate() │
   └─────────────────────────────────────────────────────────────────┘

   Thread Safety:
   ┌─────────────────────────────────────────────────────────────────┐
   │ NONCE CACHE:                                                      │
   │   • Uses asyncio.Lock (async-safe)                              │
   │   • All operations are async check_and_store()                  │
   │                                                                 │
   │ DATABASE:                                                        │
   │   • Uses threading.Lock (sync-safe)                             │
   │   • Called via run_in_executor() from async code                │
   │   • Only one DB operation at a time                             │
   │                                                                 │
   │ RATE LIMITER:                                                    │
   │   • No lock (single-threaded per IP check)                      │
   │   • collections.deque is thread-safe for append/popleft         │
   │                                                                 │
   │ CLIENTS DICT:                                                     │
   │   • No explicit lock (async single-threaded event loop)          │
   │   • All modifications happen in same thread                       │
   └─────────────────────────────────────────────────────────────────┘


┌─────────────────────────────────────────────────────────────────────────────────────┐
│                            SECURITY FEATURES SUMMARY                                   │
├─────────────────────────────────────────────────────────────────────────────────────┤

   Authentication:
   ┌─────────────────────────────────────────────────────────────────┐
   │ • License validation (same as save server)                       │
   │ • Per-license salt+pepper with rotation (every 5 logins)         │
   │ • RSA-OAEP-SHA256 handshake encryption                           │
   │ • HKDF-SHA256 key derivation                                     │
   │ • AES-256-GCM session encryption                                 │
   │ • Nonce replay cache (300s TTL)                                  │
   │ • Timestamp validation (+/- 60s)                                 │
   └─────────────────────────────────────────────────────────────────┘

   Anti-Cheat:
   ┌─────────────────────────────────────────────────────────────────┐
   │ • Server-authoritative position validation                       │
   │ • Anti-teleport (max step per packet)                             │
   │ • Collision detection against TMX map                             │
   │ • Life clamping (0-9999)                                         │
   │ • Position correction sent to client on collision                │
   │ • Name/class sanitization (whitelist)                           │
   │ • Chat rate limiting (5 per 10s)                                 │
   └─────────────────────────────────────────────────────────────────┘

   DoS Protection:
   ┌─────────────────────────────────────────────────────────────────┐
   │ • Per-IP rate limiting (30/min)                                  │
   │ • Max player cap (8 default)                                     │
   │ • Bounded write queues (256 messages max)                        │
   │ • Chunk streaming rate limit (4 per tick)                       │
   │ • Async architecture (no blocking I/O)                           │
   │ • Connection timeouts (handshake 10s, ping 15s)                  │
   └─────────────────────────────────────────────────────────────────┘
```


##**Sumar Comparativ de Securitate**

| Feature | Save Server | Patch Server | Multiplayer Server |
|---------|-------------|--------------|-------------------|
| **Protocol** | TCP sync | TCP sync | TCP async |
| **Handshake** | RSA-OAEP + AES-GCM | - | RSA-OAEP + AES-GCM |
| **Session Crypto** | AES-256-GCM | - | AES-256-GCM |
| **Key Derivation** | HKDF-SHA256 | - | HKDF-SHA256 |
| **Per-License Crypto** | Salt+Pepper (rotativ) | - | Salt+Pepper (rotativ) |
| **Signing** | - | RSA-2048 PKCS#1v15 | - |
| **Replay Protection** | Nonce cache + timestamps | - | Nonce cache + timestamps |
| **Rate Limiting** | 30/min/IP | 60/min/IP | 30/min/IP |
| **Max Connections** | 200 | 32 | 8 players |
| **Input Validation** | JSON + Base64 | Version strings | Clamp + Whitelist |
| **Audit Logging** | SQLite events table | stdout | stdout |

##**Recomandari Operationale**

1. **Key Management**: Rulati `generate_keys.py` si `generate_patch_keys.py` pe un sistem securizat, transferati doar private keys pe servere si niciodata prin canale nesecurizate.

2. **License Salt**: Generati un salt unic per server deployment in `server_config.json` / `mp_config.json`.

3. **Backup DB**: Faceti backup periodic la `saves.db` si `mp_licenses.db` - contin datele criptografice per-licenta.

4. **Firewall**: Restrictionati accesul la porturile 5005, 5006, 7777 doar de la IP-urile necesare.

5. **Logs**: Monitorizati `server.log` pentru pattern-uri de atac (replay, rate limit hits).

