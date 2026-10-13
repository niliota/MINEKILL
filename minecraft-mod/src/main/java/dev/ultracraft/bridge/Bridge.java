package dev.ultracraft.bridge;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The UltraCraft bridge transport, Minecraft side.
 *
 * <p>One memory-mapped file, opened by both processes. A mapped file rather than a
 * Windows named shared-memory object, because Minecraft runs natively on Linux and
 * ULTRAKILL runs inside Wine: a {@code Local\} object is invisible across that
 * boundary, a mapped file is visible to both. Verified empirically in both
 * directions on Wine 11.17 before this class was written.
 *
 * <p>Layout comes from {@link Protocol}, which is generated from
 * bridge/protocol/generate.py. Offsets are never written by hand, and
 * bridge/protocol/check-layout.sh proves the Java and C# views agree.
 *
 * <p>Everything fails soft. A bridge problem must degrade to plain Minecraft.
 */
public final class Bridge implements AutoCloseable {

    public static final String DEFAULT_FILE_NAME = "ultracraft.bridge";

    /**
     * Environment variable that points both sides at the same file. This is an
     * override for unusual setups; normally the shared default path below is used
     * and no configuration is needed.
     */
    public static final String PATH_ENV_VAR = "ULTRACRAFT_BRIDGE";

    /**
     * Where the bridge lives when nothing overrides it.
     *
     * <p>{@code /var/tmp} is chosen because the ULTRAKILL side can name the very same
     * file as {@code Z:/var/tmp/ultracraft.bridge}: Wine always maps {@code Z:} to the
     * filesystem root, so this needs no username, no home directory and no
     * assumptions about the prefix layout.
     *
     * <p>A path derived from the home directory was tried first and abandoned. Inside
     * Wine the home cannot be located reliably: {@code HOME} is null, {@code USERPROFILE}
     * is a real directory inside the prefix rather than the Linux home, and
     * {@code Documents} is a symlink to the Linux home in some prefixes but a plain
     * directory in others. Different prefixes gave three different wrong answers.
     *
     * <p>A RELATIVE default would also be wrong: each process resolves it against its
     * own working directory, so the two would quietly open different files.
     *
     * @return {@code /var/tmp/ultracraft.bridge}
     */
    public static String defaultPath() {
        return "/var/tmp/" + DEFAULT_FILE_NAME;
    }

    // header field offsets, matching Protocol's Header layout
    private static final int H_MAGIC = 0;
    private static final int H_VERSION = 4;
    private static final int H_HEADER_SIZE = 8;
    private static final int H_MC_PID = 16;
    private static final int H_UK_PID = 20;
    private static final int H_MC_HEARTBEAT = 24;
    private static final int H_UK_HEARTBEAT = 32;
    private static final int H_MC_DROPPED = 40;
    private static final int H_UK_DROPPED = 44;
    private static final int H_UK_SPAWN_REQUEST = 48;
    private static final int H_UK_FLAGS = 52;

    private final String path;
    private RandomAccessFile file;
    private FileChannel channel;
    private MappedByteBuffer buf;

    private final long t0 = System.nanoTime() / 1_000_000L;
    private int heartbeatTick;

    private boolean connected;
    private String lastError;

    private Bridge(String path) {
        this.path = path;
    }

