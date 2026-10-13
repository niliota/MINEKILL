using System;
using UnityEngine;
using UltraCraft.Bridge;

namespace UltraCraft
{
    /// <summary>
    /// Minecraft's HUD drawn natively in ULTRAKILL (HUD milestone). No image crosses: Minecraft
    /// sends values and item-icon descriptions (HUD table) and its GUI / item / block atlases
    /// (STREAM); this lays them out exactly where Minecraft's Gui does, at Minecraft's automatic
    /// GUI scale, and draws them with GL quads in OnGUI.
    ///
    /// Layout (Minecraft GUI pixels, origin top-left, w/h = screen / scale), from 26.3's Gui:
    ///   hotbar 182x22 at (w/2-91, h-22); selection at (w/2-92 + 20*slot, h-23);
    ///   item i at (w/2-88 + 20*i, h-19); offhand slot at (w/2-120, h-23), its item at (w/2-117, h-19);
    ///   XP bar 182x5 at (w/2-91, h-29), level text centred above it;
    ///   hearts from (w/2-91, h-39) rightwards, 8 px apart; armour the row above;
    ///   food from (w/2+91-9, h-39) leftwards; crosshair 15x15 centred.
    /// Creative and spectator show only the hotbar and crosshair, as in Minecraft.
    ///
    /// ULTRAKILL's own health/stamina panel (HudController.gunCanvas) and crosshair are hidden:
    /// health is Minecraft's now, and two crosshairs would disagree.
    /// </summary>
    internal sealed class HudView
    {
        private readonly BridgeTransport _bridge;
        private readonly McWorldView _world;
        private readonly byte[] _table = new byte[Protocol.TBL_HUD_SIZE];
        private bool _have;
        private Material _mat;
        private GUIStyle _text;
        private int _scanFrame = -1000;
        private HudController[] _huds = new HudController[0];
        private Crosshair[] _crosshairs = new Crosshair[0];

        public HudView(BridgeTransport bridge, McWorldView world)
        {
            _bridge = bridge;
            _world = world;
        }

