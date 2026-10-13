# MINEKILL

Minecraft as the simulation and content engine for ULTRAKILL. You keep your Minecraft
inventory, your Minecraft movement and your Minecraft mining; ULTRAKILL provides the world,
the camera and its own enemies.

**Status: Stage 1 (the puppet) works. Minecraft's movement and mouse look drive ULTRAKILL's
player and camera.** Collision, inventory, mining and combat are not built yet. See *Where it
is* below for exactly what works.

---

## How it works

Two real games run at the same time and talk through one memory-mapped file.

```
  Minecraft (native Linux)                  ULTRAKILL (Wine)
  Fabric mod                                BepInEx 5 plugin
        |                                          |
        |   minekill.bridge  (memory-mapped file, both processes map it)
        +------------------------------------------+
```

A **mapped file** is used rather than a Windows named shared-memory object, because the two
processes are on opposite sides of the Wine boundary. A `Local\` object would be invisible
across that boundary; a mapped file is visible to both. This was verified empirically in both
directions before any of the bridge code was written, and re-verified against the real game.

Ownership is fixed in [`docs/CONTRACT.md`](docs/CONTRACT.md). Short version: **Minecraft owns
the player, inventory, mining, block placing, combat and saves. ULTRAKILL owns the world, the
camera and all of its own systems, which stay unmodified.** No images ever cross the bridge.

---

## Where it is

Done and verified (playtested 2026-10-09):

- BepInEx 5.4.23.2 (x64 Mono) loads in ULTRAKILL under Wine 11.17. No `WINEDLLOVERRIDES` needed.
- The Fabric mod for Minecraft 26.3 builds and loads.
- Both sides open the shared file, agree on protocol version 1, and see each other's process id
  and heartbeat. No configuration required; both default to `/var/tmp/minekill.bridge`.
- **Minecraft drives ULTRAKILL's player.** WASD moves the ULTRAKILL puppet correctly at every
  heading, mouse look turns both cameras together, and turning the camera does not move the
  player.
- ULTRAKILL keeps the OS cursor; neither game pauses when unfocused.
- The wire layout is generated once and checked to be byte-identical across C# and Java.

Not built yet: collision (ULTRAKILL geometry is not solid to the Minecraft player), placing
blocks onto ULTRAKILL geometry, the GUI/hand layers, combat, and the optional ULTRAKILL dash/slide
toggle (the puppet currently disables ULTRAKILL's movement entirely, so dash and slide are not
available at all yet).

---

## Install

You need both games installed, and the project must already be set up as described in
[`docs/SETUP.md`](docs/SETUP.md).

**ULTRAKILL side**

1. Copy `ultrakill-mod/bin/Release/Minekill.ULTRAKILL.dll` into
   `ULTRAKILL/BepInEx/plugins/`.
2. BepInEx itself lives in `ULTRAKILL/` (`winhttp.dll`, `doorstop_config.ini`,
   `.doorstop_version`, `BepInEx/`). If it is not there yet, extract BepInEx 5.4.23.2
   **win_x64** into the ULTRAKILL folder.
3. Nothing to configure: both sides find the bridge file on their own (see below).

**Minecraft side**

1. Copy `minecraft-mod/build/libs/minekill-minecraft-0.1.0.jar` into the instance's
   `minecraft/mods/` folder.
2. Nothing to configure (see *Setting `MINEKILL_BRIDGE`* below).

### Both games must keep running when unfocused

Only one window can be focused, and the other game will be in the background for the whole
session, so **neither game is allowed to pause when it loses focus**:

- **Minecraft** — set **Pause On Lost Focus** to off (Options → Video). This is a vanilla
  option; the installer sets `pauseOnLostFocus:false` in `options.txt` for you, and you can
  change it back at any time.
- **ULTRAKILL** — handled by the plugin. ULTRAKILL pauses through Unity's own
  `Application.runInBackground`, which defaults to off. Note this must be set from a loop
  that runs *after* Unity applies its Player Settings; setting it once during startup is
  silently undone.

**Do not minimise Minecraft; just leave it behind ULTRAKILL.** Minecraft caps itself at
10 FPS while minimised, and the bridge updates once per Minecraft frame, so a minimised
Minecraft makes the ULTRAKILL player stutter. To save GPU on an integrated chip, lower
Minecraft's render distance or Max Framerate (60 is plenty) instead. The mod already stops
Minecraft's "Reduce FPS when AFK" throttle from kicking in while ULTRAKILL is connected.

### Which game do you look at?

**ULTRAKILL.** It is the host: it owns the world, the camera and the window you play in.
Minecraft is the simulation engine behind it. This mirrors
[SkyCraft](https://github.com/chasmlol/SkyCraft), where "Minecraft runs hidden in the
background, and Skyrim draws everything" — SkyCraft gives Minecraft no window at all, which is
where this project is heading.

### Setting `MINEKILL_BRIDGE`

**Normally you don't need to.** Both sides default to `/var/tmp/minekill.bridge`, so they find
each other with no configuration:

- Minecraft resolves `/var/tmp/minekill.bridge`
- ULTRAKILL resolves the same file as `Z:\var\tmp\minekill.bridge`

`/var/tmp` is used because `Z:` is the one anchor Wine always provides — it is the filesystem
root — so the path needs no username, no home directory and no assumptions about how the Wine
prefix was built. Paths derived from the home directory were tried first and abandoned: inside
Wine the home cannot be located reliably, and different prefixes produced three different wrong
answers.

`MINEKILL_BRIDGE` is only an override for unusual setups. If you do set it, it must be the
same file on both sides — the Linux path for Minecraft, and the `Z:` form for ULTRAKILL, because
Wine maps `Z:` to `/`.

A relative path must never be used here. Each process resolves it against its own working
directory, so the two would silently open different files and never meet.

To confirm what each side resolved, read `/var/tmp/ultrakill-status.txt` while both games are
running. It is the dependable source, because BepInEx's log stops flushing under Wine.

---

## Uninstall

**ULTRAKILL**

1. Delete `ULTRAKILL/BepInEx/plugins/Minekill.ULTRAKILL.dll`.
2. To remove BepInEx entirely: delete `ULTRAKILL/winhttp.dll`,
   `ULTRAKILL/doorstop_config.ini`, `ULTRAKILL/.doorstop_version` and `ULTRAKILL/BepInEx/`.
   Nothing else in the game folder is modified, so those four are a complete rollback.

**Minecraft**

1. Delete `minecraft/mods/minekill-minecraft-0.1.0.jar`.
2. Delete the `minekill.bridge` file if you want a clean slate.

---

## Rebuilding

```bash
# protocol (regenerates Protocol.cs, Protocol.java and PROTOCOL.md)
python3 bridge/protocol/generate.py

