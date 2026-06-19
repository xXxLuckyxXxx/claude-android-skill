using System.IO;
using UnityEditor;
using UnityEditor.Events;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.Rendering;
using UnityEngine.Rendering.Universal;
using UnityEngine.SceneManagement;
using UnityEngine.UI;
using UnityEngine.EventSystems;
using AIGames.Controls;
using AIGames.Gameplay;

namespace AIGames.EditorTools
{
    /// <summary>
    /// Procedurally builds the playable test scene: lit arena with PBR placeholder
    /// materials, a global post-processing volume, an FPS player rig, and the
    /// touch HUD (move stick + look area + fire button). Generating it from code
    /// keeps the scene reproducible and free of fragile hand-authored YAML.
    /// </summary>
    public static class TestSceneBuilder
    {
        private const string SceneDir  = "Assets/_Project/Scenes";
        private const string ScenePath = SceneDir + "/TestScene.unity";
        private const string MatDir    = "Assets/_Project/Art/Materials";

        [MenuItem("AI Games/Setup/Build Test Scene", priority = 2)]
        public static void BuildAndSave()
        {
            // Ensure the URP pipeline + Bloom/SSAO volume profile exist first.
            GraphicsSetup.SetupHighEndGraphics();

            Scene scene = EditorSceneManager.NewScene(NewSceneSetup.EmptyScene, NewSceneMode.Single);

            BuildBootstrap();
            BuildLighting();
            BuildEnvironment();
            GameObject player = BuildPlayerRig(out Camera cam, out FPSController controller, out PlayerShoot shoot);
            BuildPostProcessing();
            BuildHud(controller, shoot, player);

            Directory.CreateDirectory(SceneDir);
            EditorSceneManager.MarkSceneDirty(scene);
            EditorSceneManager.SaveScene(scene, ScenePath);

            EditorBuildSettings.scenes = new[] { new EditorBuildSettingsScene(ScenePath, true) };
            Debug.Log("[AIGames] Test scene built -> " + ScenePath);
        }

        // --------------------------------------------------------------------- //

        private static void BuildBootstrap()
        {
            var go = new GameObject("GameBootstrap");
            go.AddComponent<GameBootstrap>();
        }

        private static void BuildLighting()
        {
            var sunGo = new GameObject("Directional Light (Sun)");
            sunGo.transform.rotation = Quaternion.Euler(50f, -30f, 0f);
            Light sun = sunGo.AddComponent<Light>();
            sun.type = LightType.Directional;
            sun.intensity = 1.3f;
            sun.color = new Color(1.0f, 0.96f, 0.88f);
            sun.shadows = LightShadows.Soft;

            // A couple of dynamic point lights for specular interest.
            CreatePointLight("Accent Light A", new Vector3(-5f, 3.5f, 2f), new Color(0.4f, 0.6f, 1f), 12f, 30f);
            CreatePointLight("Accent Light B", new Vector3(6f, 3f, -3f), new Color(1f, 0.5f, 0.3f), 12f, 25f);

            RenderSettings.ambientMode = AmbientMode.Trilight;
            RenderSettings.ambientSkyColor = new Color(0.45f, 0.5f, 0.6f);
            RenderSettings.ambientEquatorColor = new Color(0.3f, 0.3f, 0.34f);
            RenderSettings.ambientGroundColor = new Color(0.1f, 0.1f, 0.12f);
        }

        private static void CreatePointLight(string name, Vector3 pos, Color color, float intensity, float range)
        {
            var go = new GameObject(name) { transform = { position = pos } };
            Light l = go.AddComponent<Light>();
            l.type = LightType.Point;
            l.color = color;
            l.intensity = intensity;
            l.range = range;
            l.shadows = LightShadows.Soft;
        }

