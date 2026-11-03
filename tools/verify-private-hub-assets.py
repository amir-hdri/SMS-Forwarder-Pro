#!/usr/bin/env python3
"""Execute the actual Gradle asset boundary with synthetic one-use profiles only."""
import json
from pathlib import Path
import subprocess
import tempfile
import time

root = Path(__file__).resolve().parents[1]
out = root / 'app/build/generated/privateHubAssets/hubRelease/barpro_hub_install.json'
with tempfile.TemporaryDirectory(prefix='synthetic-hub-profile-') as folder:
    profile = Path(folder) / 'profile.json'
    document = dict(protocol='barpro-hub-install-v1', server='https://87.107.160.251/',
                    capability='A' * 43, expires_at=int(time.time()) + 3600)
    def execute(label, private=True, success=True, cached=False):
        command = ['./gradlew', '--max-workers=2', 'generateHubReleaseProvisioningAssets', '--build-cache' if cached else '--no-build-cache', '--console=plain']
        if private:
            command.append(f'-PBARPRO_HUB_PROVISIONING_PROFILE_FILE={profile}')
        log = Path('/tmp') / f'private-hub-assets-{label}.log'
        with log.open('w') as output:
            result = subprocess.run(command, cwd=root, stdout=output, stderr=subprocess.STDOUT)
        assert (result.returncode == 0) == success, f'{label}: unexpected Gradle result; see {log}'
    profile.write_text(json.dumps(document))
    execute('first')
    assert json.loads(out.read_text()) == document
    document['capability'] = 'B' * 43
    profile.write_text(json.dumps(document))
    execute('changed')
    assert json.loads(out.read_text()) == document, 'A prior private capability survived a new private build'
    execute('public', private=False)
    assert not out.exists(), 'A private capability leaked into a subsequent public asset build'
    document['server'] = 'https://87.107.160.251'
    profile.write_text(json.dumps(document))
    execute('root-spelling')
    assert json.loads(out.read_text()) == document
    execute('cache-rejected', cached=True, success=False)
    assert not out.exists(), 'A cache-enabled private build left a capability asset'
    document['server'] = 'https://untrusted.example.test/'
    profile.write_text(json.dumps(document))
    execute('origin-rejected', success=False)
    assert not out.exists()
    document['server'] = 'https://87.107.160.251/'
    document['expires_at'] = int(time.time()) - 1
    profile.write_text(json.dumps(document))
    execute('expired', success=False)
    assert not out.exists(), 'Rejected profile left a usable stale private asset'
print('7 actual Gradle asset controls passed: private generation, changed profile, public purge, equivalent root, cache refusal, origin refusal, expired rejection.')