    /**
     * Open (or create) the shared file.
     *
     * @return a connected bridge, or a disconnected one carrying an error message.
     *         Never throws, so a failure degrades to vanilla Minecraft.
     */
    public static Bridge open(String path) {
        // Explicit argument wins, then the environment variable, then the shared
        // absolute default. Never a bare relative name: see defaultPath().
        String chosen = path;
        if (chosen == null || chosen.isEmpty()) chosen = System.getenv(PATH_ENV_VAR);
        if (chosen == null || chosen.isEmpty()) chosen = defaultPath();
        Bridge b = new Bridge(chosen);
        try {
            b.doOpen();
        } catch (IOException | RuntimeException e) {
            b.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return b;
    }

    private void doOpen() throws IOException {
        Path p = Paths.get(path);
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        if (!Files.exists(p)) Files.createFile(p);

        file = new RandomAccessFile(p.toFile(), "rw");
        if (file.length() < Protocol.TOTAL_SIZE) {
            file.setLength(Protocol.TOTAL_SIZE);
        }
        channel = file.getChannel();
        buf = channel.map(FileChannel.MapMode.READ_WRITE, 0, Protocol.TOTAL_SIZE);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        if (buf.getInt(H_MAGIC) == 0) {
            // Fresh file: lay down the header ourselves.
            buf.putInt(H_MAGIC, Protocol.MAGIC);
            buf.putInt(H_VERSION, Protocol.VERSION);
            buf.putInt(H_HEADER_SIZE, Protocol.HEADER_SIZE);
            buf.putInt(H_UK_PID, 0);
        }

        int magic = buf.getInt(H_MAGIC);
        if (magic != Protocol.MAGIC) {
            fail(String.format("bad magic 0x%08X, expected 0x%08X", magic, Protocol.MAGIC));
            return;
        }
        int version = buf.getInt(H_VERSION);
        if (version != Protocol.VERSION) {
            fail("protocol version " + version + " on the other side, this build speaks "
                    + Protocol.VERSION + ". Refusing to read it.");
            return;
        }

        buf.putInt(H_MC_PID, pid());
        connected = true;
        lastError = null;
    }

    private void fail(String why) {
        lastError = why;
        closeHandles();
    }

    private static int pid() {
        // Java 9+ ProcessHandle. Avoids a JNI dependency.
        return (int) ProcessHandle.current().pid();
    }

    public boolean connected() { return connected; }
    public String lastError() { return lastError; }
    public String path() { return path; }

    // ---- header -----------------------------------------------------------

    public int ukPid() { return connected ? buf.getInt(H_UK_PID) : 0; }
    public long ukHeartbeat() { return connected ? buf.getLong(H_UK_HEARTBEAT) : 0L; }
    public long mcHeartbeat() { return connected ? buf.getLong(H_MC_HEARTBEAT) : 0L; }
    public int ukDroppedEvents() { return connected ? buf.getInt(H_UK_DROPPED) : 0; }
    public int ukSpawnRequest() { return connected ? buf.getInt(H_UK_SPAWN_REQUEST) : 0; }
    public int ukFlags() { return connected ? buf.getInt(H_UK_FLAGS) : 0; }

    public boolean ukPaused() { return (ukFlags() & Protocol.FLAG_UK_PAUSED) != 0; }
    public boolean ukMovementEnabled() { return (ukFlags() & Protocol.FLAG_UK_MOVEMENT) != 0; }

    /**
     * Advance our heartbeat. Call about every 100 ms.
     *
     * <p>The value is EPOCH milliseconds, not "milliseconds since this process
     * started". Each process starts its own clock at a different moment, so
     * comparing two per-process elapsed times is meaningless. It happened to look
     * right only when both games were launched at roughly the same moment, and
     * reported a live peer as stale otherwise. Both sides must use a clock origin
     * they agree on.
     */
    public void tickHeartbeat() {
        if (!connected) return;
        buf.putLong(H_MC_HEARTBEAT, System.currentTimeMillis());
        if (++heartbeatTick >= 120) {
            buf.putInt(H_MC_PID, pid());
            heartbeatTick = 0;
        }
    }

    private static long nowMs() { return System.currentTimeMillis(); }

    /** True once ULTRAKILL's heartbeat has stopped moving. */
    public boolean ukStale(int timeoutMs) {
        if (!connected) return true;
        if (ukPid() == 0) return true;
        long hb = ukHeartbeat();
        if (hb == 0) return true;
        return (nowMs() - hb) > timeoutMs;
    }

    // ---- seqlock ----------------------------------------------------------

    /**
     * Begin a seqlock read of a slot.
     *
     * @return the sequence number if the slot was stable and not being written,
     *         or -1 if no clean read was possible. -1 means "no new data right
     *         now", not an error.
     */
    public int beginRead(int offset, int size) {
        if (!connected) return -1;
        for (int attempt = 0; attempt < 3; attempt++) {
            int s1 = buf.getInt(offset);
            if ((s1 & 1) != 0) {           // writer active
                Thread.onSpinWait();
                continue;
            }
            byte[] copy = new byte[size];
            for (int i = 0; i < size; i++) {
                copy[i] = buf.get(offset + i);
            }
            int s2 = buf.getInt(offset);
            if (s1 != s2) {               // torn, retry
                Thread.onSpinWait();
                continue;
            }
            lastRead = copy;
            return s1;
        }
        return -1;
    }

    private byte[] lastRead;

    /** Confirm the slot did not change while we were reading it. */
    public boolean endRead(int offset, int seqStarted) {
        if (!connected) return false;
        return buf.getInt(offset) == seqStarted;
    }

    /** The payload from the last successful read. Do not retain. */
    public byte[] lastRead() { return lastRead; }

    private float f(int at) { return ByteBuffer.wrap(lastRead).order(ByteOrder.LITTLE_ENDIAN).getFloat(at); }
    private long l(int at) { return ByteBuffer.wrap(lastRead).order(ByteOrder.LITTLE_ENDIAN).getLong(at); }
    private boolean bit(int at) { return lastRead[at] != 0; }

    // ---- writes -----------------------------------------------------------

    public void beginWrite(int offset) {
        int s = buf.getInt(offset);
        buf.putInt(offset, (s & ~1) + 1);        // odd means "being written"
    }

    public void endWrite(int offset) {
        int s = buf.getInt(offset);
        buf.putInt(offset, (s | 1) + 1);        // back to even, advanced by 2
    }

    // ---- typed slot accessors ---------------------------------------------

    public static final class PlayerState {
        public double x, y, z;
        public float vx, vy, vz;
        public float yaw, pitch;
        public boolean onGround, sprinting, crouching, sneaking, inWater, onLadder;
        public float health, food, air;
        public long tickTimestamp;
        /** Last spawn request completed; see header ukSpawnRequest. */
        public int spawnAck;
    }

    /** Read the PLAYER slot ULTRAKILL drives its camera from. */
    public boolean readPlayer(PlayerState out) {
        int at = Protocol.OFFSET_PLAYER;
        int seq = beginRead(at, Protocol.SlotPlayer.SIZE);
        if (seq < 0) return false;
        out.tickTimestamp = l(Protocol.SlotPlayer.OFF_TICKTIMESTAMP);
        out.x = f(Protocol.SlotPlayer.OFF_POSX);
        out.y = f(Protocol.SlotPlayer.OFF_POSY);
        out.z = f(Protocol.SlotPlayer.OFF_POSZ);
        out.vx = f(Protocol.SlotPlayer.OFF_VELX);
        out.vy = f(Protocol.SlotPlayer.OFF_VELY);
        out.vz = f(Protocol.SlotPlayer.OFF_VELZ);
        out.yaw = f(Protocol.SlotPlayer.OFF_YAW);
        out.pitch = f(Protocol.SlotPlayer.OFF_PITCH);
        out.onGround = bit(Protocol.SlotPlayer.OFF_ONGROUND);
        out.sprinting = bit(Protocol.SlotPlayer.OFF_SPRINTING);
        out.crouching = bit(Protocol.SlotPlayer.OFF_CROUCHING);
        out.sneaking = bit(Protocol.SlotPlayer.OFF_SNEAKING);
        out.inWater = bit(Protocol.SlotPlayer.OFF_INWATER);
        out.onLadder = bit(Protocol.SlotPlayer.OFF_ONLADDER);
        out.health = f(Protocol.SlotPlayer.OFF_HEALTH);
        out.food = f(Protocol.SlotPlayer.OFF_FOOD);
        out.air = f(Protocol.SlotPlayer.OFF_AIR);
        out.spawnAck = i4(Protocol.SlotPlayer.OFF_SPAWNACK);
        return endRead(at, seq);
    }

    public static final class InputState {
        public int keys;
        public float mouseDx, mouseDy;
        public boolean attack, use, inventory;
        /** Bumps on each hotbar key press in ULTRAKILL; hotbarSlot is that press's slot 0-8. */
        public int hotbarSeq, hotbarSlot;
        /** Running sum of mouse-wheel steps in ULTRAKILL. */
        public float scrollTotal;
        /** ULTRAKILL's screen size in pixels (0 from an older build). */
        public int screenWidth, screenHeight;
        public long tickTimestamp;
    }

    /** Read the INPUT slot ULTRAKILL forwards its keyboard and mouse into. */
    /**
     * Hex dump of a slot's raw bytes, bypassing the seqlock.
     *
     * <p>Deliberately does not go through {@link #beginRead}, because the point is to see
     * what is physically in the file rather than a validated snapshot of it. This pairs
     * with ULTRAKILL's matching dump in InputForwarder: the two logs show the same bytes
     * from each end, so a disagreement about where a field landed is visible in one
     * playtest instead of being inferred from gameplay.
     *
     * <p>This earned its place. The INPUT slot used to be written four bytes late, so
     * {@code keys} was really the high word of {@code tickTimestamp} and read as
     * {@code 0x1a1}, which set KEY_FORWARD and KEY_SNEAK permanently. The generated
     * constants agreed on both sides the whole time and {@code check-layout.sh} had
     * nothing to catch it with, because it compares constants and cannot see where a
     * writer puts a struct.
     */
    public String dumpInputSlot() {
        int at = Protocol.OFFSET_INPUT;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Protocol.SlotInput.SIZE; i++) {
            if (i > 0 && (i % 8) == 0) sb.append(' ');
            sb.append(String.format("%02x", buf.get(at + i)));
        }
        return sb.toString();
    }

