using System;
using System.IO;
using System.IO.MemoryMappedFiles;
using System.Runtime.InteropServices;
using System.Threading;

namespace UltraCraft.Bridge
{
    /// <summary>
    /// The UltraCraft bridge transport.
    ///
    /// One memory-mapped file, opened by both processes. A mapped file rather than a
    /// Windows named shared-memory object, because Minecraft runs natively on Linux
    /// and ULTRAKILL runs inside Wine: a `Local\` object is invisible across that
    /// boundary, a mapped file is visible to both. Verified empirically in both
    /// directions on Wine 11.17 before this class was written.
    ///
    /// Layout is generated from bridge/protocol/generate.py. Do not hand-edit offsets.
    /// Every method here fails soft: a bridge problem must degrade to vanilla
    /// ULTRAKILL, never crash the game.
    /// </summary>
    public sealed class BridgeTransport : IDisposable
    {
        public const string DefaultFileName = "ultracraft.bridge";

        /// <summary>
        /// Environment variable that points both sides at the same file. This is an
        /// override for unusual setups; normally the shared default path below is
        /// used and no configuration is needed.
        /// </summary>
        public const string PathEnvVar = "ULTRACRAFT_BRIDGE";

        /// <summary>
        /// Where the bridge lives when nothing overrides it.
        ///
        /// This used to guess the Linux home, using three separate mechanisms. All
        /// three were wrong, in three different ways, because the answer depends on
        /// how the Wine prefix happens to be built:
        ///
        ///   - walking up the working directory looking for a "home" segment fails
        ///     when the game runs through a different drive mapping (one prefix on
        ///     this machine maps s: to /home, so the path reads S:\ash\... and there
        ///     is no "home" segment anywhere in it);
        ///   - USERPROFILE (C:\users\ash) is a real directory inside the prefix, NOT
        ///     the Linux home;
        ///   - USERPROFILE\Documents is a symlink to the Linux home in some prefixes
        ///     and a plain directory in others.
        ///
        /// The one anchor Wine always provides is Z:, mapped to the filesystem root.
        /// Addressing the file through it needs no username, no home directory and no
        /// assumptions about the prefix, so both sides name the same real file from
        /// opposite sides of the Wine boundary:
        ///
        ///     Z:\var\tmp\ultracraft.bridge  ==  /var/tmp/ultracraft.bridge
        ///
        /// A relative default must never be used here: each process would resolve it
        /// against its own working directory and quietly open two different files,
        /// which looks exactly like a dead bridge.
        /// </summary>
        public static string DefaultPath()
        {
            // Z: existing at all is the signal that we are under Wine on Linux.
            if (!System.IO.Directory.Exists("Z:\\")) return DefaultFileName;
            return @"Z:\var\tmp\" + DefaultFileName;
        }

        // header field offsets, from Protocol's Header layout
        private const int H_MAGIC = 0;
        private const int H_VERSION = 4;
        private const int H_HEADER_SIZE = 8;
        private const int H_MC_PID = 16;
        private const int H_UK_PID = 20;
        private const int H_MC_HEARTBEAT = 24;
        private const int H_UK_HEARTBEAT = 32;
        private const int H_MC_DROPPED = 40;
        private const int H_UK_DROPPED = 44;
        private const int H_UK_SPAWN_REQUEST = 48;
        private const int H_UK_FLAGS = 52;

        private readonly string _path;
        private MemoryMappedFile _mmf;
        private MemoryMappedViewAccessor _view;

        private int _heartbeatTick;
        private bool _disposed;

        public bool Connected { get; private set; }
        public string LastError { get; private set; }
        public string Path => _path;

        public BridgeTransport(string path)
        {
            // Explicit argument wins, then the environment variable, then the
            // shared absolute default. Both processes derive the same real file.
            string chosen = path;
            if (string.IsNullOrEmpty(chosen))
            {
                try { chosen = System.Environment.GetEnvironmentVariable(PathEnvVar); }
                catch { chosen = null; }
            }
            _path = string.IsNullOrEmpty(chosen) ? DefaultPath() : chosen;
        }

