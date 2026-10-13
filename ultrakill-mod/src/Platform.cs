using System;
using System.IO;
using System.IO.MemoryMappedFiles;
using System.Runtime.InteropServices;

namespace Minekill.Bridge
{
    /// <summary>
    /// The two ways ULTRAKILL can run next to Minecraft: under Wine on Linux (Minecraft native
    /// Linux), or on native Windows (both native Windows).
    /// </summary>
    internal static class Platform
    {
        [DllImport("kernel32.dll", CharSet = CharSet.Ansi)]
        private static extern IntPtr GetModuleHandleA(string name);

        [DllImport("kernel32.dll", CharSet = CharSet.Ansi)]
        private static extern IntPtr GetProcAddress(IntPtr module, string name);

        private static bool? _isWine;

        /// <summary>
        /// Wine's ntdll exports wine_get_version; Windows' does not. This is Wine's own documented
        /// way to tell. (A Z: drive is not: a Windows machine can have one.)
        /// </summary>
        public static bool IsWine
        {
            get
            {
                if (_isWine == null)
                {
                    try
                    {
                        IntPtr ntdll = GetModuleHandleA("ntdll.dll");
                        _isWine = ntdll != IntPtr.Zero && GetProcAddress(ntdll, "wine_get_version") != IntPtr.Zero;
                    }
                    catch (Exception)
                    {
                        _isWine = Directory.Exists("Z:\\");   // P/Invoke unavailable: fall back to the old signal
                    }
                }
                return _isWine.Value;
            }
        }

        /// <summary>
        /// Map a file the other game maps too.
        ///
        /// Under Wine (Mono): CreateFromFile(path) itself, and no other handle held across it,
        /// because a second open of the same file failed there ("Could not open file").
        /// On native Windows: the path overload opens the file exclusively, which fails while
        /// Minecraft has it open (and would lock Minecraft out if ULTRAKILL came first), so the
        /// file is opened shared for reading and writing and mapped through that handle.
        /// </summary>
        public static MemoryMappedFile MapShared(string path, long size)
        {
            if (IsWine)
                return MemoryMappedFile.CreateFromFile(path, FileMode.OpenOrCreate, null, size, MemoryMappedFileAccess.ReadWrite);
            var fs = new FileStream(path, FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.ReadWrite | FileShare.Delete);
            try
            {
                return MemoryMappedFile.CreateFromFile(fs, null, size, MemoryMappedFileAccess.ReadWrite,
                                                       null, HandleInheritability.None, false);
            }
            catch
            {
                fs.Dispose();
                throw;
            }
        }
    }
}
