using UnityEngine;
using AIGames.Controls;

namespace AIGames.Gameplay
{
    /// <summary>
    /// Minimal mobile FPS controller: CharacterController locomotion driven by a
    /// virtual stick, and camera look driven by a touch-drag region. Body yaws on
    /// the Y axis; the camera pivot pitches (clamped).
    /// </summary>
    [RequireComponent(typeof(CharacterController))]
    public class FPSController : MonoBehaviour
    {
        [Header("Input")]
        [SerializeField] private VirtualJoystick moveStick;
        [SerializeField] private TouchLookArea lookArea;

        [Header("References")]
        [SerializeField] private Transform cameraPivot;   // child that holds the Camera

        [Header("Movement")]
        [SerializeField] private float walkSpeed = 4.5f;
        [SerializeField] private float gravity = -19.62f;

        [Header("Look")]
        [SerializeField] private float lookSensitivity = 0.16f;
        [SerializeField] private float minPitch = -80f;
        [SerializeField] private float maxPitch = 80f;

        private CharacterController _cc;
        private float _pitch;
        private float _verticalVelocity;

        private void Awake()
        {
            _cc = GetComponent<CharacterController>();
            if (cameraPivot == null && Camera.main != null)
                cameraPivot = Camera.main.transform;
        }

        private void Update()
        {
            HandleLook();
            HandleMove();
        }

        private void HandleLook()
        {
            if (lookArea == null) return;

            Vector2 delta = lookArea.ConsumeDelta() * lookSensitivity;

            transform.Rotate(Vector3.up, delta.x, Space.Self);     // yaw the body

            _pitch = Mathf.Clamp(_pitch - delta.y, minPitch, maxPitch);
            if (cameraPivot != null)
                cameraPivot.localRotation = Quaternion.Euler(_pitch, 0f, 0f);
        }

        private void HandleMove()
        {
            Vector2 stick = moveStick != null ? moveStick.Value : Vector2.zero;
            Vector3 planar = (transform.right * stick.x + transform.forward * stick.y) * walkSpeed;

            if (_cc.isGrounded && _verticalVelocity < 0f)
                _verticalVelocity = -2f;                           // keep grounded
            _verticalVelocity += gravity * Time.deltaTime;

            Vector3 velocity = planar + Vector3.up * _verticalVelocity;
            _cc.Move(velocity * Time.deltaTime);
        }

        /// <summary>Wires input + camera references (used by the scene builder).</summary>
        public void Bind(VirtualJoystick move, TouchLookArea look, Transform pivot)
        {
            moveStick = move;
            lookArea = look;
            cameraPivot = pivot;
        }
    }
}
