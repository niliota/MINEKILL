using System;
using BepInEx;
using BepInEx.Logging;
using UnityEngine;
using UltraCraft.Bridge;
// UnityEngine also has a Logger type, so qualify BepInEx's explicitly.
using BepLogger = BepInEx.Logging.Logger;

namespace UltraCraft
{
    /// <summary>
    /// Owns the bridge and keeps it alive.
    ///
    /// It deliberately does NOT live on the BepInEx plugin GameObject. BepInEx 5
    /// creates that object with HideFlags.None, so ULTRAKILL's scene change at boot
    /// destroys it, and OnDestroy fires in frame 0, tearing the bridge down seconds
    /// after it opened. An object created with HideFlags.HideAndDontSave is not
    /// destroyed by scene loads, so the heartbeat survives.
    /// </summary>
    /// <summary>
    /// Runs after every other MonoBehaviour, including ULTRAKILL's CameraController.
    /// ULTRAKILL's camera accumulates the real mouse into rotationX/rotationY during its
    /// own LateUpdate, so anything we do in an ordinary Update is overwritten. Running
    /// last gives us the final word on where the camera looks.
    /// </summary>
    [DefaultExecutionOrder(10000)]
    [AddComponentMenu("")]
    public sealed class BridgeHost : MonoBehaviour
    {
        /// <summary>
        /// BepInEx's own log source rather than UnityEngine.Debug.Log.
        /// UnityEngine.Debug.Log output only reaches LogOutput.log when
        /// [Logging.Disk] WriteUnityLog is true, and it ships false, so bridge
        /// messages written that way are invisible during a normal session. The
        /// whole point of logging the resolved path is to be able to see it.
        /// </summary>
        private static readonly ManualLogSource Log =
            BepLogger.CreateLogSource("UltraCraft");

        private static BridgeHost _instance;

        /// <summary>Shared log source, so other components report consistently.</summary>
        internal static ManualLogSource LogSource => Log;

        /// <summary>
        /// Create the persistent host if it does not exist. Returns null if the
        /// bridge could not be opened, so the caller can degrade to vanilla.
        /// </summary>
        public static BridgeHost Create()
        {
            if (_instance != null) return _instance;

            var go = new GameObject("UltraCraft.Host")
            {
                // Survives scene loads, and does not clutter the hierarchy.
                hideFlags = HideFlags.HideAndDontSave
            };
            _instance = go.AddComponent<BridgeHost>();

            if (!_instance.Open())
            {
                UnityEngine.Object.Destroy(go);
                _instance = null;
                return null;
            }
            return _instance;
        }

        private BridgeTransport _bridge;
        private float _nextHeartbeat;
        private bool _reported;
        private bool _selfTestRan;
        private int _lastLogFrame;
        private string _lastStatus;
        private string _playerText = "";
        private const int FocusAssertFrames = 120;
        private PuppetController _puppet;
        private InputForwarder _input;
        private CollisionPublisher _collision;
        private McWorldView _world;
        private HudView _hud;
        private int _focusFrames;
        private bool _focusApplied;
        private int _lastX = int.MinValue, _lastY = int.MinValue, _lastZ = int.MinValue;

        public BridgeTransport Bridge => _bridge;

        private bool Open()
        {
            // null means: ask the ULTRACRAFT_BRIDGE environment variable, else fall
            // back to a path next to the plugin.
            _bridge = new BridgeTransport(null);
            string file = _bridge.Path;

            if (!_bridge.Open())
            {
                Log.LogError(
                    $"bridge failed to open at {file} ({_bridge.LastError}). " +
                    "Running vanilla ULTRAKILL.");
                _bridge = null;
                return false;
            }

            Log.LogInfo($"bridge open at {file} (pid {_bridge.UkPid}, " +
                        $"{Protocol.TOTAL_SIZE} bytes, awaiting Minecraft pid {_bridge.McPid})");
            return true;
        }

