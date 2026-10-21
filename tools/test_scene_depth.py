"""Execute pinned Iris depth-cache methods; no Minecraft rendering or GPU is simulated."""
import copy
import json
import os
import subprocess

from build import ROOT, verify_hooks


def check_scene_depth(profile, javac, java):
    if profile['depth_resource'] != 'gpu_texture':
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
