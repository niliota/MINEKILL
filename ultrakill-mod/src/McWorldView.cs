using System;
using System.Collections.Generic;
using System.Linq;
using UnityEngine;
using UnityEngine.Rendering;
using UltraCraft.Bridge;

namespace UltraCraft
{
    /// <summary>
    /// The world layer, ULTRAKILL side: Minecraft's blocks drawn natively in ULTRAKILL's scene.
    ///
    /// Minecraft meshes each 16^3 section with its own block renderer and sends the triangles
    /// and its block atlas through the STREAM file (WorldMesher on the Java side). Here they
    /// become ordinary Unity meshes, so ULTRAKILL's camera draws them with correct depth against
    /// its own level: no images cross, as the contract requires.
    ///
    /// All sections hang under one root whose transform IS the Minecraft-to-ULTRAKILL map
    /// (<see cref="PuppetController.GetMcWorldTransform"/>). Section objects sit at their
    /// Minecraft block origin and vertices are section-relative, so a level load (new anchor)
    /// only moves the root.
    ///
    /// Materials: one per Minecraft section layer (solid, cutout, translucent), each a submesh.
    /// A shipped game only has the shaders it was built with, and none can be compiled here.
    /// The first playtest showed ULTRAKILL's level geometry uses 'ULTRAKILL/Master' with
    /// _VERTEXCOLORS_ON, so the preferred material for each layer is a CLONE of one of the
    /// current level's own Master materials of the matching blend style (it keeps that
    /// material's blend/cutout setup, which cannot be known offline), with vertex colours on,
    /// level-specific effects off, and the Minecraft atlas. Every distinct Master variant in the
    /// level is logged, and F9 / F10 / F11 cycle the solid / cutout / translucent material
    /// through the candidates live, so the right one can be found in a single session.
    /// </summary>
    internal sealed class McWorldView
    {
        private const double PollBudgetMs = 4.0;
        /// <summary>Unity layer 8, "Environment": rendered by ULTRAKILL's main camera. No colliders are added.</summary>
        private const int RenderLayer = 8;

        private const int Solid = 0, Cutout = 1, Translucent = 2;
        private static readonly string[] LayerNames = { "solid", "cutout", "translucent" };
        private static readonly KeyCode[] CycleKeys = { KeyCode.F9, KeyCode.F10, KeyCode.F11 };
        private static readonly string[][] GenericShaders =
        {
            new[] { "psx/vertexlit/vertexlit", "ProBuilder/Standard Vertex Color", "psx/unlit/unlit", "Unlit/Texture" },
            new[] { "psx/unlit/alphatest/nocull", "Sprites/Default", "Unlit/Texture" },
            new[] { "psx/vertexlit/transparent/zwrite", "psx/unlit/transparent/zwrite",
                    "Legacy Shaders/Particles/Alpha Blended", "Sprites/Default" },
        };
        private const string MasterShader = "ULTRAKILL/Master";

        /// <summary>
        /// Default material per layer, by name, as chosen by the user in the 2026-10-13 playtest
        /// (names, not positions: the candidate lists depend on what each level contains).
        /// Solid: the Master opaque clone (F9 option 1). Cutout: plain Unlit/Texture (F10 option 3).
        /// Translucent: the Master transparent clone (F11 option 1). Null = first candidate.
        /// F9/F10/F11 still cycle at runtime, for comparing in new levels.
        /// </summary>
        private static readonly string[] PreferredDefault = { null, "Unlit/Texture", null };

        private readonly PuppetController _puppet;
        private readonly BepInEx.Logging.ManualLogSource _log;
        private readonly StreamReader _stream;
        private GameObject _root;
        private Texture2D _atlas;
        private Material _fallback;
        private readonly List<Material>[] _candidates = { new List<Material>(), new List<Material>(), new List<Material>() };
        private readonly int[] _choice = new int[3];
        private readonly Material[] _mats = new Material[3];
        private bool _candidatesBuilt;
        private readonly List<MeshRenderer> _renderers = new List<MeshRenderer>();
        private readonly Dictionary<long, GameObject> _sections = new Dictionary<long, GameObject>();
        private int _sectionsReceived;

