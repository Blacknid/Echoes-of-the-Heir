# Licensing & multiplayer — how it works, and how to deploy it

## The model in one line

**itch.io is the door, not the landlord.** A player proves they bought the game *once*, at first
launch. From then on the license belongs to **your** server, and itch is never contacted again —
the game keeps working offline, without the itch app, forever.

```
FIRST LAUNCH (once per install)
  game  ──browser OAuth──>  itch.io          player approves, game gets a token
  game  ──ACTIVATE(token)─> save_server      token travels inside the RSA envelope
                            save_server ──>  itch API: "did user N buy game 111?"
                                             (asked with YOUR dev API key)
                            └─ yes ──> issues license_key + activation_id + enc_blob
                            └─ no  ──> ITCH_NOT_OWNED

EVERY LATER LAUNCH                            ...and itch is never involved again.
  game  ──LOGIN(activation_id, enc_blob)──> save_server ──> AUTH_OK

MULTIPLAYER
  game  ──LOGIN(activation_id, enc_blob)──> mp_server
                            mp_server ──VERIFY_ACTIVATION──> save_server (internal port)
                                       └─ resolves the license, or refuses the join
```

The client **never** stores the plaintext license key — only `activation_id` + an encrypted blob
that only the save server can decrypt (it alone holds `enc_key`). That's what `activation.dat` is.

---

## What was broken (and is now fixed)

1. **Multiplayer could never work.** `mp_config.json` had `save_server_host: "127.0.0.1"`. Inside
   the `michi-mp` container that means *that container*, not the save server — so every license
   verification got connection-refused and every join was rejected with
   `LICENSE_SERVER_UNAVAILABLE`. It is now `save` (the compose service name), on the shared
   `michi` network.

2. **…and the save server would have refused it anyway.** Its `INTERNAL` command was gated on the
   caller being `127.0.0.1`, but cross-container traffic arrives from a Docker bridge address. The
   internal API now lives on **its own port (5105)** that compose deliberately does **not**
   publish — so it is reachable from sibling containers and from nowhere else. The public port
   (5005) refuses `INTERNAL` outright.

3. **Anyone could get a free license.** ACTIVATE handed one to whoever asked. It now requires
   itch.io proof of purchase.

4. **Placeholder secrets were live in production** (`CHANGE_THIS_TO_A_RANDOM_SECRET`,
   `CHANGE_ME_BEFORE_DEPLOYING`), and the admin dashboard was open to the whole internet. Secrets
   now come from `SERVERS/.env` (gitignored), and the dashboard is bound to the VPS loopback.

5. **The client gave up on its license.** If activation hadn't finished (it runs in the background
   at boot) or had failed once, Multiplayer said "No valid license" forever with no way to retry.
   It now retries activation on demand and reports *why* it failed.

---

## Deploying

### 1. Create the itch.io OAuth application

itch.io → **Settings → OAuth applications → Create new**

- **Redirect URI:** `http://127.0.0.1:34567/`
  (itch validates redirect_uri with an *exact* string match, port included — it does NOT
  accept an arbitrary port when only `http://127.0.0.1/` is registered. The game's local
  OAuth listener binds this same fixed port; see `REDIRECT_PORT` in
  [`DesktopItchAuth.java`](../desktop/src/main/java/desktop/itch/DesktopItchAuth.java))
- Copy the **client id**.

Put that client id in `ITCH_CLIENT_ID_BAKED` in
[`desktop/src/main/java/desktop/itch/DesktopItchAuth.java`](../desktop/src/main/java/desktop/itch/DesktopItchAuth.java).
A client id is **not** a secret — it is meant to ship inside the game.

### 2. Get your itch API key and game id

- **API key:** itch.io → Settings → **API keys** → *Generate new key*.
  This one **is** a secret. It is what proves ownership on the server. It never ships to clients.
