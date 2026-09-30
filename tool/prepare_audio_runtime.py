#!/usr/bin/env python3
"""Build-time only: fixed, checksum-verified native runtime archives and models."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import zipfile
root = Path(__file__).resolve().parent.parent
manifest = json.loads((root / 'native/models/manifest.json').read_text())
platform = sys.argv[1]
for entry in manifest['models']:
    path = root / 'native/models' / entry['file']
    if hashlib.sha256(path.read_bytes()).hexdigest() != entry['sha256']:
        raise SystemExit(f'Model checksum mismatch: {path}')
for entry in manifest['runtimes']:
    if entry['platform'] != platform:
        continue
    dest = root / 'native/vendor' / entry['dest']
    marker = dest / ('.' + entry['sha256'])
    if marker.exists():
        continue
    dest.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='mobilespeak-runtime-') as tmp:
        archive = Path(tmp) / 'runtime'
        subprocess.run(['curl', '-fL', '--retry', '2', entry['url'], '-o', str(archive)], check=True)
        if hashlib.sha256(archive.read_bytes()).hexdigest() != entry['sha256']:
            raise SystemExit('Native runtime archive checksum mismatch')
        extracted = Path(tmp) / 'extracted'; extracted.mkdir()
        if entry['format'] == 'tar':
            with tarfile.open(archive) as f:
                f.extractall(extracted)
            source = next(extracted.iterdir()) if platform == 'macos' else extracted
        else:
            with zipfile.ZipFile(archive) as f:
                # Archive bytes have already been verified against a fixed upstream checksum.
                f.extractall(extracted)
            source = extracted
        shutil.copytree(source, dest, dirs_exist_ok=True)
    marker.touch()
