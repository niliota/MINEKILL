using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Threading.Tasks;
using UnityEngine;

namespace Minekill
{
    /// <summary>
    /// ULTRAKILL collision that is not an exact box (MeshColliders, rotated boxes, anything
    /// else), voxelised at 1/8 block on Minecraft's grid around the player (Milestone D).
    ///
    /// Same idea as SkyCraft's collision field: host geometry becomes small AABBs that
    /// Minecraft collides against, with slopes turning into 1/8-block steps, well under
    /// Minecraft's 0.6-block step-up.
    ///
    /// The geometry is never read. Each Minecraft cell is mapped into ULTRAKILL space
    /// (<see cref="PuppetController.McBoxToUk"/>) and asked of ULTRAKILL's own physics with
    /// an overlap query, so it works for every collider type and for meshes that are not
    /// CPU-readable. A whole block is probed first and refined (1/2, 1/4, 1/8) only where
    /// something is there, nearest blocks first, under a per-frame time budget. Results are
    /// cached per block until the mapping changes (level load).
    ///
    /// Geometry that changes (rooms ULTRAKILL switches on when a door opens, sliding doors,
    /// colliders switched off) is handled by <see cref="Invalidate"/>, driven from the
    /// collider tracking in <see cref="CollisionPublisher"/>.
    /// </summary>
    internal sealed class VoxelField
    {
        private const int Res = 8;                    // voxels per block per axis
        private const int RadiusH = 10;               // blocks probed around the player, horizontally
        private const int RadiusV = 8;                // and vertically
        private const double BudgetMs = 2.0;          // probe time allowed per frame
        /// <summary>
        /// Minimum seconds between background merges (0.25 until 2026-10-15, 0.1 until 2026-10-20; the merge takes well
        /// under a millisecond off-thread, and a shorter wait gets new floor to Minecraft sooner).
        /// </summary>
        private const float RebuildInterval = 0.05f;
        private const int MaxBlocks = 60000;          // cache size before far blocks are evicted
        private const int InterestR = 4;              // blocks probed around each entity cluster
        private const double InterestBudgetMs = 1.0;  // extra probe time per frame for them
        /// <summary>
        /// Probe time per frame for re-checking cached blocks (see Verify): cached results are
        /// never trusted forever. 2026-10-17 logs: floors that existed since the level started,
        /// cached "probed, empty" while a fresh probe hit them, and nothing ever re-probed them.
        /// </summary>
        private const double VerifyEmptyBudgetMs = 0.4;
        private const double VerifySolidBudgetMs = 0.3;
        /// <summary>
        /// Probe boxes are shifted by this (blocks) towards +x/+y/+z, so a surface lying exactly
        /// on a cell boundary marks exactly one cell: the one on its negative side (for a floor,
        /// the cell below it, so the floor keeps its exact height). They used to be shrunk by it
        /// on every side instead, which left a 2 * Eps gap at every boundary: ULTRAKILL's
        /// MeshColliders are hollow shells, an overlap only finds them where the surface passes
        /// through the probe, so a mesh floor exactly on a boundary was found by neither cell and
        /// was a hole (2026-10-15). Level starts anchor the player's feet on the floor below
        /// them, which puts that floor height, and every mesh floor level with it, exactly on a
        /// boundary.
        /// </summary>
        private const float Eps = 0.004f;

        private sealed class Block
        {
            public int X, Y, Z;
            /// <summary>Geometry here changed: probe again, but keep these boxes until then.</summary>
            public bool Stale;
            /// <summary>Probe order (see _recent).</summary>
            public long Seq;
            public ulong[] Bits;      // null = probed, nothing there. 512 bits: x + y*8 + z*64
            public float[] Boxes;     // greedy-merged, Minecraft coords, 6 floats each
        }

        private readonly PuppetController _puppet;
        private readonly Func<Collider, bool> _ignore;
        private readonly BepInEx.Logging.ManualLogSource _log;
        private readonly Dictionary<long, Block> _blocks = new Dictionary<long, Block>();
        private readonly Collider[] _buf = new Collider[64];
        private readonly Stopwatch _sw = new Stopwatch();
        private readonly Vector3Int[] _offsets;
        private readonly Vector3Int[] _interestOffsets;
        private readonly List<Vector3Int> _interest = new List<Vector3Int>();
        private int _interestCenter, _interestScan;
        /// <summary>
        /// Interest cubes fully probed since the last invalidation, skipped by the round-robin.
        /// Without it the round-robin restarted at the first point whenever the point list changed,
        /// and with the look-ahead points (first in the list, changing nearly every frame while
        /// the player moves fast) the cubes of mobs further down the list were rarely reached:
        /// their floors were never probed and they fell through (2026-10-17).
        /// </summary>
        private readonly HashSet<Vector3Int> _interestDone = new HashSet<Vector3Int>();
        private int _mask;