    public boolean readInput(InputState out) {
        int at = Protocol.OFFSET_INPUT;
        int seq = beginRead(at, Protocol.SlotInput.SIZE);
        if (seq < 0) return false;
        out.tickTimestamp = l(Protocol.SlotInput.OFF_TICKTIMESTAMP);
        out.keys = (int) i4(Protocol.SlotInput.OFF_KEYS);
        out.mouseDx = f(Protocol.SlotInput.OFF_MOUSEDX);
        out.mouseDy = f(Protocol.SlotInput.OFF_MOUSEDY);
        out.attack = bit(Protocol.SlotInput.OFF_ATTACK);
        out.use = bit(Protocol.SlotInput.OFF_USE);
        out.inventory = bit(Protocol.SlotInput.OFF_INVENTORY);
        out.hotbarSeq = lastRead[Protocol.SlotInput.OFF_HOTBARSEQ] & 0xFF;
        out.hotbarSlot = lastRead[Protocol.SlotInput.OFF_HOTBARSLOT] & 0xFF;
        out.scrollTotal = f(Protocol.SlotInput.OFF_SCROLLTOTAL);
        out.screenWidth = ByteBuffer.wrap(lastRead).order(ByteOrder.LITTLE_ENDIAN).getShort(Protocol.SlotInput.OFF_SCREENWIDTH) & 0xFFFF;
        out.screenHeight = ByteBuffer.wrap(lastRead).order(ByteOrder.LITTLE_ENDIAN).getShort(Protocol.SlotInput.OFF_SCREENHEIGHT) & 0xFFFF;
        return endRead(at, seq);
    }

