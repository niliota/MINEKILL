# MINEKILL — handover

The current state of the project and how the code is organised, for anyone (human or model)
picking it up. Read this, then `CONTRACT.md`, before changing code. `MODLOG.md` has the full
history with reasoning; search it for whatever you are about to touch.

Last updated 2026-10-14.

---

## 1. What it is

MINEKILL merges ULTRAKILL and Minecraft 26.3. ULTRAKILL is the host: its levels, camera,
lighting, enemies and window. Minecraft runs behind it as the simulation engine: the player,
physics, inventory, blocks, mobs, items, GUI and saves. A BepInEx plugin and a Fabric mod talk
through two memory-mapped files. Everything Minecraft shows is rebuilt from data and drawn by
ULTRAKILL; no images cross.

## 2. Decisions from the owner (do not reverse without asking)

1. **ULTRAKILL is the host, Minecraft owns the player.** ULTRAKILL's player is a puppet.
2. **No ULTRAKILL movement at all.** Pure Minecraft movement; no dash, no slide, no toggle.
3. **Fully native rendering.** Never an image overlay or frame transport.
4. **ULTRAKILL's guns, fists and HUD are off;** Minecraft's GUI replaces the HUD.
5. **The Minecraft window stays for now** (needed to load a world). Hiding it is a later task.
6. **Offline, single-player only.** No game assets in the repository, ever.
7. **Commit and push each confirmed change** (with a descriptive message) before starting the
   next one. Never commit `ULTRAKILL/`, `UltraCraft/` (the Minecraft instance folder), `backups/`
   or build output.
8. **Keep the local username and home paths out of tracked files.** Hardware, launcher
   (FreesmLauncher) and similar setup details are fine and should stay documented.

## 3. What works (all playtested by the owner)

- Movement and camera: every direction and heading, sneaking at edges, sprinting, smooth, the
  camera at Minecraft's eye; F5 behind/in front, clipped by ULTRAKILL walls.
- Collision of ULTRAKILL geometry for the player and every other entity; mob pathfinding across
  it; fluids flow and pool on it; projectiles hit and stick; buckets and block placement target it.
- Spawn sync per level, void safety net, feet anchored on the floor under spawn.
- Nothing falls through ULTRAKILL floors (2026-10-20), in layers: ULTRAKILL publishes the regions
  whose collision is complete and Minecraft keeps every entity's moves inside them
  (EntitySafety.guard); anything that passes or sinks into a floor top is put back on it
  (EntitySafety.rescue); VoxelField re-checks cached blocks continuously (Verify); a collider back
  after an absence is re-probed.
- Blocks, fluids (correct tint), animated textures, particles, cracks (including block entities),
  the block outline; entities and block entities; first-person hand; third-person body with the
  player's skin (both layers).
- Minecraft's whole GUI, including item icons for special models (chests, beds, shields), the
  player in the inventory, fonts, chat, commands, F3; keyboard (SDL3 scancodes and keycodes),
  typed text, mouse, middle click, scroll, hotbar keys; backtick opens Minecraft's pause menu.

## 4. Open issues and next steps

- **Combat balance:** combat is built (proxies, shields, armour, death/restart); Minecraft hits
  currently kill small ULTRAKILL enemies in one blow (1:1 damage). Tune with the owner.
- Missing visuals: burning-entity flames, unifont glyphs (rare characters),
  GUI scissoring (long lists), interpolated animation frames.
- Native Windows: path and file-sharing support is in (`Platform.cs`, `Bridge.defaultPath`), never
  run.
