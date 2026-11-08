"""Builds the Onyomi (Hán Việt → âm On) curriculum SQL from onyomi_rules.json.

    python scripts/onyomi/generate_onyomi.py          # lint, then write sql/seeds/onyomi_canonical.sql
    python scripts/onyomi/generate_onyomi.py --lint   # lint only

The lint splits every Hán Việt syllable into initial / rhyme / final and checks that each example
really belongs to its rule, and that its reading has the shape the rule teaches. Questions are
generated deterministically (fixed seed), so re-running gives the same SQL.

Every question has the same shape: read an example word from its Hán Việt ("Từ 外国 (Ngoại Quốc) đọc
là gì?"), four readings in hiragana that differ only in the kanji the rule is about. Each lesson gets a LESSON quiz
of these questions, one per example and exception; each unit gets a UNIT_FINAL quiz drawn from the
LESSON quizzes of its lessons. The old compound-word quizzes ("Ghép từ", RULE_PRACTICE) are removed.
The Onyomi section of sql/seeds/jlas_seed_all.sql is refreshed with the same SQL.
"""
import copy
import json
import os
import random
import sys
import unicodedata

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, '..', '..'))
SOURCE = os.path.join(HERE, 'onyomi_rules.json')
OUTPUT = os.path.join(ROOT, 'sql', 'seeds', 'onyomi_canonical.sql')
SEED_ALL = os.path.join(ROOT, 'sql', 'seeds', 'jlas_seed_all.sql')
SEED = 20260926
PRACTICE_SEED = SEED + 2

# ── Kana ────────────────────────────────────────────────────────────────────
VOWELS = 'aiueo'
ROWS = {
    '': 'アイウエオ', 'k': 'カキクケコ', 'g': 'ガギグゲゴ', 's': 'サシスセソ', 'z': 'ザジズゼゾ',
    't': 'タチツテト', 'd': 'ダ..デド', 'n': 'ナニヌネノ', 'h': 'ハヒフヘホ', 'b': 'バビブベボ',
    'p': 'パピプペポ', 'm': 'マミムメモ', 'y': 'ヤ.ユ.ヨ', 'r': 'ラリルレロ', 'w': 'ワ....',
}
SMALL = {'a': 'ャ', 'u': 'ュ', 'o': 'ョ'}
SMALL_VOWEL = {'ャ': 'a', 'ュ': 'u', 'ョ': 'o'}
KANA_POS = {ch: (row, VOWELS[i]) for row, chars in ROWS.items() for i, ch in enumerate(chars) if ch != '.'}

ROMAJI = {}
for row, chars in ROWS.items():
    for i, ch in enumerate(chars):
        if ch != '.':
            ROMAJI[row + VOWELS[i]] = ch
ROMAJI.update({'shi': 'シ', 'chi': 'チ', 'tsu': 'ツ', 'fu': 'フ', 'ji': 'ジ', 'wo': 'ヲ'})
for row in 'kgnhbpmr':
    for v in 'auo':
        ROMAJI[row + 'y' + v] = ROWS[row][1] + SMALL[v]
for head, base in (('sh', 'シ'), ('ch', 'チ'), ('j', 'ジ')):
    for v in 'auo':
        ROMAJI[head + v] = base + SMALL[v]
for bad in ('si', 'ti', 'tu', 'hu', 'zi'):
    ROMAJI.pop(bad, None)

KANA_ROMAJI = {}
for rom, kana in sorted(ROMAJI.items(), key=lambda item: -len(item[0])):
    KANA_ROMAJI.setdefault(kana, rom)


def to_katakana(romaji):
    """Hepburn romaji (kou, gakkou, annai) → katakana. Raises on anything it cannot read."""
    text, out, i = romaji.lower(), [], 0
    while i < len(text):
        ch = text[i]
        if ch == 'n' and (i + 1 == len(text) or text[i + 1] not in 'aiueoy'):
            out.append('ン')
            i += 1
            continue
        if i + 1 == len(text) and ch not in 'aiueon':
            # a segment such as 'nik' in 'nik|ki' (日記) ends with the doubled consonant
            out.append('ッ')
            i += 1
            continue
        if i + 1 < len(text) and ch == text[i + 1] and ch not in 'aiueon':
            out.append('ッ')
            i += 1
            continue
        if ch == 't' and text[i + 1:i + 3] == 'ch':
            out.append('ッ')
            i += 1
            continue
        for size in (3, 2, 1):
            piece = text[i:i + size]
            if piece in ROMAJI:
                out.append(ROMAJI[piece])
                i += size
                break
        else:
            raise ValueError('cannot convert romaji %r (at %r)' % (romaji, text[i:]))
    return ''.join(out)


def to_romaji(kana):
    out, i = [], 0
    while i < len(kana):
        if kana[i] == 'ッ':
            nxt = to_romaji(kana[i + 1:i + 3])[:1]
            out.append(nxt)
            i += 1
            continue
        pair = kana[i:i + 2]
        if pair in KANA_ROMAJI:
            out.append(KANA_ROMAJI[pair])
            i += 2
        elif kana[i] == 'ン':
            out.append('n')
            i += 1
        else:
            out.append(KANA_ROMAJI[kana[i]])
            i += 1
    return ''.join(out)


def to_hiragana(katakana):
    return ''.join(chr(ord(c) - 0x60) if 'ァ' <= c <= 'ヶ' else c for c in katakana)


def first_mora(kana):
    """(row, vowel, palatal, rest) of the first mora, e.g. キョウ → ('k', 'o', True, 'ウ')."""
    base = kana[0]
    row, vowel = KANA_POS[base]
    if len(kana) > 1 and kana[1] in SMALL_VOWEL:
        return row, SMALL_VOWEL[kana[1]], True, kana[2:]
    return row, vowel, False, kana[1:]


