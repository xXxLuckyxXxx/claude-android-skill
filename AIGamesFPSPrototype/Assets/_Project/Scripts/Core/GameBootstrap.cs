using UnityEngine;

namespace AIGames.Gameplay
{
    /// <summary>
    /// Runtime startup tweaks for the target device (OnePlus 15 Pro class):
    /// unlock the high-refresh display and keep the screen awake during play.
    /// </summary>
    public class GameBootstrap : MonoBehaviour
    {
        [SerializeField] private int targetFrameRate = 165;   // 165 Hz panel

        private void Awake()
        {
            QualitySettings.vSyncCount = 0;                    // let targetFrameRate drive
            Application.targetFrameRate = targetFrameRate;
            Screen.sleepTimeout = SleepTimeout.NeverSleep;
        }
    }
}
