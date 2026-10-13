using System;
using UnityEngine;
using UltraCraft.Bridge;

namespace UltraCraft
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
        private int _scanFrame = -1000;
        private HudController[] _huds = new HudController[0];
        private Crosshair[] _crosshairs = new Crosshair[0];

        public int LastVertexCount => _count;

        public HudView(BridgeTransport bridge, McWorldView world)
        {
            _bridge = bridge;
            _world = world;
        }

        /// <summary>Every frame (Update): read the frame's GUI, keep ULTRAKILL's own HUD hidden.</summary>
        public void Tick()
        {
            int n = _bridge.ReadGuiTable(_table);
            if (n >= 0) _count = n;
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

        public void OnGUI()
        {
            if (Event.current.type != EventType.Repaint || _count <= 0) return;
            if (_mat == null)
                _mat = new Material(Shader.Find("Sprites/Default")) { hideFlags = HideFlags.HideAndDontSave, name = "UltraCraft.Gui" };

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
                    Texture tex = _world.GuiTexture(key);
                    skip = tex == null;
                    if (!skip)
                    {
                        _mat.mainTexture = tex;
                        _mat.SetPass(0);
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
    }
}