def build_mora(row, vowel, palatal):
    if palatal and vowel in 'auo':
        if row in ('', 'y', 'w', 'd'):
            return None
        return ROWS[row][1] + SMALL[vowel]
    ch = ROWS[row][VOWELS.index(vowel)]
    return None if ch == '.' or ch == 'ヲ' else ch


ONSET_CODES = {
    'k': ('k', None), 'g': ('g', None), 's': ('s', None), 'z': ('z', None), 't': ('t', None),
    'd': ('d', None), 'n': ('n', None), 'h': ('h', None), 'b': ('b', None), 'p': ('p', None),
    'm': ('m', None), 'r': ('r', None), 'y': ('y', False), 'w': ('w', False), '-': ('', False),
    's0': ('s', False), 't0': ('t', False), 'sh': ('s', True), 'ch': ('t', True), 'j': ('z', True),
    'ky': ('k', True), 'gy': ('g', True), 'hy': ('h', True), 'ny': ('n', True), 'my': ('m', True),
    'ry': ('r', True), 'by': ('b', True), 'py': ('p', True),
}
TAILS = {'ku': 'ク', 'ki': 'キ', 'tsu': 'ツ', 'chi': 'チ', 'n': 'ン', 'u': 'ウ', 'i': 'イ'}
RHYMES = {'an': ('a', 'ン'), 'in': ('i', 'ン'), 'en': ('e', 'ン'), 'un': ('u', 'ン'),
          'ou': ('o', 'ウ'), 'uu': ('u', 'ウ'), 'ei': ('e', 'イ'), 'ai': ('a', 'イ')}


def with_onset(kana, code):
    row, vowel, palatal, rest = first_mora(kana)
    new_row, new_pal = ONSET_CODES[code]
    mora = build_mora(new_row, vowel, palatal if new_pal is None else new_pal)
    return None if mora is None else mora + rest


def with_tail(kana, tail):
    return kana[:-1] + TAILS[tail]


def with_rhyme(kana, rhyme):
    row, _, palatal, _ = first_mora(kana)
    vowel, tail = RHYMES[rhyme]
    mora = build_mora(row, vowel, palatal)
    return None if mora is None else mora + tail


# ── Hán Việt syllables ──────────────────────────────────────────────────────
INITIALS = ['ngh', 'ng', 'nh', 'ch', 'gh', 'gi', 'kh', 'ph', 'th', 'tr', 'qu',
            'b', 'c', 'd', 'đ', 'g', 'h', 'k', 'l', 'm', 'n', 'p', 'r', 's', 't', 'v', 'x']
FINALS = ['ng', 'nh', 'ch', 'c', 'm', 'n', 'p', 't']
TONE_MARKS = {'̀', '́', '̃', '̉', '̣'}


def strip_tone(text):
    decomposed = unicodedata.normalize('NFD', text.lower())
    return unicodedata.normalize('NFC', ''.join(c for c in decomposed if c not in TONE_MARKS))


def split_syllable(hv):
    """'Nguyên' → ('ng', 'iên', 'n'); 'Hoàng' → ('h', 'ang', 'ng'); 'Ý' → ('', 'i', '')."""
    s = strip_tone(hv)
    initial, rest = '', s
    for candidate in INITIALS:
        if s.startswith(candidate) and len(s) > len(candidate):
            initial, rest = candidate, s[len(candidate):]
            break
    rhyme = rest
    if len(rhyme) > 1 and rhyme[0] == 'o' and rhyme[1] in 'aăe':
        rhyme = rhyme[1:]
    elif len(rhyme) > 1 and rhyme[0] == 'u' and rhyme[1] in 'âêyơ' and initial != 'qu':
        rhyme = rhyme[1:]
    if rhyme.startswith('y'):
        rhyme = 'i' + rhyme[1:]
    final = next((f for f in FINALS if rhyme.endswith(f)), '')
    return initial, rhyme, final


ONSETS = ['ky', 'gy', 'sh', 'ch', 'ts', 'ny', 'hy', 'by', 'py', 'my', 'ry',
          'j', 'k', 'g', 's', 'z', 't', 'd', 'n', 'h', 'f', 'b', 'p', 'm', 'y', 'r', 'w']


def onset_of(romaji):
    if romaji[0] in VOWELS:
        return '-'
    return next(o for o in ONSETS if romaji.startswith(o))


# ── Data model ──────────────────────────────────────────────────────────────
class Example:
    """A kanji of a rule. `practice` marks one that is not on the flashcards: it only shows up in the
    practice questions, so the learner applies the rule to a kanji they have not seen."""

    def __init__(self, rule, row, practice=False):
        self.rule = rule
        self.practice = practice
        (self.kanji, self.hv, self.romaji, self.alts, self.word, self.word_reading,
         self.meaning, self.jlpt) = row[:8]
        self.variant = row[8] if len(row) > 8 else ''
        self.kana = to_katakana(self.romaji)
        self.alt_kana = [to_katakana(a) for a in self.alts]

    @property
    def all_kana(self):
        return {self.kana, *self.alt_kana}


class Exception_:
    def __init__(self, rule, data):
        self.rule = rule
        self.kanji, self.hv, self.romaji = data['kanji'], data['hv'], data['reading']
        self.alts, self.predicted = data.get('alts', []), data['predicted']
        self.word, self.word_reading, self.meaning = data['word'], data['word_reading'], data['meaning']
        self.note = data['note']
        self.kana = to_katakana(self.romaji)
        self.alt_kana = [to_katakana(a) for a in self.alts]


def rule_matches(rule, hv, romaji):
    """(vn_ok, on_ok) for a syllable/reading against a rule."""
    initial, rhyme, final = split_syllable(hv)
    kind = rule['kind']
    if kind == 'initial':
        vn_ok = initial in rule['vn']
        on_ok = onset_of(romaji) in rule['on']
    elif kind == 'final':
        vn_ok = final in rule['vn']
        on_ok = any(romaji.endswith(end) for end in rule['on'])
        if vn_ok and on_ok and rule['code'] == 'FIN_CH':
            on_ok = romaji.endswith('ku') if rhyme.endswith('ach') else romaji.endswith('ki')
    else:
        vn_ok = rhyme in rule['vn']
        on_ok = any(romaji.endswith(end) for end in rule['on'])
    return vn_ok, on_ok


