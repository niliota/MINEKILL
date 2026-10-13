using System;
using UnityEngine;
using UltraCraft.Bridge;

namespace UltraCraft
{
    /// <summary>
    /// Drives ULTRAKILL's player from Minecraft's position.
    ///
    /// Per docs/CONTRACT.md, Minecraft is authoritative for the player. This class makes
    /// that real: ULTRAKILL's own movement is switched off and its player becomes a
    /// puppet that is moved to wherever Minecraft says the player is.
    ///
    /// The puppet stays in the world rather than being removed, which is what keeps
    /// ULTRAKILL's enemy targeting, detection, trigger volumes and projectiles working.
    /// It is the same idea SkyCraft uses with its Skyrim player ref.
    ///
    /// Nothing here is visible yet: this milestone is about the control loop, not about
    /// drawing anything.
    /// </summary>
    public sealed class PuppetController
    {
        /// <summary>Minecraft's player is 1.8 blocks tall (eye height 1.62).</summary>
        private const float MinecraftPlayerHeightBlocks = 1.8f;

        /// <summary>
        /// Whether Z needs its sign flipped when mapping Minecraft onto ULTRAKILL.
        ///
        /// MEASURED, not guessed. Both engines are Y-up, but they disagree about which way
        /// Z points relative to the direction you are facing, which is also why
        /// <see cref="UkToMcYaw"/> has to negate yaw. The position mapping has to agree
        /// with that, or forward becomes back.
        ///
        /// The evidence, from a play session where the player walked in a straight line:
        ///
        ///     Minecraft walked with a decreasing Z.
        ///     The puppet moved with an INCREASING ULTRAKILL Z.
        ///
        /// An inverted relationship on Z alone, with X and Y carrying through with the same
        /// sign, is the signature of a mirrored Z. The player's report agrees and rules out
        /// the alternatives: "Forward and Backward are backwards", with left and right
        /// reported as fine. An X or Y inversion would not produce that.
        ///
        /// SUPERSEDED, and kept only as a warning. This mirror was derived from a session in
        /// which Minecraft's yaw happened to sit at -177.00, and it was correct there. It is
        /// not correct in general: a mirror of the world Z axis only preserves "forward" at
        /// the two headings that lie along Z. Once the camera worked and yaw ranged over 55
        /// values from -177 to +110, movement broke at every other heading, which is what the
        /// player reported as "the movement directions also got messed up".
        ///
        /// The correct transform is a rotation by the yaw difference. See
        /// <see cref="McToUk"/>. Kept as a comment because the reasoning is the useful part:
        /// a measurement taken at a single heading is not a measurement of a transform.
        /// </summary>
        private const bool MirrorZAxis_DoNotUse = false;

        /// <summary>
        /// Yaw sign between the two engines' conventions.
        ///
        /// Unity is LEFT-handed, Minecraft is RIGHT-handed. A correct map between the two
        /// therefore cannot be a rotation at all; it has to reflect, and a reflection has to
        /// be absorbed somewhere. This constant says it is absorbed by the yaw sense.
        ///
        /// Deriving the correct value from ULTRAKILL's own code gave -1, and that is what
        /// shipped first. The player then reported that every camera direction was reversed,
        /// which is the direct observation that the derivation got it backwards. It is now
        /// +1, from the player's report rather than from arithmetic.
        ///
        /// This being a SIGN is what makes it safe at every heading, unlike the axis mirror
        /// this replaced, which was correct only along one axis.
        ///
        /// Note the consequence, because it is the reason this is not a free choice. With
        /// YawSign +1, ULTRAKILL's forward and Minecraft's forward differ in the sign of X,
        /// so the two engines' "right" vectors also disagree. Movement does not rely on
        /// either being derived: <see cref="McToUk"/> reads ULTRAKILL's basis from the live
        /// camera and caches it, so the handedness is absorbed by measurement and cannot
        /// contradict this constant. The two are independent, which is deliberate.
        /// </summary>
        private const float YawSign = 1f;

        private readonly BridgeTransport _bridge;
        private readonly BepInEx.Logging.ManualLogSource _log;

        private NewMovement _movement;
        private CameraController _camera;
        private Rigidbody _rb;
        private HarmonyLib.Harmony _harmony;
        private readonly System.Collections.Generic.List<System.Reflection.MethodInfo> _patched = new();

        private float _scale = 1f;

        /// <summary>
        /// Where Minecraft's world is anchored inside ULTRAKILL's.
        ///
        /// Absolute coordinates cannot be compared: Minecraft's y=70 is a normal spot in
        /// its own world but maps to y=136 in ULTRAKILL, whose levels sit around y=-76.
        /// So the first position seen is treated as "here", and only DELTAS from it are
        /// applied. This is the same anchoring SkyCraft does before collision injection
        /// exists, and it means walking works immediately regardless of where either
        /// world starts.
        /// </summary>
        private Vector3 _mcOrigin;
        private Vector3 _ukAnchor;
        private float _mcYawOrigin;
        private float _ukYawAnchor;
        private float _mcPitchOrigin;
        private float _ukPitchAnchor;
        private bool _anchored;

        /// <summary>The position requested on the previous frame.</summary>
        private Vector3 _lastDesired;
        private bool _ready;
        private int _frames;
        private int _searches;

        /// <summary>Last known Minecraft position, so a correction can be echoed back.</summary>

        public PuppetController(BridgeTransport bridge, BepInEx.Logging.ManualLogSource log)
        {
            _bridge = bridge;
            _log = log;
        }

        public bool Ready => _ready;
        public float Scale => _scale;

        /// <summary>
        /// Where the puppet was last told to be, and where it actually ended up.
        /// Exposed so the status file can prove the puppet is really being driven;
        /// Ready alone only proves it initialised.
        /// </summary>
        public Vector3 LastDesired { get; private set; }
        public Vector3 LastActual { get; private set; }
        public float LastUkYaw { get; private set; }
        public float LastUkPitch { get; private set; }

        /// <summary>
        /// Minecraft's look direction as last read, and how far it has turned since the
        /// anchor.
        ///
        /// Exposed because a static reading cannot distinguish "the camera follows
        /// Minecraft" from "the camera is stuck". An earlier status file reported
        /// puppetYaw=0.00 next to mcYaw=-177.00 and looked like a failure, but the two
        /// were equal because the player had not turned since anchoring, so it was not
        /// evidence of anything. These fields change when you turn the mouse, which is
        /// the property that actually matters.
        /// </summary>
        public float LastMcYaw { get; private set; }
        public float LastMcPitch { get; private set; }
        public float CameraDeltaYaw { get; private set; }
        public float CameraDeltaPitch { get; private set; }

