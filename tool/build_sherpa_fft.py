#!/usr/bin/env python3
"""Build pinned sherpa-onnx with its existing KissFFT for 48k realtime denoising."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
root=Path(__file__).resolve().parent.parent
vendor=root/'native/vendor'
manifest=json.loads((root/'native/models/manifest.json').read_text())
source_info=manifest['sherpa_source']; patch=root/source_info['patch']
if hashlib.sha256(patch.read_bytes()).hexdigest()!=source_info['patch_sha256']:
    raise SystemExit('sherpa patch checksum mismatch')
source=vendor/'sherpa-onnx-1.13.8'
if not source.exists():
    with tempfile.TemporaryDirectory(prefix='mobilespeak-sherpa-') as tmp:
        archive=Path(tmp)/'source.tar.gz'
        subprocess.run(['curl','-fL','--retry','2',source_info['url'],'-o',str(archive)],check=True)
        if hashlib.sha256(archive.read_bytes()).hexdigest()!=source_info['sha256']:
            raise SystemExit('sherpa source checksum mismatch')
        with tarfile.open(archive) as f:f.extractall(vendor)
    subprocess.run(['git','apply',str(patch)],cwd=source,check=True)
# Refuse an incomplete or different source patch, even when a build cache exists.
subprocess.run(['git','apply','--reverse','--check',str(patch)],cwd=source,check=True)
platform=sys.argv[1]
variants={'macos':['macos'],'android':['arm64-v8a','x86_64'],'ios':['ios-arm64','ios-arm64_x86_64-simulator']}[platform]
if platform=='android' and os.environ.get('TS_ANDROID_ABI'):
    abi=os.environ['TS_ANDROID_ABI']
    if abi not in variants:raise SystemExit(f'Unsupported Android ABI: {abi}')
    variants=[abi]
for variant in variants:
    build=vendor/('build-fft-'+variant)
    marker=build/('.'+source_info['patch_sha256'])
    if marker.exists():continue
    args=['-DCMAKE_BUILD_TYPE=Release','-DBUILD_SHARED_LIBS=ON','-DSHERPA_ONNX_ENABLE_TTS=OFF','-DSHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF','-DSHERPA_ONNX_ENABLE_BINARY=OFF','-DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF','-DSHERPA_ONNX_ENABLE_WEBSOCKET=OFF','-DSHERPA_ONNX_BUILD_C_API_EXAMPLES=OFF']
    env=dict(os.environ)
    headers=vendor/'build-sherpa-macos/_deps/onnxruntime-src/include'
    if platform=='android':
        ndk=Path(env.get('ANDROID_NDK_HOME',str(Path.home()/'Library/Android/sdk/ndk/28.2.13676358')))
        args += ['-DCMAKE_TOOLCHAIN_FILE='+str(ndk/'build/cmake/android.toolchain.cmake'),'-DANDROID_ABI='+variant,'-DANDROID_PLATFORM=android-24']
        env['SHERPA_ONNXRUNTIME_LIB_DIR']=str(vendor/'jniLibs'/variant)
        if headers.exists():env['SHERPA_ONNXRUNTIME_INCLUDE_DIR']=str(headers)
        dest=vendor/'jniLibs'/variant/'libsherpa-onnx-c-api.so'
    elif platform=='ios':
        slice_=vendor/'ios-shared/onnxruntime.xcframework'/variant
        env['SHERPA_ONNXRUNTIME_LIB_DIR']=str(slice_)
        env['SHERPA_ONNXRUNTIME_INCLUDE_DIR']=str(slice_/'onnxruntime.framework/Headers')
        args += ['-DCMAKE_TOOLCHAIN_FILE='+str(source/'toolchains/ios.toolchain.cmake'),'-DPLATFORM='+('OS64' if variant=='ios-arm64' else 'SIMULATORARM64'),'-DENABLE_BITCODE=0','-DENABLE_ARC=1','-DENABLE_VISIBILITY=1','-DDEPLOYMENT_TARGET=15.0']
        dest=vendor/'ios-shared/sherpa-onnx.xcframework'/variant/'SherpaOnnxC.framework/SherpaOnnxC'
    else:
        args+=['-DCMAKE_OSX_DEPLOYMENT_TARGET=13.0']
        env['SHERPA_ONNXRUNTIME_LIB_DIR']=str(vendor/'macos/lib')
        if headers.exists():env['SHERPA_ONNXRUNTIME_INCLUDE_DIR']=str(headers)
        dest=vendor/'macos/lib/libsherpa-onnx-c-api.dylib'
    subprocess.run(['cmake','-S',str(source),'-B',str(build),*args],env=env,check=True)
    subprocess.run(['cmake','--build',str(build),'--target','sherpa-onnx-c-api','-j','8'],env=env,check=True)
    lib=build/'lib'/('libsherpa-onnx-c-api.so' if platform=='android' else 'libsherpa-onnx-c-api.dylib')
    if platform=='ios':
        subprocess.run(['install_name_tool','-id','@rpath/SherpaOnnxC.framework/SherpaOnnxC',str(lib)],check=True)
        if variant.endswith('simulator'):
            # The test build requires ARM64; keep XCFramework metadata consistent.
            import plistlib
            info=vendor/'ios-shared/sherpa-onnx.xcframework/Info.plist'
            value=plistlib.loads(info.read_bytes())
            for row in value['AvailableLibraries']:
                if row['LibraryIdentifier']==variant:row['SupportedArchitectures']=['arm64']
            info.write_bytes(plistlib.dumps(value))
    shutil.copy2(lib,dest);marker.touch()