def lint(data):
    errors, warnings = [], []
    readings = {}
    flashcards = {}
    for rule in data['rules']:
        rule['examples_'] = [Example(rule, row) for row in rule['examples']]
        rule['practice_'] = [Example(rule, row, practice=True) for row in rule.get('practice', [])]
        rule['exceptions_'] = [Exception_(rule, e) for e in rule.get('exceptions', [])]
        flashcards.setdefault(lesson_key(rule), set()).update(ex.kanji for ex in rule['examples_'])
    for rule in data['rules']:
        if len(rule['examples_']) < 2:
            errors.append('%s: needs at least 2 examples' % rule['code'])
        seen = set()
        for ex in rule['examples_'] + rule['practice_']:
            where = '%s %s (%s)' % (rule['code'], ex.kanji, ex.hv)
            if ex.kanji in seen:
                errors.append(where + ': duplicate kanji in rule')
            seen.add(ex.kanji)
            if ex.practice and ex.kanji in flashcards[lesson_key(rule)]:
                errors.append(where + ': practice kanji is already on the flashcards of this lesson')
            vn_ok, on_ok = rule_matches(rule, ex.hv, ex.romaji)
            if not vn_ok:
                errors.append(where + ': Hán Việt %r does not fit %s (parsed %r)'
                              % (ex.hv, rule['source'], split_syllable(ex.hv)))
            if not on_ok and not ex.variant:
                errors.append(where + ': reading %s does not have the %s shape' % (ex.romaji, rule['target']))
            if not on_ok and ex.variant:
                warnings.append(where + ': variant reading %s (%s)' % (ex.romaji, ex.variant))
            if ex.word and ex.kanji not in ex.word:
                errors.append(where + ': word %s does not contain the kanji' % ex.word)
            if ex.word and len(ex.word_reading.split('|')) != len(ex.word):
                errors.append(where + ': word reading %r must have one segment per kanji' % ex.word_reading)
            known = readings.setdefault(ex.kanji, (ex.hv, ex.romaji))
            if known != (ex.hv, ex.romaji):
                errors.append(where + ': inconsistent with an earlier entry %r' % (known,))
        for exc in rule['exceptions_']:
            where = '%s exception %s (%s)' % (rule['code'], exc.kanji, exc.hv)
            vn_ok, on_ok = rule_matches(rule, exc.hv, exc.romaji)
            if not vn_ok:
                errors.append(where + ': Hán Việt does not fit the rule, so it is not an exception of it')
            if on_ok:
                errors.append(where + ': reading follows the rule, so it is not an exception')
            for predicted in exc.predicted:
                if to_katakana(predicted) in {exc.kana, *exc.alt_kana}:
                    errors.append(where + ': distractor %s is a real reading' % predicted)
    # every question shows the Hán Việt of its word, so every kanji of every word needs one
    sources = rule_kanji(data['rules'])
    used = set()
    for rule in data['rules']:
        for ex in rule['examples_'] + rule['practice_'] + rule['exceptions_']:
            if not ex.word:
                continue
            where = '%s %s (%s) word %s' % (rule['code'], ex.kanji, ex.hv, ex.word)
            used.update(ch for ch in ex.word if ch not in sources)
            syllables = han_viet_of(ex.word, data, sources)
            if syllables is None:
                errors.append(where + ': no Hán Việt for %s; add it to han_viet'
                              % ''.join(ch for ch in ex.word if ch not in sources and ch not in data.get('han_viet', {})))
            elif len(syllables) != len(ex.word):
                errors.append(where + ': word_han_viet %r must have one syllable per kanji' % ' '.join(syllables))
            elif ex.kanji in ex.word and syllables[ex.word.index(ex.kanji)] != ex.hv:
                errors.append(where + ': the word gives %s the Hán Việt %s' % (ex.kanji, syllables[ex.word.index(ex.kanji)]))
    for ch in data.get('han_viet', {}):
        if ch in sources:
            errors.append('han_viet %s: already the kanji of a rule (%s); use word_han_viet for a word that reads it differently'
                          % (ch, sources[ch].hv))
        elif ch not in used:
            errors.append('han_viet %s: no example word uses it' % ch)
    return errors, warnings


def rule_kanji(rules):
    """Each kanji the rules teach, with its entry: examples first, then exceptions, then practice kanji."""
    sources = {}
    for rule in rules:
        for ex in rule['examples_']:
            sources.setdefault(ex.kanji, ex)
        for exc in rule['exceptions_']:
            sources.setdefault(exc.kanji, exc)
    for rule in rules:
        for ex in rule['practice_']:
            sources.setdefault(ex.kanji, ex)
    return sources


def han_viet_of(word, data, sources):
    """The Hán Việt of a word, one syllable per kanji (高校 → ['Cao', 'Hiệu']): its own entry in
    word_han_viet, else each kanji's from its rule or from han_viet. None when a kanji has neither."""
    if word in data.get('word_han_viet', {}):
        return data['word_han_viet'][word].split(' ')
    syllables = [sources[ch].hv if ch in sources else data.get('han_viet', {}).get(ch) for ch in word]
    return None if None in syllables else syllables


# ── Questions ───────────────────────────────────────────────────────────────
def display(kana):
    return '%s (%s)' % (kana, to_romaji(kana))


def lesson_key(rule):
    return rule['unit'], rule['lesson']


def taught_by(rule, rules):
    """Rules of the same kind taught up to this rule's lesson: this lesson first, then the earlier
    lessons, nearest first, since the course is learned in order."""
    taught = [r for r in rules if r['kind'] == rule['kind'] and lesson_key(r) <= lesson_key(rule)]
    return sorted(taught, key=lesson_key, reverse=True)