        private uint _generation = uint.MaxValue;
        private Vector3Int _scanCenter = new Vector3Int(int.MinValue, 0, 0);
        private int _scanIndex;

        // Known regions: where every block is cached, so what Minecraft has there is complete.
        // Around the player: the scan goes shell by shell (Chebyshev distance), and every block
        // before _knownIndex is cached, so the cube of the completed shells is known. Around
        // entity clusters: whole cubes, once every block in them is cached (_knownCubes).
        // Minecraft keeps entities out of space outside the known regions (CollisionPublisher).
        private int _knownIndex;
        private readonly int[] _shellOf;
        private readonly HashSet<Vector3Int> _knownCubes = new HashSet<Vector3Int>();

        // Re-checking cached blocks: one cycle for empty blocks (one overlap query each), one for
        // solid blocks (probed again in full, replaced when different). Each walks the player's
        // cube, then each entity cluster's cube. Position: center (0 = player, i = _interest[i - 1])
        // and scan index.
        private int _verifyEmptyCenter, _verifyEmptyScan, _verifySolidCenter, _verifySolidScan;
        private int _verifyFixLogs;
        public int VerifyFixes { get; private set; }

        // Merged result. The merge sorts thousands of boxes, which done on the main thread at
        // every block boundary was a visible hitch, so it runs on a worker over a snapshot
        // of the per-block box arrays (immutable once built) and the result is swapped in.
        private bool _dirty = true;
        private Vector3Int _collectCenter = new Vector3Int(int.MinValue, 0, 0);
        private sealed class MergeResult
        {
            public uint Generation; public List<float[]> Boxes; public double Ms; public long Seq;
            /// <summary>The snapshot's player block and entity clusters: the region it covers.</summary>
            public Vector3Int Center; public HashSet<Vector3Int> Interest;
        }
        /// <summary>The merged result the last Collect sent.</summary>
        private MergeResult _collected;

        /// <summary>
        /// Solid blocks probed since the snapshot of the merged result in use, sent to Minecraft
        /// as they are (unmerged) until a merge includes them. Before this, a block waited for
        /// the next background merge and the sample after it: 0.1-0.25 s in which a player
        /// moving fast over freshly probed ground (every level load starts with an empty cache)
        /// stood where Minecraft had no floor yet, sank in, and was inside the floor when it
        /// arrived (2026-10-15 logs: the floor in the cache, "merged boxes holding the point: 0").
        /// </summary>
        private readonly List<Block> _recent = new List<Block>();
        private long _probeSeq;
        private volatile MergeResult _result;
        private Task<MergeResult> _rebuild;
        private float _lastRebuild = -1f;

        // Stats.
        public long Probes { get; private set; }
        public double ProbeMs { get; private set; }
        public int BlocksProbed => _blocks.Count;
        public int BlocksSolid { get; private set; }
        public int MergedBoxes => _result != null ? _result.Boxes.Count : 0;
        public double LastMergeMs => _result != null ? _result.Ms : 0;
        public int Invalidations { get; private set; }
        public bool Complete => _scanIndex >= _offsets.Length;
        /// <summary>The last Collect ran out of room: what it sent is not complete anywhere.</summary>
        public bool LastCollectTruncated { get; private set; }

