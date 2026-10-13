# UltraCraft — MODLOG

Running log. Newest at the bottom. Not game-derived content — safe to commit.

## 2026-10-08 — Project start

**Decisions locked (from intake):**
- Route 1, live passthrough. MC = simulation/content engine. ULTRAKILL = host world/renderer/camera.
- MC owns movement, inventory, mining, block placing, combat, saves.
- ULTRAKILL movement features (dash/slide) exist but default OFF, toggleable.
- Transport is shared-memory state exchange. **No images cross.**
- ULTRAKILL levels voxelized into MC blocks, textures assembled at runtime from the player's own installs.
- Private during development. User publishes themselves.

**Environment verified:**
- Arch Linux, kernel 7.2.4, Wine 11.17, dotnet SDK 10.0.111, Java 25 bundled.
- ULTRAKILL: Unity 2022.3.29f1, **Mono** backend, **x86-64** (PE32+). Project-folder copy used; Steam install untouched.
- Minecraft 26.3, Fabric Loader 0.19.5, mixinextras 0.5.5, native Linux (LWJGL 3.4.3, Mesa GL 4.6, Radeon 860M).
- MC assets present as loose objects at `~/.local/share/FreesmLauncher/assets` (12,464 files, 3.0 GB) — read at runtime, never copied.
- Disk: 66 GB free.

**Milestone 1 — transport feasibility. VERIFIED, not assumed.**
Built a throwaway cross-boundary test: a self-contained win-x64 .NET process (standing in for the
BepInEx plugin) and a native Linux JVM process (standing in for the Fabric mod) each mapped the
same file and exchanged a value.
- Wine → Java: `JAVA saw: 1860134951`
- Java → Wine: `WINE saw: 1159929141`

Both directions coherent across the Wine boundary. This was the single biggest architectural risk
in the project and it is now a proven fact. Transport is a **file-backed mmap**, not a Windows
`Local\` named object (which would not be visible across the boundary).

**Milestone 2 — BepInEx canary. PASSED.**
Installed BepInEx **5.4.23.2 win_x64** into the project ULTRAKILL copy. Result:

    [Message:   BepInEx] BepInEx 5.4.23.2 - ULTRAKILL (2/25/2026 5:23:58 AM)
    [Info   :   BepInEx] Running under Unity v2022.3.29.9261324
    [Info   :   BepInEx] CLR runtime version: 4.0.30319.42000
    [Info   :   BepInEx] System platform: Bits64, Windows
    [Message:   BepInEx] Chainloader startup complete
    [Info   :   BepInEx] 0 plugins to load

**No `WINEDLLOVERRIDES` are required.** BepInEx 5 Mono injects via `winhttp.dll`, which Wine
implements natively, so the game's own imports resolve. Nothing had to be faked. ULTRAKILL's build
date from the binary is 2026-02-25.

Installed into `ULTRAKILL/`: `winhttp.dll`, `doorstop_config.ini`, `.doorstop_version`,
`BepInEx/`. **Rollback:** delete those four. No other file in the folder was modified.

**Correction to an earlier assumption:** ULTRAKILL was assumed 32-bit; it is PE32+ x86-64. BepInEx
x64 is correct. Caught before installing.

---

## 2026-10-08 — Protocol, and three real bugs found before they shipped

**Protocol.** `bridge/protocol/generate.py` is the single source of truth for the wire layout
and emits C#, Java and a markdown reference from it. Total mapped file: **12,552 bytes**
(56-byte header, 4 seqlock slots, 2 ring buffers of 256 events each). Everything little-endian,
`bool` is one byte in both languages.

Three bugs found and fixed, all of which would have caused silent data corruption:

1. **C# `bool` is 4 bytes and non-blittable.** The generator originally emitted `bool` on the
   C# side and 1 byte in Java, which would have shifted every field after the first boolean.
   Both sides now use `byte` on the wire.
2. **The generator padded struct payloads, but `[StructLayout(Pack = 1)]` packs fields
   contiguously.** The generated sizes were 72/32/64/24 where the real C# structs were
   67/29/60/20, so every field after the padding would have been read at the wrong offset.
   Caught by `check-layout.sh`, which compares the actual runtime layout of the compiled C#
   structs against the generated Java constants. Records are now contiguous, and alignment is
   applied only to where a record starts.
3. **Ring buffer constants were nested inside the ring class in Java but top-level in C#.**

**Minecraft 26.3 turns out to be unobfuscated.** Its version manifest carries only client and
server jars with no `client_mappings`, so `loom.officialMojangMappings()` fails. Yarn has no
26.3 build. Fabric's intermediary artifact for 26.3 contains a 25-byte `mappings.tiny` — an
empty mapping set, i.e. an identity mapping. The client jar contains real names
(`net/minecraft/client/player/LocalPlayer.class`). 26.3 also renamed `MinecraftClient` to
`net.minecraft.client.Minecraft` and `tick()` to `runTick(boolean)`. A `mappings` line is still
required or Loom refuses to configure, and the empty intermediary set is what satisfies it.

**Milestone 3 — handshake. PASSED, verified in both directions against the real game.**

Reading from a live JVM while ULTRAKILL ran under Wine:

    path      = .../ultracraft.bridge
    connected = true
    mc pid    = 127014
    uk pid    = 632
    uk hb     = 106892 ms
    uk stale  = false
    magic     = 0x55544C52 (expect 0x55544C52)
    version   = 1
    HANDSHAKE OK

Both heartbeats advancing at once, read straight out of the mapped file:

    ukPid=632 ukHb=209007   |   mcPid=127419 mcHb=2935
    ukPid=632 ukHb=213056   |   mcPid=127419 mcHb=6940
    ukPid=632 ukHb=217070   |   mcPid=127419 mcHb=10945

---

## Four things that cost real time, and why

**1. "0 plugins to load" with no error.** BepInEx 5's `Chainloader.ToPluginInfo` requires the
plugin class to be a subtype of `BepInEx.BaseUnityPlugin`. A class deriving from
`UnityEngine.MonoBehaviour` is rejected **silently**: the subtype check returns null before the
code that warns about a missing attribute, so there is no warning and no error. Only found by
decompiling `BepInEx.dll` with ilspycmd. BaseUnityPlugin derives from MonoBehaviour, so the fix
is a one-word base class change.

**2. Mono refuses the second open of the same file.** The transport opened a `FileStream` and
then called `MemoryMappedFile.CreateFromFile(path, ...)`, which opens the file again. Under
Mono that fails with `IOException: Could not open file`; on .NET Core the same mistake surfaces
as "being used by another process". The mapping keeps its pages alive by itself, so the extra
handle is not needed.

**3. Unity errors were invisible.** `BepInEx/config/BepInEx.cfg` has
`[Logging.Disk] WriteUnityLog = false` by default, so a failing `Awake` produced no trace at
all. Setting it to `true` immediately revealed the real IOException from problem 2.

**4. The plugin GameObject does not survive ULTRAKILL's boot.** BepInEx creates it with
`HideFlags.None`, and ULTRAKILL loads level content right away, which changes scenes. The
plugin was created and destroyed **within frame 0**, running `OnDestroy` and tearing the bridge
down seconds after opening it. `DontDestroyOnLoad` did not help. The fix is a separate host
object created with `HideFlags.HideAndDontSave`, which scene loads do not destroy.

**Tooling note worth remembering:** `pkill -f "ULTRAKILL.exe"` matched its own shell command
line and killed the shell before the following commands ran. That silently wasted several
cycles where a build appeared not to have taken effect. Kill by exact PID from `pgrep -x`.

## Known issue: ULTRAKILL's log stops updating

`BepInEx/LogOutput.log` freezes partway through level preload under Wine and never resumes, so
live diagnostics from within a session are unreliable. Startup messages are captured fine. The
bridge file itself is reliable and can be read directly while both games run.

### Correction: it is not a logging bug, the game hangs

Chasing the frozen log led somewhere more important. A status file written by the plugin from
its own `Update` shows `Time.frameCount` stopped advancing while the process stays alive. The
log "freeze" was a symptom of the game hanging.

### What the hang actually was: ULTRAKILL does not tick while unfocused

**Resolved. It was never a bug.** ULTRAKILL stops doing anything at all when its window loses
focus, so `Time.frameCount` stops advancing, the plugin's `Update` stops running, and
`LogOutput.log` appears to "freeze". The game was healthy the whole time.

This cost a long detour because the stall looks exactly like a deadlock: all ~100 threads sit
in `futex_do_wait` and the main thread is parked in a libc syscall wrapper. It was also
time-based rather than work-based, which ruled out the renderer (hardware GL froze at frame
~13,000, llvmpipe at ~1,200, both at roughly the same wall-clock time).

Hypotheses tested and ruled out along the way:

| Hypothesis | Test | Result |
|---|---|---|
| BepInEx or this plugin | runs without the plugin too | not it |
| Unity's pause-on-lost-focus | set `Application.runInBackground = true` | still stalled |
| GPU driver deadlock | `LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe` | still stalled |
| Deadlock / missing Steam | user confirmed the game runs fine | not it |

`Application.runInBackground = true` was **removed**. It did not fix the stall, and leaving it
in would have modified game behaviour for no benefit. The earlier wrong reasoning was deleted
from the source comment rather than left behind.

**Lesson for testing:** ULTRAKILL must be the focused, visible window while testing, and
automated launches under Wayland/XWayland have no focus, so they will always look stalled. Do
not diagnose an ULTRAKILL freeze without first checking whether the window was actually focused
and visible.

**Why the backtrace never resolved:** `gdb -p` and `eu-stack -p` both fail with
`ptrace: Operation not permitted` (`kernel.yama.ptrace_scope = 1`). Running under gdb works but
Wine and Mono have no debuginfo, so frames above the libc wrapper are garbage. Not pursued
further, since the cause turned out to be mundane. No debug packages were installed.

---

## 2026-10-08 — Playtest failure, and four bugs it exposed

Running the two real games produced "nothing happened". Four separate bugs, found by
reading the actual evidence rather than guessing.

**1. The two sides still opened different files.** The default path was derived from the
Linux home, resolved *inside Wine*. Three mechanisms had been tried and all three are wrong,
because the answer depends on how the prefix was built:

- walking up the working directory looking for a `home` segment — this user's prefix maps
  `s:` to `/home`, so the game's working directory read `S:\<user>\...` with no `home` in it;
- `USERPROFILE` (`C:\users\<user>`) is a real directory inside the prefix, not the Linux home;
- `USERPROFILE\Documents` is a symlink to the Linux home in one prefix and a plain directory
  in the other.

**Fixed by deleting all of it.** `Z:` is the one anchor Wine always provides: it is the
filesystem root. Both sides now use `/var/tmp/ultracraft.bridge` — ULTRAKILL as
`Z:\var\tmp\ultracraft.bridge`. No username, no home, no prefix assumptions.

**2. A dead process reported as connected.** Minecraft logged
`HANDSHAKE OK, ULTRAKILL pid 32` for a process that had been hard-killed twenty minutes
earlier. A killed process leaves its pid and last heartbeat behind, and liveness was decided
by `pid != 0` alone. Both sides now require a fresh heartbeat as well.

**3. The heartbeat clock was meaningless.** Each side sent "milliseconds since *this* process
started", so comparing them compared two unrelated clock origins. It only looked correct when
both games happened to launch at about the same time, and otherwise flagged a **live** peer
as stale — caught because a connected publisher showed `mcStale=True`. Both sides now send
**epoch milliseconds**, a clock they agree on.

**4. A log line that lied.** `PlayerPublisher` used `{:.3f}`. SLF4J does not understand format
specifiers and printed them literally, which also desynchronised the placeholder count from
the argument count — so `onGround=-0.0363235` was printing a *velocity*, and `hp=-0.0784`
another one. Numeric formatting now goes through `String.format`; SLF4J only sees `{}`.

Also fixed: an all-zero PLAYER slot is indistinguishable from "no data", since a player can
stand at the origin. The ULTRAKILL side now ignores the slot until `tickTimestamp` is non-zero,
so "no data" and "player at 0,0,0" cannot be confused. And `handshake` in the status file now
reports live state rather than history.

Verified afterwards, live:

    mcPid=147657  mcStale=False  handshake=True
    mcPos=10,70,-301   mcPosExact=10.500,70.000,-300.250

and after the peer left cleanly:

    mcPid=0  mcStale=True  handshake=False

**The Minecraft side was working perfectly throughout** — it published real positions from a
real world. Every failure was on the ULTRAKILL side or in the shared transport.

---

## 2026-10-08 — Focus fix: both games now survive losing focus

The playtest worked but data stopped as soon as you switched to Minecraft. That turned out
to be both games pausing, which was never a bridge problem.

**Minecraft** had `pauseOnLostFocus:true` in `options.txt`. Set to false. This is a vanilla
option (Options → Video), not a hidden behaviour change.

**ULTRAKILL** pauses through Unity, and getting the fix to stick took three attempts:

1. `Application.runInBackground = true` in `Awake` — did not work. BepInEx runs `Awake`
   during its own startup, before Unity applies Player Settings, and Unity overwrites the
   value afterwards.
2. Moved it into a separate plugin's `Start`, re-asserting for 60 frames — the plugin loaded
   but `Start` never ran, because **the BepInEx plugin GameObject is destroyed in frame 0**.
   That was already known from the BridgeHost work; the mistake was not applying it.
3. Applied it from `BridgeHost.Update`, which is on a persistent `HideAndDontSave` host and
   is the only loop proven to survive. Confirmed in the log:

       runInBackground settled at frame 120: True

Verified unfocused, which is what every previous test was not:

    t=15s   frame=1800        t=90s   frame=10800
    t=45s   frame=5400        t=120s  frame=14400
    t=75s   frame=9000        t=135s  frame=16200

and the data still flows with ULTRAKILL in the background:

    mcStale=False  handshake=True
    mcPos=56,71,-124   mcPosExact=56.500,71.000,-123.250

The dead-code `FocusGuard.cs` from attempt 2 was deleted rather than left behind.

---

## 2026-10-08 — Stage 1: puppet, and input forwarding

**Puppet (Minecraft drives ULTRAKILL's player).** `NewMovement`'s `Update`/`FixedUpdate`
are neutralised with Harmony and its rigidbody made kinematic, then moved to Minecraft's
position each frame. The player ref stays in the world so ULTRAKILL's enemy targeting keeps
working. Verified headlessly with a moving publisher: the UK player travelled from x=26 to
x=110 units following MC data, with `desired == actual`.

**Scale measured, not guessed.** `1.94445 ULTRAKILL units per block` (originally mislabelled "blocks per unit"), from ULTRAKILL's real 3.5-unit
capsule against Minecraft's 1.8-block player. Same derivation SkyCraft uses for its 70.

Four bugs found here:

1. **Absolute coordinates are unusable.** MC y=70 mapped to UK y=136 while ULTRAKILL's
   levels sit near y=-76. The origin was a placeholder, not a plan. Now the first position
   seen is treated as "here" and only deltas apply.
2. **The player object does not survive a level load.** The cached rigidbody read back
   `(0,0,0)` — Unity destroys and recreates the player, leaving a dead reference. Now
   detected and re-acquired.
3. **Kinematic `MovePosition` applies on the NEXT physics step**, so comparing
   `_rb.position` against the position requested this frame reported a collision on every
   frame of ordinary movement, which would have yanked Minecraft around constantly. Now
   compares against the previous request, which is why `desired == actual`.
4. **Yaw wrapping.** Minecraft reports yaw in [-180,180]; a raw delta from the anchor
   would spin the camera 358 degrees on the seam. Now uses shortest-angle.

Also: the puppet logged ~4,700 lines because a fresh instance per retry reset the throttle
counter. One long-lived instance retries internally instead.

**Input forwarding (ULTRAKILL's keyboard/mouse into Minecraft).** Minecraft 26.3 was
checked with `javap -c` rather than assumed, and three details would each have broken it
silently:

- `KeyboardHandler.keyPress(long, int, KeyEvent)` starts with
  `if (window != 0 && window != getWindow().handle()) return;` — a wrong window argument
  means **every event is silently dropped**. `MouseHandler.onMove` has the identical guard.
- `KeyMapping.matches(KeyEvent)` compares only `KeyEvent.key()`, never the scancode, so a
  scancode of `0` is safe.
- There is no press/release flag in the 3-arg `keyPress`, so replayed events cannot reliably
  release a key and held keys would stick. Held keys are therefore set directly on
  `KeyMapping.setDown`, which is unambiguous. Discrete presses are a separate mechanism,
  not built yet.

26.3 also has **no `keySneak`**; `keyShift` is the sneak binding.

**Two mixins that must ship together.** `WindowMixin` makes `Window.isFocused()` return
true so Minecraft does not pause in the background. On its own that makes Minecraft **grab
the OS cursor** and steal the pointer from ULTRAKILL, so `MouseHandlerMixin` cancels
`grabMouse()`. Mouse look survives because deltas are forwarded to `onMove` directly.
Either half alone is worse than neither.

Suppressing the grab unconditionally would have broken mouse-look when playing Minecraft
directly, so ULTRAKILL publishes `FLAG_UK_FOCUSED` each sample and the grab is only
suppressed while ULTRAKILL really has focus. Verified: `flags=4`.

### Playtest finding: ULTRAKILL's camera was fighting Minecraft's

Input forwarding appeared to do nothing, and vertical mouse look "worked". Investigating
`CameraController` showed why:

```csharp
// CameraController.LateUpdate()
Vector2 vector3 = MonoSingleton<InputManager>.Instance.InputSource.Look.ReadValue<Vector2>();
rotationX += vector3.y * (opm.mouseSensitivity / 10f) * num;
rotationY += vector3.x * (opm.mouseSensitivity / 10f) * num;
```

ULTRAKILL accumulates its own real mouse into `rotationX`/`rotationY` and calls
`ApplyRotations()` itself. Because `LateUpdate` runs after every `Update`, our values set
during Update were overwritten on top by ULTRAKILL's accumulation, **every frame, both
axes**. The vertical axis only looked correct because that motion was ULTRAKILL's own
camera reacting to the real mouse, not Minecraft at all.

**Fixed without patching ULTRAKILL:** the host is marked
`[DefaultExecutionOrder(10000)]` and camera application moved into its `LateUpdate`, so it
runs after `CameraController.LateUpdate` and overwrites the result. ULTRAKILL's camera
logic still runs; we simply get the last word.

Confirmed from the bytecode that Minecraft is **not** pausing:

```java
// Minecraft.pauseIfInactive()
if (window.isFocused() || !options.pauseOnLostFocus) { lastActiveTime = now; return; }
if (now - lastActiveTime > 500) pauseGame(false);
```

so the `Window.isFocused()` override is doing its job.

**"Stuck trying to go back to the centre" is the signature of a cursor grab that was not
suppressed.** Rather than guess again, the Minecraft side now instruments the whole chain
and reports three facts that cannot be inferred from one another:

1. whether forwarded keys arrive at all (`keys=0x...` on the first sample),
2. whether `setDown` survives Minecraft's own input handling (`keyUp.isDown()` afterwards),
3. whether the player actually moves.

Cursor-grab attempts and suppressions are counted too. One playtest should now identify
which link is broken instead of producing another round of "it changed but I still can't
tell why".

### Root causes, found by reading the logs instead of guessing

Three prompts had produced "nothing changed", so both logs were read directly. Three real
bugs, one catastrophic.

**1. The collision-correction loop was corrupting Minecraft (catastrophic).** It computed
the Minecraft position to send back as `uk/scale + mcOrigin`, but the forward mapping is
`uk = ukAnchor + (mc - mcOrigin) * scale`, so the inverse needs the `ukAnchor` term too.
Without it every "correction" teleported the player somewhere meaningless. The log showed
it happening tens of times a second:

    correction #1 -> player now 61.128574,-0.015368992,0.0
    correction #2 -> player now 40.82857,28.567965,-49.97
    correction #8 -> player now 61.128574,-28.517725,21.38

and Minecraft's yaw being violently slammed around as a consequence:

    yaw/pitch 147 -> 214 -> 227 -> -143 -> -140 -> -151 -> -186 -> -196 -> +164

That is what "moves slowly diagonally" was, and why the camera looked chaotic: ULTRAKILL's
camera was faithfully mirroring a Minecraft yaw that was garbage. **The correction loop is
now removed entirely.** A push-back approximation cannot express ULTRAKILL's geometry to
Minecraft anyway; SkyCraft injects the host's collision *shapes* into Minecraft's queries so
Minecraft's own physics resolves against them. Until that exists, no correction is far safer
than a wrong one.

**2. Minecraft wipes every key on every mouse grab.** From the bytecode:

    // MouseHandler.grabMouse()
    if (!minecraft.isWindowActive()) return;
    if (mouseGrabbed) return;
    if (InputQuirks.RESTORE_KEY_STATE_AFTER_MOUSE_GRAB) KeyMapping.setAll();   // <-- here

Minecraft grabs the mouse repeatedly while in game, and each grab clears **all** key
mappings. That is exactly the observed `setDown()` then `isDown()==false`. Suppressing
`grabMouse` while ULTRAKILL has focus stops the `setAll()` call as well as the grab.

**3. The cursor trap.** Minecraft grabbed the cursor while the player was briefly in
Minecraft, and never gave it back. The log proved the suppression logic was right but
incomplete: `cursor grab: attempt #1 suppressed=false (ULTRAKILL focused=false)`.
Minecraft now actively calls `releaseMouse()` on the rising edge of ULTRAKILL's focus flag,
so the pointer comes back.

