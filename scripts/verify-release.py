"""Validate the unified source version and frozen product completeness."""
import json
import re
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[1]
version = json.loads((root / 'desktop/package.json').read_text())['version']
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