# target sounds of the rules (their 'on' lists) written the way each kind of change takes them
ONSET_OF_TARGET = {'ts': 't0', 'f': 'h'}
TAIL_OF_TARGET = {'n': 'n', 'ku': 'ku', 'ki': 'ki', 'tsu': 'tsu', 'chi': 'chi', 'uu': 'u', 'ou': 'u'}


def reading_distractors(ex, all_readings, rules, count=3):
    """Wrong readings that change only what the rule teaches (the initial, final or rhyme of the kanji)
    and keep the rest of the reading. They use first the sounds of rules already taught, nearest lesson
    first; the rule's own traps fill in when too few are taught yet (the first lesson teaches only K).
    None of them may be a sound the rule itself gives, so exactly one option follows the rule.
    Three, or up to `count` when spares are wanted."""
    rule = ex.rule
    taught = [target for r in taught_by(rule, rules) for target in r['on']]
    if rule['kind'] == 'initial':
        def codes(targets):
            return [ONSET_OF_TARGET.get(t, t) for t in targets]
        own_rows = {ONSET_CODES[c][0] for c in codes(rule['on'])} | {first_mora(ex.kana)[0]}

        def change(code):
            return None if ONSET_CODES[code][0] in own_rows else with_onset(ex.kana, code)
        # P comes last: after a doubled consonant (日記 にっき) only K, S, T and P can follow
        steps = codes(taught) + list(rule['traps']) + ['k', 'h', 's', 't', 'n', 'm', 'r', 'g', 'p']
    elif rule['kind'] == 'final':
        def tails(targets):
            return [TAIL_OF_TARGET.get(t, t) for t in targets]
        own = set(tails(rule['on'])) | {t for t, kana in TAILS.items() if ex.kana.endswith(kana)}

        def change(tail):
            return None if tail in own else with_tail(ex.kana, tail)
        steps = tails(taught) + list(rule['tails']) + list(TAILS)
    else:
        own = set(rule['on'])

        def change(rhyme):
            return None if rhyme in own else with_rhyme(ex.kana, rhyme)
        steps = [t for t in taught if t in RHYMES] + list(rule['rhymes']) + list(RHYMES)
    taken = set(all_readings.get(ex.kanji, set())) | ex.all_kana
    out = []
    for step in steps:
        kana = change(step)
        if kana and kana not in taken and kana not in out:
            out.append(kana)
        if len(out) >= count:
            break
    if len(out) < 3:
        raise ValueError('not enough distractors for %s %s' % (rule['code'], ex.kanji))
    return out[:count]


def word_segments(ex):
    segments = ex.word_reading.split('|')
    return [to_hiragana(to_katakana(s)) for s in segments]


def mcq(rng, correct, wrong):
    options = [correct] + list(wrong)
    rng.shuffle(options)
    return options, options.index(correct) + 1


class Question:
    def __init__(self, rule, ex, qtype, difficulty, text, answer, explanation, options, correct, context=None):
        self.rule, self.ex, self.qtype, self.difficulty = rule, ex, qtype, difficulty
        self.text, self.answer, self.explanation = text, answer, explanation
        self.options, self.correct = options, correct
        self.context = context


def word_parts(word_reading):
    """The reading of each kanji of an example word, in katakana: 'gai|koku' → ['ガイ', 'コク']."""
    return [to_katakana(s) for s in word_reading.split('|')]


def pronounceable(kana):
    # a doubled consonant (ッ) only comes before K, S, T or P; にっひ would give the answer away
    for i, ch in enumerate(kana):
        if ch == 'ッ':
            following = KANA_POS.get(kana[i + 1]) if i + 1 < len(kana) else None
            if following is None or following[0] not in ('k', 's', 't', 'p'):
                return False
    return True


def word_options(parts, index, traps, rng):
    """The four readings of a word: the right one and three that swap the part at `index` for a trap.
    None when fewer than three traps give a different, pronounceable word."""
    right = ''.join(parts)
    wrong = []
    for trap in traps:
        variant = ''.join(parts[:index] + [trap] + parts[index + 1:])
        if variant != right and variant not in wrong and pronounceable(variant):
            wrong.append(variant)
    if len(wrong) < 3:
        return None
    options, correct = mcq(rng, to_hiragana(right), [to_hiragana(k) for k in wrong[:3]])
    return to_hiragana(right), options, correct


def word_breakdown(word, parts, meaning, syllables):
    """外国 = 外 (Ngoại → がい) + 国 (Quốc → こく) → がいこく: nước ngoài."""
    steps = ' + '.join('%s (%s → %s)' % (ch, hv, to_hiragana(part)) for ch, hv, part in zip(word, syllables, parts))
    return '%s = %s → %s: %s.' % (word, steps, to_hiragana(''.join(parts)), meaning)


def word_prompt(word, syllables):
    """The question asks for the reading from the Hán Việt the rules start from, not from the meaning."""
    return 'Từ %s (%s) đọc là gì?' % (word, ' '.join(syllables))


def word_question(rule, ex, data, all_readings, sources, rng):
    """Reads the example word of `ex`; the wrong readings change only this kanji's part, as
    reading_distractors builds them, so exactly one option applies the rule. None when the word
    cannot show the rule: no word, or a sound change inside the word that hides what the rule
    teaches (法 ホウ → ポウ in 文法)."""
    if not ex.word:
        return None
    parts = word_parts(ex.word_reading)
    index = ex.word.index(ex.kanji)
    part = parts[index]
    notes = ['Lưu ý: ' + ex.variant] if ex.variant else []
    if part == ex.kana:
        base = ex
    elif rule['kind'] == 'initial' and first_mora(part)[0] == first_mora(ex.kana)[0]:
        # the sound changes in the word (決 ケツ → ケッ in 決定) but keeps the initial the rule is about
        base = copy.copy(ex)
        base.kana, base.alt_kana = part, []
        notes.append('Trong từ này %s đọc thành %s (vốn là %s).' % (ex.kanji, to_hiragana(part), to_hiragana(ex.kana)))
    else:
        return None
    built = word_options(parts, index, reading_distractors(base, all_readings, data['rules'], count=12), rng)
    if built is None:
        return None
    answer, options, correct = built
    syllables = han_viet_of(ex.word, data, sources)
    explanation = ' '.join([word_breakdown(ex.word, parts, ex.meaning, syllables),
                            '%s (%s) → %s theo quy tắc %s: %s' % (ex.kanji, ex.hv, to_hiragana(ex.kana),
                                                                  rule['title'], rule['description'])] + notes)
    text = word_prompt(ex.word, syllables)
    return Question(rule, ex, 'WORD', 'MEDIUM', text, answer, explanation, options, correct)


