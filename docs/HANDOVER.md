# MINEKILL — HANDOVER

**Read this first.** You are taking over an in-progress project. This document is the
authoritative state. It is written to be read once, top to bottom, before you touch code.

Written 2026-10-13. Previous model: Claude (Opus 5). The user is the human owner.

---

## 1. What this project is

**MINEKILL merges ULTRAKILL and Minecraft.**

- **Minecraft is the simulation engine.** It owns the player, physics, inventory, mining,
  block placing, combat, and saves.
- **ULTRAKILL is the host world and renderer.** It supplies the geometry, the camera, the
  enemies, the level design.
- They talk through a **shared-memory bridge** in a single file. Minecraft publishes its
  player state; ULTRAKILL forwards its input to Minecraft and mirrors Minecraft's view.

The fantasy: a Minecraft player (Steve) with full Minecraft abilities, walking on
ULTRAKILL's geometry, with ULTRAKILL rendering the world.

**Stage 1 is complete and working.** Movement, camera, and the mapping between them all
behave correctly. See §5.

---

## 2. Hard constraints — do not violate these

These came from the user directly. Breaking them wastes the whole effort.

1. **ULTRAKILL is the host. Minecraft runs hidden/background.** Never the reverse.
2. **Do not change how ULTRAKILL works, looks, or behaves.** Read-only reference. The only
   writes into `ULTRAKILL/` are `BepInEx/plugins/*.dll` and the status file.
3. **ULTRAKILL movement features (dash, slide) must exist but default OFF.**
4. **Fully offline, single-player only.** Both games always running. No network, no accounts.
5. **Minecraft owns the player.** ULTRAKILL's player becomes a puppet. Never the reverse.
6. **As legal as possible.** The user will publish it themselves. See `docs/LEGAL.md`.
7. The user is comfortable with C#/Unity but has said "don't expect too much". Explain
   non-obvious things; do not assume deep Unity or Fabric knowledge.

---

## 3. Where things are

Working directory: `<project folder>`

```
bridge/protocol/generate.py      SOURCE OF TRUTH for the wire layout. Do not hand-edit
                                 the generated Protocol.cs / Protocol.java.
bridge/protocol/check-layout.sh  Run on every change. Proves C# and Java agree.
docs/CONTRACT.md                 Ownership, units, lifecycle, environment gotchas.
docs/MODLOG.md                   1461 lines. Every bug, in order, with reasoning.
                                 READ THE SECTIONS RELEVANT TO WHAT YOU TOUCH.
docs/SETUP.md, docs/LEGAL.md, README.md
init_prompt.md                   The original brief.

ultrakill-mod/                   BepInEx 5 plugin, .NET Framework 4.7.2 (net472)
  src/Plugin.cs                  Entry point. MUST derive from BepInEx.BaseUnityPlugin.
  src/BridgeHost.cs              MonoBehaviour host, DefaultExecutionOrder(10000).
                                 Update = puppet movement, LateUpdate = camera + input.
  src/Bridge.cs                  Transport. Memory-mapped file, seqlock slots, rings.
  src/Protocol.cs                GENERATED. Do not edit.
  src/PuppetController.cs        Position + camera mapping. Most intricate file.
  src/InputForwarder.cs          UK keyboard/mouse -> INPUT slot.
  src/SelfTest.cs                One-shot layout verification at handshake.

minecraft-mod/                   Fabric mod for Minecraft 26.3
  src/main/java/dev/minekill/MinekillClient.java   Tick entry, focus handling.
  src/main/java/dev/minekill/InputApplier.java       Applies forwarded input.
  src/main/java/dev/minekill/PlayerPublisher.java   Publishes player state.
  src/main/java/dev/minekill/bridge/Bridge.java     Transport (Java side).
  src/main/java/dev/minekill/bridge/Protocol.java   GENERATED. Do not edit.
  src/main/java/dev/minekill/mixin/MinecraftMixin.java
  src/main/java/dev/minekill/mixin/MouseHandlerMixin.java
  src/main/java/dev/minekill/mixin/WindowMixin.java

ULTRAKILL/                       Read-only game install. Write only:
                                 BepInEx/plugins/Minekill.ULTRAKILL.dll
UltraCraft/                      Minecraft instance. Write only:
                                 minecraft/mods/minekill-minecraft-0.1.0.jar
```

**Everything is untracked in git** (`git status` shows `??` for all of it). `.gitignore`
excludes `ULTRAKILL/`, `UltraCraft/`, `*.bridge`, `build/`, `obj/`, `bin/`. There are **no
commits**. The user has never asked for git work; do not start committing unprompted.

