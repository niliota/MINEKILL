using System;
using System.Collections.Generic;
using HarmonyLib;
using UnityEngine;
using Minekill.Bridge;

namespace Minekill
{
    /// <summary>
    /// Combat between the two games (the owner's design, 2026-10-14):
    ///
    ///   - ULTRAKILL's live enemies are published (ENEMIES table) with their bounds in Minecraft
    ///     coordinates. Minecraft mirrors each one as an invisible proxy entity, so all of its own
    ///     combat applies to them (melee, bows, tridents, potions, enchantments, fire, mobs).
    ///     Every hit comes back as ENTITY_ATTACKED and is delivered with the enemy's own
    ///     EnemyIdentifier.DeliverDamage. Balance is proportional: Minecraft damage is delivered
    ///     1:1 as ULTRAKILL damage, i.e. an enemy's ULTRAKILL health counts as Minecraft health.
    ///   - Every hit ULTRAKILL lands on the player goes through NewMovement.GetHurt. A Harmony
    ///     prefix sends it to Minecraft instead (PLAYER_HURT), where it becomes Minecraft damage
    ///     (scaled 100 HP to 20); ULTRAKILL's own health is never touched.
    ///   - When Minecraft's player respawns after dying (PLAYER_RESPAWN), ULTRAKILL restarts from
    ///     its checkpoint (StatsManager.Restart, the "restart checkpoint" path). A level without a
    ///     checkpoint restarts its scene, and the usual spawn sync places Minecraft's player. With
    ///     a checkpoint, ULTRAKILL moves its player there; Minecraft's player is sent to the same
    ///     spot (TELEPORT).
    /// </summary>
    internal sealed class CombatBridge
    {
        /// <summary>Enemies farther than this (blocks) are not mirrored.</summary>
        private const float RangeBlocks = 64f;
        private const float PublishInterval = 0.1f;
        /// <summary>Minecraft damage to ULTRAKILL damage. 1 = an enemy's health counts as Minecraft health.</summary>
        public static float DamageScale = 1f;

        private readonly BridgeTransport _bridge;
        private readonly PuppetController _puppet;
        private readonly BepInEx.Logging.ManualLogSource _log;
        private readonly int[] _ids = new int[Protocol.TBL_ENEMIES_CAPACITY];
        private readonly float[] _data = new float[Protocol.TBL_ENEMIES_CAPACITY * 6];
        private readonly Dictionary<int, EnemyIdentifier> _byId = new Dictionary<int, EnemyIdentifier>();
        private float _next;
        private bool _patched;
        private int _hitsLogged;

        private static CombatBridge _instance;

        public CombatBridge(BridgeTransport bridge, PuppetController puppet, BepInEx.Logging.ManualLogSource log)
        {
            _bridge = bridge;
            _puppet = puppet;
            _log = log;
            _instance = this;
            Patch();
        }

        private void Patch()
        {
            try
            {
                var harmony = new Harmony("dev.minekill.ultrakill.combat");
                harmony.Patch(AccessTools.Method(typeof(NewMovement), nameof(NewMovement.GetHurt)),
                              prefix: new HarmonyMethod(typeof(CombatBridge), nameof(GetHurtPrefix)));
                _patched = true;
                _log.LogInfo("combat: ULTRAKILL damage to the player now goes to Minecraft");
            }
            catch (Exception e)
            {
                _log.LogError($"combat: could not patch NewMovement.GetHurt: {e.Message}");
            }
        }

        /// <summary>Every frame (Update, after the puppet).</summary>
        public void Tick()
        {
            HandleMinecraftEvents();
            if (Time.unscaledTime < _next) return;
            _next = Time.unscaledTime + PublishInterval;
            PublishEnemies();
        }

        // ---- enemies -> Minecraft -----------------------------------------------------