        private void Update()
        {
            KeepRunningWhileUnfocused();

            if (_bridge == null || !_bridge.Connected) return;

            if (Time.unscaledTime >= _nextHeartbeat)
            {
                _nextHeartbeat = Time.unscaledTime + 0.1f;
                _bridge.TickHeartbeat();
            }

            // Liveness is decided by heartbeat freshness, NOT by a non-zero pid.
            // A game that was hard-killed leaves its pid and last heartbeat behind on
            // the bridge, so `pid != 0` alone once made this report
            // "HANDSHAKE OK, Minecraft pid N" for a process that had been dead for
            // twenty minutes. The pid is a label; the heartbeat is the evidence.
            if (!_reported && _bridge.McPid != 0 && !_bridge.McStale())
            {
                _reported = true;
                Log.LogInfo($"HANDSHAKE OK, Minecraft pid {_bridge.McPid} " +
                            $"last heartbeat {_bridge.McHeartbeat} ms");
                RunSelfTest();
            }

            TickPuppet();
            ReadPlayerSlot();

            // Periodic liveness line, so a frozen heartbeat is visible in the log.
            if (Time.frameCount - _lastLogFrame >= 600)
            {
                _lastLogFrame = Time.frameCount;
                Log.LogInfo($"tick frame={Time.frameCount} ukPid={_bridge.UkPid} " +
                            $"mcPid={_bridge.McPid} mcHb={_bridge.McHeartbeat} " +
                            $"flags={_bridge.UkFlags}");
                WriteStatusFile();
            }
        }

        /// <summary>
        /// Verify that the bytes written into the bridge land where a reader will look
        /// for them, once, as soon as both sides are talking.
        ///
        /// Run at handshake time rather than on a timer: this is a one-off correctness
        /// check on a fixed layout, and the interesting moment is the first one, before
        /// anything has been built on top of it. See <see cref="SelfTest"/> for why an
        /// earlier revision shipped a four-byte skew here that only showed up as
        /// Minecraft walking forward forever.
        /// </summary>
        private void RunSelfTest()
        {
            if (_selfTestRan) return;
            _selfTestRan = true;

            try
            {
                if (!SelfTest.Run(_bridge, Log))
                {
                    Log.LogError(
                        "selftest: FAILED. The bridge layout is not what Minecraft expects, " +
                        "so input and camera data will be read from the wrong offsets. " +
                        "Treat anything the controls do as meaningless until this passes.");
                }
            }
            catch (Exception e)
            {
                Log.LogError($"selftest: could not run: {e.GetType().Name}: {e.Message}");
            }
        }

        /// <summary>
        /// Drive ULTRAKILL's player from Minecraft. Retried for a while because the player
        /// object does not exist yet during early startup.
        /// </summary>
        private void TickPuppet()
        {
            if (_bridge == null || !_bridge.Connected) return;

            // One long-lived instance. It retries initialisation internally, because the
            // player object does not exist until a level has loaded.
            if (_puppet == null) _puppet = new PuppetController(_bridge, Log);
            _puppet.Tick();

            // After the puppet, so this frame's mapping is the one the boxes are built in.
            if (_collision == null) _collision = new CollisionPublisher(_bridge, _puppet, Log);
            _collision.Tick();

            if (_world == null) _world = new McWorldView(_bridge, _puppet, Log);
            _world.Tick();

            if (_hud == null) _hud = new HudView(_bridge, _world);
            _hud.Tick();
        }

        /// <summary>
        /// Late stage of the frame: aim the camera from Minecraft, then sample
        /// ULTRAKILL's input for the next frame.
        ///
        /// Runs last (see the execution order on this class) so ULTRAKILL's own camera
        /// input has already been applied and is then overwritten.
        /// </summary>
        /// <summary>Minecraft's HUD, drawn natively over ULTRAKILL's frame.</summary>
        private void OnGUI()
        {
            if (_bridge == null || !_bridge.Connected || _hud == null) return;
            _hud.OnGUI();
        }

        private void LateUpdate()
        {
            if (_bridge == null || !_bridge.Connected) return;

            if (_puppet != null) _puppet.TickCamera();
            // After the camera is placed, so particles face where the camera actually looks.
            if (_world != null) _world.LateTick();

            // ULTRAKILL holds OS focus, so Minecraft never sees the keyboard. Sampled
            // here so the final mouse position of the frame is used.
            if (_input == null) _input = new InputForwarder(_bridge, Log);
            _input.Tick();
        }

