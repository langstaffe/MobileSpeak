#!/usr/bin/env python3
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import ios_core_fingerprint as core

class FingerprintTests(unittest.TestCase):
    def test_finder_metadata_does_not_invalidate(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root/'native').mkdir()
            before = core.fingerprint(root)
            for name in ('native/src/.DS_Store', 'native/patched/.DS_Store',
                         'native/patched/tsclientlib/src/.DS_Store', 'native/.cargo/.DS_Store',
                         'native/models/.DS_Store', '.cargo/.DS_Store', 'tool/.DS_Store'):
                path = root/name; path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b'original metadata')
                self.assertEqual(before, core.fingerprint(root), name)
                path.write_bytes(b'changed metadata')
                self.assertEqual(before, core.fingerprint(root), name)

    def test_source_patch_lock_config_script_and_parameters_invalidate(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            paths = ['native/src/lib.rs', 'native/patched/tsproto/src/lib.rs', 'native/patched/tsclientlib/src/lib.rs',
                     'native/.cargo/config.toml', 'native/Cargo.lock', 'native/Cargo.toml', 'native/build.rs',
                     'tool/ios_core_fingerprint.py', 'tool/build_ios_core.sh', 'tool/prepare_audio_runtime.py',
                     'tool/build_sherpa_fft.py', 'tool/sherpa-onnx-fft.patch', 'native/models/manifest.json',
                     'native/assets/sounds/message.wav']
            for name in paths:
                path = root/name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text('original')
            before = core.fingerprint(root)
            self.assertEqual(before, core.fingerprint(root))
            for name in paths:
                path = root/name; path.write_text('modified')
                self.assertNotEqual(before, core.fingerprint(root), name)
                path.write_text('original')
            with patch.dict('os.environ', {'MOBILESPEAK_NETWORK_DIAGNOSTICS': '1'}):
                self.assertNotEqual(before, core.fingerprint(root))

    def test_unrelated_tools_do_not_invalidate(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root/'native').mkdir()
            before = core.fingerprint(root)
            for name in ('tool/validate_localizations.py', 'tool/test_ios_core_fingerprint.py',
                         'tool/check_android_apk.py', 'tool/build_android_core.sh', 'tool/android.cmake'):
                path = root/name; path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('original')
                self.assertEqual(before, core.fingerprint(root), name)
                path.write_text('modified')
                self.assertEqual(before, core.fingerprint(root), name)
                path.unlink()
                self.assertEqual(before, core.fingerprint(root), name)

    def test_missing_stale_and_modified_artifacts_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            bundle = Path(tmp)
            with self.assertRaises(FileNotFoundError): core.verify(bundle)
            for variant in ('ios-arm64', 'ios-arm64-simulator'):
                path = bundle/variant/'libmobilespeak_core.a'; path.parent.mkdir(); path.write_bytes(b'core')
            with patch.object(core, 'fingerprint', return_value={'source': 1}):
                manifest = {'source': core.fingerprint(), 'artifacts': core.artifacts(bundle)}
                (bundle/'core-fingerprint.json').write_text(json.dumps(manifest))
                core.verify(bundle); core.verify(bundle)
                for directory in (bundle, bundle/'ios-arm64', bundle/'ios-arm64-simulator'):
                    metadata = directory/'.DS_Store'
                    metadata.write_bytes(b'original metadata')
                    core.verify(bundle)
                    metadata.write_bytes(b'changed metadata')
                    core.verify(bundle)
                with patch.object(core, 'fingerprint', return_value={'source': 2}):
                    with self.assertRaises(ValueError): core.verify(bundle)
                (bundle/'ios-arm64/libmobilespeak_core.a').write_bytes(b'wrong')
                with self.assertRaises(ValueError): core.verify(bundle)

if __name__ == '__main__': unittest.main()
