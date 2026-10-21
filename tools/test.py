#!/usr/bin/env python3
"""Run host contracts and package checks; these do not launch Minecraft or qualify a GPU."""
import argparse
import ast
import copy
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

from build import ROOT, verify_hooks


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True, cwd=ROOT)


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
        artifact = ROOT / 'dist' / ('mgfsr3bridge-0.2.0-' + profile['id'] + '.jar')
        with zipfile.ZipFile(artifact) as jar:
            names = jar.namelist()
            if any(name.startswith(('net/minecraft/', 'net/irisshaders/', 'net/coderbot/', 'org/lwjgl/')) for name in names):
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
    run(java, '--enable-native-access=ALL-UNNAMED', '-cp', os.pathsep.join(map(str, [folder, baseline])), 'com.googlebook.fsrbridge.HelperContractTest',
        folder / 'helper.so', folder / 'incomplete.so', folder / 'complete.so')
    for source in (ROOT / 'tools').glob('*.py'):
        ast.parse(source.read_text(), filename=str(source))
    print('PASS Python AST and package isolation checks; game/device tests NOT RUN')


if __name__ == '__main__':
    main()
