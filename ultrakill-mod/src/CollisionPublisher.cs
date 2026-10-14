using System;
using System.Collections.Generic;
using System.Text;
using UnityEngine;
using Minekill.Bridge;

namespace Minekill
{
    /// <summary>
    /// Publishes ULTRAKILL's environment colliders around the player into the COLLISION
    /// table, in Minecraft block coordinates (Milestone D, spike).
    ///
    /// Minecraft appends these boxes to its own collision queries, so Minecraft's physics
    /// resolves the player against ULTRAKILL's walls. Nothing here moves the player and
    /// nothing in ULTRAKILL is modified: this only reads colliders.
    ///
    /// Two sources:
    ///   - BoxColliders whose box is axis-aligned in Minecraft space are sent exactly.
    ///   - Everything else (MeshColliders, rotated boxes, other shapes) is voxelised at
    ///     1/8 block by <see cref="VoxelField"/>, which probes ULTRAKILL's physics and
    ///     ignores the exact boxes so they are not sent twice.
    /// A rotated box's bounding box is never sent: it would fatten walls across doorways
    /// and can enclose the player.
    /// </summary>
    internal sealed class CollisionPublisher
    {
        /// <summary>Half-size of the query box around the player, in Minecraft blocks.</summary>
        private const float RadiusBlocks = 10f;
        private const float IntervalSeconds = 0.1f;
        /// <summary>A box counts as axis-aligned if its mapped volume is within this of exact.</summary>
        private const float AxisAlignedTolerance = 0.02f;

        private readonly BridgeTransport _bridge;
        private readonly PuppetController _puppet;
        private readonly BepInEx.Logging.ManualLogSource _log;

        private readonly Collider[] _hits = new Collider[4096];
        private readonly Collider[] _interestHits = new Collider[512];
        private readonly HashSet<int> _hitIds = new HashSet<int>();
        private readonly float[] _interestXyz = new float[Protocol.TBL_COLLISION_INTEREST_CAPACITY * 3];
        private readonly List<Vector3> _interest = new List<Vector3>();
        /// <summary>Half-size of the query box around each entity cluster, in Minecraft blocks.</summary>
        private const float InterestRadiusBlocks = 5f;
        private readonly float[] _boxes = new float[Protocol.TBL_COLLISION_CAPACITY * 6];
        private readonly List<KeyValuePair<float, int>> _order = new List<KeyValuePair<float, int>>();
        private const int MaxCandidates = 4096 + 32768;
        private readonly float[] _candidate = new float[MaxCandidates * 6];
        private readonly float[] _lastWritten = new float[Protocol.TBL_COLLISION_CAPACITY * 6];
        private int _lastWrittenCount = -1;
        private uint _lastWrittenGeneration = uint.MaxValue;
        private readonly Dictionary<int, bool> _exactCache = new Dictionary<int, bool>();
        private uint _exactCacheGeneration = uint.MaxValue;
        private readonly VoxelField _voxels;
        private readonly Vector3[] _corners = new Vector3[8];
        private readonly bool _layoutOk;
        private int _envMask;

        private float _next;
        private uint _lastCensusGeneration = uint.MaxValue;
        private bool _wasPublishing;
        private uint _voxelReportedGeneration = uint.MaxValue;

        // Collider tracking, so the voxel cache follows geometry that changes. Per collider:
        // its Minecraft-space bounds when last seen. Reset per mapping generation.
        private struct Seen { public Vector3 Min, Max; }
        private readonly Dictionary<int, Seen> _seen = new Dictionary<int, Seen>();
        private readonly HashSet<int> _present = new HashSet<int>();
        private readonly List<int> _gone = new List<int>();
        private uint _seenGeneration = uint.MaxValue;
        private bool _seenPrimed;

        // Diagnostic: solid-to-the-player colliders OUTSIDE the Environment mask.
        private int _extraMask = -1;
        private readonly HashSet<int> _extraReported = new HashSet<int>();
        private uint _extraGeneration = uint.MaxValue;
        private readonly Collider[] _extraHits = new Collider[256];

