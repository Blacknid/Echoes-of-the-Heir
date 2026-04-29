# Michi's Adventure — Servers & Security

This document explains how the **cloud save server** and the **multiplayer
server** work, what their wire protocols look like, what guarantees they
give, and what the threat model is.

There are two completely independent server programs:

| Server | Path | Default port | Purpose |
| --- | --- | --- | --- |
| Cloud save | `ceva/server/server.py` | 5005 (TCP) | Stores per-license game saves, syncs by timestamp. |
| Multiplayer | `ceva/multiplayer_server/server.py` | 7777 (TCP) | Real-time position sync between players in a game session. |

Both use the **same security stack** (RSA-OAEP-SHA256 + AES-256-GCM + HKDF-SHA256
+ license auth + replay protection + per-IP rate limiting). They do **not**
share state, do **not** share databases, and can run on different machines.

---

## 1. Identity: license keys

The single shared secret tying a player to the servers is their **license
key**, in the format `XXXXXXXX-YYYY` where:

- `XXXXXXXX` is 8 random alphanumeric characters (base 36).
- `YYYY` is `SHA-256(prefix + LICENSE_SALT)` truncated to 4 hex chars.

Servers validate keys without contacting any external system:

```python
expected = sha256(prefix + LICENSE_SALT).hexdigest()[:4].upper()
hmac.compare_digest(expected, suffix.upper())
```

The **structural** validity of a license (its check digit) is verified using
`license_salt` from the server config. This is the **only global secret**.

Once a license passes the structural check, its **per-connection crypto
material** (salt + pepper for HKDF delivery-key derivation) is looked up from
the server's `licenses` database table — or created fresh on first connect:

```
licenses table (saves.db / mp_licenses.db)
  license_key   TEXT PRIMARY KEY
  salt_hex      TEXT   — 32 random bytes, hex-encoded
  pepper_hex    TEXT   — 32 random bytes, hex-encoded
  login_count   INT    — incremented every successful session
  rotated_at    TEXT   — timestamp of last salt/pepper rotation
  created_at    TEXT
```

Every **5 successful logins** the server atomically replaces the salt and
pepper with new `os.urandom(32)` values. Rotation happens server-side only,
inside the already-authenticated session, and is invisible to the client.

The Java `LicenseGenerator` generates a fresh random prefix each run and
computes the check-digit suffix from `license_salt`. **Two separate runs
always produce different licenses**, so two JARs built for different users
will never share a license key.

> **Caveat.** The check digit is only 16 bits of entropy (≈65 k possible
> suffixes). On its own, this is brute-forceable. The server therefore
> applies a **per-IP sliding-window rate limit** (default 30 attempts per
> minute) and logs every attempt in `events` (cloud save) or the journal
> (multiplayer). To raise the bar further, increase the suffix length in
> `LicenseGenerator.computeSuffix` and update both servers' `[:4]` slice.

---

## 2. Crypto stack (shared by both servers)

```
                     ┌──────────────────────────────────┐
                     │  RSA-2048  +  OAEP-SHA256        │  ← handshake
                     │  (asymmetric, license-binding)   │
                     ├──────────────────────────────────┤
                     │  HKDF-SHA256                     │  ← key derivation
                     │  (delivery + session keys)       │
                     ├──────────────────────────────────┤
                     │  AES-256-GCM                     │  ← session
                     │  (AEAD, 12-byte nonce, 128-bit   │
                     │   auth tag)                      │
                     ├──────────────────────────────────┤
                     │  Per-direction sequence counter  │  ← anti-replay
                     │  bound into AES-GCM AAD          │
                     └──────────────────────────────────┘
```

### Why these primitives

| Choice | Reason |
| --- | --- |
| **RSA-OAEP-SHA256** instead of PKCS1v15 | OAEP avoids Bleichenbacher-style padding-oracle attacks. |
| **AES-GCM** instead of AES-CBC | GCM is AEAD, so any tampering or replay is detected by tag verification — no padding oracle, no integrity gap. |
| **HKDF-SHA256** for delivery keys | Replaces the previous `SHA-256(license)` delivery key. Mixes in a per-handshake server nonce and a static pepper, so even if a license leaks, an attacker can't decrypt past sessions without observing the live handshake. |
| **Sequence counter in AAD** | Each direction has its own monotonically increasing counter. AES-GCM authenticates that counter as additional data, so dropping, reordering, or replaying a frame breaks decryption. |
| **Server timestamp + nonce check** | The handshake AUTH frame contains a `ts` (must be within ±60 s) and the client's nonce (cached server-side for 5 minutes). Replayed handshakes fail. |