        /// <summary>
        /// Open or create the shared file. Returns false and sets LastError rather
        /// than throwing, so a failure degrades to vanilla ULTRAKILL.
        /// </summary>
        public bool Open()
        {
            if (Connected) return true;
            try
            {
                // Size the file, then let CreateFromFile own it.
                //
                // Do NOT hold a FileStream open across CreateFromFile: it opens the
                // same file a second time, and under Mono (ULTRAKILL's runtime) the
                // second open fails with "IOException: Could not open file". On .NET
                // Core the same mistake shows up as "being used by another process".
                // The mapping keeps the pages alive on its own, so there is no need
                // to hold a second handle.
                if (!File.Exists(_path))
                {
                    // The default lives under the user's home, so the directory may
                    // not exist yet.
                    string dir = System.IO.Path.GetDirectoryName(_path);
                    if (!string.IsNullOrEmpty(dir) && !Directory.Exists(dir))
                        Directory.CreateDirectory(dir);

                    using (var seed = File.Create(_path)) seed.SetLength(Protocol.TOTAL_SIZE);
                }
                else if (new FileInfo(_path).Length < Protocol.TOTAL_SIZE)
                {
                    // A file left by an older build is shorter (tables are appended after
                    // the rings). Grow it before mapping; the handle is closed again
                    // before CreateFromFile, for the reason above.
                    using (var grow = new FileStream(_path, FileMode.Open, FileAccess.ReadWrite,
                                                     FileShare.ReadWrite))
                        grow.SetLength(Protocol.TOTAL_SIZE);
                }

                _mmf = MemoryMappedFile.CreateFromFile(_path, FileMode.OpenOrCreate, null,
                                                      Protocol.TOTAL_SIZE,
                                                      MemoryMappedFileAccess.ReadWrite);
                _view = _mmf.CreateViewAccessor(0, Protocol.TOTAL_SIZE,
                                                MemoryMappedFileAccess.ReadWrite);

                if (_view.ReadUInt32(H_MAGIC) == 0)
                {
                    // Fresh file: lay down the header ourselves.
                    _view.Write(H_MAGIC, Protocol.MAGIC);
                    _view.Write(H_VERSION, Protocol.VERSION);
                    _view.Write(H_HEADER_SIZE, (uint)Protocol.HEADER_SIZE);
                    _view.Write(H_UK_PID, (uint)System.Diagnostics.Process.GetCurrentProcess().Id);
                }

                uint magic = _view.ReadUInt32(H_MAGIC);
                if (magic != Protocol.MAGIC)
                {
                    Fail($"bad magic 0x{magic:X8}, expected 0x{Protocol.MAGIC:X8}");
                    return false;
                }
                uint version = _view.ReadUInt32(H_VERSION);
                if (version != Protocol.VERSION)
                {
                    Fail($"protocol version {version} on the other side, this build speaks " +
                         $"{Protocol.VERSION}. Refusing to read it.");
                    return false;
                }

                _view.Write(H_UK_PID, (uint)System.Diagnostics.Process.GetCurrentProcess().Id);
                Connected = true;
                LastError = null;
                return true;
            }
            catch (Exception e)
            {
                Fail(e.GetType().Name + ": " + e.Message);
                return false;
            }
        }

        private void Fail(string why)
        {
            LastError = why;
            CloseHandles();
        }

        // ---- header ----------------------------------------------------------

        public uint McPid => Connected ? _view.ReadUInt32(H_MC_PID) : 0u;
        public uint UkPid => Connected ? _view.ReadUInt32(H_UK_PID) : 0u;
        public long McHeartbeat => Connected ? _view.ReadInt64(H_MC_HEARTBEAT) : 0L;
        public uint McDroppedEvents => Connected ? _view.ReadUInt32(H_MC_DROPPED) : 0u;
        public uint UkFlags => Connected ? _view.ReadUInt32(H_UK_FLAGS) : 0u;
        public uint UkSpawnRequest => Connected ? _view.ReadUInt32(H_UK_SPAWN_REQUEST) : 0u;

