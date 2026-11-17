"""Pre-record fixed catalog dialogue with Fish Audio as separate MP3 voice lines.

--plan validates cast and writes pending metadata without contacting the provider.
--generate creates/resumes recordings; --check checks published coverage and assets.
Requires ffprobe for validation, PyYAML only for application-local.yaml fallback.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import math
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

from sample_audio import (OUTPUT, ROOT, SAMPLE_RATE, WEB_PATH, cpath_definitions, definitions, digest, pending_entry)

CACHE = ROOT / 'target/ai-call-sample-audio/cache'
MANIFEST = OUTPUT / 'manifest.json'
# What the voice actually reads, for the few displayed lines the provider mispronounces (e.g. a price in brackets
# read as noise). The transcript shown to learners stays the authored text.
SPEECH_FILE = ROOT / 'data/ai-call/sample-audio-speech.json'
SPEECH = json.loads(SPEECH_FILE.read_text(encoding='utf-8'))['lines'] if SPEECH_FILE.exists() else {}


def spoken(line):
    return SPEECH.get(line.text, line.text)


def clip_name(line, model, speed):
    return digest({'text': spoken(line), 'voice': line.voiceId, 'model': model, 'speed': speed}) + '.mp3'
MODELS = ('s1', 's2-pro', 's2.1-pro', 's2.1-pro-free', 'drama-3-preview')


def configuration():
    """Secrets stay in memory; Spring dotted and nested YAML forms are supported."""
    local = ROOT / 'src/main/resources/application-local.yaml'
    values = {}
    if local.exists():
        try:
            import yaml
        except ImportError:
            if not os.environ.get('FISH_AUDIO_API_KEY'):
                raise ValueError('Set FISH_AUDIO_API_KEY or install PyYAML to read local configuration.') from None
        else:
            try:
                values = yaml.safe_load(local.read_text(encoding='utf-8')) or {}
            except yaml.YAMLError:
                raise ValueError('Local Fish Audio configuration could not be parsed. Check the YAML syntax locally.') from None
            if not isinstance(values, dict):
                raise ValueError('Local configuration must be a YAML mapping.')

    def setting(name, env, default=''):
        if os.environ.get(env):
            return os.environ[env].strip()
        value = values.get(name)
        if value is None:
            value = values
            for part in name.split('.'):
                value = value.get(part) if isinstance(value, dict) else None
        if value is None:
            return default
        value = str(value).strip()
        if value.startswith('${') and value.endswith('}'):
            variable, _, fallback = value[2:-1].partition(':')
            return os.environ.get(variable, fallback or default).strip()
        return value

    return {'key': setting('fish.audio.api.key', 'FISH_AUDIO_API_KEY'),
            'url': setting('fish.audio.api.url', 'FISH_AUDIO_API_URL', 'https://api.fish.audio'),
            'model': setting('fish.audio.model', 'FISH_AUDIO_MODEL', 's2.1-pro-free')}


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix('.json.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    temporary.replace(path)


def run_media(arguments):
    result = subprocess.run(arguments, capture_output=True, timeout=120)
    if result.returncode:
        raise ValueError(f'{Path(arguments[0]).name} could not process recording; check its audio format.')
    return result.stdout


def duration(path):
    data = json.loads(run_media(['ffprobe', '-v', 'error', '-select_streams', 'a:0',
                                '-show_entries', 'stream=codec_name:format=duration', '-of', 'json', str(path)]))
    if not data.get('streams') or data['streams'][0].get('codec_name') != 'mp3':
        raise ValueError(f'Invalid MP3 asset: {path.name}')
    seconds = float(data['format']['duration'])
    if not math.isfinite(seconds) or seconds <= 0:
        raise ValueError(f'Empty MP3 asset: {path.name}')
    return seconds


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def synthesize(line, config, speed, *, temperature=None):
    if not config['key'] or config['key'].startswith('YOUR_'):
        raise ValueError('Fish Audio key is missing. Set FISH_AUDIO_API_KEY or fish.audio.api.key locally.')
    address = urllib.parse.urlsplit(config['url'])
    if address.scheme != 'https' or not address.hostname or address.username or address.password or address.query or address.fragment:
        raise ValueError('Fish Audio API URL must be a HTTPS service address without credentials or query parameters.')
    body = {'text': spoken(line), 'reference_id': line.voiceId, 'format': 'mp3',
            'mp3_bitrate': 128, 'sample_rate': SAMPLE_RATE, 'latency': 'normal',
            'prosody': {'speed': speed, 'volume': 0, 'normalize_loudness': True}}
    if temperature is not None:
        if type(temperature) not in (int, float) or not math.isfinite(temperature) or not 0 <= temperature <= 1:
            raise ValueError('Recording temperature must be a finite number between 0 and 1.')
        body['temperature'] = temperature
    request = urllib.request.Request(config['url'].rstrip('/') + '/v1/tts',
                                    data=json.dumps(body, ensure_ascii=False).encode('utf-8'),
                                    headers={'Authorization': 'Bearer ' + config['key'],
                                             'Content-Type': 'application/json', 'model': config['model']})
    opener = urllib.request.build_opener(NoRedirect())
    for attempt in range(3):
        try:
            with opener.open(request, timeout=120) as response:
                audio = response.read(16 * 1024 * 1024 + 1)
                if not audio or len(audio) > 16 * 1024 * 1024:
                    raise ValueError('Fish Audio returned empty or excessive audio.')
                return audio
        except urllib.error.HTTPError as error:
            if error.code in (429, 500, 502, 503, 504) and attempt < 2:
                time.sleep(2 ** attempt)
                continue
            # Never echo provider bodies, authorization headers or a private proxy URL.
            reason = {401: 'update the API key', 402: 'check provider credits',
                      403: 'check access to this voice', 429: 'retry after the provider rate limit'}.get(error.code, 'check provider availability and voice access')
            raise ValueError(f'Fish Audio HTTP {error.code}: {reason}. No recording was published for this sample.') from None
        except (urllib.error.URLError, TimeoutError):
            if attempt < 2:
                time.sleep(2 ** attempt)
                continue
            raise ValueError('Fish Audio connection failed or timed out. Completed recordings can be resumed.') from None


def clip_asset(line, config, speed):
    """Publish the provider's original MP3, without joining or re-encoding speech."""
    raw = CACHE / clip_name(line, config['model'], speed)
    asset = OUTPUT / 'clips' / raw.name
    if asset.is_file():
        return {'audioUrl': WEB_PATH + 'clips/' + raw.name, 'duration': round(duration(asset), 6)}
    CACHE.mkdir(parents=True, exist_ok=True)
    if not raw.exists():
        audio = synthesize(line, config, speed)
        temporary = raw.with_suffix('.mp3.tmp')
        temporary.write_bytes(audio)
        try:
            duration(temporary)
            temporary.replace(raw)
        finally:
            temporary.unlink(missing_ok=True)
    measured = duration(raw)
    asset.parent.mkdir(parents=True, exist_ok=True)
    staged = asset.with_suffix('.mp3.tmp')
    try:
        shutil.copyfile(raw, staged)
        staged.replace(asset)
    finally:
        staged.unlink(missing_ok=True)
    return {'audioUrl': WEB_PATH + 'clips/' + raw.name, 'duration': round(measured, 6)}