def exception_question(rule, exc, data, sources, rng):
    """The same question for an exception's word; the wrong readings are what the rule would predict."""
    parts = word_parts(exc.word_reading)
    index = exc.word.index(exc.kanji)
    if parts[index] != exc.kana:
        return None
    built = word_options(parts, index, [to_katakana(p) for p in exc.predicted], rng)
    if built is None:
        return None
    answer, options, correct = built
    syllables = han_viet_of(exc.word, data, sources)
    explanation = '%s %s (%s) là ngoại lệ của quy tắc %s: %s' % (
        word_breakdown(exc.word, parts, exc.meaning, syllables), exc.kanji, exc.hv, rule['title'], exc.note)
    fake = Example(rule, [exc.kanji, exc.hv, exc.romaji, exc.alts, exc.word, exc.word_reading, exc.meaning, 'N3'])
    # the lesson page lists a rule's exceptions straight from this context
    context = {'type': 'EXCEPTION', 'rule': rule['code'], 'kanji': exc.kanji, 'hanViet': exc.hv,
               'reading': exc.kana, 'romaji': to_romaji(exc.kana), 'note': exc.note, 'word': exc.word,
               'wordReading': answer, 'meaning': exc.meaning}
    text = word_prompt(exc.word, syllables)
    return Question(rule, fake, 'EXCEPTION', 'HARD', text, answer, explanation, options, correct, context)


# ── SQL ─────────────────────────────────────────────────────────────────────
def q(value):
    if value is None:
        return 'NULL'
    if isinstance(value, (int, float)):
        return str(value)
    return "'" + str(value).replace('\\', '\\\\').replace("'", "''") + "'"


