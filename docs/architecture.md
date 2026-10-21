# Adapter architecture and source evidence

The shared Java core owns bridge state, camera copying, diagnostic limits and native
loading. It exposes no Minecraft types. Build-generated mixins contain exact audited
Iris/Oculus class and method descriptors for one game/loader tuple. Those shader-mod
classes keep their own release names, so the mixins use `remap=false` and do not
reference obfuscated Minecraft classes as injection targets. There is no empty or
fabricated refmap. Camera vector fields and legacy matrix methods are separately
pinned to the runtime loader namespace.

`compatibility-lock.json` records all eight actual Modrinth version IDs, URLs,
checksums, shader-mod metadata versions, descriptors, class names and mappings.
`tools/classfile.py` reads their classfiles without executing game code. The build
verifies every injection method plus the actual instructions in the pipeline
constructor and `beginLevelRendering` that call `RenderTargets` allocation/resize.
Trace hooks are required and verified too. Drift fails the build; no `require=0`
fallback pretends that an absent rendering hook worked.

The six older profiles use an integer depth ID at allocation argument 2 and resize
argument 1. All dimensions and depth are replaced together through `@ModifyArgs`.
The 1.18.2 profiles use `net.coderbot.iris.postprocess.FinalPassRenderer`,
`pipeline.newshader.NewWorldRenderingPipeline`, and the three-argument
`rendertarget.DepthTexture` constructor. Both 1.20.1 artifacts, including Oculus
1.8.0, already use `net.irisshaders.iris` and named four-argument depth construction.

26.2 changes the depth argument to `GpuTexture`. The adapter calls the real game
`RenderSystem.getDevice()` and `GpuDevice.createTexture(String,int,GpuFormat,int,int,int,int)`
API with the source texture's exact `getFormat()` and `usage()`. It uses
`GlTexture.glId()` at the native boundary and closes the object on replacement and
pipeline destruction. Extent, GL format and usage form the reuse key. A separately
SHA-locked Mojang 26.2 client artifact verifies these GPU API descriptors; this
artifact is only a build verification input, never shipped with the bridge.
OpenGL context checks guard both dimension scaling and object replacement.

The native helper's Java package/signatures retain the existing MobileGlues JNI
ABI. A failed symbol lookup closes the temporary `RTLD_NOLOAD` handle and leaves
all published pointers unchanged. Diagnostics are optional; scale, camera, viewport,
submit, final-scope and depth bindings are mandatory. This helper cannot load a
second renderer copy.

Primary references used on 2026-10-21:

- [Fabric's 26.2 rendering and loader changes](https://fabricmc.net/2026/06/15/262.html).
- [NeoForge mod files and Java entrypoints](https://docs.neoforged.net/docs/gettingstarted/modfiles/).
- [Fabric mod metadata specification](https://wiki.fabricmc.net/documentation:fabric_mod_json_spec).
- [Sponge Mixin ModifyArgs implementation](https://github.com/SpongePowered/Mixin/blob/0.8.5/src/main/java/org/spongepowered/asm/mixin/injection/invoke/ModifyArgsInjector.java): handler arguments must match the entire enclosing method descriptor; no assumed coercion is used for `ProgramSet`.
- [Iris project and release artifacts](https://modrinth.com/mod/iris/versions), [Iris source](https://github.com/IrisShaders/Iris).
- [Oculus project and release artifacts](https://modrinth.com/mod/oculus/versions), [Oculus source](https://github.com/Asek3/Oculus).
- [Official Mojang version manifest](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json), with exact 26.2 client content hash pinned in `build-dependencies.json`.
- [Fabric Yarn 1.18.2 matrix mappings](https://maven.fabricmc.net/docs/yarn-1.18.2+build.4/net/minecraft/util/math/Matrix4f.html).

Legacy matrix method names were also verified in the pinned Iris/Oculus
`gl.uniform.MatrixUniform.updateValue` bytecode: Fabric `method_4932(FloatBuffer)`
and Forge `m_27650_(FloatBuffer)`. Camera vector members are Fabric intermediary
`field_1352/1351/1350`, Forge SRG `f_82479_/82480_/82481_`, and unobfuscated
NeoForge/26.2 `x/y/z`.

The local JDK compiler was obtained from the official Adoptium Temurin 25 release
asset `OpenJDK25U-jdk_x64_linux_hotspot_25.0.4.1_1.tar.gz`, verified against its
published SHA-256 `dbb698396d478e7fa2b1e50f4103324b2a99b90569ee27c33f2261f9215cf41e`.
It reports `Temurin-25.0.4.1+1-LTS`; the Actions workflow pins the same Java version.