        // Last sample, for the status file.
        public int LastFound { get; private set; }
        public int LastSent { get; private set; }
        public int LastRotated { get; private set; }
        public int LastOther { get; private set; }
        public int LastOverflow { get; private set; }
        public string LastSkippedSummary { get; private set; } = "";
        public int LastExact { get; private set; }
        public int LastVoxelBoxes { get; private set; }
        public VoxelField Voxels => _voxels;

        public CollisionPublisher(BridgeTransport bridge, PuppetController puppet,
                                  BepInEx.Logging.ManualLogSource log)
        {
            _bridge = bridge;
            _puppet = puppet;
            _log = log;

            // WriteCollisionTable lays records down as one float array, which is only
            // right if a record is exactly six contiguous floats. Check the generated
            // layout instead of trusting that it still is.
            _layoutOk = Protocol.TBL_COLLISION_RECORD_SIZE == 24
                     && Protocol.TBL_COLLISION_REC_OFF_MINX == 0
                     && Protocol.TBL_COLLISION_REC_OFF_MAXZ == 20;
            if (!_layoutOk)
                _log.LogError("collision: COLLISION record layout is not 6 contiguous floats; " +
                              "publisher disabled. Update WriteCollisionTable.");

            _voxels = new VoxelField(puppet, IgnoreForVoxels, log);
        }

        /// <summary>Colliders the voxel probes must not count: the puppet, and exact boxes.</summary>
        private bool IgnoreForVoxels(Collider c) =>
            c.attachedRigidbody == _puppet.PlayerBody || IsExactBox(c) || BlockColliders.IsOurs(c);

        /// <summary>Is this an axis-aligned BoxCollider (sent exactly)? Cached per collider per mapping.</summary>
        private bool IsExactBox(Collider c)
        {
            if (!(c is BoxCollider box)) return false;
            if (_exactCacheGeneration != _puppet.MappingGeneration)
            {
                _exactCache.Clear();
                _exactCacheGeneration = _puppet.MappingGeneration;
            }
            int id = c.GetInstanceID();
            if (_exactCache.TryGetValue(id, out bool exact)) return exact;
            exact = TryAxisAligned(box, out _, out _);
            _exactCache[id] = exact;
            return exact;
        }

        public void Tick()
        {
            if (!_layoutOk) return;

            if (!_puppet.MappingReady)
            {
                if (_wasPublishing)
                {
                    // Mapping is being rebuilt (level load). Old boxes are in the old frame.
                    _bridge.WriteCollisionTable(NowMs(), _puppet.MappingGeneration, _boxes, 0);
                    _lastWrittenCount = 0;
                    _wasPublishing = false;
                }
                return;
            }

            if (_envMask == 0)
            {
                // ULTRAKILL's own definition of "environment", not a guessed layer list.
                _envMask = LayerMaskDefaults.Get(LMD.Environment);
                _log.LogInfo($"collision: environment layer mask 0x{_envMask:X}");
            }

            // Voxel probing runs every frame, under its own time budget.
            Rigidbody body = _puppet.PlayerBody;
            int ni = _bridge.ReadCollisionInterest(_interestXyz);
            if (ni >= 0)
            {
                _interest.Clear();
                for (int i = 0; i < ni; i++)
                    _interest.Add(new Vector3(_interestXyz[i * 3], _interestXyz[i * 3 + 1], _interestXyz[i * 3 + 2]));
            }
            _voxels.Tick(_envMask, _puppet.UkToMc(body.position + _puppet.UkFeetOffset), _interest);

            if (Time.unscaledTime < _next) return;
            _next = Time.unscaledTime + IntervalSeconds;
            Sample();
            _wasPublishing = true;
        }