        public void SetFlags(uint flags) { if (Connected) _view.Write(H_UK_FLAGS, flags); }
        public void SetSpawnRequest(uint id) { if (Connected) _view.Write(H_UK_SPAWN_REQUEST, id); }

        public bool McMovementEnabled =>
            (UkFlags & Protocol.FLAG_UK_MOVEMENT) != 0u;
        public bool UkPaused => (UkFlags & Protocol.FLAG_UK_PAUSED) != 0u;

        /// <summary>
        /// Advance our heartbeat. Call about every 100ms.
        ///
        /// The value is EPOCH milliseconds, not "milliseconds since this process
        /// started". Each process starts its own clock at a different moment, so
        /// comparing two per-process elapsed times is meaningless; it only looked
        /// right when both games happened to launch at about the same time, and
        /// flagged a live peer as stale otherwise. Both sides must share a clock
        /// origin.
        /// </summary>
        public void TickHeartbeat()
        {
            if (!Connected) return;
            _view.Write(H_UK_HEARTBEAT, NowMs);
            if (++_heartbeatTick >= 120)
            {
                _view.Write(H_UK_PID, (uint)System.Diagnostics.Process.GetCurrentProcess().Id);
                _heartbeatTick = 0;
            }
        }

        /// <summary>Epoch milliseconds, shared with the Java side.</summary>
        private static long NowMs =>
            (long)(DateTime.UtcNow - UnixEpoch).TotalMilliseconds;

        private static readonly DateTime UnixEpoch =
            new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc);

        /// <summary>True once the other side's heartbeat has stopped moving.</summary>
        public bool McStale(int timeoutMs = 2000)
        {
            if (!Connected) return true;
            if (McPid == 0) return true;
            long hb = McHeartbeat;
            if (hb == 0) return true;
            return (NowMs - hb) > timeoutMs;
        }

        // ---- seqlock reads ---------------------------------------------------

        private byte[] _scratch = new byte[1024];

        /// <summary>
        /// Read a slot under its seqlock. Returns true only if the bytes in
        /// <paramref name="slot"/> came from a stable, non-being-written slot.
        /// A false return means "no clean data right now", not an error.
        /// </summary>
        public bool TryReadSlot(int offset, int size, out Protocol.SlotPlayer slot)
        {
            slot = default(Protocol.SlotPlayer);
            if (!Connected || size > _scratch.Length) return false;
            for (int attempt = 0; attempt < 3; attempt++)
            {
                uint s1 = _view.ReadUInt32(offset);
                if ((s1 & 1) != 0) { Thread.SpinWait(16); continue; }   // writer active
                _view.ReadArray(offset, _scratch, 0, size);
                uint s2 = _view.ReadUInt32(offset);
                if (s1 != s2) continue;                                  // torn, retry
                slot = StructFromBytes<Protocol.SlotPlayer>(_scratch, 0);
                return true;
            }
            return false;
        }

        public bool TryReadSlot(int offset, int size, out Protocol.SlotCamera slot)
        {
            slot = default(Protocol.SlotCamera);
            if (!Connected || size > _scratch.Length) return false;
            for (int attempt = 0; attempt < 3; attempt++)
            {
                uint s1 = _view.ReadUInt32(offset);
                if ((s1 & 1) != 0) { Thread.SpinWait(16); continue; }
                _view.ReadArray(offset, _scratch, 0, size);
                uint s2 = _view.ReadUInt32(offset);
                if (s1 != s2) continue;
                slot = StructFromBytes<Protocol.SlotCamera>(_scratch, 0);
                return true;
            }
            return false;
        }