    /** One consistent copy of the COLLISION table. Boxes are flat: minX,minY,minZ,maxX,maxY,maxZ. */
    public static final class CollisionTable {
        public long tickTimestamp;
        public int generation;
        public int count;
        public final float[] boxes = new float[Protocol.TBL_COLLISION_CAPACITY * 6];
    }

    /**
     * Read the COLLISION table under its seqlock.
     *
     * <p>Copies the header and only the {@code count} records in use, then re-checks the
     * sequence word, so the copy is never half of one list and half of the next. The
     * fences matter because this reads memory another process is writing; without them
     * the JIT may move the record reads outside the two sequence reads.
     *
     * @return false if no clean copy was possible right now (writer busy). Not an error;
     *         the caller keeps the previous copy.
     */
    public boolean readCollisionTable(CollisionTable out) {
        if (!connected) return false;
        final int base = Protocol.TBL_COLLISION_OFFSET;
        for (int attempt = 0; attempt < 3; attempt++) {
            int s1 = buf.getInt(base + Protocol.TBL_COLLISION_OFF_SEQ);
            if ((s1 & 1) != 0) { Thread.onSpinWait(); continue; }
            java.lang.invoke.VarHandle.acquireFence();

            long ts = buf.getLong(base + Protocol.TBL_COLLISION_OFF_TICKTIMESTAMP);
            int gen = buf.getInt(base + Protocol.TBL_COLLISION_OFF_GENERATION);
            int count = buf.getInt(base + Protocol.TBL_COLLISION_OFF_COUNT);
            if (count < 0 || count > Protocol.TBL_COLLISION_CAPACITY) {
                // Out of range is a torn or garbage read, never a real list.
                Thread.onSpinWait();
                continue;
            }
            int rec = base + Protocol.TBL_COLLISION_OFF_RECORDS;
            for (int i = 0; i < count; i++) {
                int at = rec + i * Protocol.TBL_COLLISION_RECORD_SIZE;
                out.boxes[i * 6]     = buf.getFloat(at + Protocol.TBL_COLLISION_REC_OFF_MINX);
                out.boxes[i * 6 + 1] = buf.getFloat(at + Protocol.TBL_COLLISION_REC_OFF_MINY);
                out.boxes[i * 6 + 2] = buf.getFloat(at + Protocol.TBL_COLLISION_REC_OFF_MINZ);
                out.boxes[i * 6 + 3] = buf.getFloat(at + Protocol.TBL_COLLISION_REC_OFF_MAXX);
                out.boxes[i * 6 + 4] = buf.getFloat(at + Protocol.TBL_COLLISION_REC_OFF_MAXY);
                out.boxes[i * 6 + 5] = buf.getFloat(at + Protocol.TBL_COLLISION_REC_OFF_MAXZ);
            }

            java.lang.invoke.VarHandle.acquireFence();
            int s2 = buf.getInt(base + Protocol.TBL_COLLISION_OFF_SEQ);
            if (s1 != s2) { Thread.onSpinWait(); continue; }
            out.tickTimestamp = ts;
            out.generation = gen;
            out.count = count;
            return true;
        }
        return false;
    }

