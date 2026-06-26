# StasisBot

A Fabric client mod that turns a spare Minecraft account into an automatic **stasis "home bot" for 2b2t** — no commands. Park it at your base; when a friend types a trigger word in chat, it finds *their* stasis chamber, checks the pearl is loaded, and fires it.

Built to run **24/7 headless in Docker** (or as a normal client mod).

## Features

- **Zero commands** — reacts to chat trigger words (`!home`, …), matched to each player by the name/keyword on their stasis sign.
- **Fires only the loaded chamber** (nearest first), walks to it with Baritone if out of reach, then **returns home** (and after death).
- **Auto-connect & auto-reconnect** to 2b2t — survives queue drops, kicks and restarts.
- **Discord webhook** notifications: home requests, stasis fired/recharged, players entering render, crystals/TNT, bot death, …
- **Hot-reloaded config** — edit the JSON, changes apply in ~1s, no restart.
- **Remote control from in-game** — run a second copy of the mod in *controller mode* to drive the headless bot's settings live from a GUI, over an **end-to-end-encrypted channel that rides on `/msg`** (no open ports).
- Won't re-teleport someone already standing at the base.

## Deploy with Docker (recommended)

Reliable and headless — no GPU or display needed (a virtual display + software rendering are built in).

```bash
git clone https://github.com/NyuDev/StasisBot
cd StasisBot

# First run (once): authenticate the bot's Microsoft account
docker compose run --rm stasisbot
#   → DevAuth prints a sign-in URL/code. Open it, log in with the BOT account.
#     When you see "Setting user: <bot>", press Ctrl+C.

# Then run it detached
docker compose up -d
docker compose logs -f
```

The token is cached in `run/devauth/` and persists. Set the server with `STASIS_SERVER` (default `2b2t.org`) in a `.env` file. To update later: `git pull && docker compose up -d --build`.

> Authenticating on a remote/headless server: the OAuth redirect needs the container's port 3000 — see the FAQ.

## Or download the jar

Grab the jar for your Minecraft version from the [Releases](https://github.com/NyuDev/StasisBot/releases) (named `stasisbot-<version>+mc<mcversion>.jar`). Drop it in your instance's `mods/` next to **Fabric API** (plus Baritone/Meteor for pathfinding). Log in the bot account and park it at your base.

## Configuration

Everything lives in one hand-edited JSON, **hot-reloaded** while the bot runs (save → applied in ~1s):

- Docker: `run/config/stasisbot.json`
- Client: `.minecraft/config/stasisbot.json`

Created on first run with sensible defaults. The main keys:

| Key | Default | What it does |
|---|---|---|
| `triggerWords` | `["!home","pearl","warp"]` | chat words that fire a request (must be the line's first word) |
| `aliases` | `{}` | player → keyword(s) on their sign, e.g. `"alex": ["tower"]` |
| `master` | `""` | your name, for config-by-chat |
| `returnHome` + `returnX/Y/Z` | `true` | walk back to these coords after each job |
| `skipIfPresent` | `true` | don't TP someone already in render distance |
| `discordEnabled` + `discordWebhookUrl` | `false` | Discord notifications |

The file lists every key (~40); just edit and save.

## Remote control (controller mode)

Configure the headless bot live from your own client — no open ports, nothing readable by the server. The **same mod** runs as the bot or as a *controller*; control frames are **AES-256-GCM encrypted with a shared secret** and travel as ordinary whispers between the two accounts, so even the server only ever sees ciphertext (your coordinates never leak).

**On the bot** (`run/config/stasisbot.json`): set a `master` (your in-game name) and a `controlSecret`. Both must be set, or remote control stays off.

```json
"master": "YourName",
"controlSecret": "a-long-random-shared-secret"
```

**On your client:** install the mod, open the panel (default key **H**) → **Controller mode** ON → relaunch. Now H opens the remote panel: enter the **bot's in-game name** and the **same secret**, hit **Save & Connect**. Once it shows `● synced`, every toggle drives the bot live. Both accounts must be online on the server at the same time.

> Security: the secret is the key — it is never transmitted. Frames are authenticated (only a holder of the secret can issue commands) and replay-guarded (timestamp window). On top of that, the bot only accepts control from the configured `master`.

**Choosing the Minecraft version:** the Docker image is pinned to **1.21.11** (the verified version) via `gradle.properties` — that's what the bot runs. For the downloadable jar, pick the one matching your MC version. To build another version yourself: `./gradlew build -Pminecraft_version=1.21.x -Pyarn_mappings=… -Pfabric_version=…`.

## FAQ

**Does it need its own account?** Yes — a second Minecraft account that owns the game. It logs in as the bot and sits at your base.

**How do I switch the bot's account?** A second slot (`alt`) is pre-declared. Authenticate it once, then set `STASIS_ACCOUNT=alt` (or back to `main`) in `.env`. Full steps are in the `docker-compose.yml` header.

**The OAuth page won't load `127.0.0.1:3000` on my server.** DevAuth's redirect server runs inside the container, so your browser can't reach it on a remote box. Run the auth with host networking instead:
```bash
docker run --rm -it --network host \
  -v "$PWD/run:/app/run" -v stasisbot_gradle-cache:/root/.gradle \
  stasisbot-headless:latest
```
…then open the printed URL (use an SSH `-X` browser on the server, or an `ssh -L 3000:127.0.0.1:3000` tunnel from your PC).

**Which MC versions are supported?** Jars are built for 1.21.1–1.21.11, but only **1.21.11** is verified in-game; the rest are best-effort (whichever compile are attached to the release).

**It uses too much CPU / lags.** Software rendering is already capped (`maxFps`, low render distance via `SB_RENDER_DISTANCE`). On 2b2t the effective view distance is server-limited anyway.

**Can I run it without Docker?** Yes — drop the jar into `mods/` on a normal client (needs Fabric API). Docker is just the reliable headless option.