def build_sql(data):
    rng = random.Random(SEED)
    rules, units, lessons = data['rules'], data['units'], data['lessons']
    all_readings = {}
    for rule in rules:
        for ex in rule['examples_'] + rule['practice_']:
            all_readings.setdefault(ex.kanji, set()).update(ex.all_kana)
        for exc in rule['exceptions_']:
            all_readings.setdefault(exc.kanji, set()).update({exc.kana, *exc.alt_kana})

    lines = []
    w = lines.append
    book = data['textbook']
    w('-- Giáo trình Onyomi (Hán Việt → âm On). SINH TỰ ĐỘNG từ scripts/onyomi/onyomi_rules.json:')
    w('-- sửa dữ liệu ở file JSON rồi chạy lại scripts/onyomi/generate_onyomi.py, không sửa tay file này.')
    w('-- Chạy lại được: giáo trình/unit/bài/quy tắc được cập nhật tại chỗ theo khoá tự nhiên; ví dụ,')
    w('-- mẫu và câu hỏi của các bài này được xoá rồi tạo lại (kèm các lượt làm quiz cũ của chúng).')
    w('')
    w('SET @onyomi_book = (SELECT id FROM textbook WHERE title = %s ORDER BY id LIMIT 1);' % q(book['title']))
    w('INSERT INTO textbook (title, description, jlpt_level, status, version)')
    w('SELECT %s, %s, %s, \'PUBLISHED\', 1 FROM DUAL WHERE @onyomi_book IS NULL;'
      % (q(book['title']), q(book['description']), q(book['jlpt_level'])))
    w('SET @onyomi_book = COALESCE(@onyomi_book, LAST_INSERT_ID());')
    w('UPDATE textbook SET description = %s, status = \'PUBLISHED\' WHERE id = @onyomi_book;' % q(book['description']))
    w('')
    w('INSERT INTO tag (code, name, tag_type) VALUES')
    w(',\n'.join('    (%s, %s, \'KNOWLEDGE\')' % (q(u['tag']), q(u['tag_name'])) for u in units))
    w('ON DUPLICATE KEY UPDATE name = VALUES(name), tag_type = VALUES(tag_type);')
    for u in units:
        w('SET @onyomi_tag_%d = (SELECT id FROM tag WHERE code = %s);' % (u['order'], q(u['tag'])))
    w('')
    w('INSERT INTO unit (textbook_id, title, description, order_index) VALUES')
    w(',\n'.join('    (@onyomi_book, %s, %s, %d)' % (q(u['title']), q(u['description']), u['order']) for u in units))
    w('ON DUPLICATE KEY UPDATE title = VALUES(title), description = VALUES(description);')
    for u in units:
        w('SET @onyomi_unit_%d = (SELECT id FROM unit WHERE textbook_id = @onyomi_book AND order_index = %d);'
          % (u['order'], u['order']))
    w('')
    w('INSERT INTO lesson (unit_id, knowledge_tag_id, title, description, order_index, estimated_minutes,')
    w('                    lesson_type, skill, situation_tag, is_review, status) VALUES')
    w(',\n'.join("    (@onyomi_unit_%d, @onyomi_tag_%d, %s, %s, %d, 20, 'AMON', 'ONYOMI', 'FOUNDATION', 0, 'PUBLISHED')"
                 % (l['unit'], l['unit'], q(l['title']), q(l['description']), l['order']) for l in lessons))
    w('ON DUPLICATE KEY UPDATE knowledge_tag_id = VALUES(knowledge_tag_id), title = VALUES(title),')
    w('    description = VALUES(description), estimated_minutes = VALUES(estimated_minutes),')
    w('    lesson_type = VALUES(lesson_type), skill = VALUES(skill), status = VALUES(status);')
    for l in lessons:
        w('SET @onyomi_lesson_%d_%d = (SELECT id FROM lesson WHERE unit_id = @onyomi_unit_%d AND order_index = %d);'
          % (l['unit'], l['order'], l['unit'], l['order']))
    lesson_vars = ['@onyomi_lesson_%d_%d' % (l['unit'], l['order']) for l in lessons]
    w('SET @onyomi_first_lesson = @onyomi_lesson_1_1;')
    w('')

    # quizzes: per lesson a practice quiz (LESSON)
    for l in lessons:
        var = '@onyomi_quiz_%d_%d' % (l['unit'], l['order'])
        lvar = '@onyomi_lesson_%d_%d' % (l['unit'], l['order'])
        title = 'Luyện tập: ' + l['title']
        # LESSON is the type the learning path's lesson quiz looks up (LessonQuizRepository); older
        # seeds used RULE_PRACTICE, so such a quiz is reused and switched over when no LESSON quiz exists.
        w("SET %s = (SELECT id FROM assessment_quiz WHERE lesson_id = %s AND quiz_type IN ('LESSON', 'RULE_PRACTICE')"
          " ORDER BY quiz_type = 'LESSON' DESC, id LIMIT 1);" % (var, lvar))
        w("INSERT INTO assessment_quiz (lesson_id, unit_id, title, quiz_type, status)")
        w("SELECT %s, NULL, %s, 'LESSON', 'PUBLISHED' FROM DUAL WHERE %s IS NULL;" % (lvar, q(title), var))
        w('SET %s = COALESCE(%s, LAST_INSERT_ID());' % (var, var))
        w("UPDATE assessment_quiz SET title = %s, quiz_type = 'LESSON', status = 'PUBLISHED' WHERE id = %s;" % (q(title), var))
    # quizzes: per unit a final test (UNIT_FINAL) over the practice questions of its lessons
    for u in units:
        fvar = '@onyomi_final_%d' % u['order']
        ftitle = 'Kiểm tra cuối: ' + u['title']
        w("SET %s = (SELECT MIN(id) FROM assessment_quiz WHERE unit_id = @onyomi_unit_%d AND quiz_type = 'UNIT_FINAL');"
          % (fvar, u['order']))
        w("INSERT INTO assessment_quiz (lesson_id, unit_id, title, quiz_type, status)")
        w("SELECT NULL, @onyomi_unit_%d, %s, 'UNIT_FINAL', 'PUBLISHED' FROM DUAL WHERE %s IS NULL;"
          % (u['order'], q(ftitle), fvar))
        w('SET %s = COALESCE(%s, LAST_INSERT_ID());' % (fvar, fvar))
        w("UPDATE assessment_quiz SET title = %s, status = 'PUBLISHED' WHERE id = %s;" % (q(ftitle), fvar))
    quiz_vars = ['@onyomi_quiz_%d_%d' % (l['unit'], l['order']) for l in lessons]
    final_vars = ['@onyomi_final_%d' % u['order'] for u in units]
    quiz_list = ', '.join(quiz_vars + final_vars)
    lesson_list = ', '.join(lesson_vars)
    # every RULE_PRACTICE quiz left on these lessons is an old compound-word quiz ("Ghép từ"): the
    # practice quiz above has just been switched to LESSON
    old_compound = "(quiz.quiz_type = 'RULE_PRACTICE' AND quiz.lesson_id IN (%s))" % lesson_list
    w('')
    w('-- Xoá câu hỏi cũ của các bài này (kể cả bộ 450 câu cũ) cùng các lượt làm quiz trên chúng')
    w('-- (quiz luyện tập và bài kiểm tra cuối giai đoạn), rồi xoá hẳn các quiz Ghép từ cũ (RULE_PRACTICE):')
    w('-- phần Ghép từ đã bỏ, vì mọi câu hỏi giờ đều là đọc cả từ.')
    w('DELETE FROM assessment_attempt WHERE id IN (')
    w('    SELECT attempt_id FROM (SELECT link.attempt_id FROM assessment_quiz_attempt link')
    w('    JOIN assessment_quiz quiz ON quiz.id = link.quiz_id')
    w('    WHERE quiz.id IN (%s) OR %s) old_attempts);' % (quiz_list, old_compound))
    w('DELETE quiz FROM assessment_quiz quiz WHERE %s;' % old_compound)
    w('DELETE FROM assessment_quiz_question WHERE quiz_id IN (%s);' % quiz_list)
    w('DELETE a FROM assessment_answer a JOIN assessment_question qn ON qn.id = a.question_id')
    w(' WHERE qn.quiz_id IN (%s)' % quiz_list)
    w('    OR qn.conversion_rule_id IN (SELECT id FROM conversion_rule WHERE lesson_id IN (%s));' % lesson_list)
    w('DELETE FROM assessment_question WHERE quiz_id IN (%s)' % quiz_list)
    w('    OR conversion_rule_id IN (SELECT id FROM conversion_rule WHERE lesson_id IN (%s));' % lesson_list)
    w('')

    # rules
    per_lesson = {}
    for rule in rules:
        per_lesson.setdefault((rule['unit'], rule['lesson']), []).append(rule)
    w('INSERT INTO conversion_rule (lesson_id, source_sound, target_sound, title, description,')
    w('                             example_breakdown, tip, note, display_order) VALUES')
    rows = []
    for (unit, lesson), group in per_lesson.items():
        for order, rule in enumerate(group, 1):
            rule['var'] = '@onyomi_rule_' + rule['code'].lower()
            rule['lesson_var'] = '@onyomi_lesson_%d_%d' % (unit, lesson)
            rule['order'] = order
            first = rule['examples_'][0]
            breakdown = '%s (%s) → %s' % (first.hv, first.kanji, display(first.kana))
            rows.append('    (%s, %s, %s, %s, %s, %s, %s, %s, %d)' % (
                rule['lesson_var'], q(rule['source']), q(rule['target']), q(rule['title']), q(rule['description']),
                q(breakdown), q(rule['tip']), q(rule['note'] or None), order))
    w(',\n'.join(rows))
    w('ON DUPLICATE KEY UPDATE source_sound = VALUES(source_sound), target_sound = VALUES(target_sound),')
    w('    title = VALUES(title), description = VALUES(description), example_breakdown = VALUES(example_breakdown),')
    w('    tip = VALUES(tip), note = VALUES(note);')
    for rule in rules:
        w('SET %s = (SELECT id FROM conversion_rule WHERE lesson_id = %s AND display_order = %d);'
          % (rule['var'], rule['lesson_var'], rule['order']))
    for (unit, lesson), group in per_lesson.items():
        w('DELETE FROM conversion_rule WHERE lesson_id = @onyomi_lesson_%d_%d AND display_order > %d;'
          % (unit, lesson, len(group)))
    rule_list = ', '.join(r['var'] for r in rules)
    w('')

    # patterns: real teaching steps
    w('DELETE FROM rule_pattern WHERE rule_id IN (%s);' % rule_list)
    w('INSERT INTO rule_pattern (rule_id, step, title, content) VALUES')
    rows = []
    what = {'initial': 'âm đầu', 'final': 'âm cuối', 'rhyme': 'vần'}
    for rule in rules:
        first = rule['examples_'][0]
        steps = [
            ('Nhận diện', 'Tách âm Hán Việt, tìm %s %s.' % (what[rule['kind']], rule['source'])),
            ('Chuyển đổi', '%s → %s: %s (%s) → %s.' % (rule['source'], rule['target'], first.hv, first.kanji,
                                                    display(first.kana))),
            ('Ghi nhớ', rule['tip']),
        ]
        if rule['note']:
            steps.append(('Lưu ý', rule['note']))
        for i, (title, content) in enumerate(steps, 1):
            rows.append('    (%s, %d, %s, %s)' % (rule['var'], i, q(title), q(content)))
    w(',\n'.join(rows) + ';')
    w('')

    # kanji and their On readings
    kanji = {}
    for rule in rules:
        for ex in rule['examples_']:
            kanji.setdefault(ex.kanji, [ex.hv, ex.jlpt, set()])[2].update({(ex.kana, 1), *[(k, 0) for k in ex.alt_kana]})
        for exc in rule['exceptions_']:
            kanji.setdefault(exc.kanji, [exc.hv, None, set()])[2].update({(exc.kana, 1), *[(k, 0) for k in exc.alt_kana]})
    w('INSERT INTO kanji (`character`, unicode_code, han_viet, jlpt_level) VALUES')
    w(',\n'.join('    (%s, %s, %s, %s)' % (q(ch), q('%04X' % ord(ch)), q(v[0]), q(v[1])) for ch, v in sorted(kanji.items())))
    w('ON DUPLICATE KEY UPDATE han_viet = VALUES(han_viet), jlpt_level = COALESCE(jlpt_level, VALUES(jlpt_level));')
    w('INSERT IGNORE INTO kanji_reading (kanji_id, reading_type, reading_text, is_common)')
    w('SELECT k.id, \'ON\', r.reading_text, r.is_common FROM (')
    parts = []
    for ch, v in sorted(kanji.items()):
        common = {kana for kana, c in v[2] if c}
        for kana in sorted({kana for kana, _ in v[2]}):
            parts.append("    SELECT %s AS ch, %s AS reading_text, %d AS is_common" % (q(ch), q(kana), 1 if kana in common else 0))
    w('\n    UNION ALL\n'.join(parts))
    w(') r JOIN kanji k ON k.`character` = r.ch COLLATE utf8mb4_unicode_ci;')
    w('')

    # examples
    w('DELETE FROM rule_example WHERE rule_id IN (%s);' % rule_list)
    w('INSERT INTO rule_example (rule_id, kanji_id, example_expression, example_reading, breakdown, display_order)')
    w('SELECT e.rule_id, k.id, e.expression, e.reading, e.breakdown, e.display_order FROM (')
    parts = []
    for rule in rules:
        for order, ex in enumerate(rule['examples_'], 1):
            breakdown = '%s → %s' % (ex.hv, display(ex.kana))
            if ex.word:
                breakdown += ' · %s %s: %s' % (ex.word, ''.join(word_segments(ex)), ex.meaning)
            parts.append('    SELECT %s AS rule_id, %s AS ch, %s AS expression, %s AS reading, %s AS breakdown, %d AS display_order'
                         % (rule['var'], q(ex.kanji), q(ex.hv), q(ex.kana), q(breakdown), order))
    w('\n    UNION ALL\n'.join(parts))
    w(') e JOIN kanji k ON k.`character` = e.ch COLLATE utf8mb4_unicode_ci;')
    w('')

    # questions: one per example, exception and practice kanji, all reading an example word
    sources = rule_kanji(rules)
    # the practice kanji shuffle their options with their own generator, so adding some leaves the others as they are
    practice_rng = random.Random(PRACTICE_SEED)
    questions, skipped = [], []
    for rule in rules:
        for ex in rule['examples_']:
            question = word_question(rule, ex, data, all_readings, sources, rng)
            (questions if question else skipped).append(question or '%s %s' % (rule['code'], ex.kanji))
        for exc in rule['exceptions_']:
            question = exception_question(rule, exc, data, sources, rng)
            (questions if question else skipped).append(question or '%s %s (ngoại lệ)' % (rule['code'], exc.kanji))
    for rule in rules:
        for ex in rule['practice_']:
            question = word_question(rule, ex, data, all_readings, sources, practice_rng)
            (questions if question else skipped).append(question or '%s %s (luyện tập)' % (rule['code'], ex.kanji))
    order_in_quiz = {}
    rows = []
    for question in questions:
        rule = question.rule
        quiz_var = '@onyomi_quiz_%d_%d' % (rule['unit'], rule['lesson'])
        order_in_quiz[quiz_var] = order_in_quiz.get(quiz_var, 0) + 1
        context = question.context or {
            'type': question.qtype, 'rule': rule['code'], 'kanji': question.ex.kanji,
            'hanViet': question.ex.hv, 'reading': question.ex.kana,
            'romaji': to_romaji(question.ex.kana)}
        # focus: what the quiz card shows large, the word to read
        context['focus'] = question.ex.word
        context = json.dumps(context, ensure_ascii=False)
        opts = question.options
        rows.append("    (%s, @onyomi_tag_%d, 'KANJI', %s, %s, 'MULTIPLE_CHOICE', %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %d, %s)" % (
            rule['var'], rule['unit'], q(question.ex.jlpt or 'N3'), q(question.difficulty),
            q(question.text), q(question.answer),
            q(opts[0]), q(opts[1]), q(opts[2]), q(opts[3]), q(question.correct), q(question.explanation), q(context),
            quiz_var, order_in_quiz[quiz_var], q('onyomi:%s:%s:%s' % (rule['code'], question.ex.kanji, question.qtype))))
    header = ('INSERT INTO assessment_question (conversion_rule_id, knowledge_tag_id, skill, jlpt_level, difficulty,\n'
              '    question_type, question_text, answer_text, option_1, option_2, option_3, option_4, correct_option,\n'
              '    explanation, context_json, quiz_id, order_index, title) VALUES')
    for start in range(0, len(rows), 100):
        w(header)
        w(',\n'.join(rows[start:start + 100]) + ';')
    # The schema trigger trg_assessment_question_legacy_bridge links every question that has a quiz_id
    # to its quiz (assessment_quiz_question). Options live in option_1..4, which the lesson quiz reads.
    w('')
    w('-- Bài kiểm tra cuối giai đoạn dùng lại câu hỏi luyện tập của các bài trong giai đoạn.')
    for u in units:
        unit_quizzes = ', '.join('@onyomi_quiz_%d_%d' % (l['unit'], l['order']) for l in lessons if l['unit'] == u['order'])
        w('INSERT INTO assessment_quiz_question (quiz_id, question_id, order_index)')
        w('SELECT @onyomi_final_%d, linked.question_id,' % u['order'])
        w('       ROW_NUMBER() OVER (ORDER BY linked.quiz_id, linked.order_index, linked.question_id)')
        w('  FROM assessment_quiz_question linked WHERE linked.quiz_id IN (%s);' % unit_quizzes)
    return '\n'.join(lines) + '\n', questions, skipped