        public int Frames { get; private set; }
        public bool Anchored => _anchored;

        /// <summary>
        /// True once position AND both cached bases are fixed, i.e. once <see cref="UkToMc"/>
        /// is the exact inverse of the map that moves the puppet. Before the basis is cached
        /// the forward map still uses the live camera, so geometry converted then would be
        /// in a slightly different frame from the player.
        /// </summary>
        public bool MappingReady => _ready && _anchored && _ukBasisCached && _rb != null;

        /// <summary>Bumps on every anchor, so Minecraft can discard boxes from an older mapping.</summary>
        public uint MappingGeneration => _generation;

        /// <summary>ULTRAKILL's player body, so collider queries can exclude it.</summary>
        public Rigidbody PlayerBody => _rb;

        public Vector3 UkFeetOffset => _ukFeetOffset;

        private uint _generation;

        // Spawn sync: before anchoring, ask Minecraft to move its player to its fixed spawn
        // spot and wait for the echo in PLAYER.spawnAck. Otherwise the anchor pins whatever
        // Minecraft's player happens to be doing, which in the 2026-10-13 playtest was
        // falling through the void.
        private uint _spawnRequest;
        private bool _awaitingSpawn;
        private float _spawnRequestedAt;
        /// <summary>An older Minecraft build never acknowledges; anchor anyway after this.</summary>
        private const float SpawnAckTimeoutSeconds = 5f;

        /// <summary>
        /// Capsule bottom minus rigidbody position, measured at the anchor.
        ///
        /// The puppet pins Minecraft's FEET (Entity position) to ULTRAKILL's rigidbody
        /// position, which is the capsule's middle, not its bottom. That is harmless for
        /// moving the puppet because only deltas apply, but converting ULTRAKILL geometry
        /// into Minecraft must map feet to feet, or every floor lands about half a player
        /// height too low and the player sinks into it.
        /// </summary>
        private Vector3 _ukFeetOffset;
        private bool _sawCameraLateUpdate;

        /// <summary>Find the player, measure the scale, and switch ULTRAKILL's movement off.</summary>
        public bool Initialise()
        {
            if (_ready) return true;
            return TryInitialise();
        }

        private bool TryInitialise()
        {
            try
            {
                // FindObjectsOfType only sees ACTIVE objects, and there may be no
                // player at all on the main menu. Resources.FindObjectsOfTypeAll also
                // returns inactive ones, which covers a player that exists but is
                // disabled, e.g. during level load.
                _movement = UnityEngine.Object.FindObjectOfType<NewMovement>();
                if (_movement == null)
                {
                    var all = Resources.FindObjectsOfTypeAll<NewMovement>();
                    foreach (var m in all)
                    {
                        if (m != null && m.rb != null) { _movement = m; break; }
                    }
                }

                if (_movement == null || _movement.rb == null)
                {
                    return false;
                }

                _rb = _movement.rb;
                _camera = _movement.cc;
                if (_camera == null)
                    _log.LogWarning("puppet: CameraController is null; camera will not be driven");

                MeasureScale();
                DisableNativeMovement();
                MakeKinematic();

                _log.LogInfo($"puppet: attached, scale {_scale:F4} units per block " +
                             $"(capsule {CapsuleHeight():F3} units tall), " +
                             $"rb at {_rb.position}");
                _ready = true;
                return true;
            }
            catch (Exception e)
            {
                _log.LogError($"puppet: init failed: {e.GetType().Name}: {e.Message}");
                _ready = false;
                return false;
            }
        }

        /// <summary>
        /// ULTRAKILL units per Minecraft block, measured rather than guessed.
        /// A Minecraft offset in blocks is multiplied by this to get ULTRAKILL units.
        ///
        /// SkyCraft hard-codes 1 block = 70 Skyrim units because Skyrim's player capsule
        /// happens to be about 128 units for a 1.8-block character. The same reasoning
        /// applies here, but the number has to be measured from THIS build's capsule
        /// instead of copied, because ULTRAKILL's units are its own.
        /// </summary>
        private void MeasureScale()
        {
            float height = CapsuleHeight();
            if (height > 0.01f)
            {
                _scale = height / MinecraftPlayerHeightBlocks;
            }
            else
            {
                // Fall back to the eye height if there is no capsule on this object.
                _scale = 1f / MinecraftPlayerHeightBlocks;
                _log.LogWarning($"puppet: no capsule found; using a placeholder of {_scale:F4} units per block; positions will be mis-scaled");
            }
        }

        private float CapsuleHeight()
        {
            try
            {
                var capsule = _rb.GetComponent<CapsuleCollider>();
                if (capsule != null) return capsule.height;
                var parent = _rb.GetComponentInParent<CapsuleCollider>();
                if (parent != null) return parent.height;
            }
            catch { }
            return 0f;
        }

        /// <summary>
        /// Stop ULTRAKILL moving the player itself. Its Update also applies gravity,
        /// dash, slide and jump, all of which would fight the puppet every frame.
        /// </summary>
        private void DisableNativeMovement()
        {
            try
            {
                _harmony = new HarmonyLib.Harmony("dev.ultracraft.puppet");
                foreach (var name in new[] { "Update", "FixedUpdate" })
                {
                    var mi = typeof(NewMovement).GetMethod(name,
                        System.Reflection.BindingFlags.Instance |
                        System.Reflection.BindingFlags.Public |
                        System.Reflection.BindingFlags.NonPublic);
                    if (mi == null) continue;
                    var prefix = new HarmonyLib.HarmonyMethod(
                        typeof(PuppetController).GetMethod(
                            nameof(NoOpUpdate),
                            System.Reflection.BindingFlags.Static |
                            System.Reflection.BindingFlags.NonPublic));
                    _harmony.Patch(mi, prefix: prefix);
                    _patched.Add(mi);
                    _log.LogInfo($"puppet: neutralised NewMovement.{name}");
                }
            }
            catch (Exception e)
            {
                _log.LogError($"puppet: could not patch NewMovement: {e.GetType().Name}: {e.Message}");
            }
        }

        private static void NoOpUpdate(ref bool __state)
        {
            // Replaces the original body. The player is driven from Minecraft instead.
        }

        private void MakeKinematic()
        {
            try
            {
                _rb.isKinematic = true;
                _rb.useGravity = false;
                _rb.detectCollisions = true;   // still needed: UK's walls must push back
                _rb.interpolation = RigidbodyInterpolation.None;
            }
            catch (Exception e)
            {
                _log.LogWarning($"puppet: could not make rigidbody kinematic: {e.Message}");
            }
        }

