"""Exercise actual pinned expression/cached-uniform classes without loading Minecraft."""
import copy
import json
import os
import subprocess
import zipfile

from build import ROOT, fetch, verify_hooks


def check_viewport_uniforms(profile, javac, java):
    folder = ROOT / 'build/tests/viewport' / profile['id']
    folder.mkdir(parents=True, exist_ok=True)
    source_jar = ROOT / '.cache' / (profile['id'] + '.jar')
    # Drop only the stale manifest Class-Path. All executed classes remain the
    # locked artifact's actual bytes; no shader or parser fixture is substituted.
    api = folder / 'shader-runtime.jar'
    with zipfile.ZipFile(source_jar) as original, zipfile.ZipFile(api, 'w') as output:
        for member in original.namelist():
            if member.endswith('.class'):
                output.writestr(member, original.read(member))
    source = (ROOT / 'tests/templates/ViewportUniformContractTest.java.in').read_text()
    source = source.replace('@NAMESPACE@', profile['namespace']).replace('@PROFILE@', profile['id'])
    java_source = folder / 'ViewportUniformContractTest.java'
    java_source.write_text(source)
    classes = ROOT / 'build' / profile['id'] / 'classes'
    dependencies = [fetch(name, spec) for name, spec in
                    json.loads((ROOT / 'test-dependencies.json').read_text()).items()]
    classpath = os.pathsep.join(map(str, [classes, api, *dependencies]))
    subprocess.run([str(javac), '--release', '17', '-Xlint:all', '-Werror', '-proc:none',
                    '-cp', classpath, '-d', str(folder), str(java_source)], check=True)
    subprocess.run([str(java), '-cp', os.pathsep.join([str(folder), classpath]),
                    'com.googlebook.fsrbridge.ViewportUniformContractTest'], check=True)
    for change in ('field', 'expression'):
        altered = copy.deepcopy(profile)
        if change == 'field':
            altered['viewport_fields']['FinalPassRenderer']['customUniforms'] = 'Ljava/lang/Object;'
        else:
            altered['viewport_expression_methods']['ExpressionFunctionCall']['missingAccessor'] = '()V'
        try:
            verify_hooks(altered, source_jar)
        except ValueError:
            continue
        raise AssertionError('viewport API drift accepted: ' + change)