        /// <summary>
        /// Stop ULTRAKILL from freezing when its window loses focus.
        ///
        /// ULTRAKILL has no OnApplicationPause or OnApplicationFocus code and never
        /// mentions runInBackground, so this pause is Unity's own, driven purely by
        /// Player Settings. Left alone, the game stops ticking entirely when you switch
        /// to another window: the frame counter freezes, the heartbeat stops and the
        /// whole bridge goes quiet. That is indistinguishable from a deadlock, which is
        /// exactly how it was first mistaken for one.
        ///
        /// Two things were needed to make this stick, both found by testing:
        ///
        ///   - setting it once in Awake or Start does not work. BepInEx runs those
        ///     during its own startup, before Unity applies Player Settings, and Unity
        ///     then overwrites the value. It has to be re-asserted from a loop that is
        ///     still running afterwards, which is why this lives in Update.
        ///   - the BepInEx plugin object is destroyed in frame 0, so a separate
        ///     plugin's Start never runs. Only this persistent host has an Update that
        ///     survives.
        ///
        /// Harmless for vanilla play: a focused window ticks either way. It only changes
        /// what happens in the background, which the mod needs in order to keep the
        /// bridge alive while Minecraft holds focus.
        /// </summary>
        private void KeepRunningWhileUnfocused()
        {
            if (_focusApplied)
            {
                // Re-assert cheaply if anything ever resets it.
                if (!UnityEngine.Application.runInBackground)
                    UnityEngine.Application.runInBackground = true;
                return;
            }

            if (++_focusFrames >= FocusAssertFrames)
            {
                _focusApplied = true;
                Log.LogInfo($"runInBackground settled at frame {_focusFrames}: " +
                            $"{UnityEngine.Application.runInBackground}");
                return;
            }

            if (!UnityEngine.Application.runInBackground)
            {
                if (_focusFrames % 20 == 0)
                    Log.LogInfo($"runInBackground was false at frame {_focusFrames}; re-asserting");
                UnityEngine.Application.runInBackground = true;
            }
        }

        /// <summary>
        /// Write a tiny status file next to the bridge.
        ///
        /// BepInEx's on-disk logger stops flushing partway through ULTRAKILL's level
        /// preload under Wine and never recovers, so log-based diagnostics stop
        /// working a minute into a session. This file does not, so it is the
        /// dependable way to see what the ULTRAKILL side believes the bridge state
        /// is. Rewritten only when the content actually changes, so it costs almost
        /// nothing.
        /// </summary>
        private void WriteStatusFile()
        {
            try
            {
                string dir = System.IO.Path.GetDirectoryName(_bridge.Path);
                string path = System.IO.Path.Combine(dir, "ultrakill-status.txt");
                string body =
                    $"frame={Time.frameCount}\n" +
                    $"bridge={_bridge.Path}\n" +
                    $"ukPid={_bridge.UkPid}\n" +
                    $"mcPid={_bridge.McPid}\n" +
                    $"mcHeartbeat={_bridge.McHeartbeat}\n" +
                    $"mcStale={_bridge.McStale()}\n" +
                    $"flags={_bridge.UkFlags}\n" +
                    // Live state, not history: a peer that left cleanly zeroes its
                    // pid, and a peer that was killed leaves a pid whose heartbeat has
                    // stopped. Both mean "not connected".
                    $"handshake={_bridge.McPid != 0 && !_bridge.McStale()}\n" +
                    $"puppetReady={_puppet != null && _puppet.Ready}\n" +
                    $"puppetScale={(_puppet != null ? _puppet.Scale : 0f):F5}\n" +
                    (_puppet != null && _puppet.Ready
                        ? $"puppetFrames={_puppet.Frames}\n" +
                          $"puppetDesired={_puppet.LastDesired.x:F3},{_puppet.LastDesired.y:F3},{_puppet.LastDesired.z:F3}\n" +
                          $"puppetActual={_puppet.LastActual.x:F3},{_puppet.LastActual.y:F3},{_puppet.LastActual.z:F3}\n" +
                          $"puppetYaw={_puppet.LastUkYaw:F2}\n" +
                          $"puppetPitch={_puppet.LastUkPitch:F2}\n" +
                          $"camMcYaw={_puppet.LastMcYaw:F2}\n" +
                          $"camMcPitch={_puppet.LastMcPitch:F2}\n" +
                          $"camDeltaYaw={_puppet.CameraDeltaYaw:F2}\n" +
                          $"camDeltaPitch={_puppet.CameraDeltaPitch:F2}\n"
                        : "") +
                    (_collision != null
                        ? $"collisionGeneration={_puppet.MappingGeneration}\n" +
                          $"collisionFound={_collision.LastFound}\n" +
                          $"collisionSent={_collision.LastSent}\n" +
                          $"collisionSkippedRotated={_collision.LastRotated}\n" +
                          $"collisionSkippedOther={_collision.LastOther}\n" +
                          $"collisionOverflow={_collision.LastOverflow}\n" +
                          $"collisionSkipped={_collision.LastSkippedSummary}\n" +
                          $"collisionExact={_collision.LastExact}\n" +
                          $"collisionVoxelBoxes={_collision.LastVoxelBoxes}\n" +
                          $"voxelBlocksProbed={_collision.Voxels.BlocksProbed}\n" +
                          $"voxelBlocksSolid={_collision.Voxels.BlocksSolid}\n" +
                          $"voxelProbes={_collision.Voxels.Probes}\n" +
                          $"voxelProbeMs={_collision.Voxels.ProbeMs:F0}\n" +
                          $"voxelScanComplete={_collision.Voxels.Complete}\n" +
                          $"voxelInvalidatedBlocks={_collision.Voxels.Invalidations}\n" +
                          $"voxelMergedBoxes={_collision.Voxels.MergedBoxes}\n" +
                          $"voxelLastMergeMs={_collision.Voxels.LastMergeMs:F1}\n"
                        : "") +
                    (_world != null
                        ? $"worldSections={_world.Sections}\n" +
                          $"worldAtlas={_world.AtlasSize}\n" +
                          $"worldShader={_world.ShaderName}\n" +
                          $"streamOk={_world.Stream.Ok}\n" +
                          $"streamRecords={_world.Stream.Records}\n" +
                          $"streamBytesRead={_world.Stream.BytesRead}\n" +
                          $"streamPending={_world.Stream.Pending}\n" +
                          $"streamResync={_world.Stream.ResyncRequested}/{_world.Stream.ResyncServed}\n"
                        : "") +
                    _playerText;

                if (body == _lastStatus) return;
                _lastStatus = body;
                System.IO.File.WriteAllText(path, body);
            }
            catch
            {
                // Diagnostics must never take the game down.
            }
        }

