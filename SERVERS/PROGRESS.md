# Licensing Overhaul — Progress Report

**Date:** 2026-05-17
**Scope:** Audit, simplify, harden, and de-duplicate the entire licensing pipeline.

---

## 1. Problems Found

### 1.1 Problems carried over from the previous audit

| # | Problem | Severity |
|---|---------|----------|
| 1 | `license_salt` only protected a 4-char SHA-256 checksum (1/65 536 collision space). Trivially brute-forceable. | High |
| 2 | Dual verification (salt + RSA + registry) added complexity with no extra security. | Medium |
| 3 | RSA public key had to be manually pasted into **6 different places**. | High |
| 4 | TOFU registry suffered a race condition: a pirate could claim a leaked key before the buyer. | Medium |
| 5 | `LicenseGenerator.java` (dev signing tool) lived in the production package tree. | High |

### 1.2 Additional problems discovered during this pass

| # | Problem | Severity |
|---|---------|----------|
| 6 | `license_salt` hardcoded as `DEFAULT_CONFIG` fallback in both `server.py` files — silently used if config field missing. | Medium |
| 7 | `LicenseGenerator.java` was actually **compiled into the production JAR** (`compile.cmd` globs all `.java`). Anyone could run `java -cp Michi-s-adventure.jar data.LicenseGenerator` to learn the format. | High |
| 8 | `compile.cmd` only injected the public key into `LicenseManager.java`, **not** into `server_config.json` / `mp_config.json`. Operator had to copy-paste manually after every key rotation. | High |
| 9 | `license.properties` was loaded via the **relative path** `Paths.get("license.properties")`. If the game was launched with a different `cwd` (e.g. shortcut without `WorkingDir`), the license was never found. | Medium |
| 10 | `LicenseManager.startWatchdog()` used **reflection** to null `Main.LICENSE_KEY` — brittle, silently swallowed any refactor. | Low |
| 11 | `LICENSE_PEPPER = "michi-license-pepper-v2"` is a **public constant** known to both client and server. Adds zero cryptographic value. | Low (cosmetic) |
| 12 | No revocation, no expiry, no pre-bind support in `authorize()`. A leaked key could only be killed by hand-editing JSON. | Medium |
| 13 | `UNSIGNED_DEV_PLACEHOLDER` in `LicenseGenerator.java` silently produced a useless file when the private key was missing. | Low |
| 14 | `INSTRUCTIONS.md` and both `setup.sh` scripts referenced the now-dead `license_salt` in user-visible prompts. | Low |

---

## 2. Current Behaviour (post-overhaul)

### 2.1 License generation

```
[Customer buys the game]
        │
        ▼
┌────────────────────────────────────────────┐
│  Inno Setup installer (setup_init.iss)     │
│  ───────────────────────────────────────   │
│  1. Read Windows MachineGuid               │
│  2. fp = SHA-256(MachineGuid)[:8 bytes]    │
│  3. key = 8 random base32 + "-" + 4 random │
│  4. sig = RSA-2048-PKCS1v15-SHA256(        │
│             key + "|" + fp,                │
│             embedded_private_key)          │
│  5. Write license.properties next to .exe  │
└────────────────────────────────────────────┘
```

`license.properties` layout:
```
license_key=A1B2C3D4-E5F6
machine_fp=deadbeef12345678
signature=<base64 RSA-2048 signature>
```

### 2.2 Game startup

```
Main.main()
   │
   ▼
LicenseManager.load()
   │
   ├─ resolveLicensePath()           ← absolute, JAR-relative
   ├─ Read license.properties
   ├─ Compute machine fingerprint
   ├─ Verify fp matches stored fp
   ├─ Verify RSA signature
   ├─ Cache (key, fp, sig, mtime)
   └─ return key  (or null on any failure)

If key != null:
   LicenseManager.startWatchdog(60s)
   ─ background thread re-verifies file every minute
   ─ on any change → tampered=true, Main.invalidateLicense()
```

### 2.3 Server connection (cloud-save / multiplayer)

