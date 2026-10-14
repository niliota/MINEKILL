using System;
using UnityEngine;
using Minekill.Bridge;

namespace Minekill
{
    /// <summary>
    /// Minecraft's GUI, replayed natively in ULTRAKILL: HUD, text, items, chat, titles, boss bars,
    /// effects, open screens — whatever Minecraft draws, with every animation.
    ///
    /// Minecraft captures its own GUI geometry each frame (GuiCapture on the Java side: every
    /// GUI element's quads in final draw order, with item models projected in place of the GPU
    /// item atlas) into the GUI table, laid out for THIS screen (it is told our size and computes
    /// its GUI scale for it). This draws those quads in order with GL in OnGUI, switching texture
    /// when the quad's texture key changes. No image crosses: the textures are Minecraft's GUI,
    /// block and item atlases and its font sheets, mirrored glyph by glyph.
    ///
    /// ULTRAKILL's own health/stamina panel (HudController.gunCanvas) and crosshair are hidden:
    /// health is Minecraft's, and two crosshairs would disagree.
    /// </summary>
    internal sealed class HudView
    {
        private readonly BridgeTransport _bridge;
        private readonly McWorldView _world;
        private readonly byte[] _table = new byte[Protocol.TBL_GUI_OFF_RECORDS + Protocol.TBL_GUI_CAPACITY * Protocol.TBL_GUI_RECORD_SIZE];
        private int _count;
        private Material _mat;
        private readonly byte[] _hand = new byte[Protocol.TBL_HAND_OFF_RECORDS + Protocol.TBL_HAND_CAPACITY * Protocol.TBL_HAND_RECORD_SIZE];
        private int _handCount;
        private Material _handMat;
        /// <summary>ULTRAKILL's light at the camera, multiplied into the hand (smoothed).</summary>
        private Color _handLight = Color.white;
        private const int WorldLayer = 8;   // the layer Minecraft's world is drawn on (McWorldView)
        /// <summary>The hand's share of the world light (owner, 2026-10-15: "a bit darker").</summary>
        private const float HandLightScale = 0.75f;
        private int _scanFrame = -1000;
        private HudController[] _huds = new HudController[0];
        private Crosshair[] _crosshairs = new Crosshair[0];

        public int LastVertexCount => _count;

        /// <summary>A Minecraft screen is open (inventory, chat, pause): mouse and keys go to Minecraft.</summary>
        public static bool McScreenOpen { get; private set; }
        /// <summary>That screen pauses Minecraft's game.</summary>
        public static bool McPauseScreen { get; private set; }
        /// <summary>Minecraft's GUI scale for this screen (GUI pixels to screen pixels).</summary>
        public static float GuiScale { get; private set; } = 1f;

        private bool _wasOpen, _setFrozen, _setTimeScale;
        private float _savedTimeScale = 1f;

        public HudView(BridgeTransport bridge, McWorldView world)
        {
            _bridge = bridge;
            _world = world;
        }

        /// <summary>Every frame (Update): read the frame's GUI, keep ULTRAKILL's own HUD hidden.</summary>
        public void Tick()
        {
            int n = _bridge.ReadGuiTable(_table);
            // A table Minecraft stopped writing (closed, crashed, a previous session's bridge file)
            // is not shown: it would freeze its last frame on screen.
            if (n >= 0 && DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - BitConverter.ToInt64(_table, Protocol.TBL_GUI_OFF_TICKTIMESTAMP) > 2000)
                n = 0;
            if (n >= 0)
            {
                _count = n;
                McScreenOpen = BitConverter.ToUInt32(_table, Protocol.TBL_GUI_OFF_SCREENOPEN) != 0;
                McPauseScreen = BitConverter.ToUInt32(_table, Protocol.TBL_GUI_OFF_PAUSESCREEN) != 0;
                uint sc = BitConverter.ToUInt32(_table, Protocol.TBL_GUI_OFF_GUISCALE);
                if (sc > 0) GuiScale = sc;
            }
            int h = _bridge.ReadHandTable(_hand);
            if (h >= 0 && DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - BitConverter.ToInt64(_hand, Protocol.TBL_HAND_OFF_TICKTIMESTAMP) > 2000)
                h = 0;
            if (h >= 0) _handCount = h;
            if (Time.frameCount - _scanFrame > 120)
            {
                _scanFrame = Time.frameCount;
                _huds = UnityEngine.Object.FindObjectsOfType<HudController>();
                _crosshairs = UnityEngine.Object.FindObjectsOfType<Crosshair>();
            }
            foreach (var hud in _huds)
            {
                if (hud == null || hud.gunCanvas == null) continue;
                var canvas = hud.gunCanvas.GetComponent<Canvas>();
                if (canvas != null && canvas.enabled) canvas.enabled = false;
            }
            foreach (var ch in _crosshairs)
                if (ch != null && ch.gameObject.activeSelf) ch.gameObject.SetActive(false);
        }