        public bool TryReadSlot(int offset, int size, out Protocol.SlotInput slot)
        {
            slot = default(Protocol.SlotInput);
            if (!Connected || size > _scratch.Length) return false;
            for (int attempt = 0; attempt < 3; attempt++)
            {
                uint s1 = _view.ReadUInt32(offset);
                if ((s1 & 1) != 0) { Thread.SpinWait(16); continue; }
                _view.ReadArray(offset, _scratch, 0, size);
                uint s2 = _view.ReadUInt32(offset);
                if (s1 != s2) continue;
                slot = StructFromBytes<Protocol.SlotInput>(_scratch, 0);
                return true;
            }
            return false;
        }

        public bool TryReadSlot(int offset, int size, out Protocol.SlotSelection slot)
        {
            slot = default(Protocol.SlotSelection);
            if (!Connected || size > _scratch.Length) return false;
            for (int attempt = 0; attempt < 3; attempt++)
            {
                uint s1 = _view.ReadUInt32(offset);
                if ((s1 & 1) != 0) { Thread.SpinWait(16); continue; }
                _view.ReadArray(offset, _scratch, 0, size);
                uint s2 = _view.ReadUInt32(offset);
                if (s1 != s2) continue;
                slot = StructFromBytes<Protocol.SlotSelection>(_scratch, 0);
                return true;
            }
            return false;
        }

        public bool TryReadSlot(int offset, int size, out Protocol.SlotLevel slot)
        {
            slot = default(Protocol.SlotLevel);
            if (!Connected || size > _scratch.Length) return false;
            for (int attempt = 0; attempt < 3; attempt++)
            {
                uint s1 = _view.ReadUInt32(offset);
                if ((s1 & 1) != 0) { Thread.SpinWait(16); continue; }
                _view.ReadArray(offset, _scratch, 0, size);
                uint s2 = _view.ReadUInt32(offset);
                if (s1 != s2) continue;
                slot = StructFromBytes<Protocol.SlotLevel>(_scratch, 0);
                return true;
            }
            return false;
        }

        private static T StructFromBytes<T>(byte[] src, int offset) where T : struct
        {
            int size = Marshal.SizeOf(typeof(T));
            IntPtr p = Marshal.AllocHGlobal(size);
            try
            {
            for (int i = 0; i < size; i++) Marshal.WriteByte(p, i, src[offset + i]);
            return (T)Marshal.PtrToStructure(p, typeof(T));
            }
            finally { Marshal.FreeHGlobal(p); }
        }

        // ---- seqlock writes --------------------------------------------------

        public void BeginWrite(int offset)
        {
            uint s = _view.ReadUInt32(offset);
            _view.Write(offset, (s & ~1u) + 1u);      // odd means "being written"
        }

        public void EndWrite(int offset)
        {
            uint s = _view.ReadUInt32(offset);
            _view.Write(offset, (s | 1u) + 1u);       // back to even, advanced by 2
        }

        /// <summary>Bytes occupied by one seqlock word, at the head and the tail of a slot.</summary>
        private const int SeqlockBytes = 4;

        /// <summary>
        /// Write a slot struct atomically: odd seqlock, payload, even seqlock. A reader
        /// that catches the odd value retries rather than read half a struct.
        ///
        /// The bytes written are the struct <b>minus</b> its own leading <c>seq</c> and
        /// trailing <c>seqEnd</c> fields. Those two words belong to the seqlock and are
        /// owned by <see cref="BeginWrite"/> and <see cref="EndWrite"/>, which act on
        /// the slot base. The struct's copies must be skipped, or every field after
        /// <c>seq</c> lands 4 bytes late.
        ///
        /// That is not a hypothetical bug, it shipped once. The source offset here was
        /// hardcoded to 0 while only the destination offset was configurable, so
        /// <c>struct.seq</c> was written at offset+4 and Minecraft read the high 32
        /// bits of <c>tickTimestamp</c> as the key flags: 0x1a1, which sets KEY_FORWARD
        /// and KEY_SNEAK and therefore held Minecraft walking forward and crouching
        /// forever while every real key press was ignored. Because the high word of a
        /// millisecond epoch timestamp only changes about every 49.7 days, the symptom
        /// was a constant that looked exactly like noise.
        ///
        /// <c>SelfTest.WriteInputSentinel</c> and the matching hex dump on the
        /// Minecraft side exist so that any future skew of this kind is visible in the
        /// log instead of showing up as "the controls do nothing".
        /// </summary>
        public void WriteSlot(int offset, byte[] payload, int structSize)
        {
            BeginWrite(offset);
            _view.WriteArray(
                offset + SeqlockBytes,       // destination: just past the seqlock word
                payload,
                SeqlockBytes,                // source: skip the struct's duplicate seq
                structSize - SeqlockBytes * 2);
            EndWrite(offset);
        }

