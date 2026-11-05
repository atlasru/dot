"""Exercise the portable verifier's CLI with the same cwd/arguments as CI."""
import json
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
VERIFIER = ROOT / 'scripts/verify-portable.py'


class PortableVerifierTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.desktop = self.root / 'desktop'
        self.desktop.mkdir()
        self.archive = self.desktop / 'portable.zip'
        self.notices = {
            'LICENSE': self.root / 'LICENSE',
            'THIRD_PARTY_NOTICES.md': self.root / 'THIRD_PARTY_NOTICES.md',
            'licenses/Xray-core-MPL-2.0.txt': self.desktop / 'src-tauri/runtime/LICENSE-Xray-core.txt',
            'licenses/Wintun-PBL.txt': self.desktop / 'src-tauri/runtime/LICENSE-Wintun.txt',
        }
        notice_sources = [
            ROOT / 'LICENSE', ROOT / 'THIRD_PARTY_NOTICES.md',
            ROOT / 'third_party_licenses/Xray-core-MPL-2.0.txt',
            ROOT / 'third_party_licenses/Wintun-PBL.txt',
        ]
        self.entries = {}
        for (name, destination), original in zip(self.notices.items(), notice_sources):
            destination.parent.mkdir(parents=True, exist_ok=True)
            self.entries[name] = original.read_bytes()
            destination.write_bytes(self.entries[name])

        # Small synthetic x64 PE headers avoid building/downloading real binaries.
        pe = bytearray(2048)
        pe[:2] = b'MZ'
        struct.pack_into('<I', pe, 0x3c, 128)
        pe[128:132] = b'PE\0\0'
        struct.pack_into('<H', pe, 132, 0x8664)
        for name, relative in {
            'dot-desktop.exe': 'src-tauri/target/release/dot-desktop.exe',
            'runtime/xray.exe': 'src-tauri/runtime/xray.exe',
            'runtime/wintun.dll': 'src-tauri/runtime/wintun.dll',
        }.items():
            original = self.desktop / relative
            original.parent.mkdir(parents=True, exist_ok=True)
            original.write_bytes(pe)
            self.entries[name] = bytes(pe)
        (self.desktop / 'package.json').write_text('{"version":"0.4.0"}')
        self.entries['BUILD.json'] = b'{"version":"0.4.0"}'

    def verify(self, entries=None, cwd=None, source='.'):
        with zipfile.ZipFile(self.archive, 'w') as archive:
            for name, data in (self.entries if entries is None else entries).items():
                archive.writestr(name, data)
        return subprocess.run(
            [sys.executable, str(VERIFIER), str(self.archive), str(source)],
            cwd=self.desktop if cwd is None else cwd,
            capture_output=True, text=True,
        )

    def test_source_path_forms(self):
        for cwd, source in [
            (self.desktop, '.'), (self.root, 'desktop'),
            (self.root, self.desktop),
        ]:
            with self.subTest(cwd=cwd, source=source):
                result = self.verify(cwd=cwd, source=source)
                self.assertEqual(result.returncode, 0, result.stderr)

    def test_changed_license_bytes_are_rejected(self):
        for name in self.notices:
            with self.subTest(name=name):
                entries = dict(self.entries)
                entries[name] += b' altered'
                result = self.verify(entries)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(f'{name}: license contents differ', result.stderr)

    def test_missing_or_empty_notices_are_rejected(self):
        for name in self.notices:
            for replacement in [None, b'']:
                with self.subTest(name=name, replacement=replacement):
                    entries = dict(self.entries)
                    if replacement is None:
                        del entries[name]
                    else:
                        entries[name] = replacement
                    self.assertNotEqual(self.verify(entries).returncode, 0)

    def test_missing_canonical_license_does_not_use_desktop_copy(self):
        (self.root / 'LICENSE').unlink()
        (self.desktop / 'LICENSE').write_bytes(self.entries['LICENSE'])
        result = self.verify()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('FileNotFoundError', result.stderr)

    def test_changed_binary_is_rejected(self):
        entries = dict(self.entries)
        entries['runtime/xray.exe'] += b' altered'
        result = self.verify(entries)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('runtime/xray.exe', result.stderr)

    def test_wrong_architecture_is_rejected(self):
        entries = dict(self.entries)
        pe = bytearray(entries['dot-desktop.exe'])
        struct.pack_into('<H', pe, 132, 0x14c)
        entries['dot-desktop.exe'] = pe
        result = self.verify(entries)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('not x64', result.stderr)

    def test_wrong_version_is_rejected(self):
        entries = dict(self.entries)
        entries['BUILD.json'] = json.dumps({'version': '0.0.0'}).encode()
        self.assertNotEqual(self.verify(entries).returncode, 0)


if __name__ == '__main__':
    unittest.main()