- **Game id:** the number in `https://itch.io/game/edit/<NUMBER>`.
- **Your own user id (optional but recommended):** itch's purchase check
  (`GET /api/1/<key>/game/<id>/download_keys`) only returns a result for a **claimed
  purchase key** — the game's developer/admin never holds one for their own game, so
  without this you'd be locked out of your own gated build. Grab your numeric id from
  `https://itch.io/api/1/key/me` (or the OAuth `/me` response) and put it in
  `MICHI_ITCH_OWNER_USER_IDS` (comma-separated if more than one admin/tester needs it).

  **This alone is not enough for the owner to actually activate**, though: itch's OAuth
  implicit-grant flow only issues an `access_token` to accounts that bought the game
  through the storefront. The developer's own account never gets one, so the browser
  flow in `DesktopItchAuth` has nothing to send — `ITCH_OWNER_USER_IDS` is checked only
  *after* a token identifies a `user_id`, so it's unreachable for the owner on its own.

  Instead, generate your own secret and set `MICHI_ITCH_OWNER_SECRET`:
  ```bash
  python -c "import secrets; print(secrets.token_urlsafe(24))"
  ```
  Then launch the game with `-Dmichi.itch.ownerSecret=<the same value>` — this activates
  without ever going through itch OAuth. Keep this secret private; anyone who has it can
  activate as the owner.

### 3. Fill in the server secrets

On the VPS, in `SERVERS/`:

```bash
cp .env.example .env
nano .env
```

```ini
# must be identical for both servers; generate with:
#   python3 -c "import secrets; print(secrets.token_urlsafe(32))"
MICHI_INTERNAL_API_KEY=<paste the generated value>

MICHI_ITCH_API_KEY=<your itch.io API key>
MICHI_ITCH_GAME_ID=<your numeric game id>
MICHI_ITCH_OWNER_USER_IDS=<your numeric itch user id, comma-separated if more than one>
MICHI_ITCH_OWNER_SECRET=<a secret you generate — see above; lets you activate without itch OAuth>

MICHI_ADMIN_PASSWORD=<a strong password, or leave empty to disable the dashboard>
```

### 4. Bring it up

```bash
cd SERVERS
docker compose up -d --build
docker compose logs -f
```

Look for these lines. They are the deployment's self-check:

```
save  | Internal license API listening on 0.0.0.0:5105 (not internet-exposed)
save  | itch.io purchase gate ACTIVE (game_id=...) — ACTIVATE requires proof of purchase.
mp    | save_server license link OK (save:5105).
```

If you instead see `save_server license link is DOWN`, multiplayer logins **will** fail — the MP
server now tells you at boot rather than letting players discover it.

### 5. Firewall

Only these should be reachable from the internet:

| Port | Service | Public? |
|------|---------|---------|
| 5005 | save server (activation, cloud saves) | **yes** |
| 5006 | patch server | **yes** |
| 7777 | multiplayer | **yes** |
| 5105 | internal license API | **no** — compose does not publish it |
| 8888 | admin dashboard | **no** — bound to VPS loopback |

```bash
ufw allow 5005/tcp && ufw allow 5006/tcp && ufw allow 7777/tcp
ufw deny 5105/tcp  && ufw deny 8888/tcp
ufw enable
```

Reach the dashboard over an SSH tunnel instead of exposing it:

```bash
ssh -L 8888:127.0.0.1:8888 root@142.93.103.51
# then open http://127.0.0.1:8888/
```

---

## Notes and edge cases

- **Existing players are not locked out.** Licenses issued before the gate existed have
  `itch_user_id = NULL` and keep working. Only *new* activations need proof of purchase.

- **Reinstalling works.** Activating again as the same itch account re-issues the *same*
  `license_key` with fresh credentials, so saves, friends, and username survive. A partial unique
  index on `itch_user_id` enforces one license per purchase even against concurrent activations.

- **If itch.io is down**, activation is refused with `ITCH_UNAVAILABLE` rather than granting a
  license on an inconclusive answer — but the player is told it's *our* problem, not an accusation
  that they pirated it. Existing players are unaffected: they use LOGIN, which never calls itch.

- **The gate is off if unconfigured.** With `MICHI_ITCH_API_KEY` / `MICHI_ITCH_GAME_ID` unset the
  server issues free licenses to anyone and logs a loud warning at boot every time. Don't ship
  like that.

- **The RSA private keys are committed to this repo** (`SERVERS/*/server_private_key.pem`). They
  are the transport keys for the handshake, and their public halves are baked into shipped
  clients, so rotating them is a breaking change for existing installs — but anyone with the repo
  can decrypt handshake envelopes. Worth rotating before a public launch, together with a client
  update.