### Wire formats (shared)

All lines are UTF-8 newline-terminated.

```text
PING path  (no auth):
  C ─► "PING"
  S ─► "PONG"

Authenticated path (4 lines):
  C ─► "HELLO v2 <base64(client_nonce_16)>"
  S ─► "OK <base64(server_nonce_16)>"
  C ─► "AUTH <base64(rsa_oaep_sha256(handshake_json))>"
       handshake_json = {
         "license":      "XXXXXXXX-YYYY",
         "ts":           <unix epoch s>,
         "client_nonce": <hex>,
         "server_nonce": <hex>,
         ... server-specific extras (e.g. MP includes name & class)
       }
  S ─► "AUTH_OK <base64(aesgcm(session_key, key=delivery_key,
                              nonce=client_nonce[:12], aad=<context>))>"

Then both sides exchange AEAD-framed JSON:
  "DATA <base64(seq_8 || nonce_12 || ciphertext || tag_16)>"
  AAD = direction_byte (0x01=server→client, 0x02=client→server) || seq_8

Server-only failure responses (sent before/instead of OK/AUTH_OK):
  "AUTH_FAIL"   bad license, replay, stale ts, malformed AUTH
  "RATE_LIMIT"  IP exceeded the per-minute connection budget
  "SERVER_FULL" multiplayer-only: max players reached
  "BUSY"        save-only: max concurrent connections reached
```

`<context>` is `MichiCloudSession` for the save server and `MichiMpSession`
for the multiplayer server. This **domain-separates** sessions across the
two services even when they share the same RSA keypair.

---

## 3. Cloud save server (`ceva/server/server.py`)

### Storage

- `saves.db` — SQLite (WAL). Two tables:
  - `saves(license_key PK, save_data BLOB, game_timestamp, size_bytes, updated_at)`
  - `events(id, ts, client_ip, license_key, status)` — audit log.
- `saves/<license>.bin` — legacy file mirror; the server transparently
  migrates these into the database on first read.

### Configuration

`server_config.json` (next to `server.py`) overrides the defaults:

```jsonc
{
  "host":                          "0.0.0.0",
  "port":                          5005,
  "private_key_path":              "server_private_key.pem",
  "rate_limit_per_ip_per_minute":  30,
  "max_concurrent_connections":    200,
  "handshake_timeout_seconds":     10,
  "session_timeout_seconds":       30,
  "max_payload_bytes":             10485760
}
```

`MICHI_SAVE_HOST` and `MICHI_SAVE_PORT` env vars take precedence over the
file (handy for systemd drop-ins).

### Session commands (after AEAD wrapping is established)

```jsonc
// Client → server
{"cmd":"UPLOAD",  "data":"<base64-of-save-bytes>"}
{"cmd":"DOWNLOAD"}

// Server → client
{"status":"SAVED"}                                      // upload OK
{"status":"SYNC",       "data":"<base64-newer-save>"}   // server has newer copy
{"status":"DOWNLOADED", "data":"<base64-save>"}         // download payload
{"status":"NO_SAVE"}                                    // empty slot
{"status":"ERROR",      "msg":"..."}                    // bad request
```

### Setup (Raspberry Pi OS / Bookworm)

```bash
cd ceva/server
sudo bash setup.sh
```

The script (7 steps):
1. Installs `python3-cryptography`, `sqlite3`, `ufw` via apt; pip-upgrades if
   the apt version is too old for HKDF/AESGCM.
2. Creates `saves/` and `server.log`.
3. Generates `server_private_key.pem` (RSA-2048, mode 0600) +
   `server_public_key.b64` if no key exists yet. Prints a reminder to paste
   the public key into `CloudSaveService.RSA_PUBLIC_KEY_B64`.
4. Copies `server_config.example.json` → `server_config.json` if no config
   exists. **Edit `server_config.json` to set `license_salt`,
   `license_pepper`, port, etc. before your first real run.**
5. Opens the configured port in `ufw`.
6. Installs and starts the `michi-save` systemd service (auto-restart).
7. Verifies the service is active.

Useful commands after setup:
```bash
sudo systemctl status michi-save
journalctl -u michi-save -f
sqlite3 ceva/server/saves.db
```

### Multiple save servers (client-side pool)

The game reads `save_servers.txt` from its working directory. One endpoint
per line, `host` or `host:port`, `#` for comments. Example:

```text
primary.example.com:5005
192.168.1.13
192.168.100.235:5005
```

