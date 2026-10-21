# Legacy scene-depth lifetime correction

The six pinned adapters for Minecraft 1.18.2, 1.20.1 and 1.21.1 use integer GL
texture names. Their Iris/Oculus `RenderTargets` objects cache the selected depth
texture and refresh its framebuffer attachments only when the supplied depth
generation changes. The constructor takes depth and generation at arguments 2
and 3; `resizeIfNeeded` takes generation and depth at arguments 0 and 1.

The earlier bridge replaced the scene texture and dimensions while forwarding
Minecraft's unchanged generation. A quality change could therefore leave Iris
using an old depth attachment. It also destroyed the old bridge texture before
Iris adopted the replacement. Switching to Native AA returned the original
texture without clearing the bridge's managed submission texture.

## Corrected contract

- Track the source generation, selected integer texture name and bridge allocation
  revision together. Compare texture names by value, including names above 127;
  an allocation revision also distinguishes a recycled GL name.
- Substitute the bridge generation in both constructor and resize arguments.
  Repeating an unchanged, completed selection keeps its generation stable.
- Retain replaced resources until Iris returns normally after adopting them.
  Completion also runs when `resizeIfNeeded` returns false: that result describes
  an unchanged extent and does not imply an unchanged depth attachment.
- If adoption throws, keep the resources alive and advance generation on retry.
  Iris may have updated its cached generation before updating every attachment.
- Native AA, disabled scaling, a pack without a final pass, and an unavailable
  OpenGL context select the original depth and stop submitting managed depth.
- Preserve the independent no-hand and no-translucent snapshot attachments. All
  six pinned legacy implementations already exclude those FBOs from this rebind
  loop and resize their snapshot textures in place. The 26.2-specific snapshot
  redirect is not applied to these adapters.

The 26.2 object-identity path remains unchanged. Its existing ownership tests
still run. This correction does not change shaderpack ZIPs or loader/version
ranges, and does not establish a fix for the separate 26.2 missing-terrain result.

## Verification and boundaries

The build now pins the exact legacy constructor and resize method bodies, the
cache/snapshot field descriptors and the unique pipeline call sites in addition
to the upstream JAR checksums. All six pinned contracts pass; 18 deliberate
constructor, resize and field-contract mutations are rejected.

Each legacy profile passes 86 host checks against the generated bridge handlers
and the pinned Iris/Oculus resize/getter bytecode. GL allocation and attachment
operations and JNI scale values use controlled host probes. These checks cover
stable large texture IDs, same-source-generation quality changes, same-size
replacement, snapshot preservation, Native AA at unchanged extent, throwing
adoption and retry, source-only changes, recycled names, allocation failure and
retirement when scaling is unavailable. They do not execute a game, real Mixin
transformation or GPU depth rendering.

The preserved official 0.2.3 JARs from commit
`2f0e4faf57489c51418bfa9fe534d63f7623a769` fail two independently repeated
witnesses per legacy profile: unchanged source generation after a quality change,
and stale managed submission depth after Native AA. The 12 exact old-JAR hashes,
assertions and failing exit codes are in
[the negative-control record](evidence/legacy-depth-0.2.3-negative.json).
The full eight-profile host suite also runs the existing 26.2 ownership,
viewport, submission and diagnostic contracts.

Physical quality switching, shader reloads, dimension/pipeline transitions and
save/quit still require separate checks with the newly built JAR and its exact
MobileGlues APK. Earlier game smoke tests do not qualify this change. The
supported development matrix remains the eight explicitly pinned profiles;
Minecraft 1.17, intermediate releases, future 26.2+ releases and X2E hardware
are not automatically qualified by these host checks.