        public int Sections => _sections.Count;
        public string AtlasSize => _atlas != null ? $"{_atlas.width}x{_atlas.height}" : "none";
        public string ShaderName => string.Join(" / ", _mats.Select(m => m != null ? m.name : "none"));
        public StreamReader Stream => _stream;

        public McWorldView(string bridgePath, PuppetController puppet, BepInEx.Logging.ManualLogSource log)
        {
            _puppet = puppet;
            _log = log;
            _stream = new StreamReader(bridgePath);
            if (_stream.Open())
                _log.LogInfo($"world: stream open at {_stream.Path}, resync requested ({_stream.ResyncRequested})");
            else
                _log.LogError($"world: stream failed to open at {_stream.Path}: {_stream.LastError}");

            _root = new GameObject("UltraCraft.McWorld") { hideFlags = HideFlags.HideAndDontSave };
            _root.SetActive(false);
        }

        /// <summary>Every frame: read stream records, then place the root.</summary>
        public void Tick()
        {
            if (!_stream.Ok) return;
            _stream.Poll(PollBudgetMs, Handle);

            if (_puppet.MappingReady)
            {
                _puppet.GetMcWorldTransform(out Vector3 p, out Quaternion r, out Vector3 s);
                Transform t = _root.transform;
                t.position = p;
                t.rotation = r;
                t.localScale = s;
                if (!_root.activeSelf) _root.SetActive(true);
                if (!_candidatesBuilt && _atlas != null) BuildCandidates();
            }
            else if (_root.activeSelf)
            {
                _root.SetActive(false);   // mapping being rebuilt (level load): nothing to place it by
            }

            if (_candidatesBuilt)
                for (int layer = 0; layer < 3; layer++)
                    if (UnityEngine.Input.GetKeyDown(CycleKeys[layer]) && _candidates[layer].Count > 0)
                    {
                        _choice[layer] = (_choice[layer] + 1) % _candidates[layer].Count;
                        ApplyChoice(layer);
                        _log.LogInfo($"world: {CycleKeys[layer]} -> {LayerNames[layer]} material " +
                                     $"[{_choice[layer] + 1}/{_candidates[layer].Count}] '{_mats[layer].name}'");
                    }
        }

        private void Handle(int type, byte[] data, int len)
        {
            try
            {
                switch (type)
                {
                    case Protocol.STREAM_MSG_CLEAR_ALL: ClearAll(); break;
                    case Protocol.STREAM_MSG_ATLAS: LoadAtlas(data, len); break;
                    case Protocol.STREAM_MSG_SECTION: LoadSection(data, len); break;
                    default: _log.LogWarning($"world: unknown stream record type {type} ({len} bytes)"); break;
                }
            }
            catch (Exception e)
            {
                _log.LogError($"world: record type {type} failed: {e.GetType().Name}: {e.Message}");
            }
        }

        private void ClearAll()
        {
            foreach (var go in _sections.Values) DestroySection(go);
            _sections.Clear();
            _log.LogInfo("world: cleared all sections");
        }

        private void LoadAtlas(byte[] data, int len)
        {
            int w = BitConverter.ToInt32(data, 0), h = BitConverter.ToInt32(data, 4);
            int bytes = w * h * 4;
            if (w <= 0 || h <= 0 || len < 8 + bytes) { _log.LogError($"world: bad atlas header {w}x{h}, {len} bytes"); return; }

            // Row 0 of Minecraft's atlas is v = 0, and row 0 of a Unity texture is v = 0, so
            // Minecraft's UVs sample the same texel here without flipping.
            var tex = new Texture2D(w, h, TextureFormat.RGBA32, false)
            {
                filterMode = FilterMode.Point,
                wrapMode = TextureWrapMode.Clamp,
                hideFlags = HideFlags.HideAndDontSave,
                name = "UltraCraft.BlockAtlas",
            };
            var pixels = new byte[bytes];
            Buffer.BlockCopy(data, 8, pixels, 0, bytes);
            tex.LoadRawTextureData(pixels);
            tex.Apply(false, true);

            if (_atlas != null) UnityEngine.Object.Destroy(_atlas);
            _atlas = tex;
            EnsureFallback();
            _fallback.mainTexture = _atlas;
            foreach (var list in _candidates) foreach (var m in list) SetAtlas(m);
            _log.LogInfo($"world: block atlas {w}x{h} loaded");
        }