        /// <summary>
        /// Milestone 4: read the PLAYER slot Minecraft writes, and record what it says.
        ///
        /// Nothing consumes this yet. That is the point: the architecture is proven once
        /// a real value crosses and the other side sees it, and only then is it worth
        /// building movement on top.
        ///
        /// The slot is a seqlock, so a read can fail while Minecraft is mid-write. That
        /// is expected and is not an error: the previous value simply stays current.
        /// </summary>
        private void ReadPlayerSlot()
        {
            Protocol.SlotPlayer slot;
            if (!_bridge.TryReadSlot(Protocol.OFFSET_PLAYER, Protocol.SLOTPLAYER_SIZE, out slot))
                return;

            // An all-zero slot is indistinguishable from "no data yet", because a
            // player really can stand at the origin. Until Minecraft has published a
            // tick timestamp, treat the slot as empty rather than reporting the
            // player as being at 0,0,0.
            if (slot.tickTimestamp == 0)
                return;

            // Only report when the position actually changed, so the log shows
            // movement instead of one line per frame.
            int bx = (int)Math.Floor(slot.posX);
            int by = (int)Math.Floor(slot.posY);
            int bz = (int)Math.Floor(slot.posZ);
            if (bx == _lastX && by == _lastY && bz == _lastZ) return;

            _lastX = bx; _lastY = by; _lastZ = bz;
            _playerText =
                $"mcPos={bx},{by},{bz}\n" +
                $"mcPosExact={slot.posX:F3},{slot.posY:F3},{slot.posZ:F3}\n" +
                $"mcVel={slot.velX:F3},{slot.velY:F3},{slot.velZ:F3}\n" +
                $"mcYaw={slot.yaw:F2}\n" +
                $"mcPitch={slot.pitch:F2}\n" +
                $"mcOnGround={(slot.onGround != 0)}\n" +
                $"mcHealth={slot.health}\n" +
                $"mcPlayerTick={slot.tickTimestamp}\n";
            Log.LogInfo($"player at block {bx},{by},{bz} yaw {slot.yaw:F2} pitch {slot.pitch:F2}");
        }

        private void OnDestroy()
        {
            if (_puppet != null)
            {
                _puppet.Shutdown();
                _puppet = null;
            }
            if (_bridge != null)
            {
                _bridge.Dispose();
                _bridge = null;
            }
            if (_instance == this) _instance = null;
        }
    }
}