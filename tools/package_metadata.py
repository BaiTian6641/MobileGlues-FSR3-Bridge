"""Validate loader and resource metadata in finished bridge JARs.

Forge's resource-pack scan reads pack.mcmeta even for a code-only mod. Fabric
and NeoForge synthesize metadata for ordinary mod resources, so those profiles
intentionally omit it. See docs/resource-pack-metadata.md for primary sources.
"""
import json
import re
import tomllib
import zipfile


MOD_ID = 'mgfsr3bridge'
ENTRYPOINT = 'com.googlebook.fsrbridge.LoaderEntrypoint'
MIXIN_CONFIG = 'mixins.mgfsrbridge.json'
MIXIN_CLASSES = {
    'FinalPassMixin', 'PipelineMixin', 'ViewportMixin', 'HandRendererTraceMixin',
    'ShadowRendererTraceMixin', 'CompositeRendererTraceMixin', 'RenderTargetsTraceMixin',
}
LOADER_FILES = {'fabric.mod.json', 'META-INF/mods.toml', 'META-INF/neoforge.mods.toml'}


def forge_pack_metadata(profile):
    """Return the Forge MDK format for this target; other loaders use defaults."""
    if profile['loader'] != 'forge':
        return None
    pack = {'description': 'MobileGlues FSR3 Bridge resources'}
    if profile['minecraft'] == '1.20.1':
        pack['pack_format'] = 15
    elif profile['minecraft'] == '1.18.2':
        pack.update({'pack_format': 9, 'forge:resource_pack_format': 8,
                     'forge:data_pack_format': 9})
    else:
        raise ValueError('No reviewed Forge resource-pack format: ' + profile['id'])
    return {'pack': pack}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_json(jar, name):
    require(name in jar.namelist(), 'Missing packaged metadata: ' + name)
    def unique_object(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, 'Duplicate JSON field in ' + name + ': ' + key)
            result[key] = value
        return result

    def reject_constant(value):
        raise ValueError('Invalid JSON constant in ' + name + ': ' + value)

    value = json.loads(jar.read(name).decode('utf-8'), object_pairs_hook=unique_object,
                       parse_constant=reject_constant)
    require(isinstance(value, dict), 'Metadata must be an object: ' + name)
    return value


def read_manifest(jar):
    """Read this generator's single-section, unfolded manifest unambiguously."""
    name = 'META-INF/MANIFEST.MF'
    require(name in jar.namelist(), 'Missing JAR manifest')
    fields = {}
    ended = False
    # str.splitlines also accepts Unicode separators that the JAR parser does
    # not treat as newlines. Only CR/LF delimit manifest attributes.
    lines = re.split(r'\r\n|\r|\n', jar.read(name).decode('utf-8'))
    require(lines[-1] == '', 'Unterminated manifest line')
    for line in lines[:-1]:
        if not line:
            ended = True
            continue
        require(not ended, 'Unexpected manifest section after main attributes')
        require(len(line.encode('utf-8')) <= 72, 'Generated manifest field needs folding')
        key, separator, value = line.partition(': ')
        require(separator and re.fullmatch(r'[A-Za-z0-9_-]{1,70}', key), 'Malformed manifest field')
        key = key.lower()
        require(key not in fields, 'Duplicate manifest field')
        require(fields or key == 'manifest-version', 'Manifest-Version must be first')
        fields[key] = value
    require(ended, 'Missing manifest main-section terminator')
    return fields