Also noted for later: Minecraft reported `keys=0x1a1` while ULTRAKILL's own log showed only
`0x0` and `0x10`, and `0x1a1` contains bits 7 and 8 which are not defined in the protocol.
Struct sizes agree (31 bytes both sides) and the layout check passes, so this is unexplained
and worth watching in the next run now that the corruption is gone.

---

## 2026-10-08 — Milestone 4: a real value crosses

The point of this stage is that the architecture stops being a theory. Minecraft publishes its
actual player state every tick, and ULTRAKILL reads it back.

**Minecraft side** (`PlayerPublisher`): samples `LocalPlayer` once per tick and writes the
PLAYER and CAMERA slots. 26.3 API details, all confirmed against the client jar with `javap`
rather than assumed:

- `Minecraft.player` is the local player.
- `Entity.getX()/getY()/getZ()` give block coordinates.
- `Entity.yRot`/`xRot` are **private**, so yaw and pitch come from
  `getYRot(partialTick)`/`getXRot(partialTick)`. `1.0f` is used so the value is fully
  interpolated, per the contract's rule about never sending the raw 20-tick position.
- `Entity.getDeltaMovement()` returns a `Vec3` of per-tick velocity, with public `x`/`y`/`z`.
- `Options.fieldOfView` no longer exists; FOV now lives behind `Options.fov()`, an
  `OptionInstance<Integer>`. FOV is diagnostics-only, so it falls back to 70.

**ULTRAKILL side** (`BridgeHost.ReadPlayerSlot`): reads the PLAYER slot under its seqlock and
writes what it sees to the status file. A failed seqlock read is expected while Minecraft is
mid-write and is not an error, so the previous value simply stays current.

Verified with a JVM harness driving the real `Bridge` class, publishing
`100.5, 64.0, -200.25` with velocity `0.125, 0, -0.25`, yaw 90, pitch 12.5:

    mcPos=101,64,-201
    mcPosExact=101.500,64.000,-200.250
    mcVel=0.125,0.000,-0.250
    mcYaw=90.00
    mcPitch=12.50
    mcOnGround=True
    mcHealth=20
    handshake=True

(`101` rather than `100` is deliberate: the harness jitters x by 1 to make the ULTRAKILL side's
change detection fire, and it read both values correctly.)

**This is the whole trick.** Both processes map the same file, agree on byte layout, exchange
process ids and heartbeats, and now carry real game state. Everything remaining is features on
top of a working bridge.

Two gaps to close when they matter: air supply has no accessible accessor yet and is sent as a
constant 300, and nothing consumes the data on the ULTRAKILL side except the status file.

---

## 2026-10-08 — Bridge path fix, and how the Wine default was actually found

**The bug that caused playtest B.** Both processes fell back to the *relative* name
`ultracraft.bridge`, and each resolved it against its own working directory:

| Process | File it opened | Contained |
|---|---|---|
| ULTRAKILL | `ULTRAKILL/ultracraft.bridge` | `ukPid=864`, `mcPid=0` |
| Minecraft | `.../Instances/UltraCraft/minecraft/ultracraft.bridge` | `mcPid=128986`, `ukPid=0` |

Both were alive and healthy; they simply never touched the same bytes. That is why a relative
default must never be used for a shared file.

**Fixed:** both sides now default to an absolute path under the user's home,
`~/.ultracraft/ultracraft.bridge`. `ULTRACRAFT_BRIDGE` remains as an override but is no longer
needed. Verified with zero configuration:

    [Info   :UltraCraft] bridge open at Z:\home\<user>\.ultracraft\ultracraft.bridge (pid 640, ...)

    mc pid    = 134037
    uk pid    = 640
    uk hb     = 13104 ms
    uk stale  = false
    magic     = 0x55544C52 (expect 0x55544C52)
    version   = 1
    HANDSHAKE OK

**Finding the Linux home from inside Wine took three attempts, and the two obvious ideas are
both wrong.** Measured, not assumed:

1. `HOME` is **null**. Wine replaces the Unix `HOME` with `USERPROFILE`, so
   `Environment.GetEnvironmentVariable("HOME")` returns nothing.
2. `USERPROFILE` (`C:\users\<user>`) is a **real directory on the prefix's tmpfs**, not the Linux
   home, so paths under it do not coincide with what a native Linux process sees. `readlink -f`
   on it resolves to itself, and the filesystem differs (`tmpfs` vs `ext4`).
3. What works: Wine maps `Z:` to the Linux root, and the game runs from somewhere under the
   Linux home. So the Linux home is found by walking up the working directory to the segment
   following `home`. The fallback is `USERPROFILE\Documents`, which Wine *does* symlink to the
   Linux home (`Documents -> ~/Documents`).

**Unity's logger is the wrong logger here.** Messages written with `UnityEngine.Debug.Log` only
reach `LogOutput.log` when `[Logging.Disk] WriteUnityLog = true`, which ships false. The plugin
now uses BepInEx's own log source, and `WriteUnityLog` was enabled in the project copy.

**Added `~/.ultracraft/ultrakill-status.txt`**, rewritten by the plugin from `Update`. BepInEx's
disk logger stops flushing under Wine, so this is the only dependable way to see the ULTRAKILL
side's view of the bridge. It is also what revealed the main-loop hang above.

## Still open

- **ULTRAKILL hangs under bare Wine** shortly after level preload. Blocks everything downstream.
- The Minecraft instance in the project folder is a stale duplicate of
  `~/Games/Minecraft/Instances/UltraCraft`. The plan was to symlink the Games path to the
  project copy; not done, because Minecraft was running.---

## 2026-10-08 — The input bug: a constant that was not noise

Reported as "nothing changed, still moving for no reason, can't control camera or move".
Three rounds had produced no movement on the input path, and the previous round's
diagnosis (a broken collision-correction inverse) turned out to be a separate, real bug
fixed in the same session, but not the cause of this.

### The value was a constant

Minecraft consistently reported `keys=0x1a1`. ULTRAKILL's own log showed only `0x0`,
`0x4`, `0x8`, `0xC`, `0xD`, `0x1C` and similar. Two runs fifteen minutes apart produced a
byte-for-byte identical `0x1a1`.

That identity is the whole clue, and it was available from the first report. A value that
never changes is a constant, not corruption; corruption varies. It was treated as noise
for two rounds because "the controls don't work" primes you to expect garbage.

`0x1a1` is the **high 32 bits of the millisecond epoch timestamp**:

    tickTimestamp = 1791477141345
      high32 = 0x000001a1     <- exactly what Minecraft read
      low32  = 0x1c5bcf61

The high word of a millisecond epoch changes only once every ~49.7 days, which is why it
was identical across runs.

### Cause: the seqlock `seq` field was written twice

`Bridge.WriteSlot` took a `payloadOffset` intended to skip the struct's leading `seq`, but
used it only as the **destination** offset. The **source** offset was hardcoded `0`:

    _view.WriteArray(offset + payloadOffset, payload, 0, payloadLen);

So `struct.seq` was written at `offset+4` instead of being skipped, and every field after
`seq` landed four bytes late:

    152..155  seqlock (BeginWrite/EndWrite)      correct
    156..159  struct.seq                          duplicate
    160..167  tickTimestamp
    168..171  keys                                should be 164

Minecraft read `keys` at slot+12 = 164, which is the high word of `tickTimestamp`.

Verified by simulating the old and new write paths against a reader that decodes at the
generated offsets. Old: `keys = 0x000001a1`, matching the log exactly. New: `keys = 0x1c`,
matching what ULTRAKILL actually sent.

### Why nothing caught it

- The generated constants on both sides agreed perfectly. **They were never wrong.**
- `check-layout.sh` compares those constants. It cannot see where a writer places a
  struct, so it had nothing to compare against. This is a structural limit of the check,
  not a gap in its implementation.
- `Marshal.SizeOf(SlotInput)` was never compared against the generated `SLOTINPUT_SIZE`, so
  even the cheaper struct-level check was not being done.
- `endRead()` validates the seqlock at the slot base, which was correct, while the
  duplicated `seq` four bytes later was never validated. Torn reads were undetectable.

The write also overran: `struct.seqEnd` landed at 183..186 and `OFFSET_CORRECTION` is 184,
so three bytes of the INPUT write clobbered the next slot.

### Fix

`WriteSlot(offset, payload, structSize)` now uses the same `SeqlockBytes` constant for both
the destination and the source offset, writing `structSize - 8` bytes from `payload[4]`.
The struct's `seq` and `seqEnd` are skipped entirely, leaving the seqlock owned solely by
`BeginWrite`/`EndWrite`, which is what the generated layout describes.

### Guards added, because the existing check could not see this class of bug

- `SelfTest.cs`, run once at handshake on the ULTRAKILL side:
  1. Compares `Marshal.OffsetOf` for every `SlotInput` field, and `Marshal.SizeOf`,
     against the offsets the generator emitted for Java. The Java-side numbers are copied
     in deliberately, so a struct change that does not update the copy fails loudly
     instead of silently agreeing with itself.
  2. Writes a probe through the **real** `WriteSlot` path and reads the mapping back at
     the offsets a reader will use. This is the check the layout script structurally
     cannot perform: it exercises the writer rather than trusting that it lines up.
- ULTRAKILL logs the raw INPUT slot bytes once, next to the values it wrote and the
  offsets a reader expects.
- Minecraft logs the same slot from its end, next to the values it decoded out of them.
  The two logs show the same bytes from both sides, so any disagreement is visible in one
  playtest.

Verified in a live run, decoding the logged probe byte-for-byte:

    seqlock  @ +0  : c0170300 even, valid
    timestamp @ +4 : 0x1122334455667788   expected 0x1122334455667788
    keys      @ +12: 0xa5a5a5a5           expected 0xa5a5a5a5
    mouseDx   @ +16: 1234.5               expected 1234.5
    mouseDy   @ +20: -6789.25             expected -6789.25
    a/u/inv   @ +24: [1, 1, 1]            expected [1, 1, 1]

### How each reported symptom follows from it

- **"Moving for no reason"** — `0x1a1` has bit 0 (`KEY_FORWARD`) and bit 5 (`KEY_SNEAK`)
  set. Minecraft was walking forward and crouching continuously. This was a stuck W, not
  phantom drift.
- **"Can't control camera"** — `mouseDx` was read from 168, which held the `keys` word.
  Key bits reinterpreted as a float are denormals around `2e-51`, so the mouse delta was
  always effectively zero.
- **"Can't move"** — real keys were never read; `keys` was the timestamp's high word
  regardless of what was pressed.
- **"Input only arrives once"** — see the torn-read note above.

### Also changed

- **Cursor grab is now suppressed whenever the bridge is connected**, not while ULTRAKILL
  reports OS focus. `Application.isFocused` was false on almost every tick under Wine
  (flags 0, with spikes to 4), so the grab went almost unsuppressed, and `grabMouse()`
  calls `KeyMapping.setAll()`, which clears **every** key mapping. Bridging is also the
  honest condition: ULTRAKILL is the surface the player drives.
- **Cursor release now fires on the rising edge of bridge connection as well as of
  ULTRAKILL's focus flag**, because suppressing the grab cannot return a cursor Minecraft
  already took.
- ULTRAKILL's input log now fires on mouse movement as well as key changes. Mouse-only
  turns used to be silent, so a dead mouse chain was indistinguishable from a working one.
- The dead collision-correction reader is gone from the Minecraft side. The slot stays
  reserved in the protocol, since collision injection will need it with a different
  meaning.

### Still outstanding

- The puppet still anchors to a fixed point (`ukDesired=(0.000,104.197,-47.000)` while
  `mc=(-14.70,11.79,85.30)`) because level voxelization has not been built. Stage 2.
- `mcYaw=517.65` shows Minecraft's yaw accumulating past 360 with the handedness flip
  applied as a plain negation. Needs a normalised-angle audit before camera work.
