# Packaging and scaling regressions, 2026-10-21

This investigation separates three failures observed with bridge 0.2.0. The
source corrections below are in 0.2.1; live acceptance of the corrected bytes
must be recorded with their hashes after testing. The requested delivery lane
remains Minecraft 1.21.1 / NeoForge 21.1.262. Legacy Forge and 26.2 are additional
test lanes, not replacements for it.

| Failure | Evidence | Correction |
| --- | --- | --- |
| Forge reports no valid ResourcePackInfo | Create Delight Remake, Minecraft 1.20.1, Oculus 1.8.0: missing metadata for `mod:mgfsr3bridge` | Root `pack.mcmeta` for both Forge profiles; validate actual JAR metadata for all eight profiles |
| Steadfast final image repeats texture edges | Original Steadfast 0.8.0 derives `windowToScreen` from `viewWidth`/`viewHeight`, then multiplies native final-pass `gl_FragCoord` by the cached scene reciprocal | Refresh only safe viewport-dependent custom expressions on final entry and restore scene values on exit/recovery |
| 26.2 world and subsequent menus shrink into the lower-left corner | Correct 26.2 NeoForge/Iris 1.11.4/Sodium 0.9.2 tuple, Complementary Reimagined r5.9.3; actual final-pass bytecode can leave a swap framebuffer bound | Select and validate Iris's main-color framebuffer explicitly before submission; renderer refuses an intermediate output extent |

The original Steadfast archive is preserved unchanged outside Git. Its SHA-256
is `c3c9bc76f51b7fb31be37186088e3afe1863386d283d733e7ec69de7798bddfb`.
Complementary looked correct in the user's 1.20.1 comparison, but the 26.2
failure also occurs with Complementary. Therefore the 26.2 output-binding
defect must not be described as a Steadfast-only compatibility issue.

## Output ownership

All six pinned legacy Iris/Oculus final-pass implementations bind Minecraft's
main render target before returning. Both pinned 26.2 implementations omit that
binding and can finish after a swap-pass source framebuffer was bound. The old
native submission inferred the output size from whichever color attachment was
current, stored it globally as the native extent, and applied the quality scale
again. This also contaminated later menu/default-framebuffer viewport resets.

For 26.2 the adapter now obtains Minecraft's current main color texture and
extent, checks that Iris's existing `colorHolder` framebuffer contains exactly
that texture at mip zero, verifies completeness and actual GL dimensions, and
binds it for submission. Read/draw framebuffer names, current-unit texture
binding and viewport are restored afterwards, including failure paths. The
adapter does not allocate another presentation framebuffer or mutate the game
renderer's cached framebuffer names.

MobileGlues 2.0.4-dev independently takes viewport output size from the current
EGL surface and rejects a final color attachment of a different size before
allocation or FSR dispatch. It no longer stores a process-wide size inferred
from the submitted attachment. This protects old bridges from recursive extent
adoption, but the new bridge is required to select the correct 26.2 image.

The current native ABI requires the main output extent to match the EGL surface.
Launchers or dynamic-resolution systems with a separate main-output size require
an explicit future output-extent contract; they are rejected by this guard.
Current-frame identity checks cannot establish temporal-history correctness.

## Uniform and resource metadata boundaries

See [viewport-expression behavior](final-viewport-uniforms.md) and
[resource metadata](resource-pack-metadata.md) for exact source evidence and
regression checks. Mixed stateful/viewport expressions remain a limitation;
arbitrary functions are never re-evaluated merely to make one shaderpack work.
No original shaderpack archive is patched.

The actual pinned shader parser and cached-uniform implementations are used in
host tests. Separate GL fixtures inject attachment, dimension, bind and submit
failures to check restoration. Build checks verify the exact fields and methods
used by each Mixin and the 26.2 game API. These are useful source/host gates, not
proof of Minecraft launch, GPU behavior, visual fidelity or performance.

## General support from 1.17 through 26.2 and later

Support must be based on render API families and verified ownership contracts,
not a permissive Minecraft version range. The current artifact lock covers the
eight requested 1.18.2, 1.20.1, 1.21.1 and 26.2 loader combinations. It does not
yet cover 1.17, every intervening release, or uninspected future releases.

A general adapter needs separate scene, shaderpack-final, game-output and EGL
surface dimensions; explicit color/depth identities and generations; validated
viewports and compute-dispatch extents; a defined shader uniform domain; and
reset rules for reload, resize, context loss and world changes. The current
fixes close concrete ownership mistakes without claiming that all those gates
are complete. A future shared dispatch descriptor should carry these facts to
the native core rather than infer them from incidental GL state.

Iris specifies that its final pass targets the Minecraft window resolution,
independent of other buffer scales ([Iris final-pass reference](https://shaders.properties/current/reference/programs/final/)).
A general implementation must preserve that contract or virtualize the complete
shaderpack rendering domain coherently; changing only a viewport or a few
uniform names is insufficient for integer texture fetches and compute outputs.

AMD requires render-resolution color and depth with an explicit presentation
resolution and correctly interpreted motion data
([FSR upscaler integration](https://gpuopen.com/manuals/fidelityfx_sdk/techniques/super-resolution-upscaler/)).
The current stable spatial path does not establish complete temporal FSR3 or
frame-generation support. Jitter, moving-object motion, reactive masks, shader
history isolation and effect placement remain separate implementation gates.
The new packaging and scaling checks must not be used as substitutes for them.
