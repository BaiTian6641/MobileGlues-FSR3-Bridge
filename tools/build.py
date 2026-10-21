#!/usr/bin/env python3
"""Reproducible adapter build using pinned release JARs, javac, and Android NDK r28c.

No Minecraft class is transformed or referenced by an injection selector. Iris/Oculus
classes keep their release names; therefore an obfuscation refmap is neither needed
nor generated. Reflection-only Minecraft fields are locked separately per loader.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import urllib.request
import zipfile

from classfile import methods
from package_metadata import forge_pack_metadata, verify_artifact

ROOT = Path(__file__).resolve().parents[1]
VERSION = '0.2.2'
NDK_VERSION = '28.2.13676358'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True, cwd=ROOT)


def fetch(name, spec):
    destination = ROOT / '.cache' / (name + '.jar')
    destination.parent.mkdir(exist_ok=True)
    if destination.is_file() and sha(destination.read_bytes()) == spec['sha256']:
        return destination
    if not spec['url'].startswith('https://'):
        raise ValueError('dependency URLs must use HTTPS')
    request = urllib.request.Request(spec['url'], headers={'User-Agent': 'Mozilla/5.0 MobileGlues-FSR3-Bridge/0.2'})
    with urllib.request.urlopen(request, timeout=90) as response:
        data = response.read(64 * 1024 * 1024 + 1)
    if len(data) > 64 * 1024 * 1024 or sha(data) != spec['sha256']:
        raise ValueError('dependency size/hash mismatch: ' + name)
    destination.write_bytes(data)
    return destination


def template(name, replacements):
    result = (ROOT / 'templates' / (name + '.java.in')).read_text()
    for key, value in replacements.items():
        result = result.replace('@' + key + '@', value)
    if re.search(r'@[A-Z_]+@', result):
        raise ValueError('unresolved template placeholder in ' + name)
    return result


def descriptor(profile, owner, name):
    values = profile['methods'][owner][name]
    if len(values) != 1:
        raise ValueError('ambiguous hook ' + owner + '.' + name)
    return values[0]


def verify_hooks(profile, artifact):
    """Check actual class structure, descriptors, and all allocation call references."""
    required = {
        'FinalPassRenderer': ['renderFinalPass'],
        'Pipeline': ['<init>', 'destroy', 'beginLevelRendering'],
        'ViewportUniforms': ['lambda$addViewportUniforms$0', 'lambda$addViewportUniforms$1'],
        'RenderTargets': ['<init>', 'resizeIfNeeded', 'copyPreHandDepth', 'copyPreTranslucentDepth'],
        'ProgramSet': ['get'], 'DepthTexture': ['<init>', 'getTextureId'],
        'CapturedRenderingState': ['getGbufferModelView', 'getGbufferProjection'],
        'CameraUniforms': ['getUnshiftedCameraPosition'],
        'HandRenderer': ['renderSolid', 'renderTranslucent'],
        'ShadowRenderer': ['renderShadows'], 'CompositeRenderer': ['renderAll'],
        'CustomUniforms': ['<init>', 'optimise'],
        'CachedUniform': ['getName', 'update'],
        'GlFramebuffer': ['getId'],
    }
    evidence = []
    with zipfile.ZipFile(artifact) as jar:
        for owner, names in required.items():
            class_name = profile['classes'][owner].replace('.', '/')
            _, entries, _ = methods(jar.read(class_name + '.class'))
            actual = {(name, desc) for name, desc, _, _ in entries}
            for name in names:
                desc = descriptor(profile, owner, name)
                if (name, desc) not in actual:
                    raise ValueError('missing exact hook: ' + class_name + '.' + name + desc)
                evidence.append(class_name + '.' + name + desc)
        for owner, names in profile['viewport_expression_methods'].items():
            class_name = profile['classes'][owner].replace('.', '/')
            _, entries, _ = methods(jar.read(class_name + '.class'))
            actual = {(name, desc) for name, desc, _, _ in entries}
            for name, desc in names.items():
                if (name, desc) not in actual:
                    raise ValueError('missing expression API: ' + class_name + '.' + name + desc)
                evidence.append(class_name + '.' + name + desc)
        for owner, names in profile['viewport_fields'].items():
            class_name = profile['classes'][owner].replace('.', '/')
            _, _, _, fields = methods(jar.read(class_name + '.class'), include_fields=True)
            actual = {(name, desc) for name, desc, access, _ in fields if access & 0x10}
            for name, desc in names.items():
                if (name, desc) not in actual:
                    raise ValueError('missing final field: ' + class_name + '.' + name + ':' + desc)
                evidence.append(class_name + '.' + name + ':' + desc)
        if profile['scene_scaling']:
            pipeline = profile['classes']['Pipeline'].replace('.', '/')
            _, pipeline_methods, _ = methods(jar.read(pipeline + '.class'))
            invocations = {name: attrs.get('_invocations', []) for name, _, _, attrs in pipeline_methods}
            target = profile['classes']['RenderTargets'].replace('.', '/')
            for method, name in [('<init>', '<init>'), ('beginLevelRendering', 'resizeIfNeeded')]:
                if (target, name, descriptor(profile, 'RenderTargets', name)) not in invocations[method]:
                    raise ValueError('missing scene allocation call ' + name)
        if profile['depth_resource'] == 'gpu_texture':
            _, target_methods, _ = methods(jar.read(target + '.class'))
            resize = next(attrs['Code'] for name, desc, _, attrs in target_methods
                          if name == 'resizeIfNeeded' and desc == descriptor(profile, 'RenderTargets', name))
            if sha(resize) != profile['depth_contract']['resize_code_sha256']:
                raise ValueError('GPU depth generation/attachment contract drift')
            evidence.append(target + '.resizeIfNeeded:verified-generation-and-attachment-bytecode')
            _, _, _, fields = methods(jar.read(target + '.class'), include_fields=True)
            actual = {(name, desc) for name, desc, _, _ in fields}
            for name, desc in profile['depth_contract']['snapshot_fields'].items():
                if (name, desc) not in actual:
                    raise ValueError('GPU depth snapshot field drift: ' + name)
            invocation = (profile['classes']['GlFramebuffer'].replace('.', '/'),
                          'addDepthAttachment', '(Lcom/mojang/blaze3d/textures/GpuTexture;)V')
            calls = next(attrs['_invocations'] for name, desc, _, attrs in target_methods
                         if name == 'resizeIfNeeded' and desc == descriptor(profile, 'RenderTargets', name))
            if calls.count(invocation) != 3:
                raise ValueError('GPU depth snapshot attachment call count drift')
            evidence.append(target + '.resizeIfNeeded:verified-three-depth-attachment-calls')
    return evidence


def generate_sources(profile, folder):
    sources = folder / 'generated' / 'com/googlebook/fsrbridge/mixin'
    sources.mkdir(parents=True)
    replacements = {
        'FINAL_CLASS': profile['classes']['FinalPassRenderer'],
        'PIPELINE_CLASS': profile['classes']['Pipeline'],
        'VIEWPORT_CLASS': profile['classes']['ViewportUniforms'],
        'PROGRAM_IMPORT': '',
        'TARGETS_CLASS': profile['classes']['RenderTargets'],
        'RESIZE_DESCRIPTOR': descriptor(profile, 'RenderTargets', 'resizeIfNeeded'),
        'CUSTOM_UNIFORMS_CLASS': profile['classes']['CustomUniforms'],
        'CUSTOM_DEFINITION_CLASS': profile['classes']['CustomUniformDefinition'],
        'CUSTOM_INPUT_CLASS': profile['classes']['CustomUniformInputs'],
        'CACHED_UNIFORM_CLASS': profile['classes']['CachedUniform'],
        'CUSTOM_CONSTRUCTOR': descriptor(profile, 'CustomUniforms', '<init>'),
        'FRAMEBUFFER_CLASS': profile['classes']['GlFramebuffer'],
        'DEPTH_ATTACHMENT_HOOKS': '',
    }
    if profile['depth_resource'] == 'gpu_texture':
        replacements['TARGET_DEPTH_ATTACHMENT'] = ('L' + profile['classes']['GlFramebuffer'].replace('.', '/')
                                                   + ';addDepthAttachment(Lcom/mojang/blaze3d/textures/GpuTexture;)V')
        replacements['DEPTH_ATTACHMENT_HOOKS'] = template('GpuDepthAttachments', replacements)
    replacements.update({'AST_' + key.upper(): value for key, value in profile['viewport_ast_accessors'].items()})
    if profile['scene_scaling']:
        target = profile['classes']['RenderTargets'].replace('.', '/')
        args = {'TARGET_CONSTRUCTOR': 'L' + target + ';<init>' + descriptor(profile, 'RenderTargets', '<init>'),
                'TARGET_RESIZE': 'L' + target + ';resizeIfNeeded' + descriptor(profile, 'RenderTargets', 'resizeIfNeeded')}
        replacements['ALLOCATION_HOOKS'] = template('GpuSceneAllocation' if profile['depth_resource'] == 'gpu_texture' else 'SceneAllocation', args)
        replacements['PROGRAM_IMPORT'] = 'import ' + profile['classes']['ProgramSet'] + ';'
    else:
        raise ValueError('profile has no scene allocation adapter')
    for name in ['FinalPassMixin', 'PipelineMixin', 'ViewportMixin', 'RenderTargetsTraceMixin',
                 'CustomUniformsMixin', 'CustomUniformDefinitionAccessor']:
        (sources / (name + '.java')).write_text(template(name, replacements))
    (folder / 'generated/com/googlebook/fsrbridge/UniformExpressionPolicy.java').write_text(
        template('UniformExpressionPolicy', replacements))
    # Keep trace hooks on stable Iris/Oculus class names; no brittle GUI target across versions.
    for owner, names in [('HandRenderer', ['renderSolid', 'renderTranslucent']),
                         ('ShadowRenderer', ['renderShadows']), ('CompositeRenderer', ['renderAll'])]:
        cls = profile['classes'][owner]
        body = 'package com.googlebook.fsrbridge.mixin;\n'
        body += 'import com.googlebook.fsrbridge.DiagnosticTrace;\n'
        body += 'import org.spongepowered.asm.mixin.Mixin;\nimport org.spongepowered.asm.mixin.injection.*;\n'
        body += 'import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;\n'
        body += '/** Diagnostic pass boundaries verified against the pinned shader-mod artifact. */\n'
        body += '@Mixin(targets = "' + cls + '", remap = false)\npublic abstract class ' + owner + 'TraceMixin {\n'
        for index, name in enumerate(names):
            desc = descriptor(profile, owner, name)
            role = {'HandRenderer': ['HAND_SOLID', 'HAND_TRANSLUCENT'], 'ShadowRenderer': ['SHADOW'], 'CompositeRenderer': ['COMPOSITE']}[owner][index]
            body += '    @Inject(method = "' + name + desc + '", at = @At("HEAD"), require = 1)\n'
            body += '    private void mgfsr3$head' + str(index) + '(CallbackInfo callback) { DiagnosticTrace.startRole("' + role + '", true); }\n'
            body += '    @Inject(method = "' + name + desc + '", at = @At("RETURN"), require = 1)\n'
            body += '    private void mgfsr3$return' + str(index) + '(CallbackInfo callback) { DiagnosticTrace.endRole("' + role + '_RETURN", null); }\n'
        (sources / (owner + 'TraceMixin.java')).write_text(body + '}\n')
    entry = folder / 'generated/com/googlebook/fsrbridge/LoaderEntrypoint.java'
    if profile['loader'] == 'fabric':
        body = 'public final class LoaderEntrypoint implements net.fabricmc.api.ClientModInitializer {\n    @Override public void onInitializeClient() { AdapterProfile.initialize(); }\n}'
    else:
        annotation = 'net.neoforged.fml.common.Mod' if profile['loader'] == 'neoforge' else 'net.minecraftforge.fml.common.Mod'
        body = '@' + annotation + '("mgfsr3bridge")\npublic final class LoaderEntrypoint {\n    public LoaderEntrypoint() { AdapterProfile.initialize(); }\n}'
    entry.write_text('package com.googlebook.fsrbridge;\n/** Loader registration; JNI initializes lazily at the rendering boundary. */\n' + body + '\n')
    return sorted((folder / 'generated').rglob('*.java'))


def resources(profile, classes):
    pack = forge_pack_metadata(profile)
    if pack is not None:
        (classes / 'pack.mcmeta').write_text(json.dumps(pack, indent=2) + '\n')
    mixins = {'required': True, 'minVersion': '0.8', 'package': 'com.googlebook.fsrbridge.mixin',
              'compatibilityLevel': 'JAVA_17', 'client': ['FinalPassMixin', 'PipelineMixin', 'ViewportMixin',
                  'HandRendererTraceMixin', 'ShadowRendererTraceMixin', 'CompositeRendererTraceMixin', 'RenderTargetsTraceMixin',
                  'CustomUniformsMixin', 'CustomUniformDefinitionAccessor'],
              'injectors': {'defaultRequire': 1}}
    (classes / 'mixins.mgfsrbridge.json').write_text(json.dumps(mixins, indent=2) + '\n')
    props = {key: profile[key] for key in ['id', 'minecraft', 'namespace', 'scene_scaling', 'matrix_store', 'camera_fields', 'depth_convention']}
    props.update(profile['classes'])
    (classes / 'adapter.properties').write_text(''.join(f'{key}={str(value).lower() if isinstance(value, bool) else value}\n' for key, value in sorted(props.items())))
    (classes / 'META-INF').mkdir(exist_ok=True)
    (classes / 'META-INF/MANIFEST.MF').write_text('Manifest-Version: 1.0\nMixinConfigs: mixins.mgfsrbridge.json\nImplementation-Version: ' + VERSION + '\n\n')
    if profile['loader'] == 'fabric':
        data = {'schemaVersion': 1, 'id': 'mgfsr3bridge', 'version': VERSION, 'name': 'MobileGlues FSR3 Bridge',
                'environment': 'client', 'license': 'LGPL-2.1-only', 'mixins': ['mixins.mgfsrbridge.json'],
                'entrypoints': {'client': ['com.googlebook.fsrbridge.LoaderEntrypoint']},
                'depends': {'fabricloader': '>=0.19.3', 'java': '>=' + str(profile['java']),
                            'minecraft': profile['minecraft'], 'iris': '=' + profile['mod_version']}}
        (classes / 'fabric.mod.json').write_text(json.dumps(data, indent=2) + '\n')
    else:
        neo = profile['loader'] == 'neoforge'
        loader_version = '[4,)' if neo else '[40,41)' if profile['minecraft'] == '1.18.2' else '[47,48)'
        text = f'modLoader="javafml"\nloaderVersion="{loader_version}"\nlicense="LGPL-2.1-only"\n\n'
        text += f'[[mods]]\nmodId="mgfsr3bridge"\nversion="{VERSION}"\ndisplayName="MobileGlues FSR3 Bridge"\n'
        text += 'description="Client connector to the already loaded MobileGlues FSR3 backend."\n'
        if neo:
            text += '\n[[mixins]]\nconfig="mixins.mgfsrbridge.json"\n'
        for name, version in [('minecraft', profile['minecraft']), (profile['shader_mod'], profile['mod_version'])]:
            text += '\n[[dependencies.mgfsr3bridge]]\nmodId="' + name + '"\n'
            text += 'type="required"\n' if neo else 'mandatory=true\n'
            text += 'versionRange="[' + version + ']"\nordering="AFTER"\nside="CLIENT"\n'
        (classes / 'META-INF' / ('neoforge.mods.toml' if neo else 'mods.toml')).write_text(text)
    shutil.copyfile(ROOT / 'LICENSE', classes / 'LICENSE')


def native(classes):
    ndk = Path(os.environ.get('ANDROID_NDK_HOME', os.environ.get('ANDROID_NDK_ROOT', str(Path.home() / 'Android/Sdk/ndk' / NDK_VERSION))))
    if not (ndk / 'source.properties').is_file() or NDK_VERSION not in (ndk / 'source.properties').read_text():
        raise ValueError('ANDROID_NDK_HOME must point to pinned NDK ' + NDK_VERSION)
    hosts = list((ndk / 'toolchains/llvm/prebuilt').glob('*'))
    if len(hosts) != 1:
        raise ValueError('expected one NDK host toolchain')
    result = {}
    for abi, triple in [('arm64-v8a', 'aarch64-linux-android'), ('x86_64', 'x86_64-linux-android')]:
        output = classes / 'native' / abi / 'libmgfsrhelper.so'
        output.parent.mkdir(parents=True, exist_ok=True)
        run(hosts[0] / 'bin' / (triple + '26-clang++'), '-std=c++17', '-O2', '-fPIC', '-shared',
            '-Wall', '-Wextra', '-Werror', '-static-libstdc++', '-Wl,-z,max-page-size=16384',
            '-Wl,-z,relro,-z,now,-z,noexecstack', '-ffile-prefix-map=' + str(ROOT) + '=.',
            '-o', output, ROOT / 'src/main/cpp/mgfsrhelper.cpp', '-ldl')
        result[abi] = sha(output.read_bytes())
    (classes / 'native/checksums.properties').write_text(''.join(key + '=' + value + '\n' for key, value in sorted(result.items())))
    return result


def package(profile, classes):
    destination = ROOT / 'dist' / ('mgfsr3bridge-' + VERSION + '-' + profile['id'] + '.jar')
    destination.parent.mkdir(exist_ok=True)
    epoch = int(os.environ.get('SOURCE_DATE_EPOCH', '1767225600'))
    stamp = time.gmtime(max(315532800, epoch))[:6]
    with zipfile.ZipFile(destination, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as jar:
        for path in sorted(classes.rglob('*')):
            if path.is_file():
                info = zipfile.ZipInfo(path.relative_to(classes).as_posix(), stamp)
                info.external_attr = 0o100644 << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                jar.writestr(info, path.read_bytes(), compresslevel=9)
    return destination


def build(profile, dependencies):
    artifact = fetch(profile['id'], profile)
    evidence = verify_hooks(profile, artifact)
    if profile['minecraft'] == '26.2':
        with zipfile.ZipFile(dependencies['minecraft-26.2']) as game:
            _, game_renderer, _ = methods(game.read('net/minecraft/client/renderer/GameRenderer.class'))
            contract = profile['depth_contract']
            code = next(attrs['Code'] for name, desc, _, attrs in game_renderer
                        if name == 'render' and desc == contract['game_render_descriptor'])
            if sha(code) != contract['game_render_code_sha256'] or code[8 + 96] != 14:
                raise ValueError('26.2 main depth clear-zero bytecode drift')
            evidence.append('GameRenderer.render:verified-main-depth-clear-zero-bytecode')
            for owner, expected in profile['game_api'].items():
                _, declared, _ = methods(game.read(owner + '.class'))
                actual = {(name, desc) for name, desc, _, _ in declared}
                for name, desc in expected.items():
                    if (name, desc) not in actual:
                        raise ValueError('Minecraft 26.2 GPU API drift: ' + owner + '.' + name)
                    evidence.append(owner + '.' + name + desc)
            for owner, expected in profile['game_fields'].items():
                _, _, _, fields = methods(game.read(owner + '.class'), include_fields=True)
                actual = {(name, desc) for name, desc, access, _ in fields if access & 0x1}
                for name, desc in expected.items():
                    if (name, desc) not in actual:
                        raise ValueError('Minecraft 26.2 GPU field drift: ' + owner + '.' + name)
                    evidence.append(owner + '.' + name + ':' + desc)
    folder = ROOT / 'build' / profile['id']
    if folder.exists():
        shutil.rmtree(folder)
    classes = folder / 'classes'
    classes.mkdir(parents=True)
    generated = generate_sources(profile, folder)
    classpath = [dependencies['mixin'], dependencies[profile['loader'] + '-loader' if profile['loader'] != 'forge' else 'forge-javafml']]
    if profile['loader'] == 'neoforge':
        classpath.append(dependencies['neoforge-api'])
    if profile['depth_resource'] == 'gpu_texture':
        classpath.append(dependencies['minecraft-26.2'])
    if profile['scene_scaling']:
        # Use actual class bytes without Oculus's stale manifest Class-Path. Only
        # compile-time API dependencies are copied; none enter the adapter JAR.
        compile_api = folder / 'shader-api.jar'
        owners = ['ProgramSet', 'CustomUniforms', 'CustomUniformInputs', 'CachedUniform', 'GlFramebuffer', 'GlResource']
        members = {profile['classes'][owner].replace('.', '/') + '.class' for owner in owners}
        with zipfile.ZipFile(artifact) as source, zipfile.ZipFile(compile_api, 'w') as view:
            members.update(name for name in source.namelist() if name.startswith('kroppeb/stareval/') and name.endswith('.class'))
            for member in sorted(members):
                view.writestr(member, source.read(member))
        classpath.append(compile_api)
    run(os.environ.get('JAVAC', 'javac'), '--release', '17', '-Xlint:all', '-Werror', '-proc:none', '-cp', os.pathsep.join(map(str, classpath)),
        '-d', classes, *sorted((ROOT / 'src/main/java').rglob('*.java')), *generated)
    resources(profile, classes)
    native_hashes = native(classes)
    source_files = sorted(path for path in ROOT.rglob('*') if path.is_file() and not any(part in {'.git', '.cache', 'build', 'dist', '__pycache__'} for part in path.relative_to(ROOT).parts))
    source_hash = sha(b''.join(path.relative_to(ROOT).as_posix().encode() + b'\0' + path.read_bytes() for path in source_files))
    (classes / 'trace-build.properties').write_text('schema_version=2\nsource_tree_sha256=' + source_hash + '\niris_jar_sha256=' + profile['sha256'] + '\nadapter=' + profile['id'] + '\n')
    jar = package(profile, classes)
    package_metadata = verify_artifact(profile, jar, VERSION)
    report = {'profile': profile['id'], 'artifact': jar.name, 'sha256': sha(jar.read_bytes()), 'source_tree_sha256': source_hash,
              'native_sha256': native_hashes, 'verified_hooks': evidence, 'javac_lint': 'pass',
              'package_metadata': package_metadata,
              'scene_scaling_adapter': profile['scene_scaling'], 'loader_launch': 'not_run', 'device_qualification': 'not_run'}
    (ROOT / 'dist' / (profile['id'] + '-build.json')).write_text(json.dumps(report, indent=2) + '\n')
    print(report['sha256'], jar.name, flush=True)


def main():
    profiles = json.loads((ROOT / 'compatibility-lock.json').read_text())['profiles']
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profile', default='all', choices=['all'] + [profile['id'] for profile in profiles])
    args = parser.parse_args()
    dependencies = {name: fetch(name, spec) for name, spec in json.loads((ROOT / 'build-dependencies.json').read_text()).items()}
    for profile in profiles:
        if args.profile in ('all', profile['id']):
            build(profile, dependencies)


if __name__ == '__main__':
    main()