        public VoxelField(PuppetController puppet, Func<Collider, bool> ignore,
                          BepInEx.Logging.ManualLogSource log)
        {
            _puppet = puppet;
            _ignore = ignore;
            _log = log;

            var list = new List<Vector3Int>();
            for (int x = -RadiusH; x <= RadiusH; x++)
            for (int y = -RadiusV; y <= RadiusV; y++)
            for (int z = -RadiusH; z <= RadiusH; z++)
                list.Add(new Vector3Int(x, y, z));
            // Shell by shell (Chebyshev distance), nearest first inside a shell, so the blocks
            // done so far always form a cube around the player (the known region).
            list.Sort((p, q) =>
            {
                int c = Shell(p).CompareTo(Shell(q));
                return c != 0 ? c : p.sqrMagnitude.CompareTo(q.sqrMagnitude);
            });
            _offsets = list.ToArray();
            _shellOf = new int[_offsets.Length];
            for (int i = 0; i < _offsets.Length; i++) _shellOf[i] = Shell(_offsets[i]);

            var small = new List<Vector3Int>();
            for (int x = -InterestR; x <= InterestR; x++)
            for (int y = -InterestR; y <= InterestR; y++)
            for (int z = -InterestR; z <= InterestR; z++)
                small.Add(new Vector3Int(x, y, z));
            small.Sort((p, q) => p.sqrMagnitude.CompareTo(q.sqrMagnitude));
            _interestOffsets = small.ToArray();
        }

        private static int Shell(Vector3Int o) => Math.Max(Math.Abs(o.x), Math.Max(Math.Abs(o.y), Math.Abs(o.z)));

        /// <summary>Probe more blocks, within the frame budget. Call every frame.</summary>
        public void Tick(int mask, Vector3 mcPlayer, List<Vector3> interest)
        {
            // Entity clusters (block coords). A changed set needs a new merged result.
            bool changed = interest.Count != _interest.Count;
            for (int i = 0; !changed && i < interest.Count; i++)
                changed = Floor(interest[i]) != _interest[i];
            if (changed)
            {
                // Keep the round-robin's place when the point it is on is still there.
                Vector3Int at = _interestCenter < _interest.Count ? _interest[_interestCenter] : new Vector3Int(int.MinValue, 0, 0);
                _interest.Clear();
                foreach (Vector3 p in interest) _interest.Add(Floor(p));
                int keep = _interest.IndexOf(at);
                if (keep < 0) { _interestCenter = 0; _interestScan = 0; }
                else _interestCenter = keep;
                if (_interestDone.Count > 4096) _interestDone.Clear();
                _dirty = true;
            }

            _mask = mask;
            if (_puppet.MappingGeneration != _generation)
            {
                // New mapping (level load): every cached cell is in the old frame.
                _generation = _puppet.MappingGeneration;
                _blocks.Clear();
                _recent.Clear();
                _interestDone.Clear();
                _knownCubes.Clear();
                _knownIndex = 0;
                _result = null;
                BlocksSolid = 0;
                Probes = 0;
                ProbeMs = 0;
                Invalidations = 0;
                _dirty = true;
                _scanCenter = new Vector3Int(int.MinValue, 0, 0);
            }

            var center = new Vector3Int(Mathf.FloorToInt(mcPlayer.x), Mathf.FloorToInt(mcPlayer.y),
                                        Mathf.FloorToInt(mcPlayer.z));
            if (center != _scanCenter)
            {
                _scanCenter = center;
                _scanIndex = 0;
                _knownIndex = 0;
                if (_blocks.Count > MaxBlocks) Evict(center);
            }

            _sw.Restart();
            while (_scanIndex < _offsets.Length && _sw.Elapsed.TotalMilliseconds < BudgetMs)
            {
                Vector3Int b = center + _offsets[_scanIndex++];
                long key = Key(b.x, b.y, b.z);
                if (_blocks.TryGetValue(key, out Block have) && !have.Stale) continue;
                ProbeBlock(b.x, b.y, b.z, key);
            }

            // Then the cubes around entity clusters, round-robin, nearest blocks first in each.
            double until = _sw.Elapsed.TotalMilliseconds + InterestBudgetMs;
            int visited = 0;
            while (_interest.Count > 0 && visited < _interest.Count && _sw.Elapsed.TotalMilliseconds < until)
            {
                if (_interestCenter >= _interest.Count) _interestCenter = 0;
                if (_interestScan == 0 && _interestDone.Contains(_interest[_interestCenter]))
                {
                    _interestCenter++;
                    visited++;
                    continue;
                }
                if (_interestScan >= _interestOffsets.Length)
                {
                    _interestDone.Add(_interest[_interestCenter]);
                    if (_knownCubes.Count > 4096) _knownCubes.Clear();
                    _knownCubes.Add(_interest[_interestCenter]);
                    _interestScan = 0;
                    _interestCenter++;
                    visited++;
                    continue;
                }
                Vector3Int b = _interest[_interestCenter] + _interestOffsets[_interestScan++];
                long key = Key(b.x, b.y, b.z);
                if (_blocks.TryGetValue(key, out Block have) && !have.Stale) continue;
                ProbeBlock(b.x, b.y, b.z, key);
            }

            // Known extent around the player: every block before _knownIndex is cached.
            for (int guard = 0; _knownIndex < _offsets.Length && guard < 8000; guard++)
            {
                Vector3Int b = center + _offsets[_knownIndex];
                if (!_blocks.ContainsKey(Key(b.x, b.y, b.z))) break;
                _knownIndex++;
            }

            Verify(center);
            ProbeMs += _sw.Elapsed.TotalMilliseconds;
        }