        private void LoadSection(byte[] data, int len)
        {
            int sx = BitConverter.ToInt32(data, 0), sy = BitConverter.ToInt32(data, 4), sz = BitConverter.ToInt32(data, 8);
            int n = BitConverter.ToInt32(data, 12);
            long key = Key(sx, sy, sz);
            if (_sections.TryGetValue(key, out GameObject old))
            {
                DestroySection(old);
                _sections.Remove(key);
            }
            if (n <= 0) return;   // section emptied
            if (len < Protocol.STREAM_SECTION_HEADER + n * Protocol.STREAM_VERTEX_SIZE || n % 3 != 0)
            {
                _log.LogError($"world: section {sx},{sy},{sz} claims {n} vertices in {len} bytes");
                return;
            }

            var verts = new Vector3[n];
            var uvs = new Vector2[n];
            var cols = new Color32[n];
            var byLayer = new List<int>[] { new List<int>(), new List<int>(), new List<int>() };
            int o = Protocol.STREAM_SECTION_HEADER;
            for (int i = 0; i < n; i++, o += Protocol.STREAM_VERTEX_SIZE)
            {
                verts[i] = new Vector3(BitConverter.ToSingle(data, o + Protocol.STREAM_VTX_OFF_POS),
                                       BitConverter.ToSingle(data, o + Protocol.STREAM_VTX_OFF_POS + 4),
                                       BitConverter.ToSingle(data, o + Protocol.STREAM_VTX_OFF_POS + 8));
                uvs[i] = new Vector2(BitConverter.ToSingle(data, o + Protocol.STREAM_VTX_OFF_UV),
                                     BitConverter.ToSingle(data, o + Protocol.STREAM_VTX_OFF_UV + 4));
                int c = o + Protocol.STREAM_VTX_OFF_COLOR;
                cols[i] = new Color32(data[c], data[c + 1], data[c + 2], data[c + 3]);
                if (i % 3 == 0)
                {
                    int flags = BitConverter.ToInt32(data, o + Protocol.STREAM_VTX_OFF_FLAGS);
                    int layer = (flags & (1 << 16)) != 0 ? Translucent : (flags & (1 << 17)) != 0 ? Cutout : Solid;
                    byLayer[layer].Add(i); byLayer[layer].Add(i + 1); byLayer[layer].Add(i + 2);
                }
            }

            var mesh = new Mesh { name = $"UltraCraft.Section {sx},{sy},{sz}", hideFlags = HideFlags.HideAndDontSave };
            if (n > 65535) mesh.indexFormat = IndexFormat.UInt32;
            mesh.vertices = verts;
            mesh.uv = uvs;
            mesh.colors32 = cols;
            mesh.subMeshCount = 3;
            for (int l = 0; l < 3; l++) mesh.SetTriangles(byLayer[l], l);
            // ULTRAKILL/Master lights by vertex normal. Triangles share no vertices, so these
            // come out as flat face normals, which is what a block face should have.
            mesh.RecalculateNormals();
            mesh.RecalculateBounds();

            EnsureFallback();
            var go = new GameObject($"UltraCraft.Section {sx},{sy},{sz}") { hideFlags = HideFlags.HideAndDontSave, layer = RenderLayer };
            go.transform.SetParent(_root.transform, false);
            go.transform.localPosition = new Vector3(sx * 16, sy * 16, sz * 16);
            go.AddComponent<MeshFilter>().sharedMesh = mesh;
            var mr = go.AddComponent<MeshRenderer>();
            mr.sharedMaterials = CurrentMaterials();
            _renderers.Add(mr);
            mr.shadowCastingMode = ShadowCastingMode.Off;
            mr.receiveShadows = false;
            _sections[key] = go;

            if (++_sectionsReceived <= 10 || _sectionsReceived % 200 == 0)
                _log.LogInfo($"world: section {sx},{sy},{sz}: {n} vertices ({_sections.Count} sections)");
        }

        private void DestroySection(GameObject go)
        {
            if (go == null) return;
            _renderers.Remove(go.GetComponent<MeshRenderer>());
            var mf = go.GetComponent<MeshFilter>();
            if (mf != null && mf.sharedMesh != null) UnityEngine.Object.Destroy(mf.sharedMesh);
            UnityEngine.Object.Destroy(go);
        }

