using System;
using System.Runtime.InteropServices;
using Minekill.Bridge;

namespace Minekill
{
    /// <summary>
    /// Proves that what ULTRAKILL writes into the shared file is where Minecraft will
    /// read it from, before any of it depends on the player noticing.
    ///
    /// This exists because of a bug that shipped and was hard to see. The INPUT slot was
    /// written four bytes late, so Minecraft read the high 32 bits of <c>tickTimestamp</c>
    /// as the key flags. That value was <c>0x1a1</c>: bit 0 is KEY_FORWARD and bit 5 is
    /// KEY_SNEAK, so Minecraft walked forward and crouched continuously while ignoring
    /// every key press, and the camera never turned because the mouse deltas it received
    /// were the key bits reinterpreted as floats, which are denormals of about 2e-51.
    ///
    /// Three separate checks failed to catch that:
    ///
    ///   * The generated constants on both sides agreed perfectly. They were never wrong.
    ///   * <c>check-layout.sh</c> compares those constants, so it had nothing to compare
    ///     against. It cannot see where a struct gets written.
    ///   * The value looked like noise, because a constant does not look like a constant
    ///     when you expect garbage. The clue was that it was byte-for-byte identical in
    ///     runs fifteen minutes apart, which is why it now gets checked for being a
    ///     constant rather than dismissed as corruption.
    ///
    /// Two things are verified, in order:
    ///
    ///   1. <b>Struct layout.</b> Marshal's real field offsets are compared against the
    ///      offsets the generator emitted for the Java side. This catches padding,
    ///      alignment and <c>bool</c>-is-4-bytes mistakes, all of which have bitten
    ///      earlier revisions.
    ///   2. <b>Write alignment.</b> A struct full of unmistakable values is written
    ///      through the real <see cref="BridgeTransport.WriteSlot"/> path, then the
    ///      mapping is read back at the offsets a reader will use. This catches the
    ///      writer placing the struct at the wrong base, which is the bug above and the
    ///      one the layout check is structurally incapable of seeing.
    ///
    /// The probe is written to the INPUT slot only, and only when the bridge is already
    /// connected. Minecraft treats it as ordinary input for the one tick it is visible;
    /// the values are chosen so that if any of it were ever acted on, it would be
    /// obvious in the log rather than silently plausible.
    /// </summary>
    public static class SelfTest
    {
        /// <summary>
        /// Field offsets the generator emitted for Java's <c>SlotInput</c>, copied here
        /// deliberately rather than generated. A copy that can drift is the point: if
        /// the struct changes and this does not, the test fails and says so.
        /// </summary>
        private struct Expect
        {
            public string Field;
            public int Offset;
            public Expect(string field, int offset) { Field = field; Offset = offset; }
        }

        private static readonly Expect[] InputLayout =
        {
            new Expect("seq",          0),
            new Expect("tickTimestamp", 4),
            new Expect("keys",        12),
            new Expect("mouseDx",     16),
            new Expect("mouseDy",     20),
            new Expect("attack",      24),
            new Expect("use",         25),
            new Expect("inventory",   26),
            new Expect("hotbarSeq",   27),
            new Expect("hotbarSlot",  28),
            new Expect("scrollTotal", 29),
            new Expect("screenWidth", 33),
            new Expect("screenHeight", 35),
            new Expect("cursorX",     37),
            new Expect("cursorY",     41),
            new Expect("cursorActive", 45),
            new Expect("seqEnd",      46),
        };

        private const int ExpectedInputSize = 50;

        // Probe values. Deliberately not real input: a key bit pattern that is not
        // reachable from the keyboard, and floats that are not small enough to collapse
        // to a denormal, so a skew cannot hide by rounding.
        private const uint   ProbeKeys = 0xA5A5A5A5u;
        private const float  ProbeDx   = 1234.5f;
        private const float  ProbeDy   = -6789.25f;
        private const long   ProbeTick = 0x1122334455667788L;

        /// <summary>Returns true if everything checked out.</summary>
        public static bool Run(BridgeTransport bridge, BepInEx.Logging.ManualLogSource log)
        {
            bool ok = true;
            ok &= CheckStructLayout(log);
            ok &= CheckWriteAlignment(bridge, log);
            return ok;
        }