        /// <summary>
        /// Re-check cached blocks, so a wrong cached result never lasts. Whatever made it wrong
        /// (a collider switched on or off out of the tracker's sight, physics not yet updated when
        /// probed), it is found and fixed within a cycle. First, every frame, the empty blocks right
        /// around and under the player; then a cycle over empty blocks and a slower one over solid
        /// blocks, through the player's cube and every entity cluster's cube.
        /// </summary>
        private void Verify(Vector3Int center)
        {
            for (int dy = -2; dy <= 1; dy++)
            for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                VerifyEmpty(center.x + dx, center.y + dy, center.z + dz);

            double until = _sw.Elapsed.TotalMilliseconds + VerifyEmptyBudgetMs;
            for (int steps = 0; _sw.Elapsed.TotalMilliseconds < until && steps < 20000; steps++)
                if (!VerifyStep(center, ref _verifyEmptyCenter, ref _verifyEmptyScan, false)) break;
            until = _sw.Elapsed.TotalMilliseconds + VerifySolidBudgetMs;
            for (int steps = 0; _sw.Elapsed.TotalMilliseconds < until && steps < 20000; steps++)
                if (!VerifyStep(center, ref _verifySolidCenter, ref _verifySolidScan, true)) break;
        }

        /// <summary>One block of a verify cycle. False when there is nothing to walk.</summary>
        private bool VerifyStep(Vector3Int center, ref int c, ref int scan, bool solid)
        {
            int centers = 1 + _interest.Count;
            if (c >= centers) { c = 0; scan = 0; }
            Vector3Int[] offs = c == 0 ? _offsets : _interestOffsets;
            if (scan >= offs.Length) { c++; scan = 0; return true; }
            Vector3Int b = (c == 0 ? center : _interest[c - 1]) + offs[scan++];
            if (solid) VerifySolid(b.x, b.y, b.z);
            else VerifyEmpty(b.x, b.y, b.z);
            return true;
        }

        private void VerifyEmpty(int x, int y, int z)
        {
            long key = Key(x, y, z);
            if (!_blocks.TryGetValue(key, out Block b) || b.Stale || b.Bits != null) return;
            var origin = new Vector3(x, y, z);
            if (!Overlaps(origin, origin + Vector3.one)) return;
            ProbeBlock(x, y, z, key);
            if (_blocks[key].Bits != null) ReportFix(x, y, z, "cached empty, has geometry");
        }

        private void VerifySolid(int x, int y, int z)
        {
            long key = Key(x, y, z);
            if (!_blocks.TryGetValue(key, out Block b) || b.Stale || b.Bits == null) return;
            var origin = new Vector3(x, y, z);
            var bits = new ulong[Res * Res * Res / 64];
            if (Overlaps(origin, origin + Vector3.one))
            {
                var probe = new Block { Bits = bits };
                Refine(probe, origin, 0, 0, 0, Res);
            }
            bool same = true;
            for (int i = 0; i < bits.Length && same; i++) same = bits[i] == b.Bits[i];
            if (same) return;
            ProbeBlock(x, y, z, key);
            ReportFix(x, y, z, "cached shape was out of date");
        }

        private void ReportFix(int x, int y, int z, string what)
        {
            VerifyFixes++;
            if (_verifyFixLogs >= 300) return;
            _verifyFixLogs++;
            _log.LogInfo($"collision verify: block {x},{y},{z} {what}; re-probed");
        }