- Collision injection, which is how the host's geometry reaches Minecraft's physics, is
  the real fix for the player walking through ULTRAKILL's walls. Later milestone.---

## 2026-10-08 — Milestones A and B: click actions, and making the camera observable

A and B, as scoped after the input fix landed. Reported symptom going in: movement works,
but left and right click do nothing in Minecraft, while ULTRAKILL's own gun fires fine.

That detail mattered and cut the problem in half. ULTRAKILL's gun working proves Unity's
mouse button state *is* readable inside ULTRAKILL, so the signal exists and the forwarder
should be receiving it. The first reading of this round was wrong: I initially took the
report to mean ULTRAKILL's buttons were dead and started chasing focus. They were not.

### A: two independent reasons click did nothing

Both confirmed from the 26.3 bytecode, not inferred.

**1. Sustained mining is gated on the cursor being grabbed.** In
`Minecraft.handleKeybinds`:

```
700: getfield      keyAttack
703: invokevirtual KeyMapping.isDown()
706: ifeq          723          // attack not held -> false
709: getfield      mouseHandler
713: invokevirtual MouseHandler.isMouseGrabbed()
716: ifeq          723          // CURSOR NOT GRABBED -> false
719: iconst_1
724: invokevirtual continueAttack(Z)V
```

Mining is the AND of "attack is held" and "the OS cursor is grabbed". Suppressing the grab
is deliberate and necessary, because a grabbed cursor in Minecraft's background window hides
the pointer and steals it from ULTRAKILL. So `continueAttack(false)` ran every tick, which
Minecraft reads as *the button was released*, and mining could never start or continue.

Movement was unaffected because it runs in the player tick rather than in
`handleKeybinds`. That is the entire reason walking worked while clicking did not, and it is
why "movement is fine" was never evidence that the input path was healthy.

Fixed with a `@Redirect` on that single `isMouseGrabbed()` call, scoped to `handleKeybinds`,
returning `true` while the bridge is connected. It is the only such call in the whole class,
so nothing else in Minecraft's input handling is affected, and with the bridge down the
original value is returned unchanged. The `keyAttack.isDown()` half of the gate is left
alone, so mining still only happens while the button is genuinely held.

**2. Discrete actions need a click counter that `setDown()` never increments.**
`KeyMapping.consumeClick()` reads and clears a private `clickCount`, and the only thing that
increments it is `KeyMapping.click()`. `setDown()` does not, so `startAttack()` — the press
that begins mining — never fired either, and neither did anything else gated on a click.

Rather than poke at a private counter, presses are now replayed through Minecraft's own entry
point, `MouseHandler.onButton(0L, new MouseButtonInfo(button, 0), action)`. Verified: it
takes the same "window == 0 means no particular window" convention as `onMove`, and it calls
`KeyMapping.click()`, which increments the counter for every mapping bound to that physical
button. So it respects the player's own keybinds instead of assuming left click is attack.
Held state is still set with `setDown`, because `keyAttack.isDown()` has to stay true across
ticks and `startUseItem` is reached through a `keyUse.isDown()` branch.

Also: the `inventory` byte was being decoded off the bridge and then never used. It is now
applied for exactly one sample and released on the next.

### Ruled out along the way, worth recording

- **Block targeting does not need a grabbed cursor.** `Minecraft.pick(float)` is called
  unconditionally from `renderFrame`, with no `isMouseGrabbed()` test.
- **Targeting follows the player, not the pointer.** `LocalPlayer.raycastHitResult` uses
  `Entity.getViewVector` and `Entity.pick`, so aiming tracks the yaw and pitch driven from
  forwarded mouse. That removed what looked like a serious problem: crosshair accuracy
  cannot silently depend on a cursor we refuse to let Minecraft have.
- **`isMouseGrabbed()` appears exactly once in `Minecraft`**, which is what makes a narrow
  redirect possible instead of a global patch.

### Diagnostics added

"Click does nothing" had several causes that all looked identical from outside. Now reported
per click, on change rather than once, because a click is a one-frame event and a single
early sample would miss it every time:

- whether the button arrives from ULTRAKILL at all (ULTRAKILL now logs `buttons L= R=` on
  change; previously the log never mentioned the buttons at all)
- whether `keyAttack.isDown()` / `keyUse.isDown()` survive being set
- the click counter itself, read reflectively without consuming it, since calling
  `consumeClick()` to inspect it would destroy the thing being measured
- `hitResult` type, i.e. whether there is even a block to act on
- the open screen, read via `gui.screen()`; there is no `screen` field on `Minecraft` in 26.3

### B: the camera was not broken, but was unobservable, and had a real anchoring bug

`puppetYaw=0.00` next to `mcYaw=-177.00` had been flagged as a possible camera failure. It
was not: the two were equal because the player had not turned since anchoring. A single static
reading cannot distinguish "the camera follows Minecraft" from "the camera is stuck", and I
should not have read one as evidence.

Fixed and made observable:

- **Anchoring read uninitialised camera values.** The anchor records where ULTRAKILL's camera
  points, read during our `Update`, but `CameraController.LateUpdate` is what maintains
  `rotationX`/`rotationY` and runs later in the frame. On the first frame after a level load
  those fields still hold whatever the freshly created object was constructed with. Both
  observed anchors recorded `rotationY` as exactly `0.00`, from two different Minecraft
  yaws, which is what identified it: that is the uninitialised value, not a coincidence.
  Anchoring on it would bake an offset into the view for the whole session, because every
  later frame is a delta from the anchor. Anchoring now waits one frame. Verified in the log
  on both the initial attach and a level reload.
  Ordering note: the "camera has run" flag is set *before* the anchored check on purpose.
  Anchoring waits for that flag and anchoring is what sets `_anchored`, so testing
  `_anchored` first deadlocks.
- **A wrong comment about Minecraft's yaw.** It does not report yaw wrapped to [-180,180];
  `getYRot()` accumulates unbounded, and 517.65 was seen in one session and -177.00 in the
  next. `Mathf.DeltaAngle` is still correct and still needed, but because it folds an
  unbounded difference into (-180,180] first, not because the input is wrapped. The comment
  now says so.
- Camera state added to the status file: `camMcYaw`, `camMcPitch`, `camDeltaYaw`,
  `camDeltaPitch`, plus a `puppet camera:` log line that fires only while the camera is
  actually moving, so turning the mouse visibly proves the chain end to end.

### Not yet verified

The new `@Redirect` mixin has not been exercised, because Minecraft was not running during
this round and it cannot be launched from here. The target was confirmed in the bytecode and
the mixin config uses `defaultRequire: 1`, so a failure to match would abort Minecraft at
startup rather than fail silently — a loud failure, but still an unproven one.---

## 2026-10-08 — Mouse, and the mirrored Z axis

Reported as "nothing changed from last prompt", plus one new symptom: forward and backward
are backwards in ULTRAKILL.

That second report was the more valuable of the two, and it was the only part that turned
out to be a plain bug rather than a missing mechanism. Everything else this round was a case
of a mechanism existing but not being reached, and the previous round's fixes were correct
but insufficient.

### The camera: three separate gates, all in the way

**The hard evidence came first.** ULTRAKILL's own log showed that Minecraft's view never
moved at all during an entire session:

    distinct mcYaw values   : -177.00     (one value, whole session)
    distinct mcPitch values : 74.10       (one value, whole session)
    "puppet camera:" lines  : 0           (the new diagnostic never fired)

ULTRAKILL was forwarding hundreds of non-zero mouse samples during that time, dominated by
1 to 3 pixel steps. So this was never a sensitivity, deadzone or clamping problem. The
rotation was not arriving in the first place, which is a very different bug and pointed
straight at the delivery mechanism.

**1. `MouseHandler.onMove` does not accumulate a delta when the cursor is not grabbed.**
This was the big one, and the previous round's assumption about it was wrong. The bytecode:

```
 53: ifeq  119                     // !isWindowActive -> skip
 57: getfield mouseGrabbed
 60: ifeq  88                      // NOT grabbed -> different branch
 65: accumulatedDX += dload 7      // grabbed: accumulate the 4th arg (delta)
 85: goto  119
 88: accumulatedDX += dload_3      // not grabbed: use the 1st arg
 95: getfield xpos                 // MINUS the stored previous value
120: xpos = dload_3                // and store the argument as a POSITION
```

In the branch we are permanently in, because suppressing the grab is deliberate,
`onMove` treats its argument as an **absolute cursor position** and differences it against
the previous one. We were passing **deltas**, so every delta got differenced a second time:

    send d= 3   xpos-> 3    accumulatedDX:  0 ->  3   (wanted 3)
    send d=-2   xpos->-2    accumulatedDX:  3 -> -2   (wanted -2)
    send d= 5   xpos-> 5    accumulatedDX: -2 ->  5   (wanted 5)
    send d=-1   xpos->-1    accumulatedDX:  5 -> -1   (wanted -1)

Forwarded total 9, accumulated 4, and for smooth 1-pixel motion the cancellation is very
nearly complete. That is precisely why yaw sat at -177.00 for a whole session.

The previous round's comment on `forwardMouse` asserted that `onMove` "accumulates that
delta and turns the player from it". It does not, in this branch. The claim was checked
against the wrong part of the method.

**2. There is no public way to turn the player without a grabbed cursor.** `turnPlayer` is
private, and `handleAccumulatedMovement` is the only thing that reaches it. The camera turn
is written directly into `MouseHandler.accumulatedDX` / `accumulatedDY`, the exact fields
`handleAccumulatedMovement` drains and passes to `turnPlayer`, using reflection.

Writing Minecraft's own fields is the point, not a workaround: its turn path, smoothing,
sensitivity option and pitch clamp all stay in charge, so look speed and feel remain
vanilla and there is no second implementation of "turn a player" to drift out of step.
`handleAccumulatedMovement` is not gated on the grab, only on `isWindowActive()`, which
`WindowMixin` already forces true. It is called from `runTick`, the same place the previous
round's `isMouseGrabbed` redirect was needed.

Values are read-modify-written, not assigned: accumulators drain once per tick while
forwarded samples arrive more often, so assigning would discard turns.

`onMove` is still called, now with a running total so that its position-differencing branch
behaves and Minecraft's idea of the pointer stays roughly sane for GUI hit-testing.

**3. Confirmed not a problem, so it stops being a suspect.** `LocalPlayer.raycastHitResult`
uses `Entity.getViewVector` and `Entity.pick`, so block targeting follows the player's look
direction rather than the pointer. Crosshair accuracy cannot silently depend on a cursor we
refuse to let Minecraft have.

### Forward/backward: a mirrored Z axis

MEASURED from the session rather than reasoned about, by differencing paired
`mc=(x,y,z)` and `ukDesired=(x,y,z)` samples:

    f12721  mcDelta=(-0.01,+0.00,+0.16)  ukDelta=(-0.016,+0.000,+0.312)
    f12781  mcDelta=(+0.16,+0.00,+0.69)  ukDelta=(+0.318,+0.000,+1.344)
    f12901  mcDelta=(-0.32,+0.00,+0.79)  ukDelta=(-0.608,+0.000,+1.550)
    f12961  mcDelta=(+0.18,+0.00,-0.60)  ukDelta=(+0.337,+0.000,-1.179)

X and Y carry through with the same sign and the correct 1.94445 scale. Z carries through
**inverted**. A lone Z inversion is exactly what "forward and backward are backwards, left
and right are fine" describes, and it rules out X or Y being wrong.

The mapping now negates Z, via a named `MirrorZAxis` constant carrying the measurement and
the reasoning, so that if it is ever wrong again there is one obvious place to change it.
It has to agree with `UkToMcYaw`'s negation: the two engines are both Y-up but disagree
about which way Z points relative to the direction you are facing, and the position mapping
and the yaw mapping cannot be chosen independently.

### Logging

ULTRAKILL's input log was drowning itself. One session produced 181 lines of `mouse 0,0`
and buried the rest. Mouse motion is now only logged when it moves at least 4 pixels, and
the number of suppressed samples is carried into the next real line so that a session which
sent nothing at all is still visible rather than silent.

### Method note

Three of the five findings this round came from reading the bytecode of a method that was
already being called successfully, where the previous note described behaviour the bytecode
did not support. The one-line fix that mattered most was found by asking "Minecraft's yaw is
a single value all session, and hundreds of mouse samples were sent, so where did they go?"
That question is answerable from the logs alone and should have been asked before writing a
single line of the previous round's mouse code.---

## 2026-10-08 — The camera gate was in a different method, and the diagnostic was lying

Reported: camera still dead (C), movement now correct. The Z fix held, which was the one
part of the previous round that was a straightforward bug.

### The camera gate

The previous round wrote forwarded mouse movement into `MouseHandler.accumulatedDX` and
`accumulatedDY`, which is the correct place: those are the exact fields
`handleAccumulatedMovement` drains and passes to `turnPlayer`. It still did nothing.

`handleAccumulatedMovement` ends like this:

```
 287: aload_0
 288: invokevirtual MouseHandler.isMouseGrabbed()
 291: ifeq  309                  // NOT GRABBED -> skip the turn
 294: getfield  minecraft.player
 301: ifnull 309
 305: invokevirtual turnPlayer(D)V
 309: accumulatedDX = 0.0        // then discarded unconditionally
 315: accumulatedDY = 0.0
```

So the accumulated movement is not merely unused while ungrabbed, it is read and then thrown
away. No amount of correctly delivered movement can turn the camera while that gate is shut,
and the gate is shut by design, because suppressing the grab is the whole point.

The previous round had already opened an identical gate in
`Minecraft.handleKeybinds`, for mining, via a `@Redirect` on `isMouseGrabbed()`. That
redirect is correct and is still in place. But it patches **that one call site**, and the
camera's gate is a **second, separate call site in a different class**. One redirect cannot
reach both.

This is the same mistake twice in a row, which is worth naming precisely: I found a gate
that was closed for a good reason, opened the one I was looking at, and treated that as
having opened "the gate". There were two of them. The symptom it produced is the worst kind,
because the fix verifiably applied, logged cleanly, opened *a* gate, and changed nothing.

A `@Redirect` is per call site by construction. Two gates means two redirects, and the way to
avoid a third miss is to grep for the predicate rather than reasoning about which method seems
like "the" input path.

### The diagnostic was also wrong, and had been for two rounds

```
[21:43:11] input: button LMB=true RMB=false | keyAttack.isDown()=false keyUse.isDown()=false
```

`keyAttack.isDown()` was read **before** the `set()` calls that make it true. The reporting
call sat near the top of `apply()`, the state it reports is set further down, so it always
described the previous tick. Every key looked permanently dead in the logs while arriving
perfectly.

That is a specific kind of mistake worth recording: an instrument that cannot be wrong in the
direction you expect is worse than no instrument, because it turns "keys are broken" into a
belief. The logs were consistent and confident and false. Split into `reportFirstSample`
(before, raw bytes only) and `reportApplied` (after, actual state), which is the ordering the
question actually requires.

### Mining: not yet proven either way

`continueAttack` in `handleKeybinds` is now reachable, and clicks are replayed through
`MouseHandler.onButton`, which reaches `KeyMapping.set` and `KeyMapping.click`. Both are in
place. But the only evidence that mining was broken was the line above, which was reporting
stale state. So mining is currently **unproven in both directions** rather than known-broken,
and the corrected diagnostic is what will settle it.

Worth recording that `KeyMapping.setAll()`, called from `grabMouse`, does not clear keys. It
re-polls them: `setDown(InputConstants.isKeyDown(key.getValue()))`. So the "Minecraft wipes
every key on grab" note from two rounds ago was also wrong in its mechanism, even though
suppressing the grab was still correct for keeping the pointer out of Minecraft's window.---

## 2026-10-08 — The Z mirror was one heading's worth of luck

Reported: camera now works on the Minecraft side, and "because the mouse moved, the
movement directions also got messed up". Both are consistent, and the second is the
consequence of the first.

### What actually happened to the camera

Minecraft captures mouse *movement* but not the cursor, which is exactly the intended
arrangement. The forward vector is arriving: Minecraft's yaw now ranges over 55 distinct
values from -177 to +110 across a session, where before it sat at a single unchanging value.
The player's own description of the fix is more accurate than my framing of it.

### Why movement broke, and why my previous fix was wrong

The previous round set a constant that negates Z. It was derived from a session in which
Minecraft's yaw sat at -177.00, and at that heading it is correct:

    Minecraft forward at yaw -177 = (-sin(-177), 0, cos(-177)) = (+0.05, 0, -1.00)

which is almost exactly -Z, so negating Z preserves it. That is the whole reason it appeared
to work. A mirror of the world Z axis preserves forward only at the two headings lying along
that axis; it is not a transform, it is a coincidence that survived one test. Once the
camera worked and yaw began to vary, movement broke at every other heading, which is precisely
what was reported.

