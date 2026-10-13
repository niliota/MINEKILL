using System;
using UnityEngine;
using UnityEngine.InputSystem;
using UltraCraft.Bridge;

namespace UltraCraft
{
    /// <summary>
    /// Forwards ULTRAKILL's keyboard and mouse into Minecraft.
    ///
    /// ULTRAKILL holds OS focus, so its keys go to ULTRAKILL. Minecraft is not focused and
    /// has no way to know you pressed anything. This samples ULTRAKILL's input and puts it
    /// on the bridge for the Fabric mod to apply to its own key bindings.
    ///
    /// Held keys are sent as bits rather than synthesised key events. Minecraft's
    /// <c>keyPress(window, key, KeyEvent)</c> takes no press/release flag, so a synthetic
    /// event cannot reliably distinguish the two and held keys would stick down. Setting
    /// the state directly is unambiguous. Discrete presses such as the inventory key are
    /// a separate mechanism and not handled here yet.
    ///
    /// Mouse delta is forwarded separately, so Minecraft still turns while its window is in
    /// the background and does not need the real cursor.
    /// </summary>
    public sealed class InputForwarder
    {
        private readonly BridgeTransport _bridge;
        private readonly BepInEx.Logging.ManualLogSource _log;

        private bool _lastInventory;
        private uint _lastKeys = uint.MaxValue;   // force first sample to log
        private byte _lastAttack = byte.MaxValue;
        private byte _lastUse = byte.MaxValue;
        private int _changes;
        private int _smallMoves;
        private bool _dumped;

        public InputForwarder(BridgeTransport bridge, BepInEx.Logging.ManualLogSource log)
        {
            _bridge = bridge;
            _log = log;
        }

        public void Tick()
        {
            if (_bridge == null || !_bridge.Connected) return;
            try
            {
                Sample();
            }
            catch (Exception e)
            {
                // Never let input handling take the game down.
                _log.LogWarning($"input: sampling failed: {e.GetType().Name}: {e.Message}");
            }
        }

