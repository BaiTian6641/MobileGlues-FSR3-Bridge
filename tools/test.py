#!/usr/bin/env python3
"""Run host contracts and package checks; these do not launch Minecraft or qualify a GPU."""
import argparse
import ast
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

from build import ROOT, VERSION, verify_hooks
from package_metadata import MIXIN_CONFIG, verify_package_metadata
from test_viewport_uniforms import check_viewport_uniforms
from test_scene_depth import check_scene_depth
from test_diagnostics import check_diagnostics


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True, cwd=ROOT)


def reject_metadata_mutation(profile, entries, replacements, label):
    """Corrupt an in-memory copy of a built JAR; the actual artifact stays intact."""
    data = io.BytesIO()
    with zipfile.ZipFile(data, 'w') as jar:
        for name, content in entries.items():
            content = replacements.get(name, content)
            if content is not None:
                jar.writestr(name, content)
    data.seek(0)
    with zipfile.ZipFile(data) as jar:
        try:
            verify_package_metadata(profile, jar, VERSION)
        except ValueError:
            return
    raise AssertionError('Metadata regression not rejected: ' + profile['id'] + ': ' + label)


def check_metadata_regressions(profile, jar):
    verified = verify_package_metadata(profile, jar, VERSION)
    entries = {name: jar.read(name) for name in jar.namelist()}
    loader_file = verified['loader_metadata']
    reject_metadata_mutation(profile, entries, {loader_file: None}, 'missing loader descriptor')
    reject_metadata_mutation(profile, entries, {loader_file: b'{invalid'}, 'malformed loader descriptor')
    broken_mixins = json.loads(entries[MIXIN_CONFIG])
    broken_mixins['required'] = False
    reject_metadata_mutation(profile, entries, {MIXIN_CONFIG: json.dumps(broken_mixins)}, 'optional required Mixins')
    reject_metadata_mutation(profile, entries,
                             {'com/googlebook/fsrbridge/mixin/FinalPassMixin.class': None}, 'missing Mixin class')
    cases = 4
    for name in ('CustomUniformsMixin', 'CustomUniformDefinitionAccessor'):
        altered = json.loads(entries[MIXIN_CONFIG])
        altered['client'].remove(name)
        reject_metadata_mutation(profile, entries, {MIXIN_CONFIG: json.dumps(altered)},
                                 'unregistered viewport Mixin: ' + name)
        reject_metadata_mutation(profile, entries,
                                 {'com/googlebook/fsrbridge/mixin/' + name + '.class': None},
                                 'missing viewport Mixin class: ' + name)
        cases += 2
    mixin_text = entries[MIXIN_CONFIG].decode('utf-8')
    for text, label in [('{"required":false,' + mixin_text[1:], 'duplicate JSON field'),
                        ('{"unused":NaN,' + mixin_text[1:], 'nonstandard JSON constant')]:
        reject_metadata_mutation(profile, entries, {MIXIN_CONFIG: text}, label)
        cases += 1
    for key, value in [('injectors', {'defaultRequire': True}), ('server', False)]:
        altered = json.loads(entries[MIXIN_CONFIG])
        altered[key] = value
        reject_metadata_mutation(profile, entries, {MIXIN_CONFIG: json.dumps(altered)},
                                 'invalid Mixin metadata type: ' + key)
        cases += 1
    manifest_file = 'META-INF/MANIFEST.MF'
    manifest = entries[manifest_file].decode('utf-8')
    for text, label in [
            (None, 'missing manifest'),
            (manifest.replace('MixinConfigs:', '\nMixinConfigs:'), 'Mixin field outside main section'),
            (manifest.replace('\n\n', '\nmixinconfigs: ignored.json\n\n'), 'case-insensitive duplicate field'),
            (manifest.replace('\n', '\u0085', 1), 'non-JAR Unicode line separator'),
            (manifest.rstrip('\n') + '\n', 'unterminated manifest section'),
            (manifest.replace('MixinConfigs:', 'Mixin Configs:'), 'invalid manifest field name')]:
        reject_metadata_mutation(profile, entries, {manifest_file: text}, label)
        cases += 1
    if profile['loader'] == 'fabric':
        metadata = json.loads(entries[loader_file])
        metadata['depends'][profile['shader_mod']] = '=0.0.0'
        wrong_dependency = json.dumps(metadata)
        altered = json.loads(entries[loader_file])
        altered['schemaVersion'] = True
        reject_metadata_mutation(profile, entries, {loader_file: json.dumps(altered)}, 'Boolean Fabric schemaVersion')
        reject_metadata_mutation(profile, entries,
                                 {loader_file: '{"id":"wrong",' + entries[loader_file].decode()[1:]},
                                 'duplicate Fabric identity')
        cases += 2
    else:
        original = 'versionRange="[' + profile['mod_version'] + ']"'
        require_count = entries[loader_file].decode().count(original)
        if require_count != 1:
            raise AssertionError('Expected one pinned shader dependency')
        wrong_dependency = entries[loader_file].decode().replace(original, 'versionRange="[0.0.0]"')
        reject_metadata_mutation(profile, entries,
                                 {loader_file: 'modLoader="wrong"\n' + entries[loader_file].decode()},
                                 'duplicate TOML field')
        cases += 1
    reject_metadata_mutation(profile, entries, {loader_file: wrong_dependency}, 'wrong shader dependency')
    cases += 1
    if profile['loader'] == 'forge':
        reject_metadata_mutation(profile, entries, {'pack.mcmeta': None}, 'missing root pack.mcmeta')
        reject_metadata_mutation(profile, entries, {'pack.mcmeta': b'{invalid'}, 'malformed pack.mcmeta')
        reject_metadata_mutation(profile, entries, {'pack.mcmeta': b'{}'}, 'missing pack section')
        pack = json.loads(entries['pack.mcmeta'])
        # Independent MDK expectations prevent generation and validation from
        # agreeing on the same incorrect format after a future refactor.
        expected_formats = {'pack_format': 15} if profile['minecraft'] == '1.20.1' else {
            'pack_format': 9, 'forge:resource_pack_format': 8, 'forge:data_pack_format': 9,
        }
        for key, expected in expected_formats.items():
            if type(pack['pack'].get(key)) is not int or pack['pack'][key] != expected:
                raise AssertionError('Forge MDK format mismatch: ' + profile['id'] + ': ' + key)
        for key, value in [('description', ''), ('pack_format', -1), ('pack_format', '15'), ('pack_format', True)]:
            altered = copy.deepcopy(pack)
            altered['pack'][key] = value
            reject_metadata_mutation(profile, entries, {'pack.mcmeta': json.dumps(altered)}, 'invalid ' + key)
        reject_metadata_mutation(profile, entries,
                                 {'pack.mcmeta': '{"pack":{},' + entries['pack.mcmeta'].decode()[1:]},
                                 'duplicate pack section')
        cases += 8
        if profile['minecraft'] == '1.18.2':
            for key in ('forge:resource_pack_format', 'forge:data_pack_format'):
                altered = copy.deepcopy(pack)
                altered['pack'][key] = 15
                reject_metadata_mutation(profile, entries, {'pack.mcmeta': json.dumps(altered)}, 'wrong ' + key)
                cases += 1
    print('PASS packaged metadata and', cases, 'negative cases:', profile['id'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profile', default='all')
    args = parser.parse_args()
    profiles = json.loads((ROOT / 'compatibility-lock.json').read_text())['profiles']
    profiles = [p for p in profiles if args.profile in ('all', p['id'])]
    if not profiles:
        raise ValueError('unknown profile')
    folder = ROOT / 'build/tests'
    folder.mkdir(parents=True, exist_ok=True)
    javac = Path(shutil.which(os.environ.get('JAVAC', 'javac'))).resolve()
    java = javac.parent / 'java'
    baseline = ROOT / 'build' / profiles[0]['id'] / 'classes'
    run(javac, '--release', '17', '-Xlint:all', '-Werror', '-cp', baseline,
        '-d', folder, *sorted((ROOT / 'tests/java').rglob('*.java')))
    for profile in profiles:
        artifact = ROOT / 'dist' / ('mgfsr3bridge-' + VERSION + '-' + profile['id'] + '.jar')
        with zipfile.ZipFile(artifact) as jar:
            check_metadata_regressions(profile, jar)
            names = jar.namelist()
            if any(name.startswith(('net/minecraft/', 'net/irisshaders/', 'net/coderbot/',
                                    'org/lwjgl/', 'kroppeb/', 'it/unimi/dsi/')) for name in names):
                raise AssertionError('dependency or test fixture leaked into ' + artifact.name)
            for name in names:
                if name.endswith('.class') and int.from_bytes(jar.read(name)[6:8], 'big') != 61:
                    raise AssertionError('common code must remain Java 17 bytecode')
            for abi in ['arm64-v8a', 'x86_64']:
                library = jar.read('native/' + abi + '/libmgfsrhelper.so')
                manifest = jar.read('native/checksums.properties').decode()
                if abi + '=' + hashlib.sha256(library).hexdigest() not in manifest:
                    raise AssertionError('native checksum mismatch')
        run(java, '-cp', os.pathsep.join(map(str, [folder, artifact])), 'com.googlebook.fsrbridge.BridgeContractTest')
        run(java, '-cp', os.pathsep.join(map(str, [folder, artifact])), 'com.googlebook.fsrbridge.FinalViewportScopeTest')
        run(java, '-cp', os.pathsep.join(map(str, [folder, artifact])), 'com.googlebook.fsrbridge.SubmissionTargetTest')
        run(java, '-cp', os.pathsep.join(map(str, [folder, artifact])), 'com.googlebook.fsrbridge.DiagnosticMetadataTest')
        check_viewport_uniforms(profile, javac, java)
        check_scene_depth(profile, javac, java)
        altered = copy.deepcopy(profile)
        altered['methods']['FinalPassRenderer']['renderFinalPass'] = ['(I)V']
        try:
            verify_hooks(altered, ROOT / '.cache' / (profile['id'] + '.jar'))
        except ValueError:
            pass
        else:
            raise AssertionError('ABI drift must fail the build')
    # Exercise real dlopen(RTLD_NOLOAD) behavior on the host instead of mocking it.
    includes = ['-I' + str(javac.parent.parent / 'include'), '-I' + str(javac.parent.parent / 'include/linux')]
    common = ['c++', '-std=c++17', '-O2', '-fPIC', '-shared', '-Wall', '-Wextra', '-Werror', *includes]
    run(*common, ROOT / 'src/main/cpp/mgfsrhelper.cpp', '-ldl', '-o', folder / 'helper.so')
    run(*common, '-DINCOMPLETE_ABI', ROOT / 'tests/core_fixture.cpp', '-o', folder / 'incomplete.so')
    run(*common, ROOT / 'tests/core_fixture.cpp', '-o', folder / 'complete.so')
    run(*common, '-DDEPTH_CONVENTION', ROOT / 'tests/core_fixture.cpp', '-o', folder / 'depth-aware.so')
    run(java, '--enable-native-access=ALL-UNNAMED', '-cp', os.pathsep.join(map(str, [folder, baseline])), 'com.googlebook.fsrbridge.HelperContractTest',
        folder / 'helper.so', folder / 'incomplete.so', folder / 'complete.so')
    run(java, '--enable-native-access=ALL-UNNAMED', '-cp', os.pathsep.join(map(str, [folder, baseline])),
        'com.googlebook.fsrbridge.DepthConventionContractTest', folder / 'helper.so', folder / 'depth-aware.so')
    run(*common, '-DDEPTH_CONVENTION', ROOT / 'tests/diagnostic_fixture.cpp', '-o', folder / 'diagnostic.so')
    for profile in profiles:
        artifact = ROOT / 'dist' / ('mgfsr3bridge-' + VERSION + '-' + profile['id'] + '.jar')
        check_diagnostics(profile, javac, java, folder, artifact)
    for source in (ROOT / 'tools').glob('*.py'):
        ast.parse(source.read_text(), filename=str(source))
    print('PASS Python AST and package isolation checks; game/device tests NOT RUN')


if __name__ == '__main__':
    main()