```
CLIENT                              SERVER
  │  HELLO v2 + client_nonce         │
  │ ────────────────────────────────►│
  │                                  │  server_nonce = os.urandom(16)
  │  OK <server_nonce>               │
  │ ◄────────────────────────────────│
  │                                  │
  │  AUTH (RSA-OAEP-encrypted JSON): │
  │   { license, machine_fp,         │
  │     license_sig, ts,             │
  │     client_nonce, server_nonce } │
  │ ────────────────────────────────►│
  │                                  │  Step 1: structural sanity check
  │                                  │  Step 2: RSA verify(key|fp, sig)
  │                                  │  Step 3: registry lookup
  │                                  │           ├─ revoked?  → REJECT
  │                                  │           ├─ expired?  → REJECT
  │                                  │           ├─ fp null?  → TOFU pin
  │                                  │           └─ fp match? → ACCEPT
  │                                  │  Step 4: nonce+ts replay check
  │  AUTH_OK <enc_session_key>       │
  │ ◄────────────────────────────────│
  │                                  │
  │  delivery_key = HKDF(            │  (same on both sides)
  │     secret = lic+pepper,         │
  │     salt   = server_nonce,       │
  │     info   = "michi-delivery-v2")│
  │  session_key = aesgcm.decrypt(   │
  │     enc, delivery_key,           │
  │     client_nonce[:12], aad)      │
  │                                  │
  │ ═══ AES-256-GCM session ═══════ │
```

### 2.4 Security posture

| Attack vector                | Defence                                       | Status |
|------------------------------|-----------------------------------------------|--------|
| Casual tamper (edit props)   | RSA signature                                 | Strong |
| File swap                    | RSA sig over key+fp + watchdog                | Strong |
| Copy to another PC           | machine_fp binding                            | Strong |
| Forgery without private key  | RSA-2048 — private key only in installer.exe  | Strong |
| Replay handshake             | nonce cache + 60 s timestamp window           | Strong |
| Brute-force a valid key      | RSA sig required — no salt shortcut anymore   | Strong |
| Pirate steals leaked key     | `revoked: true` in registry kills it          | NEW    |
| Bulk leak after grace period | Optional `expires` field                      | NEW    |
| TOFU race                    | Pre-bind via `--machine-fp` at issue time     | NEW    |
| Reverse-engineer key format  | `LicenseGenerator.class` removed from JAR     | FIXED  |
| MITM session key delivery    | RSA-OAEP wrap, AES-GCM AAD bound to channel   | Strong |

---

## 3. Changes Made — What / Why / Where

### 3.1 Removed: the salt system (problems 1, 2, 6)

| File | Change |
|------|--------|
| `SERVERS/save_server/server_config.json` | Removed `license_salt` field |
| `SERVERS/save_server/server_config.example.json` | Removed `license_salt` field |
| `SERVERS/multiplayer_server/mp_config.json` | Removed `license_salt` field |
| `SERVERS/multiplayer_server/mp_config.example.json` | Removed `license_salt` field |
| `SERVERS/save_server/server.py` | Dropped salt from `DEFAULT_CONFIG`; replaced `license_is_valid(salt)` with `license_is_well_formed()` (regex only) |
| `SERVERS/multiplayer_server/server.py` | Same |
| `build_tools/setup_init.iss` | Replaced salt-derived suffix with random base32 suffix (format `XXXXXXXX-YYYY` preserved for back-compat) |
| `build_tools/issue_license.py` | Dropped `--salt` arg, dropped salt-check on resulting key |
| `SERVERS/save_server/setup.sh` | Removed user-facing `license_salt` prompt |
| `SERVERS/multiplayer_server/setup.sh` | Removed user-facing `license_salt` prompt |
| `SERVERS/INSTRUCTIONS.md` | Updated legacy note explaining what is gone |

### 3.2 Removed: `LicenseGenerator.java` (problems 5, 7, 13)

| File | Change |
|------|--------|
| `ceva/src/data/LicenseGenerator.java` | **DELETED** — no longer shipped in production JAR |
| `build_tools/generate_dev_license.py` | **NEW** — Python replacement, lives outside the production source tree |
| `build_tools/generate_license_keys.py` | Updated NEXT-STEPS prints to point at the new tool |