    private int i4(int at) {
        return ByteBuffer.wrap(lastRead).order(ByteOrder.LITTLE_ENDIAN).getInt(at);
    }

    /*
 * The collision-correction slot is RESERVED and currently has no reader or writer on
 * either side. It was removed after the only writer turned out to be wrong: it computed
 * the position to send back as uk/scale + mcOrigin, but the forward mapping is
 * uk = ukAnchor + (mc - mcOrigin) * scale, so the inverse needs the ukAnchor term too.
 * Without it every correction teleported the player somewhere meaningless, tens of times
 * a second, and it was the cause of both the phantom diagonal drift and the camera
 * chaos.
 *
 * A push-back approximation is not sound anyway: it cannot express ULTRAKILL's geometry
 * to Minecraft. The correct approach, which is a later milestone, is to inject the host's
 * collision SHAPES into Minecraft's own collision queries so Minecraft's physics resolves
 * against them directly, the way SkyCraft does with Skyrim's. That will need this slot
 * again, with a different meaning, and should reuse this region.
 */

    public static final class CameraState {
        public float yaw, pitch, fov;
        /** Blocks from feet to eye in the current pose; the origin of Minecraft's crosshair ray. */
        public float eyeHeight;
    }

    public boolean readCamera(CameraState out) {
        int at = Protocol.OFFSET_CAMERA;
        int seq = beginRead(at, Protocol.SlotCamera.SIZE);
        if (seq < 0) return false;
        out.yaw = f(Protocol.SlotCamera.OFF_YAW);
        out.pitch = f(Protocol.SlotCamera.OFF_PITCH);
        out.fov = f(Protocol.SlotCamera.OFF_FOV);
        return endRead(at, seq);
    }

    public static final class LevelState {
        public int levelId;
        public float offsetX, offsetY, offsetZ, scale, yawOffset, pitchScale;
        public float minX, minY, minZ, maxX, maxY, maxZ;
    }

    public boolean readLevel(LevelState out) {
        int at = Protocol.OFFSET_LEVEL;
        int seq = beginRead(at, Protocol.SlotLevel.SIZE);
        if (seq < 0) return false;
        out.levelId = (int) l(Protocol.SlotLevel.OFF_LEVELID);
        out.offsetX = f(Protocol.SlotLevel.OFF_POSOFFSETX);
        out.offsetY = f(Protocol.SlotLevel.OFF_POSOFFSETY);
        out.offsetZ = f(Protocol.SlotLevel.OFF_POSOFFSETZ);
        out.scale = f(Protocol.SlotLevel.OFF_SCALE);
        out.yawOffset = f(Protocol.SlotLevel.OFF_YAWOFFSET);
        out.pitchScale = f(Protocol.SlotLevel.OFF_PITCHSCALE);
        out.minX = f(Protocol.SlotLevel.OFF_BOUNDSMINX);
        out.minY = f(Protocol.SlotLevel.OFF_BOUNDSMINY);
        out.minZ = f(Protocol.SlotLevel.OFF_BOUNDSMINZ);
        out.maxX = f(Protocol.SlotLevel.OFF_BOUNDSMAXX);
        out.maxY = f(Protocol.SlotLevel.OFF_BOUNDSMAXY);
        out.maxZ = f(Protocol.SlotLevel.OFF_BOUNDSMAXZ);
        return endRead(at, seq);
    }