---

## 4. Build and deploy

**ULTRAKILL side:**
```bash
cd ultrakill-mod && dotnet build -c Release
cp bin/Release/Minekill.ULTRAKILL.dll ../ULTRAKILL/BepInEx/plugins/
```

**Minecraft side:**
```bash
cd minecraft-mod && gradle build --no-daemon
cp build/libs/minekill-minecraft-0.1.0.jar "../UltraCraft/minecraft/mods/"
```

**Always run after either:**
```bash
./bridge/protocol/check-layout.sh     # must print LAYOUT CHECK PASSED
```

Toolchain: dotnet 10.0.111, Java 25, Gradle 9.7.1, `ilspycmd` at `~/.dotnet/tools`.

**Restart requirements — this matters and has caused confusion:**
- UK plugin changes → **restart ULTRAKILL**. No other way.
- MC mod changes → **restart Minecraft**, through the launcher.
- Installing the MC jar does NOT affect a running Minecraft, and vice versa.

Killing ULTRAKILL: `pkill -f "ULTRAKILL.exe"` kills the calling shell. Use:
```bash
for p in $(pgrep -x "ULTRAKILL.exe"); do kill "$p"; done
```

---

## 5. Current state: what works

Verified working by the user on 2026-10-09:

| Feature | Status |
|---|---|
| Bridge handshake, heartbeats, both PIDs | Works |
| Minecraft movement drives the UK puppet | **Correct**, all four directions, every heading |
| Mouse look | **Correct**, ULTRAKILL mirrors Minecraft's view |
| Camera turn no longer moves the player | Fixed |
| Unbounded Minecraft yaw vs UK's ±360 clamp | Fixed (yaw wrapped) |
| UK window keeps the OS cursor | Fixed (grab suppressed while bridged) |
| Self-test at handshake | Passing |

**Not yet working / not yet built** — see §7. Also: **dash/slide do not exist yet.** The
puppet neutralises `NewMovement` entirely, so constraint 3's "exists but default OFF" toggle
still has to be built. It is not yet on the milestone list.

**Minecraft keeping its own window is the user's decision.** Hiding it comes later; do not
treat it as a gap.

---

## 6. The environment, and its traps

These are the things that will waste your afternoon if you don't know them.

- **Wine 11.17**, user's own `~/.wine` prefix. ULTRAKILL runs under Wine. **No
  `WINEDLLOVERRIDES` are needed.** Wine maps `s:` → `/home`, so the same file is
  `Z:\var\tmp\minekill.bridge` in UK and `/var/tmp/minekill.bridge` in MC.
- **Bridge path**: `/var/tmp/minekill.bridge`. Override with env var
  `MINEKILL_BRIDGE` on either side. All home-directory guessing was deleted — every
  mechanism was wrong.
- **Minecraft 26.3 is UNOBFUSCATED.** Real Mojang names, no refmap. `MinecraftClient` is
  now `Minecraft`, `tick()` is `runTick(boolean)`, and the current screen is
  `mc.gui.screen()`, not a `screen` field.
- **BepInEx's log freezes under Wine.** It stops updating mid-write and loses lines. This is
  why `/var/tmp/ultrakill-status.txt` exists — it never freezes. **Prefer the status file.**
  The log is also append-only across sessions, so `tail` when reading it.
- **ULTRAKILL takes ~40 s of boot before a player object exists.** The puppet attaches
  around frame 2300 and re-acquires after every level load. This is normal, not a hang.
- **Vanilla ULTRAKILL does not tick while unfocused.** The plugin fixes this by setting
  `Application.runInBackground` from `BridgeHost.Update` (settles ~frame 120); setting it in
  `Awake`/`Start` does not survive because Unity's Player Settings overwrite it. Without the
  plugin loaded, an unfocused or automated launch looks frozen.
- **Minecraft instance** is at `UltraCraft/` (canonical); `~/Games/Minecraft/Instances/UltraCraft`
  is a symlink to it. Launch through PrismLauncher/Freesm.
- **`Application.isFocused` is unreliable under Wine.** It was true ~32% of the time. Do not
  build logic on it; it is now diagnostics only.

---

## 7. Next milestones, in the order the user chose them

The user chose: **void world + inject ULTRAKILL's collision**, and **Minecraft blocks
placed anywhere on the UK map must have full collision for both player and NPCs**, the
SkyCraft model.

