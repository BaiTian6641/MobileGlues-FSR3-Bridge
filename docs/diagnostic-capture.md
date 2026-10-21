# Bounded terrain and submission diagnostics

Bridge 0.2.3 connects the existing trace phase recorder to each pinned Iris/Oculus
`Pipeline.setPhase(WorldRenderingPhase)` return boundary. Earlier bridge traces
recorded shadow, hand, composite and final passes but did not call the phase
recorder, so those captures could not establish the main opaque terrain draw state.
The exact method descriptor, method `Code` bytes and enum constants are verified
against all eight pinned shader-mod JARs during the build. No Minecraft class hook
or shader-name condition is added.

The recorder captures `TERRAIN_SOLID`, `TERRAIN_CUTOUT_MIPPED`, `TERRAIN_CUTOUT` and
`TERRAIN_TRANSLUCENT` phases on every captured frame. Repeated calls for the same
phase retain one role, so earlier draw observations are not discarded. Existing
shadow, hand, composite and final roles retain ownership across nested phase
notifications. Other phase sampling follows the existing policy. The native
recorder's role-end data includes accumulated draw count and the first draw's GL
state; it is not a state history for every terrain draw.

Marker polling stays at the world-frame boundary. The capture stops at the first
existing limit: 120 frames, 3000 events or 12 Mi characters. Additional terrain
phases can reach the event or character budget before 120 frames; the manifest
reports the reason and actual counts. Diagnostic failures in the new phase and
submission callbacks are isolated from rendering. Tracing remains opt-in through
`mgfsr3-trace.arm` in the actual game directory and requires a renderer built with
`MG_FSR3_DIAGNOSTICS=ON`.

`SUBMIT_RESULT` is emitted after the Java submission attempt and target cleanup,
with `gl: null`: collecting it does not issue another GL or JNI trace query.
Its `iris` object contains:

| Field | Meaning |
| --- | --- |
| `attempted` | Java invoked `NativeBridge.submit`; false if setup rejected first. |
| `native_rc` | Returned JNI status, or null when no status returned. Zero alone does not establish visual acceptance. |
| `stage` | `unavailable`, `target_bind`, `native_submit`, `target_restore` or `complete`; a complete call may return a nonzero status. |
| `exception_class` | Exception type only, never its message or path-bearing text. |
| `main_target_verified` | Whether the adapter verified the authoritative main color identity and extent. Currently true only for the 26.2 target adapter. |
| `main_color_texture`, `main_width`, `main_height` | Immutable verified target data, or null when unsupported or setup did not complete. |
| `sampled_depth_reversed` | The profile's requested sampled-depth convention. |
| `depth_convention_negotiated` | Initial helper/core negotiation succeeded; this is not temporal projection or GPU-dispatch acceptance. |

If cleanup throws after a JNI status returns, the event retains that status and
records `target_restore` with the cleanup exception class. Legacy profiles keep
unknown main-target identity as null. The report does not infer submission success
from the prior `FINAL_DRAW_RETURN` event or from a missing launcher log message.

## Verification and limits

The host tests execute the actual generated phase handler with each artifact's
unmodified enum class, and use the real Java/JNI bridge with a transport fixture.
They cover optional trace failure, duplicate phase notifications, shadow ownership,
late-frame terrain roles, 120-frame termination, exact 3000-event termination,
negative/zero JNI statuses, native exceptions and rejection before a native call.
Every emitted JSON line is parsed; hook bytecode and enum mutations are rejected.
The verified target metadata and cleanup-failure result schema are tested directly;
that schema test does not inject a throwing target close through `Fsr3Bridge.submit`.

These are host control-flow and packaging checks. They do not perform a live Mixin
transformation, render terrain, prove a target GPU result, or qualify shader effects.
The next physical capture must confirm nonempty terrain roles, their actual
framebuffer/depth/viewport state, and a subsequent submission outcome on the exact
APK/JAR pair under test. No prior screenshot or pass-level capture supplies that
missing evidence.
