"""Run Java/JNI diagnostics through a host transport fixture and parse every JSON row."""
import copy
import json
import os
import shutil
import subprocess

from build import ROOT, verify_hooks


def check_diagnostics(profile, javac, java, folder, artifact):
    hook_folder = folder / 'diagnostic-hooks' / profile['id']
    hook_folder.mkdir(parents=True, exist_ok=True)
    source = (ROOT / 'tests/templates/DiagnosticPipelineHook.java.in').read_text()
    source = source.replace('@PHASE_CLASS@', profile['classes']['WorldRenderingPhase'])
    hook_source = hook_folder / 'DiagnosticPipelineHook.java'
    hook_source.write_text(source)
    shader_api = folder / 'viewport' / profile['id'] / 'shader-runtime.jar'
    classpath = os.pathsep.join(map(str, [folder, hook_folder, artifact, shader_api, ROOT / '.cache/mixin.jar']))
    subprocess.run([str(javac), '--release', '17', '-Xlint:all', '-Werror', '-proc:none',
                    '-cp', classpath, '-d', str(hook_folder), str(hook_source)], check=True)
    for mode in ('frames', 'events'):
        output = folder / 'diagnostics' / profile['id'] / mode
        if output.exists():
            shutil.rmtree(output)
        result = subprocess.run([str(java), '--enable-native-access=ALL-UNNAMED', '-cp',
                                 classpath,
                                 'com.googlebook.fsrbridge.DiagnosticFlowTest', str(folder / 'helper.so'),
                                 str(folder / 'diagnostic.so'), str(output), mode],
                                text=True, stdout=subprocess.PIPE, check=True)
        rows = [json.loads(line) for line in (output / 'mgfsr3-trace.jsonl').read_text().splitlines()]
        manifest, *events = rows
        bounds = manifest['bounds']
        assert bounds['frames'] <= 120 and bounds['events'] <= 3000
        assert bounds['events'] == len(events)
        if mode == 'events':
            assert bounds['overflow'] and bounds['overflow_reason'] == 'event_limit'
            assert len(events) == 3000
        else:
            assert bounds['frames'] == 120 and not bounds['overflow']
            for name in ('TERRAIN_SOLID', 'TERRAIN_CUTOUT_MIPPED', 'TERRAIN_CUTOUT', 'TERRAIN_TRANSLUCENT'):
                assert any(e['role'] == 'PHASE_' + name and e['frame'] == 119 for e in events)
        assert 'Pipeline.setPhase' in manifest['hooks']
        submissions = [e for e in events if e['event'] == 'SUBMIT_RESULT']
        assert all(e['gl'] is None for e in submissions)
        outcomes = [e['iris'] for e in submissions]
        first = outcomes[0]
        if profile['depth_resource'] == 'gpu_texture':
            assert not first['attempted'] and first['native_rc'] is None and first['stage'] == 'target_bind'
        else:
            assert first['attempted'] and first['native_rc'] == -23 and first['stage'] == 'complete'
            assert outcomes[1]['native_rc'] == 0 and outcomes[1]['stage'] == 'complete'
            assert outcomes[2]['attempted'] and outcomes[2]['native_rc'] is None
            assert outcomes[2]['exception_class'] == 'java.lang.IllegalStateException'
        verified = next(e for e in outcomes if e['main_target_verified'])
        assert (verified['main_color_texture'], verified['main_width'], verified['main_height']) == (99, 2880, 1585)
        restore = next(e for e in outcomes if e['stage'] == 'target_restore')
        assert restore['attempted'] and restore['native_rc'] == 0 and restore['exception_class']
        assert any(e['stage'] == 'unavailable' and not e['depth_convention_negotiated'] for e in outcomes)
        assert all(e['sampled_depth_reversed'] == (profile['depth_convention'] == 1) for e in outcomes)
        assert 'private/path' not in json.dumps(rows)
        print('PASS parsed diagnostic JSON, terrain roles, submission outcomes and', mode, 'budget:', profile['id'])
    for field, value in [('set_phase_code_sha256', '0' * 64), ('phase_constants', ['NONE'])]:
        changed = copy.deepcopy(profile)
        changed['diagnostic_contract'][field] = value
        try:
            verify_hooks(changed, ROOT / '.cache' / (profile['id'] + '.jar'))
        except ValueError:
            continue
        raise AssertionError('Diagnostic hook drift accepted: ' + field)