        private void EnsureFallback()
        {
            if (_fallback != null) return;
            _fallback = new Material(Shader.Find("Unlit/Texture")) { hideFlags = HideFlags.HideAndDontSave, name = "fallback Unlit/Texture" };
            if (_atlas != null) _fallback.mainTexture = _atlas;
        }

        private Material[] CurrentMaterials()
        {
            var arr = new Material[3];
            for (int l = 0; l < 3; l++) arr[l] = _mats[l] != null ? _mats[l] : _fallback;
            return arr;
        }

        private void ApplyChoice(int layer)
        {
            _mats[layer] = _candidates[layer].Count > 0 ? _candidates[layer][_choice[layer]] : _fallback;
            Material[] arr = CurrentMaterials();
            foreach (var r in _renderers) if (r != null) r.sharedMaterials = arr;
        }

        private void SetAtlas(Material m)
        {
            if (m == null || _atlas == null) return;
            m.mainTexture = _atlas;
            m.mainTextureScale = Vector2.one;
            m.mainTextureOffset = Vector2.zero;
        }

        /// <summary>
        /// Once a level is loaded and the atlas is here: log what this build and level offer,
        /// then build the per-layer candidate lists (Master clones first, generic shaders after).
        /// </summary>
        private void BuildCandidates()
        {
            _candidatesBuilt = true;
            try
            {
                var names = Resources.FindObjectsOfTypeAll<Shader>().Select(x => x.name).Distinct().OrderBy(x => x).ToList();
                _log.LogInfo($"world: {names.Count} shaders loaded: {string.Join(" | ", names)}");

                // Every distinct ULTRAKILL/Master variant in memory, by blend setup and keywords.
                var masters = Resources.FindObjectsOfTypeAll<Material>()
                    .Where(m => m != null && m.shader != null && m.shader.name == MasterShader && m.mainTexture != _atlas)
                    .ToList();
                var groups = masters.GroupBy(Signature).OrderByDescending(g => g.Count()).ToList();
                _log.LogInfo($"world: {masters.Count} '{MasterShader}' materials in {groups.Count} variants:");
                foreach (var g in groups.Take(40))
                    _log.LogInfo($"world:   x{g.Count()} e.g. '{g.First().name}': {g.Key}");

                // Preferred template per layer, by Unity render queue / blend factors.
                Material Pick(Func<Material, bool> f) =>
                    groups.Where(g => f(g.First())).Select(g => g.First()).FirstOrDefault();
                Material opaque = Pick(m => m.renderQueue < 2450 && F(m, "_SrcBlend") == 1 && F(m, "_DstBlend") == 0);
                Material cutout = Pick(m => m.renderQueue >= 2450 && m.renderQueue < 3000
                                         || m.shaderKeywords.Any(k => k.Contains("CUTOUT") || k.Contains("ALPHATEST") || k.Contains("CLIP")));
                Material transparent = Pick(m => m.renderQueue >= 3000 || (F(m, "_SrcBlend") == 5 && F(m, "_DstBlend") == 10));

                AddMaster(Solid, opaque);
                AddMaster(Cutout, cutout);
                AddMaster(Translucent, transparent);
                for (int l = 0; l < 3; l++)
                {
                    foreach (string sn in GenericShaders[l])
                    {
                        Shader sh = Shader.Find(sn);
                        if (sh == null) continue;
                        var m = new Material(sh) { hideFlags = HideFlags.HideAndDontSave, name = sn };
                        if (m.HasProperty("_Cutoff")) m.SetFloat("_Cutoff", 0.5f);
                        SetAtlas(m);
                        _candidates[l].Add(m);
                    }
                    if (l == Cutout) AddVertexLitCutout(opaque, cutout);
                    int preferred = PreferredDefault[l] == null ? -1 : _candidates[l].FindIndex(m => m.name == PreferredDefault[l]);
                    _choice[l] = preferred >= 0 ? preferred : 0;
                    ApplyChoice(l);
                    _log.LogInfo($"world: {LayerNames[l]} candidates ({CycleKeys[l]} cycles): " +
                                 string.Join(", ", _candidates[l].Select((m, k) => $"[{k + 1}] {m.name}")));
                }
            }
            catch (Exception e)
            {
                _log.LogError($"world: building materials failed: {e.GetType().Name}: {e.Message}");
            }
        }