        /// <summary>
        /// Append the known regions (Minecraft AABBs, 6 floats each) to <paramref name="dst"/> at
        /// box index <paramref name="at"/>, at most <paramref name="max"/>. Only space whose blocks
        /// are all in what the last <see cref="Collect"/> sent: the merged result covered its own
        /// snapshot's region, plus the blocks probed since, so a known region is clipped to that
        /// snapshot's region. Returns how many.
        /// </summary>
        public int CollectKnown(Vector3 mcPlayer, float[] dst, int at, int max)
        {
            if (max <= 0 || LastCollectTruncated) return 0;
            var center = new Vector3Int(Mathf.FloorToInt(mcPlayer.x), Mathf.FloorToInt(mcPlayer.y),
                                        Mathf.FloorToInt(mcPlayer.z));
            if (center != _scanCenter) return 0;      // the cache was not advanced for this center yet
            MergeResult r = _collected;
            int n = 0;
            int k = _knownIndex >= _offsets.Length ? RadiusH : _shellOf[_knownIndex] - 1;
            if (k >= 0)
            {
                int kv = Math.Min(k, RadiusV);
                Vector3Int mn = new Vector3Int(center.x - k, center.y - kv, center.z - k);
                Vector3Int mx = new Vector3Int(center.x + k, center.y + kv, center.z + k);
                if (r != null)
                {
                    mn = Vector3Int.Max(mn, new Vector3Int(r.Center.x - RadiusH, r.Center.y - RadiusV, r.Center.z - RadiusH));
                    mx = Vector3Int.Min(mx, new Vector3Int(r.Center.x + RadiusH, r.Center.y + RadiusV, r.Center.z + RadiusH));
                }
                if (mn.x <= mx.x && mn.y <= mx.y && mn.z <= mx.z)
                    PutBox(dst, at + n++, mn, mx);
            }
            foreach (Vector3Int ic in _interest)
            {
                if (n >= max) break;
                if (!_knownCubes.Contains(ic)) continue;
                if (r != null && !r.Interest.Contains(ic)) continue;
                PutBox(dst, at + n++, ic - Vector3Int.one * InterestR, ic + Vector3Int.one * InterestR);
            }
            return n;
        }

        /// <summary>Blocks mn..mx inclusive as a Minecraft AABB.</summary>
        private static void PutBox(float[] dst, int i, Vector3Int mn, Vector3Int mx)
        {
            int o = i * 6;
            dst[o] = mn.x; dst[o + 1] = mn.y; dst[o + 2] = mn.z;
            dst[o + 3] = mx.x + 1; dst[o + 4] = mx.y + 1; dst[o + 5] = mx.z + 1;
        }

        private static Vector3Int Floor(Vector3 p) =>
            new Vector3Int(Mathf.FloorToInt(p.x), Mathf.FloorToInt(p.y), Mathf.FloorToInt(p.z));

