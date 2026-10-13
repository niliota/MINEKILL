using System;
using System.Diagnostics;
using System.IO;
using System.IO.MemoryMappedFiles;
using System.Threading;

namespace Minekill.Bridge
{
    /// <summary>
    /// Reader side of the STREAM file: bulk data Minecraft sends (texture atlas, block meshes).
    /// Layout and protocol are in generate.py / PROTOCOL.md.
    ///
    /// Single-producer single-consumer byte ring. Minecraft owns HEAD and the ring contents;
    /// this side owns TAIL. On open, everything pending is dropped (TAIL = HEAD) and a resync is
    /// requested, so Minecraft sends CLEAR_ALL, the atlas and every section again: the state
    /// on this side always starts from nothing.
    /// </summary>
    public sealed class StreamReader : IDisposable
    {
        public const string FileName = "minekill.stream";

        private MemoryMappedFile _mmf;
        private MemoryMappedViewAccessor _view;
        private byte[] _payload = new byte[1 << 20];
        private readonly Stopwatch _sw = new Stopwatch();

        public string Path { get; }
        public bool Ok => _view != null;
        public string LastError { get; private set; }
        public long BytesRead { get; private set; }
        public long Records { get; private set; }

        public StreamReader(string bridgePath)
        {
            string dir = System.IO.Path.GetDirectoryName(bridgePath) ?? ".";
            Path = System.IO.Path.Combine(dir, FileName);
        }

        public bool Open()
        {
            try
            {
                // Same rule as the bridge: never hold a FileStream across CreateFromFile (Mono).
                if (!File.Exists(Path) || new FileInfo(Path).Length < Protocol.STREAM_TOTAL_SIZE)
                {
                    using (var fs = new FileStream(Path, FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.ReadWrite))
                        fs.SetLength(Protocol.STREAM_TOTAL_SIZE);
                }
                _mmf = MemoryMappedFile.CreateFromFile(Path, FileMode.OpenOrCreate, null,
                                                      Protocol.STREAM_TOTAL_SIZE, MemoryMappedFileAccess.ReadWrite);
                _view = _mmf.CreateViewAccessor(0, Protocol.STREAM_TOTAL_SIZE, MemoryMappedFileAccess.ReadWrite);

                if (_view.ReadUInt32(Protocol.STREAM_OFF_MAGIC) == 0)
                {
                    _view.Write(Protocol.STREAM_OFF_VERSION, (uint)Protocol.STREAM_VERSION);
                    _view.Write(Protocol.STREAM_OFF_HEAD, 0L);
                    _view.Write(Protocol.STREAM_OFF_TAIL, 0L);
                    Thread.MemoryBarrier();
                    _view.Write(Protocol.STREAM_OFF_MAGIC, (uint)Protocol.STREAM_MAGIC);
                }
                if (_view.ReadUInt32(Protocol.STREAM_OFF_MAGIC) != (uint)Protocol.STREAM_MAGIC
                    || _view.ReadUInt32(Protocol.STREAM_OFF_VERSION) != (uint)Protocol.STREAM_VERSION)
                {
                    LastError = "stream magic/version mismatch";
                    Dispose();
                    return false;
                }
                RequestResync();
                return true;
            }
            catch (Exception e)
            {
                LastError = e.GetType().Name + ": " + e.Message;
                Dispose();
                return false;
            }
        }

        /// <summary>Drop everything pending and ask Minecraft to send its whole state again.</summary>
        public void RequestResync()
        {
            long head = _view.ReadInt64(Protocol.STREAM_OFF_HEAD);
            _view.Write(Protocol.STREAM_OFF_TAIL, head);
            uint req = _view.ReadUInt32(Protocol.STREAM_OFF_UK_RESYNC_REQUEST) + 1u;
            _view.Write(Protocol.STREAM_OFF_UK_RESYNC_REQUEST, req);
        }

        public uint ResyncRequested => Ok ? _view.ReadUInt32(Protocol.STREAM_OFF_UK_RESYNC_REQUEST) : 0u;
        public uint ResyncServed => Ok ? _view.ReadUInt32(Protocol.STREAM_OFF_MC_RESYNC_SERVED) : 0u;
        public long Pending => Ok ? _view.ReadInt64(Protocol.STREAM_OFF_HEAD) - _view.ReadInt64(Protocol.STREAM_OFF_TAIL) : 0;

        /// <summary>
        /// Hand complete records to <paramref name="handle"/> (type, payload, length) until the
        /// ring is empty or <paramref name="budgetMs"/> is spent. One record is always handled
        /// whole, however large (the atlas).
        /// </summary>
        public void Poll(double budgetMs, Action<int, byte[], int> handle)
        {
            if (!Ok) return;
            int ring = Protocol.STREAM_RING_SIZE;
            _sw.Restart();
            long head = _view.ReadInt64(Protocol.STREAM_OFF_HEAD);
            Thread.MemoryBarrier();     // record bytes are read only after HEAD
            long tail = _view.ReadInt64(Protocol.STREAM_OFF_TAIL);
            if (tail > head || head - tail > ring)
            {
                // Minecraft restarted onto a fresh file, or something is badly off: start over.
                RequestResync();
                return;
            }
            while (tail < head && _sw.Elapsed.TotalMilliseconds < budgetMs)
            {
                int pos = (int)(tail % ring);
                long at = Protocol.STREAM_RING_OFFSET + pos;
                int type = _view.ReadInt32(at);
                int len = _view.ReadInt32(at + 4);
                if (len < 0 || len > ring) { RequestResync(); return; }
                int rec = ((len + 7) & ~7) + Protocol.STREAM_RECORD_HEADER;
                if (type == Protocol.STREAM_MSG_PAD)
                {
                    tail += ring - pos;
                    continue;
                }
                if (_payload.Length < len) _payload = new byte[Math.Max(len, _payload.Length * 2)];
                if (len > 0) _view.ReadArray(at + Protocol.STREAM_RECORD_HEADER, _payload, 0, len);
                tail += rec;
                BytesRead += rec;
                Records++;
                handle(type, _payload, len);
            }
            Thread.MemoryBarrier();
            _view.Write(Protocol.STREAM_OFF_TAIL, tail);
        }

        public void Dispose()
        {
            try { _view?.Dispose(); } catch { }
            try { _mmf?.Dispose(); } catch { }
            _view = null;
            _mmf = null;
        }
    }
}