        private static void BuildEnvironment()
        {
            Material floor = CreateMaterial("M_Floor", new Color(0.18f, 0.19f, 0.21f), 0.1f, 0.35f);
            GameObject ground = GameObject.CreatePrimitive(PrimitiveType.Plane);
            ground.name = "Ground";
            ground.transform.localScale = Vector3.one * 6f;       // 60 x 60 m
            ApplyMaterial(ground, floor);

            Material steel  = CreateMaterial("M_Steel",  new Color(0.55f, 0.57f, 0.6f), 0.9f, 0.65f);
            Material copper = CreateMaterial("M_Copper", new Color(0.72f, 0.45f, 0.2f), 1.0f, 0.7f);
            Material glow   = CreateEmissiveMaterial("M_Glow", new Color(0.1f, 0.7f, 0.9f), 3f);

            // A small arena of cover blocks / pillars.
            SpawnBlock("Pillar_1", new Vector3(-4f, 1.5f, 4f),  new Vector3(1.2f, 3f, 1.2f), steel);
            SpawnBlock("Pillar_2", new Vector3(4f, 1.5f, 4f),   new Vector3(1.2f, 3f, 1.2f), steel);
            SpawnBlock("Crate_1",  new Vector3(-2f, 0.75f, 0f), new Vector3(1.5f, 1.5f, 1.5f), copper);
            SpawnBlock("Crate_2",  new Vector3(2.5f, 0.75f, 1f),new Vector3(1.5f, 1.5f, 1.5f), copper);
            SpawnBlock("Wall",     new Vector3(0f, 2f, 12f),    new Vector3(20f, 4f, 1f), steel);
            SpawnBlock("Beacon",   new Vector3(0f, 0.5f, 6f),   new Vector3(0.6f, 1f, 0.6f), glow);
        }

        private static void SpawnBlock(string name, Vector3 pos, Vector3 scale, Material mat)
        {
            GameObject cube = GameObject.CreatePrimitive(PrimitiveType.Cube);
            cube.name = name;
            cube.transform.position = pos;
            cube.transform.localScale = scale;
            ApplyMaterial(cube, mat);
        }

        private static GameObject BuildPlayerRig(out Camera cam, out FPSController controller, out PlayerShoot shoot)
        {
            var player = new GameObject("Player");
            player.transform.position = new Vector3(0f, 1.2f, -6f);

            CharacterController cc = player.AddComponent<CharacterController>();
            cc.height = 1.8f; cc.radius = 0.4f; cc.center = new Vector3(0f, 0f, 0f);

            var pivot = new GameObject("CameraPivot");
            pivot.transform.SetParent(player.transform, false);
            pivot.transform.localPosition = new Vector3(0f, 0.75f, 0f);

            var camGo = new GameObject("Main Camera");
            camGo.tag = "MainCamera";
            camGo.transform.SetParent(pivot.transform, false);
            cam = camGo.AddComponent<Camera>();
            cam.fieldOfView = 70f;
            cam.nearClipPlane = 0.05f;
            camGo.AddComponent<AudioListener>();

            UniversalAdditionalCameraData camData = cam.GetUniversalAdditionalCameraData();
            camData.renderPostProcessing = true;
            camData.antialiasing = AntialiasingMode.SubpixelMorphologicalAntiAliasing;
            camData.renderShadows = true;

            controller = player.AddComponent<FPSController>();
            shoot = player.AddComponent<PlayerShoot>();
            shoot.Bind(cam);

            return player;
        }

        private static void BuildPostProcessing()
        {
            var go = new GameObject("Global Volume");
            Volume volume = go.AddComponent<Volume>();
            volume.isGlobal = true;
            volume.priority = 1f;
            volume.sharedProfile = GraphicsSetup.LoadVolumeProfile();
        }

        // --- HUD ------------------------------------------------------------- //