        /// <summary>
        /// Publish the COLLISION table: odd seqlock, header, <paramref name="count"/> boxes,
        /// even seqlock. <paramref name="boxes"/> is flat minX,minY,minZ,maxX,maxY,maxZ in
        /// Minecraft block coordinates.
        /// </summary>
        public void WriteCollisionTable(long tickTimestamp, uint generation, float[] boxes, int count)
        {
            if (!Connected) return;
            if (count > Protocol.TBL_COLLISION_CAPACITY) count = Protocol.TBL_COLLISION_CAPACITY;
            int b = Protocol.TBL_COLLISION_OFFSET;
            BeginWrite(b + Protocol.TBL_COLLISION_OFF_SEQ);
            _view.Write(b + Protocol.TBL_COLLISION_OFF_TICKTIMESTAMP, tickTimestamp);
            _view.Write(b + Protocol.TBL_COLLISION_OFF_GENERATION, generation);
            _view.Write(b + Protocol.TBL_COLLISION_OFF_COUNT, (uint)count);
            // Records are six contiguous floats with no padding, so one array write
            // lays them down at exactly the generated REC_OFF_* offsets.
            if (count > 0)
                _view.WriteArray(b + Protocol.TBL_COLLISION_OFF_RECORDS, boxes, 0, count * 6);
            EndWrite(b + Protocol.TBL_COLLISION_OFF_SEQ);
        }

        public void WriteSlot(int offset, Protocol.SlotInput value)
        {
            WriteSlot(offset, ToBytes(value), Marshal.SizeOf(typeof(Protocol.SlotInput)));
        }

        public void WriteSlot(int offset, Protocol.SlotLevel value)
        {
            WriteSlot(offset, ToBytes(value), Marshal.SizeOf(typeof(Protocol.SlotLevel)));
        }

        /// <summary>Read raw bytes straight out of the mapping, with no decoding.</summary>
        public byte[] ReadSlot(int offset, int count)
        {
            byte[] buf = new byte[count];
            for (int i = 0; i < count; i++) buf[i] = _view.ReadByte(offset + i);
            return buf;
        }

        /// <summary>
        /// Format raw slot bytes as hex for the log. Used to make a misalignment
        /// visible instead of having to infer it from gameplay symptoms.
        /// </summary>
        public string DumpSlot(int offset, int count)
        {
            System.Text.StringBuilder sb = new System.Text.StringBuilder(count * 3);
            byte[] raw = ReadSlot(offset, count);
            for (int i = 0; i < raw.Length; i++)
            {
                if (i > 0 && (i % 8) == 0) sb.Append(' ');
                sb.Append(raw[i].ToString("x2"));
            }
            return sb.ToString();
        }

        private static byte[] ToBytes<T>(T value) where T : struct
        {
            int size = Marshal.SizeOf(typeof(T));
            byte[] buf = new byte[size];
            IntPtr p = Marshal.AllocHGlobal(size);
            try
            {
                Marshal.StructureToPtr(value, p, false);
                Marshal.Copy(p, buf, 0, size);
                return buf;
            }
            finally { Marshal.FreeHGlobal(p); }
        }

