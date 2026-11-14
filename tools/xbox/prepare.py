#!/usr/bin/env python3
"""Reproduce the pinned Nexus backend source (a fork of Xodus) plus the local Android/DroidDeck patches."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

recipe = Path(__file__).resolve().parent
cache = Path(sys.argv[1]).resolve()
lock = json.loads((recipe / 'backend.lock.json').read_text())
local_patches = ['droiddeck-credentials.patch', 'droiddeck-launch-view.patch', 'droiddeck-library-subscriptions.patch',
                 'bannerlator-android.patch', 'bannerlator-diagnostics.patch',
                 'bannerlator-parallel-download.patch', 'bannerlator-cloud-token.patch']
identity = hashlib.sha256((recipe / 'backend.lock.json').read_bytes() + b''.join((recipe / name).read_bytes() for name in local_patches)).hexdigest()
source = cache / ('source-' + identity[:16])
if not (source / '.prepared').is_file():
    if source.exists():
        raise RuntimeError('Incomplete Xbox source directory; inspect it before retrying: ' + str(source))
    source.mkdir(parents=True)
    # Nexus keeps its Xodus changes as commits in its backend fork (since 0.2.15), not as patch files.
    subprocess.run(['git', 'init', '-q', str(source)], check=True)
    subprocess.run(['git', '-C', str(source), 'fetch', '-q', '--depth', '1', lock['repository'], lock['commit']], check=True)
    subprocess.run(['git', '-C', str(source), 'checkout', '-q', '--detach', 'FETCH_HEAD'], check=True)
    assert subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip() == lock['commit']
    assert hashlib.sha256((source / 'Cargo.lock').read_bytes()).hexdigest() == lock['cargoLockSha256']
    for name in local_patches:
        subprocess.run(['git', '-C', str(source), 'apply', str(recipe / name)], check=True)
    (source / '.prepared').write_text(identity + '\n')
assert (source / '.prepared').read_text().strip() == identity
print(source)
