"""Audit extracted master workbooks against local seed/assets; stage facts, never approve content.

python reconcile_handoff.py AUDIT_DIRECTORY REPOSITORY
Reads source-tables.json produced by audit_handoff.py. Does not connect to a database.
"""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import re


def sql_values(line):
    """Parse one generated VALUES tuple without eval/SQL execution; reject unfamiliar syntax."""
    text = line.strip().rstrip(',;').strip()
    if not (text.startswith('(') and text.endswith(')')):
        raise ValueError('Expected a single VALUES tuple')
    text, result, i = text[1:-1], [], 0
    while i < len(text):
        while i < len(text) and text[i].isspace(): i += 1
        if text[i] == "'":
            i += 1
            chars = []
            while i < len(text):
                char = text[i]; i += 1
                if char == '\\':
                    escaped = text[i]; i += 1
                    chars.append({'n': '\n', 'r': '\r', 't': '\t', '0': '\0'}.get(escaped, escaped))
                elif char == "'":
                    if i < len(text) and text[i] == "'":
                        chars.append("'"); i += 1
                    else: break
                else: chars.append(char)
            else: raise ValueError('Unterminated string')
            value = ''.join(chars)
        else:
            start = i
            while i < len(text) and text[i] != ',': i += 1
            token = text[start:i].strip()
            if token == 'NULL': value = None
            elif re.fullmatch(r'-?\d+', token): value = int(token)
            elif re.fullmatch(r'-?\d+\.\d+', token): value = float(token)
            else: raise ValueError('Unsupported SQL token: ' + token[:50])
        result.append(value)
        while i < len(text) and text[i].isspace(): i += 1
        if i < len(text):
            if text[i] != ',': raise ValueError('Expected separator')
            i += 1
    return result


def read_seed(path, wanted):
    tables = defaultdict(list)
    table, columns = None, []
    with path.open(encoding='utf-8-sig') as stream:
        for number, line in enumerate(stream, 1):
            match = re.match(r'INSERT INTO (\w+) \((.+)\) VALUES', line)
            if match:
                table = match[1] if match[1] in wanted else None
                columns = [column.strip(' `') for column in match[2].split(',')]
            elif table and line.lstrip().startswith('('):
                values = sql_values(line)
                if len(values) != len(columns): raise ValueError(f'{path}:{number}: column count mismatch')
                tables[table].append(dict(zip(columns, values)))
                if line.rstrip().endswith(';'): table = None
    return tables


def records(rows):
    """Contract tabs use row 4; auxiliary tabs use row 1. Retain exact source row."""
    contract = next((values for row, values in rows if row == 4 and any(str(v).endswith('*') for v in values)), None)
    header_row, header = (4, contract) if contract else rows[0]
    keys = [str(v).rstrip('*') if v is not None else '' for v in header]
    return [dict({'_row': row}, **{key: values[i] if i < len(values) else None
                                 for i, key in enumerate(keys) if key})
            for row, values in rows if row > header_row]


def identity_columns(title, required):
    return {'TOPIC_LEVEL': ['topic_code', 'level_code'],
            'LESSON_SCOPE': ['lesson_code', 'goal_code', 'topic_code', 'competency_code', 'level_code'],
            'READINESS': ['entity_type', 'external_key']}.get(title, required[:1])