The client tries each entry in order and uses the first that responds to
`PING`. If `save_servers.txt` is missing, the client falls back to the
hostnames embedded in `CloudSaveService.FALLBACK_HOSTS`.

> **Future direction.** When the server fleet becomes a true cluster, the
> recommended path is:
>
> 1. Replicate `saves` rows by license-key timestamp using rqlite or Litestream.
> 2. Have each node accept any client; conflict resolution is already
>    timestamp-based and idempotent.
> 3. Optionally surface a "preferred home" node hint in the
>    `AUTH_OK` response so clients can pin to one shard.

### Offline cache

When **no save server** in the pool responds, the client encrypts the
state locally:

```
local_save.dat layout:
  4 bytes  magic    "MICH"
  1 byte   version  0x02
 12 bytes  nonce    (random per save)
   N+16    AES-GCM(plaintext, key=local_key, nonce, aad="michi-local")

local_key = HKDF-SHA256(
    secret = (license_key || ":local")  or  "<no-license>",
    salt   = SHA-256(user || hostname || MAC bytes of active interfaces),
    info   = "michi-local-v2",
    length = 32 bytes,
)
```

No plaintext key is ever written to disk (the legacy `local_aes.key` from
v1 is deleted on first successful local save). Decryption is bound to the
licensed user **and** the machine fingerprint, so a stolen `local_save.dat`
on a different machine is useless.

When a save server comes back online, the heartbeat thread automatically
uploads the cached state and removes both `local_save.dat` and the legacy
`local_aes.key`.

### Threat coverage

| Threat | Mitigation |
| --- | --- |
| Network sniffing | All session traffic is AES-GCM encrypted with a fresh per-connection key. |
| Active MitM | RSA-OAEP handshake binds to the client nonce; without the server's RSA private key, an attacker can't issue a valid `AUTH_OK`. |
| Replay of recorded handshake | Server enforces a ±60 s `ts` window and a 5-minute nonce cache. |
| Replay/reorder of session frames | Direction-tagged sequence counter is bound into the GCM AAD. Mismatch → AEAD failure → connection drop. |
| Padding oracle | AES-GCM has no padding; failures return a single generic error. |
| Brute-forced license key | 30 connection attempts per IP per minute, all logged. |
| DoS via connection flood | Bounded `max_concurrent_connections` semaphore + per-IP rate limit; excess connections get `BUSY` or `RATE_LIMIT` and close. |
| Stolen offline save file | Encrypted with key derived from license + machine fingerprint; not portable. |

### Known residual risks

- The license check digit is only 16 bits of integrity. Rate limiting makes
  brute force impractical online but offline it remains weak. Increase the
  suffix length in `LicenseGenerator.computeSuffix` and both servers'
  `[:4]` slice if you need stronger structural validation.
- The RSA public key is embedded in the Java client. An attacker with the
  binary can replace it and point the game at a hostile server. Code signing
  (jarsigner) is the right next step if you ship to untrusted machines.
- `license_salt` lives only in `server_config.json` / `mp_config.json` —
  never in Python source. Treat these files like secrets (`chmod 600`).
- Per-license salt+pepper live in the DB. Back up `saves.db` and
  `mp_licenses.db` — losing them means existing sessions can no longer
  authenticate until the DB is rebuilt (at which point all clients
  auto-re-register on next connect).

---

## 4. Multiplayer server (`ceva/multiplayer_server/server.py`)

### What changed in v2

The previous multiplayer server had **no authentication and no encryption**.
v2 mirrors the cloud-save security stack, layered on top of the original
JSON message envelope so the gameplay surface is unchanged.

### Bind IP / port

The server **asks for the bind IP and port at startup**. This is also
selectable via:

```bash
python3 server.py --host 0.0.0.0 --port 7777    # explicit
python3 server.py --no-prompt                   # combine with env vars
MICHI_MP_HOST=10.0.0.5 MICHI_MP_PORT=7777 python3 server.py
```

`setup.sh` asks for the bind IP/port once and bakes them into the systemd
service, so `michi-multiplayer.service` runs unattended.

### Application messages (after AEAD wrapping)

Same JSON envelope as before, with server-side validation added:

