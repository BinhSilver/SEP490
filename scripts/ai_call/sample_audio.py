"""Validated, database-independent recording definitions for authored AI Call samples."""
from dataclasses import asdict, dataclass
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / 'data/ai-call/catalog.json'
CAST = ROOT / 'data/ai-call/sample-audio-cast.json'
OUTPUT = ROOT / 'src/main/resources/static/audio/ai-call/samples'
WEB_PATH = '/audio/ai-call/samples/'
PIPELINE_VERSION = 2
SAMPLE_RATE = 44100


def digest(value):
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))
    return hashlib.sha256(encoded.encode('utf-8')).hexdigest()


def identifier(value):
    if not isinstance(value, str) or not re.fullmatch(r'[a-z0-9][a-z0-9-]{0,119}', value):
        raise ValueError('Invalid sample identifier')
    return value


@dataclass(frozen=True)
class Line:
    speaker: str
    text: str
    translation: str
    speakerCode: str
    speakerName: str
    voiceId: str

    def public(self):
        return {k: v for k, v in asdict(self).items() if k != 'voiceId'}


@dataclass(frozen=True)
class Sample:
    scenario: str
    code: str
    title: str
    lines: tuple[Line, ...]

    @property
    def key(self):
        return self.scenario + '/' + self.code

    def revision(self, model, speed, gap):
        return digest({'pipeline': PIPELINE_VERSION, 'model': model, 'speed': speed,
                       'gap': gap, 'lines': [asdict(line) for line in self.lines]})


def definitions(catalog=None, cast=None):
    catalog = catalog if catalog is not None else json.loads(CATALOG.read_text(encoding='utf-8'))
    cast = cast if cast is not None else json.loads(CAST.read_text(encoding='utf-8'))
    if cast.get('version') != 1:
        raise ValueError('Unsupported recording cast version')
    result = []
    group_slugs = {g['slug'] for g in catalog['scenarios']}
    if set(cast['scenarios']) != group_slugs:
        raise ValueError('Recording cast must cover every scenario')
    for group in catalog['scenarios']:
        root_slug = identifier(group['slug'])
        mapping = cast['scenarios'][root_slug]
        learner_voice = mapping['learnerVoiceId']
        if not re.fullmatch(r'[0-9a-f]{32}', learner_voice):
            raise ValueError(f'{root_slug}: invalid learner voice')
        seen_texts = set()
        overrides_used = set()
        for case in group['cases']:
            case_code = identifier(case['code'])
            slug = root_slug if case_code == 'standard' else root_slug + '--' + case_code
            identifier(slug)
            actors = {a['code']: a for a in case['flow']['speakers']}
            ai_voices = {v for a in actors.values() for v in [a['voiceId'], *a.get('voiceVariants', {}).values()]}
            if learner_voice in ai_voices:
                raise ValueError(f'{root_slug}: learner and AI must have different voices')
            dialogues = [{'code': 'example', 'title': case['title'],
                          'exampleDialogue': case['learning']['exampleDialogue']},
                         *case['learning'].get('branchDialogues', [])]
            for dialogue in dialogues:
                code = identifier(dialogue['code'])
                override_key = case_code + '/' + code
                overrides = mapping.get('sampleOverrides', {}).get(override_key, {})
                if overrides:
                    overrides_used.add(override_key)
                lines = []
                for source in dialogue['exampleDialogue']:
                    role = source['speaker']
                    text = source['text'].strip()
                    translation = source['translation'].strip()
                    # Provider control tags, hidden controls and excessive input are never recorded.
                    if not text or len(text) > 2000 or re.search(r'[\x00-\x08\x0b\x0c\x0e-\x1f<>]', text):
                        raise ValueError(f'{slug}/{code}: invalid recording text')
                    if not translation:
                        raise ValueError(f'{slug}/{code}: missing translation')
                    if role == 'ai':
                        seen_texts.add(text)
                        actor_code = overrides.get(text, mapping['aiLines'].get(text))
                        if actor_code not in actors:
                            raise ValueError(f'{slug}/{code}: Japanese line needs an explicit actor: {text}')
                        actor = actors[actor_code]
                        if not re.fullmatch(r'[0-9a-f]{32}', actor['voiceId']):
                            raise ValueError(f'{slug}/{code}: invalid actor voice')
                        lines.append(Line(role, text, translation, actor_code, actor['name'], actor['voiceId']))
                    elif role == 'learner':
                        lines.append(Line(role, text, translation, 'LEARNER',
                                          'Bạn · ' + group['learner_role']['name'], learner_voice))
                    else:
                        raise ValueError(f'{slug}/{code}: unknown dialogue role')
                if not lines or not any(l.speaker == 'ai' for l in lines) or not any(l.speaker == 'learner' for l in lines):
                    raise ValueError(f'{slug}/{code}: sample needs both sides of the dialogue')
                if not set(overrides) <= {l.text for l in lines if l.speaker == 'ai'}:
                    raise ValueError(f'{slug}/{code}: obsolete actor override')
                result.append(Sample(slug, code, dialogue['title'], tuple(lines)))
        if seen_texts != set(mapping['aiLines']):
            raise ValueError(f'{root_slug}: recording cast contains missing or obsolete Japanese lines')
        if overrides_used != set(mapping.get('sampleOverrides', {})):
            raise ValueError(f'{root_slug}: recording cast contains obsolete sample overrides')
    if len({sample.key for sample in result}) != len(result):
        raise ValueError('Duplicate sample recording key')
    return result


def pending_entry(sample, model, speed, gap):
    return {'title': sample.title, 'revision': sample.revision(model, speed, gap),
            'ready': False, 'duration': None, 'gap': gap,
            'lines': [line.public() for line in sample.lines]}