I then tried to replace it with a rotation derived from yaw algebra, and got three mutually
contradictory answers across three hand-derivations. The derivations each assumed things I
had never checked: that Unity being left-handed implies a particular forward convention, that
`AngleAxis` composes with ULTRAKILL's `gravityRotation` in a particular order, and that the
camera's parent transform is unrotated. None of those needed to be true, and each one changed
the answer. **Deriving a convention is not measuring one.**

### What replaced it

Movement is now mapped from ULTRAKILL's *measured* camera basis rather than from an assumed
sign:

1. Decompose Minecraft's movement offset into forward and right components using Minecraft's
   documented forward vector, `(-sin yaw, 0, cos yaw)`.
2. Rebuild it from ULTRAKILL's actual basis, read live from `_camera.cam.transform.forward`
   and `.right`.

Nothing has to be guessed, because ULTRAKILL's half is read rather than predicted. Forward now
maps to forward by construction, at every heading, and no sign constant exists to be wrong.

`UkForward()` prefers the live transform and falls back to the AngleAxis convention only when
there is no camera object, with the fallback documented as conditional on an unrotated parent.

### The measurement is now in the log, and it is not yet right

`puppet camera:` lines now report both bases and their dot product, so "forward is forward"
is observable:

    mc yaw=579.34  UK fwd=(0.631,0.775)  MC fwd=(0.634,-0.773)  dot=-0.200

The dot product should approach **+1.0**. It is sitting near **-0.2**, and the two vectors
have visibly opposite signs on Z. So the honest state is: the mechanism is in place and
readable, the measurement shows a real disagreement, and **which side is wrong is not yet
determined.** Two candidates remain, and the log distinguishes them:

- ULTRAKILL's camera forward is right and Minecraft's forward formula is the wrong way round
  for this build, or
- the camera basis is being sampled before Unity has applied the rotation this frame, so it
  reports last frame's forward.

The second is worth ruling out first because it is cheap: `ApplyRotations` assigns
`transform.localRotation`, and reading `transform.forward` in the same frame can return the
previous frame's value if the world matrix has not been refreshed. That would produce exactly
a consistent, moderate disagreement like this.

I am deliberately not adjusting a constant to make the dot product look better. Doing that is
what produced this bug.

### Logging fix

The camera line had a 0.5-degree change threshold, so an unattended session with a still mouse
produced **zero** camera lines, and there was no way to tell "camera not responding" from
"camera never exercised". The threshold is now 0.05 degrees. This cost a round of confusion
after the mouse fix, when the missing lines looked like the camera still being dead rather than
the player simply not having moved the mouse.---

## 2026-10-08 — The camera was reading the wrong input API, and the anchor was arbitrary

Reported: movement is now perfectly aligned with the camera, but the camera only moves when
ESC is pressed and the cursor is released. ULTRAKILL locks the cursor during normal play and
only frees it on ESC, in vanilla and with the mod installed alike.

That symptom was the most informative report so far, because it is a state change rather
than a magnitude. Something worked in one state and not the other, and the difference between
those states is the cursor lock.

### Cause 1: the legacy Input class, not the Input System

ULTRAKILL does not read look input from the legacy `Input` class. From `CameraController`:

```csharp
Vector2 vector3 = MonoSingleton<InputManager>.Instance
                    .InputSource.Look.ReadValue<Vector2>();
rotationX += vector3.y * (opm.mouseSensitivity / 10f) * num;
rotationY += vector3.x * (opm.mouseSensitivity / 10f) * num;
```

That is Unity's **Input System**, via `PlayerInput`. Our `InputForwarder` was using
`UnityEngine.Input.mousePosition` and differencing it frame to frame. That is a cursor
*position*, not a movement delta, and a locked cursor's position does not track physical
mouse movement. So the forwarded delta was approximately zero for the entire time the player
was actually playing, and became non-zero only once ESC freed the cursor.

The earlier flood of `mouse 0,0` and `1,0` lines was that position jittering. It was never
movement, and reading it as weak movement is what sent the previous round looking for a
sensitivity problem instead of the right API.

Now reading `Pointer.current.delta`, a true per-frame delta that is independent of cursor lock
state. `Unity.InputSystem.dll` ships inside `ULTRAKILL_Data/Managed/`, so this adds no
download and the build still writes nothing into the game folder.

The delta is forwarded **unscalled**. ULTRAKILL applies its own `mouseSensitivity` to the same
raw figure, so forwarding it raw leaves Minecraft's sensitivity option governing look speed,
as in vanilla. Scaling here would silently make ULTRAKILL's slider also drive Minecraft's
camera. `Pointer.current` is null until the Input System sees its first device event, which
is null-guarded and reported once, because a genuinely uninitialised Input System and a player
who has not moved the mouse look identical in the log and are not the same problem.

### Cause 2: the yaw anchor compared two unrelated cameras

With real deltas flowing, the `dot` between ULTRAKILL's measured camera forward and
Minecraft's forward read **-0.417**, not -1.0 and not +1.0. A sign error gives exactly -1.0,
so this was not a sign error, and a partial angle means a fixed rotation between the bases.

Fitting the actual relation from the live log settled it:

    appliedUkYaw = -0.998 * mcYaw + 114.39

The code applies `R = ukYawAnchor - (mcYaw - mcYawOrigin)`, which is
`-mcYaw + (ukYawAnchor + mcYawOrigin)`. The slope of -1 confirms the code was doing exactly
what it claimed. The intercept of **114.39** is the bug.

`_ukYawAnchor` was read from ULTRAKILL's camera and `_mcYawOrigin` from Minecraft's player.
Nothing relates those two: they are two independent cameras facing wherever they face. The
anchor assumed the views coincide at attach, froze whatever arbitrary difference existed at
that instant, and held it forever. That is the "camera stuck 114 degrees off" symptom, and it
would have been invisible while the camera never moved at all.

The anchors are now derived from Minecraft, so the intercept is zero by construction. Verified
against ULTRAKILL's own `ApplyRotations`, which tilts with
`Quaternion.AngleAxis(rotationY, Vector3.up)`, taking forward (0,0,1) to `(sin R, 0, cos R)`
in Unity's left-handed frame against Minecraft's `(-sin Y, 0, cos Y)`, which requires
`R = -Y`. Pitch is negated for the same reason and gets a zero intercept too.

Ordering matters and is now correct: the origins are assigned before the anchors that are
derived from them.

### Cause 3: unbounded yaw into a clamped field

Independent of the above, and it would have broken the camera permanently once fixed:

    Minecraft getYRot()   : unbounded. Observed 517.65, 569.29, 591.34.
    ULTRAKILL rotationY   : minimumY = -360f, maximumY = 360f

Once Minecraft's yaw passes 360, a directly assigned `rotationY` falls outside ULTRAKILL's
range and the camera stops responding, with no error and no warning. A couple of minutes of
looking around would produce exactly the "camera is dead" symptom this project has already
chased three times, from a cause that had nothing to do with input.

Yaw is now wrapped into (-180, 180] before assignment. That is visually identical, since
`AngleAxis` is periodic in 360 and `Mathf.DeltaAngle` takes the short way round the seam, so
the jump by 360 produces no visible spin. Pitch is clamped to +/-89 to match ULTRAKILL's own
limit.

### The recurring lesson, honestly

Three of the last four rounds were spent on causes that a single well-chosen measurement would
have excluded immediately: a value that is byte-identical across runs, a yaw that never moves
at all, a camera that works in exactly one of two states. Each was diagnosable from the logs
before any code was written. The failure was consistently treating a symptom as a mystery to
reason about rather than as a measurement to take, and preferring a plausible mechanism to a
verified one — which is how a comment came to assert that `onMove` "accumulates that delta"
when it does not, and how a Z mirror survived being derived at a single heading.---

## 2026-10-08 — Camera direction reversed, and camera motion dragging the player

Reported: the camera moves. Two remaining problems: "camera directions are all reversed",
and "the camera moves fine at the start, the moment I start to walk around, looking around
with the camera starts moving the player too". Movement itself was reported as fine.

### The circular drag, confirmed analytically

The previous round replaced a fixed axis mirror with a decomposition that used the **live**
camera basis every frame. That decomposed the player's world offset using the *current* yaw
and rebuilt it from the *current* basis. Turning the camera changes the basis but not the
offset, so the result had to move. Simulated with the player standing completely still:

    mcYaw=  0  offset -> (-1.500, 2.000)   heading  -36.9 deg
    mcYaw= 45  offset -> (-2.000,-1.500)   heading -126.9 deg
    mcYaw= 90  offset -> ( 1.500,-2.000)   heading  143.1 deg

A 90-degree sweep of a motionless player. That is the reported circle, reproduced rather than
guessed.

**Fixed by caching ULTRAKILL's horizontal basis at the anchor** instead of reading it live.
Verified both properties that matter:

    yaw   LIVE basis -> mapped offset     CACHED basis -> mapped offset
      30   (-1.500,2.000)                 (-1.500,2.000)
      90   ( 0.982,2.299)                 (-1.500,2.000)
     180   ( 2.299,-0.982)                (-1.500,2.000)

Turning the camera now provably cannot move the player.

**This does not change movement.** The cache is sampled at the anchor, when ULTRAKILL's
heading has just been set equal to Minecraft's, so it holds the value the live version would
have used on that frame; with the camera still, every later frame is identical to before. The
only behaviour that changes is the swing, which was the bug. Worth stating plainly because
"fix the camera, don't touch movement" and "the camera dragging the player is a position
mapping bug" are in tension, and the resolution is that the fix is sampling-time only.

### Camera direction: flipped from observation, not derivation

`YawSign` went from -1 to +1, so a given mouse delta now turns the camera the other way.

This is worth recording honestly: the value -1 was *derived* from ULTRAKILL's own
`ApplyRotations`, and it was wrong. The player's report that every camera direction was
reversed is the observation, and it beats the derivation. Two earlier rounds produced
confident derivations from that same code that each turned out wrong.

Pitch was deliberately **not** flipped. It is part of the same report, but it derives cleanly
from `ApplyRotations`: it tilts by `AngleAxis(0 - rotationX, Vector3.right)`, and in Unity's
left-handed frame a positive rotation about +X tilts forward *downward*, so Minecraft's
positive pitch maps to `rotationX = -mcPitch`. Flipping pitch on the strength of a phrase that
was about yaw would break the one axis that works. **This is the one open question left, and
it is a one-line change if up/down is also inverted.**

### Why the handedness question could not be settled by reasoning

Unity is left-handed, Minecraft is right-handed, so a correct map between them has to
*reflect* and cannot be a rotation. That gives exactly two self-consistent options:

| | yaw relation | axis map | forward | strafe |
|---|---|---|---|---|
| (a) | `ukYaw = -mcYaw` | identity | correct | inverted |
| (b) | `ukYaw = +mcYaw` | flip X | correct | correct |

Under (a), no world transform can fix strafing without breaking forward. Both options are
internally consistent, and which one holds depends on whether the handedness mismatch is
absorbed by the yaw sense or by the axis flip. Reasoning could not choose, so the yaw sense
was settled by observation and the axis by measurement.

Caching the basis removes the dependency entirely: movement now reads ULTRAKILL's basis from
the live camera and keeps it, so the handedness is absorbed by measurement and **cannot
contradict `YawSign`**. The two are now independent, which is why the constant can be set
from a report without destabilising movement.

### Instrumentation blind spot, closed

The camera log reported only the forward dot product. Forward alone cannot distinguish a
correct mapping from one that is correct for forward and inverted for strafing, which is
exactly the ambiguity that ran for several rounds. It now reports both:

    fwdDot=... rightDot=...

`McRight` is Minecraft's forward rotated -90 degrees about +Y, which at yaw 0 gives the
player's right as -X, matching "facing south, right hand points west".

### Method note

This is the third consecutive round where the first useful action was a measurement and the
temptation was to ship a reasoned constant. Two of those constants were wrong. The pattern is
consistent enough to name: for a mapping between two engines, the convention has to be
observed, because deriving it requires assumptions about handedness and transform composition
that nothing in the log checks.---

## 2026-10-08 — Two corrections, and instrumentation that finally measures instead of guessing

Reported: everything still the same; the yaw flip "didn't change much, but keep it, it moved
less"; and, decisively, **all directions are reversed — up and down as well as left and right**.

### Minecraft's camera is correct. Verified.

From the player's own session, 91 samples of real mouse motion:

    mouseDx vs change in Minecraft's yaw : agree 91/91 = 100%
    +168 px -> yaw +27.45
    -379 px -> yaw -44.85

Minecraft yaw 0 faces south, the right hand points west, and west is yaw +90, so turning right
*raises* yaw. Mouse right raises yaw. The whole camera reversal is therefore NOT on the
Minecraft side, and NOT in the yaw sign that was flipped. This was measurable from logs that
already existed and had not been used for it.

### The flip was not justified

`YawSign` went -1 to +1 on the strength of "all camera directions reversed", which was
reported while the camera was not yet turning. The player reports it barely changed anything.
That sign rests on an assumption that has never been checked: that ULTRAKILL's forward for
rotation `R` is `(sin R, cos R)`. If it is actually `(-sin R, cos R)` then the original -1 was
right and the flip is a regression. That single unverified formula is what every yaw decision
this project has turned on, and it has now been guessed wrong three times.

The player asked to keep the flip, so it stays. It is recorded as an open question rather than
a fix.

### Pitch: observation beats derivation

Pitch was negated, on a derivation from `ApplyRotations`: it tilts by
`AngleAxis(0 - rotationX, right)`, so a positive `rotationX` reads as looking up while
Minecraft's positive pitch looks down, implying `-mcPitch`.

The player reports up and down reversed too. The derivation predicted correct; the game says
otherwise. Observation wins, and pitch now follows Minecraft directly with no negation. The
comment records the report and the history rather than asserting a convention that is not
confirmed, because a comment claiming a verified sign is worse than no comment when it is wrong.

**Both** axes being reversed at once is itself informative. A handedness mismatch reverses one
axis. Two axes at once is closer to a 180-degree error than to a mirror, which is consistent
with "the flip did not change much" and is worth remembering when reading the next result.

### The diagnostic was reporting itself, not the game

This is the mistake worth recording. The camera log line printed `UkForward()`, which returns
the **cached** basis, and the cache is constant by construction. So it always printed
`UK fwd=(0.000,1.000)` no matter what ULTRAKILL's camera did.

I read that constant as proof the camera was dead. It was proof of nothing except that I was
printing my own cache. The `fwdDot` and `rightDot` values were just as bad: a stale
anchor-time basis compared against the current Minecraft heading, producing numbers that
changed sign at random and meant nothing. Shipping a diagnostic built on a cached value is how
an instrument ends up confidently reporting the wrong thing.

The camera line now reports, in one place:

- `UKliveFwd` — ULTRAKILL's camera forward, read live, for observation only
- `UKcachedFwd` — what the movement map actually uses
- `predSinCos` — the assumed `(sin R, cos R)` formula, side by side with reality
- `fwdDot` / `rightDot` — both computed from the **live** basis

Comparing `UKliveFwd` against `predSinCos` settles the convention permanently and by
observation, and the dot products then determine the yaw sign from data rather than from
arithmetic. Movement still uses the cache and is unchanged.

### Note on the automated runs

Every "verification" run this round was an unattended session in which Minecraft's player was
drifting with `onGround=false` and yaw spinning over a hundred values. Those logs are close to
useless for camera and movement questions, and conclusions drawn from them have been
unreliable. The measurements that mattered all came from sessions the player actually played.---

## 2026-10-08 — The last of the camera-drags-the-player coupling

