# Bridge contract — MINEKILL

Two games, one shared world. Minecraft is the simulation and content engine; ULTRAKILL is
the host world, renderer, camera and game shell. Every value that crosses between them is
defined here. **Check every change against this file.**

## Route

**Route 1 — live passthrough (state exchange).**

Real Minecraft runs as a real client. Real ULTRAKILL runs as the host. A Fabric mod and a
BepInEx mod exchange state through a memory-mapped file. Neither game works without the other.

Chosen because the requirements are real Minecraft inventory, real Minecraft movement and
real Minecraft mining. Frame compositing (route 2) cannot give any of those: it pastes a
picture. Geometry transfer (route 3) would give rendering but forfeits Minecraft's own
physics and inventory as the authority.

Not doing: images crossing the bridge at all. No frame transport, no depth buffer, no
compositing. This removes the largest family of passthrough bugs (torn frames, stale images,
depth mismatch) by construction.

## Programs and exact versions

| | |
|---|---|
| Host | ULTRAKILL, Hackita, Steam release. Unity **2022.3.29f1**, **Mono** backend (not IL2CPP). `Assembly-CSharp.dll` present in `ULTRAKILL_Data/Managed/`. |
| Host loader | **BepInEx 5 x64 Mono** (ULTRAKILL.exe is PE32+ / x86-64 — confirmed, not assumed) |
| Guest | **Minecraft 26.3**, Fabric Loader **0.19.5**, Java 25, mixinextras 0.5.5 |
| Guest loader | Fabric |
| Guest rendering | Native Linux. LWJGL 3.4.3, OpenGL 4.6 core via Mesa 26.2.2, Radeon 860M |
| OS | Arch Linux, kernel 7.2.4 |
| Translation layer | **Wine 11.17** for the host. Guest is native — no translation. |

Host install used: `ULTRAKILL/` inside this project (a copy). The Steam install is never
touched.

ULTRAKILL's exact version string is not readable from the binary offline; it will be read
from `GameManager` at runtime during the first canary and recorded here.

## Ownership

| Thing | Owner | How control is handed back, and how it returns |
|---|---|---|
| Player position and physics | **Minecraft** | ULTRAKILL never simulates the player. MC is authoritative at all times; UK reads position each frame to drive its camera. **No hand-back exists** — MC never gives movement to UK. |
| ULTRAKILL movement features (dash, slide, wall-jump) | **ULTRAKILL, disabled by default** | Per-player toggle. Off = pure Minecraft player, as specified. On = UK applies its own velocity impulses on top of MC's. MC's authority is retained: UK writes an *impulse request* through the bridge, never a position. **Not built yet:** the puppet currently neutralises `NewMovement` entirely, so dash/slide do not exist in any state. |
| Camera | **ULTRAKILL** | UK owns the camera and viewmodel. MC's FOV/rotation is written **to** UK, never read back. MC's local player does a yaw/pitch-only copy of the camera so its own raycasts aim where UK looks. |
| World geometry | **ULTRAKILL** (render and collision truth), **Minecraft** (player-placed blocks only) | The MC world is empty (void). ULTRAKILL draws and owns its level; MC holds only what the player builds. |
| Collision (UK → MC) | **ULTRAKILL shapes, resolved by Minecraft's physics** | Chosen direction (D1, SkyCraft model): UK collision shapes are injected into MC's own collision queries so MC physics resolves the player (and later NPCs) against them. **Unproven** — a one-wall spike comes first. Fallback D2 (voxelise UK geometry into a hidden collision-only chunk) is to be raised with the user, not adopted silently. |
| Collision (MC → UK) | **To be designed** | Requirement: MC blocks placed anywhere on the UK map have full collision for the player **and** UK NPCs. Not designed yet. Needs priority rules where UK and MC collision overlap. |
| NPCs / enemies | **ULTRAKILL** | UK spawns, thinks and moves its own enemies. MC never simulates them. |
| Damage and health | **Minecraft** | MC damage values are native MC damage. UK's HP is converted per-entity by its max HP. See *Units*. |
| Inventory | **Minecraft** | Full ownership. UK has no inventory concept that is bridged. |
| Mining / block breaking | **Minecraft** | Break progress, tool speed, drops and XP are all MC's. UK geometry is not mineable. |
| Block placing | **Minecraft** | MC places blocks. They are rendered by MC's own renderer (route 1, not native geometry). Placing onto UK geometry (no MC face to target) needs a custom raycast-and-snap path; LMB/RMB semantics there are undecided. |
| Saves | **Minecraft** | One persistent MC void world holds the player, inventory and placed blocks. UK saves are untouched and unaffected. |
| Menus, pause, loading screens | **ULTRAKILL** | UK pause halts the bridge writes; MC sees stale state and holds. MC's inventory screen stays fully usable while UK is paused. |
| Cutscenes, vehicles, scripted events | **ULTRAKILL** | MC's player is kinematic during UK cutscenes and parked at its last position. Returns to MC control on the event's end. |

