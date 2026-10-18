"""Validate the unified source version and frozen product completeness."""
import json
import re
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[1]
package = json.loads((root / 'desktop/package.json').read_text())
version = package['version']
assert package.get('license') == 'GPL-3.0-or-later'
assert json.loads((root / 'desktop/package-lock.json').read_text())['packages']['']['license'] == 'GPL-3.0-or-later'
assert 'license = "GPL-3.0-or-later"' in (root / 'desktop/src-tauri/Cargo.toml').read_text()
assert (root / 'LICENSE').read_text().startswith('GNU GENERAL PUBLIC LICENSE')
assert 'GPL-3.0-or-later' in (root / 'THIRD_PARTY_NOTICES.md').read_text()
assert (root / 'app/src/main/assets/LICENSE').read_bytes() == (root / 'LICENSE').read_bytes()
assert (root / 'app/src/main/assets/THIRD_PARTY_NOTICES.md').read_bytes() == (root / 'THIRD_PARTY_NOTICES.md').read_bytes()
for name in ['Xray-core-MPL-2.0.txt', 'libXray-MIT.txt']:
    assert (root / 'third_party_licenses' / name).read_bytes() == (
        root / 'app/src/main/assets/licenses' / name
    ).read_bytes()
assert re.fullmatch(r'\d+\.\d+\.\d+', version), f'Not a stable version: {version}'
android = (root / 'app/build.gradle.kts').read_text()
assert re.search(r'versionName\s*=\s*"' + re.escape(version) + '"', android)
code = int(re.search(r'versionCode\s*=\s*(\d+)', android)[1])
if version == '0.3.0':
    assert code == 300, f'Unexpected Android versionCode: {code}'
subprocess.run(['node', str(root / 'desktop/scripts/sync-version.mjs'), '--check'], check=True)
for folder in ['app/src/main', 'desktop/src', 'desktop/src-tauri/src']:
    for path in (root / folder).rglob('*'):
        if path.suffix in {'.kt', '.rs', '.ts', '.tsx', '.js', '.css', '.xml'}:
            assert not re.search(r'TODO|FIXME|todo!\(|unimplemented!\(', path.read_text()), path
print(f'Unified version verified: {version}; Android versionCode {code}')