```jsonc
// Client → server
{"type":"move",     "x":..,"y":..,"dir":..,"sprite":..,
                    "attacking":bool,"life":..,"maxLife":..}
{"type":"chat",     "msg":"..."}
{"type":"ping"}

// Server → client
{"type":"welcome",       "id":<int>, "players":[...]}
{"type":"player_join",   "id":<int>, "name":"...", "class":"..."}
{"type":"player_leave",  "id":<int>}
{"type":"player_update", "id":<int>, "x":.., ... }   // broadcast at TICK_RATE
{"type":"chat",          "from":"...", "msg":"..."}
{"type":"chat_throttled"}                            // when the rate limit hits
{"type":"pong"}
{"type":"kick",          "reason":"..."}
```

### Server-authoritative validation

To stop trivial cheat clients:

- `name` is sanitized to `[A-Za-z0-9 _.-]` and capped at 24 chars.
- `class` is alphanumeric only, capped at 16 chars.
- `life` and `maxLife` are clamped to `[0, 9999]`.
- `direction` ∈ `[0, 7]`, `sprite` ∈ `[0, 32]`.
- `x`, `y` clamped to `±10⁷` (sanity check, not anti-teleport).
- Chat: maximum **5 messages per 10 seconds** per connection. Excess sends
  back `chat_throttled` and is dropped (no broadcast).

> The MP server still trusts clients for *position* — the existing
> protocol is broadcast-only, not authoritative simulation. If you need
> actual anti-cheat, simulate movement server-side and validate against
> the previous tick's position.

### Connection limits

- `MAX_PLAYERS_DEFAULT = 8` (override via `--max-players`).
- `RATE_LIMIT_PER_IP_PER_MIN = 30` connection attempts.
- Per-connection write queue is bounded (256 frames). Slow consumers get
  their oldest messages dropped rather than freezing the server.
- Idle clients are kicked after `PING_TIMEOUT = 15 s` of silence.

### Setup (Raspberry Pi OS / Bookworm)

```bash
cd ceva/multiplayer_server
sudo bash setup.sh
```

The script (7 steps):
1. Installs `python3-cryptography`, `ufw` via apt; pip-upgrades if needed.
2. Makes `server.py` executable.
3. RSA key logic (in order of priority):
   a. Reuses `../server/server_private_key.pem` if the save server was set
      up on the same Pi (shared keypair, simplest).
   b. Otherwise generates a fresh RSA-2048 keypair in this directory and
      prints a reminder to embed the public key in
      `MultiplayerClient.RSA_PUBLIC_KEY_B64`.
4. Copies `mp_config.example.json` → `mp_config.json` if not present.
   **Edit `mp_config.json` to set `license_salt`, `license_pepper`,
   `max_players`, etc.**
5. Prompts for bind IP/port (defaults from config), writes them back into
   `mp_config.json`, and opens the port in `ufw`.
6. Installs and starts the `michi-multiplayer` systemd service running
   `server.py --no-prompt` (host/port come from `mp_config.json`).
7. Verifies the service is active.

Useful commands after setup:
```bash
sudo systemctl status michi-multiplayer
journalctl -u michi-multiplayer -f
```

> **Keypair note.** If you generate a **separate** keypair for the MP server
> (step 3b above), the Java client embeds *one* `RSA_PUBLIC_KEY_B64` shared
> by both services. You must split that constant in `MultiplayerClient.java`
> and `CloudSaveService.java` so each points to the correct key.

---

## 5. Patch server (`ceva/patch_server/server.py`)

A separate, **independent** server that delivers signed game-update patches.
It is intentionally decoupled from the cloud-save and multiplayer stacks:
patching is *publisher-to-player* and uses a different RSA keypair from
the save-server handshake. **Licenses are not involved at all** — every JAR
keeps the same per-build license forever.

### Wire protocol

```
PING\n                                       → PONG\n
CHECK <current_version>\n                    → UPTODATE\n
                                             | UPDATE <to> <size> <sha256_hex> <sig_b64>\n
FETCH <from_version>\n                       → 8 bytes BE size, then raw patch ZIP
```

The signature in the `UPDATE` line covers
`SHA-256(patch_bytes) || from || to`, so a malicious mirror cannot serve an
unrelated (still-validly-signed) patch in place of the real one. The client
recomputes this payload after download and verifies with the embedded
`UpdateClient.PATCH_PUBLIC_KEY_B64`.

### Patch ZIP layout

```
patch.zip
├── manifest.json       {"from": "x.y.z", "to": "a.b.c",
│                        "replace": [...], "add": [...], "delete": [...]}
├── add/<entry>         new files inside the JAR
└── replace/<entry>     replacement bytes for changed files
```

