using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Threading.Tasks;
using UnityEngine;

namespace UltraCraft
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
        /// <summary>Minimum seconds between background merges.</summary>
        private const float RebuildInterval = 0.25f;
        private const int MaxBlocks = 60000;          // cache size before far blocks are evicted
        /// <summary>Probe boxes are shrunk by this (blocks) so a surface lying exactly on a
        /// cell boundary marks one cell, not both.</summary>
        private const float Eps = 0.004f;

        private sealed class Block
        {
            public int X, Y, Z;
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
        private int _mask;

        private uint _generation = uint.MaxValue;
        private Vector3Int _scanCenter = new Vector3Int(int.MinValue, 0, 0);
        private int _scanIndex;

        // Merged result. The merge sorts thousands of boxes, which done on the main thread at
        // every block boundary was a visible hitch, so it runs on a worker over a snapshot
        // of the per-block box arrays (immutable once built) and the result is swapped in.
        private bool _dirty = true;
        private Vector3Int _collectCenter = new Vector3Int(int.MinValue, 0, 0);
        private sealed class MergeResult { public uint Generation; public List<float[]> Boxes; public double Ms; }
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
            list.Sort((p, q) => p.sqrMagnitude.CompareTo(q.sqrMagnitude));
            _offsets = list.ToArray();
        }

        /// <summary>Probe more blocks, within the frame budget. Call every frame.</summary>
        public void Tick(int mask, Vector3 mcPlayer)
        {
            _mask = mask;
            if (_puppet.MappingGeneration != _generation)
            {
                // New mapping (level load): every cached cell is in the old frame.
                _generation = _puppet.MappingGeneration;
                _blocks.Clear();
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
                if (_blocks.Count > MaxBlocks) Evict(center);
            }

            _sw.Restart();
            while (_scanIndex < _offsets.Length && _sw.Elapsed.TotalMilliseconds < BudgetMs)
            {
                Vector3Int b = center + _offsets[_scanIndex++];
                long key = Key(b.x, b.y, b.z);
                if (_blocks.ContainsKey(key)) continue;
                ProbeBlock(b.x, b.y, b.z, key);
            }
            ProbeMs += _sw.Elapsed.TotalMilliseconds;
        }

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
                for (int i = 0; i < _offsets.Length; i++)
                {
                    Vector3Int b = center + _offsets[i];
                    if (_blocks.TryGetValue(Key(b.x, b.y, b.z), out Block blk) && blk.Boxes != null)
                        snapshot.Add(blk.Boxes);
                }
                uint gen = _generation;
                _rebuild = Task.Run(() => Merge(snapshot, gen));
            }

            MergeResult r = _result;
            if (r == null || r.Generation != _generation) return 0;
            int n = Math.Min(r.Boxes.Count, max);
            for (int i = 0; i < n; i++) Array.Copy(r.Boxes[i], 0, dst, (at + i) * 6, 6);
            return n;
        }

        /// <summary>
        /// Forget cached blocks overlapping a Minecraft-space box, so they are probed again.
        /// Called when a collider appears, moves or disappears there.
        /// </summary>
        public void Invalidate(Vector3 mcMin, Vector3 mcMax)
        {
            if (_blocks.Count == 0) return;
            // Only blocks the scan can reach matter; clamp a huge collider to that cube.
            int x0 = Math.Max(Mathf.FloorToInt(mcMin.x), _scanCenter.x - RadiusH);
            int x1 = Math.Min(Mathf.FloorToInt(mcMax.x), _scanCenter.x + RadiusH);
            int y0 = Math.Max(Mathf.FloorToInt(mcMin.y), _scanCenter.y - RadiusV);
            int y1 = Math.Min(Mathf.FloorToInt(mcMax.y), _scanCenter.y + RadiusV);
            int z0 = Math.Max(Mathf.FloorToInt(mcMin.z), _scanCenter.z - RadiusH);
            int z1 = Math.Min(Mathf.FloorToInt(mcMax.z), _scanCenter.z + RadiusH);
            int removed = 0;
            for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
            for (int z = z0; z <= z1; z++)
            {
                long key = Key(x, y, z);
                if (!_blocks.TryGetValue(key, out Block b)) continue;
                if (b.Boxes != null) BlocksSolid--;
                _blocks.Remove(key);
                removed++;
            }
            if (removed > 0)
            {
                Invalidations += removed;
                _dirty = true;
                _scanIndex = 0;     // resume the nearest-first scan so the gap refills first
            }
        }

        // ---- probing ----------------------------------------------------------------

        private void ProbeBlock(int bx, int by, int bz, long key)
        {
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
            _puppet.McBoxToUk(mcMin + e, mcMax - e, out Vector3 c, out Vector3 half, out Quaternion rot);
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
                if (Math.Abs(b.X - center.x) > RadiusH * 2 || Math.Abs(b.Y - center.y) > RadiusV * 2
                    || Math.Abs(b.Z - center.z) > RadiusH * 2)
                    drop.Add(kv.Key);
            }
            foreach (long k in drop)
            {
                if (_blocks[k].Boxes != null) BlocksSolid--;
                _blocks.Remove(k);
            }
            _dirty = true;
        }

        private static long Key(int x, int y, int z) =>
            ((long)(x & 0x1FFFFF) << 42) | ((long)(y & 0x1FFFFF) << 21) | (long)(z & 0x1FFFFF);
    }
}