    /**
     * Write the PLAYER slot. Minecraft owns the player, so this is the one value
     * ULTRAKILL depends on most.
     */
    /**
     * Write the GUI table under its seqlock: xyuv (4 floats per vertex), rgba and texture key per
     * vertex, plus Minecraft's GUI scale and size for ULTRAKILL's screen.
     */
    public void writeGuiTable(long ts, int count, int scale, int width, int height, float[] xyuv, int[] rgba, int[] tex) {
        if (!connected) return;
        if (count > Protocol.TBL_GUI_CAPACITY) count = Protocol.TBL_GUI_CAPACITY;
        int b = Protocol.TBL_GUI_OFFSET;
        beginWrite(b + Protocol.TBL_GUI_OFF_SEQ);
        buf.putLong(b + Protocol.TBL_GUI_OFF_TICKTIMESTAMP, ts);
        buf.putInt(b + Protocol.TBL_GUI_OFF_COUNT, count);
        buf.putInt(b + Protocol.TBL_GUI_OFF_GUISCALE, scale);
        buf.putInt(b + Protocol.TBL_GUI_OFF_GUIWIDTH, width);
        buf.putInt(b + Protocol.TBL_GUI_OFF_GUIHEIGHT, height);
        int r0 = b + Protocol.TBL_GUI_OFF_RECORDS;
        for (int i = 0; i < count; i++) {
            int at = r0 + i * Protocol.TBL_GUI_RECORD_SIZE, o = i * 4;
            buf.putFloat(at + Protocol.TBL_GUI_REC_OFF_X, xyuv[o]);
            buf.putFloat(at + Protocol.TBL_GUI_REC_OFF_Y, xyuv[o + 1]);
            buf.putFloat(at + Protocol.TBL_GUI_REC_OFF_U, xyuv[o + 2]);
            buf.putFloat(at + Protocol.TBL_GUI_REC_OFF_V, xyuv[o + 3]);
            buf.putInt(at + Protocol.TBL_GUI_REC_OFF_RGBA, rgba[i]);
            buf.putInt(at + Protocol.TBL_GUI_REC_OFF_TEX, tex[i]);
        }
        java.lang.invoke.VarHandle.releaseFence();
        endWrite(b + Protocol.TBL_GUI_OFF_SEQ);
    }

    /**
     * Write the PARTICLES table under its seqlock: x,y,z,size,u0,v0,u1,v1 (floats, 8 per particle)
     * and rgba (one int per particle).
     */
    public void writeParticleTable(long tickTimestamp, float[] rec, int[] rgba, int count) {
        if (!connected) return;
        if (count > Protocol.TBL_PARTICLES_CAPACITY) count = Protocol.TBL_PARTICLES_CAPACITY;
        int base = Protocol.TBL_PARTICLES_OFFSET;
        beginWrite(base + Protocol.TBL_PARTICLES_OFF_SEQ);
        buf.putLong(base + Protocol.TBL_PARTICLES_OFF_TICKTIMESTAMP, tickTimestamp);
        buf.putInt(base + Protocol.TBL_PARTICLES_OFF_COUNT, count);
        int r0 = base + Protocol.TBL_PARTICLES_OFF_RECORDS;
        for (int i = 0; i < count; i++) {
            int at = r0 + i * Protocol.TBL_PARTICLES_RECORD_SIZE, o = i * 8;
            buf.putFloat(at + Protocol.TBL_PARTICLES_REC_OFF_X, rec[o]);
            buf.putFloat(at + Protocol.TBL_PARTICLES_REC_OFF_Y, rec[o + 1]);
            buf.putFloat(at + Protocol.TBL_PARTICLES_REC_OFF_Z, rec[o + 2]);
            buf.putFloat(at + Protocol.TBL_PARTICLES_REC_OFF_SIZE, rec[o + 3]);
            buf.putFloat(at + Protocol.TBL_PARTICLES_REC_OFF_U0, rec[o + 4]);
            buf.putFloat(at + Protocol.TBL_PARTICLES_REC_OFF_V0, rec[o + 5]);
            buf.putFloat(at + Protocol.TBL_PARTICLES_REC_OFF_U1, rec[o + 6]);
            buf.putFloat(at + Protocol.TBL_PARTICLES_REC_OFF_V1, rec[o + 7]);
            buf.putInt(at + Protocol.TBL_PARTICLES_REC_OFF_RGBA, rgba[i]);
        }
        java.lang.invoke.VarHandle.releaseFence();
        endWrite(base + Protocol.TBL_PARTICLES_OFF_SEQ);
    }

