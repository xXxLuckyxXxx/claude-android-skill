using UnityEngine;
using UnityEngine.EventSystems;

namespace AIGames.Controls
{
    /// <summary>
    /// A transparent, raycast-enabled screen region (typically the right half)
    /// that converts touch drags into a camera look delta. The delta accumulates
    /// between reads; the controller pulls it once per frame via ConsumeDelta().
    /// </summary>
    public class TouchLookArea : MonoBehaviour,
        IPointerDownHandler, IDragHandler, IPointerUpHandler
    {
        private Vector2 _accumulated;
        private int _activePointerId = -1;

        public void OnPointerDown(PointerEventData e)
        {
            if (_activePointerId != -1) return;
            _activePointerId = e.pointerId;
        }

        public void OnDrag(PointerEventData e)
        {
            if (e.pointerId != _activePointerId) return;
            _accumulated += e.delta;
        }

        public void OnPointerUp(PointerEventData e)
        {
            if (e.pointerId != _activePointerId) return;
            _activePointerId = -1;
        }

        /// <summary>Returns the look delta gathered since the last call, then clears it.</summary>
        public Vector2 ConsumeDelta()
        {
            Vector2 d = _accumulated;
            _accumulated = Vector2.zero;
            return d;
        }
    }
}