        /// <summary>
        /// Move the puppet to Minecraft's position and face where Minecraft looks.
        ///
        /// Initialisation is attempted from here rather than by the caller, so the retry
        /// counter and the log throttle live on one long-lived instance. Creating a new
        /// instance per attempt reset the throttle counter and produced thousands of log
        /// lines.
        /// </summary>
        public void Tick()
        {
            if (!_ready)
            {
                // The player object does not exist until a level is loaded, which on this
                // game takes tens of seconds of boot. Keep trying, quietly.
                _searches++;
                if (_searches == 1 || _searches % 900 == 0)
                    TryInitialise();
                return;
            }

            // ULTRAKILL destroys and recreates the player on every level load, which
            // leaves the cached rigidbody pointing at a dead object whose position reads
            // as (0,0,0). Re-acquire instead of driving into nothing.
            if (_rb == null || _movement == null)
            {
                _log.LogInfo("puppet: player object was replaced (level load); re-acquiring");
                _ready = false;
                _anchored = false;
                _awaitingSpawn = false;
                _sawCameraLateUpdate = false;
                _searches = 0;
                return;
            }

            Protocol.SlotPlayer p;
            if (!_bridge.TryReadSlot(Protocol.OFFSET_PLAYER, Protocol.SLOTPLAYER_SIZE, out p)) return;
            if (p.tickTimestamp == 0) return;

            Frames++;

            if (!_anchored)
            {
                Anchor(p);
                return;
            }

            // Yaw is deliberately NOT passed in. The horizontal mapping is a fixed linear
            // transform of the world offset, cached at the anchor, and has no term that
            // depends on where the player is looking. See McToUk.
            Vector3 desired = McToUk(p.posX, p.posY, p.posZ);
            LastDesired = desired;

            // Set the pose directly, every frame, NOT with MovePosition.
            //
            // MovePosition on a kinematic body only takes effect at the next physics step.
            // ULTRAKILL's CameraController.ApplyRotations (run by ULTRAKILL and by us every
            // LateUpdate) writes `rb.rotation = player.transform.rotation`, and the camera is a
            // child of the player. So whenever the view turned while the player moved, the
            // rotation was written to a body still at the PREVIOUS step's position, and the
            // body and camera snapped between old and new positions: the 2026-10-13 stutter
            // that only happened "when both the character and mouse is moving".
            //
            // Kinematic bodies are not pushed by colliders either way; collision is Minecraft's
            // job (collision injection), so nothing is lost by teleporting here.
            try
            {
                _rb.position = desired;
                _rb.transform.position = desired;
            }
            catch (Exception e)
            {
                _log.LogWarning($"puppet: setting position failed: {e.Message}");
                return;
            }

            Vector3 actual = _rb.position;
            LastActual = actual;
            _lastDesired = desired;

            // NO COLLISION CORRECTION IS SENT BACK, ON PURPOSE.
            //
            // This ran once and was catastrophic. It computed the Minecraft position as
            // uk/scale + mcOrigin, but the forward mapping is
            //     uk = ukAnchor + (mc - mcOrigin) * scale
            // so the inverse needs the ukAnchor term as well. Without it every
            // "correction" teleported the Minecraft player to a meaningless place, tens
            // of times a second. The logs showed it being flung to
            // (61.1, -0.015, 0.0), (40.8, 28.5, -50.0), (15.1, -0.1, 0.1) and its yaw
            // spinning 147 -> 214 -> -196 -> +164, which destroyed the camera and made
            // the player drift diagonally.
            //
            // A push-back approximation is not sound anyway: it cannot express ULTRAKILL
            // geometry to Minecraft, and SkyCraft does not do this either. It injects
            // Skyrim's collision SHAPES into Minecraft's own collision queries so
            // Minecraft's physics resolves against them directly. That is the correct
            // fix and it is a later milestone. Until then, no correction at all is far
            // better than a wrong one.

            LogOccasionally(p, desired, actual);
        }

        /// <summary>
        /// Apply the look direction.
        ///
        /// Separate from <see cref="Tick"/> and called from a LATE update on purpose.
        /// ULTRAKILL's CameraController.LateUpdate reads the real mouse and accumulates
        /// into rotationX/rotationY, then calls ApplyRotations itself. If we set those
        /// values during Update, ULTRAKILL adds its own mouse delta on top afterwards and
        /// wins, so both axes end up fighting. Running last means our values overwrite
        /// whatever it accumulated, without patching ULTRAKILL's camera at all.
        /// </summary>
        public void TickCamera()
        {
            if (!_ready) return;

            // Record that ULTRAKILL's own camera has run at least once, so anchoring
            // never reads rotationX/rotationY before they hold a real value.
            //
            // Set BEFORE the _anchored check on purpose. Anchoring waits for this flag and
            // anchoring is what sets _anchored, so testing _anchored first would deadlock:
            // the camera flag could never be set, so the anchor could never happen, so
            // this line would never be reached.
            if (!_sawCameraLateUpdate)
            {
                _sawCameraLateUpdate = true;
                _log.LogInfo(
                    "puppet: ULTRAKILL's camera has run at least once; " +
                    "anchoring on the next frame can now trust rotationX/rotationY");
            }

            if (!_anchored) return;

            Protocol.SlotPlayer p;
            if (!_bridge.TryReadSlot(Protocol.OFFSET_PLAYER, Protocol.SLOTPLAYER_SIZE, out p)) return;
            if (p.tickTimestamp == 0) return;

            LastMcYaw = p.yaw;
            LastMcPitch = p.pitch;

            ApplyLook(p.yaw, p.pitch);

            // Only now is the camera actually pointing at the anchor heading, so this is
            // the first moment the ULTRAKILL basis can be sampled meaningfully.
            TryCacheBasis();
        }