    /** Write the SELECTION slot: what the crosshair targets (kind 0 none, 1 block, 2 placement). */
    public void writeSelection(int kind, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (!connected) return;
        int at = Protocol.OFFSET_SELECTION;
        beginWrite(at);
        buf.putInt(at + Protocol.SlotSelection.OFF_KIND, kind);
        buf.putFloat(at + Protocol.SlotSelection.OFF_MINX, (float) minX);
        buf.putFloat(at + Protocol.SlotSelection.OFF_MINY, (float) minY);
        buf.putFloat(at + Protocol.SlotSelection.OFF_MINZ, (float) minZ);
        buf.putFloat(at + Protocol.SlotSelection.OFF_MAXX, (float) maxX);
        buf.putFloat(at + Protocol.SlotSelection.OFF_MAXY, (float) maxY);
        buf.putFloat(at + Protocol.SlotSelection.OFF_MAXZ, (float) maxZ);
        endWrite(at);
    }

    public void writePlayer(PlayerState s) {
        int at = Protocol.OFFSET_PLAYER;
        beginWrite(at);
        buf.putLong(at + Protocol.SlotPlayer.OFF_TICKTIMESTAMP, s.tickTimestamp);
        buf.putFloat(at + Protocol.SlotPlayer.OFF_POSX, (float) s.x);
        buf.putFloat(at + Protocol.SlotPlayer.OFF_POSY, (float) s.y);
        buf.putFloat(at + Protocol.SlotPlayer.OFF_POSZ, (float) s.z);
        buf.putFloat(at + Protocol.SlotPlayer.OFF_VELX, s.vx);
        buf.putFloat(at + Protocol.SlotPlayer.OFF_VELY, s.vy);
        buf.putFloat(at + Protocol.SlotPlayer.OFF_VELZ, s.vz);
        buf.putFloat(at + Protocol.SlotPlayer.OFF_YAW, s.yaw);
        buf.putFloat(at + Protocol.SlotPlayer.OFF_PITCH, s.pitch);
        buf.put(at + Protocol.SlotPlayer.OFF_ONGROUND, (byte) (s.onGround ? 1 : 0));
        buf.put(at + Protocol.SlotPlayer.OFF_SPRINTING, (byte) (s.sprinting ? 1 : 0));
        buf.put(at + Protocol.SlotPlayer.OFF_CROUCHING, (byte) (s.crouching ? 1 : 0));
        buf.put(at + Protocol.SlotPlayer.OFF_SNEAKING, (byte) (s.sneaking ? 1 : 0));
        buf.put(at + Protocol.SlotPlayer.OFF_INWATER, (byte) (s.inWater ? 1 : 0));
        buf.put(at + Protocol.SlotPlayer.OFF_ONLADDER, (byte) (s.onLadder ? 1 : 0));
        buf.putFloat(at + Protocol.SlotPlayer.OFF_HEALTH, s.health);
        buf.putFloat(at + Protocol.SlotPlayer.OFF_FOOD, s.food);
        buf.putFloat(at + Protocol.SlotPlayer.OFF_AIR, s.air);
        buf.putInt(at + Protocol.SlotPlayer.OFF_SPAWNACK, s.spawnAck);
        endWrite(at);
    }

