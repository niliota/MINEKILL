package dev.minekill;

import dev.minekill.bridge.Bridge;
import dev.minekill.bridge.Protocol;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Husk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ULTRAKILL's enemies as Minecraft entities you can fight (combat design, 2026-10-14).
 *
 * <p>Each enemy in the ENEMIES table gets an invisible, silent, AI-less husk on the integrated
 * server, scaled (Attributes.SCALE, synced to the client) so its height matches the enemy's, and
 * moved to it every table update. Minecraft's whole combat then applies to it as to any mob:
 * crosshair targeting and reach, attack cooldown, crits, sweeping, enchantments, knockback,
 * arrows, tridents, splash potions, fire, explosions, other mobs. LivingEntityProxyMixin catches
 * the proxy's {@code hurtServer} before any health changes: the damage Minecraft computed is
 * forwarded to ULTRAKILL (ENTITY_ATTACKED) and the proxy itself never loses health. Proxies are
 * never saved, push nothing and are pushed by nothing.
 */
public final class EnemyProxies {
    private static final Logger LOG = LoggerFactory.getLogger("minekill");
    public static final String TAG = "minekill_proxy";
    private static final float HUSK_HEIGHT = 1.95f;
    /** Minecraft's hurt cooldown: within it, only a larger hit counts, by the difference. */
    private static final int INVULNERABLE_TICKS = 10;

    private record Hit(int ukId, float damage, boolean fireOrExplosion) {}

    /** Server-thread state. */
    private final Map<Integer, Husk> proxies = new HashMap<>();
    private int generation = Integer.MIN_VALUE;

    /** Entity id -> ULTRAKILL id, and per-proxy hurt cooldown; read from the server thread's hurt hook. */
    private static final Map<Integer, Integer> UK_ID = new ConcurrentHashMap<>();
    private static final Map<Integer, long[]> COOLDOWN = new ConcurrentHashMap<>();   // {gameTime, lastDamage bits}
    private static final ConcurrentLinkedQueue<Hit> HITS = new ConcurrentLinkedQueue<>();

    private final Bridge.EnemiesTable table = new Bridge.EnemiesTable();
    private long lastTimestamp = -1;
    private int hitsLogged;

