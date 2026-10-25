#!/usr/bin/env python3
"""Build immutable core bundles; reject missing, stale or altered iOS cores."""
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / 'ios/NativeCore/TeamSpeakCore.xcframework'

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def fingerprint(root=ROOT):
    files = set()
    for pattern in ('native/src/**/*', 'native/patched/**/*', 'native/.cargo/**/*',
                    '.cargo/**/*', 'native/models/**/*', 'native/Cargo.*',
                    # Keep the tool inputs aligned with build_ios_core.sh and its helpers.
                    'tool/ios_core_fingerprint.py', 'tool/build_ios_core.sh',
                    'tool/prepare_audio_runtime.py', 'tool/build_sherpa_fft.py',
                    'tool/sherpa-onnx-fft.patch',
                    'native/build.rs', 'rust-toolchain*', 'native/rust-toolchain*'):
        files.update(p for p in root.glob(pattern) if p.is_file() and p.name != '.DS_Store' and '__pycache__' not in p.parts and p.suffix != '.pyc')
    return {'files': {str(p.relative_to(root)): digest(p) for p in sorted(files)},
            'parameters': {'targets': ['aarch64-apple-ios', 'aarch64-apple-ios-sim'],
                           'profile': 'release', 'deployment': '15.0',
                           'diagnostics': os.environ.get('MOBILESPEAK_NETWORK_DIAGNOSTICS', '0'),
                           **{k: os.environ.get(k, '') for k in ('RUSTFLAGS', 'CARGO_ENCODED_RUSTFLAGS', 'CC', 'CXX', 'CFLAGS', 'CXXFLAGS', 'CARGO_PROFILE_RELEASE_OPT_LEVEL', 'CARGO_PROFILE_RELEASE_LTO', 'CARGO_PROFILE_RELEASE_CODEGEN_UNITS', 'CARGO_PROFILE_RELEASE_PANIC', 'CARGO_PROFILE_RELEASE_DEBUG') }},
            'cargo_user_config': {str(p): digest(p) for p in (Path.home()/'.cargo/config', Path.home()/'.cargo/config.toml') if p.is_file()},
            'rustc': subprocess.check_output([str(Path.home()/'.cargo/bin/rustc'), '-Vv'], text=True, cwd=root/'native'),
            'xcode': subprocess.check_output(['xcodebuild', '-version'], text=True)}

def artifacts(bundle):
    return {str(p.relative_to(bundle)): digest(p) for p in sorted(bundle.rglob('*'))
            if p.is_file() and p.name not in ('core-fingerprint.json', '.DS_Store')}

def verify(bundle=OUTPUT):
    manifest = json.loads((bundle/'core-fingerprint.json').read_text())
    if manifest['source'] != fingerprint() or manifest['artifacts'] != artifacts(bundle):
        raise ValueError('core source, build parameters or artifact fingerprint changed')
    for variant in ('ios-arm64', 'ios-arm64-simulator'):
        if not (bundle/variant/'libmobilespeak_core.a').is_file():
            raise ValueError('missing core slice: ' + variant)
    # ProcessXCFramework can run before script phases. Verify the actual linker input too.
    if os.environ.get('BUILT_PRODUCTS_DIR') and os.environ.get('PLATFORM_NAME') in ('iphoneos', 'iphonesimulator'):
        variant = 'ios-arm64' if os.environ['PLATFORM_NAME'] == 'iphoneos' else 'ios-arm64-simulator'
        linked = Path(os.environ['BUILT_PRODUCTS_DIR'])/'libmobilespeak_core.a'
        if not linked.is_file() or digest(linked) != manifest['artifacts'][variant+'/libmobilespeak_core.a']:
            raise ValueError('Xcode copied a different core; rebuild core then rerun Xcode')

def main():
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with (OUTPUT.parent/'.core-build.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if sys.argv[1:] == ['build']:
            before = fingerprint()
            with tempfile.TemporaryDirectory(prefix='.core-stage-', dir=OUTPUT.parent) as stage:
                bundle = Path(stage)/'TeamSpeakCore.xcframework'
                subprocess.run(['bash', str(ROOT/'tool/build_ios_core.sh'), '--inner', str(bundle)], check=True)
                if fingerprint() != before:
                    raise ValueError('source changed during core build; rebuild')
                (bundle/'core-fingerprint.json').write_text(json.dumps({'source': before, 'artifacts': artifacts(bundle)}, sort_keys=True))
                # Published versions are immutable, including while Xcode is linking.
                version = Path(tempfile.mkdtemp(prefix='.core-version-', dir=OUTPUT.parent))
                shutil.move(str(bundle), str(version/'TeamSpeakCore.xcframework'))
                link = Path(stage)/'link'
                link.symlink_to(version/'TeamSpeakCore.xcframework', target_is_directory=True)
                if OUTPUT.exists() and not OUTPUT.is_symlink():
                    OUTPUT.rename(version/'legacy.xcframework')
                os.replace(link, OUTPUT)
        verify()
    print('iOS core fingerprint verified (device + simulator)')

if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        sys.exit(f'error: iOS core is missing or stale: {error}. Rebuild: bash tool/build_ios_core.sh')
