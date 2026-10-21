# Validation record — 2026-10-21

All eight requested profiles were built locally using JDK 25.0.4.1+1, Python 3.14
and NDK 28.2.13676358. All bridge Java classes target Java 17. Per-build source,
artifact and native helper hashes are written to `dist/*-build.json`; they are
regenerated for each build. The historical commit and JAR hash in the device record
below identify the exact tested artifact, rather than a rebuild of these later docs.

| Check | Result / evidence boundary |
| --- | --- |
| Eight pinned Iris/Oculus artifact hashes | PASS |
| Exact methods, allocation/resize invocation instructions and trace selectors | PASS |
| Minecraft 26.2 GpuTexture/GpuDevice/GlTexture API descriptors | PASS |
| Java compile and lint | PASS, `--release 17 -Xlint:all -Werror` |
| Both Android native ABIs | PASS, `-Wall -Wextra -Werror`, 16 KB ELF alignment |
| Host scale and camera mapping contracts | PASS across all eight profile resources |
| GL state after injected attachment/query/clear failure | PASS on host fixtures |
| JNI loaded-core-only binding | PASS using real host `dlopen` with test shared libraries |
| JNI incomplete ABI retry | PASS: repeated init remains false, later complete mapped ABI binds |
| Dependency/test classes excluded from JAR | PASS |
| Packaged native checksum manifests | PASS |
| Java/Python AST parsing | PASS; shared Java: 4 files, 96 methods, 11 native methods, 37 catches, zero broad Throwable catches, zero empty catches |
| Repeated-build byte identity | PASS for all eight JARs; `dist/reproducibility.json` records final artifact hashes |
| Loader launch / Mixin transformation in Minecraft | PASS for 1.21.1 NeoForge only: version 0.2.0 recognized, all seven required bridge mixins applied; seven other profiles NOT RUN |
| New standalone JAR on X1E | One 1.21.1 NeoForge world-render and save/quit smoke PASS; FSR acceptance remains open |
| X2E hardware or driver | NOT RUN |
| Vulkan | Unsupported and rejected before scene scaling |
| Visual fidelity, moving objects, temporal isolation, performance and lifecycle | NOT QUALIFIED |

Host fixtures deliberately provide GL method failures and minimal native ABI symbols.
They establish error handling and binding behavior, not renderer execution. No
Minecraft class is run by the bytecode verifier. The extraction/build made no
device changes. The subsequent physical-device smoke is recorded separately below.

## Hosted build and X1E smoke

[GitHub Actions run 39553378205, attempt 2](https://github.com/BaiTian6641/MobileGlues-FSR3-Bridge/actions/runs/39553378205)
passed all eight profile jobs and uploaded all eight JARs. Every downloaded JAR
and build report was byte-identical to the clean local build of commit
`287fff5e6e72c6cf94b082f13f81cb7250d012ec`. The 2026-10-21 device smoke used
`mgfsr3bridge-0.2.0-1.21.1-neoforge.jar`, SHA-256
`db37b5f138f8271cf5fc90735a724a365b0f72d0a045350212eae2433439842b`.
Its embedded source-tree SHA-256 is
`0855f370b77968e9131e960a07c04ef930ed25cc1b9199f6d3160524e6c39ab5`.
Later documentation changes affect a future build's embedded source hash; they do
not change or requalify this preserved hosted artifact.

The smoke ran on the requested Snapdragon X Elite X1E-80-100 Android target,
using ARM64 and ANGLE over Vulkan. Device serial, exact OS/provider build and
local paths are retained only in local evidence. The game log and F3 capture
both showed the tested core marker `src-167f4da0e295031b`.
Minecraft used the delivery tuple:
1.21.1, NeoForge 21.1.262, Java 21, Iris 1.8.14-beta.1 and Sodium 0.8.13 in FCL.

The loader recognized bridge 0.2.0. Its temporarily replaced installed filename
still contained `0.1.0`; the artifact hash and loaded metadata establish its
identity. The actual Minecraft debug log recorded transformation of all seven
bridge mixins: `PipelineMixin`, `ShadowRendererTraceMixin`,
`RenderTargetsTraceMixin`, `ViewportMixin`, `CompositeRendererTraceMixin`,
`FinalPassMixin` and `HandRendererTraceMixin`.

The existing 1.21.1 world rendered with `ComplementaryReimagined_r5.9.3.zip`.
The logged profile was `LOW (+2 options changed by user)`; the shaderpack archive
and these pre-existing options were preserved. Save/quit at 01:17:35 on
2026-10-21 recorded all chunks saved for the overworld, Nether and End, followed by
`All dimensions are saved`. After the game exited, the original installed 0.1 JAR
was restored byte-for-byte. The test does not leave the new JAR installed.

This is one loader/mixin/world/save-quit smoke, with no native-submit trace or
quantitative scene/output-scale evidence. It establishes neither active FSR
reconstruction nor FSR visual quality, preset correctness, temporal isolation,
performance or repeated lifecycle recovery. No other bridge profile or X2E device
was run. The full evidence narrative and retained runtime captures are linked
from the [MobileGlues runtime report](https://github.com/BaiTian6641/MobileGlues/blob/codex/desktop-gl-universal-fsr/docs/mobileglues-googlebook/2026-10-21-desktop-gl-bridge-ci.md).

## Remaining runtime gates

For the seven unrun profiles, first launch the actual loader with the exact shader
dependency and JAR hash, and verify all required mixins apply. For 1.21.1 NeoForge,
continue from the bounded smoke above with native-submit tracing and explicit
scene/output dimensions. In every lane, identify the loaded MobileGlues build
before comparing NativeAA and each scaled preset. Capture OS, provider, renderer,
GPU, game/loader/Java tuple,
scene/frame dimensions, depth format, visible frame/readback, save/quit, resize,
shader reload and world/dimension transitions. Preserve original shader archives.
26.2 additionally needs an OpenGL-selected run and a Vulkan-selected refusal check.
No host or CI result substitutes for these gates.
