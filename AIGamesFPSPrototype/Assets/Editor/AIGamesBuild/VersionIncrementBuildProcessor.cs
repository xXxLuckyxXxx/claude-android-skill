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

            int next = PlayerSettings.Android.bundleVersionCode + 1;
            PlayerSettings.Android.bundleVersionCode = next;

            // Persist immediately so the bump survives even if the build aborts.
            AssetDatabase.SaveAssets();

            Debug.Log($"[AIGames] versionCode auto-incremented -> {next} " +
                      $"(versionName {PlayerSettings.bundleVersion}).");
        }
    }
}
