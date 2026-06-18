using System.IO;
using UnityEditor;
using UnityEngine;
using UnityEngine.Rendering;
using UnityEngine.Rendering.Universal;

namespace AIGames.EditorTools
{
    /// <summary>
    /// Builds and wires a high-fidelity URP setup entirely through the URP API
    /// (no hand-authored asset YAML, so no GUID/version fragility):
    ///   * UniversalRenderPipelineAsset  – HDR, MSAA x4, soft shadows
    ///   * UniversalRendererData         – + Screen Space Ambient Occlusion
    ///   * VolumeProfile                 – Bloom, ACES tonemapping, grade, vignette
    /// and assigns it as the active + default render pipeline.
    /// </summary>
    public static class GraphicsSetup
    {
        private const string SettingsDir  = "Assets/_Project/Settings";
        private const string RendererPath = SettingsDir + "/AIGames-Renderer.asset";
        private const string PipelinePath = SettingsDir + "/AIGames-URP-HighFidelity.asset";
        private const string VolumePath   = SettingsDir + "/AIGames-GlobalVolume.asset";

        [MenuItem("AI Games/Setup/High-End Graphics (URP)", priority = 1)]
        public static UniversalRenderPipelineAsset SetupHighEndGraphics()
        {
            Directory.CreateDirectory(SettingsDir);

            // ---- Renderer + Ambient Occlusion --------------------------------
            var renderer = AssetDatabase.LoadAssetAtPath<UniversalRendererData>(RendererPath);
            if (renderer == null)
            {
                renderer = ScriptableObject.CreateInstance<UniversalRendererData>();
                AssetDatabase.CreateAsset(renderer, RendererPath);
            }
            AddAmbientOcclusionIfMissing(renderer);

            // ---- Pipeline asset ----------------------------------------------
            var urp = AssetDatabase.LoadAssetAtPath<UniversalRenderPipelineAsset>(PipelinePath);
            if (urp == null)
            {
                urp = UniversalRenderPipelineAsset.Create(renderer);
                AssetDatabase.CreateAsset(urp, PipelinePath);
            }
            TunePipeline(urp);

            // ---- Post-processing volume profile ------------------------------
            CreateVolumeProfile();

            // ---- Make it the active + default pipeline -----------------------
            GraphicsSettings.defaultRenderPipeline = urp;
            QualitySettings.renderPipeline = urp;

            EditorUtility.SetDirty(renderer);
            EditorUtility.SetDirty(urp);
            AssetDatabase.SaveAssets();
            AssetDatabase.Refresh();

            Debug.Log("[AIGames] High-end URP ready: HDR + MSAA x4 + soft shadows + SSAO + Bloom.");
            return urp;
        }

        private static void AddAmbientOcclusionIfMissing(UniversalRendererData renderer)
        {
            foreach (ScriptableRendererFeature f in renderer.rendererFeatures)
                if (f is ScreenSpaceAmbientOcclusion) return;

            var ssao = ScriptableObject.CreateInstance<ScreenSpaceAmbientOcclusion>();
            ssao.name = "ScreenSpaceAmbientOcclusion";
            renderer.rendererFeatures.Add(ssao);
            AssetDatabase.AddObjectToAsset(ssao, renderer);

            // Let the renderer re-serialize its feature + map lists.
            var so = new SerializedObject(renderer);
            so.ApplyModifiedProperties();
            EditorUtility.SetDirty(renderer);
        }

        /// <summary>
        /// Many URP asset fields are read-only at the API level, so we set them
        /// via SerializedObject. Each set is null-guarded: if a field name differs
        /// in your URP version it is silently skipped and the URP default is kept.
        /// </summary>
        private static void TunePipeline(UniversalRenderPipelineAsset urp)
        {
            var so = new SerializedObject(urp);
            SetBool (so, "m_SupportsHDR", true);
            SetInt  (so, "m_MSAA", 4);
            SetFloat(so, "m_RenderScale", 1.0f);
            SetFloat(so, "m_ShadowDistance", 120f);
            SetInt  (so, "m_ShadowCascadeCount", 4);
            SetBool (so, "m_SoftShadowsSupported", true);
            SetBool (so, "m_MainLightShadowsSupported", true);
            SetInt  (so, "m_MainLightShadowmapResolution", 4096);
            SetBool (so, "m_AdditionalLightShadowsSupported", true);
            SetInt  (so, "m_AdditionalLightsRenderingMode", 1); // per-pixel
            so.ApplyModifiedProperties();
            EditorUtility.SetDirty(urp);
        }

        private static void CreateVolumeProfile()
        {
            var profile = AssetDatabase.LoadAssetAtPath<VolumeProfile>(VolumePath);
            if (profile == null)
            {
                profile = ScriptableObject.CreateInstance<VolumeProfile>();
                AssetDatabase.CreateAsset(profile, VolumePath);
            }

            if (!profile.Has<Bloom>())
            {
                Bloom bloom = profile.Add<Bloom>(true);
                bloom.active = true;
                bloom.threshold.Override(1.1f);
                bloom.intensity.Override(0.9f);
                bloom.scatter.Override(0.7f);
            }
            if (!profile.Has<Tonemapping>())
            {
                Tonemapping tm = profile.Add<Tonemapping>(true);
                tm.mode.Override(TonemappingMode.ACES);
            }
            if (!profile.Has<ColorAdjustments>())
            {
                ColorAdjustments ca = profile.Add<ColorAdjustments>(true);
                ca.postExposure.Override(0.15f);
                ca.contrast.Override(12f);
                ca.saturation.Override(8f);
            }
            if (!profile.Has<Vignette>())
            {
                Vignette vg = profile.Add<Vignette>(true);
                vg.intensity.Override(0.26f);
                vg.smoothness.Override(0.4f);
            }

            EditorUtility.SetDirty(profile);
        }

        public static VolumeProfile LoadVolumeProfile()
            => AssetDatabase.LoadAssetAtPath<VolumeProfile>(VolumePath);

        // --- SerializedObject helpers (null-guarded) ---------------------------
        private static void SetBool(SerializedObject so, string prop, bool v)
        { SerializedProperty p = so.FindProperty(prop); if (p != null) p.boolValue = v; }
        private static void SetInt(SerializedObject so, string prop, int v)
        { SerializedProperty p = so.FindProperty(prop); if (p != null) p.intValue = v; }
        private static void SetFloat(SerializedObject so, string prop, float v)
        { SerializedProperty p = so.FindProperty(prop); if (p != null) p.floatValue = v; }
    }
}
