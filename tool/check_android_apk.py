#!/usr/bin/env python3
"""Check that an APK contains exactly the requested ABIs and their audio libraries."""
import sys
import zipfile

if len(sys.argv) < 3:
    raise SystemExit("Usage: python3 tool/check_android_apk.py APK ABI [ABI ...]")

with zipfile.ZipFile(sys.argv[1]) as apk:
    names = set(apk.namelist())
abis = {name.split('/')[1] for name in names if name.startswith('lib/') and name.endswith('.so')}
expected = set(sys.argv[2:])
assert abis == expected, f"Expected {expected}, found {abis}"
for abi in expected:
    for library in ('libmobilespeak_core.so', 'libonnxruntime.so', 'libsherpa-onnx-c-api.so', 'libc++_shared.so'):
        assert f'lib/{abi}/{library}' in names, f"Missing {abi}/{library}"
print(f"APK libraries OK: {', '.join(sorted(abis))}")