        /// <summary>
        /// Minecraft block coordinates to ULTRAKILL units.
        ///
        /// Both engines are Y-up, so Y is a plain scale. The horizontal plane is mapped by
        /// MEASUREMENT rather than by an assumed sign or a fixed axis flip.
        ///
        /// <b>Why.</b> Two earlier attempts were both wrong, and instructively so:
        ///
        /// A fixed Z mirror was derived from a session where Minecraft's yaw happened to
        /// sit at -177.00, where forward is almost exactly -Z and negating Z happens to
        /// preserve it. A mirror only preserves forward at headings lying along the mirrored
        /// axis, so it was correct at one heading and wrong at every other one. The player
        /// reported movement directions changing once the camera started working and yaw
        /// began to vary, which is exactly that failure.
        ///
        /// Deriving a rotation from yaw algebra was also wrong, repeatedly: three different
        /// hand-derivations produced three different answers, because each depended on
        /// assumptions about handedness and about how ULTRAKILL composes AngleAxis with its
        /// gravityRotation. None of those assumptions needed to be true.
        ///
        /// What this does instead: decompose Minecraft's movement into its own
        /// forward/right components using Minecraft's documented forward vector, then
        /// rebuild it from ULTRAKILL's camera basis. No convention has to be guessed,
        /// because ULTRAKILL's side is measured rather than predicted, and
        /// <see cref="LogOccasionally"/> reports both dot products so that "forward is
        /// forward" is observable rather than assumed.
        ///
        /// <b>Both bases are cached at the anchor</b>, which is what makes this a fixed
        /// linear map. Using the current yaw anywhere in here would re-express the offset in
        /// a frame that rotates with the camera, and the puppet would swing whenever the
        /// player looked around. See <see cref="CacheUkBasis"/>.
        ///
        /// <b>Why the map is applied to an offset.</b> It acts on the offset from
        /// <see cref="_mcOrigin"/> and is added to <see cref="_ukAnchor"/>. Transforming an
        /// absolute coordinate would drag the puppet across the level on every re-anchor.
        /// </summary>
        private Vector3 McToUk(float x, float y, float z)
        {
            // Deltas only. See _mcOrigin for why absolute coordinates are unusable.
            Vector3 d = new Vector3(x - _mcOrigin.x, y - _mcOrigin.y, z - _mcOrigin.z);

            // Minecraft's own basis. forward is the standard right-handed Y-up formula;
            // right is forward rotated -90 degrees about +Y, which points west at yaw 0.
            // BOTH bases are the ones cached at the anchor. The ULTRAKILL half was
            // cached already, but the MINECRAFT half was still using the current yaw,
            // which left half the coupling in place: turning the camera rotates the
            // frame this offset is expressed in, so the mapped result moved even though
            // neither the offset nor the cached basis had changed.
            //
            // That is precisely the reported symptom, including why it only appears after
            // the player has walked away from the starting point. At the anchor the offset
            // is zero, and rotating a zero vector still gives zero, so the bug is invisible
            // until there is a non-zero offset to swing.
            float forward = Vector3.Dot(d, _cachedMcForward);
            float right = Vector3.Dot(d, _cachedMcRight);

            // Rebuild from ULTRAKILL's basis, also cached at the anchor.
            Vector3 ukFwd = UkForward();
            Vector3 ukRight = UkRight();

            Vector3 mapped = new Vector3(
                (forward * ukFwd.x + right * ukRight.x) * _scale,
                d.y * _scale,
                (forward * ukFwd.z + right * ukRight.z) * _scale);

            return _ukAnchor + mapped;
        }

        /// <summary>
        /// Treat Minecraft's current position and look as "here", so the puppet starts
        /// exactly where ULTRAKILL's player already is and only moves from there.
        ///
        /// Deferred until ULTRAKILL's camera has actually run once.
        ///
        /// The anchor records where ULTRAKILL's camera is pointing, and that is read
        /// during our Update, while CameraController.LateUpdate is what actually
        /// maintains rotationX and rotationY. LateUpdate runs later in the frame, so on
        /// the first frame after a level load the values read here are still whatever the
        /// freshly created object was constructed with rather than where the camera is.
        /// Both observed anchors recorded rotationY as exactly 0.00, from two different
        /// Minecraft yaws, which is what tipped this off: it is the uninitialised value,
        /// not a coincidence.
        ///
        /// Anchoring on that would bake an offset into the view for the whole session,
        /// because every later frame is a delta from the anchor. Waiting one LateUpdate
        /// costs one frame after a level load and makes the anchor mean what it says.
        /// </summary>
        private Vector3 MeasureFeetOffset()
        {
            try
            {
                var capsule = _rb.GetComponent<CapsuleCollider>() ?? _rb.GetComponentInParent<CapsuleCollider>();
                if (capsule != null && capsule.direction == 1)
                {
                    Vector3 bottomLocal = capsule.center - Vector3.up * (capsule.height * 0.5f);
                    Vector3 offset = capsule.transform.TransformPoint(bottomLocal) - _rb.position;
                    _log.LogInfo($"puppet: feet offset {offset.x:F3},{offset.y:F3},{offset.z:F3} units " +
                                 "(capsule bottom relative to rigidbody)");
                    return offset;
                }
                _log.LogWarning("puppet: no Y-axis capsule; assuming the rigidbody is at the feet " +
                                "(ULTRAKILL floors may sit too high or low in Minecraft)");
            }
            catch (Exception e)
            {
                _log.LogWarning($"puppet: feet offset failed: {e.Message}");
            }
            return Vector3.zero;
        }

        /// <summary>
        /// ULTRAKILL world point to Minecraft block coordinates: the exact inverse of the
        /// map that moves the puppet, feet to feet. Only meaningful while
        /// <see cref="MappingReady"/>.
        ///
        /// The forward map sends a Minecraft offset d to
        ///     uk = ukAnchor + scale * (A * d.xz, d.y)
        /// with A = ukFwd*mcFwd^T + ukRight*mcRight^T, built from the bases cached at the
        /// anchor. A is inverted here as a general 2x2 rather than assumed orthogonal or
        /// assumed to be any particular mirror, so it stays correct whatever the bases are.
        /// </summary>
        public Vector3 UkToMc(Vector3 uk)
        {
            Vector3 u = uk - _ukAnchor - _ukFeetOffset;
            Vector3 uF = _cachedUkForward, uR = _cachedUkRight;
            Vector3 mF = _cachedMcForward, mR = _cachedMcRight;
            float a = uF.x * mF.x + uR.x * mR.x, b = uF.x * mF.z + uR.x * mR.z;
            float c = uF.z * mF.x + uR.z * mR.x, d = uF.z * mF.z + uR.z * mR.z;
            float det = a * d - b * c;
            if (Mathf.Abs(det) < 1e-6f) det = 1e-6f;   // degenerate bases; never seen
            float ux = u.x / _scale, uz = u.z / _scale;
            float dx = ( d * ux - b * uz) / det;
            float dz = (-c * ux + a * uz) / det;
            return new Vector3(_mcOrigin.x + dx, _mcOrigin.y + u.y / _scale, _mcOrigin.z + dz);
        }

