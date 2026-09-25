"""Stage the APK to capture (a published release, or a branch build) and create original, tagged demo audio."""
import hashlib
import json
import os
import shutil
from pathlib import Path
import subprocess
from urllib.parse import quote

OUT = Path('promo-output')
OUT.mkdir(exist_ok=True)
apk = OUT / 'tryptify.apk'
built = os.environ.get('APK_PATH')
if built:
    # A branch build from the workflow: capture exactly what build_ref produced.
    shutil.copyfile(built, apk)
    digest = hashlib.file_digest(apk.open('rb'), 'sha256').hexdigest()
    source = {'build_ref': os.environ.get('BUILD_REF'), 'build_commit': os.environ.get('BUILD_SHA'),
              'apk': 'benchmark variant (release-shaped, debug-signed)'}
else:
    tag = os.environ.get('RELEASE_TAG', 'latest')
    repo = os.environ['GITHUB_REPOSITORY']
    endpoint = f'repos/{repo}/releases/' + ('latest' if tag == 'latest' else 'tags/' + quote(tag, safe=''))
    release = json.loads(subprocess.check_output(['gh', 'api', endpoint]))
    assets = [a for a in release['assets'] if a['name'].lower().endswith('.apk')]
    preferred = [a for a in assets if a['name'] == 'tryptify.apk']
    if len(preferred or assets) != 1:
        raise SystemExit('Expected tryptify.apk or exactly one APK in this release.')
    asset = (preferred or assets)[0]
    subprocess.run(['curl', '--fail', '--location', '--retry', '3', '--max-time', '300',
                    '--output', str(apk), asset['browser_download_url']], check=True)
    digest = hashlib.file_digest(apk.open('rb'), 'sha256').hexdigest()
    if asset.get('digest') and asset['digest'] != 'sha256:' + digest:
        raise SystemExit('APK SHA-256 does not match the release asset digest.')
    source = {'release': release['tag_name'], 'release_url': release['html_url'], 'apk': asset['name']}
(OUT / 'build.json').write_text(json.dumps({
    **source, 'sha256': digest,
    'capture_scripts_commit': os.environ.get('GITHUB_SHA'),
    'device': 'Android 15 / x86_64 / 1080x2400 / 420 dpi',
    'media': 'Generated demonstration tones, not commercially released music',
}, indent=2))
media = OUT / 'demo-media'
media.mkdir(exist_ok=True)
for i, (title, frequency) in enumerate([('Afterglow (Demo)', 220), ('Night Drive (Demo)', 277),
                                        ('Prism (Demo)', 330)], start=1):
    subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i',
        f'sine=frequency={frequency}:sample_rate=48000:duration=180', '-af', 'volume=0.08',
        '-ac', '2', '-metadata', f'title={title}', '-metadata', 'artist=Tryptify Demo',
        '-metadata', 'album=Signal Studies', '-metadata', 'album_artist=Tryptify Demo',
        '-metadata', 'composer=Tryptify Demo', '-metadata', 'genre=Electronic',
        '-metadata', 'date=2026', '-metadata', f'track={i}',
        str(media / f'{i:02d}-demo.flac')], check=True)