        private void PublishEnemies()
        {
            int n = 0;
            _byId.Clear();
            var tracker = MonoSingleton<EnemyTracker>.Instance;
            if (_puppet.MappingReady && tracker != null && _puppet.PlayerBody != null)
            {
                Vector3 mcPlayer = _puppet.UkToMc(_puppet.PlayerBody.position + _puppet.UkFeetOffset);
                foreach (EnemyIdentifier eid in tracker.enemies)
                {
                    if (n >= _ids.Length) break;
                    if (eid == null || eid.dead || !eid.gameObject.activeInHierarchy) continue;
                    if (!TryBounds(eid, out Bounds b)) continue;
                    McBounds(b, out Vector3 mn, out Vector3 mx);
                    var bottom = new Vector3((mn.x + mx.x) * 0.5f, mn.y, (mn.z + mx.z) * 0.5f);
                    if ((bottom - mcPlayer).sqrMagnitude > RangeBlocks * RangeBlocks) continue;
                    int id = eid.GetInstanceID();
                    _ids[n] = id;
                    int o = n * 6;
                    _data[o] = bottom.x; _data[o + 1] = bottom.y; _data[o + 2] = bottom.z;
                    _data[o + 3] = Mathf.Max(0.3f, Mathf.Max(mx.x - mn.x, mx.z - mn.z));
                    _data[o + 4] = Mathf.Max(0.3f, mx.y - mn.y);
                    _data[o + 5] = eid.health;
                    _byId[id] = eid;
                    n++;
                }
            }
            _bridge.WriteEnemiesTable(DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), _puppet.MappingGeneration, _ids, _data, n);
        }

        /// <summary>The enemy's body: its own collider, else its solid child colliders.</summary>
        private static bool TryBounds(EnemyIdentifier eid, out Bounds b)
        {
            b = default;
            bool any = false;
            var own = eid.GetComponent<Collider>();
            if (own != null && own.enabled) { b = own.bounds; return true; }
            foreach (Collider c in eid.GetComponentsInChildren<Collider>())
            {
                if (c == null || !c.enabled || c.isTrigger) continue;
                if (!any) { b = c.bounds; any = true; } else b.Encapsulate(c.bounds);
            }
            return any;
        }

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

        // ---- Minecraft -> ULTRAKILL ------------------------------------------------------

        private void HandleMinecraftEvents()
        {
            for (int i = 0; i < 64; i++)
            {
                if (!_bridge.TryPopEvent(false, out Protocol.RingUkEventsRecord e)) return;
                try
                {
                    if (e.kind == (uint)Protocol.EventKind.ENTITY_ATTACKED) HitEnemy(e.a, e.c, e.d != 0f);
                    else if (e.kind == (uint)Protocol.EventKind.PLAYER_RESPAWN) RestartCheckpoint();
                }
                catch (Exception ex)
                {
                    _log.LogWarning($"combat: event {e.kind} failed: {ex.Message}");
                }
            }
        }

        private void HitEnemy(int id, float mcDamage, bool fireOrExplosion)
        {
            if (!_byId.TryGetValue(id, out EnemyIdentifier eid) || eid == null || eid.dead) return;
            Vector3 center = TryBounds(eid, out Bounds b) ? b.center : eid.transform.position;
            Vector3 away = _puppet.PlayerBody != null ? (center - _puppet.PlayerBody.position).normalized : Vector3.zero;
            eid.hitter = fireOrExplosion ? "explosion" : "punch";
            float multiplier = mcDamage * DamageScale;
            float before = eid.health;
            eid.DeliverDamage(eid.gameObject, away * 1000f, center, multiplier, false);
            if (_hitsLogged < 20)
            {
                _hitsLogged++;
                _log.LogInfo($"combat: Minecraft hit {eid.enemyType} for {mcDamage:F1} -> ULTRAKILL {multiplier:F2} " +
                             $"(health {before:F2} -> {eid.health:F2}{(eid.dead ? ", dead" : "")})");
            }
        }

