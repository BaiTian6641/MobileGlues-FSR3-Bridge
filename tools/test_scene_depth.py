"""Execute pinned Iris depth-cache methods; no Minecraft rendering or GPU is simulated."""
import copy
import json
import os
import subprocess

from build import ROOT, fetch, verify_hooks


def check_scene_depth(profile, javac, java):
    if profile['depth_resource'] != 'gpu_texture':
        check_legacy_scene_depth(profile, javac, java)
        return
    folder = ROOT / 'build/tests/depth' / profile['id']
    folder.mkdir(parents=True, exist_ok=True)
    classes = ROOT / 'build' / profile['id'] / 'classes'
    # The viewport test already copied the artifact's unmodified class bytes,
    # removing only its manifest Class-Path. Minecraft bytes remain pinned too.
    api = ROOT / 'build/tests/viewport' / profile['id'] / 'shader-runtime.jar'
    classpath = os.pathsep.join(map(str, [classes, api, ROOT / '.cache/minecraft-26.2.jar',
                                        ROOT / '.cache/test-fastutil.jar', ROOT / '.cache/test-guava.jar',
                                        ROOT / '.cache/mixin.jar']))
    subprocess.run([str(javac), '--release', '17', '-Xlint:all', '-Werror', '-proc:none',
                    '-cp', classpath, '-d', str(folder),
                    str(ROOT / 'tests/depth/SceneDepthContractTest.java')], check=True)
    result = subprocess.run([str(java), '-cp', os.pathsep.join([str(folder), classpath]),
                             'com.googlebook.fsrbridge.SceneDepthContractTest'], check=True,
                            text=True, stdout=subprocess.PIPE)
    for line in result.stdout.splitlines():
        if line.startswith('DEPTH_JSON '):
            metadata = json.loads(line.removeprefix('DEPTH_JSON '))
            assert metadata['source_depth_texture'] == 73 and metadata['noHand_texture'] == 73
        else:
            print(line)
    altered = copy.deepcopy(profile)
    altered['depth_contract']['resize_code_sha256'] = '0' * 64
    try:
        verify_hooks(altered, ROOT / '.cache' / (profile['id'] + '.jar'))
    except ValueError:
        return
    raise AssertionError('Iris depth generation bytecode drift accepted')


def check_legacy_scene_depth(profile, javac, java):
    folder = ROOT / 'build/tests/depth' / profile['id']
    folder.mkdir(parents=True, exist_ok=True)
    replacements = {
        'PROFILE': profile['id'], 'NAMESPACE': profile['namespace'],
        'DEPTH_PACKAGE': profile['classes']['DepthTexture'].rsplit('.', 1)[0],
        'RENDER_TARGETS': profile['classes']['RenderTargets'],
        'DEPTH_TEXTURE': profile['classes']['DepthTexture'],
        'GL_FRAMEBUFFER': profile['classes']['GlFramebuffer'],
        'PROGRAM_SET': profile['classes']['ProgramSet'],
        'PROGRAM_ID': profile['classes']['ProgramId'],
        'PROGRAM_SOURCE': profile['classes']['ProgramSet'].rsplit('.', 1)[0] + '.ProgramSource',
        'PROGRAM_FIXTURE': (
            '        var programsById = new java.util.EnumMap<ProgramId, ProgramSource>(ProgramId.class);\n'
            '        programsById.put(ProgramId.Final, source);\n'
            '        set(programs, ProgramSet.class, "gbufferPrograms", programsById);'
            if profile['minecraft'] == '1.21.1' or profile['id'] == '1.20.1-fabric' else
            '        set(programs, ProgramSet.class, "compositeFinal", source);'),
    }
    sources = []
    for template, name in [('LegacyDepthTexture', 'DepthTexture'),
                           ('LegacySceneDepthContractTest', 'LegacySceneDepthContractTest')]:
        source = (ROOT / 'tests/templates' / (template + '.java.in')).read_text()
        for key, value in replacements.items():
            source = source.replace('@' + key + '@', value)
        destination = folder / (name + '.java')
        destination.write_text(source)
        sources.append(destination)
    classes = ROOT / 'build' / profile['id'] / 'classes'
    api = ROOT / 'build/tests/viewport' / profile['id'] / 'shader-runtime.jar'
    dependencies = [fetch(name, spec) for name, spec in
                    json.loads((ROOT / 'test-dependencies.json').read_text()).items()]
    classpath = os.pathsep.join(map(str, [classes, api, *dependencies, ROOT / '.cache/mixin.jar',
                                        ROOT / 'build/tests']))
    subprocess.run([str(javac), '--release', '17', '-Xlint:all', '-Werror', '-proc:none',
                    '-cp', classpath, '-d', str(folder), *map(str, sources),
                    str(ROOT / 'tests/depth/GL.java')], check=True)
    includes = ['-I' + str(javac.parent.parent / 'include'), '-I' + str(javac.parent.parent / 'include/linux')]
    common = ['c++', '-std=c++17', '-O2', '-fPIC', '-shared', '-Wall', '-Wextra', '-Werror', *includes]
    subprocess.run([*common, str(ROOT / 'src/main/cpp/mgfsrhelper.cpp'), '-ldl',
                    '-o', str(folder / 'helper.so')], check=True)
    subprocess.run([*common, str(ROOT / 'tests/legacy_depth_fixture.cpp'),
                    '-o', str(folder / 'scale.so')], check=True)
    subprocess.run([str(java), '--enable-native-access=ALL-UNNAMED',
                    '-cp', os.pathsep.join([str(folder), classpath]),
                    'com.googlebook.fsrbridge.LegacySceneDepthContractTest',
                    str(folder / 'helper.so'), str(folder / 'scale.so')], check=True)
    for key in ('constructor_code_sha256', 'resize_code_sha256', 'fields'):
        altered = copy.deepcopy(profile)
        altered['depth_contract'][key] = {'currentDepthTexture': 'J'} if key == 'fields' else '0' * 64
        try:
            verify_hooks(altered, ROOT / '.cache' / (profile['id'] + '.jar'))
        except ValueError:
            continue
        raise AssertionError('legacy depth contract drift accepted: ' + key)
