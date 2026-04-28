# Deployment Guide — Michi's Adventure

End-to-end instructions to take this repo from clean checkout to a fully
running production stack: **cloud save server**, **multiplayer server**,
**patch server**, and **player installer**.

If you only want a deeper architectural / threat-model write-up, see
[`SERVERS.md`](./SERVERS.md). This file is the practical "do this in this
order" checklist.

---

## 0. Prerequisites

### On the **server host** (Raspberry Pi 5, Raspberry Pi OS Bookworm)

- Fresh apt: `sudo apt update && sudo apt full-upgrade -y`
- Python 3.11+ (default on Bookworm)
- `python3-cryptography`, `sqlite3`, `ufw` (each `setup.sh` installs these
  itself; you don't need to do it manually)

### On the **build host** (Windows, your dev machine)

- JDK 21+ with `javac`, `jar`, `jpackage`, `keytool`
- Inno Setup 6 (for the `.exe` installer) — optional but recommended
- WiX Toolset (only if you want jpackage to produce an `.msi`)
- Python 3.10+ (only if you want to run `build_patch.py` on the build
  host instead of the patch server itself)

---

## 1. Provision the three servers on the Pi

> Run all commands as a normal user; each script will `sudo` what it needs.

### 1a. Cloud save server

```bash
cd ~/michi/ceva/server
sudo bash setup.sh
```

After it finishes:

```bash
nano server_config.json
# Set a strong random license_salt (a 24-byte hex string is fine):
#   python3 -c "import secrets; print(secrets.token_hex(24))"
# Optionally tweak port, rate_limit_per_ip_per_minute.
sudo systemctl restart michi-save
sudo systemctl status michi-save
```

Copy `server_public_key.b64` somewhere safe — you'll paste it into the Java
client in §3.

### 1b. Multiplayer server

```bash
cd ~/michi/ceva/multiplayer_server
sudo bash setup.sh
```

The script asks for the bind IP and port, then writes them into
`mp_config.json`. Then:

```bash
nano mp_config.json
# IMPORTANT: license_salt MUST exactly match the save server's value.
sudo systemctl restart michi-multiplayer
sudo systemctl status michi-multiplayer
```

If the script generated a *separate* MP keypair (because the save server
key wasn't on this host), copy `server_public_key.b64` from this directory
too — you'll need both in §3.

### 1c. Patch server

```bash
cd ~/michi/ceva/patch_server
sudo bash setup.sh
```

Copy `patch_public_key.b64` — you'll paste it into the Java client in §3.

```bash
sudo systemctl status michi-patch
```

You should now have three active services: `michi-save`, `michi-multiplayer`,
`michi-patch`.

---

## 2. Open ports on your router / firewall

The setup scripts already opened ports in `ufw`. If the Pi is behind NAT,
forward these TCP ports from your router to the Pi:

| Service | Default port |
|---|---|
| Save server | 5005 |
| Multiplayer | 7777 |
| Patch server | 5006 |

(Whatever you actually wrote into the JSON config files.)

---

## 3. Wire public keys into the Java source

Edit these constants in the source tree on your **build host** before
compiling:

| Constant | Source file | Paste the contents of |
|---|---|---|
| `RSA_PUBLIC_KEY_B64` | `src/data/CloudSaveService.java` | `ceva/server/server_public_key.b64` |
| `RSA_PUBLIC_KEY_B64` | `src/main/MultiplayerClient.java` | Same key (or MP-specific key if you split them) |
| `PATCH_PUBLIC_KEY_B64` | `src/update/UpdateClient.java` | `ceva/patch_server/patch_public_key.b64` |

> If the same RSA keypair is used for both save and MP servers (the default
> when you ran both `setup.sh` on the same Pi), you can leave both
> `RSA_PUBLIC_KEY_B64` constants pointing at the same value. The patch
> server **always** uses its own keypair.

Edit the **endpoint lists** in the repo root so players hit your real
servers:

- `ceva/save_servers.example.txt` → list your cloud-save servers
- `ceva/update_servers.example.txt` → list your patch servers

`compile.cmd` will copy these into `deploy/` next to the JAR with their
non-`.example` names. Existing customized files in `deploy/` are preserved.

---

## 4. Build the game JAR + EXE

```cmd
cd ceva\build_tools
compile.cmd
```

What this does:

1. Auto-increments `build` in `src/res/build.properties` (the **single
   source of truth** for the game version, read by both `Config` and
   `UpdateClient`).
2. Compiles every `.java` under `src/`, including `update/UpdateClient.java`
   and `update/Updater.java`.
3. Packs the JAR into `deploy/Michi-s-adventure.jar`.
4. Copies `update_servers.txt` and `save_servers.txt` next to the JAR.
5. Runs `jpackage` to produce a Windows EXE/MSI (or app-image fallback).

Verify:

```cmd
java -jar deploy\Michi-s-adventure.jar
```

The game should start. On startup you'll see:

```
[Update] Up to date (v2.0.<n>).
```

…if your patch server is reachable and the manifest reports the same
version. Otherwise it will say "No patch server reachable" and continue.

---

## 5. Build the player installer

Open `ceva/build_tools/setup_init.iss` in Inno Setup, fix the hard-coded
paths at the top to point at your local copies, and click **Compile**.
Inno Setup will produce `MichiGame_Setup.exe` containing:

- The launcher EXE
- The game JAR
- A bundled JRE
- `update_servers.txt` and `save_servers.txt` (one-time copy; player edits
  preserved on re-install)

The installer's `[Code]` section generates a unique `license.properties`
post-install via PowerShell + the structural `LICENSE_SALT` constant
(`MichiCloudSalt2026`). **This must match `license_salt` in both
`server_config.json` and `mp_config.json` on the Pi.** If you changed the
salt server-side, update the Pascal string in `setup_init.iss` line 66
to match, or licenses will fail with `AUTH_FAIL`.

---

## 6. Publishing a new game version

When you have a new build that fixes/adds something:

1. **On the build host**, keep a copy of the *previous* `Michi-s-adventure.jar`.
   (After running `compile.cmd` it lives in `deploy/`.)
2. Run `compile.cmd` again to produce the new JAR (and bump the build number).
3. Copy both JARs to the patch server:

   ```bash
   scp deploy_old/Michi-s-adventure.jar pi@patch-host:/tmp/old.jar
   scp deploy/Michi-s-adventure.jar     pi@patch-host:/tmp/new.jar
   ```

4. SSH into the Pi:

   ```bash
   cd ~/michi/ceva/patch_server
   python3 build_patch.py /tmp/old.jar /tmp/new.jar 2.0.7 2.0.8
   sudo systemctl restart michi-patch
   ```

5. Done. Every running client will detect the update on next start, prompt
   the player, download + verify the patch, and self-replace the JAR.

> **The game refuses to start until the update is installed.** If the
> player declines the dialog, the game exits. If the patch server is
> unreachable, the game starts normally (offline-friendly).

---

## 7. Day-to-day operations

```bash
# tail logs
journalctl -u michi-save        -f
journalctl -u michi-multiplayer -f
journalctl -u michi-patch       -f

# inspect the save / license database
sqlite3 ~/michi/ceva/server/saves.db
sqlite> SELECT license_key, login_count, rotated_at FROM licenses;

# back up what cannot be regenerated
tar czf michi-backup-$(date +%F).tgz \
    ~/michi/ceva/server/saves.db \
    ~/michi/ceva/multiplayer_server/mp_licenses.db \
    ~/michi/ceva/server/server_private_key.pem \
    ~/michi/ceva/multiplayer_server/server_private_key.pem \
    ~/michi/ceva/patch_server/patch_private_key.pem \
    ~/michi/ceva/patch_server/manifest.json
```

**What you must back up regularly:**

- `saves.db` — player saves + per-license salts/peppers
- `mp_licenses.db` — per-license salts/peppers for MP
- The three `*_private_key.pem` files — losing them invalidates every
  installed client

**What you must never commit to git:**

- Any `*_private_key.pem`
- `server_config.json`, `mp_config.json`, `patch_config.json` (they contain
  `license_salt` and the like)
- `saves.db`, `mp_licenses.db`
- `patch_server/patches/` — these are signed with your key
- The legacy GitHub token in `build_tools/patcher.bat` line 19. Rotate it
  and remove it from history if anyone outside your team has clone access.

---

## 8. Troubleshooting

| Symptom | Likely cause |
|---|---|
| `AUTH_FAIL` on every connection | `license_salt` mismatch between `server_config.json`, `mp_config.json`, and the salt baked into the installer / `LicenseGenerator.java`. |
| Game gets stuck on "Update required" dialog | Patch server ran out of disk; `manifest.json` lists a `file:` that no longer exists in `patches/`. |
| `[Update] verifySignature error` in the client log | The `PATCH_PUBLIC_KEY_B64` constant in `UpdateClient.java` doesn't match the key the server is signing with. Re-paste from `patch_public_key.b64` and rebuild. |
| Updater spawns but JAR isn't replaced | On Windows, antivirus may quarantine `.updating` temp files. Whitelist the install dir, or temporarily disable real-time scanning during the update. |
| `michi-patch` keeps restarting | Check `journalctl -u michi-patch -n 100` — usually a malformed `manifest.json` or missing `patch_private_key.pem`. |