SEED_ALL_START = '-- 4) Giáo trình Onyomi'
SEED_ALL_END = '-- 5) '

# Only the standalone file gets these; jlas_seed_all.sql sets them once around all its sections.
STANDALONE_START = """-- Chạy riêng được (mysql CLI hoặc MySQL Workbench): chọn DB jlas và tạm tắt safe update mode,
-- vì các lệnh DELETE theo subquery bên dưới bị safe update mode chặn (lỗi 1175).
USE jlas;
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET @onyomi_previous_safe_updates = @@SQL_SAFE_UPDATES;
SET SQL_SAFE_UPDATES = 0;
"""
STANDALONE_END = 'SET SQL_SAFE_UPDATES = @onyomi_previous_safe_updates;\n'


def standalone(sql):
    """Wraps the section SQL so the file runs on its own: after the header comment, before the statements."""
    header, _, body = sql.partition('\n\n')
    return header + '\n\n' + STANDALONE_START + '\n' + body + '\n' + STANDALONE_END


def update_seed_all(sql):
    """Replaces the Onyomi section (4) of jlas_seed_all.sql with the freshly generated SQL."""
    if not os.path.exists(SEED_ALL):
        return False
    with open(SEED_ALL, encoding='utf-8') as f:
        lines = f.read().splitlines()
    start = next(i for i, line in enumerate(lines) if line.startswith(SEED_ALL_START))
    end = next(i for i, line in enumerate(lines) if line.startswith(SEED_ALL_END))
    # keep the '====' rule under the section title and the one above the next section's title
    body = [''] if lines[end - 2] == '' else []
    lines[start + 2:end - 1] = sql.rstrip('\n').split('\n') + body
    with open(SEED_ALL, 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(lines) + '\n')
    return True