        /// <summary>
        /// Append merged voxel boxes within <paramref name="radius"/> blocks of the player to
        /// <paramref name="dst"/> starting at box index <paramref name="at"/>. Returns how many.
        /// </summary>
        public int Collect(Vector3 mcPlayer, float[] dst, int at, int max)
        {
            var center = new Vector3Int(Mathf.FloorToInt(mcPlayer.x), Mathf.FloorToInt(mcPlayer.y),
                                        Mathf.FloorToInt(mcPlayer.z));

            if (_rebuild != null && _rebuild.IsCompleted)
            {
                if (_rebuild.IsFaulted)
                    _log.LogError($"collision voxels: merge failed: {_rebuild.Exception?.GetBaseException().Message}");
                else if (_rebuild.Result.Generation == _generation)
                    _result = _rebuild.Result;
                _rebuild = null;
            }

            bool moved = Math.Abs(center.x - _collectCenter.x) > 2 || Math.Abs(center.y - _collectCenter.y) > 2
                      || Math.Abs(center.z - _collectCenter.z) > 2;
            if ((_dirty || moved) && _rebuild == null && Time.unscaledTime - _lastRebuild >= RebuildInterval)
            {
                _dirty = false;
                _collectCenter = center;
                _lastRebuild = Time.unscaledTime;
                var snapshot = new List<float[]>();
                var taken = new HashSet<long>();
                for (int i = 0; i < _offsets.Length; i++)
                {
                    Vector3Int b = center + _offsets[i];
                    long k = Key(b.x, b.y, b.z);
                    if (taken.Add(k) && _blocks.TryGetValue(k, out Block blk) && blk.Boxes != null)
                        snapshot.Add(blk.Boxes);
                }
                foreach (Vector3Int ic in _interest)
                    for (int i = 0; i < _interestOffsets.Length; i++)
                    {
                        Vector3Int b = ic + _interestOffsets[i];
                        long k = Key(b.x, b.y, b.z);
                        if (taken.Add(k) && _blocks.TryGetValue(k, out Block blk) && blk.Boxes != null)
                            snapshot.Add(blk.Boxes);
                    }
                uint gen = _generation;
                long seq = _probeSeq;
                var interestSet = new HashSet<Vector3Int>(_interest);
                Vector3Int snapCenter = center;
                _rebuild = Task.Run(() =>
                {
                    var res = Merge(snapshot, gen);
                    res.Seq = seq;
                    res.Center = snapCenter;
                    res.Interest = interestSet;
                    return res;
                });
            }

            MergeResult r = _result;
            int n = 0;
            long mergedSeq = 0;
            _collected = r != null && r.Generation == _generation ? r : null;
            LastCollectTruncated = false;
            if (r != null && r.Generation == _generation)
            {
                n = Math.Min(r.Boxes.Count, max);
                if (r.Boxes.Count > max) LastCollectTruncated = true;
                for (int i = 0; i < n; i++) Array.Copy(r.Boxes[i], 0, dst, (at + i) * 6, 6);
                mergedSeq = r.Seq;
            }
            // Then every solid block probed after that merge's snapshot, unmerged. Blocks since
            // replaced by a newer probe are skipped (the newer one is in the list too).
            _recent.RemoveAll(b => b.Seq <= mergedSeq);
            foreach (Block b in _recent)
            {
                if (b.Boxes == null || !_blocks.TryGetValue(Key(b.X, b.Y, b.Z), out Block cur) || cur != b) continue;
                if (n + b.Boxes.Length / 6 > max) LastCollectTruncated = true;
                for (int k = 0; k < b.Boxes.Length && n < max; k += 6, n++)
                    Array.Copy(b.Boxes, k, dst, (at + n) * 6, 6);
            }
            return n;
        }

        /// <summary>
        /// Mark cached blocks overlapping a Minecraft-space box stale, so they are probed again.
        /// Called when a collider appears, moves or disappears there. Returns how many.
        ///
        /// Stale blocks keep their boxes until the new probe replaces them. They used to be
        /// deleted, and a background merge that snapshotted the cache before the re-probe left
        /// those blocks out of what Minecraft received for at least a merge interval (0.25 s):
        /// the floor under the player vanished, the player sank in, and when it came back the
        /// player was inside it, which Minecraft's collision does not push out of, so they fell
        /// through (2026-10-15: "falling through the ground after passing doors", stairs).
        /// </summary>
        public int Invalidate(Vector3 mcMin, Vector3 mcMax)
        {
            if (_blocks.Count == 0) return 0;
            // Every cached block in the box, wherever it is. This used to be clamped to the cubes
            // being scanned around the player and entity clusters; but blocks are also cached
            // from earlier visits, entity clusters and look-ahead, and stay cached when the player
            // moves away. A room switched on while those blocks were out of the clamp kept them
            // "probed, empty" for good: the player walked onto them later and fell through, until
            // something else made them re-probe (2026-10-16 logs: floor "first seen 4.4 s ago",
            // block "probed, empty", a fresh probe "HIT").
            int x0 = Mathf.FloorToInt(mcMin.x), x1 = Mathf.FloorToInt(mcMax.x);
            int y0 = Mathf.FloorToInt(mcMin.y), y1 = Mathf.FloorToInt(mcMax.y);
            int z0 = Mathf.FloorToInt(mcMin.z), z1 = Mathf.FloorToInt(mcMax.z);
            long cells = (long)(x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
            int marked = 0;
            if (cells <= _blocks.Count)
            {
                for (int x = x0; x <= x1; x++)
                for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++)
                {
                    if (!_blocks.TryGetValue(Key(x, y, z), out Block b) || b.Stale) continue;
                    b.Stale = true;
                    marked++;
                }
            }
            else
            {
                // A huge collider: walk the cache instead of the box.
                foreach (Block b in _blocks.Values)
                {
                    if (b.Stale || b.X < x0 || b.X > x1 || b.Y < y0 || b.Y > y1 || b.Z < z0 || b.Z > z1) continue;
                    b.Stale = true;
                    marked++;
                }
            }
            if (marked > 0)
            {
                Invalidations += marked;
                _scanIndex = 0;     // resume the nearest-first scan so the nearest stale blocks go first
                _interestScan = 0;
                _interestDone.Clear();
            }
            return marked;
        }

