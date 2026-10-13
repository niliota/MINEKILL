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

        // ---- keyboard passthrough ------------------------------------------------------

        /// <summary>
        /// Unity key -> SDL3 scancode, which is what Minecraft 26.x's KeyboardHandler expects: its
        /// input moved from GLFW to SDL3 (InputConstants.KEY_ESCAPE = 41, KEY_E = 8, KEY_T = 23,
        /// KEY_SLASH = 56, verified with javap; KeyEvent.isEscape() is input() == 41). The first
        /// version sent GLFW codes, so E arrived as F12, Q as the Down arrow and Esc as nothing.
        /// </summary>
        private static readonly System.Collections.Generic.Dictionary<KeyCode, int> SdlKeys = BuildSdlKeys();
        private const int SdlEscape = 41;

        private static System.Collections.Generic.Dictionary<KeyCode, int> BuildSdlKeys()
        {
            var m = new System.Collections.Generic.Dictionary<KeyCode, int>();
            for (int i = 0; i < 26; i++) m[KeyCode.A + i] = 4 + i;          // A=4 .. Z=29
            for (int i = 1; i <= 9; i++) m[KeyCode.Alpha0 + i] = 29 + i;    // 1=30 .. 9=38
            m[KeyCode.Alpha0] = 39;
            for (int i = 0; i < 12; i++) m[KeyCode.F1 + i] = 58 + i;        // F1=58 .. F12=69
            m[KeyCode.Return] = 40; m[KeyCode.Escape] = SdlEscape; m[KeyCode.Backspace] = 42; m[KeyCode.Tab] = 43;
            m[KeyCode.Space] = 44; m[KeyCode.Minus] = 45; m[KeyCode.Equals] = 46; m[KeyCode.LeftBracket] = 47;
            m[KeyCode.RightBracket] = 48; m[KeyCode.Backslash] = 49; m[KeyCode.Semicolon] = 51; m[KeyCode.Quote] = 52;
            m[KeyCode.BackQuote] = 53; m[KeyCode.Comma] = 54; m[KeyCode.Period] = 55; m[KeyCode.Slash] = 56;
            m[KeyCode.Insert] = 73; m[KeyCode.Home] = 74; m[KeyCode.PageUp] = 75; m[KeyCode.Delete] = 76;
            m[KeyCode.End] = 77; m[KeyCode.PageDown] = 78; m[KeyCode.RightArrow] = 79; m[KeyCode.LeftArrow] = 80;
            m[KeyCode.DownArrow] = 81; m[KeyCode.UpArrow] = 82; m[KeyCode.KeypadEnter] = 88;
            m[KeyCode.LeftControl] = 224; m[KeyCode.LeftShift] = 225; m[KeyCode.LeftAlt] = 226;
            m[KeyCode.RightControl] = 228; m[KeyCode.RightShift] = 229; m[KeyCode.RightAlt] = 230;
            return m;
        }

        /// <summary>Outside Minecraft screens, only these go to Minecraft as key events.</summary>
        private static readonly KeyCode[] GameplayKeys = { KeyCode.T, KeyCode.Slash, KeyCode.E, KeyCode.Q, KeyCode.BackQuote };
        /// <summary>Held editing keys repeat inside screens, like typing in Minecraft's own window.</summary>
        private static readonly KeyCode[] RepeatKeys =
            { KeyCode.Backspace, KeyCode.Delete, KeyCode.LeftArrow, KeyCode.RightArrow, KeyCode.UpArrow, KeyCode.DownArrow };
        private KeyCode _repeatKey = KeyCode.None;
        private float _repeatAt;

        /// <summary>
        /// Key and character events for Minecraft (UK_EVENTS ring). Outside a Minecraft screen: T
        /// (chat), / (command line), E (inventory), Q (drop), and ~ which is sent as Esc to open
        /// Minecraft's pause menu (Esc itself stays ULTRAKILL's). Inside a Minecraft screen: every
        /// mapped key, held editing keys repeating, typed characters, and Esc goes to Minecraft
        /// (ULTRAKILL's pause key is held off meanwhile, see HudView.LateTick).
        /// </summary>
        private void ForwardKeyEvents(bool screenOpen)
        {
            // SDL modifier bits (InputConstants.MOD_SHIFT = 3, MOD_CONTROL = 192, MOD_ALT = 768).
            int mods = (Held(KeyCode.LeftShift) ? 0x1 : 0) | (Held(KeyCode.RightShift) ? 0x2 : 0)
                     | (Held(KeyCode.LeftControl) ? 0x40 : 0) | (Held(KeyCode.RightControl) ? 0x80 : 0)
                     | (Held(KeyCode.LeftAlt) ? 0x100 : 0) | (Held(KeyCode.RightAlt) ? 0x200 : 0);
            if (screenOpen)
            {
                foreach (var kv in SdlKeys)
                {
                    int code = kv.Key == KeyCode.BackQuote ? SdlEscape : kv.Value;   // ` closes screens too
                    if (UnityEngine.Input.GetKeyDown(kv.Key))
                    {
                        PushKey(code, 1, mods);
                        if (System.Array.IndexOf(RepeatKeys, kv.Key) >= 0) { _repeatKey = kv.Key; _repeatAt = Time.unscaledTime + 0.5f; }
                    }
                    if (UnityEngine.Input.GetKeyUp(kv.Key))
                    {
                        PushKey(code, 0, mods);
                        if (_repeatKey == kv.Key) _repeatKey = KeyCode.None;
                    }
                }
                if (_repeatKey != KeyCode.None && Held(_repeatKey) && Time.unscaledTime >= _repeatAt)
                {
                    PushKey(SdlKeys[_repeatKey], 2, mods);
                    _repeatAt = Time.unscaledTime + 0.05f;
                }
                foreach (char ch in UnityEngine.Input.inputString)
                    if (ch >= 32 && ch != 127) PushEvent(Protocol.EventKind.CHAR, ch, 0);   // control chars come as keys
            }
            else
            {
                _repeatKey = KeyCode.None;
                foreach (KeyCode k in GameplayKeys)
                {
                    int code = k == KeyCode.BackQuote ? SdlEscape : SdlKeys[k];
                    if (UnityEngine.Input.GetKeyDown(k)) PushKey(code, 1, mods);
                    if (UnityEngine.Input.GetKeyUp(k)) PushKey(code, 0, mods);
                }
            }
        }

        private void PushKey(int scancode, int action, int mods) =>
            PushEvent(Protocol.EventKind.KEY, SdlKeycode(scancode), action | (mods << 8) | (scancode << 22));

        /// <summary>
        /// The SDL keycode for a scancode, which Minecraft's text editing switches on (EditBox:
        /// shortcutKey() 8 = Backspace, 127 = Delete, 0x4000004F = Right, ...; verified with javap).
        /// SDL's rule: printable keys are their character (US layout), the rest scancode | 1 << 30.
        /// </summary>
        private static int SdlKeycode(int sc)
        {
            if (sc >= 4 && sc <= 29) return 'a' + (sc - 4);
            if (sc >= 30 && sc <= 38) return '1' + (sc - 30);
            switch (sc)
            {
                case 39: return '0';
                case 40: return 13;    // Return
                case 41: return 27;    // Escape
                case 42: return 8;     // Backspace
                case 43: return 9;     // Tab
                case 44: return ' ';
                case 45: return '-';
                case 46: return '=';
                case 47: return '[';
                case 48: return ']';
                case 49: return '\\';
                case 51: return ';';
                case 52: return '\'';
                case 53: return '`';
                case 54: return ',';
                case 55: return '.';
                case 56: return '/';
                case 76: return 127;   // Delete
                default: return sc | (1 << 30);
            }
        }

        private void PushEvent(Protocol.EventKind kind, int a, int b)
        {
            _bridge.TryPushEvent(true, new Protocol.RingUkEventsRecord { kind = (uint)kind, a = a, b = b });
        }

        private byte _hotbarSeq;
        private byte _hotbarSlot;
        private float _scrollTotal;

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

            // Mouse passthrough: while a Minecraft screen is open the mouse is Minecraft's
            // cursor, not the camera.
            bool screenOpen = HudView.McScreenOpen;
            float cursorX = 0f, cursorY = 0f;
            if (screenOpen)
            {
                dx = 0f;
                dy = 0f;
                Vector3 mp = UnityEngine.Input.mousePosition;              // pixels, origin bottom-left
                cursorX = mp.x / HudView.GuiScale;
                cursorY = (Screen.height - mp.y) / HudView.GuiScale;       // GUI y grows downward
            }
            ForwardKeyEvents(screenOpen);

            // Edge-triggered so a held key does not reopen the inventory every frame.
            // E now reaches Minecraft as a real key event (ForwardKeyEvents), so the one-sample
            // inventory flag is no longer sent.
            bool inventory = false;
            bool inventoryEdge = inventory && !_lastInventory;
            _lastInventory = inventory;

            // Hotbar keys 1-9 and the mouse wheel (HUD milestone). Free in ULTRAKILL now that
            // its guns are off. Sent as counters so a sample Minecraft misses loses nothing.
            for (int i = 0; i < 9; i++)
            {
                if (UnityEngine.Input.GetKeyDown(KeyCode.Alpha1 + i))
                {
                    _hotbarSeq++;
                    _hotbarSlot = (byte)i;
                }
            }
            _scrollTotal += UnityEngine.Input.mouseScrollDelta.y;   // +up, as Minecraft's onScroll

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
                hotbarSeq = _hotbarSeq,
                hotbarSlot = _hotbarSlot,
                scrollTotal = _scrollTotal,
                // Minecraft lays its GUI out for this screen (its GUI is replayed here).
                screenWidth = (ushort)Mathf.Clamp(Screen.width, 0, 65535),
                screenHeight = (ushort)Mathf.Clamp(Screen.height, 0, 65535),
                cursorX = cursorX,
                cursorY = cursorY,
                cursorActive = screenOpen ? (byte)1 : (byte)0,
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