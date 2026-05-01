# Patch Server — How it works

Everything in this document describes `SERVERS/patch_server/`. Nothing here
touches the cloud-save or multiplayer servers.

---

## Table of contents

1. [Architecture overview](#1-architecture-overview)
2. [Server internals](#2-server-internals)
3. [Wire protocol](#3-wire-protocol)
4. [How a patch is created](#4-how-a-patch-is-created)
5. [Patch file format](#5-patch-file-format)
6. [Signature and integrity](#6-signature-and-integrity)
7. [How the client uses a patch](#7-how-the-client-uses-a-patch)
8. [The Updater process](#8-the-updater-process)
9. [Version tracking](#9-version-tracking)
10. [Security properties](#10-security-properties)
11. [Quick-reference commands](#11-quick-reference-commands)

---

## 1. Architecture overview

```
 Build host (Windows)               Patch server (Raspberry Pi)
 ────────────────────               ──────────────────────────
 compile.cmd                        server.py  (always running,
   → new Michi-s-adventure.jar        port 5006, systemd)
   → bumps build.properties
                                    build_patch.py
 scp old.jar + new.jar ──────────►    reads old.jar + new.jar
 (via SSH)                            produces patch.zip
                                      signs it with private key
                                      updates manifest.json
                        
                        
 Player machine (Windows)
 ───────────────────────
 java -jar Michi-s-adventure.jar
   UpdateClient.checkAndApply()
     → CHECK 2.0.8  ─────────────►  server.py
     ◄── UPDATE 2.0.9 ...sig ──────
     → FETCH 2.0.8  ─────────────►
     ◄── [raw patch bytes] ────────
     verify SHA-256 + RSA signature
     spawn Updater.java (separate JVM)
     game exits
                                    
 Updater.java (separate JVM)
   waits for game process to exit
   rewrites Michi-s-adventure.jar
   relaunches game
   exits
```

The game **never starts** if an update is confirmed by the server and the
player accepts — the update must be applied first. If the patch server is
unreachable, the game starts normally (offline-friendly).

---

## 2. Server internals

`server.py` is a **plain TCP server** (no HTTP, no framework). It spawns
one thread per connection.

Key pieces:

| Component | What it does |
|---|---|
| `load_manifest()` | Reads `manifest.json` from disk **on every connection** so publishing a new patch just requires re-running `build_patch.py` — no server restart needed. |
| `find_patch()` | Searches the manifest for an entry with `"from": <client_version>` and `"to": <latest_version>`. Direct hops only — one patch per upgrade. |
| `IpRateLimiter` | Token-bucket per IP. Default: 60 requests/minute. |
| `BoundedSemaphore` | Caps concurrent connections. Default: 32. |
| `maybe_sign_unsigned_patches()` | On startup, signs any patches in `manifest.json` that are missing a signature (useful if you add a patch file manually). |

Configuration is in `patch_config.json` (created from `patch_config.example.json`
by `setup.sh`):

```json
{
  "host": "0.0.0.0",
  "port": 5006,
  "private_key_path": "patch_private_key.pem",
  "patches_dir": "patches",
  "manifest_path": "manifest.json",
  "max_concurrent_connections": 32,
  "rate_limit_per_ip_per_minute": 60,
  "max_patch_bytes": 268435456
}
```

---

## 3. Wire protocol

All control messages are **UTF-8 newline-terminated text**. Patch bytes are
sent as **length-prefixed raw binary** (8-byte big-endian `uint64` then the
patch bytes).

### PING

```
Client → Server:  PING\n
Server → Client:  PONG\n
```

Used by monitoring scripts to check if the server is alive.

### CHECK

```
Client → Server:  CHECK <current_version>\n
```

Server looks up `current_version` in `manifest.json`:

- **Up to date:**
  ```
  Server → Client:  UPTODATE\n
  ```

- **Update available:**
  ```
  Server → Client:  UPDATE <to_version> <size_bytes> <sha256_hex> <sig_b64>\n
  ```
  `sig_b64` is the Base64-encoded RSA signature. The client uses this to
  verify the patch *before* applying it.

- **Error** (patch file missing from disk, etc.):
  ```
  Server → Client:  ERROR <message>\n
  ```

### FETCH

```
Client → Server:  FETCH <from_version>\n
Server → Client:  <8 bytes: uint64 size><size bytes: raw patch ZIP>
```

The client opens a **fresh connection** for FETCH. No authentication or
session state is kept between CHECK and FETCH.

---

## 4. How a patch is created

You run `build_patch.py` on the patch server with the **old JAR** and the
**new JAR**:

```bash
python3 build_patch.py  old.jar  new.jar  2.0.8  2.0.9
```

What happens step by step:

### Step 1 — Diff the two JARs

Both JARs are ZIP files. `build_patch.py` opens them with Python's
`zipfile` module and reads every entry:

```
old_entries = {entry_name: bytes, ...}   # all files in old.jar
new_entries = {entry_name: bytes, ...}   # all files in new.jar
```

It then classifies every entry:

| Category | Condition |
|---|---|
| **add** | name exists in `new.jar` but NOT in `old.jar` |
| **replace** | name exists in both, but the bytes are different |
| **delete** | name exists in `old.jar` but NOT in `new.jar` |
| *(unchanged)* | same name, same bytes — skipped entirely |

Only changed/new/deleted files go into the patch. If 95% of the JAR is
unchanged, the patch is tiny.

### Step 2 — Build the patch ZIP

A patch ZIP is assembled in memory with this layout:

```
patch.zip
├── manifest.json          ← describes what changed
├── add/<entry_name>       ← full bytes of each new file
└── replace/<entry_name>   ← full bytes of each changed file
```

Deleted files are listed only in `manifest.json`; no bytes are stored for
them.

### Step 3 — Sign the patch

```python
sha256_of_patch = hashlib.sha256(patch_bytes).digest()

signature_payload = sha256_of_patch
                  + b"|"
                  + from_version.encode()
                  + b"|"
                  + to_version.encode()

signature = private_key.sign(
    signature_payload,
    padding.PKCS1v15(),
    hashes.SHA256(),
)
```

The signature payload binds together:

- The exact bytes of this patch
- The version it starts from
- The version it ends at

A hostile mirror holding an older signed patch **cannot** serve it as a
newer one because the `to_version` in the payload wouldn't match.

### Step 4 — Register in manifest.json

`build_patch.py` writes or updates `manifest.json`:

```json
{
  "latest_version": "2.0.9",
  "patches": [
    {
      "from":           "2.0.8",
      "to":             "2.0.9",
      "file":           "patches/v2.0.8_to_2.0.9.zip",
      "sha256_hex":     "abcd1234...",
      "signature_b64":  "BASE64...",
      "size_bytes":     102400,
      "created_at":     "2026-04-25T16:00:00Z"
    }
  ]
}
```

The server re-reads this file on every incoming connection, so the new
patch is live immediately — **no server restart needed**.

### Step 5 — (Optional) Restart the service

```bash
sudo systemctl restart michi-patch
```

Not required for the patch to be served, but recommended after any
significant manifest change to ensure a clean server state.

---

## 5. Patch file format

```
patch.zip
│
├── manifest.json
│       {
│         "from":    "2.0.8",          ← version this patch is FROM
│         "to":      "2.0.9",          ← version this patch produces
│         "replace": ["a/B.class", …], ← entries overwritten in the JAR
│         "add":     ["a/C.class", …], ← entries added to the JAR
│         "delete":  ["a/Old.class"]   ← entries removed from the JAR
│       }
│
├── replace/
│   └── <path>     ← new bytes for every entry listed in "replace"
│
└── add/
    └── <path>     ← bytes for every entry listed in "add"
```

Deleted entries are named in `manifest.json["delete"]` only. No bytes are
stored for them.

Paths inside `add/` and `replace/` mirror the entry name inside the JAR.
For example, if `main/Main.class` changed, the patch contains
`replace/main/Main.class`.

---

## 6. Signature and integrity

Two independent checks protect every patch download:

| Check | When | What is verified |
|---|---|---|
| **SHA-256** | After download | The downloaded bytes match the hash announced in the `UPDATE` reply. Catches truncation and corruption. |
| **RSA-2048 signature** | After hash check | The private key (kept only on the patch server) signed `SHA-256(patch) \|\| from \|\| to`. Proves the patch is authentic and has not been swapped by a hostile mirror. |

The public key is compiled into `UpdateClient.PATCH_PUBLIC_KEY_B64` (a
Base64 DER X.509 SubjectPublicKeyInfo blob). It cannot be replaced without
recompiling the game.

If either check fails, the client shows an error dialog and exits without
touching the JAR.

---

## 7. How the client uses a patch

All update logic runs in `src/update/UpdateClient.java`, called from the
very first line of `Main.main()` — **before** any window, game thread, or
license load.

### 7.1 Version detection

```java
// UpdateClient.readCurrentVersion()
InputStream is = UpdateClient.class.getResourceAsStream("/res/build.properties");
// reads  version=2.0  build=8  → returns "2.0.8"
```

`build.properties` lives **inside** the JAR and is auto-incremented by
`compile.cmd` on every build. There is no external version file.

### 7.2 Server list

The client reads `update_servers.txt` from the working directory (one
`host:port` per line). If the file is absent it falls back to
`FALLBACK_HOSTS` compiled into the class. `compile.cmd` and the Inno Setup
installer both ship `update_servers.txt` next to the JAR automatically.

### 7.3 CHECK round-trip

```
→  CHECK 2.0.8
←  UPDATE 2.0.9 102400 abcd1234... BASE64SIG
```

If the server says `UPTODATE`, `checkAndApply()` returns `true` immediately
and the game launches normally.

### 7.4 Confirmation dialog

The player sees:

```
A required update is available:

    2.0.8  →  2.0.9
    100 KB

Download and install now?
(The game cannot start until the update is applied.)
```

- **OK** → proceeds to download.
- **Cancel** → game closes (mandatory update policy).

### 7.5 FETCH + verify

A fresh TCP connection is opened:

```
→  FETCH 2.0.8
←  [8-byte size][patch bytes...]
```

After reading all bytes:

1. Recompute `SHA-256(patch_bytes)` — must match `sha256_hex` from the
   `UPDATE` line.
2. Build the signature payload:
   `SHA-256(patch) + "|" + from_version + "|" + to_version`
3. Verify the RSA signature with `PATCH_PUBLIC_KEY_B64`.

If either check fails → error dialog, game exits, JAR is untouched.

### 7.6 Hand-off to Updater

The verified patch ZIP is written to a system temp file. Then:

```java
ProcessBuilder pb = new ProcessBuilder(
    javaExe, "-cp", gameJar.toString(),
    "update.Updater",
    gameJar.toString(),   // which JAR to patch
    patchFile.toString(), // where the patch ZIP is
    toVersion,            // "2.0.9"
    String.valueOf(pid)   // current process PID
);
pb.start();
```

The game then shows a brief "Updater is running" dialog and `return`s from
`main()`. The JVM exits, releasing the JAR file lock.

---

## 8. The Updater process

`src/update/Updater.java` runs in a **separate JVM** so it can replace the
JAR file that the main game had open.

### 8.1 Wait for parent

```java
ProcessHandle.of(parentPid).ifPresent(ph -> ph.onExit().get());
Thread.sleep(750); // give the OS time to release file handles
```

On Windows, a JAR cannot be overwritten while a JVM is using it. The
Updater blocks until the game process is gone.

### 8.2 Apply the patch

```
1. Open old game JAR as a ZipInputStream.
2. Open the patch ZIP with ZipFile.
3. Read manifest.json from the patch → build delete set + overlay map.
4. Stream through every entry in the old JAR:
     - If the entry name is in delete[]   → skip it (omit from output).
     - If the entry name is in overlay    → write the new bytes instead.
     - Otherwise                          → copy bytes unchanged.
5. After the old JAR is exhausted, write any add[] entries from the overlay
   that weren't already written (because they didn't exist in the old JAR).
6. Result is a new complete JAR written to a sibling temp file.
```

### 8.3 Atomic swap

```
game.jar        → game.jar.bak    (rename)
game.jar.tmp    → game.jar        (atomic move)
game.jar.bak    deleted
```

If the atomic move fails, the original is restored from `.bak`. The JAR is
never left in a broken state.

### 8.4 Relaunch

```java
new ProcessBuilder(javaExe, "-jar", gameJar.toString())
    .directory(gameJar.getParent().toFile())
    .inheritIO()
    .start();
```

The game reopens at the new version. The Updater process exits.

---

## 9. Version tracking

The version is **only** stored in `/res/build.properties` inside the JAR:

```properties
version=2.0
build=8
```

`compile.cmd` increments `build` automatically on every build. The patch's
`replace[]` list includes this file, so after the Updater finishes the JAR
reports the new build number. No external file needs to be written.

`UpdateClient.readCurrentVersion()` reads from this resource, returning
`"2.0.8"`. The patch server's `manifest.json` must use the same string in
its `"from"` field.

---

## 10. Security properties

| Threat | Mitigation |
|---|---|
| **Forged patch** | RSA-2048 + SHA-256 over `(hash, from, to)`. Public key is compiled into the binary. |
| **Downgrade attack** | Signature payload includes both `from` and `to`. A mirror cannot serve an older signed patch to a newer client. |
| **Truncation / corruption** | Client recomputes SHA-256 of the full download and compares to the hash announced in `CHECK`. |
| **Partial write (crash mid-update)** | Updater writes to a `.updating` temp file, then does an atomic move. The original is kept as `.bak` and restored on failure. |
| **JAR locked on Windows** | Updater is a separate JVM that waits for the game's PID to exit before touching the file. |
| **MitM patch swap** | Bound signature means any byte-level modification invalidates the RSA check. |
| **Licence / save tampering** | The patch only ever writes to the JAR file. All files outside the JAR are never touched. |

---

## 11. Quick-reference commands

### Run the server (manual, for testing)
```bash
cd SERVERS/patch_server
python3 server.py
```

### Install as a systemd service (Raspberry Pi)
```bash
sudo bash setup.sh
```

### Check service logs
```bash
journalctl -u michi-patch -f
```

### Publish a new patch
```bash
# On the patch server host
cd SERVERS/patch_server
python3 build_patch.py /path/to/old.jar /path/to/new.jar 2.0.8 2.0.9
# manifest.json is updated immediately; no restart needed
```

### Ping the server manually
```bash
echo -e "PING\r" | nc <host> 5006
# → PONG
```

### Check what version the server reports
```bash
echo -e "CHECK 2.0.8\r" | nc <host> 5006
# → UPTODATE
# or
# → UPDATE 2.0.9 <size> <sha256> <sig>
```

### Regenerate the signing keypair
```bash
# Only do this if the private key is lost. All clients must be recompiled
# with the new PATCH_PUBLIC_KEY_B64 constant before the new key is used.
python3 generate_patch_keys.py
# Paste patch_public_key.b64 → UpdateClient.PATCH_PUBLIC_KEY_B64 → recompile
```