def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
    data = json.load(open(SOURCE, encoding='utf-8'))
    errors, warnings = lint(data)
    for message in warnings:
        print('warning:', message)
    for message in errors:
        print('ERROR:', message)
    if errors:
        sys.exit('%d lint error(s)' % len(errors))
    examples = sum(len(r['examples_']) for r in data['rules'])
    print('lint ok: %d rules, %d examples, %d practice kanji, %d exceptions'
          % (len(data['rules']), examples, sum(len(r['practice_']) for r in data['rules']),
             sum(len(r['exceptions_']) for r in data['rules'])))
    if '--lint' in sys.argv:
        return
    sql, questions, skipped = build_sql(data)
    with open(OUTPUT, 'w', encoding='utf-8', newline='\n') as f:
        f.write(standalone(sql))
    by_type = {}
    for question in questions:
        by_type[question.qtype] = by_type.get(question.qtype, 0) + 1
    print('wrote %s: %d questions %s' % (os.path.relpath(OUTPUT, ROOT), len(questions), by_type))
    if skipped:
        print('no question (no word, or the word changes the sound the rule is about): ' + ', '.join(skipped))
    if update_seed_all(sql):
        print('refreshed the Onyomi section of %s' % os.path.relpath(SEED_ALL, ROOT))


if __name__ == '__main__':
    main()
