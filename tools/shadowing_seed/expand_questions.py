"""Cover reviewed speech with distinct listening exercises, without a hosted model.

One GAP fact per cue, matching the application's duplicate policy. Existing
editorial questions/IDs are retained. Fugashi proposes word boundaries only;
answers, translations and timing always come from the reviewed transcript.
"""
from __future__ import annotations
from collections import Counter
import json
import re
import fugashi
from validate import DATA, key, japanese, load_payload, materialize, read_json, spoken, validate

METHOD = "OFFLINE_TRANSCRIPT_GAP_V1"
# Placeholders and symbols a learner cannot type from what they hear (〇〇 = "something").
UNREADABLE = re.compile(r'[〇○◯●×※]')
QUOTES = ('「」', '『』', '（）', '()')


def typeable(answer):
    """An answer must be typed from audio: no placeholder symbols and no quote cut in half."""
    return not UNREADABLE.search(answer) and all(answer.count(o) == answer.count(c) for o, c in QUOTES)


def katakana(char):
    return 'ァ' <= char <= 'ヺ' or char == 'ー'


# A word of the word-level furigana that holds kanji: 秘匿(ひとく), 達し(たっし), 3名(さんめい).
FURIGANA_WORD = re.compile(r'(?<!\S)([^\s()（）]*[㐀-鿿々〆ヵヶ][^\s()（）]*)[(（]([^)）]+)[)）]')


def cuts_furigana_word(row, start, end):
    """The app reads a blank's kana from whole furigana words; half of one (秘匿 of 秘匿死刑) has no reading."""
    text, cursor = row['text'], 0
    for match in FURIGANA_WORD.finditer(row.get('furigana') or ''):
        begin = text.find(match.group(1), cursor)
        if begin < 0:
            continue
        cursor = begin + len(match.group(1))
        if begin < start < cursor or begin < end < cursor:
            return True
    return False


def splits_katakana_word(text, start, end):
    """Fugashi splits unknown names (フリー|レン); a blank must not cut such a word."""
    return (0 < start and katakana(text[start - 1]) and katakana(text[start])) \
        or (end < len(text) and katakana(text[end - 1]) and katakana(text[end]))


def duplicate(answer, existing):
    a = key(answer)
    return any(a == b or (min(len(a), len(b)) >= 6 and (a in b or b in a)) for b in existing)