        /// <summary>A clone of one of ULTRAKILL's level materials, repurposed for Minecraft blocks.</summary>
        private void AddMaster(int layer, Material template)
        {
            if (template == null)
            {
                _log.LogInfo($"world: no '{MasterShader}' template for the {LayerNames[layer]} layer in this level");
                return;
            }
            var m = new Material(template) { hideFlags = HideFlags.HideAndDontSave, name = $"{MasterShader} clone of '{template.name}'" };
            // Keep blend/cutout/depth keywords and the look (fog, vertex lighting); drop the
            // level-specific effects (rain, caustics, emissive albedo, scrolling, ...).
            foreach (string k in m.shaderKeywords)
            {
                bool keep = k == "VERTEX_LIGHTING" || k == "_VERTEXLIGHTING_ON" || k == "_FOG_ON" || k == "_GLITCHMODE_NONE"
                         || k.Contains("BLEND") || k.Contains("ALPHA") || k.Contains("CUTOUT") || k.Contains("CLIP")
                         || k.Contains("TRANSP") || k.Contains("ZWRITE") || k.Contains("CULL") || k.Contains("SURFACE");
                if (!keep) m.DisableKeyword(k);
            }
            m.EnableKeyword("_VERTEXCOLORS_ON");
            if (m.HasProperty("_VertexColors")) m.SetFloat("_VertexColors", 1f);
            if (m.HasProperty("_UseAlbedoAsEmissive")) m.SetFloat("_UseAlbedoAsEmissive", 0f);
            if (m.HasProperty("_Color")) m.SetColor("_Color", Color.white);
            SetAtlas(m);
            _candidates[layer].Add(m);
            _log.LogInfo($"world: {LayerNames[layer]} template '{template.name}' ({Signature(template)}); " +
                         $"clone keywords [{string.Join(" ", m.shaderKeywords)}]");
        }

        /// <summary>
        /// The solid layer's vertex-lit level material, switched to alpha cutout with the cutout
        /// template's blend settings (measured: SisyHead3 = queue 2450, _BlendMode 1, ALPHA_TEST),
        /// so cutout blocks are lit and fogged exactly like solid ones but see-through where the
        /// texture is. Added last so the user's existing F10 positions do not move.
        /// </summary>
        private void AddVertexLitCutout(Material opaque, Material cutout)
        {
            if (opaque == null || cutout == null) return;
            int before = _candidates[Cutout].Count;
            AddMaster(Cutout, opaque);
            if (_candidates[Cutout].Count == before) return;
            Material m = _candidates[Cutout][_candidates[Cutout].Count - 1];
            m.name = $"{MasterShader} clone of '{opaque.name}' + alpha test";
            m.renderQueue = cutout.renderQueue;
            foreach (string prop in new[] { "_BlendMode", "_SrcBlend", "_DstBlend", "_ZWrite" })
                if (m.HasProperty(prop) && cutout.HasProperty(prop)) m.SetFloat(prop, cutout.GetFloat(prop));
            foreach (string k in cutout.shaderKeywords)
                if (k.Contains("ALPHA")) m.EnableKeyword(k);
            _log.LogInfo($"world: cutout candidate '{m.name}': queue {m.renderQueue}, keywords [{string.Join(" ", m.shaderKeywords)}]");
        }

        private static float F(Material m, string prop) => m.HasProperty(prop) ? m.GetFloat(prop) : float.NaN;

        private static string Signature(Material m) =>
            $"queue={m.renderQueue} blend={F(m, "_BlendMode")} src={F(m, "_SrcBlend")} dst={F(m, "_DstBlend")} " +
            $"zwrite={F(m, "_ZWrite")} cull={F(m, "_CullMode")} surface={F(m, "_SurfaceType")} " +
            $"keywords=[{string.Join(" ", m.shaderKeywords.OrderBy(k => k))}]";

        private static long Key(int x, int y, int z) =>
            ((long)(x & 0x1FFFFF) << 42) | ((long)(y & 0x1FFFFF) << 21) | (long)(z & 0x1FFFFF);
    }
}
