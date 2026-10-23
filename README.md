# MINEKILL

**Play Minecraft inside ULTRAKILL.**

MINEKILL runs both games at once and merges them. ULTRAKILL is the window you play in: its levels,
its camera, its lighting and its enemies. Minecraft runs behind it as the simulation engine: your
player, movement, inventory, mining, building, mobs, items and saves are all real Minecraft.
Everything Minecraft shows is redrawn natively inside ULTRAKILL. No images are copied between the
two games.

> MINEKILL is an unofficial fan project. It is not affiliated with or endorsed by New Blood
> Interactive, Hakita, Mojang or Microsoft. You need your own legitimate copies of both games.
> The repository contains no game files or assets; everything is read from your installs at
> runtime.

---

## What works

- **Minecraft's player in ULTRAKILL's levels.** Pure Minecraft movement (walking, sprinting,
  sneaking, jumping, swimming, flying in creative). ULTRAKILL's camera follows Minecraft's eye.
  ULTRAKILL's own movement, guns and HUD are switched off.
- **ULTRAKILL's level is solid to Minecraft.** Walls, floors, ramps and doors collide with the
  player, with mobs, dropped items and minecarts, and nothing falls through the floors: an entity
  waits at the edge of the space whose collision has arrived instead of falling into it, and
  anything that ends up inside a floor is put back on top. Mobs path-find across ULTRAKILL
  floors. Arrows, tridents and thrown items hit and stick. Water and lava pool on and flow off
  ULTRAKILL geometry.
- **Minecraft blocks are solid to ULTRAKILL's enemies** (they walk around them, their shots hit
  them), and **the two games fight each other.** Every ULTRAKILL enemy near you has an invisible
  Minecraft stand-in, so swords, bows, tridents, potions, enchantments and fire hurt it. ULTRAKILL's
  attacks on you become Minecraft damage, so shields and armour work. When you die in Minecraft,
  ULTRAKILL restarts from its checkpoint.
- **Building and mining.** Place Minecraft blocks anywhere, including onto ULTRAKILL surfaces,
  and mine them, with break cracks, particles and the block outline. Buckets work on ULTRAKILL
  surfaces too.
- **Minecraft's world, drawn by ULTRAKILL.** Blocks (lit by ULTRAKILL's lights), fluids,
  animated textures, particles, mobs, players, dropped items, arrows, chests, beds, signs,
  banners and other block entities.
- **Minecraft's whole interface.** HUD, hotbar, health and hunger, chat, commands, inventories,
  containers, the creative menu, the pause menu and the F3 debug screen, all usable with
  ULTRAKILL's mouse and keyboard.
- **First-person hand and held items, F5 third person** with your own skin, and the player
  model in the inventory.

See *Known limitations* below for what is not done yet.

---

## How it works

```
   ULTRAKILL  (BepInEx 5 plugin, C#)          Minecraft 26.3  (Fabric mod, Java)
   ─────────────────────────────────          ──────────────────────────────────
   draws the world, camera, HUD     ◄──────   player, physics, inventory, blocks,
   publishes its level collision    ──────►   mobs, items, GUI; simulates everything
   forwards keyboard and mouse      ──────►   applies them as if typed into Minecraft

                  minekill.bridge   memory-mapped file, fixed layout (state, events, tables)
                  minekill.stream   memory-mapped ring (block meshes, textures)
```

Each frame Minecraft publishes its player, camera, GUI, hand, world models and particles, and
streams changed block sections and textures. ULTRAKILL publishes its keyboard and mouse input and
the collision geometry around the player and other entities. Minecraft applies those boxes inside
its own physics, pathfinding, fluid and projectile code, so its own rules handle them like blocks.

The design and the full ownership rules are in [`docs/CONTRACT.md`](docs/CONTRACT.md). The wire
layout is generated from a single schema and checked byte for byte on both sides:
[`bridge/protocol/PROTOCOL.md`](bridge/protocol/PROTOCOL.md).

---

## Requirements

