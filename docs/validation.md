# Validation record — 2026-10-21

All eight requested profiles were built locally using JDK 25.0.4.1+1, Python 3.14
and NDK 28.2.13676358. All bridge Java classes target Java 17. Per-build source,
artifact and native helper hashes are written to `dist/*-build.json`; they are
regenerated rather than copied into this document, so edits cannot silently retain
outdated hash claims.

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
| Loader launch / Mixin transformation in Minecraft | NOT RUN |
| New standalone JAR on X1E | NOT RUN |
| X2E hardware or driver | NOT RUN |
| Vulkan | Unsupported and rejected before scene scaling |
| Visual fidelity, moving objects, temporal isolation, performance and lifecycle | NOT QUALIFIED |

Host fixtures deliberately provide GL method failures and minimal native ABI symbols.
They establish error handling and binding behavior, not renderer execution. No
Minecraft class is run by the bytecode verifier. No game world, shaderpack archive,
launcher configuration or physical device was modified by the extraction/build.

For each lane, the next runtime gate is an actual loader launch with the exact
shader dependency and JAR hash; verify all required mixins apply. Then use the
MobileGlues build marker to identify the loaded renderer before comparing NativeAA
and each scaled preset. Capture OS, provider, renderer, GPU, game/loader/Java tuple,
scene/frame dimensions, depth format, visible frame/readback, save/quit, resize,
shader reload and world/dimension transitions. Preserve original shader archives.
26.2 additionally needs an OpenGL-selected run and a Vulkan-selected refusal check.
No host or CI result substitutes for these gates.