## Units and axes

- **Distance: measured.** `scale = 1.94445` **ULTRAKILL units per Minecraft block**, from
  ULTRAKILL's 3.5-unit player capsule against Minecraft's 1.8-block player. Measured at runtime
  from the capsule (`PuppetController.MeasureScale`). MC offsets in blocks are multiplied by it
  to get UK units.
- **Axes:** ULTRAKILL is Unity: **+x right, +y up, +z forward**, left-handed.
  Minecraft: **+x east, +y up, +z south**, right-handed. Position is **relative**, not absolute:
  the first MC position seen after a level load is pinned to the UK player's position there, and
  only deltas apply. Y is a plain scale. The horizontal plane uses a **fixed linear map** built
  from Minecraft's and ULTRAKILL's horizontal bases, both cached at the anchor (no term depends
  on current yaw, which is what stops camera motion moving the player).
- **Angles (from observation, not derivation):** `CameraController.rotationY = wrap(mcYaw)` into
  (-180, 180] (`YawSign = +1`, relative to the anchor; MC yaw is unbounded and UK clamps to
  ±360, so the wrap is required). `rotationX = mcPitch`, clamped to ±89, no negation. Both signs
  were set from the user's playtest reports after derivations predicted the opposite.
- **Health/damage:** damage crosses as **native MC damage points** (MC deals 1–30ish per hit).
  ULTRAKILL enemy HP is divided by a per-enemy factor to convert UK's 100-point scale into
  MC points; the factor is per-enemy and derived from that enemy's max HP at spawn time.
- **Time:** Minecraft ticks at a fixed **20 Hz**. ULTRAKILL runs at its own frame rate. MC
  writes at 20 Hz; UK interpolates between the last two physics ticks for rendering.
  Never send the 20-tick position without its timestamp.

## Messages

| Channel | Kind | Direction | Rate | If full or late |
|---|---|---|---|---|
| `SLOT_PLAYER` | snapshot (seqlock) | MC → UK | 20 Hz | Latest value wins. If seqlock is torn, retry; drop after 3 attempts. |
| `SLOT_CAMERA` | snapshot (seqlock) | MC → UK | 20 Hz | Latest wins. |
| `SLOT_INPUT` | snapshot (seqlock) | UK → MC | every frame | Latest wins — input is naturally latest-value. |
| `SLOT_CORRECTION` | snapshot (seqlock) | UK → MC | — | **Reserved, unused.** The old correction loop was removed (it teleported the player). Kept for collision injection. |
| `SLOT_LEVEL` | snapshot (seqlock) | UK → MC | on level load | Defined, not yet written. |
| `RING_MC_EVENTS` | queue | MC → UK | as they occur (defined, unused) | Blocked writes drop the **newest** event and increment a dropped counter. Mining/damage events are rate-limited anyway. |
| `RING_UK_EVENTS` | queue | UK → MC | as they occur (defined, unused) | Same. Block-placed events drop rather than corrupt the stream. |