        /// <summary>
        /// A Minecraft-axis-aligned box to the ULTRAKILL box occupying the same space: the
        /// forward counterpart of <see cref="UkToMc"/>, feet to feet, from the same cached
        /// bases. Used to probe ULTRAKILL's physics one Minecraft cell at a time.
        ///
        /// The horizontal map A sends Minecraft +X to (a, c) and +Z to (b, d) in ULTRAKILL.
        /// For orthogonal A (measured: exactly diag(-1, 1)) those are perpendicular, so the
        /// cell is a box whose local axes are along them. A mirror only flips an axis's
        /// sign, which a box does not care about.
        /// </summary>
        public void McBoxToUk(Vector3 mcMin, Vector3 mcMax,
                              out Vector3 center, out Vector3 halfExtents, out Quaternion rotation)
        {
            Vector3 uF = _cachedUkForward, uR = _cachedUkRight;
            Vector3 mF = _cachedMcForward, mR = _cachedMcRight;
            float a = uF.x * mF.x + uR.x * mR.x, b = uF.x * mF.z + uR.x * mR.z;
            float c = uF.z * mF.x + uR.z * mR.x, d = uF.z * mF.z + uR.z * mR.z;

            Vector3 m = (mcMin + mcMax) * 0.5f - _mcOrigin;
            center = _ukAnchor + _ukFeetOffset + new Vector3(
                (a * m.x + b * m.z) * _scale, m.y * _scale, (c * m.x + d * m.z) * _scale);

            Vector3 h = (mcMax - mcMin) * 0.5f;
            float lenX = Mathf.Sqrt(a * a + c * c), lenZ = Mathf.Sqrt(b * b + d * d);
            halfExtents = new Vector3(h.x * _scale * lenX, h.y * _scale, h.z * _scale * lenZ);
            // Local +Z along Minecraft +Z's image; local +X is then +-Minecraft +X's image.
            rotation = Quaternion.LookRotation(new Vector3(b, 0f, d), Vector3.up);
        }

        private void Anchor(Protocol.SlotPlayer p)
        {
            if (!_sawCameraLateUpdate) return;

            if (!_awaitingSpawn)
            {
                // Continue from the value already in the file, so a restarted ULTRAKILL
                // never reuses a number Minecraft has already handled. 0 means "none".
                _spawnRequest = _bridge.UkSpawnRequest + 1u;
                if (_spawnRequest == 0u) _spawnRequest = 1u;
                _bridge.SetSpawnRequest(_spawnRequest);
                _awaitingSpawn = true;
                _spawnRequestedAt = Time.unscaledTime;
                _log.LogInfo($"puppet: asked Minecraft to move its player to spawn (request {_spawnRequest})");
                return;
            }
            if (p.spawnAck != _spawnRequest)
            {
                if (Time.unscaledTime - _spawnRequestedAt < SpawnAckTimeoutSeconds) return;
                _log.LogWarning($"puppet: no spawn acknowledgement after {SpawnAckTimeoutSeconds}s " +
                                $"(got {p.spawnAck}, wanted {_spawnRequest}); older Minecraft build? " +
                                "Anchoring wherever Minecraft's player is.");
            }
            _awaitingSpawn = false;

            try
            {
                if (!_sawCameraLateUpdate)
                {
                    return;
                }

                _ukAnchor = _rb.position;

                // The ULTRAKILL yaw and pitch anchors are DERIVED FROM MINECRAFT, and are
                // set after the origins are known. See the note where they are assigned.
            }
            catch (Exception e)
            {
                _log.LogWarning($"puppet: anchor read failed: {e.Message}");
                return;
            }

            _mcOrigin = new Vector3(p.posX, p.posY, p.posZ);
            _ukFeetOffset = MeasureFeetOffset();
            _generation++;
            _mcYawOrigin = p.yaw;
            _mcPitchOrigin = p.pitch;

            // The ULTRAKILL camera anchors are derived from Minecraft's, NOT read from
            // ULTRAKILL's own camera.
            //
            // The previous version read ULTRAKILL's heading here, assuming that at the
            // moment of attaching the two views coincide. They do not. ULTRAKILL's camera
            // faces wherever it faces; Minecraft's player faces wherever it faces; nothing
            // relates the two. So the anchor froze whatever arbitrary difference happened
            // to exist at that instant and held it for the whole session.
            //
            // Fitting the relation from a live session made it measurable:
            //
            //     appliedUkYaw = -0.998 * mcYaw + 114.39
            //
            // The code applies R = ukYawAnchor - (mcYaw - mcYawOrigin), which is
            // -mcYaw + (ukYawAnchor + mcYawOrigin). The slope of -1 confirms it was doing
            // exactly what it claimed; the intercept of 114.39 shows the two cameras were
            // pinned 114 degrees apart at every heading. "Forward is forward" needs
            // R = -mcYaw, an intercept of exactly zero.
            //
            // Verified against ULTRAKILL's own ApplyRotations, which tilts the player with
            // Quaternion.AngleAxis(rotationY, Vector3.up). That takes forward (0,0,1) to
            // (sin R, 0, cos R) in Unity's left-handed frame, while Minecraft's forward is
            // (-sin Y, 0, cos Y). Matching them requires R = -Y.
            //
            // Pitch likewise: ApplyRotations tilts by AngleAxis(0 - rotationX, right), so a
            // positive rotationX looks up while Minecraft's positive pitch looks down, hence
            // the negation. That makes the pitch intercept zero too.
            _ukYawAnchor = YawSign * _mcYawOrigin;

            // Pitch keeps its own negation, derived from ULTRAKILL's ApplyRotations rather
            // than guessed: it tilts by AngleAxis(0 - rotationX, Vector3.right), and in
            // Unity's left-handed frame a POSITIVE rotation about +X tilts forward
            // DOWNWARD. Minecraft's positive pitch also looks down, so
            //   mcPitch > 0  ->  rotationX < 0  ->  rotationX = -mcPitch
            // Pitch was reported as part of "all directions reversed", but this derivation
            // says it is already correct, so it is deliberately NOT flipped along with yaw.
            // Flipping it on the strength of a phrase that was about yaw would break the one
            // axis that demonstrably works.
            // Pitch follows Minecraft directly, no negation.
            //
            // This USED to be negated, on a derivation from ApplyRotations: it tilts by
            // AngleAxis(0 - rotationX, right), so a positive rotationX was read as looking
            // up while Minecraft's positive pitch looks down, implying -mcPitch.
            //
            // The player reports up and down reversed, as well as left and right. The
            // derivation predicted correct and the game says otherwise, and the same style
            // of derivation has been wrong three times on this project, so the observation
            // wins. A comment claiming a verified sign is worse than no comment when it is
            // wrong, so this one records the report and the history rather than asserting
            // a convention that has not been confirmed.
            _ukPitchAnchor = _mcPitchOrigin;

            // The basis is NOT cached here. It has to be captured after the camera has actually
            // been rotated to the anchor heading, and at this point it has not been:
            // TickCamera returns early while _anchored is false, so ApplyLook has never
            // run and the camera is still wherever ULTRAKILL left it, which on a fresh
            // level load is rotationY = 0.
            //
            // Caching here therefore recorded a stale heading that never matched the
            // camera, and the player read it as WASD rotated about 82 degrees:
            //
            //     cached at anchor : UKcachedFwd=(0.000, 1.000)
            //     live in play     : UKliveFwd  =(0.163,-0.987) ... (0.633,-0.774)
            //
            // See TryCacheBasis, which runs on the first anchored frame instead, after
            // ApplyRotations has put the camera at the anchor heading.

            _anchored = true;
            _lastDesired = _ukAnchor;

            LastDesired = _ukAnchor;
            LastActual = _rb.position;
            LastMcYaw = p.yaw;
            LastMcPitch = p.pitch;
            CameraDeltaYaw = 0f;
            CameraDeltaPitch = 0f;

            _log.LogInfo($"puppet: anchored at mc=({p.posX:F2},{p.posY:F2},{p.posZ:F2}) " +
                         $"-> uk=({_ukAnchor.x:F3},{_ukAnchor.y:F3},{_ukAnchor.z:F3}) " +
                         $"yaw {p.yaw:F2}->{_ukYawAnchor:F2} " +
                         $"pitch {p.pitch:F2}->{_ukPitchAnchor:F2}; further movement is relative");
        }