def record(sample, config, speed, gap, workers=1):
    entry = pending_entry(sample, config['model'], speed, gap)
    # Submit each text/voice pair once so repeated turns never write the same
    # temporary MP3 concurrently. Rebuild the transcript in its authored order.
    unique = {(line.text, line.voiceId): line for line in sample.lines}
    with ThreadPoolExecutor(max_workers=workers) as executor:
        futures = {key: executor.submit(clip_asset, line, config, speed)
                   for key, line in unique.items()}
        assets = {key: future.result() for key, future in futures.items()}
    lines = [line.public() | assets[(line.text, line.voiceId)] for line in sample.lines]
    total = sum(line['duration'] for line in lines) + gap * (len(lines) - 1)
    entry.update(ready=True, duration=round(total, 6), lines=lines)
    return entry


def complete_entry(entry, expected, verify_audio=False):
    if not isinstance(entry, dict) or entry.get('revision') != expected['revision']:
        return False
    if entry.get('ready') is not True or entry.get('gap') != expected['gap']:
        return False
    seconds = entry.get('duration')
    if not isinstance(seconds, (int, float)) or not math.isfinite(seconds) or seconds <= 0:
        return False
    recorded = entry.get('lines', [])
    if not isinstance(recorded, list) or len(recorded) != len(expected['lines']):
        return False
    total = expected['gap'] * (len(recorded) - 1)
    for actual, authored in zip(recorded, expected['lines']):
        if not isinstance(actual, dict) or any(actual.get(k) != v for k, v in authored.items()):
            return False
        clip_seconds, url = actual.get('duration'), actual.get('audioUrl')
        if type(clip_seconds) not in (int, float) or not math.isfinite(clip_seconds) or clip_seconds <= 0:
            return False
        prefix = WEB_PATH + 'clips/'
        if not isinstance(url, str) or not url.startswith(prefix):
            return False
        filename = url[len(prefix):]
        if len(filename) != 68 or not filename.endswith('.mp3') or any(c not in '0123456789abcdef' for c in filename[:-4]):
            return False
        asset = OUTPUT / 'clips' / filename
        if not asset.is_file() or not asset.stat().st_size:
            return False
        if verify_audio:
            try:
                if abs(duration(asset) - clip_seconds) > .2:
                    return False
            except (ValueError, OSError):
                return False
        total += clip_seconds
    return abs(total - seconds) <= .001