        private void Sample()
        {
            uint keys = 0;
            if (Held(KeyCode.W)) keys |= Protocol.KEY_FORWARD;
            if (Held(KeyCode.S)) keys |= Protocol.KEY_BACK;
            if (Held(KeyCode.A)) keys |= Protocol.KEY_LEFT;
            if (Held(KeyCode.D)) keys |= Protocol.KEY_RIGHT;
            if (Held(KeyCode.Space)) keys |= Protocol.KEY_JUMP;
            // ULTRAKILL and Minecraft both use Left Shift for sneak.
            if (Held(KeyCode.LeftShift)) keys |= Protocol.KEY_SNEAK;
            // Minecraft sprints on Left Control by default.
            if (Held(KeyCode.LeftControl)) keys |= Protocol.KEY_SPRINT;
            // H is unbound in ULTRAKILL by default: lock/unlock the Minecraft player.
            if (Held(KeyCode.H)) keys |= Protocol.KEY_HOLD_TOGGLE;

            // Mouse movement as a raw per-frame delta.
            //
            // This reads Unity's Input System, NOT the legacy Input class, and the reason is
            // the whole bug this replaces.
            //
            // ULTRAKILL reads its own look input from the Input System:
            //
            //     CameraController.LateUpdate:
            //       Vector2 v = MonoSingleton<InputManager>.Instance
            //                       .InputSource.Look.ReadValue<Vector2>();
            //       rotationX += v.y * (opm.mouseSensitivity / 10f) * num;
            //       rotationY += v.x * (opm.mouseSensitivity / 10f) * num;
            //
            // The previous version of this file used the legacy
            // UnityEngine.Input.mousePosition and differenced it frame to frame. That is a
            // CURSOR POSITION, not a movement delta, and ULTRAKILL locks the cursor during
            // play, releasing it only on ESC. A locked cursor's position does not track
            // physical mouse movement, so the difference was ~0 for the entire time the
            // player was actually playing, and only became non-zero once the cursor was
            // freed. That produced exactly the reported symptom: the camera moved when ESC
            // was pressed, and never while playing. The flood of "mouse 0,0" and "1,0"
            // lines seen earlier was that position jittering, not real movement.
            //
            // Pointer.delta is a true movement delta and is independent of whether the
            // cursor is locked, so it works in both states.
            //
            // The value is deliberately NOT pre-scaled. ULTRAKILL applies its own
            // mouseSensitivity to the same unscaled figure, so forwarding it raw means
            // Minecraft's own sensitivity option governs look speed, exactly as in vanilla.
            // Scaling here instead would make ULTRAKILL's sensitivity slider silently also
            // drive Minecraft's camera.
            Vector2 delta = ReadMouseDelta();
            float dx = delta.x;
            float dy = delta.y;

            // Edge-triggered so a held key does not reopen the inventory every frame.
            bool inventory = UnityEngine.Input.GetKeyDown(KeyCode.E);
            bool inventoryEdge = inventory && !_lastInventory;
            _lastInventory = inventory;

            byte attack = UnityEngine.Input.GetMouseButton(0) ? (byte)1 : (byte)0;
            byte use = UnityEngine.Input.GetMouseButton(1) ? (byte)1 : (byte)0;

            _bridge.WriteSlot(Protocol.OFFSET_INPUT, new Protocol.SlotInput
            {
                tickTimestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                keys = keys,
                mouseDx = dx,
                mouseDy = dy,
                attack = attack,
                use = use,
                inventory = inventoryEdge ? (byte)1 : (byte)0,
            });

            // Reported for diagnostics only.
            //
            // This used to be the signal that told Minecraft to suppress its own cursor
            // grab, which in turn stopped Minecraft's grabMouse() from calling
            // KeyMapping.setAll() and wiping every forwarded key. Under Wine
            // Application.isFocused was false on almost every tick (flags 0 in the log,
            // with occasional spikes to 4), so the grab was almost never suppressed and
            // the keys kept getting cleared a frame after they were applied. Minecraft
            // now suppresses the grab whenever the bridge is connected, which is the
            // condition that actually matters, and does not depend on this flag at all.
            uint flags = UnityEngine.Application.isFocused
                ? Protocol.FLAG_UK_FOCUSED
                : 0u;
            _bridge.SetFlags(flags);

            // Mouse button state is logged on change of its own. It used to be folded in with the
            // key changes only, which made a click invisible in the log unless a key also
            // changed in the same sample. Left and right click doing nothing in Minecraft
            // was impossible to diagnose from this side: the log never mentioned buttons.
            //
            // Mouse motion is reported only when it is large enough to matter. Logging
            // every 1-pixel wobble produced 181 lines of "mouse 0,0" in one session and
            // buried everything else; the number of suppressed samples is carried into the
            // next real line so a session that sent nothing is still visible.
            bool moved = dx != 0f || dy != 0f;
            bool buttonChanged = attack != _lastAttack || use != _lastUse;
            if (keys != _lastKeys || moved || buttonChanged)
            {
                _changes++;
                // Mouse motion is throttled to whole lines only when it is large enough to
                // matter. Logging every 1-pixel wobble produced 181 lines of "mouse 0,0" in
                // one session and buried everything else; the count of omitted samples is
                // reported so a session where nothing was sent is still visible.
                if (moved && Mathf.Abs(dx) + Mathf.Abs(dy) >= 4f)
                {
                    _log.LogInfo(
                        $"input: keys=0x{keys:X} mouse {dx:F0},{dy:F0} " +
                        $"buttons L={attack} R={use}{(inventoryEdge ? " inv" : "")}");
                }
                else if (keys != _lastKeys || buttonChanged)
                {
                    _log.LogInfo(
                        $"input: keys=0x{keys:X} buttons L={attack} R={use}" +
                        $"{(inventoryEdge ? " inv" : "")}" +
                        (_smallMoves > 0 ? $" (+{_smallMoves} sub-threshold mouse moves)" : ""));
                }
                _smallMoves = moved ? _smallMoves + 1 : 0;
                _lastKeys = keys;
                _lastAttack = attack;
                _lastUse = use;
            }

            DumpOnce(keys, dx, dy, attack, use);
        }

