"""Generate the reviewable AI Call content migration from data/ai-call/catalog.json.

Run: python scripts/ai_call/generate_seed.py [--check]
No database access. Content upserts by slug/code and never deletes sessions.
"""
import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / 'data/ai-call/catalog.json'
SEED = ROOT / 'sql/migrations/015_ai_call_catalog_content.sql'


def validate(groups):
    if len(groups) != 14:
        raise ValueError('Expected 14 scenario groups')
    slugs = set()
    transferred_lessons = set()
    for group in groups:
        if group['level'] not in ('N5', 'N4', 'N3', 'N2'):
            raise ValueError('Invalid level')
        if len(group['cases']) != 3:
            raise ValueError(f"{group['slug']}: expected three cases")
        if len(group['learning']['vocabulary']) < 10 or len(group['learning']['grammar']) < 3:
            raise ValueError(f"{group['slug']}: insufficient shared learning content")
        terms = [v['term'] for v in group['learning']['vocabulary']]
        if len(set(terms)) != len(terms):
            raise ValueError(f"{group['slug']}: duplicate shared vocabulary")
        for case in group['cases']:
            slug = group['slug'] if case['code'] == 'standard' else group['slug'] + '--' + case['code']
            if slug in slugs or len(slug) > 120:
                raise ValueError(f'Duplicate/long slug: {slug}')
            slugs.add(slug)
            flow = case['flow']
            speakers = flow.get('speakers', [])
            if speakers:
                speaker_codes = {s['code'] for s in speakers}
                used_voices = set()
                if len(speaker_codes) != len(speakers): raise ValueError(f'{slug}: duplicate character')
                for speaker in speakers:
                    voices = {speaker['voiceId'], *speaker.get('voiceVariants', {}).values()}
                    if any(not re.fullmatch(r'[0-9a-f]{32}', voice) for voice in voices): raise ValueError(f'{slug}: invalid Fish Audio reference ID')
                    if used_voices & voices: raise ValueError(f'{slug}: two characters share a voice')
                    used_voices.update(voices)
                for state in flow['states']:
                    if state.get('speakerCode') not in speaker_codes or not state.get('openingLine'):
                        raise ValueError(f'{slug}: missing scene character/opening')
                for stage in flow.get('stages', []):
                    if len(stage.get('speakerCodes', [])) != len(stage['stateCodes']) or not set(stage['speakerCodes']) <= speaker_codes:
                        raise ValueError(f'{slug}: stage has invalid cast route')
                    if stage.get('speakers') != speakers: raise ValueError(f'{slug}: stage must snapshot its cast')
            goals = [g['code'] for g in flow['goals']]
            states = [s['code'] for s in flow['states']]
            if len(goals) < 3 or len(set(goals)) != len(goals) or len(set(states)) != len(states):
                raise ValueError(f'{slug}: invalid goals/states')
            if flow['initialState'] not in states:
                raise ValueError(f'{slug}: missing initial state')
            for goal in flow['goals']:
                if not goal['description']:
                    raise ValueError(f'{slug}: missing completion evidence')
                if goal['conditions'] != [{'type': 'OBJECTIVE_FLAG', 'value': goal['code']}]:
                    raise ValueError(f'{slug}: use semantic objective flags')
            graph = flow.get('version', 1) >= 2
            for state in flow['states']:
                if not graph and state['next'] not in states + ['END']:
                    raise ValueError(f'{slug}: broken transition')
                if not set(state['allowedGoalCodes']) <= set(goals):
                    raise ValueError(f'{slug}: unknown goal')
                if not state['prompt'] or not state['suggestedReplies']:
                    raise ValueError(f'{slug}: missing scene guidance')
            if graph:
                for skill in flow.get('skillTransfers', []):
                    if not set(skill['stateCodes']) <= set(states) or not skill['stateCodes']:
                        raise ValueError(f'{slug}: missing skill target state')
                    if not skill['pattern'] or not skill['example'] or not skill['translation']:
                        raise ValueError(f'{slug}: incomplete transferred skill')
                    transferred_lessons.update(skill['lessons'])
                edges = flow['edges']
                pairs = set()
                for edge in edges:
                    pair = (edge['source'], edge['target'])
                    if pair in pairs or not set(pair) <= set(states):
                        raise ValueError(f'{slug}: duplicate/broken graph edge')
                    pairs.add(pair)
                    if not set(edge.get('requiredGoalCodes', [])) <= set(goals):
                        raise ValueError(f'{slug}: unknown edge goal')
                    target_goals = next(s['allowedGoalCodes'] for s in flow['states'] if s['code'] == edge['target'])
                    if not set(edge.get('entryGoalCodes', [])) <= set(target_goals):
                        raise ValueError(f'{slug}: invalid entry goal')
                reached = {flow['initialState']}
                while True:
                    extended = reached | {e['target'] for e in edges if e['source'] in reached}
                    if extended == reached: break
                    reached = extended
                if reached != set(states): raise ValueError(f'{slug}: unreachable graph node')
                terminals = set(flow['terminalStateCodes'])
                if not terminals <= set(states) or any(e['source'] in terminals for e in edges):
                    raise ValueError(f'{slug}: invalid terminal')
                can_end = set(terminals)
                while True:
                    extended = can_end | {e['source'] for e in edges if e['target'] in can_end}
                    if extended == can_end: break
                    can_end = extended
                if can_end != set(states): raise ValueError(f'{slug}: node cannot reach an ending')
                for path in flow['paths']:
                    if any(pair not in pairs for pair in zip(path['stateCodes'], path['stateCodes'][1:])):
                        raise ValueError(f'{slug}: invalid preview path')
                    if flow.get('storyReview',{}).get('completePaths'):
                        route=path['stateCodes']
                        if route[0]!=flow['initialState'] or route[-1] not in terminals:
                            raise ValueError(f'{slug}: unfinished story path {path["code"]}')
                        if not set(flow['storyReview']['closureStateCodes'])<=set(route):
                            raise ValueError(f'{slug}: story lacks follow-through {path["code"]}')
                stages=flow.get('stages',[])
                stage_codes={s['code'] for s in stages}
                if len(stage_codes)!=len(stages):raise ValueError(f'{slug}: duplicate stage code')
                for stage in stages:
                    route=stage['stateCodes']
                    if route[0]!=flow['initialState'] or route[-1] not in terminals:
                        raise ValueError(f'{slug}: stage must have a full route')
                    if any(pair not in pairs for pair in zip(route,route[1:])):
                        raise ValueError(f'{slug}: stage uses a missing edge')
                    if not set(stage['requiredGoalCodes'])<=set(goals) or not set(stage['prerequisites'])<=stage_codes:
                        raise ValueError(f'{slug}: unknown stage goal/prerequisite')
                unlocked=set()
                while True:
                    next_unlocked=unlocked|{s['code'] for s in stages if set(s['prerequisites'])<=unlocked}
                    if next_unlocked==unlocked:break
                    unlocked=next_unlocked
                if unlocked!=stage_codes:raise ValueError(f'{slug}: cyclic stage prerequisites')
                if flow.get('storyReview',{}).get('completePaths'):
                    sample_codes={p['code'] for p in case['learning'].get('branchDialogues',[]) if p.get('storyComplete')}
                    if sample_codes!={p['code'] for p in flow['paths']}:
                        raise ValueError(f'{slug}: every complete story needs a reviewed dialogue sample')
            dialogue = case['learning']['exampleDialogue']
            if len(dialogue) < 3 or dialogue[0]['speaker'] != 'ai':
                raise ValueError(f'{slug}: missing opening/dialogue')
            if any(not d['text'] or not d['translation'] for d in dialogue):
                raise ValueError(f'{slug}: untranslated dialogue')
            if not flow['endings']['success']['message']:
                raise ValueError(f'{slug}: missing ending')
            practices = case['learning'].get('branchDialogues', [])
            if len({p['code'] for p in practices}) != len(practices):
                raise ValueError(f'{slug}: duplicate branch sample')
            for practice in practices:
                dialogue = practice['exampleDialogue']
                if not practice['title'] or len(dialogue) < 2 or dialogue[0]['speaker'] != 'ai':
                    raise ValueError(f'{slug}: invalid branch sample')
                if any(d['speaker'] not in ('ai', 'learner') or not d['text'] or not d['translation'] for d in dialogue):
                    raise ValueError(f'{slug}: untranslated branch sample')
                if not graph or practice['code'] not in {p['code'] for p in flow['paths']}:
                    raise ValueError(f'{slug}: sample has no graph preview')
        if group['cases'][0]['code'] != 'standard':
            raise ValueError('First case must be standard')
    if transferred_lessons != set(range(1,51)):
        raise ValueError('Expected communication skills mapped from all 50 lessons')
    return groups