def execute(args):
    # All cast/text validation happens before any paid provider request.
    samples = definitions() + cpath_definitions()
    if len({s.key for s in samples}) != len(samples):
        raise ValueError('A cpath sample key repeats a catalog sample key')
    previous = json.loads(MANIFEST.read_text(encoding='utf-8')) if MANIFEST.exists() else {}
    if not isinstance(previous, dict):
        raise ValueError('Published recording manifest must be a JSON object.')
    config = configuration() if args.generate else {'model': os.environ.get('FISH_AUDIO_MODEL') or previous.get('model', 's2.1-pro-free')}
    config['model'] = args.model or config['model']
    args.speed = args.speed if args.speed is not None else previous.get('speed', 1.0)
    args.gap = args.gap if args.gap is not None else previous.get('gap', 2.0)
    if config['model'] not in MODELS:
        raise ValueError('Unsupported Fish Audio model; choose an explicitly supported model.')
    if (type(args.speed) not in (int, float) or type(args.gap) not in (int, float)
            or not .5 <= args.speed <= 2 or not 0 <= args.gap <= 2):
        raise ValueError('Recording speed must be 0.5–2 and gap must be 0–2 seconds.')
    selected = [s for s in samples if (not args.scenario or s.scenario == args.scenario)
                and (not args.sample or s.code == args.sample)]
    if not selected:
        raise ValueError('No authored sample matches the requested scenario/sample.')
    if (args.generate or args.check) and not shutil.which('ffprobe'):
        raise ValueError('Install ffprobe before recording/checking audio.')
    published = previous.get('samples', {}) if previous.get('version') == 2 else {}
    if not isinstance(published, dict):
        raise ValueError('Published recording samples must be a JSON object.')
    entries = {}
    for sample in samples:
        expected = pending_entry(sample, config['model'], args.speed, args.gap)
        old = published.get(sample.key)
        complete = complete_entry(old, expected, args.generate or args.check)
        # A newly added spoken form must replace the clip recorded from the displayed text.
        if complete and any(line.text in SPEECH and not recorded['audioUrl'].endswith(clip_name(line, config['model'], args.speed))
                            for line, recorded in zip(sample.lines, old['lines'])):
            complete = False
        entries[sample.key] = old if complete else expected
    manifest = {'version': 2, 'provider': 'fish.audio', 'model': config['model'],
                'speed': args.speed, 'gap': args.gap, 'samples': entries}
    ready = sum(e['ready'] for e in entries.values())
    unique = {digest({'text': l.text, 'voice': l.voiceId}) for s in selected for l in s.lines}
    print(f'Validated {len(samples)} samples; {ready} ready. Selected {len(selected)} samples / {len(unique)} unique voice lines.', flush=True)
    if args.check:
        missing = [s.key for s in selected if not complete_entry(entries[s.key], pending_entry(s, config['model'], args.speed, args.gap), True)]
        if missing:
            raise ValueError(f'{len(missing)} recordings are missing, outdated or invalid. Run --generate after configuring Fish Audio.')
        print('Selected published recordings and transcript timing are valid.')
        return
    OUTPUT.mkdir(parents=True, exist_ok=True)
    write_json(MANIFEST, manifest)
    if args.plan:
        return
    CACHE.mkdir(parents=True, exist_ok=True)
    for sample in selected:
        if entries[sample.key]['ready']:
            continue
        print(f'Recording {sample.key} ({len(sample.lines)} lines)...', flush=True)
        entries[sample.key] = record(sample, config, args.speed, args.gap, args.workers)
        write_json(MANIFEST, manifest)
        print(f'Published {sample.key}: {entries[sample.key]["duration"]:.1f}s', flush=True)


def main():
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, 'reconfigure'):
            stream.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--plan', action='store_true')
    mode.add_argument('--generate', action='store_true')
    mode.add_argument('--check', action='store_true')
    parser.add_argument('--scenario', help='Exact scenario slug (including --case suffix for legacy cases).')
    parser.add_argument('--sample', help='Exact dialogue code, e.g. normal or example.')
    parser.add_argument('--model', choices=MODELS)
    parser.add_argument('--speed', type=float, help='Recording speed, initially 1.0; otherwise retain published settings.')
    parser.add_argument('--gap', type=float, help='Gap between turns, initially 2 seconds; otherwise retain published settings.')
    parser.add_argument('--workers', type=int, choices=range(1, 5), default=1,
                        help='Concurrent voice requests (1–4, default 1); completed clips are reusable on retry.')
    try:
        execute(parser.parse_args())
    except (ValueError, OSError, json.JSONDecodeError, subprocess.TimeoutExpired) as error:
        # These messages contain no credentials or provider response body.
        print(f'Recording stopped: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