        private static float UkToMcYaw(float ukYaw)
        {
            // Unity yaw is counter-clockwise about +Y in a left-handed frame; Minecraft's
            // is the opposite sense. Verified from the logged pair rather than assumed.
            return -ukYaw;
        }

        private void ApplyLook(float mcYaw, float mcPitch)
        {
            if (_camera == null) return;
            try
            {
                // CameraController applies AngleAxis(rotationY, up) for yaw and
                // AngleAxis(-rotationX, right) for pitch, so rotationX is the negation of
                // the visual pitch angle.
                // Relative to the anchor, so attaching does not snap the view.
                // Minecraft's getYRot() is NOT wrapped to [-180,180]; it accumulates
                // without bound (517.65 was seen in one session, -177.00 in the next).
                // A raw subtraction from the anchor would therefore see 179 -> 539 as a
                // jump of +360 degrees and spin the camera on every wrap, and would be
                // even worse with an unbounded value. Mathf.DeltaAngle folds the
                // difference into (-180,180] first, so it reports the shortest rotation,
                // which is what "turn a little further the same way" means. That is why
                // it is used even though the input is not actually wrapped.
                float yawDelta = Mathf.DeltaAngle(0f, mcYaw - _mcYawOrigin);
                float pitchDelta = Mathf.DeltaAngle(0f, mcPitch - _mcPitchOrigin);

                CameraDeltaYaw = yawDelta;
                CameraDeltaPitch = pitchDelta;

                // UkToMcYaw negates, so the yaw delta negates too. Pitch maps through
                // ApplyRotations as AngleAxis(-rotationX, right), hence the same sign.
                LastUkYaw = _ukYawAnchor + YawSign * yawDelta;
                LastUkPitch = _ukPitchAnchor + pitchDelta;

                // WRAP THE YAW. This is not cosmetic, it is a hard requirement.
                //
                // Minecraft's getYRot() is unbounded and keeps accumulating: values of
                // 517.65, 569.29 and 591.34 were all observed across sessions. ULTRAKILL's
                // CameraController is not:
                //
                //     public float minimumY = -360f;
                //     public float maximumY = 360f;
                //
                // So once Minecraft's yaw passes 360, a directly assigned rotationY is
                // outside ULTRAKILL's own range and the camera simply stops responding,
                // permanently, with no error. Turning the mouse would do nothing at all
                // after a couple of minutes of looking around, which reads exactly like a
                // dead input chain and is not one.
                //
                // Wrapping into (-180, 180] keeps the value small and always in range. It
                // is also visually identical: AngleAxis is periodic in 360, and
                // Mathf.DeltaAngle treats a wrap as the short way round, so the seam where
                // this jumps by 360 produces no visible spin.
                LastUkYaw = Mathf.DeltaAngle(0f, LastUkYaw);

                // Pitch is bounded in Minecraft to (-90, 90) and ULTRAKILL clamps to
                // +/-89, so it needs no wrap. Clamped anyway so a malformed value from the
                // bridge cannot drive the camera somewhere absurd.
                LastUkPitch = Mathf.Clamp(LastUkPitch, -89f, 89f);

                _camera.rotationY = LastUkYaw;
                _camera.rotationX = LastUkPitch;
                _camera.ApplyRotations();
            }
            catch (Exception e)
            {
                _log.LogWarning($"puppet: ApplyRotations failed: {e.Message}");
            }
        }

        private void LogOccasionally(Protocol.SlotPlayer p, Vector3 desired, Vector3 actual)
        {
            // Log both coordinate systems together, plus both yaw conventions. This is
            // the only way to confirm the axis and yaw conversions are right.
            if (_frames % 60 != 0) { _frames++; return; }
            _frames++;

            _log.LogInfo(
                $"puppet frame={_frames} mc=({p.posX:F2},{p.posY:F2},{p.posZ:F2}) " +
                $"ukDesired=({desired.x:F3},{desired.y:F3},{desired.z:F3}) " +
                $"ukActual=({actual.x:F3},{actual.y:F3},{actual.z:F3}) " +
                $"mcYaw={p.yaw:F2} ukYaw={UkToMcYaw(p.yaw):F2} " +
                $"mcPitch={p.pitch:F2} vel=({p.velX:F3},{p.velY:F3},{p.velZ:F3})");

            // Camera is logged separately and only when it is actually moving. Logging it
            // every time round would bury it, and the interesting event is the camera
            // responding to Minecraft, which only happens while the mouse is turning.
            // Log on ANY camera motion at all, including sub-degree. The earlier 0.5-degree
            // threshold was meant to reduce noise, but it meant that an unattended
            // session with a still mouse produced no camera lines whatsoever, so there
            // was no way to tell "camera not responding" from "camera has not been
            // exercised". Rate-limited separately by requiring a real change.
            float yaw = Mathf.DeltaAngle(0f, p.yaw - _mcYawOrigin);
            float pitch = Mathf.DeltaAngle(0f, p.pitch - _mcPitchOrigin);
            if (Mathf.Abs(yaw - _lastLoggedYaw) > 0.05f || Mathf.Abs(pitch - _lastLoggedPitch) > 0.05f)
            {
                _lastLoggedYaw = yaw;
                _lastLoggedPitch = pitch;

                // Read ULTRAKILL's basis LIVE here, separately from the cached copy the
                // movement map uses, and report both. The previous line printed the cached
                // value only, which is constant by construction, so it reported the
                // instrumentation rather than the game and produced dot products that could
                // not mean anything. Both dots now use the live basis.
                Vector3 liveFwd = UkForwardLive();
                Vector3 liveRight = UkRightLive();
                _log.LogInfo(
                    $"puppet camera: mc yaw={p.yaw:F2} pitch={p.pitch:F2} " +
                    $"| applied ukYaw={LastUkYaw:F2} ukPitch={LastUkPitch:F2} " +
                    $"| UKliveFwd=({liveFwd.x:F3},{liveFwd.z:F3}) " +
                    $"UKcachedFwd=({UkForward().x:F3},{UkForward().z:F3}) " +
                    $"predSinCos=({Mathf.Sin(LastUkYaw * Mathf.Deg2Rad):F3}," +
                    $"{Mathf.Cos(LastUkYaw * Mathf.Deg2Rad):F3}) " +
                    $"MCfwd=({McForward(p.yaw).x:F3},{McForward(p.yaw).z:F3}) " +
                    $"fwdDot={Vector3.Dot(liveFwd, McForward(p.yaw)):F3} " +
                    $"rightDot={Vector3.Dot(liveRight, McRight(p.yaw)):F3}" +
                    // The camera legitimately moves, so cached-vs-live will differ in play.
                    // What must hold is that the cache was taken from a heading where the
                    // two agreed. A cache sampled before the camera was ever rotated to the
                    // anchor heading looks perfectly plausible and silently rotates WASD by
                    // the difference, so report the agreement explicitly.
                    $" cacheWasValid={(_basisValidAtSample ? "yes" : "NO")}" +
                    $" cacheDriftDeg={Vector3.Angle(UkForward(), liveFwd):F1}");
            }
        }

