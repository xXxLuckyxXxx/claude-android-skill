using UnityEditor;
using UnityEditor.Build;
using UnityEditor.Build.Reporting;
using UnityEngine;

namespace AIGames.EditorTools
{
    /// <summary>
    /// Auto-increments the Android versionCode before EVERY Android build. A
    /// strictly increasing versionCode is the third requirement (alongside a
    /// fixed applicationId and a stable signing key) for an APK to install as an
    /// update over the previous install instead of demanding an uninstall.
    /// </summary>
    public class VersionIncrementBuildProcessor : IPreprocessBuildWithReport
    {
        public int callbackOrder => 0;

        public void OnPreprocessBuild(BuildReport report)
        {
            if (report.summary.platform != BuildTarget.Android) return;

            // A shared, monotonic counter exported by the CI workflow
            // (AIGAMES_VERSION_CODE = github.run_number) is authoritative when
            // present. The native builder reads the SAME variable, so both
            // builders stay on ONE increasing sequence even though each fresh
            // checkout starts ProjectSettings back at 1. Without this, every CI
            // run (and any later cross-builder APK) would collide on the same
            // versionCode and Android would reject the "update" as a downgrade.
            string shared = System.Environment.GetEnvironmentVariable("AIGAMES_VERSION_CODE");
            if (int.TryParse(shared, out int code) && code > 0)
            {
                PlayerSettings.Android.bundleVersionCode = code;
                Debug.Log($"[AIGames] versionCode set from AIGAMES_VERSION_CODE = {code}.");
            }
            else
            {
                code = PlayerSettings.Android.bundleVersionCode + 1;   // local fallback
                PlayerSettings.Android.bundleVersionCode = code;
                Debug.Log($"[AIGames] versionCode auto-incremented -> {code} (local).");
            }

            // Persist immediately so the value survives even if the build aborts.
            AssetDatabase.SaveAssets();
        }
    }
}