Files **outside** the JAR are never touched by a patch:
`license.properties`, `save_servers.txt`, `update_servers.txt`,
`servers.txt`, `local_save.dat`. The current version is read from
`/res/build.properties` *inside* the JAR — auto-incremented by
`compile.cmd` on every build, and overwritten by the patch as a normal
replace[] entry. There is no external version file to keep in sync.

### Building a patch

```bash
cd ceva/patch_server
python3 build_patch.py /path/to/old.jar /path/to/new.jar 1.0.0 1.0.1
# → patches/v1.0.0_to_v1.0.1.zip
# → manifest.json updated, signature appended
sudo systemctl restart michi-patch    # or send SIGHUP if you add one
```

`build_patch.py` reads both JARs, diffs their entries, ZIPs the differences
into the patch layout above, signs the SHA-256 with `patch_private_key.pem`,
and registers the result in `manifest.json` (which the server reloads on
every connection).

### Setup (Raspberry Pi OS / Bookworm)

```bash
cd ceva/patch_server
sudo bash setup.sh
```

Same shape as the other setup scripts: apt deps, generates
`patch_private_key.pem` if missing, copies the example config, opens the
configured port in `ufw`, installs the `michi-patch` systemd service.

### Client-side flow

1. `Main.main()` calls `UpdateClient.checkAndApply()` **before** anything
   else (window, game thread, license parse).
2. Client reads its version from `/res/build.properties` inside the
   running JAR (default `0.0.0` if absent) and tries each entry in
   `update_servers.txt` (or `FALLBACK_HOSTS` if absent).
3. On `UPDATE`: shows a confirmation dialog. If accepted, downloads the
   patch over a new `FETCH` connection, recomputes SHA-256, verifies the
   RSA signature, and writes the patch to a temp file.
4. Spawns `update.Updater` as a **separate JVM** (so this process can
   release the JAR), then `return`s from `main()` so the game exits.
5. The updater waits for the parent PID, rewrites the JAR by overlaying
   the patch's `add/` + `replace/` entries and dropping `delete[]` entries,
   atomically swaps the new JAR in, and relaunches the game. The new
   version takes effect immediately because `/res/build.properties`
   inside the JAR was updated as part of the patch.