### Milestone C — empty world you walk in
Switch the player to a void/superflat world so Minecraft's terrain stops blocking them.
Cheap. Prerequisite for the rest, because currently ULTRAKILL contributes *no* collision and
Minecraft contributes all of it. The user's complaint "Minecraft blocks get in the way" is
this from the other side.

### Milestone D — collision injection (the hard one, and unproven)
**Do this spike first:** make exactly one ULTRAKILL wall solid to the Minecraft player.
Nothing else. That teaches you where MC 26.3's collision entry points are, and tells you
whether "inject shapes into collision queries" is an afternoon or a fortnight. If it is
infeasible you want to know before building a stage on top of it.

Two candidate approaches, D1 faithful / D2 pragmatic:
- **D1** — inject ULTRAKILL's collision *shapes* into Minecraft's own collision queries.
  Exact geometry, works for NPCs as well as the player. Requires finding and mixin-ing
  MC 26.3's collision entry points, **which have not been located yet**.
- **D2** — voxelise ULTRAKILL geometry into a hidden, non-rendered chunk used only for
  collision. Far less work, but one-block resolution and CPU/RAM cost proportional to level
  size.

The user picked D1's direction. D2 is a legitimate stepping stone if D1 proves intractable —
but raise it with them rather than silently doing it.

### Milestone E — placing blocks onto ULTRAKILL geometry
**Raise this design question before starting.** You can only place a block by targeting a
block face, and ULTRAKILL geometry will not be Minecraft blocks, so there is no face to
target. LMB/RMB need redefined semantics:
- LMB on ULTRAKILL geometry → nothing (it is not mineable)
- RMB on ULTRAKILL geometry → place a block on the face we compute against ULTRAKILL's
  collision

This needs a custom placement path: raycast against ULTRAKILL geometry, snap to the grid,
place there. It also affects the GUI/hand layer stage.

### Stage order agreed with the user
puppet/movement+camera (**done**) → GUI layer → world layer (depth) → hand layer →
collision injection → combat.

---

## 8. The lessons that cost the most time. Read these before you reason about conventions.

This project repeatedly failed the same way: **deriving a convention instead of measuring
one.** Every major bug was findable in a log within one round, and was instead attacked by
arithmetic that produced confident wrong answers.

Concretely, and each of these cost real rounds:

1. **A value that is identical across runs is a constant, not corruption.** `keys=0x1a1` was
   byte-for-byte identical in two sessions 15 minutes apart. It was the high 32 bits of the
   millisecond epoch timestamp. Treating it as noise sent two rounds chasing the wrong thing.
   **If a number is suspiciously stable, that is the clue, not the detail.**

2. **A transform derived at one input value is not a transform.** The Z-axis mirror was
   derived when Minecraft's yaw happened to be -177.00, where it is correct. It is correct at
   exactly two headings. Measure across the range or do not claim it.

3. **Verify a claim about existing code against the actual code path.** A comment asserted
   `MouseHandler.onMove` "accumulates that delta and turns the player from it". It does not,
   in the branch we are in. That unverified comment caused a full round of wrong work.

4. **When a transform depends on two sources, cache BOTH at the same moment or neither.**
   Caching one half left the other live, and the symptom pointed *away* from the half still
   broken, because the fixed half stops contributing.

5. **Order matters and is invisible.** The basis cache was sampled in `Anchor()`, before the
   camera had ever been rotated to the anchor heading — it captured ULTRAKILL's default +Z and
   stayed there. 82° of WASD rotation. Pure ordering, no maths.

6. **A diagnostic that cannot report its own staleness will be trusted when it is stale.** A
   log line printing a cached value looks entirely plausible. `CacheUkBasis()` now self-checks
   and reports `valid` / `INVALID: sampled N degrees away`.

7. **Check the verification script before trusting it.** One check reported A and D swapped
   because I had labelled Minecraft's *right* vector as left. A check wrong in the same
   direction as the bug confirms the bug.

8. **Never gate on ULTRAKILL's `Application.isFocused`.** Unreliable under Wine (~32% true).
   Gate on bridge connection instead.

9. **A `@Redirect` is per call site.** Two identical-looking `isMouseGrabbed()` gates exist in
   different classes. Fixing one and reporting success is a real failure mode.

10. **Untrusted "instrumentation"** — printing your own cache back and calling it a camera
    reading cost a round.

`docs/MODLOG.md` has all of this in order with the reasoning. Skim the sections relating to
whatever you touch.

---

## 9. Known sharp edges in the current code

Read these before changing them; each has a comment explaining why.