        /// <summary>
        /// Hex dump the first 32 bytes of the INPUT slot, once per session.
        ///
        /// The generated constants on both sides can agree perfectly while the writer
        /// still puts the struct in the wrong place, and the layout check cannot see
        /// that. It shipped: the slot was written 4 bytes late, so Minecraft read the
        /// high word of tickTimestamp as the key flags. The symptom was a constant
        /// 0x1a1 that set KEY_FORWARD and KEY_SNEAK and looked like noise, because a
        /// value identical across every run is far more likely to be a constant than
        /// garbage. This dump makes any skew of that kind visible in one playtest.
        ///
        /// The offsets printed beside the dump are slot-relative and match what
        /// Java reads: keys at +12, mouseDx at +16, mouseDy at +20. Comparing the
        /// dump against the values on the same line shows immediately whether the
        /// writer put the struct where the reader expects it.
        /// </summary>
        private void DumpOnce(uint keys, float dx, float dy, byte attack, byte use)
        {
            if (_dumped) return;
            _dumped = true;
            _log.LogInfo(
                $"input: raw slot bytes @ {Protocol.OFFSET_INPUT}: {_bridge.DumpSlot(Protocol.OFFSET_INPUT, 32)}" +
                $" | wrote keys=0x{keys:X} mouseDx={dx} mouseDy={dy} attack={attack} use={use}" +
                $" | reader expects keys@{Protocol.OFFSET_INPUT + 12}" +
                $" mouseDx@{Protocol.OFFSET_INPUT + 16} mouseDy@{Protocol.OFFSET_INPUT + 20}" +
                $" attack@{Protocol.OFFSET_INPUT + 24} use@{Protocol.OFFSET_INPUT + 25}");
        }

        private static bool Held(KeyCode k)
        {
            return UnityEngine.Input.GetKey(k);
        }

        /// <summary>
        /// Raw mouse movement for this frame, or zero when there is no mouse to read.
        /// </summary>
        ///
        /// <remarks>
        /// <see cref="Pointer.current"/> is null until the Input System has seen its first
        /// event from a device, which happens after startup and not at any fixed moment, so
        /// it has to be treated as "no input this frame" rather than assumed present.
        /// Reporting that as a hard failure would be wrong too: a session where the mouse is
        /// genuinely untouched should look identical to a session before the device exists,
        /// and both are correctly reported as no movement.
        ///
        /// If ULTRAKILL's Input System ever fails to initialise, that is worth saying out
        /// loud, once, rather than letting the camera silently never move. That is a
        /// genuinely different problem from "the player is not moving the mouse", and it is
        /// the mistake this file already made once.
        /// </remarks>
        private Vector2 ReadMouseDelta()
        {
            Pointer mouse = Pointer.current;
            if (mouse == null)
            {
                if (!_warnedNoPointer)
                {
                    _warnedNoPointer = true;
                    _log.LogWarning(
                        "input: no mouse device visible to Unity's Input System. The camera " +
                        "will not turn. If this persists after the game has been running a " +
                        "moment, the Input System did not initialise.");
                }
                return Vector2.zero;
            }

            _warnedNoPointer = false;
            try
            {
                return mouse.delta.ReadValue();
            }
            catch (Exception e)
            {
                _log.LogWarning($"input: could not read mouse delta: {e.GetType().Name}: {e.Message}");
                return Vector2.zero;
            }
        }

        private bool _warnedNoPointer;
    }
}