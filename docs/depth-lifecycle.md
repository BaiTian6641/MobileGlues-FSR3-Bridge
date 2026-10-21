# Scene depth conventions and ownership

The 26.2 adapter owns a separate scene depth texture while Minecraft retains its
main depth texture and views. A scene depth replacement must update Iris' cached
texture identity and every framebuffer attachment before the old owned texture
can be closed. Minecraft's main texture is never bridge-owned.

The pinned Iris `RenderTargets.resizeIfNeeded` updates `currentDepthTexture` only
when its incoming generation differs from `cachedDepthBufferVersion`. Changing
the texture argument alone does not update that cache. Its boolean result reports
an extent change, so a successful texture replacement can return `false`.

`SceneDepthGeneration` therefore tracks Minecraft's generation and the selected
depth object together. The constructor and resize call receive the same generated
version sequence. The bridge retains replaced textures and their clear framebuffers
until Iris returns normally; it releases them even when resize returns `false`.
A throwing resize leaves both resources alive. A retry uses another generation
because Iris may already have changed its cache before failing while rebinding
attachments. This is not rollback of Iris' complete resize operation: failures
inside its color-texture resize loop can still require pipeline recreation.
Teardown releases outstanding bridge resources.

The same Iris resize loop also visits `noHandDestFb` and `noTranslucentsDestFb`,
which own independent depth snapshots. Unmodified, a generation change attaches
the current scene depth to both snapshot destinations. The 26.2 adapter redirects
the three exact `addDepthAttachment` call sites: only those two receiver identities
use their corresponding current snapshot texture; every other framebuffer keeps
the supplied depth argument. Iris publishes newly allocated snapshot fields before
these calls. This preserves the original call count and attachment operation,
without closing snapshot resources or introducing an extra per-frame GL operation.

The pinned Minecraft 26.2 `GameRenderer.render` clears main depth to `0.0`.
The bridge uses the same clear value for its 26.2 scene depth; older adapters retain
the forward-depth clear value `1.0`. Iris clears shadow depth separately to `1.0`;
that shadow convention does not describe its world depth texture. Exact locked
method bytecode and the clear constant are checked during the build.

The optional JNI `nativeDepthConvention(int)` contract describes **sampled depth**:

| Value | Sampled depth | Captured Iris projection |
| --- | --- | --- |
| `0` | Forward, near 0 and far 1 | Forward, clip Z in [-1, 1] |
| `1` | Reversed, near 1 and far 0 | Forward, clip Z in [-1, 1] |

Iris 26.2 explicitly applies `MatrixUtils.undoRevZ` before publishing
`CapturedRenderingState.getGbufferProjection`. Its captured projection must not
be treated as the original reversed projection. Older renderer cores without the
optional negotiation symbol accept only convention 0; the bridge disables its
26.2 allocation/submission path when convention 1 is unsupported. Native
normalization and any temporal-history restrictions belong to the renderer.

Host tests execute the pinned Iris resize and getter bytecode, with ownership
probes replacing GPU allocation and attachment work. They cover unchanged first
resize, replacement identity, source generation, same-size adoption returning
false, partial attachment failure, retry, Native AA transitions and teardown.
They also reproduce the original snapshot-attachment overwrite and exercise the
generated redirect handler with independent snapshot and scene identities. This
handler test does not establish a live Mixin transformation.
They establish Java cache/ownership behavior, not physical GPU acceptance.
The observed 26.2 missing-terrain failure still requires a device retest.

Diagnostics require a renderer built with `MG_FSR3_DIAGNOSTICS=ON` and a regular
`mgfsr3-trace.arm` file in the game directory; empty marker content is sufficient.
A core without the optional diagnostic API reports that condition once and keeps
the marker. Texture identities are serialized only as numeric IDs or JSON null,
including 26.2 `GpuTexture` objects; arbitrary reflective objects are never emitted
as unquoted JSON values.
