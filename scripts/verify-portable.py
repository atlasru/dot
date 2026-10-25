"""Reopen the exact portable archive; verify x64 PE files and source hashes."""
import hashlib
import json
import struct
import sys
import zipfile
from pathlib import Path

archive, source = (Path(value).resolve() for value in sys.argv[1:3])
binaries = {
    'dot-desktop.exe': source / 'src-tauri/target/release/dot-desktop.exe',
    'runtime/xray.exe': source / 'src-tauri/runtime/xray.exe',
    'runtime/wintun.dll': source / 'src-tauri/runtime/wintun.dll',
}
notices = {
    'LICENSE': source.parent / 'LICENSE',
    'THIRD_PARTY_NOTICES.md': source.parent / 'THIRD_PARTY_NOTICES.md',
    'licenses/Xray-core-MPL-2.0.txt': source / 'src-tauri/runtime/LICENSE-Xray-core.txt',
    'licenses/Wintun-PBL.txt': source / 'src-tauri/runtime/LICENSE-Wintun.txt',
}
with zipfile.ZipFile(archive) as z:
    assert z.testzip() is None, 'ZIP CRC failure'
    assert {n for n in z.namelist() if not n.endswith('/')} == set(binaries) | set(notices) | {'BUILD.json'}
    for name, original in binaries.items():
        data = z.read(name)
        assert len(data) > 1024 and data[:2] == b'MZ', name
        pe = struct.unpack_from('<I', data, 0x3c)[0]
        assert data[pe:pe+4] == b'PE\0\0', name
        assert struct.unpack_from('<H', data, pe+4)[0] == 0x8664, f'{name}: not x64'
        assert hashlib.sha256(data).digest() == hashlib.sha256(original.read_bytes()).digest(), name
    for name, original in notices.items():
        data = z.read(name)
        assert len(data) > 250, f'{name}: missing or empty license/notice'
        assert hashlib.sha256(data).digest() == hashlib.sha256(original.read_bytes()).digest(), f'{name}: license contents differ'
    metadata = json.loads(z.read('BUILD.json'))
    assert metadata['version'] == json.loads((source / 'package.json').read_text())['version']
print(f'Portable ZIP verified: {archive.name}')
