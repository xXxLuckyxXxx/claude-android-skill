using System;
using System.IO;
using System.Linq;
using UnityEditor;
using UnityEditor.Build.Reporting;
using UnityEngine;
using UnityEngine.Rendering;

namespace AIGames.EditorTools
{
    /// <summary>
    /// Central Android build configuration ("config as code"). Sets the fixed
    /// applicationId, high-end rendering targets (Vulkan, Linear, ASTC), the
    /// 64-bit IL2CPP toolchain, landscape orientation, and wires the signing
    /// keystore so debug AND release builds are signed with the same key.
    ///
    /// CI entry point:
    ///   Unity -batchmode -quit -projectPath . -buildTarget Android \
    ///         -executeMethod AIGames.EditorTools.BuildScript.PerformAndroidBuild
    /// </summary>
    public static class BuildScript
    {
        public const string ApplicationId = "com.aigames.fpsprototype";
        private const string ProductName = "AIGames FPS Prototype";
        private const string CompanyName = "AI Games";

        private static readonly string[] Scenes =
        {
            "Assets/_Project/Scenes/TestScene.unity"
        };

        private static string ProjectRoot =>
            Directory.GetParent(Application.dataPath).FullName;

        [MenuItem("AI Games/Build/1. Configure Player Settings", priority = 20)]
        public static void ConfigurePlayerSettings()
        {
            // --- Identity: a FIXED applicationId is one pillar of seamless updates.
            PlayerSettings.SetApplicationIdentifier(BuildTargetGroup.Android, ApplicationId);
            PlayerSettings.productName = ProductName;
            PlayerSettings.companyName = CompanyName;

            // --- Rendering: Linear color space is required for correct URP/HDR PBR.
            PlayerSettings.colorSpace = ColorSpace.Linear;

            // --- Vulkan only (Snapdragon 8 Elite 2 / Adreno class, 165 Hz panel).
            PlayerSettings.SetUseDefaultGraphicsAPIs(BuildTarget.Android, false);
            PlayerSettings.SetGraphicsAPIs(BuildTarget.Android,
                new[] { GraphicsDeviceType.Vulkan });

            // --- Modern 64-bit toolchain.
            PlayerSettings.SetScriptingBackend(BuildTargetGroup.Android, ScriptingImplementation.IL2CPP);
            PlayerSettings.Android.targetArchitectures = AndroidArchitecture.ARM64;
            PlayerSettings.Android.minSdkVersion = AndroidSdkVersions.AndroidApiLevel29;
            PlayerSettings.Android.targetSdkVersion = AndroidSdkVersions.AndroidApiLevelAuto;

            // --- Best mobile texture compression for modern GPUs.
            //     (Unity 6 alternative API: PlayerSettings.Android.textureCompressionFormats.)
            EditorUserBuildSettings.androidBuildSubtarget = MobileTextureSubtarget.ASTC;

            // --- Landscape FPS.
            PlayerSettings.defaultInterfaceOrientation = UIOrientation.LandscapeLeft;

            // --- Signing (the same key for every build => seamless updates).
            ApplySigning();

            AssetDatabase.SaveAssets();
            Debug.Log($"[AIGames] Player settings configured: {ApplicationId} " +
                      "(Vulkan, IL2CPP/ARM64, ASTC, Linear, Landscape).");
        }

        private static void ApplySigning()
        {
            KeystoreConfig.Signing s = KeystoreConfig.Resolve(ProjectRoot);
            if (!s.IsComplete)
            {
                PlayerSettings.Android.useCustomKeystore = false;
                Debug.LogWarning(
                    "[AIGames] No complete keystore found (AIGAMES_* env vars or " +
                    "keystore/keystore.properties). Run scripts/generate-keystore.sh. " +
                    "Until then builds use Unity's throwaway debug key, which BREAKS " +
                    "seamless updates.");
                return;
            }

            PlayerSettings.Android.useCustomKeystore = true;
            PlayerSettings.Android.keystoreName = s.StoreFile;
            PlayerSettings.Android.keystorePass = s.StorePassword;
            PlayerSettings.Android.keyaliasName = s.KeyAlias;
            PlayerSettings.Android.keyaliasPass = s.KeyPassword;

            Debug.Log($"[AIGames] Signing wired: {s.StoreFile} (alias '{s.KeyAlias}'). " +
                      "Every build is signed with this key.");
        }

        [MenuItem("AI Games/Build/2. Build Android APK", priority = 21)]
        public static void BuildAndroidMenu() => PerformAndroidBuild();

        public static void PerformAndroidBuild()
        {
            EditorUserBuildSettings.SwitchActiveBuildTarget(
                BuildTargetGroup.Android, BuildTarget.Android);

            ConfigurePlayerSettings();
            EnsureSceneRegistered();

            string outDir = Path.Combine(ProjectRoot, "Builds", "Android");
            Directory.CreateDirectory(outDir);

            // versionCode is incremented by VersionIncrementBuildProcessor in the
            // preprocess step; reflect the post-increment value in the file name.
            int code = PlayerSettings.Android.bundleVersionCode + 1;
            string outFile = Path.Combine(outDir,
                $"AIGamesFPS_v{PlayerSettings.bundleVersion}_code{code}.apk");

            var options = new BuildPlayerOptions
            {
                scenes = Scenes,
                locationPathName = outFile,
                target = BuildTarget.Android,
                targetGroup = BuildTargetGroup.Android,
                options = BuildOptions.None,
            };

            BuildReport report = BuildPipeline.BuildPlayer(options);
            BuildSummary summary = report.summary;

            if (summary.result == BuildResult.Succeeded)
                Debug.Log($"[AIGames] BUILD OK -> {outFile} " +
                          $"({summary.totalSize} bytes, versionCode {PlayerSettings.Android.bundleVersionCode}).");
            else
                throw new Exception($"[AIGames] BUILD FAILED: {summary.result} " +
                                    $"({summary.totalErrors} errors).");
        }

        private static void EnsureSceneRegistered()
        {
            if (!File.Exists(Scenes[0]))
            {
                Debug.LogWarning($"[AIGames] Scene '{Scenes[0]}' not found — generating it.");
                TestSceneBuilder.BuildAndSave();
            }

            EditorBuildSettings.scenes = Scenes
                .Select(s => new EditorBuildSettingsScene(s, true))
                .ToArray();
        }
    }
}