Reported: camera directions are now **correct in every axis** ("all of them. up and down,
left and right"). One bug remains: the player also moves when the camera moves, but only
"after the player moves from the starting point".

That qualifier turned out to be the whole diagnosis.

### The two previous fixes were confirmed by the shipped diagnostic

The instrumentation added last round settled the convention that every earlier yaw decision
had been guessed at. From the running game:

    UKliveFwd=(-0.050,0.999)  predSinCos=(-0.050,0.999)   -> forward IS (sin R, cos R)
    mc yaw=-362.89  ->  applied ukYaw=-2.89                -> wrap working on unbounded MC yaw
    fwdDot=0.992   rightDot=-0.995                         -> forward aligned

So `predSinCos` matched `UKliveFwd` exactly, the unbounded-yaw clamp is doing its job, and
forward is aligned. The remaining `rightDot` of about -1 is the handedness difference between
a left-handed and a right-handed engine, and movement does not depend on it because the map
uses measured bases rather than derived signs.

### Cause: only half the coupling was removed

The position map decomposes the world offset into a forward/right pair and rebuilds it in
ULTRAKILL's frame:

    f     = dot(d, mcFwd(CURRENT yaw))     <- still live
    r     = dot(d, mcRight(CURRENT yaw))   <- still live
    mapped = f*cachedUkFwd + r*cachedUkRight

ULTRAKILL's basis had been cached at the anchor, but **Minecraft's half was still being
computed from the current yaw**. That is enough on its own: `(f, r)` is the world offset
expressed in Minecraft's *current* frame, so turning the camera rotates the frame, which
rotates `(f, r)`, which moves `mapped` — even though the offset `d` and the cached ULTRAKILL
basis are both unchanged.

**Why the symptom only appeared after walking away.** At the anchor the offset is exactly
zero, and rotating a zero vector still gives zero, so the bug is invisible at the start. The
moment the player moves, there is a non-zero offset to swing. That is why it read as
"the player also moves when the camera moves after the player moves from the starting point",
which sounded like two interacting bugs and was one bug with a clean explanation.

Simulated with the player motionless, offset (1.2, 0.8):

    yaw   live Minecraft basis       cached Minecraft basis
      -3   (-1.200, 0.800)            (-1.200, 0.800)
      42   (-1.414,-0.283)            (-1.200, 0.800)
      87   (-0.800,-1.200)            (-1.200, 0.800)
     177   ( 1.200,-0.800)            (-1.200, 0.800)

### Fix, verified against the running game's own numbers

Both bases are now cached at the anchor, making the map a fixed linear transform of the world
offset with no term that depends on where the player is looking. Re-verified using the basis
values the running build logged (`MC fwd=(-0.052,-0.999)`, `MC right=(0.999,-0.052)`,
`UK fwd=(0.000,1.000)`, `UK right=(1.000,0.000)`):

    yaw      mapped offset
    177    (1.1571,-0.8609)
    267    (1.1571,-0.8609)
    402    (1.1571,-0.8609)
    492    (1.1571,-0.8609)

Constant. And forward still walks forward: a Minecraft step of (0,-1) maps to UK heading 3.0
degrees against a camera forward of 0 degrees, so movement is preserved and the coupling is
gone.

The now-unused `mcYaw` parameter was removed from `McToUk` rather than left in place, so the
signature cannot suggest a yaw dependence that no longer exists.

### How this round went, honestly

The fix was one cache. It took a week of project time to find because the previous round
cached the *obvious* half of a two-sided dependency and the symptom pointed at the half that
was already fixed — the player described it as a confusing compound sentence because it *was*
one, and the "only after moving from the start" detail was the tell that it was a
rotation-of-a-zero-vector problem rather than anything about camera control.

The generalisable lesson is narrow and worth stating: when a transform depends on state from
two sources, cache **both** at the same moment or neither. Half-measures produce a bug whose
symptom points away from the remaining half, because the fixed half stops contributing.---

## 2026-10-08 — WASD rotated: the basis cache was sampled one step too early

Reported: camera directions are correct in every axis and must not be touched; camera motion
no longer affects the player. One bug left: "A is backwards, D is forward, W is left S is
right or something."

### Cause: ordering, not arithmetic

The diagnostic added the round before had been printing both the live camera basis and the
cached one all along, and the answer was sitting in the log:

    cached at anchor : UKcachedFwd=(0.000, 1.000)
    live in play     : UKliveFwd  =(0.163,-0.987) ... (0.633,-0.774)

The cache was frozen at ULTRAKILL's default heading for the entire session. The reason is
purely one of ordering:

`Anchor()` computed the anchors and then called `CacheUkBasis()`. But at that moment the
camera had **never been rotated to the anchor heading**, because `TickCamera()` returns early
while `_anchored` is false, so `ApplyLook()` had not run once. On a fresh level load the
camera is still at `rotationY = 0`, pointing at +Z. So the cache recorded +Z and kept it.

With the anchor at `mc yaw 82.00`, that put WASD **82 degrees** out from the camera's real
heading — exactly the reported rotation.

**Fixed by moving the sampling**, not by changing any maths: `CacheUkBasis()` is no longer
called from `Anchor()`. `TickCamera()` now calls `TryCacheBasis()` on the first anchored
frame, immediately after `ApplyLook()` has put the camera at the anchor heading. It reads the
live transforms directly, since `UkForward()` returns the cache once it exists.

Verified with the player's own anchor numbers, and at every heading, since both bases are
fixed at the anchor:

    mc yaw -180: W->forward 1.000  D->right 1.000
    mc yaw  -57: W->forward 1.000  D->right 1.000
    mc yaw    0: W->forward 1.000  D->right 1.000
    mc yaw   90: W->forward 1.000  D->right 1.000
    mc yaw  170: W->forward 1.000  D->right 1.000

Confirmed live, and the cache now reports its own validity:

    cached bases at anchor (mc yaw 760.49, ukYaw 760.49):
    UK fwd=(0.649,0.760) UK right=(0.760,-0.649)
    MC fwd=(-0.649,0.760) MC right=(-0.760,-0.649)
    [cache sampled from a camera at the anchor heading: valid]

### Guard added

`CacheUkBasis()` now compares the basis it sampled against the heading the camera *should* be
at, and says so in the log, either `valid` or `INVALID: sampled N degrees away`. Every camera
line also reports `cacheDriftDeg`, the angle between the cached basis and the live one.

This exists because a stale cache is **indistinguishable from a correct one** in the log. The
cached value looked entirely plausible — `(0.000, 1.000)` is a perfectly good unit vector —
and nothing about it said "this was sampled before the camera was ever rotated". The only
thing that exposed it was printing the live basis next to the cached one, which was added for
an unrelated reason. A diagnostic that cannot report its own staleness will eventually be
trusted when it is stale.

### A correction to my own verification

The first check I ran reported A and D swapped. That was wrong in the check, not the code: I
had labelled A as `McRight`, but `McRight` is Minecraft's **right** — at yaw 0 Minecraft faces
+Z (south) with the right hand pointing west, and `McRight(0) = (-1, 0)`. With the labels
corrected, all four controls map correctly. Worth recording because a verification script
that is wrong in the same direction as the bug it is checking will happily confirm the bug.

### Where the project stands

Stage 1 is complete: movement, camera, and the mapping between them all behave, with Minecraft
authoritative and ULTRAKILL mirroring it. The remaining known gaps are all in the later
milestones — collision injection, so ULTRAKILL's geometry actually stops the player, and the
voxelisation that Stage 2 needs.

---

## 2026-10-13 — Handover review: doc pass

New session (Opus 5.5) took over from HANDOVER.md. No behaviour changes.

- **Scale label was backwards.** `_scale = capsuleHeight / 1.8` = 1.94445 is ULTRAKILL units
  per Minecraft block, and Minecraft deltas are multiplied by it, so the maths is right. The
  log line, doc comment, generator field comments and docs said "blocks per unit". Labels
  fixed; protocol regenerated (comments only), `check-layout.sh` passes.
- **The protocol files' 2026-10-13 timestamps** came from `check-layout.sh`, which regenerates
  them. Not a layout change.
- README, SETUP and CONTRACT still described `~/.ultracraft/` and `bridge/` bridge paths,
  required `ULTRACRAFT_BRIDGE`, said ULTRAKILL "does not tick while unfocused" (the plugin
  fixes that), and described the voxelisation plan that the D1 collision direction replaced.
  CONTRACT's message table listed a `SLOT_VOXEL` that does not exist. All updated.
- Recorded that dash/slide do not exist yet (the puppet disables `NewMovement` wholesale), and
  that Minecraft keeping its own window is the user's choice, deferred.


## 2026-10-13 — Milestone C: void world (decision, awaiting playtest)

**User chose: vanilla "The Void" superflat preset, Creative mode.** No mod code needed:
26.3 ships `flat_level_generator_preset/the_void.json` (one air layer, `the_void` biome, no
natural mob spawns, `void_start_platform` stone pad at spawn). Creative so the player flies
and cannot fall while ULTRAKILL geometry is not yet solid; Survival returns once Milestone D
gives them something to stand on. The old "New World" save is left untouched.

Unverified: double-tap Space to toggle flight via forwarded input (KEY_JUMP is applied with
`KeyMapping.setDown` per sample, which should satisfy `LocalPlayer`'s double-tap timer).

Playtest result (user): void world and flight both work. Going deep in ULTRAKILL still drops
Minecraft's player into the void and kills it, which is expected until ULTRAKILL floors are solid.

---

## 2026-10-13 — Milestone D spike: ULTRAKILL boxes in Minecraft's collision (awaiting playtest)

**Collision entry point, from 26.3 bytecode.** Every movement collision query funnels through
`Entity.collectCollidersIgnoringWorldBorder(Entity, Level, List, AABB)` (private static):
`collide` → `collideBoundingBox` → it, and `collide` also calls it directly for step-up. It
returns entity shapes + border + `level.getBlockCollisions`, and the caller resolves the move
with `collideWithShapes`. `EntityCollisionMixin` appends ULTRAKILL boxes there at RETURN, for
`Player` only (LocalPlayer and the integrated server's ServerPlayer share the JVM and the
static snapshot, so client and server agree). Not patched: the `CollisionContext` overload,
`noCollision`/`collidesWithSuffocatingBlock`/`findSupportingBlock` (separate paths).

**Protocol: COLLISION table**, appended after the rings so no existing offset moves (version
stays 1; an older peer just never reads it). One seqlock over `{tickTimestamp, generation,
count}` + 256 boxes of six f32 in Minecraft block coords. Total file 18,768 bytes. The C#
side grows a shorter file left by an older build. `check-layout.sh` now also compares every
`OFFSET_*`/`TBL_*` constant across languages; verified it FAILS when one Java constant is
altered.

**ULTRAKILL side (`CollisionPublisher`), 10 Hz:** `Physics.OverlapBoxNonAlloc` within 10 blocks
of the puppet on ULTRAKILL's own `LayerMaskDefaults.Get(LMD.Environment)` (layers 6,7,8,24),
triggers ignored, puppet excluded. BoxColliders whose 8 mapped corners form an exact AABB are
sent; rotated boxes, MeshColliders and others are skipped and counted. A census (types,
counts, the largest skipped colliders) is logged once per anchor and summarised in the status
file — that census is what decides D1's next step.

**Inverse map (`PuppetController.UkToMc`).** General 2x2 inverse of the cached-basis map, plus
a measured feet offset (capsule bottom − rigidbody), because the puppet pins Minecraft's feet
to the rigidbody centre: without it every floor would land ~half a player height low.
Simulated with the bases from the user's own log: round-trip error 3e-14 blocks, and the
horizontal map is exactly `diag(-1, 1)` (an X mirror) at any heading, so axis-aligned
ULTRAKILL boxes stay axis-aligned in Minecraft.

**Cross-language test:** the real C# `WriteCollisionTable` and the real Java
`readCollisionTable` on one file (seeded at the old 12,600 bytes): grown to 18,768, timestamp,
generation, count and all box floats read back exactly. (.NET on Linux, not Mono under Wine;
the Wine-boundary coherence itself was proven at Milestone 1.)

**Found, not changed (Stage 1):** `PuppetController.NoOpUpdate` is a `void` Harmony prefix. A
void prefix does not skip the original, so `NewMovement.Update`/`FixedUpdate` most likely still
run every frame despite the comment saying the body is replaced. The kinematic rigidbody and
per-frame `MovePosition` mask it. Raised with the user rather than fixed silently.

Backups of the working Stage 1 artefacts: `backups/2026-10-13-stage1/` (git-ignored).

---

## 2026-10-13 — Player safety: spawn sync, hold, void net (awaiting playtest)

**Playtest blocked:** "the Minecraft player keeps falling". Logs: every ULTRAKILL level load
anchored on a Minecraft player that was already free-falling (anchors at y=-115.9, -60.0,
-129.4; status file later y=-155, health 12.8). With no solid ULTRAKILL floors, Minecraft
gravity and void damage owned the body.

Side evidence for the NoOpUpdate finding: the measured feet offset differed per anchor
(-1.379, -1.500, -1.124), i.e. ULTRAKILL's capsule height was changing — consistent with
`NewMovement` still running (slide shrinks the capsule).

**SkyCraft, from its DESIGN.md:** Minecraft's player is real (gravity, fall damage, death);
Skyrim's is a puppet. Mapping is ABSOLUTE (MC = Skyrim / 70); on cell/door changes the MC
player is MOVED to match. MC client frozen during Skyrim menus/loading. Void world, custom
dimension (min_y -2032). Collision: host shapes voxelised to 1/8-block AABBs, appended to
MC's movement collision query via a mixin (same hook idea as ours).

**User chose: spawn sync + hold + void net, keeping the relative mapping** (so `UkToMc` and
the wall code are untouched).

- Header `ukLevelId` (unused) became `ukSpawnRequest`; `PLAYER.spawnAck` added. PLAYER grew
  67 -> 71 bytes inside its existing aligned space: **no offset moved**. `KEY_HOLD_TOGGLE` =
  bit 7 = H (unbound in ULTRAKILL by default).
- ULTRAKILL (`PuppetController.Anchor`): before anchoring, writes request = current header
  value + 1 (so a restarted ULTRAKILL never reuses a handled number) and waits for
  `spawnAck == request`. 5 s timeout -> anchors anyway with a warning (older MC build).
- Minecraft (`PlayerSafety`): on a new request, teleports client AND integrated-server player
  (server via `ServerPlayer.teleportTo(level, x,y,z, Set.of(), yaw, pitch, false)`, the /tp
  path) to SPAWN = (1000.5, 128, 1000.5), zeroes velocity and fall distance, holds, acks once
  the client is within 0.05 of SPAWN. Spawn hold = no gravity + zero velocity, released by a
  movement key. H = hard lock (movement keys dropped in InputApplier). Void net below
  minY-8 -> SPAWN + hold (SPAWN is the anchor, so the puppet returns to the level spawn).
  All holds drop if ULTRAKILL is not live.
- Bytecode facts: `shouldCheckPlayerMovement` returns early for `isSingleplayerOwner`;
  "moved wrongly" is skipped for creative/spectator.
- `SelfTest` now clears its 0xA5A5A5A5 INPUT probe immediately: its bits press forward,
  attack, inventory and now the H toggle, and it used to sit there until the next sample.

---

## 2026-10-13 — Playtest: safety works, first walls solid; lag fixed

User: H works; some walls and floors are solid; most floors are not; some walls can be passed
through; movement "sometimes pretty laggy".

**Census at the end of the session** (status file): 61 environment colliders in range, 21
axis-aligned boxes sent, **36 MeshColliders and 4 rotated boxes skipped**. Floors are mostly
MeshColliders, which the spike deliberately does not send; pass-through walls are consistent
with rotated boxes / meshes. Spawn sync worked on every level load after the first (the first
timed out because Minecraft was still starting, and fell back as designed).

**Lag, measured:** the periodic Minecraft log line fires every 200 bridge ticks, and the bridge
ticks once per Minecraft FRAME. Its spacing showed ~120 FPS normally with repeated drops to
~29-33 FPS. 26.3 `FramerateLimitTracker`: SHORT_AFK after 60 s without Minecraft's own input
-> min(limit, 30); LONG_AFK after 10 min and WINDOW_ICONIFIED -> 10. Input arrives through the
bridge, so Minecraft always thought it was AFK. Fix: `onInputReceived()` every frame while
ULTRAKILL is live. README's "minimise Minecraft" advice was wrong for the same reason
(minimised = 10 FPS) and is reversed.

**Second, constant choppiness:** `PlayerPublisher` sent `p.position()`, which only changes per
20 Hz game tick, so ULTRAKILL drew the puppet in 50 ms steps. Now publishes
`getPosition(partialTick)` (the position Minecraft itself renders). `snapTo` sets the old
position too, so spawn/void-net teleports are not smeared.

---

## 2026-10-13 — 1/8-block voxel collision for non-box colliders (awaiting playtest)

User chose SkyCraft's resolution (1/8 block) over 1/4.

**`VoxelField` (ULTRAKILL side).** Geometry is never read. Each Minecraft-grid cell is mapped
into ULTRAKILL space with the new `PuppetController.McBoxToUk` (forward counterpart of
`UkToMc`: centre, half extents along the images of MC +X/+Z, `LookRotation` onto MC +Z's image)
and tested with `Physics.OverlapBoxNonAlloc` on the Environment mask. A whole block first, then
1/2, 1/4, 1/8 only where it overlaps. Probe boxes are shrunk by 0.004 blocks so a surface on a
cell boundary marks one side. Colliders that are exact boxes (and the puppet) are ignored by the
probes, so they are not sent twice; a full 64-entry result buffer counts as a hit. Blocks are
probed nearest-first within 10 blocks horizontally / 8 vertically, 2.5 ms per frame, cached
until the mapping generation changes, far blocks evicted past 60k. Per-block greedy merge
(X, Z, Y), then cross-block merge passes along X, Z, Y with exact float compares (1/8
multiples are exact in float).

Limit, stated in the class doc: the cache assumes static geometry (doors, moving platforms,
colliders enabled later by scripts keep their probed state).

**`CollisionPublisher`:** exact-box classification cached per collider per mapping; voxel
probing every frame, sampling still 10 Hz; voxel boxes appended after exact ones; nearest-first
truncation to capacity; the table is rewritten only when its contents change (Minecraft
rebuilds shapes on every new table). A one-off "collision voxels ... complete" line per level
reports blocks, queries, total ms and us per query — the performance measurement under Mono +
Wine. Status file gains voxel stats.

**Protocol:** COLLISION capacity 256 -> 4096 (98,328 bytes; still appended last, nothing else
moved). `check-layout.sh` passes.

**Offline checks:** `McBoxToUk` then `UkToMc` on the 8 corners returns the original box
(worst error 3e-14 blocks, 2000 random boxes, user's real bases). Python port of the greedy +
merge: 8x8-block floor (1/8 thick) -> 1 box; 4-block wall -> 1 box; 27-degree ramp -> 32 boxes.

---

## 2026-10-13 — Playtest: floors solid; door-edge falls and stutter fixed (awaiting playtest)

User: floors are solid now, some stutter, and "at the edge of every door after going forward
there is a chance of falling through the ground".

**Measured:** overlap queries cost 1.1-1.8 us each under Mono + Wine; the first full scan
around spawn was 35,649 queries in 64 ms. (Later "complete" lines were cumulative because
`Probes`/`ProbeMs` were never reset per generation — fixed.) Every void-net catch was preceded
by Minecraft receiving 0 boxes.

**Door falls — cause:** ULTRAKILL switches a room on when its door opens. The voxel scan
(10 blocks) reaches past doors long before, so those blocks were probed while the room was
off and cached as EMPTY; blocks probed after opening are solid. Exactly "fall just past every
door". This was the static-cache limit already stated in the VoxelField doc.

**Fix:** `CollisionPublisher.TrackColliders`, on the existing 10 Hz sample, keeps each
environment collider's Minecraft-space bounds per generation. First sighting (after the first
sample of a level) -> `VoxelField.Invalidate` its bounds: the voxel cube lies inside the query
cube, so a collider never seen cannot have been active when those blocks were probed. Moved
-> invalidate old and new bounds (sliding doors). Vanished while its bounds are still in range
-> invalidate (switched off). Leaving range by walking away is not a change. Invalidation is
clamped to the scan cube and restarts the nearest-first scan so the gap refills first.

**Stutter — likely cause and fix:** the cross-block merge re-sorted thousands of boxes on the
main thread at every block boundary (several times a second while walking). It now runs on a
worker thread over a snapshot of the per-block box arrays (immutable once built), at most every
0.25 s, re-triggered by new probes or 3+ blocks of movement; the result is swapped in when done.
Probe budget 2.5 -> 2.0 ms/frame.

**Layer diagnostic:** layer names read from globalgamemanagers (6 OutdoorsBaked, 7
EnvironmentBaked, 8 Environment, 24 Outdoors = LMD.Environment; others include 0 Default,
18 PlayerOnly, 25 Outdoors Non-solid). The publisher now logs, once per collider per level,
anything the player's layer collides with (Unity's layer collision matrix) that is outside the
Environment mask and not a dynamic layer: "collision: NOT SENT, solid to the player ...". If
level geometry shows up there, the mask is too narrow.

---

## 2026-10-13 — Playtest: doors fixed; stutter only when moving AND turning — cause found

User: doors fixed; the stutter persists and "only happens when both the character and mouse
is moving".

**Cause (from ULTRAKILL's code, not guessed):** `CameraController.ApplyRotations` ends with
`player.transform.localRotation = ...; NewMovement.rb.rotation = player.transform.rotation;`
and the camera is a child of the player (it only touches `localPosition`). The puppet moved
the body with `_rb.MovePosition`, which on a kinematic body applies at the NEXT physics step,
with interpolation off. Turning writes `rb.rotation` every frame onto a body still at the
previous step's position, so body and camera snapped between old and new positions — only when
both changed. Moving alone (physics-rate steps) and turning alone (position unchanged) were
smooth, which is exactly the report.

**Fix:** the puppet sets `_rb.position` and `_rb.transform.position` directly every frame.
Kinematic bodies are not pushed by colliders anyway (collision is Minecraft's job now), so
nothing is lost. The old comment claiming "kinematic movement still resolves against
colliders" was wrong and is gone.

---

## 2026-10-13 — Decisions and research: no ULTRAKILL movement; how SkyCraft renders

**User decision:** no ULTRAKILL movement at all, not even dash/slide. Pure Minecraft movement.
Supersedes the brief's "keep sliding and dashing" and HANDOVER constraint 3. (`NoOpUpdate`
still does not actually stop `NewMovement` — fixing that is now simply "finish disabling it".)

**SkyCraft rendering, from its SOURCE (MIT, github.com/chasmlol/SkyCraft, pushed 2026-10-01).**
Its DESIGN.md describes compositing three Minecraft-rendered layers with GPU interop; the shipped
code differs and is a hybrid:

- **World: native.** `WorldExporter` re-meshes dirty 16^3 sections (12/frame, placed/broken
  first) with Minecraft's own `ModelBlockRenderer`/`FluidRenderer` into triangles
  (section-relative position, atlas UV, RGBA tint+shading, block/sky light) and ships them.
  `SkyAtlas` builds one RGBA image on the CPU from Minecraft's LOADED block + item atlases
  (so the player's own install / resource packs; nothing shipped), plus crack stages and arrow
  textures; animated sprites are streamed per tick. Dropped items, arrows, cracks and the
  selection outline go as light "world entities". `AvatarExporter` implements
  `SubmitNodeCollector` to capture Minecraft's posed entity / block-entity / particle geometry
  as triangles. Skyrim draws all of it in its own frame (`WorldRender.cpp`, ~3,400 lines of
  D3D11: depth with the world, Skyrim's sun shadow maps, ambient, contact shadows).
- **Hand + HUD + screens: an image.** `LevelRendererMixin` cancels `LevelRenderer.render` while
  linked, so Minecraft's main render target holds only hand + HUD on transparent background;
  `FrameExporter` reads it back asynchronously (staging buffers) into a triple-buffered
  overlay that Skyrim draws on top (`Overlay.cpp`; crosshair with Minecraft's invert blend).
- Placement on host geometry: `SkyClip.SkyrimHitResult`, a synthetic block hit on Skyrim
  ground; the outline shows where a held block would go. Relevant to Milestone E.
- Same Minecraft 26.x APIs as ours (`QuadInstance`, `BlockQuadOutput`, `SubmitNodeCollector`).

Implication: our stage names (GUI layer, world layer (depth), hand layer) came from SkyCraft's
design doc, which assumed image compositing; our contract forbids images. Decision pending.

**User decision: fully native rendering, no images.** World, hand/held item, HUD and every
Minecraft menu are redrawn in Unity from Minecraft data and textures read from the player's own
install at runtime. The contract's "no images cross the bridge" rule stands as written.

---

## 2026-10-13 — ULTRAKILL movement fully off; world layer W1 (awaiting playtest)

**Movement cleanup.** The void `NoOpUpdate` Harmony prefixes are gone (they never skipped
anything). `NewMovement.Update` turned out to carry far more than movement (hard-damage
recovery, hurt flash, death check, dash stamina, fists, camera height/shake, footsteps,
landing), so skipping it would have broken ULTRAKILL systems. Instead the puppet calls
ULTRAKILL's own `NewMovement.DeactivateMovement()` (what its cutscenes use) every frame it
finds `activated` true: that flag gates exactly move/jump/slide/dash input in Update and
FixedUpdate. Only other readers: `CameraController.LateUpdate` (skips walking head-bob) and
`PlayerActivator.Activate` (level start turns it back on — hence every frame). Shutdown calls
`ReactivateMovement()`. Explains the varying feet offset: Ctrl (MC sprint) also started
ULTRAKILL's slide.

**Shaders (measurement pending).** ULTRAKILL ships its shaders in a 400 MB LZ4 Addressables
bundle (`StreamingAssets/aa/.../shaders.bundle`); names are not readable offline without new
tooling. The plugin logs every loaded shader, availability of the candidates, and the shader /
keywords / properties of ULTRAKILL's own level materials near the player, then picks the first
available of: Unlit/Transparent Cutout, Legacy Cutout VertexLit, Legacy Cutout Diffuse,
Unlit/Texture, Standard, Sprites/Default. None of those is known to multiply vertex colour, so
grass/leaf tint and face shading may be missing until a better shader is chosen from the log.

**STREAM file** (`/var/tmp/ultracraft.stream`, 64 MB ring + 64-byte header; constants in
generate.py, covered by check-layout.sh). SPSC: Minecraft owns HEAD and ring, ULTRAKILL owns
TAIL; 8-aligned records `u32 type, u32 len, payload`; PAD record instead of wrapping; writes
never block (returns false, caller retries). Resync: ULTRAKILL sets TAIL=HEAD and bumps
UK_RESYNC_REQUEST on open; Minecraft answers CLEAR_ALL + atlas + all sections.

**Minecraft (`render/WorldMesher`, `render/AtlasExport`, after SkyCraft's WorldExporter /
SkyAtlas, MIT).** `LevelExtractorMixin` on private `setSectionDirty(IIIZ)` (verified: the
public overload and setBlockDirty funnel into it). Dirty sections meshed with Minecraft's own
`ModelBlockRenderer.tesselateBlock` / `FluidRenderer.tesselate` (fluid vertices verified
section-relative in bytecode: `getX() & 15`), 12 sections / 3 ms per frame, player changes
first. Vertex: pos f32x3, UV f32x2 (block-atlas UVs unchanged), RGBA8 (Minecraft tint x face
shade x AO), flags (block/sky light, translucent). Block atlas built on the CPU from each
sprite's `originalImage` (accessors for TextureAtlas.width/height/sprites and
SpriteContents.originalImage), padded like Minecraft.

**ULTRAKILL (`StreamReader`, `McWorldView`).** 4 ms/frame poll. Atlas -> RGBA32 Texture2D,
point filtered, no flip needed (row 0 = v 0 on both sides). Each section -> a Mesh +
GameObject on layer 8 (Environment, rendered by the main camera; no colliders) at its MC block
origin, under one root whose TRS is `PuppetController.GetMcWorldTransform` (the MC->UK map;
scale.x negative = the measured X mirror, which Unity draws with flipped winding).

**Offline checks:** root TRS vs `UkToMc`, 5000 random points: worst error 2e-13 blocks.
Stream, real Java writer vs real C# reader: 400 records / 551 MB (~8.6 wraps through PAD) all
intact and in order; with the reader started late the writer hit ring-full 491 times and
waited, and the reader's open dropped the backlog by design (resync). Layout check passes.

---

## 2026-10-13 — Playtest: world layer works; sneak freeze and grass colours (awaiting playtest)

User: blocks visible, correctly placed, solid. Two issues: sneaking halts all movement on
ULTRAKILL floors (fine on Minecraft blocks); "complex" blocks like grass render grey with wrong
side textures.

**Shader measurement (from the W1 log):** 76 shaders loaded; none of the generic cutout
candidates exist; W1 drew with `Unlit/Texture`, which ignores vertex colour — hence grey grass
(Minecraft's biome tint lives in vertex colour) and broken sides (the side is dirt plus a
tinted, alpha-cut overlay quad; without colour or cutout the overlay is an opaque grey slab).
ULTRAKILL's level geometry uses `ULTRAKILL/Master` with keywords incl. `_VERTEXCOLORS_ON`,
`VERTEX_LIGHTING`, `_FOG_ON`; properties incl. `_BlendMode`, `_SrcBlend/_DstBlend`, `_ZWrite`,
`_CullMode`, `_SurfaceType` (meanings not recoverable offline: ULTRAKILL's C# never sets them).
Also present: psx/unlit/alphatest/nocull, psx/vertexlit/*, ProBuilder/Standard Vertex Color.

**Fix:** quads carry their Minecraft section layer (flag bit 16 translucent, 17 cutout);
sections get three submeshes. Preferred material per layer = a clone of one of the current
level's own Master materials of matching blend style (by render queue / blend factors), with
level effects' keywords off, `_VERTEXCOLORS_ON`, white `_Color`, the atlas; generic shaders
follow as candidates. Every distinct Master variant in the level is logged, and **F9 / F10 /
F11 cycle the solid / cutout / translucent material live**. Meshes now get face normals
(Master lights by normal).

**Sneak freeze — cause (bytecode):** `maybeBackOffFromEdge` and `isAboveGround` both use
`Player.canFallAtLeast`, whose only test is `level.noCollision(this, box)` — block collision,
not the movement path EntityCollisionMixin patches, so ULTRAKILL floors were invisible to it and
every direction looked like a drop. **Fix:** `PlayerEdgeMixin` redirects that call:
no collision only if ULTRAKILL boxes are clear too. Sneaking should now stop at ULTRAKILL ledges
like at block edges. (SkyCraft instead disables the edge stop entirely.)

---

## 2026-10-13 — Playtest: materials immaculate; defaults locked by name

User: "immaculate", shading included. F9 option 1 perfect; F10 option 3 "I believe"; F11 had no
visible effect.

**From the log:** F9 [1] = Master clone of 'Metal Decoration 25' (queue 2000, opaque, vertex-lit).
F10 options were [1] Master clone of 'SisyHead3' (an ENEMY material: queue 2450, _BlendMode 1,
ALPHA_TEST, cull off, NOT vertex-lit), [2] Sprites/Default, [3] Unlit/Texture. Option 3 ignores
vertex colour and alpha, so it is a surprising pick; flagged to the user. F11 did nothing because
the test had no translucent quads: in 26.3 plain glass is CUTOUT; only stained glass / ice / water
are TRANSLUCENT. Master variants in the level: 40+ logged; transparent ones are queue 3000,
_BlendMode 2, src 5 / dst 10, keyword TRANSPARENCY.

**Change:** defaults chosen by NAME (lists vary by level): solid = first (Master opaque clone),
cutout = "Unlit/Texture" as requested. Added F10 [4]: the solid layer's vertex-lit Master clone
switched to cutout with SisyHead3's measured settings (queue 2450, _BlendMode/_SrcBlend/
_DstBlend/_ZWrite copied, ALPHA_TEST) — expected best; appended so existing positions keep.

User: F11 keep the first option (Master clone of the level's transparent material, e.g. 'Mulch'),
"and that should be it". All three layer defaults settled: solid = Master opaque clone,
cutout = Unlit/Texture, translucent = Master transparent clone. F9/F10/F11 remain as a runtime
comparison tool. (F10 [4], the vertex-lit cutout clone, stays available one press away.)

User confirmed the sneak fix: sneaking works on ULTRAKILL floors, running too.

---

## 2026-10-13 — Milestone E: placing blocks on ULTRAKILL geometry (awaiting playtest)

**Design (after SkyCraft's SkyClip, MIT).** `EntityPickMixin` redirects the single
`Level.clip` call in `Entity.pick(DFZ)` (verified: exactly one), for players only.
`UkPick.refine` also raycasts the ULTRAKILL boxes (`UkCollision.raycast`, slab test; boxes the
eye starts inside are skipped) and keeps the nearer hit. An ULTRAKILL hit becomes
`UltrakillHitResult` whose block position is the EMPTY cell on the near side of the surface
(location stepped 1e-4 along the face normal). Vanilla then does the rest:
- right click with a block: the cell is air, air is replaceable, so BlockItem places INTO it;
  the server's checks pass (location within 1.0000001 of the cell centre per axis; reach);
- left click: verified in bytecode that `startAttack` and `continueAttack` both test `isAir()`
  before any destroy call, so ULTRAKILL geometry only gets a swing;
- the block result also caps entity targeting, so walls will block hitting through them.
Grid snapping: a surface partway through a cell (floor top 64.3) puts the block in that cell,
slightly embedded rather than floating. Python port check: floor -> cell above, wall from
either side -> the empty side, eye inside a box -> no hit.

**Outline.** New `SELECTION` slot (Minecraft -> ULTRAKILL; appended after LEVEL, rings/table
shift, layout check passes): kind 0 none, 1 a Minecraft block's outline-shape bounds, 2 the
placement cell on ULTRAKILL geometry (only while holding a block); inflated 0.002. ULTRAKILL
draws it as a 12-edge line mesh under the world root, black at 40% (Sprites/Default).

Not yet: block-breaking cracks (textures are outside the block atlas), dropped items.

---

## 2026-10-13 — Playtest: placing works; pitch was inverted all along; guns off (awaiting playtest)

User: blocks can be placed, but "Minecraft's and ULTRAKILL's up and down are reversed": looking
down places a block above. Also: disable ULTRAKILL's guns, they get in the way.

**Pitch — measured, not derived.** Pairing ULTRAKILL's logged `mouse dx,dy` with the next
`puppet camera: mc ... pitch=` change: 13/13 samples show forwarded mouse-UP made Minecraft's
pitch INCREASE (look down). Cause: Unity's pointer delta is +up; Minecraft's accumulatedDY is
screen coordinates, +down; it was forwarded unflipped. Stage 1's "up/down reversed" report was
then fixed by copying pitch WITHOUT negation, which cancelled the error on screen — the camera
felt right while Minecraft looked the opposite way. Nothing used Minecraft's real view until
the Milestone E crosshair raycast. This is the HANDOVER's "pitch sign set from report, overriding
a derivation" sharp edge; the original derivation (rotationX = -mcPitch) was right.
**Fix:** InputApplier flips mouse Y at the boundary into Minecraft; PuppetController maps
rotationX = -mcPitch (anchor and delta). Feel unchanged; both games now look the same way.

**Guns off:** `PuppetController.DisableWeapons` calls ULTRAKILL's own `GunControl.NoWeapon()`
(hides the current weapon, whose object carries its firing scripts; clears `activated`, which
gates switching) every frame it finds a weapon active, since `YesWeapon()` is called from ~10
places (level start, respawn, scripts). Fists untouched.

---

## 2026-10-13 — Playtest: directions correct; weapon HUD; eye offset and missed clicks (awaiting playtest)

User: directions now correct. Guns gone but their HUD remains — hide it by default. "Sensitivity"
differs / an offset between mouse and rotation-position. Left click breaks a block only sometimes.

**Missed clicks — ruled out first (bytecode):** `MouseHandler.onButton` with no screen open calls
`grabMouse()` (suppressed by our mixin) and then still reaches `KeyMapping.set` + `click`, so
replayed presses arrive. `startAttack`/`continueAttack` break when the hit block is not air.

**Cause (geometric, not yet measured):** Minecraft casts its crosshair ray from feet + eye height
(1.62 standing); ULTRAKILL's camera sat wherever its own rig puts it. Same rotation, different
origin = a different line: an offset between where you look and what Minecraft hits that grows
for near targets, and clicks that pass the block onto the floor behind. **Fix:** CAMERA slot
gains `eyeHeight` (= `getEyeHeight()`, the raycast origin's height; slot 20 -> 24 bytes, inside
its aligned space, nothing moves). `PuppetController.AlignEye` puts ULTRAKILL's camera at the
mapped feet + eyeHeight after CameraController each frame (head-bob/shake lost; sneaking now
lowers the view). Logs once how far off the old camera was — the measurement.

**Weapon HUD:** `HudController.weaponIcon` hidden on every HudController (rescanned every 120
frames), re-asserted because ULTRAKILL toggles it from its HUD settings. Fist panel kept.

---

## 2026-10-13 — Playtest: aim fixed; "a block breaks after exactly 3-4 clicks" (measuring)

User: aim now matches (and sneaking lowers the view, previously missing). Left click still
fails: a block breaks only after exactly 3-4 presses, never another number.

**Established by reading 26.3 bytecode (not yet measured):**
- `MouseHandler.onButton`, no screen: grabMouse() (suppressed) then KeyMapping.set + click — presses arrive.
- `Minecraft.startAttack`: early-outs are missTime > 0, null hit, hands busy, item disabled,
  `cannotAttackWithItem` (only items with a MINIMUM_ATTACK_CHARGE component; blocks have none),
  piercing weapons. BLOCK + not air -> `startDestroyBlock`.
- `MultiPlayerGameMode.startDestroyBlock` with `abilities.instabuild` (Creative) destroys on the
  client at once (prediction) and sends START_DESTROY; no gating.
- Server `handleBlockBreakAction` rejects as "too far" / "too high" / "spawn protection" /
  may-not-interact, reporting via `debugLogging`, which only prints with a debug flag.
- World `New World (2)` level.dat: GameType 1 (Creative).

So the 3-4 clicks are one of: click not reaching startAttack, server rejection restoring the
block, or the mesh not updating. **Diagnostics added (temporary):** `ClickProbe` logs each
forwarded left press (mode, hit type incl. ULTRAKILL, block, missTime, destroyDelay, hands
busy), each `startAttack` result (MinecraftMixin RETURN), and watches the targeted block for
20 ticks (became air / came back); `ServerBlockDebugMixin` logs every server block-action reason.

**Measured (ClickProbe):** for forwarded presses `startAttack` NEVER ran (it appeared only for
presses typed directly into Minecraft's window); `destroyDelay` on one block went 5 -> 3 -> 1 ->
break, about 2 per click; mode CREATIVE; no server rejections ("creative destroy" each time); the
block did not break on Minecraft's side either. So each click acted as "held for ~2 ticks" and
`continueAttack` counted down Creative's 5-tick destroyDelay: exactly 3-4 clicks.

**Cause (bytecode, read properly this time):** `MouseHandler.onButton` begins
`if (window == 0) return; if (window != handle) return;`. InputApplier passed 0 under a comment
(Milestone A) claiming 0 meant "no particular window, verified" — the opposite of the code.
Every replayed press since Milestone A was dropped on line one: no KeyMapping.click. Only the
separately set held state ever worked (holding mines; placement via keyUse's held branch).
`onMove` has the same guard, so its 0-window call has always been a no-op — left that way on
purpose (the camera turns via the accumulators; a live onMove would double the turning).
**Fix:** `forwardButton` passes `mc.getWindow().handle()`. Clicks now reach KeyMapping.click,
so startAttack (instant break) and startUseItem fire on the press, as in vanilla. Lesson 3 of the
HANDOVER again: an unverified "verified" comment.

**Follow-up playtest:** the window-handle fix made things worse. Left clicks still needed 3-4
presses; right-clicking a block now broke it and placed it again (break-then-replace), while
placing on ULTRAKILL geometry still worked. My misreading, too: the user had said the hand swings
on EVERY click, i.e. the press was arriving in some form.

**Measured:** Minecraft's own diagnostic line `input: button LMB=false RMB=true ... clickCounter
attack=1 use=0` — a forwarded RIGHT press incremented the ATTACK click counter; the server log
showed the same block destroyed ~4 times a second (placed by the held use, broken by the click).

**Cause:** Minecraft 26.3 numbers mouse buttons from 1. `onButton`'s tail sets isLeftPressed when
`button == 1`, isMiddlePressed for 2, isRightPressed for 3; `InputConstants.MOUSE_BUTTON_LEFT = 1`,
`MIDDLE = 2`, `RIGHT = 3`. InputApplier hard-coded GLFW's 0 = left, 1 = right. With window 0 every
press was dropped, hiding this; with the real handle a forwarded LEFT became unbound button 0 (no
click: still only the held state broke blocks, 3-4 presses) and a forwarded RIGHT became a LEFT
click. **Fix:** MOUSE_LEFT / MOUSE_RIGHT now are `InputConstants.MOUSE_BUTTON_LEFT` / `_RIGHT`.
Both bugs predate this session; the window fix only unmasked the button numbering.

User: "That actually fixed the issue for good." Click diagnostics (ClickProbe,
ServerBlockDebugMixin, the startAttack probe) removed.

---

## 2026-10-13 — Block particles in ULTRAKILL (awaiting playtest)

User asked for Minecraft's particles in ULTRAKILL so block breaking looks right.

**26.3 particle system (javap):** `ParticleEngine.particles` is a private
`Map<ParticleRenderType, ParticleGroup<?>>`; `ParticleGroup.particles` a private Queue.
Block debris is `TerrainParticle` (a `SingleQuadParticle`): `x/y/z` + `xo/yo/zo`, `quadSize`
(`getQuadSize(partial)` public), `rCol/gCol/bCol/alpha`, and protected `getU0..V1` overridden by
TerrainParticle to a random quarter patch of the block texture — in the block atlas ULTRAKILL
already holds. Other particle types use the particle atlas; not exported yet.

**Collision:** `Particle.move` collides via `Entity.collideBoundingBox(CollisionContext, ...)`, i.e.
the CollisionContext overload of `collectCollidersIgnoringWorldBorder`, unpatched until now —
debris would have fallen through ULTRAKILL floors. EntityCollisionMixin now patches that overload
too (all callers get ULTRAKILL boxes).

**Protocol:** PARTICLES table (Minecraft -> ULTRAKILL, 2048 x {x,y,z,size,u0,v0,u1,v1 f32, rgba u32},
73,744 bytes, appended last). Colour written with putInt, not packed into float bits (alpha 255
would be a NaN pattern).

**Minecraft (`render/ParticleExport`):** each frame samples live TerrainParticles via accessor
mixins (ParticleEngine/ParticleGroup/Particle/SingleQuadParticle; UV getters by @Invoker, so
TerrainParticle's override runs), position interpolated with the frame's partial tick.
**ULTRAKILL (`McWorldView.LateTick`, after the camera is placed):** one camera-facing quad per
particle in world space (centre mapped through the root, half-size x map scale), clockwise for
Unity's front face, top edge = v0; drawn with the blocks' current cutout material.

**Playtest:** particles show up. But "the whole world is diagonal" and the player falls through
the ground.

**Cause (from the log, not the particle change):** Minecraft started after ULTRAKILL, so the spawn
handshake timed out and the puppet anchored in a non-level scene (uk 0,-58.8,5). There ULTRAKILL's
own `CameraController.ApplyRotations` threw NullReferenceException every frame (1,244 warnings),
so the camera never turned to the anchor heading. `CacheUkBasis` sampled the unturned camera
(UK fwd = +Z) and its own self-check said `INVALID: sampled 67.5 deg away` — but it cached it
anyway, and `_ukBasisCached` was never reset, so the next, correct anchor at spawn kept the bad
basis. The whole MC<->UK map (movement, block placement, collision boxes) was rotated 67.5
degrees: diagonal world, floors not under the player. A latent Stage 1 bug; previous sessions
happened to make their first sample in a real level.

**Fix:** (1) an invalid sample is never stored; it retries every frame and MappingReady stays
false (collision and world drawing wait); (2) `_ukBasisCached` resets on every anchor and on
player re-acquire, so each level samples its own; (3) ApplyRotations warnings rate-limited
(1 per 600) and report which reference is null and whether NewMovement.Instance is our player.

User confirmed: world upright again, particles and clicks work.

---

## 2026-10-13 — Particle shading (awaiting playtest)

Particles were drawn with the blocks' cutout material, the user's choice `Unlit/Texture`: no
vertex colour, no scene light. Blocks look shaded because Minecraft bakes face shade + AO + tint
into vertex colour and the solid material (Master clone, VERTEX_LIGHTING, fog) lights it. Particles
now always use the vertex-lit cutout material (F10 [4]: the solid layer's Master clone + alpha test),
independent of the blocks' cutout choice; billboard normals face the camera for the vertex lighting.

---

## 2026-10-13 — HUD milestone, first pass (awaiting playtest)

Native Minecraft HUD in ULTRAKILL; no images cross.

**Verified in 26.3:** HUD sprites are in the GUI atlas (`AtlasIds.GUI`): hud/hotbar,
hotbar_selection, hotbar_offhand_left, crosshair, experience_bar_background/progress,
heart/container|full|half, food_empty|half|full, armor_empty|half|full. `Inventory.getItem(i)`,
`getSelectedSlot/setSelectedSlot` public. Item icon = `ItemModelResolver.updateForTopItem(..., GUI)` +
`ItemStackRenderState.pickParticleMaterial`. Quads expose `materialInfo().isTinted()/tintIndex()`;
`BlockColors.getTintSource(state, index).color(state)`. `MouseHandler.onScroll` has the same
`window == 0 -> return` guard as onButton (so the real handle from the start). No F1 hide-GUI flag
is exposed on Options/Minecraft/Gui in 26.3; `hideGui` is sent as 0 for now.

**Protocol:** INPUT gains `hotbarSeq`, `hotbarSlot` (u8) and `scrollTotal` (f32): counters, so a
missed sample loses nothing (INPUT 31 -> 37 bytes; later offsets shift; SelfTest's copied offsets
and size updated). STREAM gains EXTRA_ATLAS (items / gui) and GUI_SPRITES (name -> UV rect + pixel
size). New HUD table (Minecraft -> ULTRAKILL): selected, game mode, health/max/absorption, food,
armour, XP level/progress, air, and 10 item records (9 hotbar + offhand): kind (flat / cube), count,
durability, atlas, top & side UVs, top & side tints.

**Minecraft:** `HudExport` each frame; icons cached per item: full-cube blocks -> top/side sprites
and tints from their model (isometric cube in ULTRAKILL); others -> the GUI item model's sprite.
Resync now also sends the item and GUI atlases and the HUD sprite table.
`InputApplier.applyHotbarAndScroll`: slot via setSelectedSlot; wheel via onScroll(handle, 0, steps).

**ULTRAKILL:** keys 1-9 and `Input.mouseScrollDelta` forwarded. `HudView` draws in OnGUI with GL
quads (Sprites/Default, pixel matrix y-down) at Minecraft's automatic GUI scale, positions from
26.3's Gui layout; creative/spectator show only hotbar + crosshair. Counts and XP level via
GUI.Label (Unity's font for now, with Minecraft-style shadow). Hides HudController.gunCanvas
(ULTRAKILL's health/stamina panel) and ULTRAKILL's Crosshair objects. csproj gains
UnityEngine.IMGUIModule, UIModule and TextRenderingModule references.

User: everything in the HUD first pass works. Requests next: Minecraft's font, real non-cube item icons, and ALL of the HUD (animations etc.).

---

## 2026-10-13 — Whole Minecraft GUI replayed natively (awaiting playtest)

User asked for Minecraft's font, real non-cube icons, and ALL of the HUD (e.g. heart animations
on damage). Rebuilding Gui element by element would never be complete and would drift, so the
hand-built HUD (HudExport / HUD table / HudView drawing) is replaced by a capture of what
Minecraft's own GUI draws.

**26.3 GUI pipeline (javap):** the GUI is collected into a `GuiRenderState`. `GuiRenderer.render()`
-> `prepare()`: `prepareText` turns texts into glyph elements, `prepareItemElements` renders items
into a GPU item atlas and queues blits via `submitBlitFromItemAtlas(item, slot)`;
`addElementsToMeshes` then calls `addElementToMesh(GuiElementRenderState)` for every element in final
order. Every element has `buildVertices(VertexConsumer)`, `textureSetup().texure0()`, scissor.
Layout comes from `Window.getGuiScaledWidth/Height()` (via `GuiGraphicsExtractor.guiWidth()`).
Items: `ItemStackRenderState.submit(pose, SubmitNodeCollector, light, overlay, 0)` (the call
`GuiItemAtlas.drawToSlot` makes after translate(slot centre) + scale(slot size)); a plain layer
arrives as one `submitItem(pose, ctx, light, overlay, outline, tints, ItemQuads, foil)` after
`applyTransform` (ItemTransform + localTransform). Fonts: `FontTexture` (256x256) `add()` reserves a
slot and calls `GlyphBitmap.upload(x, y, texture)` once; bitmap glyphs keep CPU pixels in
`BitmapProvider$Glyph.imageData.image` at offsetX/offsetY (upload is a GPU buffer copy, not
writeToTexture, so writeToTexture hooks would miss it).

**Minecraft:** `GuiLayout` + WindowMixin answer getGuiScaledWidth/Height/getGuiScale for
ULTRAKILL's screen (sent in INPUT; Window.calculateScale's rule with the player's GUI Scale option).
`GuiRendererMixin` (render HEAD/RETURN, submitBlitFromItemAtlas, addElementToMesh) feeds
`GuiCapture`, a VertexConsumer that records every quad (x, y, u, v, rgba, texture key); an item
atlas blit is swapped for the item's model quads via our own SubmitNodeCollector, projected like
drawToSlot (slot centre + 16 px/unit, y up, then the item's GUI pose), depth-sorted, lit like the
GUI (flat items full bright; block items 0.4 + 0.6 x two directional lights). Special-renderer
items (chests, banners, heads) are not drawn yet. `GuiTextures`: atlas textures by identity ->
GUI/BLOCKS/ITEMS keys; font sheets mirrored via `FontTextureMixin` (@Redirect of the upload) and
streamed as STREAM_MSG_TEXTURE (new sheets whole, then dirty-rect patches); other textures are
logged once and their quads skipped. Non-bitmap glyph types (unifont, TTF) logged, not mirrored yet.

**Protocol:** INPUT += screenWidth/screenHeight (u16; generator gains u16); STREAM_MSG_TEXTURE;
GUI table (32,768 vertices x 24 bytes) replaces the HUD table; GUI_TEX_* keys.
**ULTRAKILL:** `HudView` replays the table with GL quads in OnGUI at Minecraft's GUI scale,
switching texture on key change; mirrored textures created/patched from the stream (rows in atlas
order, no flip). Status file gains guiVertices.

**Crash at startup (my bug):** `IllegalStateException: Texture does not exist` from
`AbstractTexture.getTexture()` in `GuiTextures.key`, called by the capture during Minecraft's first
loading screen, before the atlases' GPU textures exist. Fixes: capture only while ULTRAKILL is live
(skips startup too); atlas lookup catches not-ready and retries next frame without caching; every
capture hook catches its own exceptions (element skipped, logged once) so Minecraft's GUI can
never be broken by it.

User confirmed: the replayed Minecraft GUI is fine (crash fix included).

---

## 2026-10-13 — Mouse and keyboard passthrough; Minecraft's pause menu (awaiting playtest)

User: GUI fine. Needs mouse passthrough (also for the inventory), T (chat) and / (command) in
Minecraft, and ~ for Minecraft's Esc menu ("is having both pause menus a good idea?"). Answer:
yes, but Minecraft's pause screen freezes its single-player world while ULTRAKILL would keep
running, so ULTRAKILL's time is frozen while a Minecraft pause screen is open.

**Verified (26.3 bytecode):** `KeyboardHandler.keyPress(long, int, KeyEvent)` and
`charTyped(long, CharacterEvent)` start with the same `window == 0 -> return` guard as onButton;
screens read the cursor as `xpos * getGuiScaledWidth() / getScreenWidth()`;
`OptionsManager.Update` begins `if (frozen) return;` (ULTRAKILL's own switch for ignoring its
pause key); `FistControl.NoFist/YesFist` public.

**Found:** the UK_EVENTS ring (in the protocol since day one, never used) would have thrown on its
first push: C# `TryPushEvent` wrote the 24-byte padded record size from a 20-byte struct. Fixed
(writes the struct's size; barrier before publishing head); Java pop gets an acquire fence and an
unsigned modulo. Generator now emits event kinds from its table (was hard-coded twice).

**Protocol:** EV_KEY (a = GLFW key, b = action | mods << 8), EV_CHAR (a = code point); INPUT +=
cursorX/cursorY (GUI px) + cursorActive (50 bytes; SelfTest copy updated); GUI header +=
screenOpen, pauseScreen.

**ULTRAKILL:** `InputForwarder` maps Unity keys to GLFW codes. Outside Minecraft screens it sends
T, /, E, Q and ~ (as Esc). Inside a screen it sends every mapped key (Backspace/Delete/arrows
auto-repeat 500 ms / 50 ms), `Input.inputString` characters, the cursor in GUI pixels, and zero
look delta. `HudView.LateTick` (after ULTRAKILL's code) while a screen is open: cursor unlocked
and visible, `OptionsManager.frozen` (so Esc reaches only Minecraft), fists off; during a
Minecraft pause screen `Time.timeScale = 0`. Everything undone on close, only if we set it. The
old one-sample inventory flag is no longer sent (E is a real key event).

**Minecraft:** events replayed through `KeyboardHandler.keyPress/charTyped` with the real handle;
cursor through `onMove(handle, x, y, 0, 0)` with the inverse scaling; movement keys released and
hotbar-counter selection skipped while a screen is open; both isMouseGrabbed redirects now
require no screen open (else moving the cursor in an inventory would turn the player).

**Playtest: no new key did anything.** Measured: the shared file showed 152 events pushed and all
popped; the new diagnostics showed every press reaching `keyPress` with no screen open (E = 69,
Esc = 256, / = 47, Q = 81) and no screen ever opening — not even the inventory (keymapping path),
let alone the pause menu (direct path). Static reading of `keyPress` said Esc must reach
`pauseGame`; no exceptions were thrown.

**Cause: Minecraft 26.x input is SDL3, not GLFW.** `KeyEvent.isEscape()` is `input() == 41`;
`InputConstants.KEY_ESCAPE = 41, KEY_E = 8, KEY_T = 23, KEY_SLASH = 56, KEY_GRAVE = 53, ...` (SDL3
scancodes), modifiers `MOD_SHIFT = 3, MOD_CONTROL = 192, MOD_ALT = 768` (SDL bits; ALT does not fit
the 8 bits the event used). The forwarder sent GLFW key codes: E (69) arrived as F12, Q (81) as the
Down arrow, Esc (256) as nothing. The earlier "26.3 numbers mouse buttons from 1" finding is the same
fact (SDL_BUTTON_LEFT = 1, RIGHT = 3), misattributed at the time; comment corrected.
**Fix:** Unity key -> SDL3 scancode table (A=4..Z=29, 1=30..9=38, 0=39, Return 40, Esc 41, ...,
LCtrl 224, LShift 225, LAlt 226, RCtrl 228, RShift 229, RAlt 230); SDL modifier bits; modifiers now
`b >>> 8` (no 8-bit truncation). Also fixed: SelfTest's copied seqEnd offset (27 -> 46). The key and
screen diagnostics stay for one confirmation run.

User confirmed: keyboard and mouse passthrough work (inventory, chat, commands, pause). Open: Backspace does nothing; inventory background panel not rendered.

### Backspace and standalone GUI textures

- **Backspace / Delete / arrows in text fields.** A 26.x `KeyEvent` carries both the SDL scancode
  (bindings, `isEscape`) and the SDL keycode (`shortcutKey()`, which `EditBox` switches on: 8
  Backspace, 127 Delete, 0x4000004F Right...; verified with javap). We passed keycode 0. The KEY
  event now packs a = SDL keycode, b = action | mods << 8 | scancode << 22; ULTRAKILL derives the
  keycode by SDL's rule (printable = US character, else scancode | 1 << 30).
- **Inventory / container / menu backgrounds.** These are standalone PNG textures, not atlas
  sprites, so they were skipped. Their GPU texture label is the resource path; `GuiTextures` now
  reads that PNG from Minecraft's resource manager (player's install / resource packs), mirrors it
  whole and streams it like a font sheet. GUI textures in ULTRAKILL now use Repeat wrap, since menu
  backgrounds tile with UVs beyond 1.

User: ULTRAKILL's view "completely broken"; a translucent overlay with a stray "Crafting" label while
neither game was in a world. Causes and fixes:
- `misc/vignette.png` became mirrorable, and the vignette is drawn with a multiply blend; replayed
  with alpha blending it is an opaque full-screen sheet. GuiCapture now keeps only elements whose
  pipeline blends as translucent / premultiplied / invert, and logs skipped pipelines once.
- Capture now also requires `mc.level != null` (title screen, world list: empty table).
- HudView ignores a GUI table older than 2 s (a stopped Minecraft or a previous session's bridge
  file otherwise leaves its last frame on screen).

User confirmed: Backspace/editing keys work, inventory and menu backgrounds render, the whole GUI
is replayed correctly, and ULTRAKILL's view is intact. Temporary key and screen diagnostics removed.

### H lock removed; first-person hands; the inventory's player model

- **H lock removed** (user: no use for it). Spawn sync and the void net stay; `KEY_HOLD_TOGGLE`
  (bit 7) is retired.
- **ModelCollector** (render/): a SubmitNodeCollector that keeps submitted entity models, model
  parts and item models as posed, lit, textured quads. Models are built on the spot as
  ModelFeatureRenderer would (setupAnim, renderToBuffer through the UvMapping). Model textures come
  from the RenderType's Sampler0 location (accessors on RenderType.state / RenderSetup.textures),
  mirrored via `GuiTextures.keyForLocation`: resource PNGs (default skins, armour) or, for
  downloaded skins, the DynamicTexture's CPU pixels.
- **Hands:** after `FirstPersonHandsAndItemsRenderer.submitHandsWithItems`, the same call is
  replayed into a ModelCollector whose post-transform is the camera's view rotation (the pose stack
  carries its inverse), so quads arrive in view space, lit with the world lights and the light
  level. New bridge table HAND (8192 vertices: x,y,z,u,v,rgba,tex + hud FOV), written at every
  return of `GameRenderer.renderItemInHand` (empty when Minecraft draws no hands). ULTRAKILL draws
  it in OnGUI before the GUI, with Minecraft's hand projection and UI/Default at ZTest Always.
- **Inventory player:** PictureInPictureRenderer.prepare HEAD, for a GuiEntityRenderState, rebuilds
  the PiP pose in GUI pixels (box centre, scale(s,s,-s), translation, rotation, camera override)
  and submits the entity into a ModelCollector; the quads replace the PiP blit when GuiCapture
  reaches it (matched by position, like items).

User confirmed: hand and inventory player model render; the inventory model has no issues. Held
items did: flat (generated) items appeared doubled and blocks showed one side inside out. Cause:
painter's order by average depth fails up close in perspective (a block's side sorts in front of
its face; a flat item's back face covers its front), and the hand quads were not culled. Fix:
hand quads now drop back faces (counter-clockwise front, as GL culls) and sort by distance from
the eye. Also removed the local username from docs and comments.

User confirmed: held items render correctly; H lock removal, hands and the inventory player model all good.

### F5 perspective and block-breaking cracks

- **F5** is forwarded to Minecraft (its own keyTogglePerspective cycles the camera). CAMERA gains
  `cameraType` (0 first, 1 behind, 2 front) and `cameraDistance` (Minecraft's eye-to-camera
  distance, already clipped by its blocks). PuppetController.ThirdPerson moves ULTRAKILL's camera
  (the CameraController object itself) behind or in front of the eye, sphere-casting against
  ULTRAKILL's Environment layers with Minecraft's 0.1-block margin; the front view turns it 180
  degrees about its own up axis (= yaw + 180, pitch mirrored). Rotation is rebuilt by
  CameraController every frame, so the yaw/pitch mapping is untouched; Minecraft aims from the eye.
- **WORLD_MODELS table** (16384 vertices, Minecraft block coords, unlit): `render/WorldModels`
  fills it every frame with (a) the player's body in third person, via
  `EntityRenderDispatcher.extractEntity` + `submit` at its world position into a ModelCollector,
  and (b) cracks: LevelRendererMixin copies `blockBreakingRenderStates` at the head of
  `submitBlockDestroyAnimation`; each breaking block's model quads are lifted 0.002 blocks along
  their face and textured with `textures/block/destroy_stage_N.png`, UVs laid over the face like
  Minecraft's sheeted decal. ULTRAKILL builds one mesh under the world root (sections' triangle
  order), one submesh per texture with a clone of the vertex-lit cutout material.

User: keys dead again; cracks turn the block white; third-person skin not right (two-layer slim
skin). Findings and fixes:
- **Keys:** CAMERA grew by 8 bytes, which moved every ring and table after it. The bridge file
  persists between runs, so the UK_EVENTS head/tail were read from inside an old record:
  ULTRAKILL saw the ring as permanently full and dropped every key (the Minecraft tick log's
  `dropped` counter climbing to 94). The header VERSION is now a fingerprint of the whole layout
  (generate.py `layout_fingerprint`); whichever game opens a file laid out by another build wipes
  and re-lays it, and logs that it did.
- **Cracks:** destroy_stage PNGs are dark/light grey on a white background with alpha 1/255,
  which Minecraft multiplies in (dst * src * 2). WORLD_MODELS tex keys can carry
  MODEL_TEX_MULTIPLY (1 << 24); ULTRAKILL draws those with "ULTRAKILL/Legacy Shaders/Particles/
  Multiply (Double)" and a half-grey vertex colour, which works out to exactly dst * tex * 2.
- **Skin:** the downloaded skin is a normal 64x64 slim skin with hat and outer layers. Not yet
  explained; WorldModels now logs every submitted model (render type, texture key, tint, quads)
  on each switch into third person.

User: keys back. Holding left click froze ULTRAKILL until the block broke; cracks invisible;
third-person outer skin layer draws its transparent pixels as opaque black (body alone is fine).
- **Freeze:** `Shader.Find` returned null for both multiply shaders (they are loaded but not
  "always included"), so every frame with a crack logged "crack shader MISSING" and threw from
  `new Material(null)` (21,687 log lines in one session). The crack shader is now looked up once
  among `Resources.FindObjectsOfTypeAll<Shader>()`, falling back to alpha blending; with no
  shader at all, crack quads are skipped instead of throwing.
- **Outer layer:** the body report shows one `entity_translucent` PlayerModel submission (72
  quads, the downloaded skin), so both layers do arrive. The vertex-lit cutout is a clone of an
  opaque Master material with ALPHA_TEST switched on, but it kept the opaque material's alpha
  threshold, so nothing was discarded. Every cutoff-like property of the Master shader is now set
  from the cutout material (or 0.1); the shader's property names are logged once.

User: cracks perfect (done). Outer skin layer still opaque black. The Master shader's property list
(logged) has no cutoff at all: its alpha test exists only as compiled variants (ALPHA_TEST), and the
lit cutout asks for ALPHA_TEST together with VERTEX_LIGHTING, a combination apparently not compiled,
so Unity picks a variant that never discards. Models now use a clone of a material ULTRAKILL itself
alpha-tests with (vertex-lit if one exists, otherwise e.g. an enemy's cutout material), cleaned of
level effects. The dead cutoff-property code was removed.

User also reports Minecraft-side ground ~1 block lower for the last few commits. Diffs of every
commit since the HUD touch no mapping code (WorldMesher only gained atlas/sprite sends; McWorldView
only textures; PlayerSafety only lost the H lock). The anchor's measured feet offset does vary
between anchors (-1.500 and -1.177 units in one session), but that is ~0.17 blocks. Asked the user
which ground and how it shows.

User confirmed: F5 (both views, camera clipping), third-person body with transparent outer layer, cracks, and keys all work. The ground offset comes and goes between level resets (anchor-time measurement), not from a code change.

### F3, middle click; anchor feet on the floor

- **F3** joins the gameplay keys (down and up, so Minecraft toggles the overlay on release).
  While F3 is held every other key also goes to Minecraft, so F3 combinations work through
  Minecraft's own keyDebugModifier mapping; each combination key gets its release too.
- **Middle click**: new event kind MOUSE_BUTTON (a = SDL button, b = press/release), replayed
  through `MouseHandler.onButton` like left/right; works in the world (pick block) and in screens.
- **Ground shifting between resets:** one session's anchors logged capsule feet offsets from
  -0.74 to -1.50 units and anchor heights 0.8 units apart on the same level (the capsule's height
  animates at level start, and the player may still be dropping in). The anchor now raycasts
  down (ULTRAKILL Environment layers, up to 3 blocks) and puts the feet on the floor under spawn;
  Minecraft's player is held at spawn meanwhile, so spawn maps onto that floor every time.

User confirmed: F3 (and combinations), middle click, and blocks staying put across level resets.

### Entities, block entities; red water; fire

User: ground shift not fixed after all (and slipping through ULTRAKILL floors seems likelier when
it happens) - back burner until they can characterise it. Requests: chests/beds, fire, red water,
all of Minecraft's mobs and entities.
- **Entities:** WorldModels extracts and submits every entity within 64 blocks
  (`entitiesForRendering`, `extractEntity`, `submit` at its world position) into ModelCollector,
  rather than taking Minecraft's frame (Minecraft culls to its narrower view). The local player
  still only in third person. ModelCollector now also takes `submitBlockModel` (falling blocks,
  TNT, minecart contents). WORLD_MODELS capacity 16384 -> 65536 vertices.
- **Block entities:** chests, beds, signs, banners, ... via `BlockEntityRenderDispatcher
  .tryExtractRenderState` (+ the globally-rendered variant for beacons) and `submit`, for the
  block entities of the chunks within 4 of the player. Their textures live in other atlases
  (chest, bed, sign, ...); `GuiTextures.keyForLocation` now mirrors any TextureAtlas through
  AtlasExport (now public, `rgba()`), after mapping the fixed GUI/block/item keys first.
- **Red water:** the translucent template ('Mulch') carries VERTEX_BLENDING, kept because the
  keyword filter kept anything containing "BLEND". It reads vertex colours as masks for a second
  texture; dropped.
- **Fire (and other cut-out blocks):** the cut-out layer defaulted to Unlit/Texture, which has
  no alpha. The alpha-tested ULTRAKILL material used for models is now a cut-out candidate and
  the default (F10 still cycles); particles use it too.

User confirmed: mobs, items, tridents, arrows, chests and beds render; water blue; fire correct (static); grass, glass and water transparency fine; performance fine. Open: chests/beds in the hotbar, animated textures, entity collision with ULTRAKILL geometry.