        private float _lastLoggedYaw;
        private float _lastLoggedPitch;

        /// <summary>
        /// Where ULTRAKILL's camera is actually pointing, in ULTRAKILL units.
        ///
        /// Read from the camera's own world forward vector rather than derived from
        /// rotationY. Both were tried and the derivation kept contradicting the
        /// measurement, because it depends on a chain of assumptions: Unity being
        /// left-handed, how AngleAxis composes with ULTRAKILL's gravityRotation, and
        /// whether the camera's parent transform is already rotated. Reading
        /// <c>cam.transform.forward</c> needs none of those to be true.
        ///
        /// This exists to settle the movement question empirically. A dot product of 1.0
        /// between this and <see cref="McForward"/> means "forward is forward" and the
        /// movement map is right; -1.0 means the puppet walks backwards; anything in
        /// between means the two conventions disagree by a rotation that has to be
        /// applied to movement as well as to the camera.
        /// </summary>
        /// <summary>
        /// ULTRAKILL's horizontal basis, CACHED AT ANCHOR and then held fixed.
        ///
        /// <b>Why it is cached.</b> The previous version read this live every frame, and that
        /// made the player's position swing whenever the camera turned. The map decomposed
        /// the world offset into Minecraft's forward/right using the CURRENT yaw, then
        /// rebuilt it from ULTRAKILL's CURRENT basis. Turning the camera changes the basis
        /// but not the offset, so the result necessarily moved. Simulated with the player
        /// standing completely still:
        ///
        ///     mcYaw=  0  offset -&gt; (-1.500, 2.000)   heading  -36.9 deg
        ///     mcYaw= 45  offset -&gt; (-2.000,-1.500)   heading -126.9 deg
        ///     mcYaw= 90  offset -&gt; ( 1.500,-2.000)   heading  143.1 deg
        ///
        /// A 90 degree sweep of a motionless player, which is exactly the reported
        /// "position moves in a circle when the camera moves".
        ///
        /// <b>Why caching does not change movement.</b> It is sampled at the anchor, when
        /// ULTRAKILL's heading has just been set equal to Minecraft's, so the cached basis is
        /// the same one the live version would have used on that frame. With the camera held
        /// still, every later frame produces the same result the live version did. The only
        /// behaviour that changes is the one that was wrong: the swing under camera motion.
        ///
        /// <b>Why it is not a sign constant.</b> Reading it live also made movement depend on
        /// <see cref="YawSign"/> indirectly, so the two could contradict each other. Measured
        /// and cached, the handedness difference between the engines is absorbed by
        /// observation, and this constant only governs where the camera looks.
        /// </summary>
        private Vector3 _cachedUkForward = Vector3.forward;
        private Vector3 _cachedUkRight = Vector3.right;

        /// <summary>
        /// Minecraft's basis at the anchor, cached for the same reason as ULTRAKILL's.
        ///
        /// <b>Why both.</b> The position map decomposes the world offset into a
        /// forward/right pair and rebuilds it in ULTRAKILL's frame. Both halves of that have
        /// to be fixed at the anchor. Caching only ULTRAKILL's half left the Minecraft half
        /// rotating with the camera, so the offset was re-expressed in a turning frame every
        /// frame and the puppet swung with it. Simulated with the player motionless and an
        /// offset of (1.2, 0.8):
        ///
        ///     yaw   live Minecraft basis       cached Minecraft basis
        ///      -3   (-1.200, 0.800)            (-1.200, 0.800)
        ///      42   (-1.414,-0.283)            (-1.200, 0.800)
        ///      87   (-0.800,-1.200)            (-1.200, 0.800)
        ///     177   ( 1.200,-0.800)            (-1.200, 0.800)
        ///
        /// With both cached the map is a fixed linear transform of the world offset, and has
        /// no term that can depend on where the player is looking.
        ///
        /// <b>Why movement is unchanged.</b> At the anchor the cached Minecraft basis and the
        /// live one are the same basis, so the first frame computes exactly what the previous
        /// version computed, and every frame after is the same fixed map.
        /// </summary>
        private Vector3 _cachedMcForward = new Vector3(0f, 0f, 1f);
        private Vector3 _cachedMcRight = new Vector3(1f, 0f, 0f);

        private bool _ukBasisCached;

        /// <summary>
        /// Whether the cached basis was sampled from a camera genuinely pointing at the
        /// anchor heading. False means the cache is stale and movement is rotated.
        /// </summary>
        private bool _basisValidAtSample;

        /// <summary>
        /// ULTRAKILL's forward, read live from the camera, for DIAGNOSTICS ONLY.
        ///
        /// Movement must never use this. Reading it live is what made the player's position
        /// swing whenever the camera turned, which is fixed by caching. It is read here only
        /// so the log can show what ULTRAKILL's camera is actually doing, next to the
        /// predicted (sin R, cos R), so the convention can be established by observation.
        ///
        /// That matters because the whole yaw sign question reduces to whether ULTRAKILL's
        /// forward is (sin R, cos R) or (-sin R, cos R), and that has now been guessed wrong
        /// three times. Two numbers in one log line settle it permanently.
        /// </summary>
        private Vector3 UkForwardLive()
        {
            try
            {
                if (_camera != null && _camera.cam != null)
                {
                    Vector3 f = _camera.cam.transform.forward;
                    Vector3 flat = new Vector3(f.x, 0f, f.z);
                    if (flat.sqrMagnitude > 1e-6f) return flat.normalized;
                }
            }
            catch { }
            return Vector3.forward;
        }

