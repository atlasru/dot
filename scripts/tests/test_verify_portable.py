"""Regression coverage for the real portable validation CLI's relative source argument."""
import json
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


class PortableValidationTest(unittest.TestCase):
    def run_validator(self, corrupt_license=False):
        script = Path(__file__).resolve().parents[1] / 'verify-portable.py'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            desktop = root / 'desktop'
            files = {
                'dot-desktop.exe': desktop / 'src-tauri/target/release/dot-desktop.exe',
                'runtime/xray.exe': desktop / 'src-tauri/runtime/xray.exe',
                'runtime/wintun.dll': desktop / 'src-tauri/runtime/wintun.dll',
                'LICENSE': root / 'LICENSE',
                'THIRD_PARTY_NOTICES.md': root / 'THIRD_PARTY_NOTICES.md',
                'licenses/Xray-core-MPL-2.0.txt': desktop / 'src-tauri/runtime/LICENSE-Xray-core.txt',
                'licenses/Wintun-PBL.txt': desktop / 'src-tauri/runtime/LICENSE-Wintun.txt',
            }
            pe = bytearray(2048)
            pe[:2] = b'MZ'
            struct.pack_into('<I', pe, 0x3c, 128)
            pe[128:132] = b'PE\0\0'
            struct.pack_into('<H', pe, 132, 0x8664)
            for name, path in files.items():
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(pe if name.endswith(('.exe', '.dll')) else b'License fixture\n' * 40)
            (desktop / 'package.json').write_text(json.dumps({'version': '0.5.0'}), encoding='utf-8')
            archive = desktop / 'candidate.zip'
            with zipfile.ZipFile(archive, 'w') as output:
                for name, path in files.items():
                    contents = path.read_bytes()
                    if corrupt_license and name == 'LICENSE':
                        contents += b'altered'
                    output.writestr(name, contents)
                output.writestr('BUILD.json', json.dumps({'version': '0.5.0'}))
            # CI invokes exactly this way from desktop/: source is ".".
            return subprocess.run([sys.executable, str(script), str(archive), '.'], cwd=desktop, capture_output=True)

    def test_relative_desktop_source_resolves_root_notices(self):
        result = self.run_validator()
        self.assertEqual(result.returncode, 0, result.stderr.decode(errors='replace'))

    def test_modified_license_is_still_rejected(self):
        result = self.run_validator(corrupt_license=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b'license contents differ', result.stderr)


if __name__ == '__main__':
    unittest.main()