- Hide the Minecraft window (later, owner's call).

## 5. Repository map

```
bridge/protocol/generate.py     THE wire schema. Emits Protocol.cs, Protocol.java, PROTOCOL.md.
bridge/protocol/check-layout.sh compiles C#/Java dumpers and diffs every offset. Run every change.

ultrakill-mod/src/              BepInEx plugin (net472, ULTRAKILL's Mono)
  Plugin.cs                     entry; creates the hidden host object
  BridgeHost.cs                 MonoBehaviour, execution order 10000: wires everything per frame,
                                runInBackground, status file
  Bridge.cs, Platform.cs        transport (seqlocks, rings, tables); Wine/Windows paths and mapping
  StreamReader.cs               the stream ring reader
  PuppetController.cs           MC->UK mapping, anchoring, camera, F5, disabling UK movement/guns
  InputForwarder.cs             keyboard/mouse -> INPUT slot and UK_EVENTS (SDL codes)
  CollisionPublisher.cs         exact boxes around the player and entity clusters, census, tracking
  VoxelField.cs                 1/8-block probing of non-box colliders, merging, invalidation,
                                known regions, continuous re-checking (Verify)
  McWorldView.cs                sections, atlases, materials (F9/F10/F11), particles, world models,
                                outline, dynamic textures, animated-sprite patches
  HudView.cs                    GUI replay (GL), hand drawing, cursor/pause handling
  SelfTest.cs                   layout self-check at handshake
  Protocol.cs                   GENERATED

minecraft-mod/src/main/java/dev/minekill/   Fabric mod (Java 25)
  MinekillClient.java           per-frame entry (from MinecraftMixin.runTick), liveness, wiring
  InputApplier.java             applies INPUT and events: keys, buttons, hotbar, cursor, screens
  PlayerPublisher.java          PLAYER, CAMERA, SELECTION
  PlayerSafety.java             spawn sync, hold, void net
  EntitySafety.java             no falling through floors: known-region guard and rescue, every entity
  UkCollision.java              ULTRAKILL boxes: snapshot, grid index, queries for every hook
  CollisionInterest.java        entity clusters -> COLLISION_INTEREST
  UkPick.java                   aiming at ULTRAKILL surfaces (placement)
  ProjectileClip.java           block traces that also hit ULTRAKILL boxes
  bridge/                       Bridge.java, Stream.java, Protocol.java (GENERATED)
  gui/                          GuiCapture (GUI quads, items, PiP entity), GuiLayout, GuiTextures
  render/                       WorldMesher (sections), AtlasExport, AtlasAnimator, ParticleExport,
                                ModelCollector (submitted models -> quads), HandCapture, WorldModels
  mixin/                        hooks into Minecraft (list below)
```

Mixins, by purpose:
- input and focus: `MinecraftMixin`, `MouseHandlerMixin`, `WindowMixin`
- collision: `EntityCollisionMixin`, `EntityMoveSafetyMixin`, `PlayerEdgeMixin`, `EntityPickMixin`
- pathfinding: `PathContextMixin`, `WalkNodeEvaluatorMixin`, `PathNavigationMixin`, `GroundPathNavigationMixin`,
  `GoalUtilsMixin`, `LandRandomPosMixin`
- fluids, projectiles, aiming: `FlowingFluidMixin`, `AbstractArrowMixin`, `ProjectileUtilMixin`,
  `ProjectileMixin`, `ItemMixin`
- capture: `GuiRendererMixin`, `FontTextureMixin`, `PictureInPictureRendererMixin`,
  `GameRendererMixin`, `HandRendererMixin`, `LevelRendererMixin`, `LevelExtractorMixin`
- accessors: atlases, sprites, particles, render types

## 6. Working loop

```bash
python3 bridge/protocol/generate.py && bridge/protocol/check-layout.sh
(cd ultrakill-mod && dotnet build -c Release) && cp ultrakill-mod/bin/Release/Minekill.ULTRAKILL.dll ULTRAKILL/BepInEx/plugins/
(cd minecraft-mod && gradle build --no-daemon) && cp minecraft-mod/build/libs/minekill-minecraft-0.1.0.jar UltraCraft/minecraft/mods/
```

- A plugin change needs an ULTRAKILL restart; a mod change needs a Minecraft restart.
- Before adding a mixin, confirm the target in the 26.3 bytecode
  (`javap -p -cp <client jar> <class>`; for a redirect, the method containing the call). A
  missing target crashes Minecraft at load.
- Logs: `ULTRAKILL/BepInEx/LogOutput.log`, `/var/tmp/ultrakill-status.txt`,
  `UltraCraft/minecraft/logs/latest.log`, `.../crash-reports/`.
- Log every round in `MODLOG.md`: what the owner reported, the cause found, the change, and the
  owner's confirmation.

## 7. Lessons that cost the most time

The project repeatedly failed the same way: deriving a convention instead of measuring it.

1. A value identical across runs is a constant, not noise (a "garbage" key mask was the high
   half of an epoch timestamp).
2. A transform derived at one input value is not a transform; check it across the range.
3. Verify claims about code against the actual code path, not comments.
4. When a transform depends on two sources, cache both at the same moment or neither.
5. Order matters and is invisible (a basis sampled before the camera turned cost 82 degrees).
6. A diagnostic that cannot report its own staleness will be trusted when stale.
7. Check the verification before trusting it; a check wrong in the bug's direction confirms it.
8. Do not gate on ULTRAKILL's `Application.isFocused` under Wine.
9. A `@Redirect` is per call site; similar-looking calls elsewhere are separate.
10. Persistent shared files outlive layouts: anything that moves offsets must invalidate old
    files (now automatic through the layout fingerprint).
11. Read the target game's code for the whole path. Several fixes worked only once the full path
    was read (Minecraft ignores projectile hits on "air"; chests have an empty block model; an
    inherited method is not a mixin target on the subclass).

## 8. Sharp edges in the code

- `Protocol.*` files are generated. Edit `generate.py`.
- `PuppetController`: `YawSign`, the pitch negation and the X-mirror basis were set from playtests
  after derivations predicted otherwise. The basis is cached only from a valid sample, reset per
  anchor. `MirrorZAxis_DoNotUse` is a documented dead constant kept as a warning.
- `InputApplier` writes Minecraft's `accumulatedDX/DY` by reflection; `MouseHandler.onMove` cannot
  turn the camera while the grab is suppressed.
- Two `isMouseGrabbed()` redirects exist (`MinecraftMixin`, `MouseHandlerMixin`); both are needed.
- `CORRECTION` and `LEVEL` slots are reserved and unused.
- Materials: the alpha-tested model material is cloned from an ULTRAKILL material that already
  alpha-tests (forcing `ALPHA_TEST` on a vertex-lit clone falls back to a non-discarding variant).
  Cut-out blocks cull back faces; models keep the template's culling.
- `BlockColliders`: each block's NavMeshObstacle reaches `ClearanceBlocks` (2.5) blocks below the
  block, so enemies do not walk under hovering blocks (carving only cuts where an obstacle reaches
  the navmesh on the floor). It is one height for every enemy: shorter enemies are kept out of
  gaps they would fit through, taller ones could pass under. Suspect it first for any odd enemy
  pathing near blocks (stopping short of a gap, detours under raised blocks, ledges, stairs).
- `ModelCollector` builds models on the spot (`setupAnim` + `renderToBuffer`) exactly as
  `ModelFeatureRenderer` would; it relies on Minecraft calling `setupAnim` again before its own draw.

## 9. Working with the owner

- Direct and technical; prefers short answers and concrete test steps at the end of each round.
- Report bad news early and plainly. Never claim something works before the owner tested it.
- When something cannot be determined, measure it (logs, bytecode) or ask; do not ship guesses.
- Give a specific, cheap experiment when a bug is unclear.