        /// <summary>
        /// Compare Marshal's actual layout against the generator's numbers. Uses
        /// <see cref="Marshal.OffsetOf{T}"/> rather than the generated constants, because
        /// the C# side deliberately has none: the source of truth is the generator, and
        /// the point here is to check the CLR agrees with it.
        /// </summary>
        private static bool CheckStructLayout(BepInEx.Logging.ManualLogSource log)
        {
            bool ok = true;

            int size = Marshal.SizeOf(typeof(Protocol.SlotInput));
            if (size != ExpectedInputSize)
            {
                log.LogError(
                    $"selftest: FAIL SlotInput is {size} bytes to the CLR but the " +
                    $"generator says {ExpectedInputSize}. Every field after the first " +
                    $"misaligned one will be read from the wrong place.");
                ok = false;
            }

            foreach (Expect e in InputLayout)
            {
                int actual = Marshal.OffsetOf(typeof(Protocol.SlotInput), e.Field).ToInt32();
                if (actual != e.Offset)
                {
                    log.LogError(
                        $"selftest: FAIL SlotInput.{e.Field} is at byte {actual} but " +
                        $"Java reads it at {e.Offset}. Off by {actual - e.Offset}.");
                    ok = false;
                }
            }

            if (ok)
                log.LogInfo($"selftest: struct layout OK, SlotInput is {size} bytes at the generated offsets");

            return ok;
        }

        /// <summary>
        /// Write a probe through the real write path and read it back the way a reader
        /// will. This is the check the layout script cannot do: it exercises
        /// <see cref="BridgeTransport.WriteSlot"/> itself, including the seqlock handling
        /// and the destination/source offsets, rather than trusting that they line up.
        /// </summary>
        private static bool CheckWriteAlignment(BridgeTransport bridge, BepInEx.Logging.ManualLogSource log)
        {
            if (bridge == null || !bridge.Connected)
            {
                log.LogWarning("selftest: bridge not connected, skipping the write alignment check");
                return true;    // nothing to check against; not a failure
            }

            bool ok = true;
            int baseOffset = Protocol.OFFSET_INPUT;

            bridge.WriteSlot(baseOffset, new Protocol.SlotInput
            {
                tickTimestamp = ProbeTick,
                keys = ProbeKeys,
                mouseDx = ProbeDx,
                mouseDy = ProbeDy,
                attack = 1,
                use = 1,
                inventory = 1,
            });

            byte[] raw = bridge.ReadSlot(baseOffset, ExpectedInputSize);
            log.LogInfo($"selftest: wrote a probe into the INPUT slot at {baseOffset}, raw bytes: " +
                        $"{bridge.DumpSlot(baseOffset, ExpectedInputSize)}");

            // Decode at the offsets a reader uses, relative to the slot base.
            long tick = ReadLong(raw, 4);
            uint keys = ReadUInt32(raw, 12);
            float dx = ReadSingle(raw, 16);
            float dy = ReadSingle(raw, 20);

            if (tick != ProbeTick)
            {
                log.LogError(
                    $"selftest: FAIL tickTimestamp read back as 0x{tick:X16}, wrote 0x{ProbeTick:X16}. " +
                    $"A reader would see a different value than was written.");
                ok = false;
            }
            if (keys != ProbeKeys)
            {
                log.LogError(
                    $"selftest: FAIL keys read back as 0x{keys:X8}, wrote 0x{ProbeKeys:X8}.");
                ok = false;
            }
            if (dx != ProbeDx || dy != ProbeDy)
            {
                log.LogError(
                    $"selftest: FAIL mouse delta read back as {dx},{dy}, wrote {ProbeDx},{ProbeDy}.");
                ok = false;
            }

            // Clear the probe at once. Minecraft reads this slot as real input, and the
            // probe's bits press forward, attack, inventory and (bit 7) the H lock toggle.
            // Left in place until the next forwarded sample, it could act on any of them.
            bridge.WriteSlot(baseOffset, new Protocol.SlotInput { tickTimestamp = ProbeTick + 1 });

            if (ok)
            {
                log.LogInfo(
                    $"selftest: write alignment OK, a reader will see keys=0x{keys:X8} " +
                    $"mouseDx={dx} mouseDy={dy}");
            }

            return ok;
        }

        private static uint ReadUInt32(byte[] b, int at)
        {
            return (uint)(b[at] | (b[at + 1] << 8) | (b[at + 2] << 16) | (b[at + 3] << 24));
        }

        private static long ReadLong(byte[] b, int at)
        {
            return (long)ReadUInt32(b, at) | ((long)ReadUInt32(b, at + 4) << 32);
        }

        private static unsafe float ReadSingle(byte[] b, int at)
        {
            uint bits = ReadUInt32(b, at);
            return *(float*)&bits;
        }
    }
}