def verify_package_metadata(profile, jar, version):
    """Check the actual archive, without importing game or loader classes."""
    names = jar.namelist()
    require(len(names) == len(set(names)), 'Duplicate JAR entries are ambiguous')
    expected_file = {'fabric': 'fabric.mod.json', 'forge': 'META-INF/mods.toml',
                     'neoforge': 'META-INF/neoforge.mods.toml'}[profile['loader']]
    require(LOADER_FILES.intersection(names) == {expected_file}, 'Wrong loader metadata files')
    require(ENTRYPOINT.replace('.', '/') + '.class' in names, 'Missing loader entrypoint class')
    manifest = read_manifest(jar)
    require(manifest.get('manifest-version') == '1.0', 'Invalid manifest version')
    require(manifest.get('implementation-version') == version, 'Manifest version mismatch')
    require(manifest.get('mixinconfigs') == MIXIN_CONFIG, 'Missing manifest Mixin registration')

    mixins = read_json(jar, MIXIN_CONFIG)
    require(mixins.get('required') is True and mixins.get('minVersion') == '0.8', 'Mixin requirements changed')
    require(mixins.get('compatibilityLevel') == 'JAVA_17', 'Wrong Mixin Java compatibility')
    require(mixins.get('package') == 'com.googlebook.fsrbridge.mixin', 'Wrong Mixin package')
    clients = mixins.get('client')
    require(isinstance(clients, list) and all(isinstance(item, str) for item in clients), 'Invalid client Mixins')
    require(len(clients) == len(MIXIN_CLASSES) and set(clients) == MIXIN_CLASSES, 'Missing or duplicate client Mixin')
    require(mixins.get('mixins', []) == [] and mixins.get('server', []) == [],
            'Renderer Mixins must remain client-only')
    require(mixins.get('injectors') == {'defaultRequire': 1}, 'Required injection checks disabled')
    require(type(mixins['injectors']['defaultRequire']) is int, 'Invalid defaultRequire type')
    for name in clients:
        require('com/googlebook/fsrbridge/mixin/' + name + '.class' in names, 'Missing Mixin class: ' + name)

    if profile['loader'] == 'fabric':
        metadata = read_json(jar, expected_file)
        require(type(metadata.get('schemaVersion')) is int and metadata['schemaVersion'] == 1
                and metadata.get('id') == MOD_ID, 'Invalid Fabric mod identity')
        require(metadata.get('version') == version, 'Fabric mod version mismatch')
        require(metadata.get('environment') == 'client', 'Fabric environment must be client')
        require(metadata.get('entrypoints') == {'client': [ENTRYPOINT]}, 'Invalid Fabric entrypoints')
        require(metadata.get('mixins') == [MIXIN_CONFIG], 'Invalid Fabric Mixin registration')
        expected = {'fabricloader': '>=0.19.3', 'java': '>=' + str(profile['java']),
                    'minecraft': profile['minecraft'], profile['shader_mod']: '=' + profile['mod_version']}
        require(metadata.get('depends') == expected, 'Fabric dependency lock mismatch')
    else:
        metadata = tomllib.loads(jar.read(expected_file).decode('utf-8'))
        require(metadata.get('modLoader') == 'javafml', 'Invalid FML language loader')
        neo = profile['loader'] == 'neoforge'
        expected_loader = '[4,)' if neo else {'1.18.2': '[40,41)', '1.20.1': '[47,48)'}[profile['minecraft']]
        require(metadata.get('loaderVersion') == expected_loader, 'FML loader range mismatch')
        mods = metadata.get('mods')
        require(isinstance(mods, list) and len(mods) == 1 and isinstance(mods[0], dict), 'Expected one FML mod')
        require(mods[0].get('modId') == MOD_ID and mods[0].get('version') == version, 'FML mod identity mismatch')
        if neo:
            require(metadata.get('mixins') == [{'config': MIXIN_CONFIG}], 'Invalid NeoForge Mixin registration')
        dependency_table = metadata.get('dependencies', {})
        require(isinstance(dependency_table, dict), 'Invalid FML dependencies')
        dependencies = dependency_table.get(MOD_ID)
        require(isinstance(dependencies, list) and len(dependencies) == 2,
                'Expected Minecraft and shader-mod dependencies')
        expected = {'minecraft': profile['minecraft'], profile['shader_mod']: profile['mod_version']}
        seen = set()
        for dependency in dependencies:
            require(isinstance(dependency, dict), 'Invalid FML dependency')
            name = dependency.get('modId')
            require(isinstance(name, str) and name in expected and name not in seen, 'Wrong or duplicate FML dependency')
            seen.add(name)
            require(dependency.get('versionRange') == '[' + expected[name] + ']', 'FML dependency version mismatch')
            required = dependency.get('type') == 'required' if neo else dependency.get('mandatory') is True
            require(required and dependency.get('side') == 'CLIENT' and dependency.get('ordering') == 'AFTER',
                    'FML dependency must be required on the client with AFTER ordering')
    require(metadata.get('license') == 'LGPL-2.1-only', 'Missing or wrong license')

    expected_pack = forge_pack_metadata(profile)
    if expected_pack is not None:
        pack = read_json(jar, 'pack.mcmeta').get('pack')
        require(isinstance(pack, dict), 'Missing pack section')
        require(isinstance(pack.get('description'), str) and bool(pack['description'].strip()), 'Missing pack description')
        for key, value in expected_pack['pack'].items():
            if key != 'description':
                require(type(pack.get(key)) is int and pack[key] == value, 'Wrong target pack metadata: ' + key)
    else:
        require('pack.mcmeta' not in names, 'Code-only Fabric/NeoForge profiles use synthetic pack metadata')
        require(not any(name.startswith(('assets/', 'data/')) for name in names),
                'New resource content needs a reviewed pack metadata policy')
    return {'loader_metadata': expected_file, 'mixins': len(clients),
            'resource_pack': expected_pack if expected_pack else 'loader_synthetic_code_only'}


def verify_artifact(profile, artifact, version):
    with zipfile.ZipFile(artifact) as jar:
        return verify_package_metadata(profile, jar, version)