        // ---- ring buffers ---------------------------------------------------

        /// <summary>
        /// Push one event. Returns false when the ring is full, in which case the
        /// event is dropped and the dropped counter is bumped. Dropping the newest is
        /// deliberate: a stale event is worse than a missing one.
        /// </summary>
        public bool TryPushEvent(bool fromUltrakill, Protocol.RingUkEventsRecord rec)
        {
            if (!Connected) return false;
            int ring = fromUltrakill ? Protocol.RINGUKEVENTS_OFFSET : Protocol.RINGMCEVENTS_OFFSET;
            int cap = fromUltrakill ? Protocol.RINGUKEVENTS_CAPACITY : Protocol.RINGMCEVENTS_CAPACITY;
            int recSize = fromUltrakill
                ? Protocol.RINGUKEVENTS_RECORD_SIZE : Protocol.RINGMCEVENTS_RECORD_SIZE;

            uint head = _view.ReadUInt32(ring + Protocol.RINGUKEVENTS_OFF_HEAD);
            uint tail = _view.ReadUInt32(ring + Protocol.RINGUKEVENTS_OFF_TAIL);
            if (head - tail >= (uint)cap)
            {
                int counter = fromUltrakill ? H_UK_DROPPED : H_MC_DROPPED;
                _view.Write(counter, _view.ReadUInt32(counter) + 1);
                return false;
            }
            int at = ring + Protocol.RINGUKEVENTS_OFF_RECORDS +
                     (int)(head % (uint)cap) * recSize;
            byte[] bytes = ToBytes(rec);
            _view.WriteArray(at, bytes, 0, recSize);
            _view.Write(ring + Protocol.RINGUKEVENTS_OFF_HEAD, head + 1);
            return true;
        }

        /// <summary>Pop one event. Returns false when the ring is empty.</summary>
        public bool TryPopEvent(bool fromUltrakill, out Protocol.RingUkEventsRecord rec)
        {
            rec = default(Protocol.RingUkEventsRecord);
            if (!Connected) return false;
            int ring = fromUltrakill ? Protocol.RINGUKEVENTS_OFFSET : Protocol.RINGMCEVENTS_OFFSET;
            int cap = fromUltrakill ? Protocol.RINGUKEVENTS_CAPACITY : Protocol.RINGMCEVENTS_CAPACITY;
            int recSize = fromUltrakill
                ? Protocol.RINGUKEVENTS_RECORD_SIZE : Protocol.RINGMCEVENTS_RECORD_SIZE;

            uint head = _view.ReadUInt32(ring + Protocol.RINGUKEVENTS_OFF_HEAD);
            uint tail = _view.ReadUInt32(ring + Protocol.RINGUKEVENTS_OFF_TAIL);
            if (head == tail) return false;
            int at = ring + Protocol.RINGUKEVENTS_OFF_RECORDS +
                     (int)(tail % (uint)cap) * recSize;
            byte[] buf = new byte[recSize];
            _view.ReadArray(at, buf, 0, recSize);
            rec = StructFromBytes<Protocol.RingUkEventsRecord>(buf, 0);
            _view.Write(ring + Protocol.RINGUKEVENTS_OFF_TAIL, tail + 1);
            return true;
        }

        // ---- lifecycle -------------------------------------------------------

        private void CloseHandles()
        {
            try { if (_view != null) _view.Dispose(); } catch { }
            try { if (_mmf != null) _mmf.Dispose(); } catch { }
            _view = null; _mmf = null;
            Connected = false;
        }

        public void Dispose()
        {
            if (_disposed) return;
            _disposed = true;
            if (Connected)
            {
                try
                {
                    // Clear our heartbeat and pid so the other side notices at once
                    // instead of waiting out the 2s timeout.
                    _view.Write(H_UK_HEARTBEAT, 0L);
                    _view.Write(H_UK_PID, 0u);
                }
                catch { }
            }
            CloseHandles();
        }
    }
}