        private void RestartCheckpoint()
        {
            var stats = MonoSingleton<StatsManager>.Instance;
            if (stats == null) return;
            bool hasCheckpoint = stats.currentCheckPoint != null;
            _log.LogInfo($"combat: Minecraft player respawned; restarting ULTRAKILL {(hasCheckpoint ? "from the checkpoint" : "level")}");
            stats.Restart();
            if (!hasCheckpoint) return;   // a scene restart re-anchors through spawn sync
            // The checkpoint moved ULTRAKILL's player; send Minecraft's player to the same spot.
            var nm = MonoSingleton<NewMovement>.Instance;
            if (nm == null || !_puppet.MappingReady) return;
            Vector3 mc = _puppet.UkToMc(nm.transform.position + _puppet.UkFeetOffset);
            _bridge.TryPushEvent(true, new Protocol.RingUkEventsRecord
            {
                kind = (uint)Protocol.EventKind.TELEPORT,
                a = BitConverter.ToInt32(BitConverter.GetBytes(mc.x), 0),
                c = mc.y,
                d = mc.z,
            });
        }

        // ---- ULTRAKILL -> player ---------------------------------------------------------

        /// <summary>
        /// Harmony prefix on NewMovement.GetHurt. While Minecraft is connected and the player is
        /// mapped, the hit goes to Minecraft and ULTRAKILL's own health, death and hurt effects are
        /// skipped (Minecraft has its own invulnerability frames, hurt sound and red flash).
        /// Returning true runs the original (not bridged: vanilla ULTRAKILL).
        /// </summary>
        private static bool GetHurtPrefix(NewMovement __instance, int damage, bool invincible, bool explosion, bool ignoreInvincibility)
        {
            CombatBridge self = _instance;
            if (self == null || !self._bridge.Connected || !self._puppet.MappingReady) return true;
            if (__instance.dead || damage <= 0) return false;
            if (invincible && __instance.gameObject.layer == 15 && !ignoreInvincibility) return false;
            // Who hit: GetHurt does not say. A projectile right at the player means its shooter (the
            // enemy best lined up behind it); otherwise the nearest enemy. Minecraft attributes the
            // damage to that enemy's proxy, which is what lets a shield block it.
            int kind = explosion ? 2 : 0;
            int attacker = self.Attacker(__instance.transform.position, ref kind);
            self._bridge.TryPushEvent(true, new Protocol.RingUkEventsRecord
            {
                kind = (uint)Protocol.EventKind.PLAYER_HURT,
                a = Mathf.Min(damage, 0xFFFF) | (kind << 16),
                b = attacker,
            });
            return false;
        }

        public bool Patched => _patched;

        /// <summary>The id of the enemy that most likely landed this hit, or 0. Sets kind 1 for a projectile.</summary>
        private int Attacker(Vector3 player, ref int kind)
        {
            Projectile shot = null;
            float bestSq = (4f * _puppet.Scale) * (4f * _puppet.Scale);   // a projectile within 4 blocks
            foreach (Projectile p in UnityEngine.Object.FindObjectsOfType<Projectile>())
            {
                if (p == null || !p.isActiveAndEnabled) continue;
                float d = (p.transform.position - player).sqrMagnitude;
                if (d < bestSq) { bestSq = d; shot = p; }
            }
            if (shot != null && kind == 0) kind = 1;
            var tracker = MonoSingleton<EnemyTracker>.Instance;
            if (tracker == null) return 0;
            EnemyIdentifier best = null;
            float bestScore = float.MaxValue;
            foreach (EnemyIdentifier eid in tracker.enemies)
            {
                if (eid == null || eid.dead || !_byId.ContainsKey(eid.GetInstanceID())) continue;
                float score;
                if (shot != null)
                {
                    // Lined up behind the projectile's flight: smallest angle, then distance.
                    Vector3 to = eid.transform.position - shot.transform.position;
                    float angle = Vector3.Angle(-shot.transform.forward, to);
                    score = angle * 1000f + to.magnitude;
                }
                else score = (eid.transform.position - player).sqrMagnitude;
                if (score < bestScore) { bestScore = score; best = eid; }
            }
            return best != null ? best.GetInstanceID() : 0;
        }
    }
}