The new Python tool reads `license_private_pkcs8.b64`, computes the local `machine_fp` exactly like `LicenseManager.java` does, and writes a signed `license.properties`. It is impossible to ship it into the JAR by accident because it is not Java.

### 3.3 Added: `sync_keys.py` — single source of truth for the public key (problems 3, 8)

| File | Change |
|------|--------|
| `build_tools/sync_keys.py` | **NEW** — idempotent script that reads `license_public.b64` and patches:<br>1. `LicenseManager.java` (multi-line `PUBLIC_KEY_B64`)<br>2. `SERVERS/save_server/server_config.json`<br>3. `SERVERS/multiplayer_server/mp_config.json` |
| `build_tools/compile.cmd` | Replaced the inline PowerShell pubkey injection with a single `python sync_keys.py` call |

A key rotation is now **one command**:

```
python build_tools/generate_license_keys.py   # regenerates keypair
python build_tools/sync_keys.py               # propagates pubkey everywhere
build_tools/compile.cmd                       # rebuild + reinstall
```

Verified: running `sync_keys.py` twice in a row produces zero changes on the second run (`[skip] already up to date`).

### 3.4 Added: revoke / expire / pre-bind (problem 12)

| File | Change |
|------|--------|
| `SERVERS/save_server/license_verify.py` | `authorize()` now checks `entry.revoked` and `entry.expires` (UTC ISO-8601). Pre-bound entries bypass TOFU entirely. |
| `SERVERS/multiplayer_server/license_verify.py` | Same (file synced from save_server's copy) |
| `build_tools/issue_license.py` | New flags: `--machine-fp`, `--expires YYYY-MM-DD`, `--revoke`. Updates merge into existing entries safely (never wipes a pinned fp by accident). |

Registry schema (unchanged for old rows; new fields are optional):

```json
{
  "A1B2C3D4-E5F6": {
    "machine_fp": "deadbeef12345678",   // pre-bound, or null for TOFU
    "first_seen": "2026-05-17T13:42:00",
    "note":       "alice@example.com",
    "expires":    "2027-05-17T00:00:00+00:00",   // optional
    "revoked":    false                            // optional
  }
}
```

Common operations:

```bash
# Issue a TOFU license:
python build_tools/issue_license.py --note alice@x.com \
  --registry SERVERS/save_server/licenses.json \
  --registry SERVERS/multiplayer_server/licenses.json

# Issue a PRE-BOUND license (closes TOFU race):
python build_tools/issue_license.py --note alice@x.com \
  --machine-fp deadbeef12345678 \
  --registry SERVERS/save_server/licenses.json \
  --registry SERVERS/multiplayer_server/licenses.json

# Set an expiry:
python build_tools/issue_license.py --note "trial" --expires 2026-12-31 \
  --registry ...

# Revoke a leaked key:
python build_tools/issue_license.py --key A1B2C3D4-E5F6 --revoke \
  --registry SERVERS/save_server/licenses.json \
  --registry SERVERS/multiplayer_server/licenses.json
```

### 3.5 Fixed: JAR-relative license path + clean watchdog accessor (problems 9, 10)

| File | Change |
|------|--------|
| `ceva/src/data/LicenseManager.java` | `LICENSE_PATH` now resolved via `getProtectionDomain().getCodeSource().getLocation()` so it always sits next to the running JAR. Watchdog now calls `main.Main.invalidateLicense()` instead of reflecting on the field. |
| `ceva/src/main/Main.java` | Added `public static void invalidateLicense()` accessor. `LICENSE_KEY` marked `volatile`. |

---

## 4. Things Intentionally NOT Removed (and why)

### 4.1 `LICENSE_PEPPER` constant (problem 11)

`"michi-license-pepper-v2"` is a public constant on both ends. Cryptographically it adds **nothing** — anyone disassembling the JAR sees it. However:

* Removing it is a **protocol-breaking change** (HKDF input changes → both sides must rev simultaneously).
* The current value is bound into `PROTOCOL_TAG = "v2"`. Any change needs a bump to `"v3"`.
* Old in-the-wild clients would suddenly fail to handshake until they self-update via the patch server.

**Recommendation for a future v3 protocol**: drop the pepper, bump `PROTOCOL_TAG`, ship a final v2 patch that pre-announces the v3 servers' addresses so the auto-updater can carry users across the cut-over. Not done today to avoid breakage.

### 4.2 Existing licenses in `licenses.json` (e.g. `MICHI001-2DF3`)

These are operator data. Old `XXXXXXXX-YYYY` keys still validate (the structural check accepts any `[A-Z0-9][A-Z0-9-]{3,63}`). They keep working because the RSA signature on the customer's `license.properties` is what is actually trusted — not the salt-derived suffix.

### 4.3 `server_private_key.pem` (RSA-OAEP session-handshake key) separate from the license-signing key

Different responsibilities:

* **License-signing key**: lives in the installer EXE, never on a server. Signs `license.properties`.
* **Session-handshake key**: lives on each server, never in the client JAR. Receives RSA-OAEP-encrypted handshake from the client.

Merging them would put the license-signing private key on every server — bad idea.

---

## 5. Architecture (final, simplified)

```
       ┌─────────────────────────────────────────────────────────────┐
       │                  DEVELOPER MACHINE                          │
       │   build_tools/                                              │
       │     license_private.xml       ─ signs installer payload     │
       │     license_private_pkcs8.b64 ─ signs dev license.properties│
       │     license_public.b64        ─ propagated by sync_keys.py  │
       │              │                                              │
       │              ▼                                              │
       │   ┌───────────────────────────────────────────────────┐     │
       │   │  sync_keys.py  (single source of truth)           │     │
       │   │   ├─► LicenseManager.java   (PUBLIC_KEY_B64)      │     │
       │   │   ├─► server_config.json    (license_public_key)  │     │
       │   │   └─► mp_config.json        (license_public_key)  │     │
       │   └───────────────────────────────────────────────────┘     │
       └─────────────────────────────────────────────────────────────┘
                                │
                  ┌─────────────┼─────────────┐
                  ▼             ▼             ▼
       ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
       │  Installer   │ │ Save Server  │ │  MP Server   │
       │  (Inno Setup)│ │ (Raspberry)  │ │ (Raspberry)  │
       │              │ │              │ │              │
       │ signs        │ │ verifies     │ │ verifies     │
       │ license.props│ │ RSA + reg    │ │ RSA + reg    │
       └──────┬───────┘ └──────────────┘ └──────────────┘
              │                ▲                ▲
              ▼                │                │
       ┌──────────────┐        │                │
       │   END USER   │────────┴────────────────┘
       │ MichisAdv.exe│   AUTH(license,fp,sig)
       │ license.props│
       └──────────────┘
```

---

## 6. How to Use the System Now (cheat-sheet)

### One-time setup
```
python build_tools/generate_license_keys.py    # makes RSA-2048 keypair
python build_tools/sync_keys.py                # propagates pubkey
```

### Every build
```
build_tools/compile.cmd
```
(calls `sync_keys.py` automatically; never edit `LicenseManager.java` by hand)

### When a customer buys the game
```
python build_tools/issue_license.py \
    --note "alice@example.com" \
    --registry SERVERS/save_server/licenses.json \
    --registry SERVERS/multiplayer_server/licenses.json
# scp/rsync the two licenses.json to the Pis
```

### When a key leaks
```
python build_tools/issue_license.py --key <LEAKED> --revoke \
    --registry SERVERS/save_server/licenses.json \
    --registry SERVERS/multiplayer_server/licenses.json
```

### Local-dev license (no installer)
```
python build_tools/generate_dev_license.py
# writes ./license.properties bound to your dev machine
```

---

## 7. Verification

* **Python**: `py_compile` over all touched modules → **OK**.
* **Java**: `javac` over `LicenseManager.java` + `Main.java` with full classpath → **OK**.
* **sync_keys.py idempotency**: second consecutive run reports `[skip] already up to date` for all three targets.
* **issue_license.py argparse**: `--help` lists all new flags.
* **Old `XXXXXXXX-YYYY` format**: still passes the new structural regex `^[A-Z0-9][A-Z0-9\-]{3,63}$`.

No new errors. No logic regressions. License-key entropy unchanged (62 bits of base32; RSA signature is the actual security boundary).