| | |
|---|---|
| ULTRAKILL | Steam release, Unity 2022.3 Mono build (x86-64) |
| ULTRAKILL mod loader | [BepInEx 5.4.23.2](https://github.com/BepInEx/BepInEx/releases) **win_x64** |
| Minecraft | Java Edition **26.3** |
| Minecraft mod loader | [Fabric Loader](https://fabricmc.net/) **0.19.5** or newer (no Fabric API needed) |
| Java | 25 (what Minecraft 26.3 ships with) |

### Platforms

| Setup | Status |
|---|---|
| **Linux**: Minecraft native, ULTRAKILL under Wine | **Tested.** This is the development setup (Wine 11.17). |
| **Windows**: both games native | **Supported in code, not yet tested.** Reports welcome. |

The two setups differ only in where the shared files live (see *Configuration*) and how
ULTRAKILL's side opens them. Everything else is the same code.

---

## Install

1. **BepInEx in ULTRAKILL.** Extract BepInEx 5.4.23.2 **win_x64** into the ULTRAKILL folder
   (next to `ULTRAKILL.exe`), and start ULTRAKILL once so BepInEx creates its folders. Under
   Wine no DLL override is needed.
2. **The ULTRAKILL plugin.** Copy `Minekill.ULTRAKILL.dll` into `ULTRAKILL/BepInEx/plugins/`.
3. **Fabric in Minecraft.** Create a Minecraft 26.3 instance with Fabric Loader 0.19.5 or newer
   (any launcher with Fabric support works).
4. **The Minecraft mod.** Copy `minekill-minecraft-0.1.0.jar` into the instance's `mods/` folder.
   No Fabric API is needed.
5. **A world.** Create a single-player world. A **Superflat world with the Void preset** is
   recommended: MINEKILL puts your Minecraft player in ULTRAKILL's level at a fixed spot
   (x 1000, y 128, z 1000), and Minecraft terrain there would stand in the way.

Both files are on the [Releases page](https://github.com/niliota/MINEKILL/releases): download
`Minekill.ULTRAKILL.dll` and `minekill-minecraft-0.1.0.jar` from the latest release. The two
must come from the same release (they check each other's bridge layout at startup). You can also
build them yourself, as described in *Building*.

## Playing

1. Start Minecraft and load your world.
2. Start ULTRAKILL and load a level. The two connect on their own. When the level starts,
   Minecraft's player is moved to the spawn spot and held there until you press a movement key.
3. Play in the ULTRAKILL window. Leave Minecraft running behind it, but **do not minimise it**:
   a minimised Minecraft drops to a few frames per second and ULTRAKILL stutters with it.

### Controls

Minecraft's movement keys are read in ULTRAKILL: **WASD**, **Space**, **Left Shift** (sneak),
**Left Ctrl** (sprint), the mouse, **left/right/middle click**, the **scroll wheel** and **1-9**.

| Key | Does (in Minecraft) |
|---|---|
| E | Inventory |
| T, / | Chat, command line |
| Q | Drop item |
| ` (backtick) | Minecraft's pause menu (Esc stays ULTRAKILL's own) |
| F3 | Debug screen; hold F3 for the F3 + key combinations |
| F5 | Change perspective |

While a Minecraft screen is open, every key, typed characters and the mouse go to that screen.
F9, F10 and F11 are development keys that cycle the materials used for solid, cut-out and
translucent blocks.

---

## Configuration

Normally nothing. Both sides pick the same files on their own:

| Platform | Minecraft | ULTRAKILL |
|---|---|---|
| Linux + Wine | `/var/tmp/minekill.bridge` | `Z:\var\tmp\minekill.bridge` (Wine's `Z:` is `/`) |
| Windows | `%LOCALAPPDATA%\MINEKILL\minekill.bridge` | the same |

`minekill.stream` sits next to the bridge file, and ULTRAKILL writes a live status file,
`ultrakill-status.txt`, there too.

To use a different location, set the environment variable **`MINEKILL_BRIDGE`** to an absolute
path for **both** games, naming the same file (under Wine, the `Z:` form for ULTRAKILL). Never use
a relative path: each game would resolve it against its own working folder and they would never
meet.

Minecraft settings worth changing: lower the render distance and set Max Framerate to 60 to
leave GPU time for ULTRAKILL. MINEKILL already keeps both games running while unfocused.

---

## Building

You need: Python 3, the .NET SDK (any version that builds `net472`), Java 25 and Gradle.

```bash
# 1. Wire protocol: regenerates Protocol.cs, Protocol.java and PROTOCOL.md from the schema
python3 bridge/protocol/generate.py

# 2. Check that C# and Java agree on every offset (must print LAYOUT CHECK PASSED)
bridge/protocol/check-layout.sh

# 3. ULTRAKILL plugin  ->  ultrakill-mod/bin/Release/Minekill.ULTRAKILL.dll
cd ultrakill-mod && dotnet build -c Release
#    It compiles against ULTRAKILL's and BepInEx's own DLLs, read from your install
#    (default ../ULTRAKILL). Elsewhere:  dotnet build -c Release -p:GameDir=/path/to/ULTRAKILL

# 4. Minecraft mod  ->  minecraft-mod/build/libs/minekill-minecraft-0.1.0.jar
cd minecraft-mod && gradle build
```

Nothing from either game is redistributed by the build: the ULTRAKILL assemblies are only
compile-time references, and Minecraft is fetched by Fabric Loom from Mojang as usual.

---

## Troubleshooting

- **Nothing happens in ULTRAKILL.** Check `BepInEx/LogOutput.log` for `[MINEKILL]` lines. If
  BepInEx reports `0 plugins to load`, the plugin DLL is missing or BepInEx is the x86 build.
- **The games do not connect.** Minecraft's log (`logs/latest.log`) prints the bridge path it
  opened and `HANDSHAKE OK` once ULTRAKILL is seen. Both games must name the same file; see
  *Configuration*. ULTRAKILL's live state is in `ultrakill-status.txt`, which is more reliable
  than BepInEx's log under Wine.
- **Stutter.** Make sure Minecraft is not minimised, and lower its render distance.
- **Startup crash in Minecraft after updating.** MINEKILL targets Minecraft 26.3 exactly; its
  hooks name 26.3's own classes and methods.

## Known limitations

This is version 0.1, an early release.

- **Windows is untested.** Everything was developed and played on Linux with ULTRAKILL under
  Wine. The Windows code paths exist but have never run.
- **The Minecraft window stays open** behind ULTRAKILL (it is needed to load a world).
- **Combat is not balanced yet.** Minecraft damage reaches ULTRAKILL enemies 1:1, so small
  enemies die in one hit.
- **Burning mobs show no flames.**
- **ULTRAKILL slopes are steps.** Minecraft collides only with boxes, so ULTRAKILL's sloped and
  curved geometry reaches it as 1/8-block steps: walking up a ramp moves you up in small steps,
  and you can stand up to 1/8 block above a mesh floor.
- **Mobs more than 48 blocks away hold still** until you come closer: ULTRAKILL only prepares
  collision around entities that near, and they wait rather than fall.
- **Signs placed on ULTRAKILL walls** can sit slightly in front of or inside the surface.
- **Long scrolled lists in Minecraft menus are not clipped** to their box.

## Uninstall

- ULTRAKILL: delete `BepInEx/plugins/Minekill.ULTRAKILL.dll`. To remove BepInEx too, delete
  `winhttp.dll`, `doorstop_config.ini`, `.doorstop_version` and `BepInEx/`; nothing else in the
  game folder is touched.
- Minecraft: delete the jar from `mods/`.
- The shared files (`minekill.bridge`, `minekill.stream`, `ultrakill-status.txt`) can be deleted
  whenever both games are closed.

---

## Repository layout

```
bridge/protocol/   the wire schema (generate.py), its layout check, generated PROTOCOL.md
ultrakill-mod/     BepInEx 5 plugin (C#, net472)
minecraft-mod/     Fabric mod for Minecraft 26.3 (Java 25)
docs/
  CONTRACT.md      who owns what, units, channels, lifecycle
  HANDOVER.md      the current state and how the code is organised, for contributors
  SETUP.md         the development machine
  LEGAL.md         what this project does and does not contain, and why
  MODLOG.md        the development log, round by round
  INIT_PROMPT.md   the original brief the project was started from
```

## Credits

- [SkyCraft](https://github.com/chasmlol/SkyCraft) (MIT) by chasmlol, the Skyrim + Minecraft
  project whose architecture this one follows: Minecraft as a hidden simulation engine, its
  world re-meshed and redrawn by the host, the host's collision injected into Minecraft. Ideas
  only; no SkyCraft code is included.
- [BepInEx](https://github.com/BepInEx/BepInEx) and [Fabric](https://fabricmc.net/), which make
  both mods possible.
- Built with heavy use of AI tooling (Anthropic's Claude), directed and playtested by the author.

## License

MINEKILL's own code is licensed under the **GNU General Public License v3.0**; see
[`LICENSE`](LICENSE). ULTRAKILL and Minecraft are the property of their respective owners and are
not covered by this license.