- **`Protocol.SlotCorrection` is dead.** The correction loop was removed (it computed the
  inverse mapping wrong and teleported the player dozens of times a second). The slot is
  **reserved** at offset 184 in the generator and is needed again for collision injection.
  `MirrorZAxis_DoNotUse` in `PuppetController.cs` is a documented dead constant kept as a
  warning. Do not "clean these up" without reading the comments — they are load-bearing
  documentation.

- **`YawSign = 1f` in `PuppetController.cs`** was set from the user's report of reversed
  directions, *not* derived. The derivation said -1 and was wrong. The convention was later
  confirmed empirically (`UKliveFwd` matched `predSinCos` exactly), but the sign itself rests
  on observation. If camera directions ever look wrong again, that is the first thing to
  question — and check `fwdDot` in the log first.

- **Movement uses a FIXED linear map**, cached at the anchor (`CacheUkBasis`). Both the
  Minecraft basis and the ULTRAKILL basis are captured there and must stay captured. The map
  deliberately has **no term depending on current yaw**; that is what stops camera motion
  moving the player. `McToUk` therefore takes no yaw parameter.

- **`McRight()` must be Minecraft's right**, `-cos Y, -sin Y`. At yaw 0, Minecraft faces +Z
  (south) and the right hand points west (-X). Getting this backwards silently swaps A and D.

- **Two separate `isMouseGrabbed()` redirects exist** and both are required:
  `MinecraftMixin` for the mining gate in `handleKeybinds`, `MouseHandlerMixin` for the
  camera gate in `handleAccumulatedMovement`.

- **`MouseHandler.onMove` cannot turn the camera** while the grab is suppressed; it treats its
  argument as an absolute cursor position. `InputApplier` therefore writes Minecraft's own
  `accumulatedDX`/`accumulatedDY` by reflection. If a field is renamed in a future MC
  version, that breaks loudly and by design — do not paper over it.

- **ULTRAKILL reads look input from Unity's Input System, not legacy `Input`.** Legacy
  `Input.mousePosition` is a cursor *position* and freezes when the cursor is locked, which
  ULTRAKILL does during play. `InputForwarder` reads `Pointer.current.delta`.

- **UK pitch sign** (`_ukPitchAnchor = _mcPitchOrigin`, no negation) is set from the user's
  report, overriding a derivation that predicted the opposite. Same category as `YawSign`.

---

## 10. First actions for you

1. Read `docs/CONTRACT.md` in full. It is the shortest path to the system's shape.
2. Read §9 above and skim the relevant `docs/MODLOG.md` sections.
3. **Do not rebuild anything yet.** The installed artefacts are current and working. Confirm
   with the user before changing Stage 1 — it works, and the remaining work is additive.
4. Ask the user which milestone to start. The natural next step is Milestone C (empty world),
   then the Milestone D spike.
5. When you do measure something, prefer `/var/tmp/ultrakill-status.txt` over the BepInEx log,
   and prefer a session the user actually played over an automated one. Unattended sessions
   produce garbage: the Minecraft player drifts, `onGround=false`, yaw spinning. Several wrong
   conclusions came from exactly that.

---

## 11. Open questions the user has not answered

- **Collision injection approach**: D1 (shapes, faithful) vs D2 (voxelise, cheaper). User
  chose D1's direction but the spike has not been done.
- **Block placement onto ULTRAKILL geometry**: LMB/RMB semantics undefined (§7, Milestone E).
- **NPCs**: the user wants full collision for NPCs too. Not designed or started.
- Whether Minecraft's world is for building/mining/storage only, or the player should also
  stand on what they build. The user's answer was "in SkyCraft you can put blocks anywhere on
  the Skyrim map with full collision for player and NPCs. I want THAT." — so the second, but
  it needs priority rules so UK collision and MC block collision do not fight.

---

## 12. Scale wording

`1.94445` is **ULTRAKILL units per Minecraft block** (3.5-unit capsule / 1.8-block player).
Earlier docs and log lines called it "blocks per unit"; the maths was always right, only the
label was backwards. Fixed 2026-10-13.

## 13. Tone and working style

- The user is direct and technical. Short answers. No preamble.
- **Report bad news early and plainly.** They would much rather hear "I was wrong and here is
  why" than a hedge. Several rounds were lost by overclaiming; the user noticed every time.
- **Never claim something works without the user having tested it**, or make clear when it is
  verified by simulation/logs versus by the user.
- When you cannot determine something, **measure it or ask**. Do not guess a constant and
  ship it. Three shipped guesses were all wrong.
- The user is at their best when given a specific, cheap experiment that eliminates a whole
  class of hypotheses.