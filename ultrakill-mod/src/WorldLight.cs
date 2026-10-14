using UnityEngine;
using UnityEngine.Rendering;

namespace Minekill
{
    /// <summary>
    /// How bright ULTRAKILL's lighting is at a point, as one colour: what a surface there would
    /// receive from the ambient light (light probes, or the level's ambient when it has none) plus
    /// every realtime light reaching it, with the falloff of Unity's per-vertex lights (the
    /// blocks' material is vertex-lit). Used to shade Minecraft's first-person hand, which is drawn
    /// over the frame rather than in the scene, so it would otherwise ignore the level's lights.
    /// </summary>
    internal static class WorldLight
    {
        private static Light[] _lights = new Light[0];
        private static float _nextScan;
        private static readonly Vector3[] Dirs = { Vector3.up, Vector3.down, Vector3.left, Vector3.right, Vector3.forward, Vector3.back };
        private static readonly Color[] Sh = new Color[6];

        /// <summary>The light reaching <paramref name="pos"/> for objects on <paramref name="layer"/>, each channel at most 1.</summary>
        public static Color At(Vector3 pos, int layer)
        {
            if (Time.unscaledTime >= _nextScan)
            {
                _nextScan = Time.unscaledTime + 1f;          // lights come and go with rooms and arenas
                _lights = Object.FindObjectsOfType<Light>();
            }
            // Ambient: the probe field interpolated at the point (falls back to the level's
            // ambient probe), averaged over all directions.
            LightProbes.GetInterpolatedProbe(pos, null, out SphericalHarmonicsL2 sh);
            sh.Evaluate(Dirs, Sh);
            Color c = Color.black;
            for (int i = 0; i < Sh.Length; i++) c += Sh[i];
            c /= Sh.Length;
            int bit = 1 << layer;
            foreach (Light l in _lights)
            {
                if (l == null || !l.isActiveAndEnabled || l.intensity <= 0f || (l.cullingMask & bit) == 0) continue;
                if (l.bakingOutput.isBaked && l.bakingOutput.lightmapBakeType == LightmapBakeType.Baked) continue;   // already in the probes
                Color lc = l.color * l.intensity;
                if (l.type == LightType.Directional)
                {
                    c += lc * 0.5f;                          // an average facing, not a surface normal
                    continue;
                }
                if (l.type != LightType.Point && l.type != LightType.Spot) continue;
                Vector3 d = pos - l.transform.position;
                float r = Mathf.Max(0.01f, l.range), d2 = d.sqrMagnitude;
                if (d2 >= r * r) continue;
                // Unity's vertex lights: 1 / (1 + 25 d^2 / range^2), cut at the range.
                float atten = 1f / (1f + 25f * d2 / (r * r));
                if (l.type == LightType.Spot)
                {
                    float cos = Vector3.Dot(l.transform.forward, d.normalized);
                    float outer = Mathf.Cos(l.spotAngle * 0.5f * Mathf.Deg2Rad);
                    if (cos <= outer) continue;
                    atten *= Mathf.Clamp01((cos - outer) / Mathf.Max(0.001f, 1f - outer) * 4f);
                }
                c += lc * atten;
            }
            return new Color(Mathf.Clamp01(c.r), Mathf.Clamp01(c.g), Mathf.Clamp01(c.b), 1f);
        }
    }
}