def candidates(row, tagger):
    text = row['text']
    tokens = []
    cursor = 0
    for token in tagger(text):
        surface = str(token)
        start = text.index(surface, cursor)
        cursor = start + len(surface)
        tokens.append((start, cursor, token.feature.pos1))
    n = len(spoken(text))
    result = {'EASY': [], 'MEDIUM': [], 'HARD': []}
    # Meaningful content words; never hide punctuation or a lone grammar particle.
    for start, end, pos in tokens:
        value = text[start:end]
        if pos in ('名詞', '動詞', '形容詞', '副詞') and japanese(value) >= 2:
            if len(value) <= max(4, -(-n * 45 // 100)):
                result['EASY'].append((start, end))
    # A connected phrase bounded by actual token boundaries, not arbitrary characters.
    for i, (start, _, pos) in enumerate(tokens):
        if pos not in ('名詞', '代名詞', '動詞', '形容詞', '副詞', '連体詞'):
            continue
        for j in range(i + 1, min(len(tokens), i + 15)):
            end = tokens[j][1]
            value = text[start:end]
            if re.search(r'[。!?！？、…\[\]【】]', value):
                break
            if 5 <= len(value) < n and n * .30 <= len(value) <= n * .65:
                result['MEDIUM'].append((start, end))
    if 12 <= n <= 90 and row['end'] - row['start'] <= 18 and text in spoken(text):
        result['HARD'].append((0, len(text)))
    return result


def expand():
    document = read_json(DATA / 'questions.json')
    questions = [q for q in document['questions'] if q.get('authoring_method') not in (METHOD, 'EDITORIAL_COMPREHENSION_V7')]
    payload = load_payload()
    by_source = {}
    for row in payload:
        by_source.setdefault(row['source_key'], []).append(row)
    counters = Counter()
    additions = (DATA / 'editorial/comprehension_additions.tsv').read_text(encoding='utf-8').splitlines()[1:]
    for spec in additions:
        if not spec.strip():
            continue
        source, kind, level, orders, answer, prompt, distractors, explanation = spec.split('|')
        nums = [int(n) for n in orders.split('-')]
        first, last = nums[0], nums[-1]
        rows = by_source[source]
        evidence = [r for r in rows if first <= r['order'] <= last]
        anchor = next(r for r in evidence if answer in r['text'])
        counters[source] += 1
        questions.append(dict(key=f'{source}:comp:{counters[source]:04}', source_key=source,
            kind=kind, level=level, first_order=first, last_order=last, answer_order=anchor['order'],
            answer_start=anchor['text'].index(answer) + 1, answer_length=len(answer), prompt=prompt,
            distractors=distractors.split(';'), correct_position=(counters[source] % 4) + 1,
            explanation_vi=explanation, anchor_start=evidence[0]['start'], anchor_end=evidence[-1]['end'],
            authoring_method='EDITORIAL_COMPREHENSION_V7'))
    tagger = fugashi.Tagger()
    coverage = []
    for video in read_json(DATA / 'catalog.json')['videos']:
        source = video['source_key']
        rows = by_source.get(source, [])
        old = [materialize(q, rows) for q in questions if q['source_key'] == source and q['kind'] == 'GAP']
        occupied = {q['answer_order'] for q in old}
        answers = [key(q['answer']) for q in old]
        excluded = []
        added = 0
        for row in rows:
            order = row['order']
            if order in occupied:
                continue
            text = row['text']
            reason = None
            if row.get('requires_audio_review') or re.search(r'[\[\]【】�♫♪]', text):
                reason = 'UNVERIFIED_OR_NON_SPEECH'
            elif re.search(r'([あいうえおぁぃぅぇぉアイウエオァィゥェォ])\1{2,}', text):
                reason = 'STRETCHED_SHOUT'
            elif len(spoken(text)) < 5 or japanese(text) < 4:
                reason = 'TOO_SHORT'
            if reason:
                excluded.append({'order': order, 'reason': reason})
                continue
            choices = candidates(row, tagger)
            # Distribute levels while falling back to any valid, distinct exercise.
            preferred = ('EASY', 'MEDIUM', 'HARD')[order % 3]
            levels = [preferred] + [lv for lv in ('HARD', 'MEDIUM', 'EASY') if lv != preferred]
            chosen = None
            for level in levels:
                spans = choices[level]
                spans.sort(key=lambda span: (-len(text[span[0]:span[1]]), span[0]))
                for start, end in spans:
                    answer = text[start:end]
                    visible = text[:start] + text[end:]
                    if answer not in spoken(text) or spoken(text).count(answer) != 1 or not typeable(answer) \
                            or splits_katakana_word(text, start, end) or cuts_furigana_word(row, start, end):
                        continue
                    if level != 'HARD' and (japanese(visible) < 4 or answer in visible
                            or answer in '音声を聞いて、空欄に入る言葉は何ですか。'):
                        continue
                    if duplicate(answer, answers):
                        continue
                    chosen = (level, start, end, answer)
                    break
                if chosen:
                    break
            if not chosen:
                excluded.append({'order': order, 'reason': 'NO_DISTINCT_VALID_GAP'})
                continue
            level, start, end, answer = chosen
            instruction = 'Chép lại toàn bộ câu' if level == 'HARD' else 'Cụm cần nghe và điền'
            questions.append(dict(key=f'{source}:gap:{order:04}', source_key=source, kind='GAP', level=level,
                first_order=order, last_order=order, answer_order=order, answer_start=start + 1,
                answer_length=end - start, prompt='', distractors=[], correct_position=1,
                explanation_vi=f'{instruction}: 「{answer}」. Nghĩa của câu: {row["translation"]}',
                anchor_start=row['start'], anchor_end=row['end'], authoring_method=METHOD))
            answers.append(key(answer))
            added += 1
        coverage.append(dict(source_key=source, cues=len(rows), existing_gap_cues=len(occupied),
            added_gap_cues=added, excluded_cues=excluded))
    document['version'] = 'shadowing-questions-20261012-v7'
    document['questions'] = questions
    target = DATA / 'questions.json'
    previous = target.read_bytes()
    target.write_text(json.dumps(document, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    try:
        report = validate(payload, require_enrichment=True)
    except Exception:
        target.write_bytes(previous)
        raise
    report.update(method=METHOD, coverage=coverage,
        note='Coverage is exhaustive for this one-distinct-GAP-per-cue policy; comprehension is editorial, not an absolute mathematical maximum.')
    (DATA / 'question_coverage.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps({k: v for k, v in report.items() if k != 'coverage'}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    expand()
