using UnityEngine;
using UnityEngine.EventSystems;

namespace AIGames.Controls
{
    /// <summary>
    /// On-screen analog stick driven by touch. Implemented purely through the
    /// EventSystem drag interface so it works with the legacy input backend and
    /// needs no extra packages. Output is a normalized Vector2 in [-1, 1].
    /// </summary>
    [RequireComponent(typeof(RectTransform))]
    public class VirtualJoystick : MonoBehaviour,
        IPointerDownHandler, IDragHandler, IPointerUpHandler
    {
        [SerializeField] private RectTransform background;
        [SerializeField] private RectTransform handle;
        [SerializeField, Range(10f, 400f)] private float movementRange = 120f;
        [SerializeField, Range(0f, 0.5f)] private float deadZone = 0.08f;

        /// <summary>Current stick value, normalized to [-1, 1] per axis.</summary>
        public Vector2 Value { get; private set; }

        private int _activePointerId = -1;

        private void Awake()
        {
            if (background == null) background = transform as RectTransform;
            if (handle == null && transform.childCount > 0)
                handle = transform.GetChild(0) as RectTransform;
        }

        public void OnPointerDown(PointerEventData e)
        {
            if (_activePointerId != -1) return;   // already owned by another finger
            _activePointerId = e.pointerId;
            OnDrag(e);
        }

        public void OnDrag(PointerEventData e)
        {
            if (e.pointerId != _activePointerId) return;

            RectTransformUtility.ScreenPointToLocalPointInRectangle(
                background, e.position, e.pressEventCamera, out Vector2 local);

            Vector2 raw = local / movementRange;          // 1.0 at the edge
            if (raw.magnitude > 1f) raw = raw.normalized;
            Value = raw.magnitude < deadZone ? Vector2.zero : raw;

            if (handle != null)
                handle.anchoredPosition = Value * movementRange;
        }

        public void OnPointerUp(PointerEventData e)
        {
            if (e.pointerId != _activePointerId) return;
            _activePointerId = -1;
            Value = Vector2.zero;
            if (handle != null) handle.anchoredPosition = Vector2.zero;
        }
    }
}