- **Transport:** one memory-mapped file at `/var/tmp/minekill.bridge` (size in `PROTOCOL.md`), which
  ULTRAKILL names as `Z:\var\tmp\minekill.bridge`. `Z:` is the filesystem root inside Wine
  and is the only anchor that does not depend on the prefix's layout or the username.
  A mapped file rather than a Windows named shared-memory object because the two processes
  are on **different sides of the Wine boundary** — Minecraft is native Linux, ULTRAKILL is
  inside Wine. A `Local\` named object is only visible to processes inside the same Wine
  prefix; a mapped file is visible to both. This is the single most important transport
  decision in the project.
  **Verified empirically, both directions, Wine 11.17 + native JVM 25** — a self-contained
  win-x64 .NET process and a Linux JVM process each wrote and read a shared `FileStream`/
  `MappedByteBuffer` of the same file on `Z:\`, and each observed the other's value. The
  design does not depend on this working by luck.
- **Protocol:** magic `ULTR` (0x55 0x4C 0x54 0x52), version **1**, little-endian throughout,
  8-byte aligned fixed-size slots, no serialization library in the hot path.
- **Header:** magic, version, both process IDs, both heartbeats (monotonic ms), slot seqlocks,
  ring read/write cursors, dropped-event counters.
- **Restart detection:** each side writes its PID and advances a heartbeat every 100 ms. If a
  side's heartbeat has not moved for 2000 ms it is declared dead.
- **Heartbeat clock: EPOCH milliseconds.** Both sides must use a clock origin they agree on.
  "Milliseconds since this process started" is not comparable across two processes that
  started at different moments; it reported a live peer as stale whenever the games were
  launched at noticeably different times.
- **Liveness requires a fresh heartbeat, not just a non-zero PID.** A hard-killed game leaves
  its PID and last heartbeat on the bridge, so `pid != 0` alone reports a dead process as
  connected.

## Lifecycle

- **Start order:** either order. Both open the file; whoever starts second completes the
  handshake. ULTRAKILL may start first and wait — that is the expected case.
- **On pause / menu (UK):** UK stops writing `SLOT_INPUT` and `RING_UK_EVENTS`. MC detects the
  frozen heartbeat, stops applying input, holds the player still, and shows nothing unusual.
  **MC's inventory remains fully usable** — pausing UK must not lock the player out of their
  inventory.
- **On focus loss (either game):** NEITHER game may stop ticking, because the other one is the
  window the player is looking at. ULTRAKILL pauses via Unity unless
  `Application.runInBackground` is set from a loop that survives Player Settings being applied;
  Minecraft pauses unless the vanilla `pauseOnLostFocus` option is false. Both are required.
- **On pause / menu (MC):** MC stops writing slots but keeps the file mapped. UK sees a frozen
  heartbeat and holds the camera where it is.
- **On loading a level (UK):** the puppet re-acquires the new player object, bumps the header's
  `ukSpawnRequest`, and waits. MC teleports its player to its fixed spawn spot
  (1000.5, 128, 1000.5), zeroes velocity, holds it still, and echoes the request in
  `PLAYER.spawnAck`. Only then does UK anchor, so every level starts from the same still body.
  The hold ends on the first movement key. Below the world floor MC
  returns the player to spawn (the anchor) instead of letting the void kill it. Once collision injection exists,
  UK will publish the level's collision shapes; **until they have arrived, MC must hold the
  player still rather than let them fall through the void.** (Original plan was voxel data via
  `SLOT_LEVEL`/`SLOT_VOXEL`; superseded by the D1 direction.)
- **On loading a save (UK):** same as a level load. MC's own save is separate and unaffected.
- **On death (MC):** MC owns death respawn entirely. UK's camera is unaffected.
- **On death (UK player):** not bridged. See *Not covered*.
- **If Minecraft dies:** UK's player controller goes kinematic and UK hands movement back to
  its own code within one frame. The player can still play ULTRAKILL alone.
- **If ULTRAKILL dies:** MC pauses and shows a "host not connected" message. MC does not crash.
- **Version mismatch:** both sides refuse to interpret the data. UK logs the mismatch and
  continues vanilla. MC shows the same. **Never interpret a header we did not recognise.**

## Not covered (be honest)

- **Player-placed blocks do not yet push ULTRAKILL enemies.** The user requires this (SkyCraft
  model), so it is in scope, but it is not designed and is a large job.
- **UK geometry cannot be mined.** It is not made of Minecraft blocks. **This is expected, not a
  bug.**
- **ULTRAKILL's own systems are unmodified.** Weapons, enemies, bosses, parry timing, style
  ranks, level scripting and achievements all behave exactly as vanilla. Anything that changes
  them is out of scope. The plugin does set `Application.runInBackground` so ULTRAKILL keeps ticking while
  unfocused; that is the one deliberate exception.
- **No multiplayer.** Both games are single-player only. UK has none; MC multiplayer is
  deliberately not bridged.
- **UK's player death is not bridged.** UK's own health/damage system is untouched.
- **No frame transport.** No compositing, no depth, no image channels at all.

## UNDECIDED

- Exact ULTRAKILL version string (build dated 2026-02-25; read the precise version at runtime).
- D1 (shape injection) vs D2 (voxelised collision chunk): pending the one-wall spike.
- LMB/RMB semantics when aiming at ULTRAKILL geometry.
- How MC blocks collide with UK NPCs, and priority rules where UK and MC collision overlap.
- Whether MC mobs spawn in UK levels (v1 stretch goal).
- (Resolved) ULTRAKILL's player is `NewMovement` and its camera is `CameraController`; see
  `PuppetController.cs`.

---

## Implementation notes that must not be rediscovered

These are not design choices, they are facts about the environment that cost time to find.

- **A BepInEx 5 plugin class must derive from `BepInEx.BaseUnityPlugin`.** Deriving from
  `UnityEngine.MonoBehaviour` is rejected silently: `Chainloader.ToPluginInfo` checks
  `type.IsSubtypeOf(typeof(BaseUnityPlugin))` and returns null *before* the code that warns
  about a missing attribute. The symptom is `0 plugins to load` with no error anywhere.
- **ULTRAKILL's `Assembly-CSharp.dll` has no `GameManager` and no `PlayerControl`.** Do not
  assume names from modding guides or older ULTRAKILL versions; confirm with `ilspycmd -l c`
  against the installed DLL.
- **Never hold a `FileStream` open across `MemoryMappedFile.CreateFromFile`.** Mono refuses the
  second open with `IOException: Could not open file`.
- **The BepInEx plugin GameObject is created with `HideFlags.None` and is destroyed by ULTRAKILL's
  scene change at boot**, within frame 0. `DontDestroyOnLoad` does not prevent it. Use a
  `HideFlags.HideAndDontSave` host object.
- **Set `[Logging.Disk] WriteUnityLog = true` in `BepInEx/config/BepInEx.cfg` while developing.**
  With it off, Unity exceptions in plugin code are invisible.
- **Minecraft 26.3 is unobfuscated**, and `MinecraftClient` is now
  `net.minecraft.client.Minecraft` with `runTick(boolean)` in place of `tick()`. Fabric's
  intermediary mapping set for 26.3 is empty, which is why the Gradle build needs a `mappings`
  line that looks redundant.
- **ULTRAKILL's process id is a Wine process id**, not a Linux one. Use it only as a liveness
  flag, never to match against `ps`.
- **`pkill -f "ULTRAKILL.exe"` kills the calling shell**, because the shell's own command line
  matches. Use `pgrep -x` and kill by exact PID.
- **Never use a relative path for the shared bridge file.** Both processes resolve it against
  their own working directory and end up on different files. It presents exactly like a dead
  bridge. Default is `/var/tmp/minekill.bridge` (`Z:\var\tmp\...` in Wine). Home-directory
  paths were tried and abandoned: Wine nulls `HOME`, `USERPROFILE` is not the Linux home, and
  prefixes differ in how they map drives.
- **`UnityEngine.Debug.Log` is invisible by default.** It only reaches `LogOutput.log` when
  `[Logging.Disk] WriteUnityLog = true`. Plugin messages go through BepInEx's log source.
- **BepInEx's disk logger stops flushing under Wine.** Read `/var/tmp/ultrakill-status.txt`
  for live state. (An apparent main-loop freeze seen early on was vanilla ULTRAKILL not
  ticking while unfocused; the plugin's `runInBackground` fixes that.)