        private void Sample()
        {
            Rigidbody body = _puppet.PlayerBody;
            Vector3 center = body.position;
            float r = RadiusBlocks * _puppet.Scale;
            int n = Physics.OverlapBoxNonAlloc(center, new Vector3(r, r, r), _hits,
                                               Quaternion.identity, _envMask,
                                               QueryTriggerInteraction.Ignore);
            n = AddInterestHits(n);

            // ULTRAKILL feet = rigidbody + feet offset; mapped, this should equal where
            // Minecraft says the player is, which the census line prints as a check.
            Vector3 mcPlayer = _puppet.UkToMc(center + _puppet.UkFeetOffset);
            int candidates = 0, rotated = 0, other = 0;
            var skipped = new Dictionary<string, int>();
            bool census = _puppet.MappingGeneration != _lastCensusGeneration;
            var bigSkipped = census ? new List<string>() : null;

            TrackColliders(n, body, mcPlayer);
            ReportNonEnvironmentSolids(center, r, body);

            for (int i = 0; i < n; i++)
            {
                Collider c = _hits[i];
                if (c == null || !c.enabled) continue;
                if (c.attachedRigidbody == body) continue;   // the puppet itself
                if (BlockColliders.IsOurs(c)) continue;      // Minecraft's own blocks, mirrored

                if (c is BoxCollider box && IsExactBox(c) && TryAxisAligned(box, out Vector3 mn, out Vector3 mx))
                {
                    int o = candidates * 6;
                    _candidate[o] = mn.x; _candidate[o + 1] = mn.y; _candidate[o + 2] = mn.z;
                    _candidate[o + 3] = mx.x; _candidate[o + 4] = mx.y; _candidate[o + 5] = mx.z;
                    candidates++;
                    continue;
                }

                string kind = c is BoxCollider ? "BoxCollider(rotated)" : c.GetType().Name;
                if (c is BoxCollider) rotated++; else other++;
                skipped.TryGetValue(kind, out int k);
                skipped[kind] = k + 1;
                if (bigSkipped != null && bigSkipped.Count < 12)
                {
                    Vector3 sz = c.bounds.size / _puppet.Scale;
                    bigSkipped.Add($"{kind} '{c.name}' {sz.x:F1}x{sz.y:F1}x{sz.z:F1} blocks");
                }
            }

            int exactCount = candidates;
            candidates += _voxels.Collect(mcPlayer, _candidate, candidates, MaxCandidates - candidates);

            // Nearest first, so a crowded area keeps the walls that matter.
            int sent = candidates;
            if (candidates > Protocol.TBL_COLLISION_CAPACITY)
            {
                _order.Clear();
                for (int i = 0; i < candidates; i++)
                    _order.Add(new KeyValuePair<float, int>(DistanceSq(mcPlayer, i), i));
                _order.Sort((a, b) => a.Key.CompareTo(b.Key));
                sent = Protocol.TBL_COLLISION_CAPACITY;
                for (int j = 0; j < sent; j++)
                    Array.Copy(_candidate, _order[j].Value * 6, _boxes, j * 6, 6);
            }
            else
            {
                Array.Copy(_candidate, 0, _boxes, 0, candidates * 6);
            }

            // Only rewrite when something changed: Minecraft rebuilds its shapes on every new
            // table, and the static level gives identical samples most of the time.
            if (!SameAsLastWritten(sent))
            {
                _bridge.WriteCollisionTable(NowMs(), _puppet.MappingGeneration, _boxes, sent);
                Array.Copy(_boxes, _lastWritten, sent * 6);
                _lastWrittenCount = sent;
                _lastWrittenGeneration = _puppet.MappingGeneration;
            }
            LastExact = exactCount;
            LastVoxelBoxes = candidates - exactCount;

            if (_voxels.Complete && _voxelReportedGeneration != _puppet.MappingGeneration)
            {
                // The performance measurement for 1/8-block probing under Mono + Wine.
                _voxelReportedGeneration = _puppet.MappingGeneration;
                _log.LogInfo($"collision voxels (gen {_puppet.MappingGeneration}): scan around the player " +
                             $"complete: {_voxels.BlocksProbed} blocks probed, {_voxels.BlocksSolid} with geometry, " +
                             $"{_voxels.Probes} overlap queries in {_voxels.ProbeMs:F0} ms " +
                             $"({(_voxels.Probes > 0 ? _voxels.ProbeMs * 1000.0 / _voxels.Probes : 0):F1} us each), " +
                             $"{_voxels.MergedBoxes} merged boxes (merge {_voxels.LastMergeMs:F1} ms, off-thread); " +
                             $"sending {exactCount} exact + " +
                             $"{candidates - exactCount} voxel boxes, {LastOverflow} over capacity");
            }

            LastFound = n;
            LastSent = sent;
            LastRotated = rotated;
            LastOther = other;
            LastOverflow = candidates - sent;
            var sb = new StringBuilder();
            foreach (var kv in skipped) sb.Append(kv.Key).Append('=').Append(kv.Value).Append(' ');
            LastSkippedSummary = sb.ToString().TrimEnd();

            if (census)
            {
                // Once per mapping (i.e. per level load): what this spike can and cannot see.
                _lastCensusGeneration = _puppet.MappingGeneration;
                _log.LogInfo($"collision census (gen {_puppet.MappingGeneration}): " +
                             $"{n} environment colliders within {RadiusBlocks} blocks; " +
                             $"{exactCount} exact boxes; voxelising {rotated} rotated boxes " +
                             $"and {other} other colliders [{LastSkippedSummary}]" +
                             (n == _hits.Length ? " (QUERY BUFFER FULL)" : ""));
                foreach (string s in bigSkipped) _log.LogInfo("collision census:   voxelising " + s);
                if (sent > 0)
                    _log.LogInfo($"collision census:   first box mc=({_boxes[0]:F2},{_boxes[1]:F2},{_boxes[2]:F2})" +
                                 $" -> ({_boxes[3]:F2},{_boxes[4]:F2},{_boxes[5]:F2}); " +
                                 $"player feet mc=({mcPlayer.x:F2},{mcPlayer.y:F2},{mcPlayer.z:F2})");
            }
        }