    /** Client frame: hand fresh enemy data to the server thread, and send its hits to ULTRAKILL. */
    public void frame(Minecraft mc, Bridge bridge, boolean ukAlive) {
        for (Hit h; (h = HITS.poll()) != null; ) {
            bridge.pushEvent(new Bridge.Event(Protocol.EV_ENTITY_ATTACKED, h.ukId(), 0, h.damage(), h.fireOrExplosion() ? 1f : 0f));
            if (hitsLogged < 20) {
                hitsLogged++;
                LOG.info("[MINEKILL] combat: hit ULTRAKILL enemy {} for {}", h.ukId(), String.format("%.2f", h.damage()));
            }
        }
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) return;
        boolean fresh = ukAlive && bridge.readEnemiesTable(table) && table.tickTimestamp != lastTimestamp
                && System.currentTimeMillis() - table.tickTimestamp < 2000;
        if (!fresh && ukAlive) return;
        if (fresh) lastTimestamp = table.tickTimestamp;
        int n = fresh ? table.count : 0;
        int gen = table.generation;
        int[] ids = java.util.Arrays.copyOf(table.ids, n);
        float[] data = java.util.Arrays.copyOf(table.data, n * 6);
        var dimension = mc.player.level().dimension();
        server.execute(() -> {
            ServerLevel level = server.getLevel(dimension);
            if (level != null) sync(level, gen, ids, data);
        });
    }

    private void sync(ServerLevel level, int gen, int[] ids, float[] data) {
        if (gen != generation) {
            removeAll();
            generation = gen;
        }
        var seen = new java.util.HashSet<Integer>();
        for (int i = 0; i < ids.length; i++) {
            int id = ids[i], o = i * 6;
            seen.add(id);
            Husk proxy = proxies.get(id);
            if (proxy != null && (proxy.isRemoved() || proxy.level() != level)) {
                forget(proxy);
                proxies.remove(id);
                proxy = null;
            }
            if (proxy == null) {
                proxy = spawn(level, data[o], data[o + 1], data[o + 2]);
                if (proxy == null) continue;
                proxies.put(id, proxy);
                UK_ID.put(proxy.getId(), id);
                BY_UK_ID.put(id, proxy);
            }
            proxy.snapTo(data[o], data[o + 1], data[o + 2]);
            proxy.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            double scale = Math.clamp(data[o + 4] / HUSK_HEIGHT, 0.0625, 16.0);
            var attr = proxy.getAttribute(Attributes.SCALE);
            if (attr != null && Math.abs(attr.getBaseValue() - scale) > 0.02) attr.setBaseValue(scale);
        }
        for (Iterator<Map.Entry<Integer, Husk>> it = proxies.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (seen.contains(e.getKey())) continue;
            forget(e.getValue());
            e.getValue().discard();
            it.remove();
        }
    }

    private Husk spawn(ServerLevel level, double x, double y, double z) {
        Husk h = net.minecraft.world.entity.EntityTypes.HUSK.create(level, EntitySpawnReason.COMMAND);
        if (h == null) return null;
        h.addTag(TAG);
        h.setNoAi(true);
        h.setInvisible(true);
        h.setSilent(true);
        h.setNoGravity(true);
        h.setPersistenceRequired();
        // Death messages and the like name the attacker: not "Husk".
        h.setCustomName(net.minecraft.network.chat.Component.literal("an ULTRAKILL enemy"));
        h.setCustomNameVisible(false);
        h.noPhysics = true;
        // A husk is a zombie: when hit on Hard it may call reinforcements. Never.
        var reinforcements = h.getAttribute(Attributes.SPAWN_REINFORCEMENTS_CHANCE);
        if (reinforcements != null) reinforcements.setBaseValue(0);
        h.snapTo(x, y, z);
        level.addFreshEntity(h);
        return h;
    }

    private void forget(Entity e) {
        Integer ukId = UK_ID.remove(e.getId());
        if (ukId != null) BY_UK_ID.remove(ukId);
        COOLDOWN.remove(e.getId());
    }

    private void removeAll() {
        for (Husk h : proxies.values()) { forget(h); h.discard(); }
        proxies.clear();
    }

    private static final Map<Integer, Husk> BY_UK_ID = new ConcurrentHashMap<>();

    /** The proxy standing in for ULTRAKILL enemy {@code ukId}, or null (server thread). */
    public static Husk proxyFor(int ukId) {
        Husk h = BY_UK_ID.get(ukId);
        return h != null && !h.isRemoved() ? h : null;
    }

    /** For the client's copy of a proxy (same entity id in single-player), which has no tags. */
    public static boolean isProxyId(int entityId) {
        return UK_ID.containsKey(entityId);
    }

    public static boolean isProxy(Entity e) {
        return e.entityTags().contains(TAG);
    }

    /**
     * The proxy's hurtServer (server thread), before health changes. Returns whether the hit
     * landed (for the attacker: sound, knockback, durability, arrow consumed). Damage that is not
     * combat (suffocation in blocks, drowning, falling, the void) is ignored.
     */
    public static boolean onHurt(LivingEntity proxy, DamageSource source, float amount, long gameTime) {
        Integer ukId = UK_ID.get(proxy.getId());
        if (ukId == null || amount <= 0) return false;
        boolean fireOrExplosion = source.is(DamageTypeTags.IS_FIRE) || source.is(DamageTypeTags.IS_EXPLOSION);
        if (source.getEntity() == null && source.getDirectEntity() == null && !fireOrExplosion) return false;
        long[] cd = COOLDOWN.computeIfAbsent(proxy.getId(), k -> new long[] {Long.MIN_VALUE, 0});
        float dealt = amount;
        if (gameTime - cd[0] < INVULNERABLE_TICKS) {
            float last = Float.intBitsToFloat((int) cd[1]);
            if (amount <= last) return false;
            dealt = amount - last;      // as Minecraft does inside its hurt cooldown
        } else {
            cd[0] = gameTime;
        }
        cd[1] = Float.floatToIntBits(amount);
        HITS.add(new Hit(ukId, dealt, fireOrExplosion));
        return true;
    }
}