def select_learning_workbook(tables):
    """Locate the learning workbook by contract, not a dated filename; reject divergent duplicates."""
    required = {'LESSON', 'VOCABULARY', 'AUDIO'}
    candidates = [(name, book) for name, book in tables.items()
                  if name.lower().endswith('.xlsx') and required.issubset(book)]
    if not candidates:
        raise ValueError('No learning workbook with LESSON/VOCABULARY/AUDIO sheets')
    variants = {json.dumps(book, ensure_ascii=False, sort_keys=True, default=str) for _, book in candidates}
    if len(variants) > 1:
        raise ValueError('Conflicting learning workbooks found; choose and audit one version explicitly')
    return sorted(candidates, key=lambda candidate: candidate[0])[0][1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('audit', type=Path); parser.add_argument('repo', type=Path)
    args = parser.parse_args()
    output_directory = args.audit / 'reconciled'
    output_directory.mkdir(exist_ok=False)
    tables = json.loads((args.audit / 'source-tables.json').read_text(encoding='utf-8'))
    seed_path = args.repo / 'db/seed_data_jlas.sql'
    seed = read_seed(seed_path, {'vocabulary_entry', 'content_audio', 'tag'})
    report = {'production_ready': False, 'seed_sha256': hashlib.sha256(seed_path.read_bytes()).hexdigest(),
              'workbooks': {}, 'vocabulary_identity_candidates': [], 'audio_evidence': [],
              'canonical_tag_catalog': seed['tag']}
    for name, book in tables.items():
        if not name.endswith('.xlsx'): continue
        sheets = {name: records(rows) for name, rows in book.items() if name not in ('README', 'CODE_LISTS')}
        issues, missing, placeholders = [], [], []
        for title, rows in sheets.items():
            header = next((values for row, values in book[title] if row == 4 and any(str(v).endswith('*') for v in values)), [])
            required = [v[:-1] for v in header if isinstance(v, str) and v.endswith('*')]
            for row in rows:
                for field in required:
                    if row.get(field) is None or str(row[field]).strip() == '': missing.append([title, row['_row'], field])
                for field, value in row.items():
                    if isinstance(value, str) and value.strip().lower() in ('-', 'undefined', '(nghĩa cần nhập)', '(cần nhập nghĩa)'):
                        placeholders.append([title, row['_row'], field, value])
            if required:
                identity = identity_columns(title, required)
                # Prefer the explicit external key for scope/level rows when the workbook provides one.
                if title in ('TOPIC_LEVEL', 'LESSON_SCOPE') and 'external_key' in required:
                    identity = ['external_key']
                keys = Counter(tuple(row.get(column) for column in identity) for row in rows)
                issues.extend([title, list(key), count] for key, count in keys.items() if any(v is not None for v in key) and count > 1)
        stats = {'counts': {title: len(rows) for title, rows in sheets.items()},
                 'missing_required': missing, 'placeholder_cells': placeholders, 'duplicate_keys': issues}
        if 'QUESTION' in sheets:
            stats['assessment_coverage'] = dict(Counter(f"{r.get('jlpt_level')}:{r.get('skill')}" for r in sheets['QUESTION']))
            options = defaultdict(list)
            for option in sheets['OPTION']: options[option['question_code']].append(option)
            stats['mcq_issues'] = []
            for question in sheets['QUESTION']:
                if question['question_type'] != 'MULTIPLE_CHOICE': continue
                opts = options[question['question_code']]
                correct = [o for o in opts if str(o.get('is_correct')).lower() == 'true']
                if len(opts) != 4 or len(correct) != 1 or str(correct[0]['option_text']) != str(question['correct_answer']):
                    stats['mcq_issues'].append(question['question_code'])
        if 'LESSON' in sheets:
            exercise_lessons = {r['lesson_code'] for r in sheets['EXERCISE']}
            stats['lessons_without_exercise'] = [r['lesson_code'] for r in sheets['LESSON'] if r['lesson_code'] not in exercise_lessons]
            stats['listening_without_audio'] = [r['listening_code'] for r in sheets['LISTENING'] if not r.get('audio_code')]
            stats['published_unreviewed_lessons'] = [r['lesson_code'] for r in sheets['LESSON']
                if r.get('lifecycle_status') == 'PUBLISHED' and r.get('review_status') != 'APPROVED']
        report['workbooks'][name] = stats
    learning_book = select_learning_workbook(tables)
    latest = {name: records(rows) for name, rows in learning_book.items()
              if name not in ('README', 'CODE_LISTS')}
    by_expression = defaultdict(list)
    for row in seed['vocabulary_entry']: by_expression[row['expression']].append(row)
    for row in latest['VOCABULARY']:
        if 'undefined' not in str(row.get('vocab_code')): continue
        matches = [r for r in by_expression[row['expression']] if r['meaning'] == row['meaning_vi']
                   and (not row.get('reading') or row['reading'] == r['reading'])]
        report['vocabulary_identity_candidates'].append({'source_row': row['_row'], 'expression': row['expression'],
            'matches': [{k: r[k] for k in ('id', 'expression', 'reading', 'meaning')} for r in matches],
            'status': 'UNIQUE_SEED_MATCH_REVIEW_MAPPING' if len(matches) == 1 else 'AMBIGUOUS_OR_MISSING',
            'ready_for_import': False})
    file_index = defaultdict(list)
    for base in ('uploads/content-audio', 'src/main/resources/static/audio'):
        for file in (args.repo / base).rglob('*'):
            if file.is_file() and file.suffix.lower() in ('.mp3', '.wav', '.ogg', '.m4a'):
                file_index[file.name].append(file)
    canonical_audio = {r['id']: r for r in seed['content_audio']}
    file_hashes = {}
    supplements = []
    for row in latest['AUDIO']:
        identity = str(row.get('external_key', '')).removeprefix('db:content_audio:')
        original = canonical_audio.get(int(identity)) if identity.isdigit() else None
        uri = str(row.get('storage_uri') or '')
        files = file_index.get(Path(uri).name, [])
        for file in files:
            if file not in file_hashes: file_hashes[file] = hashlib.sha256(file.read_bytes()).hexdigest()
        report['audio_evidence'].append({'audio_code': row['audio_code'], 'storage_uri': uri,
            'seed': original, 'matching_files': [str(p.relative_to(args.repo)) for p in files],
            'status': 'FILE_FOUND_NOT_PLAYBACK_VERIFIED' if files else 'FILE_NOT_FOUND_IN_CHECKED_ROOTS',
            'merged_clip_offsets_required': bool(original and uri == original.get('merged_url')),
            'ready_for_import': False})
        if original and len(files) == 1:
            individual = (args.repo / 'uploads/content-audio' / original['audio_url']).resolve()
            audio_root = (args.repo / 'uploads/content-audio').resolve()
            individual_exists = audio_root in individual.parents and individual.is_file()
            preferred = individual if individual_exists else files[0]
            if preferred not in file_hashes: file_hashes[preferred] = hashlib.sha256(preferred.read_bytes()).hexdigest()
            supplements.append({'audio_code': row['audio_code'], 'source_external_key': row['external_key'],
                'source_seed_sha256': report['seed_sha256'], 'storage_uri': preferred.relative_to(args.repo).as_posix(),
                'checksum_sha256': file_hashes[preferred], 'file_bytes': preferred.stat().st_size,
                'is_individual_clip': individual_exists,
                'merged_storage_uri': files[0].relative_to(args.repo).as_posix(),
                'start_ms': original['start_ms'], 'end_ms': original['end_ms'],
                'owner_type': original['owner_type'], 'owner_id': original['owner_id'],
                'file_exists': True, 'playback_verified': False, 'license_verified': False, 'ready_for_import': False})
    report['unique_local_audio_files'] = len(file_hashes)
    for name, value in [('reconciliation.json', report), ('canonical-tag-catalog.json', seed['tag']),
                        ('audio-link-supplement.json', supplements)]:
        output = output_directory / name
        if output.exists(): raise ValueError('Refusing to overwrite ' + str(output))
        output.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'workbooks': len(report['workbooks']), 'tag_catalog': len(seed['tag']),
        'vocabulary_candidates': dict(Counter(r['status'] for r in report['vocabulary_identity_candidates'])),
        'audio': dict(Counter(r['status'] for r in report['audio_evidence'])),
        'merged_clip_rows': sum(r['merged_clip_offsets_required'] for r in report['audio_evidence'])}))


if __name__ == '__main__': main()