        /// <summary>
        /// LateUpdate, after ULTRAKILL's own code: while a Minecraft screen is open, free and show
        /// the cursor, hold off ULTRAKILL's pause key (its own OptionsManager.frozen switch, so Esc
        /// reaches only Minecraft), switch the fists off so clicks in an inventory do not punch, and
        /// while Minecraft's screen PAUSES its game, freeze ULTRAKILL's time too: otherwise enemies
        /// keep attacking a player who cannot move. Each change is undone when the screen closes,
        /// and only if this class made it.
        /// </summary>
        public void LateTick()
        {
            if (_handCount > 0 && Camera.main != null)
            {
                Color target = WorldLight.At(Camera.main.transform.position, WorldLayer) * HandLightScale;
                target.a = 1f;
                _handLight = Color.Lerp(_handLight, target, 1f - Mathf.Exp(-12f * Time.unscaledDeltaTime));
            }
            bool open = McScreenOpen && _count >= 0;
            if (open)
            {
                Cursor.lockState = CursorLockMode.None;
                Cursor.visible = true;
                var om = MonoSingleton<OptionsManager>.Instance;
                if (om != null && !om.frozen) { om.frozen = true; _setFrozen = true; }
                if (!_wasOpen) { var fc = MonoSingleton<FistControl>.Instance; if (fc != null) fc.NoFist(); }
                if (McPauseScreen && !_setTimeScale) { _savedTimeScale = Time.timeScale; Time.timeScale = 0f; _setTimeScale = true; }
                if (!McPauseScreen && _setTimeScale) { Time.timeScale = _savedTimeScale; _setTimeScale = false; }
            }
            else if (_wasOpen)
            {
                Cursor.lockState = CursorLockMode.Locked;
                Cursor.visible = false;
                var om = MonoSingleton<OptionsManager>.Instance;
                if (om != null && _setFrozen) om.frozen = false;
                _setFrozen = false;
                var fc = MonoSingleton<FistControl>.Instance; if (fc != null) fc.YesFist();
                if (_setTimeScale) { Time.timeScale = _savedTimeScale; _setTimeScale = false; }
            }
            _wasOpen = open;
        }

        public void OnGUI()
        {
            if (Event.current.type != EventType.Repaint) return;
            // The glint's additive shader depth-tests (it has no GUI ZTest switch like UI/Default):
            // clear the depth the frame left, so the world cannot hide the glint on the hand and
            // GUI items. Nothing after the GUI pass reads it.
            if (_handCount > 0 || _count > 0) GL.Clear(true, false, Color.clear);
            DrawHand();
            if (_count <= 0) return;
            if (_mat == null)
                _mat = new Material(Shader.Find("Sprites/Default")) { hideFlags = HideFlags.HideAndDontSave, name = "MINEKILL.Gui" };

            float scale = BitConverter.ToUInt32(_table, Protocol.TBL_GUI_OFF_GUISCALE);
            if (scale <= 0) return;
            int r0 = Protocol.TBL_GUI_OFF_RECORDS, rs = Protocol.TBL_GUI_RECORD_SIZE;

            GL.PushMatrix();
            GL.LoadPixelMatrix(0, Screen.width, Screen.height, 0);   // y down, like Minecraft's GUI
            int curKey = int.MinValue;
            bool drawing = false, skip = false;
            for (int q = 0; q + 3 < _count; q += 4)
            {
                int key = BitConverter.ToInt32(_table, r0 + q * rs + Protocol.TBL_GUI_REC_OFF_TEX);
                if (key != curKey)
                {
                    if (drawing) { GL.End(); drawing = false; }
                    curKey = key;
                    skip = !SetPass(key, _mat);
                    if (!skip)
                    {
                        GL.Begin(GL.QUADS);
                        drawing = true;
                    }
                }
                if (skip) continue;
                for (int k = 0; k < 4; k++)
                {
                    int o = r0 + (q + k) * rs;
                    int c = BitConverter.ToInt32(_table, o + Protocol.TBL_GUI_REC_OFF_RGBA);
                    GL.Color(new Color32((byte)c, (byte)(c >> 8), (byte)(c >> 16), (byte)(c >> 24)));
                    // Minecraft's v grows downward and textures are loaded unflipped: same texel.
                    GL.TexCoord2(BitConverter.ToSingle(_table, o + Protocol.TBL_GUI_REC_OFF_U),
                                 BitConverter.ToSingle(_table, o + Protocol.TBL_GUI_REC_OFF_V));
                    GL.Vertex3(BitConverter.ToSingle(_table, o + Protocol.TBL_GUI_REC_OFF_X) * scale,
                               BitConverter.ToSingle(_table, o + Protocol.TBL_GUI_REC_OFF_Y) * scale, 0);
                }
            }
            if (drawing) GL.End();
            GL.PopMatrix();
        }

