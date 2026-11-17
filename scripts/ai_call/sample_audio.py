"""Validated, database-independent recording definitions for authored AI Call samples."""
from dataclasses import asdict, dataclass
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / 'data/ai-call/catalog.json'
CAST = ROOT / 'data/ai-call/sample-audio-cast.json'
# Learning-path (Giao tiếp) scenarios: one AI role and one example dialogue each, voiced per role.
CPATH_SCENARIOS = ROOT / 'data/learning-path/comm-aicall'
CPATH_CAST = ROOT / 'data/ai-call/sample-audio-cast-cpath.json'
CPATH_GOALS = ('TRAVEL', 'DAILY_LIFE', 'BA', 'WORK')
OUTPUT = ROOT / 'src/main/resources/static/audio/ai-call/samples'
WEB_PATH = '/audio/ai-call/samples/'
PIPELINE_VERSION = 2
SAMPLE_RATE = 44100


def digest(value):
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))
    return hashlib.sha256(encoded.encode('utf-8')).hexdigest()


def identifier(value):
    # Branch codes of the case variants use underscores (choose_udon_path); keys are never file names.
    if not isinstance(value, str) or not re.fullmatch(r'[a-z0-9][a-z0-9_-]{0,119}', value):
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


def cpath_definitions(scenarios_dir=None, cast=None):
    """The example dialogue of every cpath-* scenario, keyed '<slug>/example' like the sample page asks for."""
    scenarios_dir = Path(scenarios_dir) if scenarios_dir is not None else CPATH_SCENARIOS
    cast = cast if cast is not None else json.loads(CPATH_CAST.read_text(encoding='utf-8'))
    if cast.get('version') != 1:
        raise ValueError('Unsupported cpath recording cast version')
    voice = re.compile(r'[0-9a-f]{32}')
    result = []
    for goal in CPATH_GOALS:
        data = json.loads((scenarios_dir / f'{goal}.json').read_text(encoding='utf-8'))
        names = {(r['role_kind'], r['code']): r['name'] for r in data['roles']}
        for item in data['scenarios']:
            scenario = item['scenario']
            slug = identifier(scenario['slug'])
            ai_code, learner_code = item['ai_role_code'], item['learner_role_code']
            ai_voice = cast['roles'].get(ai_code)
            if not ai_voice or not voice.fullmatch(ai_voice):
                raise ValueError(f'{slug}: AI role {ai_code} needs a voice in the cpath recording cast')
            learner_voice = cast['learnerVoiceId'] if cast['learnerVoiceId'] != ai_voice else cast['alternateLearnerVoiceId']
            if learner_voice == ai_voice or not voice.fullmatch(learner_voice):
                raise ValueError(f'{slug}: learner and AI must have different voices')
            lines = []
            for source in (scenario.get('learning_content_json') or {}).get('exampleDialogue') or []:
                text, translation = source['text'].strip(), source['translation'].strip()
                if not text or len(text) > 2000 or re.search(r'[\x00-\x08\x0b\x0c\x0e-\x1f<>]', text):
                    raise ValueError(f'{slug}/example: invalid recording text')
                if not translation:
                    raise ValueError(f'{slug}/example: missing translation')
                if source['speaker'] == 'ai':
                    # The player accepts AI01..AI99 speaker codes; a cpath scenario has a single AI role.
                    lines.append(Line('ai', text, translation, 'AI01', names[('AI', ai_code)], ai_voice))
                elif source['speaker'] == 'learner':
                    lines.append(Line('learner', text, translation, 'LEARNER',
                                      'Bạn · ' + names[('LEARNER', learner_code)], learner_voice))
                else:
                    raise ValueError(f'{slug}/example: unknown dialogue role')
            if not lines:
                continue
            if not any(l.speaker == 'ai' for l in lines) or not any(l.speaker == 'learner' for l in lines):
                raise ValueError(f'{slug}/example: sample needs both sides of the dialogue')
            result.append(Sample(slug, 'example', scenario['title'], tuple(lines)))
    return result


def pending_entry(sample, model, speed, gap):
    return {'title': sample.title, 'revision': sample.revision(model, speed, gap),
            'ready': False, 'duration': None, 'gap': gap,
            'lines': [line.public() for line in sample.lines]}