        // ---- probing ----------------------------------------------------------------

        private void ProbeBlock(int bx, int by, int bz, long key)
        {
            // A stale block is replaced in one step: its old boxes stay in use until now.
            if (_blocks.TryGetValue(key, out Block old) && old.Boxes != null) BlocksSolid--;
            var block = new Block { X = bx, Y = by, Z = bz };
            _blocks[key] = block;
            _dirty = true;

            var origin = new Vector3(bx, by, bz);
            if (!Overlaps(origin, origin + Vector3.one)) return;

            block.Bits = new ulong[Res * Res * Res / 64];
            Refine(block, origin, 0, 0, 0, Res);
            if (IsEmpty(block.Bits)) { block.Bits = null; return; }
            block.Boxes = Greedy(block.Bits, origin);
            BlocksSolid++;
            block.Seq = ++_probeSeq;
            _recent.Add(block);
        }

        /// <summary>The cell at voxel (vx,vy,vz) of size s is known to overlap; test its 8 children.</summary>
        private void Refine(Block block, Vector3 origin, int vx, int vy, int vz, int s)
        {
            int h = s / 2;
            for (int dx = 0; dx < 2; dx++)
            for (int dy = 0; dy < 2; dy++)
            for (int dz = 0; dz < 2; dz++)
            {
                int cx = vx + dx * h, cy = vy + dy * h, cz = vz + dz * h;
                Vector3 mn = origin + new Vector3(cx, cy, cz) / Res;
                Vector3 mx = mn + Vector3.one * ((float)h / Res);
                if (!Overlaps(mn, mx)) continue;
                if (h == 1) Set(block.Bits, cx, cy, cz);
                else Refine(block, origin, cx, cy, cz, h);
            }
        }

        private bool Overlaps(Vector3 mcMin, Vector3 mcMax)
        {
            Vector3 e = Vector3.one * Eps;
            _puppet.McBoxToUk(mcMin + e, mcMax + e, out Vector3 c, out Vector3 half, out Quaternion rot);
            Probes++;
            int n = Physics.OverlapBoxNonAlloc(c, half, _buf, rot, _mask, QueryTriggerInteraction.Ignore);
            if (n >= _buf.Length) return true;    // buffer full: assume something relevant is in there
            for (int i = 0; i < n; i++)
            {
                Collider col = _buf[i];
                if (col != null && col.enabled && !_ignore(col)) return true;
            }
            return false;
        }

        // ---- merging ----------------------------------------------------------------

        private static int Index(int x, int y, int z) => x + y * Res + z * Res * Res;
        private static void Set(ulong[] b, int x, int y, int z) { int i = Index(x, y, z); b[i >> 6] |= 1UL << (i & 63); }
        private static bool Get(ulong[] b, int x, int y, int z) { int i = Index(x, y, z); return (b[i >> 6] & (1UL << (i & 63))) != 0; }
        private static bool IsEmpty(ulong[] b) { foreach (ulong w in b) if (w != 0) return false; return true; }

        /// <summary>Greedy-merge one block's voxels into boxes: extend along X, then Z, then Y.</summary>
        private static float[] Greedy(ulong[] bits, Vector3 origin)
        {
            var used = new ulong[bits.Length];
            var boxes = new List<float>();
            for (int y = 0; y < Res; y++)
            for (int z = 0; z < Res; z++)
            for (int x = 0; x < Res; x++)
            {
                if (!Get(bits, x, y, z) || Get(used, x, y, z)) continue;
                int x1 = x + 1;
                while (x1 < Res && Get(bits, x1, y, z) && !Get(used, x1, y, z)) x1++;
                int z1 = z + 1;
                while (z1 < Res && RowFree(bits, used, x, x1, y, z1)) z1++;
                int y1 = y + 1;
                while (y1 < Res && RectFree(bits, used, x, x1, y1, z, z1)) y1++;
                for (int yy = y; yy < y1; yy++)
                for (int zz = z; zz < z1; zz++)
                for (int xx = x; xx < x1; xx++) Set(used, xx, yy, zz);
                boxes.Add(origin.x + (float)x / Res); boxes.Add(origin.y + (float)y / Res); boxes.Add(origin.z + (float)z / Res);
                boxes.Add(origin.x + (float)x1 / Res); boxes.Add(origin.y + (float)y1 / Res); boxes.Add(origin.z + (float)z1 / Res);
            }
            return boxes.ToArray();
        }