        /// <summary>Every frame (Update): read the table, keep ULTRAKILL's own HUD hidden.</summary>
        public void Tick()
        {
            if (_bridge.ReadHudTable(_table)) _have = I(Protocol.TBL_HUD_OFF_COUNT) > 0;
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

        private int I(int off) => BitConverter.ToInt32(_table, off);
        private float F(int off) => BitConverter.ToSingle(_table, off);
        private int RI(int rec, int off) => BitConverter.ToInt32(_table, Protocol.TBL_HUD_OFF_RECORDS + rec * Protocol.TBL_HUD_RECORD_SIZE + off);
        private float RF(int rec, int off) => BitConverter.ToSingle(_table, Protocol.TBL_HUD_OFF_RECORDS + rec * Protocol.TBL_HUD_RECORD_SIZE + off);

        /// <summary>Minecraft's automatic GUI scale: the largest s with 320s x 240s fitting the screen.</summary>
        private static int AutoScale()
        {
            int s = 1;
            while ((s + 1) * 320 <= Screen.width && (s + 1) * 240 <= Screen.height) s++;
            return s;
        }

        public void OnGUI()
        {
            if (Event.current.type != EventType.Repaint || !_have || _world.GuiAtlas == null) return;
            if (_mat == null)
            {
                _mat = new Material(Shader.Find("Sprites/Default")) { hideFlags = HideFlags.HideAndDontSave, name = "UltraCraft.Hud" };
            }
            int s = AutoScale();
            float w = Screen.width / (float)s, h = Screen.height / (float)s;
            int mode = I(Protocol.TBL_HUD_OFF_GAMEMODE);
            if (mode == 3) return;                               // spectator: no HUD
            bool survival = mode == 0 || mode == 2;
            int selected = Mathf.Clamp(I(Protocol.TBL_HUD_OFF_SELECTED), 0, 8);

            GL.PushMatrix();
            GL.LoadPixelMatrix(0, Screen.width, Screen.height, 0);   // y down, like Minecraft's GUI

            // --- GUI atlas sprites
            Begin(_world.GuiAtlas);
            Sprite("hud/hotbar", w / 2 - 91, h - 22, s);
            Sprite("hud/hotbar_selection", w / 2 - 92 + 20 * selected, h - 23, s);
            bool offhand = RI(9, Protocol.TBL_HUD_REC_OFF_KIND) != 0;
            if (offhand) Sprite("hud/hotbar_offhand_left", w / 2 - 91 - 29, h - 23, s);
            if (survival)
            {
                Sprite("hud/experience_bar_background", w / 2 - 91, h - 29, s);
                float prog = Mathf.Clamp01(F(Protocol.TBL_HUD_OFF_XPPROGRESS));
                if (prog > 0f) SpritePart("hud/experience_bar_progress", w / 2 - 91, h - 29, (int)(prog * 183f), s);
                Hearts(w, h, s);
                Food(w, h, s);
                Armor(w, h, s);
            }
            Sprite("hud/crosshair", (w - 15) / 2f, (h - 15) / 2f, s);
            GL.End();

            // --- item icons, grouped by atlas
            for (int atlas = 0; atlas < 2; atlas++)
            {
                Texture2D tex = atlas == 0 ? _world.BlockAtlas : _world.ItemAtlas;
                if (tex == null) continue;
                Begin(tex);
                for (int i = 0; i < 10; i++)
                {
                    if (RI(i, Protocol.TBL_HUD_REC_OFF_KIND) == 0 || RI(i, Protocol.TBL_HUD_REC_OFF_ATLAS) != atlas) continue;
                    ItemPos(i, w, h, out float x, out float y);
                    Icon(i, x, y, s);
                }
                GL.End();
            }
            GL.PopMatrix();

            // --- durability bars and counts (GUI layer on top)
            for (int i = 0; i < 10; i++)
            {
                if (RI(i, Protocol.TBL_HUD_REC_OFF_KIND) == 0) continue;
                ItemPos(i, w, h, out float x, out float y);
                float dmg = RF(i, Protocol.TBL_HUD_REC_OFF_DAMAGE);
                if (dmg >= 0f)
                {
                    Rect(x + 2, y + 13, 13, 2, Color.black, s);
                    Rect(x + 2, y + 13, Mathf.Round(13 * dmg), 1, Color.HSVToRGB(dmg / 3f, 1f, 1f), s);
                }
                int count = RI(i, Protocol.TBL_HUD_REC_OFF_ITEMCOUNT);
                if (count > 1) Text(count.ToString(), x, y, 17, 17, TextAnchor.LowerRight, Color.white, s);
            }
            if (survival)
            {
                int level = I(Protocol.TBL_HUD_OFF_XPLEVEL);
                if (level > 0) Text(level.ToString(), w / 2 - 20, h - 40, 40, 10, TextAnchor.MiddleCenter, new Color(0.5f, 1f, 0.125f), s);
            }
        }

        private static void ItemPos(int i, float w, float h, out float x, out float y)
        {
            if (i < 9) { x = w / 2 - 88 + 20 * i; y = h - 19; }
            else { x = w / 2 - 117; y = h - 19; }
        }

        private void Hearts(float w, float h, int s)
        {
            float hp = F(Protocol.TBL_HUD_OFF_HEALTH), max = Mathf.Max(F(Protocol.TBL_HUD_OFF_MAXHEALTH), 1f);
            int hearts = Mathf.Min(10, Mathf.CeilToInt(max / 2f));
            for (int i = 0; i < hearts; i++)
            {
                float x = w / 2 - 91 + i * 8, y = h - 39;
                Sprite("hud/heart/container", x, y, s);
                if (hp >= 2 * i + 2) Sprite("hud/heart/full", x, y, s);
                else if (hp > 2 * i) Sprite("hud/heart/half", x, y, s);
            }
        }

        private void Food(float w, float h, int s)
        {
            int food = I(Protocol.TBL_HUD_OFF_FOOD);
            for (int i = 0; i < 10; i++)
            {
                float x = w / 2 + 91 - i * 8 - 9, y = h - 39;
                Sprite("hud/food_empty", x, y, s);
                if (food >= 2 * i + 2) Sprite("hud/food_full", x, y, s);
                else if (food > 2 * i) Sprite("hud/food_half", x, y, s);
            }
        }

        private void Armor(float w, float h, int s)
        {
            int armor = I(Protocol.TBL_HUD_OFF_ARMOR);
            if (armor <= 0) return;
            for (int i = 0; i < 10; i++)
            {
                float x = w / 2 - 91 + i * 8, y = h - 49;
                Sprite(armor >= 2 * i + 2 ? "hud/armor_full" : armor > 2 * i ? "hud/armor_half" : "hud/armor_empty", x, y, s);
            }
        }

        // ---- drawing helpers (GL quads, y down, coordinates in Minecraft GUI pixels x scale)

        private void Begin(Texture tex)
        {
            _mat.mainTexture = tex;
            _mat.SetPass(0);
            GL.Begin(GL.QUADS);
        }

        private static void Quad(float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, Color c)
        {
            // Minecraft v grows downward and the atlases are loaded unflipped, so screen-top = v0.
            GL.Color(c);
            GL.TexCoord2(u0, v0); GL.Vertex3(x0, y0, 0);
            GL.TexCoord2(u1, v0); GL.Vertex3(x1, y0, 0);
            GL.TexCoord2(u1, v1); GL.Vertex3(x1, y1, 0);
            GL.TexCoord2(u0, v1); GL.Vertex3(x0, y1, 0);
        }

        private void Sprite(string name, float x, float y, int s)
        {
            if (!_world.GuiSprites.TryGetValue(name, out var sp)) return;
            Quad(x * s, y * s, (x + sp.w) * s, (y + sp.h) * s, sp.uv.xMin, sp.uv.yMin, sp.uv.xMax, sp.uv.yMax, Color.white);
        }

        /// <summary>The left <paramref name="px"/> pixels of a sprite (the XP bar's progress).</summary>
        private void SpritePart(string name, float x, float y, int px, int s)
        {
            if (!_world.GuiSprites.TryGetValue(name, out var sp)) return;
            px = Mathf.Clamp(px, 0, sp.w);
            float u1 = sp.uv.xMin + (sp.uv.xMax - sp.uv.xMin) * px / sp.w;
            Quad(x * s, y * s, (x + px) * s, (y + sp.h) * s, sp.uv.xMin, sp.uv.yMin, u1, sp.uv.yMax, Color.white);
        }

        /// <summary>An item icon: flat sprite, or an isometric cube from its top and side textures.</summary>
        private void Icon(int i, float x, float y, int s)
        {
            float u0 = RF(i, Protocol.TBL_HUD_REC_OFF_U0), v0 = RF(i, Protocol.TBL_HUD_REC_OFF_V0);
            float u1 = RF(i, Protocol.TBL_HUD_REC_OFF_U1), v1 = RF(i, Protocol.TBL_HUD_REC_OFF_V1);
            Color top = Rgba(RI(i, Protocol.TBL_HUD_REC_OFF_TINTTOP));
            if (RI(i, Protocol.TBL_HUD_REC_OFF_KIND) == 1)
            {
                Quad(x * s, y * s, (x + 16) * s, (y + 16) * s, u0, v0, u1, v1, top);
                return;
            }
            float su0 = RF(i, Protocol.TBL_HUD_REC_OFF_SU0), sv0 = RF(i, Protocol.TBL_HUD_REC_OFF_SV0);
            float su1 = RF(i, Protocol.TBL_HUD_REC_OFF_SU1), sv1 = RF(i, Protocol.TBL_HUD_REC_OFF_SV1);
            Color side = Rgba(RI(i, Protocol.TBL_HUD_REC_OFF_TINTSIDE));
            // Isometric cube filling the 16x16 slot. Points (GUI px):
            //   T top, R right, C centre, L left, LB left-bottom, B bottom, RB right-bottom.
            float cx = x + 8;
            Vector2 T = new Vector2(cx, y + 0.5f), R = new Vector2(x + 15, y + 4.25f), C = new Vector2(cx, y + 8f),
                    L = new Vector2(x + 1, y + 4.25f), LB = new Vector2(x + 1, y + 11.75f), B = new Vector2(cx, y + 15.5f),
                    RB = new Vector2(x + 15, y + 11.75f);
            // Minecraft's GUI block lighting: top brightest, the two visible sides darker.
            Face(L, T, R, C, u0, v0, u1, v1, top, s);
            Face(L, C, B, LB, su0, sv0, su1, sv1, side * new Color(0.8f, 0.8f, 0.8f, 1f), s);
            Face(C, R, RB, B, su0, sv0, su1, sv1, side * new Color(0.6f, 0.6f, 0.6f, 1f), s);
        }

        private static void Face(Vector2 a, Vector2 b, Vector2 c, Vector2 d, float u0, float v0, float u1, float v1, Color col, int s)
        {
            GL.Color(col);
            GL.TexCoord2(u0, v0); GL.Vertex3(a.x * s, a.y * s, 0);
            GL.TexCoord2(u1, v0); GL.Vertex3(b.x * s, b.y * s, 0);
            GL.TexCoord2(u1, v1); GL.Vertex3(c.x * s, c.y * s, 0);
            GL.TexCoord2(u0, v1); GL.Vertex3(d.x * s, d.y * s, 0);
        }

        private static Color Rgba(int c) =>
            new Color32((byte)(c & 0xFF), (byte)((c >> 8) & 0xFF), (byte)((c >> 16) & 0xFF), (byte)((c >> 24) & 0xFF));

        private static void Rect(float x, float y, float rw, float rh, Color c, int s)
        {
            var old = GUI.color;
            GUI.color = c;
            GUI.DrawTexture(new UnityEngine.Rect(x * s, y * s, rw * s, rh * s), Texture2D.whiteTexture);
            GUI.color = old;
        }

        private void Text(string t, float x, float y, float rw, float rh, TextAnchor anchor, Color c, int s)
        {
            if (_text == null) _text = new GUIStyle(GUI.skin.label) { padding = new RectOffset(0, 0, 0, 0) };
            _text.fontSize = 8 * s;
            _text.alignment = anchor;
            var r = new UnityEngine.Rect(x * s, y * s, rw * s, rh * s);
            _text.normal.textColor = new Color(0.25f, 0.25f, 0.25f, 1f);    // Minecraft's text shadow
            GUI.Label(new UnityEngine.Rect(r.x + s, r.y + s, r.width, r.height), t, _text);
            _text.normal.textColor = c;
            GUI.Label(r, t, _text);
        }
    }
}
