using UnityEngine;

namespace AIGames.Gameplay
{
    /// <summary>
    /// Tiny hitscan shooter for the prototype: raycasts from the camera and drops
    /// a short-lived impact marker at the hit point. Hook Fire() to a UI button.
    /// </summary>
    public class PlayerShoot : MonoBehaviour
    {
        [SerializeField] private Camera shootCamera;
        [SerializeField] private float range = 100f;
        [SerializeField] private float impactSize = 0.15f;

        private void Awake()
        {
            if (shootCamera == null) shootCamera = GetComponentInChildren<Camera>();
            if (shootCamera == null) shootCamera = Camera.main;
        }

        /// <summary>Fires one hitscan shot. Wire this to the on-screen fire button.</summary>
        public void Fire()
        {
            if (shootCamera == null) return;

            Ray ray = new Ray(shootCamera.transform.position, shootCamera.transform.forward);
            if (Physics.Raycast(ray, out RaycastHit hit, range))
                SpawnImpact(hit.point, hit.normal);
        }

        private void SpawnImpact(Vector3 pos, Vector3 normal)
        {
            GameObject marker = GameObject.CreatePrimitive(PrimitiveType.Sphere);
            marker.name = "Impact";
            marker.transform.position = pos + normal * 0.01f;
            marker.transform.localScale = Vector3.one * impactSize;

            Collider col = marker.GetComponent<Collider>();
            if (col != null) Destroy(col);

            Destroy(marker, 3f);
        }

        public void Bind(Camera cam) => shootCamera = cam;
    }
}
