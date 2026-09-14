"""Reopen the exact portable archive; verify x64 PE files and source hashes."""
import hashlib
import json
import struct
import sys
import zipfile
from pathlib import Path

archive, source = map(Path, sys.argv[1:3])
files = {
    'dot-desktop.exe': source / 'src-tauri/target/release/dot-desktop.exe',
    'runtime/xray.exe': source / 'src-tauri/runtime/xray.exe',
    'runtime/wintun.dll': source / 'src-tauri/runtime/wintun.dll',
}
with zipfile.ZipFile(archive) as z:
    assert z.testzip() is None, 'ZIP CRC failure'
    assert {n for n in z.namelist() if not n.endswith('/')} == set(files) | {'BUILD.json'}
    for name, original in files.items():
        data = z.read(name)
        assert len(data) > 1024 and data[:2] == b'MZ', name
        pe = struct.unpack_from('<I', data, 0x3c)[0]
        assert data[pe:pe+4] == b'PE\0\0', name
        assert struct.unpack_from('<H', data, pe+4)[0] == 0x8664, f'{name}: not x64'
        assert hashlib.sha256(data).digest() == hashlib.sha256(original.read_bytes()).digest(), name
    metadata = json.loads(z.read('BUILD.json'))
    assert metadata['version'] == json.loads((source / 'package.json').read_text())['version']
print(f'Portable ZIP verified: {archive.name}')
