using System;
using System.Collections.Generic;
using UnityEngine;
using UnityEngine.AI;
using Minekill.Bridge;

namespace Minekill
{
    /// <summary>
    /// Minecraft blocks, solid for ULTRAKILL. Minecraft sends the (merged) collision boxes of the
    /// blocks around the player (BLOCK_COLLISION); each becomes an invisible BoxCollider on an
    /// ULTRAKILL Environment layer - so enemy projectiles, physics-driven enemies, explosions and
    /// line-of-sight checks treat it as a wall - plus a carving NavMeshObstacle, because ULTRAKILL's
    /// ground enemies move on the navmesh, not by physics, and only an obstacle makes them path
    /// around a block. The objects live under one hidden root; CollisionPublisher and the voxel
    /// field ignore them, so blocks are never sent back to Minecraft as ULTRAKILL geometry.
    /// </summary>
    internal sealed class BlockColliders
    {
        /// <summary>
        /// Gap below a block (in blocks) that ULTRAKILL's walking enemies cannot fit under: a Stray's
        /// body is 4.7 units tall (2.4 blocks), a Filth's 3.9.
        /// </summary>
        private const float ClearanceBlocks = 2.5f;

        /// <summary>ULTRAKILL's "Environment" layer (in LayerMaskDefaults' Environment mask).</summary>
        private const int EnvironmentLayer = 8;

        private readonly BridgeTransport _bridge;
        private readonly PuppetController _puppet;
        private readonly BepInEx.Logging.ManualLogSource _log;
        private readonly float[] _boxes = new float[Protocol.TBL_BLOCK_COLLISION_CAPACITY * 6];
        private float[] _applied = new float[0];
        private uint _appliedGeneration = uint.MaxValue;
        private readonly List<GameObject> _pool = new List<GameObject>();
        private int _active;
        private float _next;

        private static Transform _root;

        /// <summary>True for colliders this class made (excluded from ULTRAKILL -> Minecraft collision).</summary>
        public static bool IsOurs(Collider c) => _root != null && c != null && c.transform.parent == _root;

        public BlockColliders(BridgeTransport bridge, PuppetController puppet, BepInEx.Logging.ManualLogSource log)
        {
            _bridge = bridge;
            _puppet = puppet;
            _log = log;
        }

        public int Active => _active;

        /// <summary>Every frame (Update).</summary>
        public void Tick()
        {
            if (Time.unscaledTime < _next) return;
            _next = Time.unscaledTime + 0.1f;
            if (!_puppet.MappingReady) { Apply(0); return; }
            int n = _bridge.ReadBlockCollision(_boxes, out long ts);
            if (n < 0) return;                                                          // writer busy
            if (DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - ts > 5000) n = 0;      // Minecraft gone
            if (n * 6 == _applied.Length && _appliedGeneration == _puppet.MappingGeneration)
            {
                bool same = true;
                for (int i = 0; i < n * 6 && same; i++) same = _boxes[i] == _applied[i];
                if (same) return;
            }
            Apply(n);
            _applied = new float[n * 6];
            Array.Copy(_boxes, _applied, n * 6);
            _appliedGeneration = _puppet.MappingGeneration;
        }

        private void Apply(int n)
        {
            if (n == 0 && _active == 0) return;
            if (_root == null)
            {
                var root = new GameObject("MINEKILL.BlockColliders") { hideFlags = HideFlags.HideAndDontSave };
                UnityEngine.Object.DontDestroyOnLoad(root);
                _root = root.transform;
            }
            for (int i = 0; i < n; i++)
            {
                int o = i * 6;
                _puppet.McBoxToUk(new Vector3(_boxes[o], _boxes[o + 1], _boxes[o + 2]),
                                  new Vector3(_boxes[o + 3], _boxes[o + 4], _boxes[o + 5]),
                                  out Vector3 center, out Vector3 half, out Quaternion rotation);
                GameObject go = i < _pool.Count ? _pool[i] : Create();
                go.transform.SetPositionAndRotation(center, rotation);
                Vector3 size = half * 2f;
                var box = go.GetComponent<BoxCollider>();
                if (box.size != size) box.size = size;
                // The navmesh lies on the floor and carving only cuts where an obstacle reaches it,
                // so a block hovering above the floor was walked under by enemies taller than the gap
                // (2026-10-14: "the Stray passes through blocks if their feet are exposed"). For the
                // navmesh, each box reaches down by an enemy's height: a gap lower than that is
                // closed, a higher one (under a roof or a bridge) stays walkable. The collider keeps
                // the block's real size.
                float reach = ClearanceBlocks * _puppet.Scale;
                var navSize = new Vector3(size.x, size.y + reach, size.z);
                var navCenter = new Vector3(0f, -reach * 0.5f, 0f);
                var obstacle = go.GetComponent<NavMeshObstacle>();
                if (obstacle.size != navSize) obstacle.size = navSize;
                if (obstacle.center != navCenter) obstacle.center = navCenter;
                if (!go.activeSelf) go.SetActive(true);
            }
            for (int i = n; i < _active; i++) _pool[i].SetActive(false);
            if (n != _active) _log.LogInfo($"blocks: {n} Minecraft collision boxes solid for ULTRAKILL");
            _active = n;
        }

        private GameObject Create()
        {
            var go = new GameObject("MINEKILL.Block") { hideFlags = HideFlags.HideAndDontSave, layer = EnvironmentLayer };
            go.transform.SetParent(_root, false);
            go.AddComponent<BoxCollider>();
            var obstacle = go.AddComponent<NavMeshObstacle>();
            obstacle.shape = NavMeshObstacleShape.Box;
            obstacle.center = Vector3.zero;
            obstacle.carving = true;
            obstacle.carveOnlyStationary = false;
            _pool.Add(go);
            return go;
        }
    }
}
