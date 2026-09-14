"""Validate the reopened GitHub release and the files downloaded from it."""
import hashlib
import json
import os
import sys
from pathlib import Path

release = json.loads(Path(sys.argv[1]).read_text())
notes = Path(sys.argv[2]).read_text()
folder = Path(sys.argv[3])
version = json.loads(Path('desktop/package.json').read_text())['version']
assert release['tagName'] == f'v{version}'
assert release['name'] == f'dot. v{version}'
assert not release['isDraft'] and not release['isPrerelease']
assert release['body'].replace('\r\n', '\n').strip() == notes.strip()
assert os.environ['GITHUB_SHA'] in notes
expected = {f'dot-android-{version}-dev-debug.apk', f'dot-desktop-{version}-windows-x64.zip', 'SHA256SUMS'}
assert {a['name'] for a in release['assets']} == expected
for asset in release['assets']:
    data = (folder / asset['name']).read_bytes()
    assert len(data) == asset['size'] > 0
    if asset['name'] != 'SHA256SUMS':
        assert hashlib.sha256(data).hexdigest() in notes
print(f"Verified stable release: {release['url']}")