        /// <summary>
        /// Colliders around each entity cluster Minecraft reported (COLLISION_INTEREST), appended
        /// to the player's hits without duplicates, so mobs, items and minecarts collide with
        /// ULTRAKILL's level wherever they are, not only next to the player.
        /// </summary>
        private int AddInterestHits(int n)
        {
            if (_interest.Count == 0) return n;
            _hitIds.Clear();
            for (int i = 0; i < n; i++) if (_hits[i] != null) _hitIds.Add(_hits[i].GetInstanceID());
            foreach (Vector3 mc in _interest)
            {
                _puppet.McBoxToUk(mc - Vector3.one * InterestRadiusBlocks, mc + Vector3.one * InterestRadiusBlocks,
                                  out Vector3 c, out Vector3 half, out Quaternion rot);
                int k = Physics.OverlapBoxNonAlloc(c, half, _interestHits, rot, _envMask, QueryTriggerInteraction.Ignore);
                for (int i = 0; i < k && n < _hits.Length; i++)
                {
                    Collider col = _interestHits[i];
                    if (col != null && _hitIds.Add(col.GetInstanceID())) _hits[n++] = col;
                }
            }
            return n;
        }

        /// <summary>
        /// Map the box's 8 world corners into Minecraft and take their bounds. If that bounding
        /// box has the same volume as the real box, the box is axis-aligned in Minecraft space
        /// and its bounds ARE the box; otherwise it is rotated and its bounds would be fatter
        /// than the wall, so it is rejected.
        /// </summary>
        private bool TryAxisAligned(BoxCollider box, out Vector3 mn, out Vector3 mx)
        {
            Transform t = box.transform;
            Vector3 h = box.size * 0.5f;
            int k = 0;
            for (int sx = -1; sx <= 1; sx += 2)
            for (int sy = -1; sy <= 1; sy += 2)
            for (int sz = -1; sz <= 1; sz += 2)
                _corners[k++] = _puppet.UkToMc(t.TransformPoint(
                    box.center + new Vector3(sx * h.x, sy * h.y, sz * h.z)));

            mn = _corners[0]; mx = _corners[0];
            for (int i = 1; i < 8; i++) { mn = Vector3.Min(mn, _corners[i]); mx = Vector3.Max(mx, _corners[i]); }

            // Real volume from the mapped edge lengths, which includes any transform scale.
            float ex = (_corners[4] - _corners[0]).magnitude;   // x edge (sx differs)
            float ey = (_corners[2] - _corners[0]).magnitude;   // y edge
            float ez = (_corners[1] - _corners[0]).magnitude;   // z edge
            float real = ex * ey * ez;
            Vector3 d = mx - mn;
            float aabb = d.x * d.y * d.z;
            if (real < 1e-6f) return false;                      // flat or degenerate
            return Mathf.Abs(aabb - real) <= AxisAlignedTolerance * real;
        }

