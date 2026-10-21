# Resource-pack and loader metadata

Version 0.2.0 omitted `pack.mcmeta` in all eight artifacts. That is invalid for
the two supported Forge profiles, even though the bridge contains no game
assets or data. Version 0.2.1 adds the required file at the JAR root for Forge
and validates the loader descriptors and Mixin registration in every artifact.

## Cause and version policy

Forge 1.20.1 calls `Pack.readMetaAndCreate` for each mod resource pack. If it
returns null, Forge records `fml.modloading.brokenresources`; the translated
message is "File {2} failed to load a valid ResourcePackInfo". Forge 1.18.2 uses
the same null-result warning after `Pack.create`. This is a resource metadata
warning presented by the loading screen, not a diagnosis of native FSR execution.

Primary sources:

- [Forge 1.20.1 client pack loading](https://github.com/MinecraftForge/MinecraftForge/blob/1.20.1/src/main/java/net/minecraftforge/client/loading/ClientModLoader.java#L140-L150)
- [Forge warning translation](https://github.com/MinecraftForge/MinecraftForge/blob/1.20.1/src/main/resources/assets/forge/lang/en_us.json#L80)
- [Forge 1.18.x client pack loading](https://github.com/MinecraftForge/MinecraftForge/blob/1.18.x/src/main/java/net/minecraftforge/client/loading/ClientModLoader.java#L177-L187)

| Profile | Pack metadata policy | Primary source |
| --- | --- | --- |
| 1.20.1 Forge | Root `pack.mcmeta`: description and `pack_format: 15` | [Official generated MDK](https://github.com/MinecraftForge/MinecraftForge/blob/1.20.1/src/generated_mdk/resources/pack.mcmeta) |
| 1.18.2 Forge | Root `pack.mcmeta`: description, `pack_format: 9`, `forge:resource_pack_format: 8`, `forge:data_pack_format: 9` | [Official MDK](https://github.com/MinecraftForge/MinecraftForge/blob/1.18.x/mdk/src/main/resources/pack.mcmeta) |
| Four Fabric profiles | No root metadata for this code-only mod; Fabric supplies defaults for mod resources | [Fabric 1.18.2 implementation](https://github.com/FabricMC/fabric/blob/1.18.2/fabric-resource-loader-v0/src/main/java/net/fabricmc/fabric/impl/resource/loader/ModResourcePackUtil.java#L68-L82), [Fabric 1.20.1 implementation](https://github.com/FabricMC/fabric/blob/1.20.1/fabric-resource-loader-v0/src/main/java/net/fabricmc/fabric/impl/resource/loader/ModResourcePackUtil.java#L72-L91) |
| Two NeoForge profiles | No root metadata for ordinary code-only mod resources; retain loader-generated metadata | [NeoForge 1.21.1 optional metadata path](https://github.com/neoforged/NeoForge/blob/1.21.1/src/main/java/net/neoforged/neoforge/resource/ResourcePackLoader.java#L130-L171), [NeoForge metadata documentation](https://docs.neoforged.net/docs/resources/metadata/) |

Forge's 1.18.2 override keys matter: client resources use format 8 while server
data uses format 9. Setting a single value to either number can mark the other
pack type incompatible. Forge's [metadata parser patch](https://github.com/MinecraftForge/MinecraftForge/blob/1.18.x/patches/minecraft/net/minecraft/server/packs/metadata/pack/PackMetadataSectionSerializer.java.patch)
reads those typed overrides.

Do not copy one integer into every profile. Minecraft 1.21 uses resource format
34 and data format 48 ([official notes](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21));
26.2 uses resource format 88.0 and data format 107.1
([official notes](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-2)).
Modern metadata also uses `min_format` and `max_format`; the
[official schema transition](https://www.minecraft.net/en-us/article/minecraft-snapshot-25w31a)
explains when legacy fields are permitted. Retaining the loaders' synthetic
metadata avoids introducing an unnecessary resource/data compatibility claim.
The artifact validator rejects new `assets/` or `data/` content in these
code-only profiles until their metadata policy receives a separate review.

## Regression checks and evidence boundary

`tools/build.py` now validates the completed JAR before writing its build report.
`tools/test.py` imports the shared artifact version and repeats the validation
for each selected profile. The checks read the archive itself and require:

- Exactly one descriptor for the intended loader, correct mod identity and
  version, and dependencies matching the pinned Minecraft and shader-mod tuple.
- The packaged loader entrypoint and all nine client Mixin classes, valid
  loader/manifest registration, and required injection checks.
- Valid root JSON and version-correct pack formats for both Forge profiles.
- Synthetic metadata policy for the code-only Fabric and NeoForge profiles.

In-memory copies of each JAR test missing/malformed loader descriptors, a wrong
shader dependency, disabled required Mixins and missing or unregistered viewport
Mixins. They also reject duplicate JSON/TOML fields, nonstandard JSON constants,
incorrect scalar types, and malformed or ambiguous manifest sections. Forge
copies additionally test absent/malformed pack metadata, a missing pack section,
an empty description, and wrong numeric or string pack formats. The 1.18.2 tests
also reject incorrect resource and data overrides. These mutations leave the
distributed JARs intact.

Run after building the selected artifacts:

```sh
python3 tools/build.py --profile all
python3 tools/test.py --profile all
```

The checks establish packaging and host contracts. They do not launch Forge,
Fabric or NeoForge, establish that Mixins transform inside Minecraft, or qualify
FSR quality/performance. Build and runtime results from version 0.2.0 are not
acceptance evidence for the corrected version. The parent integration must
rebuild and validate its final combined source before publishing artifacts.

## Related loader boundary

The shader dependencies use `side="CLIENT"`; this controls dependency checks,
not whether a Forge mod itself loads on a dedicated server. The current Forge
MDKs document `clientSideOnly` and `displayTest` separately. This patch preserves
existing sidedness behavior rather than widening its scope. Dedicated-server
installation and multiplayer display compatibility remain separate checks.