        /// <summary>
        /// Bind the material for texture key <paramref name="key"/>: <paramref name="normal"/> with
        /// that texture, or the additive glint material for enchantment-glint quads. False when the
        /// texture (or the glint shader) is not available; those quads are skipped.
        /// </summary>
        private bool SetPass(int key, Material normal)
        {
            if ((key & Protocol.MODEL_TEX_GLINT) != 0)
            {
                Material g = _world.GlintMaterial(key);
                if (g == null) return false;
                g.SetPass(0);
                return true;
            }
            Texture tex = _world.GuiTexture(key);
            if (tex == null) return false;
            normal.mainTexture = tex;
            normal.SetPass(0);
            return true;
        }

        /// <summary>
        /// Minecraft's first-person hands and held item, under the GUI: view-space quads (already
        /// posed, shaded by Minecraft's two directional lights and sorted far first) drawn with
        /// Minecraft's own hand projection and tinted by ULTRAKILL's light at the camera (WorldLight;
        /// 2026-10-15: "make the player hand be shaded according to the world"),
        /// as Minecraft draws them with their own FOV over the world. UI/Default with its GUI depth
        /// test set to Always, so whatever depth the frame left behind does not cut into them.
        /// </summary>
        private void DrawHand()
        {
            if (_handCount <= 0) return;
            if (_handMat == null)
            {
                _handMat = new Material(Shader.Find("UI/Default")) { hideFlags = HideFlags.HideAndDontSave, name = "MINEKILL.Hand" };
                _handMat.SetInt("unity_GUIZTestMode", (int)UnityEngine.Rendering.CompareFunction.Always);
            }
            float fov = BitConverter.ToSingle(_hand, Protocol.TBL_HAND_OFF_FOV);
            if (fov <= 1f || fov >= 179f) fov = 70f;
            int r0 = Protocol.TBL_HAND_OFF_RECORDS, rs = Protocol.TBL_HAND_RECORD_SIZE;

            GL.PushMatrix();
            GL.LoadProjectionMatrix(Matrix4x4.Perspective(fov, Screen.width / (float)Mathf.Max(1, Screen.height), 0.05f, 100f));
            GL.modelview = Matrix4x4.identity;   // Minecraft view space: camera looks down -z, y up, as Unity's
            int curKey = int.MinValue;
            bool drawing = false, skip = false;
            for (int q = 0; q + 3 < _handCount; q += 4)
            {
                int key = BitConverter.ToInt32(_hand, r0 + q * rs + Protocol.TBL_HAND_REC_OFF_TEX);
                if (key != curKey)
                {
                    if (drawing) { GL.End(); drawing = false; }
                    curKey = key;
                    skip = !SetPass(key, _handMat);
                    if (!skip)
                    {
                        GL.Begin(GL.QUADS);
                        drawing = true;
                    }
                }
                if (skip) continue;
                for (int k = 0; k < 4; k++)
                {
                    int o = r0 + (q + k) * rs;
                    int c = BitConverter.ToInt32(_hand, o + Protocol.TBL_HAND_REC_OFF_RGBA);
                    GL.Color((Color)new Color32((byte)c, (byte)(c >> 8), (byte)(c >> 16), (byte)(c >> 24)) * _handLight);
                    GL.TexCoord2(BitConverter.ToSingle(_hand, o + Protocol.TBL_HAND_REC_OFF_U),
                                 BitConverter.ToSingle(_hand, o + Protocol.TBL_HAND_REC_OFF_V));
                    GL.Vertex3(BitConverter.ToSingle(_hand, o + Protocol.TBL_HAND_REC_OFF_X),
                               BitConverter.ToSingle(_hand, o + Protocol.TBL_HAND_REC_OFF_Y),
                               BitConverter.ToSingle(_hand, o + Protocol.TBL_HAND_REC_OFF_Z));
                }
            }
            if (drawing) GL.End();
            GL.PopMatrix();
        }
    }
}