        /// <summary>
        /// Keep the voxel cache in step with geometry that changes.
        ///
        /// ULTRAKILL switches rooms on when their door opens. The voxel scan reaches past
        /// doors long before that, so those blocks were probed while the room was off and
        /// cached as empty: the 2026-10-13 playtest's "fall through the floor just past every
        /// door". This sample sees every active environment collider within the query cube,
        /// and the voxel scan cube lies inside it, so a collider seen here for the first time
        /// cannot have been active when any block it covers was probed. Those blocks are
        /// re-probed. A collider that moved (a sliding door) has its old and new space
        /// re-probed; one that vanished while its space is still in range (switched off) has
        /// its last space re-probed. One leaving range because the player walked away is kept,
        /// so it is not "new" again on return.
        /// </summary>
        private void TrackColliders(int n, Rigidbody body, Vector3 mcPlayer)
        {
            if (_seenGeneration != _puppet.MappingGeneration)
            {
                _seen.Clear();
                _seenGeneration = _puppet.MappingGeneration;
                _seenPrimed = false;
            }

            _present.Clear();
            for (int i = 0; i < n; i++)
            {
                Collider c = _hits[i];
                if (c == null || !c.enabled || c.attachedRigidbody == body || BlockColliders.IsOurs(c)) continue;
                int id = c.GetInstanceID();
                _present.Add(id);
                McBounds(c.bounds, out Vector3 mn, out Vector3 mx);
                if (!_seen.TryGetValue(id, out Seen old))
                {
                    // The very first sample of a level only records: nothing it could
                    // invalidate was probed under a different set of colliders.
                    if (_seenPrimed) _voxels.Invalidate(mn, mx);
                }
                else if ((old.Min - mn).sqrMagnitude > 1e-4f || (old.Max - mx).sqrMagnitude > 1e-4f)
                {
                    _voxels.Invalidate(old.Min, old.Max);
                    _voxels.Invalidate(mn, mx);
                }
                _seen[id] = new Seen { Min = mn, Max = mx };
            }

            _gone.Clear();
            Vector3 qMin = mcPlayer - Vector3.one * RadiusBlocks, qMax = mcPlayer + Vector3.one * RadiusBlocks;
            foreach (var kv in _seen)
            {
                if (_present.Contains(kv.Key)) continue;
                Seen s = kv.Value;
                bool stillInRange = s.Min.x <= qMax.x && s.Max.x >= qMin.x && s.Min.y <= qMax.y
                                 && s.Max.y >= qMin.y && s.Min.z <= qMax.z && s.Max.z >= qMin.z;
                if (stillInRange) _gone.Add(kv.Key);
            }
            foreach (int id in _gone)
            {
                Seen s = _seen[id];
                _voxels.Invalidate(s.Min, s.Max);
                _seen.Remove(id);
            }
            _seenPrimed = true;
        }

        /// <summary>A Unity world AABB as a Minecraft-space AABB (via its 8 corners).</summary>
        private void McBounds(Bounds b, out Vector3 mn, out Vector3 mx)
        {
            Vector3 c = b.center, e = b.extents;
            mn = Vector3.positiveInfinity; mx = Vector3.negativeInfinity;
            for (int sx = -1; sx <= 1; sx += 2)
            for (int sy = -1; sy <= 1; sy += 2)
            for (int sz = -1; sz <= 1; sz += 2)
            {
                Vector3 p = _puppet.UkToMc(c + new Vector3(sx * e.x, sy * e.y, sz * e.z));
                mn = Vector3.Min(mn, p); mx = Vector3.Max(mx, p);
            }
        }

