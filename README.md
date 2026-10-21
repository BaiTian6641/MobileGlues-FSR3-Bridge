# MobileGlues FSR3 Bridge

A client mod connecting Iris/Oculus render passes to the FSR3 backend already loaded
by MobileGlues on Android. It does not contain an upscaler or another renderer.
Install the JAR matching both Minecraft and its mod loader alongside the pinned
shader mod below. The MobileGlues renderer plugin remains a separate APK.

## Implemented adapters and evidence

| Minecraft | Loader | Pinned shader mod | Depth adapter | Current evidence |
| --- | --- | --- | --- | --- |
| 26.2 | Fabric | Iris 1.11.4 (`gxZWWnKH`) | Mojang `GpuTexture`, OpenGL only | Build, bytecode/API verification, host contracts |
| 26.2 | NeoForge | Iris 1.11.4 (`k55HdONq`) | Mojang `GpuTexture`, OpenGL only | Build, bytecode/API verification, host contracts |
| 1.21.1 | Fabric | Iris 1.8.14-beta.1 (`bAo1Qhte`) | Iris `DepthTexture` | Build, bytecode verification, host contracts |
| 1.21.1 | NeoForge | Iris 1.8.14-beta.1 (`KduFYu4t`) | Iris `DepthTexture` | Build, bytecode verification, host contracts |
| 1.20.1 | Fabric | Iris 1.7.6 (`s5eFLITc`) | Iris `DepthTexture` | Build, bytecode verification, host contracts |
| 1.20.1 | Forge | Oculus 1.8.0 (`iQ1SwGc3`) | Iris `DepthTexture` | Build, bytecode verification, host contracts |
| 1.18.2 | Fabric | Iris 1.6.11 (`ogIRhnAJ`) | Legacy three-argument `DepthTexture` | Build, bytecode verification, host contracts |
| 1.18.2 | Forge | Oculus 1.6.4 (`UynWw2df`) | Legacy three-argument `DepthTexture` | Build, bytecode verification, host contracts |

All eight implement scene color/depth scaling, final-pass reconstruction, viewport
uniforms, camera capture and lifecycle cleanup. These are **development artifacts**.
No loader launch, Minecraft world render, physical X1E/X2E run, visual-fidelity gate,
thermal/performance gate, or lifecycle qualification has been performed for this
standalone revision. The older in-tree 1.21.1 NeoForge bridge has separate device
history; it does not qualify the changed JARs here. See [validation](docs/validation.md).

Minecraft 26.2 has an experimental Vulkan backend. This bridge requires a current
OpenGL context and a real `GlTexture`; it does not translate Vulkan or support
Aperture/Vitrail. The 26.2 adapter allocates a real `GpuTexture` through
`GpuDevice.createTexture`, retaining the original depth format and usage flags.

The delivery baseline remains Minecraft 1.21.1 / NeoForge 21.1.262 / Java 21 /
Iris 1.8.14-beta.1 / Sodium 0.8.13. Each other row is an additional development lane.
The bridge's common Java code targets Java 17; Minecraft 1.21.1 requires Java 21
and 26.2 requires Java 25. Fabric artifacts currently require Loader 0.19.3 or newer.

## Build

Install Python 3.12+, JDK 25, and Android NDK **28.2.13676358 (r28c)**. JDK 25 is
needed to read 26.2's actual dependency classes; the emitted bridge classes remain
Java 17 bytecode. No Gradle, game remapping task, user-specific launcher installation,
or external MobileGlues checkout is needed.

```sh
export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/28.2.13676358"
sh build.sh --profile all
python3 tools/test.py --profile all
```

To build one lane, for example, use `sh build.sh --profile 1.21.1-neoforge`.
`JAVAC=/path/to/jdk25/bin/javac` selects a compiler explicitly. Build outputs are
`dist/mgfsr3bridge-0.2.0-<minecraft>-<loader>.jar`, with per-profile JSON reports.
Every JAR includes Android `arm64-v8a` and `x86_64` helpers with 16 KB-aligned ELF
segments. x86_64 is an ABI build lane and does not establish target-device support.

Build dependencies and upstream shader artifacts have SHA-256 locks. Upstream game
and shader binaries stay in the ignored `.cache/`; they are never redistributed in
this mod. ZIP timestamps, order, permissions and source provenance are deterministic.
`SOURCE_DATE_EPOCH` can choose another stable ZIP timestamp. Changing source or
locks changes the embedded source hash intentionally.

[GitHub Actions](.github/workflows/build.yml) builds all eight JARs, runs host
contracts, inventories Java ASTs, checks native checksum packaging, repeats the
build to verify reproducibility, and uploads artifacts. It has read-only repository
permissions and does not publish releases. A green job establishes those checks,
not a Minecraft/device qualification.

## Safety and runtime boundaries

- The helper binds only an already loaded MobileGlues core using `RTLD_NOLOAD`.
  It validates all rendering ABI symbols before publishing the binding. Missing or
  incompatible cores leave the bridge unavailable.
- Native extraction uses a private temporary directory, bundled SHA-256 checks,
  a 4 MiB size bound, owner-only permissions, and unlink after loading.
- One argument handler changes scene width, height and depth together. Failed
  depth allocation is explicit; it cannot silently combine scaled color with an
  original-size depth surface. GL state is restored after failed attachment, clear,
  and format-query calls.
- No shaderpack ZIP is modified. Packs without a final pass remain unscaled.
  Existing pack TAA and effects remain intact; moving-object motion, jitter ownership,
  pack temporal-history isolation and visual acceptance are separate open gates.
- Camera capture handles legacy Mojang matrices and loader-specific vector field
  mappings. A failed capture invalidates native camera history rather than reusing
  stale matrices.
- Diagnostics are opt-in using `mgfsr3-trace.arm` in the game directory and bounded
  to 120 frames, 3,000 events and 12 Mi UTF-16 code units. Repeated submit errors are rate limited.

## Source and compatibility policy

This repository was extracted from `BaiTian6641/MobileGlues`,
`integrations/minecraft/fsr3-bridge`, on 2026-10-21. The original directory remains
an independent historical integration snapshot until explicitly migrated.
The LGPL-2.1 license is retained. Iris, Oculus, Minecraft and loader artifacts
retain their own licenses and are not bundled.

Read [architecture and source evidence](docs/architecture.md) before extending a
profile. Broaden metadata only after verifying its actual release class descriptors,
call sites, ownership APIs and mapping names, then execute that lane's runtime gates.