def quote(value):
    if value is None:
        return 'NULL'
    if isinstance(value, (dict, list)):
        value = json.dumps(value, ensure_ascii=False, separators=(',', ':'))
    return "'" + str(value).replace('\\', '\\\\').replace("'", "''") + "'"


def render(groups):
    lines = [
        '-- [JLAS-W0205] Generated by scripts/ai_call/generate_seed.py; edit catalog.json.',
        '-- Apply after the base seed and migrations 010/013/014; then migration 016 and ai_call_scoring_rules.sql.',
        '-- 14 roots (standard cases) + 28 alternate cases = 42 playable cases.',
        '-- All 14 roots use connected version 2 graphs; 50 lessons map to shared communication skills.',
        '-- Uses the currently selected database; no hard-coded database name.',
        'SET NAMES utf8mb4;', 'START TRANSACTION;',
    ]
    for group in groups:
        lines.append('\n-- ' + group['title'])
        for kind in ('ai', 'learner'):
            role = group[kind + '_role']
            lines.append('INSERT INTO scenario_role (role_kind,code,name,description,personality,speech_style) VALUES ('
                         + ','.join(quote(x) for x in [kind.upper() if kind == 'ai' else 'LEARNER',
                                    role['code'], role['name'], role['description'],
                                    role.get('personality'), role.get('speech_style')])
                         + ') ON DUPLICATE KEY UPDATE name=VALUES(name),description=VALUES(description),'
                           'personality=VALUES(personality),speech_style=VALUES(speech_style);')
        for case in group['cases']:
            standard = case['code'] == 'standard'
            slug = group['slug'] if standard else group['slug'] + '--' + case['code']
            learning = dict(case['learning'])
            if standard:
                learning['vocabulary'] = group['learning']['vocabulary']
                learning['grammar'] = group['learning']['grammar']
            parent = 'NULL' if standard else 'p.id'
            # Each case is a Scenario so all existing session/chat/score APIs retain
            # the selected case ID without inventing a second conversation engine.
            title = group['title'] if standard else group['title'] + ' — ' + case['title']
            values = [parent, quote(case['title']),
                      '(SELECT id FROM category WHERE code=' + quote(group['category']) + ')',
                      '(SELECT id FROM scenario_role WHERE code=' + quote(group['ai_role']['code']) + ')',
                      '(SELECT id FROM scenario_role WHERE code=' + quote(group['learner_role']['code']) + ')',
                      "(SELECT id FROM prompt_template WHERE code='scenario_practical_v2')",
                      quote(title), quote(slug), quote(case['description']), quote(group['level']),
                      quote(group['difficulty']), str(group['minutes']), quote(case['background']),
                      quote(group['location']), quote(group['time']), quote(group['culture_note']),
                      quote(case['flow']), quote(learning), quote(group.get('thumbnail')), '1']
            columns = ['parent_scenario_id','variant_title','category_id','ai_role_id','learner_role_id',
                       'prompt_template_id','title','slug','description','jlpt_level','difficulty',
                       'estimated_minutes','background','location','time_of_day','culture_note',
                       'flow_json','learning_content_json','thumbnail_url','is_active']
            insertion = ' VALUES (\n    ' + ','.join(values) + ')' if standard else (
                ' SELECT\n    ' + ','.join(values) + ' FROM scenario p WHERE p.slug=' + quote(group['slug']))
            lines.append('INSERT INTO scenario (' + ','.join(columns) + ')' + insertion + '\nON DUPLICATE KEY UPDATE '
                         + ','.join(c + '=VALUES(' + c + ')' for c in columns if c not in ('slug', 'is_active')) + ';')
            if not standard:
                lines.append('INSERT IGNORE INTO scenario_evaluation_rule (scenario_id,rule_id) '
                             'SELECT child.id,r.rule_id FROM scenario child JOIN scenario parent ON '
                             'parent.id=child.parent_scenario_id JOIN scenario_evaluation_rule r ON '
                             'r.scenario_id=parent.id WHERE child.slug=' + quote(slug) + ';')
    lines += ['COMMIT;', '']
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true', help='Validate catalog and require SQL to match')
    args = parser.parse_args()
    groups = validate(json.loads(CATALOG.read_text(encoding='utf-8'))['scenarios'])
    sql = render(groups)
    if args.check:
        if not SEED.exists() or SEED.read_text(encoding='utf-8') != sql:
            raise SystemExit('Generated SQL is stale; run generate_seed.py')
    else:
        SEED.write_text(sql, encoding='utf-8')
    print('Validated 14 scenario groups, 42 practice cases and their dialogue flows.')


if __name__ == '__main__':
    main()