        private static bool RowFree(ulong[] bits, ulong[] used, int x0, int x1, int y, int z)
        {
            for (int x = x0; x < x1; x++) if (!Get(bits, x, y, z) || Get(used, x, y, z)) return false;
            return true;
        }

        private static bool RectFree(ulong[] bits, ulong[] used, int x0, int x1, int y, int z0, int z1)
        {
            for (int z = z0; z < z1; z++) if (!RowFree(bits, used, x0, x1, y, z)) return false;
            return true;
        }

        /// <summary>
        /// Merge per-block boxes across block boundaries, so a flat floor becomes a few slabs
        /// instead of one box per block. Runs on a worker thread: touches no Unity API and
        /// copies every box before changing it. Coordinates are multiples of 1/8 at block
        /// scale, which floats hold exactly, so "touching" is an exact comparison.
        /// </summary>
        private static MergeResult Merge(List<float[]> blockBoxes, uint generation)
        {
            var sw = Stopwatch.StartNew();
            var boxes = new List<float[]>();
            foreach (float[] arr in blockBoxes)
                for (int k = 0; k < arr.Length; k += 6)
                {
                    var box = new float[6];
                    Array.Copy(arr, k, box, 0, 6);
                    boxes.Add(box);
                }
            boxes = MergeAlong(boxes, 0);   // X
            boxes = MergeAlong(boxes, 2);   // Z
            boxes = MergeAlong(boxes, 1);   // Y
            return new MergeResult { Generation = generation, Boxes = boxes, Ms = sw.Elapsed.TotalMilliseconds };
        }

        /// <summary>Merge boxes that touch along <paramref name="axis"/> and match exactly on the other two.</summary>
        private static List<float[]> MergeAlong(List<float[]> boxes, int axis)
        {
            int u = (axis + 1) % 3, v = (axis + 2) % 3;
            boxes.Sort((p, q) =>
            {
                int c = p[u].CompareTo(q[u]); if (c != 0) return c;
                c = p[u + 3].CompareTo(q[u + 3]); if (c != 0) return c;
                c = p[v].CompareTo(q[v]); if (c != 0) return c;
                c = p[v + 3].CompareTo(q[v + 3]); if (c != 0) return c;
                return p[axis].CompareTo(q[axis]);
            });
            var outList = new List<float[]>(boxes.Count);
            float[] cur = null;
            foreach (float[] b in boxes)
            {
                if (cur != null && cur[u] == b[u] && cur[u + 3] == b[u + 3] && cur[v] == b[v]
                    && cur[v + 3] == b[v + 3] && cur[axis + 3] == b[axis])
                {
                    cur[axis + 3] = b[axis + 3];
                    continue;
                }
                if (cur != null) outList.Add(cur);
                cur = b;
            }
            if (cur != null) outList.Add(cur);
            return outList;
        }

        // ---- housekeeping -------------------------------------------------------------

        private void Evict(Vector3Int center)
        {
            var drop = new List<long>();
            foreach (var kv in _blocks)
            {
                Block b = kv.Value;
                if (Math.Abs(b.X - center.x) <= RadiusH * 2 && Math.Abs(b.Y - center.y) <= RadiusV * 2
                    && Math.Abs(b.Z - center.z) <= RadiusH * 2) continue;
                bool nearEntities = false;
                foreach (Vector3Int ic in _interest)
                    if (Math.Abs(b.X - ic.x) <= InterestR * 2 && Math.Abs(b.Y - ic.y) <= InterestR * 2
                        && Math.Abs(b.Z - ic.z) <= InterestR * 2) { nearEntities = true; break; }
                if (!nearEntities) drop.Add(kv.Key);
            }
            foreach (long k in drop)
            {
                if (_blocks[k].Boxes != null) BlocksSolid--;
                _blocks.Remove(k);
            }
            _interestDone.Clear();
            _knownCubes.Clear();
            _knownIndex = 0;
            _dirty = true;
        }

        private static long Key(int x, int y, int z) =>
            ((long)(x & 0x1FFFFF) << 42) | ((long)(y & 0x1FFFFF) << 21) | (long)(z & 0x1FFFFF);
    }
}
