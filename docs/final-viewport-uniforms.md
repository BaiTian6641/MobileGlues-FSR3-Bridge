# Final-pass viewport expressions

The bridge renders scene targets at the selected quality extent and retains the
existing native-resolution shaderpack final pass. Iris/Oculus evaluates custom
uniform expressions before scene rendering, then uploads their cached values in
the final pass. Switching `viewWidth` and `viewHeight` alone therefore left
derived coordinates such as `vec2(1 / viewWidth, 1 / viewHeight)` at the scene
extent. A final shader using that cached value with native `gl_FragCoord` samples
beyond normalized screen coordinates and can repeat texture edges.

The adapter now refreshes the standard viewport inputs and their pure dependent
expressions when entering the final pass. It follows the shader mod's existing
dependency order and restores those values on normal exit and next-frame
recovery after an aborted pass. The refresh runs only when the bridge enters its
final scope. It does not name or modify a shaderpack, shader program, or custom
uniform.

Calling the shader mod's complete `CustomUniforms.update()` again would also
advance `smooth()` and random expressions. The adapter instead reads each
original parsed expression through a pinned Mixin accessor. Known arithmetic,
vector access, and explicitly allowed mathematical functions can be refreshed;
stateful functions, unknown functions, unknown syntax nodes, and overly deep or
large expressions keep their cached frame value. Pure expressions can consume
that preserved value together with a refreshed viewport input. A preserved
node alone does not trigger reevaluation of its descendants. The adapter logs
once per custom-uniform container when it preserves a viewport-dependent node.

This is a bounded correction for pure viewport-derived expressions. A mixed
expression containing both viewport arithmetic and stateful or unsupported
functions remains at its scene-frame value. Final shaders using integer pixel
fetches into reduced textures still need a coherent scene-sized final rendering
contract, which this change does not introduce. Likewise, host tests do not
establish shader fidelity or performance on a device.

All eight locked shader artifacts are checked for exact custom-uniform fields,
constructor and update methods, and parsed-expression accessors. Their actual
parser and `CachedUniform` classes execute the regression tests: stale reciprocal
coordinates, dependency chains and diamonds, stateful preservation, mixed
parents, and scene restoration. Separate scope tests cover normal exit,
aborted-frame recovery, and callback failure. These tests use no substitute
shader parser or cached-uniform implementation. Fastutil is a checksum-pinned,
test-only dependency from Mojang's library repository.

Primary source references: [Iris custom-uniform evaluation and dependency order](https://github.com/IrisShaders/Iris/blob/1.20.1/src/main/java/net/irisshaders/iris/uniforms/custom/CustomUniforms.java),
[custom smoothing implementation](https://github.com/IrisShaders/Iris/blob/1.20.1/src/main/java/net/irisshaders/iris/parsing/SmoothFloat.java),
and [custom expression functions](https://github.com/IrisShaders/Iris/blob/1.20.1/src/main/java/net/irisshaders/iris/parsing/IrisFunctions.java).
The build verifies the pinned release JARs rather than relying on these moving
source branches as compatibility evidence.