    public void writeCamera(CameraState c) {
        int at = Protocol.OFFSET_CAMERA;
        beginWrite(at);
        buf.putFloat(at + Protocol.SlotCamera.OFF_YAW, c.yaw);
        buf.putFloat(at + Protocol.SlotCamera.OFF_PITCH, c.pitch);
        buf.putFloat(at + Protocol.SlotCamera.OFF_FOV, c.fov);
        buf.putFloat(at + Protocol.SlotCamera.OFF_EYEHEIGHT, c.eyeHeight);
        endWrite(at);
    }

    // ---- events -----------------------------------------------------------

    public static final class Event {
        public int kind, a, b;
        public float c, d;

        public Event(int kind, int a, int b, float c, float d) {
            this.kind = kind; this.a = a; this.b = b; this.c = c; this.d = d;
        }
    }

    /**
     * Push one event to ULTRAKILL. Drops the newest event when the ring is full
     * and bumps the dropped counter, because a stale event is worse than a
     * missing one.
     */
    public boolean pushEvent(Event e) {
        if (!connected) return false;
        int ring = Protocol.OFFSET_MC_EVENTS;
        int cap = Protocol.RINGMCEVENTS_CAPACITY;
        int head = buf.getInt(ring + Protocol.RINGMCEVENTS_OFF_HEAD);
        int tail = buf.getInt(ring + Protocol.RINGMCEVENTS_OFF_TAIL);
        if (head - tail >= cap) {
            buf.putInt(H_MC_DROPPED, buf.getInt(H_MC_DROPPED) + 1);
            return false;
        }
        int at = ring + Protocol.RINGMCEVENTS_OFF_RECORDS
                + (head % cap) * Protocol.RINGMCEVENTS_RECORD_SIZE;
        buf.putInt(at + Protocol.RingMcEvents.Rec.OFF_KIND, e.kind);
        buf.putInt(at + Protocol.RingMcEvents.Rec.OFF_A, e.a);
        buf.putInt(at + Protocol.RingMcEvents.Rec.OFF_B, e.b);
        buf.putFloat(at + Protocol.RingMcEvents.Rec.OFF_C, e.c);
        buf.putFloat(at + Protocol.RingMcEvents.Rec.OFF_D, e.d);
        buf.putInt(ring + Protocol.RINGMCEVENTS_OFF_HEAD, head + 1);
        return true;
    }

    /** Pop one event from ULTRAKILL. Returns null when the ring is empty. */
    public Event popEvent() {
        if (!connected) return null;
        int ring = Protocol.OFFSET_UK_EVENTS;
        int cap = Protocol.RINGUKEVENTS_CAPACITY;
        int head = buf.getInt(ring + Protocol.RINGUKEVENTS_OFF_HEAD);
        int tail = buf.getInt(ring + Protocol.RINGUKEVENTS_OFF_TAIL);
        if (head == tail) return null;
        int at = ring + Protocol.RINGUKEVENTS_OFF_RECORDS
                + (tail % cap) * Protocol.RINGUKEVENTS_RECORD_SIZE;
        Event e = new Event(
                buf.getInt(at + Protocol.RingUkEvents.Rec.OFF_KIND),
                buf.getInt(at + Protocol.RingUkEvents.Rec.OFF_A),
                buf.getInt(at + Protocol.RingUkEvents.Rec.OFF_B),
                buf.getFloat(at + Protocol.RingUkEvents.Rec.OFF_C),
                buf.getFloat(at + Protocol.RingUkEvents.Rec.OFF_D));
        buf.putInt(ring + Protocol.RINGUKEVENTS_OFF_TAIL, tail + 1);
        return e;
    }

    // ---- lifecycle --------------------------------------------------------

    private void closeHandles() {
        try { if (channel != null && channel.isOpen()) channel.close(); } catch (IOException ignored) { }
        try { if (file != null) file.close(); } catch (IOException ignored) { }
        channel = null;
        file = null;
        buf = null;
        connected = false;
    }

    @Override
    public void close() {
        if (buf != null && connected) {
            try {
                // Clear heartbeat and pid so ULTRAKILL notices at once instead of
                // waiting out the 2s timeout.
                buf.putLong(H_MC_HEARTBEAT, 0L);
                buf.putInt(H_MC_PID, 0);
            } catch (RuntimeException ignored) { }
        }
        closeHandles();
    }
}