        private Vector3 UkRightLive()
        {
            try
            {
                if (_camera != null && _camera.cam != null)
                {
                    Vector3 r = _camera.cam.transform.right;
                    Vector3 flat = new Vector3(r.x, 0f, r.z);
                    if (flat.sqrMagnitude > 1e-6f) return flat.normalized;
                }
            }
            catch { }
            Vector3 f = UkForwardLive();
            return new Vector3(-f.z, 0f, f.x);
        }

        /// <summary>
        /// Sample both bases, once, on the first frame where the camera is actually at the
        /// anchor heading.
        /// </summary>
        ///
        /// <remarks>
        /// Guarded rather than called from <see cref="Anchor"/>, because at anchor time
        /// <see cref="ApplyLook"/> has not run yet and the camera is still at ULTRAKILL's
        /// own default heading. Sampling then produced a basis that never matched the camera
        /// for the rest of the session.
        ///
        /// <see cref="UkForward"/> returns the cache when it exists, so this deliberately
        /// reads the LIVE transforms instead, which is the only correct source at this point.
        /// </remarks>
        private void TryCacheBasis()
        {
            if (_ukBasisCached) return;
            CacheUkBasis();
        }

        private void CacheUkBasis()
        {
            Vector3 f = UkForwardLive();
            Vector3 r = UkRightLive();
            if (f.sqrMagnitude > 1e-6f) _cachedUkForward = f;
            if (r.sqrMagnitude > 1e-6f) _cachedUkRight = r;

            _cachedMcForward = McForward(_mcYawOrigin);
            _cachedMcRight = McRight(_mcYawOrigin);

            _ukBasisCached = true;

            // Did we sample from a camera that was actually pointing where we think?
            // Compare the sampled forward against the heading the camera should now have.
            // If this is NO the cache is stale and WASD will be rotated by the difference,
            // which is exactly the bug this ordering fix exists to prevent.
            Vector3 expected = new Vector3(
                Mathf.Sin(_ukYawAnchor * Mathf.Deg2Rad), 0f,
                Mathf.Cos(_ukYawAnchor * Mathf.Deg2Rad));
            _basisValidAtSample = Vector3.Angle(f, expected) < 1f;

            _log.LogInfo(
                $"puppet: cached bases at anchor (mc yaw {_mcYawOrigin:F2}, " +
                $"ukYaw {_ukYawAnchor:F2}): " +
                $"UK fwd=({_cachedUkForward.x:F3},{_cachedUkForward.z:F3}) " +
                $"UK right=({_cachedUkRight.x:F3},{_cachedUkRight.z:F3}) " +
                $"MC fwd=({_cachedMcForward.x:F3},{_cachedMcForward.z:F3}) " +
                $"MC right=({_cachedMcRight.x:F3},{_cachedMcRight.z:F3})" +
                (_basisValidAtSample
                    ? "  [cache sampled from a camera at the anchor heading: valid]"
                    : $"  [INVALID: sampled {Vector3.Angle(f, expected):F1} deg away from the " +
                      "anchor heading; WASD will be rotated by that much]"));
        }

        /// <summary>ULTRAKILL's forward on the horizontal plane, as cached at the anchor.</summary>
        private Vector3 UkForward()
        {
            if (_ukBasisCached) return _cachedUkForward;

            try
            {
                if (_camera != null && _camera.cam != null)
                {
                    Vector3 f = _camera.cam.transform.forward;
                    Vector3 flat = new Vector3(f.x, 0f, f.z);
                    if (flat.sqrMagnitude > 1e-6f) return flat.normalized;
                }
            }
            catch { }
            return Vector3.forward;
        }

        /// <summary>ULTRAKILL's right-hand direction on the horizontal plane.</summary>
        private Vector3 UkRight()
        {
            if (_ukBasisCached) return _cachedUkRight;

            try
            {
                if (_camera != null && _camera.cam != null)
                {
                    Vector3 r = _camera.cam.transform.right;
                    Vector3 flat = new Vector3(r.x, 0f, r.z);
                    if (flat.sqrMagnitude > 1e-6f) return flat.normalized;
                }
            }
            catch { }
            Vector3 f = UkForward();
            return new Vector3(-f.z, 0f, f.x);
        }

        /// <summary>
        /// Where Minecraft's player is facing, in Minecraft units.
        ///
        /// Minecraft's forward for yaw Y is (-sin Y, 0, cos Y), which is the standard
        /// formula for a right-handed Y-up frame and is the one thing here that is not
        /// derived from ULTRAKILL.
        /// </summary>
        private static Vector3 McForward(float mcYaw)
        {
            float y = mcYaw * Mathf.Deg2Rad;
            return new Vector3(-Mathf.Sin(y), 0f, Mathf.Cos(y));
        }

        /// <summary>
        /// Minecraft's right-hand direction on the horizontal plane.
        ///
        /// Minecraft's forward rotated -90 degrees about +Y in its right-handed frame. At
        /// yaw 0 the player faces +Z (south) and their right hand points west, which is -X,
        /// and that is the case this reproduces.
        ///
        /// Logged alongside the forward dot product because forward alone cannot distinguish
        /// a correct mapping from one that is correct only for forward and inverted for
        /// strafing. That blind spot is what made the handedness question ambiguous for
        /// several rounds.
        /// </summary>
        private static Vector3 McRight(float mcYaw)
        {
            float y = mcYaw * Mathf.Deg2Rad;
            return new Vector3(-Mathf.Cos(y), 0f, -Mathf.Sin(y));
        }

        /// <summary>Undo the patches and hand the player back to ULTRAKILL.</summary>
        public void Shutdown()
        {
            try
            {
                if (_harmony != null)
                {
                    // Unpatch by our own id, so only OUR patches are removed and any
                    // other mod's patches on the same methods survive.
                    // UnpatchSelf() removes only this Harmony instance's patches, so any
                    // other mod's patches on the same methods survive.
                    _harmony.UnpatchSelf();
                }
            }
            catch { }
            _patched.Clear();

            try
            {
                if (_rb != null)
                {
                    _rb.isKinematic = false;
                    _rb.useGravity = true;
                }
            }
            catch { }

            _ready = false;
            _log.LogInfo("puppet: shut down, control returned to ULTRAKILL");
        }
    }
}