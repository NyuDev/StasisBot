# StasisBot

A **Fabric client mod for Minecraft 1.21.11** that turns a second account into a
fully automatic "home bot" for 2b2t. Park the bot at your base; when a friend
types the trigger word in chat, the bot finds *their* stasis chamber (by the name
or keyword on its sign), checks that the pearl is actually loaded, and fires that
one — no commands, ever.

## How it works

A stasis chamber on 2b2t = a suspended ender pearl + a trigger (lever/button)
that releases it, teleporting whoever threw the pearl. StasisBot reads the world
around the bot and models each chamber as:

- a **sign** that labels it (its text identifies whose chamber it is),
- the nearest **lever/button** to that sign (the trigger the bot clicks),
- and whether an **ender pearl** is currently hovering nearby (= loaded).

**The flow, end to end (`fr.nyuway.stasisbot.service.HomeService`):**

```
someone types "!home" in chat
   → resolve their identity: their name + any aliases you mapped
   → find chambers near the bot whose sign contains one of those words   (throttled scan)
   → keep only the chamber that actually has a pearl, nearest first
   → fire exactly that one
```

### Matching by name *or* keyword

The bot matches a chamber to a player by comparing **whole words** on the sign to
the player's identity words:

- **Steve** types `!home` → fires the chamber whose sign says `Steve`.
- **Alex** writes `TOWER` on her sign (not her name) → add the mapping below and
  `!home` from Alex fires the `TOWER` chamber.

Map keywords to players in `config/stasisbot.json` (created on first run, with an
example already filled in):

```json
{
  "triggerWords": ["!home", "!tp", "home pls"],
  "aliases": {
    "alex": ["tower"],
    "steve": ["spawnbase", "north"]
  }
}
```

A player always matches their own username, so you only need aliases for the
"keyword instead of name" case. Matching is word-level and case-insensitive, so
`tower` matches a sign reading `TOWER` but not `flowertower`.

### Only one fires

If several chambers match a player, the bot fires **only the one that currently
holds a pearl** (nearest first). If a match exists but none have a pearl, it does
nothing and tells you in chat — firing an empty stasis would teleport no one.

### No scan spam

Scanning is throttled by `ChamberIndex`: the world is re-read at most once every
`indexTtlMillis` (default 3s) and only when a request actually comes in (or you
open the monitor). It walks the loaded chunks' block entities, never a
brute-force block sweep, so it stays cheap even with a large search radius.

## Configuration (`config/stasisbot.json`)

| Key | Default | Meaning |
|---|---|---|
| `triggerWords` | `["!home", "pearl", "warp"]` | Keywords that fire a home request. The keyword must be the **first word** of the chat line; extra gibberish after a space is allowed (handy to vary wording around server anti-spam, e.g. `!home xqz`) |
| `aliases` | `{ }` | player → keywords that identify their sign |
| `scanChunkRadius` | `2` | chunks around the bot to scan |
| `maxChamberDistance` | `24` | ignore signs farther than this (blocks) |
| `triggerSearchRadius` | `3` | how far from a sign to find its lever/button |
| `pearlSearchRadius` | `4.0` | how close a pearl must be to count as loaded |
| `indexTtlMillis` | `3000` | min time between world re-scans |
| `reach` | `5.0` | bot must be parked within this of the trigger |
| `autoLook` | `true` | rotate toward the trigger before clicking |

## Toolchain (resolved & build-verified for your instance)

| | |
|---|---|
| Minecraft | 1.21.11 |
| Fabric Loader | 0.19.3 |
| Yarn | 1.21.11+build.6 |
| Fabric API | 0.141.4+1.21.11 |
| Loom / Gradle | 1.16-SNAPSHOT / 9.4.1 |
| Build JDK | 21 (PrismLauncher's `java-runtime-delta`) |

## Open it in VS Code

```powershell
cd $env:USERPROFILE\Desktop\StasisBot
code .
```

Install the recommended extensions when prompted. The first Gradle import
downloads Minecraft + mappings and can take a few minutes.

## Build & run

Two quirks of this machine are handled by the included **`build.cmd`**: the build
must run on **JDK 21** (system JDK is 26), and this JDK needs an AF_UNIX selector
fix (`-Djdk.net.unixdomain.tmpdir`) or Gradle's daemon dies with
`SocketException: Invalid argument: connect`. Use `build.cmd` instead of calling
`gradlew` directly:

```powershell
.\build.cmd build        # → build\libs\stasisbot-0.1.0.jar  (verified working)
.\build.cmd runClient    # → a dev Minecraft for local testing
```

> Prefer plain `gradlew` / VS Code's Gradle sidebar? Set the fix once as a Windows
> user env var (it also helps the Minecraft client and the Paper maintenance server):
> ```powershell
> setx JAVA_TOOL_OPTIONS "-Djdk.net.unixdomain.tmpdir=C:/Temp"
> ```
> The `.vscode` settings already inject `JAVA_HOME` + `JAVA_TOOL_OPTIONS` into the
> integrated terminal.

### Use it on your real 2b2t instance

1. `.\build.cmd build`
2. Copy `build\libs\stasisbot-0.1.0.jar` into the instance's `mods\`:
   `...\PrismLauncher\instances\2b2t2\minecraft\mods\`
3. That instance also needs **Fabric API** (`fabric-api-0.141.4+1.21.11`) in
   `mods\`. Baritone / Meteor can live alongside it.
4. Log the **bot account** in, park it within reach of your stasis levers.

## Using it in-game (zero commands)

1. Park the bot next to your stasis chambers, each with a sign naming the owner
   (or a keyword you've mapped).
2. For keyword signs, add the mapping to `config/stasisbot.json` and relog (or
   reopen the world) so it reloads.
3. Friends type `!home` in chat → the bot fires their loaded chamber.

Press **H** to open a **read-only monitor** showing what the bot currently
detects (each chamber's label, position and pearl status) — handy for checking
your signs/aliases are recognised. It triggers nothing itself.

## Project layout

```
src/main/java/fr/nyuway/stasisbot/
  StasisBotClient.java          entry point — builds the object graph & wiring
  KeyBindings.java              H → monitor
  config/StasisBotConfig.java   JSON config (trigger word, aliases, radii…)
  model/StasisChamber.java      immutable chamber (sign, trigger, sign words)
  scan/
    ChamberScanner.java         loaded-chunk block-entity scan (signs ↔ levers)
    ChamberIndex.java           throttled cache over the scanner
  entity/PearlDetector.java     AABB query: is a pearl loaded?
  identity/IdentityResolver.java player name → matching tokens (name + aliases)
  chat/
    ChatMessageParser.java      raw line → (sender, body)
    HomeRequestListener.java    chat events → service
  service/HomeService.java      orchestration: match → pick loaded → fire (debounced)
  activation/StasisActivator.java  rotate + right-click the trigger
  gui/StasisMonitorScreen.java  read-only status panel
```

## Roadmap ideas

- Detect the pearl thrower from the entity owner to auto-suggest aliases.
- Route "no pearl" alerts to a Discord webhook.
- A Meteor module / remote channel to drive the bot from your main account.
- Auto-Baritone path to the trigger if the bot is parked out of reach.
```