If the patch server is unreachable in step 2, startup continues normally —
the game stays playable offline. If the user **declines** a confirmed
update, the game closes (per design: "the game cannot start until you
make the update").

### Threat coverage (patches)

| Threat | Mitigation |
| --- | --- |
| Forged patch from a hostile mirror | RSA-2048 + SHA-256 signature on `(patch_hash, from, to)`. Public key embedded in client. |
| Downgrade attack | Signature is bound to *both* `from` and `to` versions; mirror cannot replay an old (from, to) pair to a fresher client. |
| Truncated download | Client checks declared size and recomputes SHA-256 before applying. |
| JAR locked on Windows during write | Updater is a separate JVM that waits for the game's PID to exit, then swaps the file. |
| Failed mid-write | Atomic `Files.move`; original is kept as `.bak` and restored on failure. |
| Tampering with external configs | The patch only writes the JAR file. License and server lists, save files, and config files outside the JAR are never touched. |

---

## 6. Java client side

| File | Responsibility |
| --- | --- |
| `data/CloudSaveService.java` | Cloud-save handshake, AEAD framing, server pool, offline cache. Heartbeat thread pings the pool and uploads pending cached saves once a server returns. |
| `main/MultiplayerClient.java` | Multiplayer handshake (same crypto stack), AEAD framing, gameplay messages. The IP/port comes from the in-game UI (Direct Connect or saved-server list); this class never hardcodes a host. |
| `main/ServerListManager.java` | The user's saved **multiplayer** servers (`servers.txt`, `name|ip|port` per line). Independent of the save-server pool. |

### Where each config file lives

| Path | Purpose |
| --- | --- |
| `license.properties` | License key for this player (written at build time by `LicenseGenerator`). |
| `save_servers.txt` | List of cloud-save servers to try, in order. |
| `update_servers.txt` | List of patch servers to ping at startup. |
| `servers.txt` | List of multiplayer servers the player has saved in-game. |
| `local_save.dat` | Encrypted offline save cache (AEAD-GCM, license-bound). |

**Server-side DB files** (on the Pi):

| Path | Purpose |
| --- | --- |
| `ceva/server/saves.db` | Save data + per-license crypto table (`licenses`) + event log. |
| `ceva/multiplayer_server/mp_licenses.db` | Per-license crypto table for the MP server. |

### Crypto helpers

Both Java clients implement:

- `rsaOaepEncrypt(byte[])` using `RSA/ECB/OAEPPadding` + explicit
  `OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, ...)`
  (Java's default MGF1 is SHA-1, which would break interop with Python).
- `aesGcmEncrypt/Decrypt(plain, key, nonce, aad)` using
  `AES/GCM/NoPadding` and `GCMParameterSpec(128, nonce)`.
- `hkdf(secret, salt, info, length)` — RFC 5869 implementation on top of
  `HmacSHA256`.

---

## 7. Operational checklist

When deploying (Raspberry Pi 5 / Raspberry Pi OS):

- [ ] `sudo bash ceva/server/setup.sh` — installs save server, generates keys,
      creates `michi-save` systemd service.
- [ ] Edit `ceva/server/server_config.json`: choose a strong random
      `license_salt` (e.g. `python3 -c "import secrets; print(secrets.token_hex(24))"`)
      and set `port`.
- [ ] Copy the public key from `server_public_key.b64` into
      `CloudSaveService.RSA_PUBLIC_KEY_B64` in the Java source; recompile.
- [ ] `sudo bash ceva/multiplayer_server/setup.sh` — installs MP server,
      creates `michi-multiplayer` systemd service.
- [ ] Edit `ceva/multiplayer_server/mp_config.json`: **`license_salt` must
      exactly match** the save server's config. Remove `license_pepper` — it
      is now per-license and lives in the DB only.
- [ ] If the MP server used a **separate** keypair (step 3b), copy its public
      key into `MultiplayerClient.RSA_PUBLIC_KEY_B64` too.
- [ ] `chmod 600 server_private_key.pem` on both server directories.
- [ ] **Generate license keys** for each JAR:
      ```bash
      java data.LicenseGenerator license_player1.properties "<your-license_salt>"
      java data.LicenseGenerator license_player2.properties "<your-license_salt>"
      ```
      Each run produces a unique key. Bundle the matching `.properties` file
      into each JAR as `license.properties`.
- [ ] Distribute `save_servers.txt` next to the game executable.
- [ ] Verify: `sudo systemctl status michi-save michi-multiplayer`
- [ ] Back up `saves.db` and `mp_licenses.db` regularly — they contain
      per-license crypto material that cannot be regenerated.
- [ ] `sudo bash ceva/patch_server/setup.sh` — installs patch server,
      generates the patch-signing keypair, creates `michi-patch` systemd service.
- [ ] Copy the public key from `patch_server/patch_public_key.b64` into
      `UpdateClient.PATCH_PUBLIC_KEY_B64` in the Java source; recompile.
- [ ] Distribute `update_servers.txt` next to the game JAR (`compile.cmd`
      bundles it automatically; the Inno Setup installer ships it too).
      The version itself lives in `/res/build.properties` inside the JAR.

When publishing a new game version:

- [ ] On a build host, produce `new.jar` (with the new code) and keep the
      previous `old.jar` accessible.
- [ ] On the patch server: `python3 build_patch.py old.jar new.jar 1.0.0 1.0.1`.
- [ ] `sudo systemctl restart michi-patch` (manifest is reread per-connection,
      but a restart guarantees a clean state).
- [ ] Optionally repeat `build_patch.py` for older `from` versions if you
      want to support multi-step jumps in a single download.

When rotating the global salt:

- [ ] Update `license_salt` in **`server_config.json`** and **`mp_config.json`** only.
- [ ] Re-generate all license keys using the new salt (see above).
- [ ] Restart both services; old licenses fail `AUTH_FAIL` immediately.
- [ ] The per-license salt+pepper in the DB auto-rotate every 5 logins;
      **no manual intervention needed for those**.

When a license leaks:

- [ ] Add the license to a denylist column in `saves` (or maintain a
      separate `revoked_licenses` table) and check it inside the AUTH
      handler before allocating a session key.

---

## 8. Glossary

- **AEAD** — Authenticated Encryption with Associated Data. The cipher
  produces both ciphertext and a tag that authenticates *the ciphertext
  plus the AAD*. Tampering with either causes decryption to fail.
- **HKDF** — HMAC-based Key Derivation Function. Turns one secret into
  many domain-separated keys with a salt + info string.
- **MGF1** — Mask Generation Function used inside RSA-OAEP. **Always
  pin it to SHA-256** when interoperating between Python and Java.
- **Nonce** — number used once. AES-GCM requires that no `(key, nonce)`
  pair ever encrypt two different plaintexts; we draw nonces from
  `SecureRandom`/`os.urandom`, which is safe at the message rates used here.
