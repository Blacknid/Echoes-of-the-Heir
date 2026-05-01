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
   │  PREPARE:   │  1. Serialize GameState to JSON             │              │
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

