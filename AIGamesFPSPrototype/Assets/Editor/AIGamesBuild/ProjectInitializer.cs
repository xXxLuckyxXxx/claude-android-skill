using UnityEditor;
using UnityEngine;

namespace AIGames.EditorTools
{
    /// <summary>
    /// One-click bootstrap that runs every setup step in order. Use this right
    /// after opening the project for the first time.
    /// </summary>
    public static class ProjectInitializer
    {
        [MenuItem("AI Games/Initialize Project (All Steps)", priority = 0)]
        public static void InitializeAll()
        {
            BuildScript.ConfigurePlayerSettings();
            GraphicsSetup.SetupHighEndGraphics();
            TestSceneBuilder.BuildAndSave();

            Debug.Log("[AIGames] Project initialized — open " +
                      "Assets/_Project/Scenes/TestScene.unity and press Play. " +
                      "Then 'AI Games/Build/2. Build Android APK' to produce a signed APK.");
        }
    }
}