        private static void BuildHud(FPSController controller, PlayerShoot shoot, GameObject player)
        {
            // Canvas
            var canvasGo = new GameObject("HUD Canvas");
            Canvas canvas = canvasGo.AddComponent<Canvas>();
            canvas.renderMode = RenderMode.ScreenSpaceOverlay;
            CanvasScaler scaler = canvasGo.AddComponent<CanvasScaler>();
            scaler.uiScaleMode = CanvasScaler.ScaleMode.ScaleWithScreenSize;
            scaler.referenceResolution = new Vector2(1920f, 1080f);
            scaler.matchWidthOrHeight = 0.5f;
            canvasGo.AddComponent<GraphicRaycaster>();

            // EventSystem (legacy module also handles touch)
            if (Object.FindFirstObjectByType<EventSystem>() == null)
            {
                var es = new GameObject("EventSystem");
                es.AddComponent<EventSystem>();
                es.AddComponent<StandaloneInputModule>();
            }

            Sprite knob = AssetDatabase.GetBuiltinExtraResource<Sprite>("UI/Skin/Knob.psd");
            Sprite bg   = AssetDatabase.GetBuiltinExtraResource<Sprite>("UI/Skin/Background.psd");
            Sprite uis  = AssetDatabase.GetBuiltinExtraResource<Sprite>("UI/Skin/UISprite.psd");

            // --- Move joystick (bottom-left) ---
            RectTransform stickBg = CreateUiImage("MoveStick", canvas.transform,
                new Vector2(0f, 0f), new Vector2(240f, 240f), new Vector2(200f, 200f),
                new Color(1f, 1f, 1f, 0.18f), bg);
            VirtualJoystick joystick = stickBg.gameObject.AddComponent<VirtualJoystick>();
            RectTransform handle = CreateUiImage("Handle", stickBg,
                new Vector2(0.5f, 0.5f), Vector2.zero, new Vector2(90f, 90f),
                new Color(1f, 1f, 1f, 0.55f), knob);

            // --- Look area (right half, transparent but raycastable) ---
            RectTransform look = CreateUiImage("LookArea", canvas.transform,
                new Vector2(1f, 0.5f), new Vector2(-480f, 0f), new Vector2(960f, 1080f),
                new Color(1f, 1f, 1f, 0f), null);
            TouchLookArea lookArea = look.gameObject.AddComponent<TouchLookArea>();

            // --- Fire button (bottom-right) ---
            RectTransform fireRt = CreateUiImage("FireButton", canvas.transform,
                new Vector2(1f, 0f), new Vector2(-180f, 180f), new Vector2(200f, 200f),
                new Color(1f, 0.25f, 0.2f, 0.55f), knob);
            Button fireBtn = fireRt.gameObject.AddComponent<Button>();
            UnityEventTools.AddPersistentListener(fireBtn.onClick, shoot.Fire);

            // --- Crosshair (center) ---
            CreateUiImage("Crosshair", canvas.transform,
                new Vector2(0.5f, 0.5f), Vector2.zero, new Vector2(10f, 10f),
                new Color(1f, 1f, 1f, 0.8f), uis);

            // Wire the controller's references now that the HUD exists.
            Transform pivot = player.transform.Find("CameraPivot");
            controller.Bind(joystick, lookArea, pivot);
        }

        private static RectTransform CreateUiImage(string name, Transform parent,
            Vector2 anchor, Vector2 anchoredPos, Vector2 size, Color color, Sprite sprite)
        {
            var go = new GameObject(name, typeof(RectTransform));
            go.transform.SetParent(parent, false);
            RectTransform rt = (RectTransform)go.transform;
            rt.anchorMin = anchor;
            rt.anchorMax = anchor;
            rt.pivot = new Vector2(0.5f, 0.5f);
            rt.sizeDelta = size;
            rt.anchoredPosition = anchoredPos;

            Image img = go.AddComponent<Image>();
            img.color = color;
            if (sprite != null) img.sprite = sprite;
            img.raycastTarget = true;
            return rt;
        }

        // --- Materials ------------------------------------------------------- //

        private static Material CreateMaterial(string name, Color baseColor, float metallic, float smoothness)
        {
            string path = $"{MatDir}/{name}.mat";
            var existing = AssetDatabase.LoadAssetAtPath<Material>(path);
            if (existing != null) return existing;

            Directory.CreateDirectory(MatDir);
            var mat = new Material(Shader.Find("Universal Render Pipeline/Lit"));
            mat.SetColor("_BaseColor", baseColor);
            mat.SetFloat("_Metallic", metallic);
            mat.SetFloat("_Smoothness", smoothness);
            AssetDatabase.CreateAsset(mat, path);
            return mat;
        }

        private static Material CreateEmissiveMaterial(string name, Color emission, float intensity)
        {
            string path = $"{MatDir}/{name}.mat";
            var existing = AssetDatabase.LoadAssetAtPath<Material>(path);
            if (existing != null) return existing;

            Directory.CreateDirectory(MatDir);
            var mat = new Material(Shader.Find("Universal Render Pipeline/Lit"));
            mat.SetColor("_BaseColor", emission * 0.2f);
            mat.EnableKeyword("_EMISSION");
            mat.globalIlluminationFlags = MaterialGlobalIlluminationFlags.RealtimeEmissive;
            mat.SetColor("_EmissionColor", emission * intensity);
            AssetDatabase.CreateAsset(mat, path);
            return mat;
        }

        private static void ApplyMaterial(GameObject go, Material mat)
        {
            var r = go.GetComponent<Renderer>();
            if (r != null) r.sharedMaterial = mat;
        }
    }
}