        /// <summary>
        /// Diagnostic only. Logs (once per collider per level, first 20) colliders near the
        /// player that ULTRAKILL's player physically collides with (Unity's layer collision
        /// matrix for the player's layer) but that are NOT on the Environment layers this
        /// publisher sends. If floors or walls the player can stand on show up here, the mask
        /// is too narrow. Dynamic layers (gibs, limbs, corpses, projectiles, items,
        /// explosions, armour) are excluded since they are not level geometry.
        /// </summary>
        private void ReportNonEnvironmentSolids(Vector3 center, float r, Rigidbody body)
        {
            if (_extraMask == -1)
            {
                int playerLayer = body.gameObject.layer;
                int solid = 0;
                for (int l = 0; l < 32; l++)
                    if (!Physics.GetIgnoreLayerCollision(playerLayer, l)) solid |= 1 << l;
                int dynamicLayers = (1 << 9) | (1 << 10) | (1 << 11) | (1 << 12) | (1 << 14) | (1 << 22)
                                  | (1 << 23) | (1 << 26) | (1 << 27) | (1 << 29) | (1 << playerLayer);
                _extraMask = solid & ~_envMask & ~dynamicLayers;
                _log.LogInfo($"collision: player layer {playerLayer} ({LayerMask.LayerToName(playerLayer)}) " +
                             $"collides with mask 0x{solid:X8}; Environment mask 0x{_envMask:X8}; " +
                             $"watching extra solid layers 0x{_extraMask:X8} [{LayerNames(_extraMask)}]");
            }
            if (_extraMask == 0) return;
            if (_extraGeneration != _puppet.MappingGeneration)
            {
                _extraReported.Clear();
                _extraGeneration = _puppet.MappingGeneration;
            }
            if (_extraReported.Count >= 20) return;

            int n = Physics.OverlapBoxNonAlloc(center, new Vector3(r, r, r), _extraHits, Quaternion.identity,
                                               _extraMask, QueryTriggerInteraction.Ignore);
            for (int i = 0; i < n && _extraReported.Count < 20; i++)
            {
                Collider c = _extraHits[i];
                if (c == null || !c.enabled || c.attachedRigidbody == body) continue;
                if (!_extraReported.Add(c.GetInstanceID())) continue;
                Vector3 sz = c.bounds.size / _puppet.Scale;
                _log.LogInfo($"collision: NOT SENT, solid to the player on layer " +
                             $"{c.gameObject.layer} ({LayerMask.LayerToName(c.gameObject.layer)}): " +
                             $"{c.GetType().Name} '{c.name}' {sz.x:F1}x{sz.y:F1}x{sz.z:F1} blocks");
            }
        }

        private static string LayerNames(int mask)
        {
            var sb = new StringBuilder();
            for (int l = 0; l < 32; l++)
                if ((mask & (1 << l)) != 0) sb.Append(l).Append(':').Append(LayerMask.LayerToName(l)).Append(' ');
            return sb.ToString().TrimEnd();
        }

        private bool SameAsLastWritten(int count)
        {
            if (count != _lastWrittenCount || _puppet.MappingGeneration != _lastWrittenGeneration) return false;
            for (int i = 0; i < count * 6; i++) if (_boxes[i] != _lastWritten[i]) return false;
            return true;
        }

        private float DistanceSq(Vector3 p, int i)
        {
            int o = i * 6;
            float dx = Mathf.Max(_candidate[o] - p.x, 0f, p.x - _candidate[o + 3]);
            float dy = Mathf.Max(_candidate[o + 1] - p.y, 0f, p.y - _candidate[o + 4]);
            float dz = Mathf.Max(_candidate[o + 2] - p.z, 0f, p.z - _candidate[o + 5]);
            return dx * dx + dy * dy + dz * dz;
        }

        private static long NowMs() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
    }
}
