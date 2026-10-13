package dev.ultracraft.bridge;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

/**
 * The STREAM file: bulk, variable-length data Minecraft sends to ULTRAKILL (texture atlas,
 * block meshes). Layout and protocol are in generate.py / PROTOCOL.md.
 *
 * <p>A single-producer single-consumer byte ring in a second mapped file next to the bridge
 * ({@code ultracraft.stream}). Minecraft is the only writer of HEAD and of the ring; ULTRAKILL
 * is the only writer of TAIL. Records never wrap: a record that does not fit before the end of
 * the ring is preceded by a PAD record filling the rest, and starts again at offset 0.
 *
 * <p>Writes never block. {@link #tryWrite} returns false when the ring is full (ULTRAKILL not
 * reading, or reading slowly) and the caller retries later, so a section stays dirty rather
 * than being lost.
 */
public final class Stream implements AutoCloseable {

    public static final String FILE_NAME = "ultracraft.stream";

    private final String path;
    private RandomAccessFile file;
    private MappedByteBuffer buf;
    private long head;
    private String lastError;

    private Stream(String path) {
        this.path = path;
    }

    /** Open the stream file next to the bridge file. Never throws; check {@link #ok()}. */
    public static Stream openNextTo(String bridgePath) {
        Path p = Path.of(bridgePath).resolveSibling(FILE_NAME);
        Stream s = new Stream(p.toString());
        try {
            s.doOpen();
        } catch (IOException | RuntimeException e) {
            s.lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            s.buf = null;
        }
        return s;
    }

    private void doOpen() throws IOException {
        file = new RandomAccessFile(path, "rw");
        if (file.length() < Protocol.STREAM_TOTAL_SIZE) file.setLength(Protocol.STREAM_TOTAL_SIZE);
        buf = file.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, Protocol.STREAM_TOTAL_SIZE);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        if (buf.getInt(Protocol.STREAM_OFF_MAGIC) == 0) {
            // Fresh file. Do not touch UK_RESYNC_REQUEST: ULTRAKILL may already have asked.
            buf.putInt(Protocol.STREAM_OFF_VERSION, Protocol.STREAM_VERSION);
            buf.putLong(Protocol.STREAM_OFF_HEAD, 0L);
            buf.putLong(Protocol.STREAM_OFF_TAIL, 0L);
            VarHandle.releaseFence();
            buf.putInt(Protocol.STREAM_OFF_MAGIC, Protocol.STREAM_MAGIC);
        }
        if (buf.getInt(Protocol.STREAM_OFF_MAGIC) != Protocol.STREAM_MAGIC
                || buf.getInt(Protocol.STREAM_OFF_VERSION) != Protocol.STREAM_VERSION) {
            throw new IOException("stream file has magic/version " + Integer.toHexString(buf.getInt(0))
                    + "/" + buf.getInt(4) + ", this build speaks " + Protocol.STREAM_VERSION);
        }
        // Continue from where the file is: ULTRAKILL's TAIL is relative to the same counter.
        head = buf.getLong(Protocol.STREAM_OFF_HEAD);
    }

    public boolean ok() { return buf != null; }
    public String path() { return path; }
    public String lastError() { return lastError; }

    public int resyncRequest() { return buf.getInt(Protocol.STREAM_OFF_UK_RESYNC_REQUEST); }
    public void markResyncServed(int request) { buf.putInt(Protocol.STREAM_OFF_MC_RESYNC_SERVED, request); }

    /** Bytes written but not yet consumed by ULTRAKILL. */
    public long pending() { return head - buf.getLong(Protocol.STREAM_OFF_TAIL); }

    private static int align8(int n) { return (n + 7) & ~7; }

    /**
     * Append one record made of {@code parts} (each read from position to limit, not consumed).
     *
     * @return false if the ring has no room right now; nothing was written
     */
    public boolean tryWrite(int type, ByteBuffer... parts) {
        if (buf == null) return false;
        long len = 0;
        for (ByteBuffer b : parts) len += b.remaining();
        final int ring = Protocol.STREAM_RING_SIZE;
        long recLong = align8((int) Math.min(len, Integer.MAX_VALUE - 16)) + Protocol.STREAM_RECORD_HEADER;
        if (len > ring / 2 || recLong > ring / 2) return false;   // could never fit with a PAD
        int rec = (int) recLong;

        long tail = buf.getLong(Protocol.STREAM_OFF_TAIL);
        VarHandle.acquireFence();
        long free = ring - (head - tail);
        int pos = (int) (head % ring);
        int contiguous = ring - pos;
        int needed = rec <= contiguous ? rec : rec + contiguous;
        if (needed > free) return false;

        if (rec > contiguous) {
            int at = Protocol.STREAM_RING_OFFSET + pos;
            buf.putInt(at, Protocol.STREAM_MSG_PAD);
            buf.putInt(at + 4, contiguous - Protocol.STREAM_RECORD_HEADER);
            head += contiguous;
            pos = 0;
        }

        int at = Protocol.STREAM_RING_OFFSET + pos;
        buf.putInt(at, type);
        buf.putInt(at + 4, (int) len);
        int o = at + Protocol.STREAM_RECORD_HEADER;
        for (ByteBuffer b : parts) {
            int n = b.remaining();
            buf.put(o, b, b.position(), n);
            o += n;
        }
        head += rec;
        // Publish only after every byte of the record is in place.
        VarHandle.releaseFence();
        buf.putLong(Protocol.STREAM_OFF_HEAD, head);
        return true;
    }

    @Override
    public void close() {
        try {
            if (file != null) file.close();
        } catch (IOException ignored) {
        }
        buf = null;
    }
}