# verify C# and Java agree byte for byte — run this on every change
bridge/protocol/check-layout.sh

# ULTRAKILL side
cd ultrakill-mod && dotnet build -c Release

# Minecraft side
cd minecraft-mod && gradle build
```

`check-layout.sh` is not optional politeness. Both sides read the same bytes at the same
offsets; if the two layouts ever drift, the failure shows up as random stutter or damage that
does nothing, with no error anywhere. The check turns that into a build failure.

---

## Known issues

- **Vanilla ULTRAKILL does not tick while its window is unfocused.** The plugin fixes this by
  setting `Application.runInBackground` (it settles around frame 120). Without the plugin, or
  in the first ~2 seconds, an unfocused ULTRAKILL looks frozen — so never diagnose an
  ULTRAKILL stall without first checking whether the plugin had loaded.
- **BepInEx's log stops flushing under Wine.** Read `/var/tmp/ultrakill-status.txt` for
  live bridge state instead.
- **ULTRAKILL geometry is not solid yet.** The player collides only with Minecraft's own
  world. Collision injection is the next major milestone.
- **The process id ULTRAKILL reports is a Wine process id**, not a Linux one, because
  ULTRAKILL only knows about its own Windows process space. It is non-zero and unique enough
  for liveness checks, but it will not match what `ps` shows on Linux.
- **Minecraft 26.3 ships unobfuscated**, and it renamed things: `MinecraftClient` is now
  `net.minecraft.client.Minecraft` and `tick()` is `runTick(boolean)`. Fabric publishes an
  empty intermediary mapping set for it, which is why the build has a `mappings` line that
  looks redundant. See the comments in `minecraft-mod/build.gradle`.
- No `WINEDLLOVERRIDES` have been needed so far. BepInEx 5 Mono injects via `winhttp.dll`,
  which Wine implements itself.

---

## Layout

```
bridge/protocol/        one schema, generated for both languages
  generate.py           the source of truth for the wire layout
  check-layout.sh       proves the C# and Java layouts match
  PROTOCOL.md           generated protocol documentation
  layout-check-cs/      dumps the real C# struct offsets
  layout-check-java/    dumps the Java offsets
ultrakill-mod/          BepInEx plugin (C#, net472)
minecraft-mod/          Fabric mod (Java 25)
docs/
  CONTRACT.md           who owns what, units, lifecycle. Read this first.
  MODLOG.md             what was done, what was verified, what went wrong
  SETUP.md              how this machine is set up
```

## Licence

This project's own code is MIT. It contains no ULTRAKILL or Minecraft assets, and it must
never contain any: both games' assets are read from the player's own installs at runtime.
See [`docs/LEGAL.md`](docs/LEGAL.md) before publishing anything.