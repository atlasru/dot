"""Check same-commit provenance and generate notes from the exact build outputs."""
import hashlib
import json
import os
import zipfile
from pathlib import Path

version = json.loads(Path('desktop/package.json').read_text())['version']
sha = os.environ['GITHUB_SHA']
folder = Path('release-assets')
for platform in ['android', 'desktop']:
    assert (folder / f'{platform}-source.txt').read_text(encoding='utf-8-sig').strip() == sha
names = [f'dot-android-{version}-dev-debug.apk', f'dot-desktop-{version}-windows-x64.zip']
assert sorted(p.name for p in folder.iterdir()) == sorted(names + ['android-source.txt', 'desktop-source.txt'])
with zipfile.ZipFile(folder / names[1]) as z:
    assert z.testzip() is None
    assert {n for n in z.namelist() if not n.endswith('/')} == {'dot-desktop.exe', 'runtime/xray.exe', 'runtime/wintun.dll', 'BUILD.json'}
    metadata = json.loads(z.read('BUILD.json').decode('utf-8-sig'))
    assert metadata == dict(version=version, source=sha, configuration='release', signing='unsigned')
with zipfile.ZipFile(folder / names[0]) as z:
    assert z.testzip() is None
    assert 'AndroidManifest.xml' in z.namelist()
hashes = {}
for name in names:
    binary = folder / name
    assert binary.stat().st_size > 1024 * 1024, f'Unexpectedly small asset: {name}'
    hashes[name] = hashlib.file_digest(binary.open('rb'), 'sha256').hexdigest()
(folder / 'SHA256SUMS').write_text(''.join(f'{digest}  {name}\n' for name, digest in hashes.items()))
notes = Path(f'docs/releases/{version}.md').read_text()
notes += '\n## Downloads\n\n'
for name, digest in hashes.items():
    notes += f'- `{name}`\n\n  SHA-256: `{digest}`\n\n'
notes += f'Source revision: `{sha}`. Both platforms were built from this revision.\n'
Path('release-notes.md').write_text(notes)
print((folder / 'SHA256SUMS').read_text())
