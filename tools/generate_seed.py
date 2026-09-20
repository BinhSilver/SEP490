#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
[JLAS-W0070] Sinh seed SQL tu du lieu crawl-aanime -> schema J-LAS (jlas_schema.sql).
KHONG tao/sua/xoa bang. Chi sinh INSERT.

Chay:
    python3 generate_seed.py --input <thu-muc-output> --out seed_data_jlas.sql
"""
import argparse, glob, json, os, unicodedata
from collections import OrderedDict

VOICES = ['chuan-nu', 'chuan-nam', 'nam-tre', 'nu-tre', 'ong-cu', 'tinh-nghich']

# Chi seed audio cua MOT giong. Bo crawl co 6 giong (523 MB) nhung san pham chot
# dung mot giong chuan, va chi giong nay duoc host. Bang content_voice van giu du
# 6 dong lam danh muc, vi dialogue_role.voice_code tro toi cac giong khac.
# Muon bat lai giong khac: them vao danh sach nay roi sinh lai seed.
AUDIO_VOICES = ['chuan-nu']
LEVELS = [('sekai-n5', 'N5'), ('sekai-n4', 'N4')]
LESSONS_PER_UNIT = 5


def esc(v):
    """Escape gia tri cho MySQL, giu nguyen utf8mb4."""
    if v is None:
        return 'NULL'
    if isinstance(v, bool):
        return '1' if v else '0'
    if isinstance(v, (int, float)):
        return str(v)
    s = str(v).replace('\\', '\\\\').replace("'", "''")
    s = s.replace('\r\n', '\n').replace('\r', '\n').replace('\n', '\\n')
    return "'" + s + "'"


SMALL_KANA = str.maketrans('\u3041\u3043\u3045\u3047\u3049\u3063\u3083\u3085\u3087\u308e\u3095\u3096',
                           '\u3042\u3044\u3046\u3048\u304a\u3064\u3084\u3086\u3088\u308f\u304b\u3051')


def norm(s):
    """Chuan hoa cho normalized_expression / normalized_reading.

    Phai khop voi cach MySQL so sanh duoi utf8mb4_unicode_ci, neu khong se dinh
    loi trung unique key uk_vocabulary_normalized:
      - hiragana va katakana duoc coi la giong nhau  (mata == MATA)
      - kana nho va kana lon duoc coi la giong nhau
    Nen phai gap ve mot dang truoc khi khu trung.
    """
    s = unicodedata.normalize('NFKC', (s or '').strip()).lower()
    # katakana -> hiragana
    s = ''.join(chr(ord(c) - 0x60) if '\u30a1' <= c <= '\u30f6' else c for c in s)
    return s


def mysql_fold(s):
    """Mo phong cach utf8mb4_unicode_ci so sanh: bo luon dakuten/handakuten.

    MySQL utf8mb4_unicode_ci khong phan biet dau, nen voi tieng Nhat thi
    ha/ba/pa duoc coi la nhu nhau. Dung ham nay CHI de phat hien dung do,
    khong dung lam gia tri luu vao DB.
    """
    s = norm(s)
    s = ''.join(c for c in unicodedata.normalize('NFD', s) if not unicodedata.combining(c))
    return unicodedata.normalize('NFC', s)


def is_kanji(ch):
    return '\u4e00' <= ch <= '\u9fff'


class Batch:
    """Gom INSERT thanh lo 500 dong."""

    def __init__(self, table, columns, size=500):
        self.table, self.columns, self.size = table, columns, size
        self.rows = []

    def add(self, *values):
        self.rows.append('(' + ', '.join(esc(v) for v in values) + ')')

    def render(self):
        if not self.rows:
            return ''
        cols = ', '.join(self.columns)
        # roles dung INSERT IGNORE: khong ghi de vai tro may khac da tao.
        verb = 'INSERT IGNORE INTO' if self.table == 'roles' else 'INSERT INTO'
        out = ['-- {}: {} dong'.format(self.table, len(self.rows))]
        for i in range(0, len(self.rows), self.size):
            chunk = self.rows[i:i + self.size]
            out.append('{} {} ({}) VALUES\n{};'.format(
                verb, self.table, cols, ',\n'.join(chunk)))
        return '\n'.join(out) + '\n'


def load_kanji_data(path):
    """Nap du lieu Kanji trich tu KANJIDIC2 (xem extract_kanji_data.py).

    Thieu file thi van sinh seed duoc, chi la cac cot radical/stroke/han_viet
    de NULL nhu truoc.
    """
    if not path or not os.path.exists(path):
        return None
    d = json.load(open(path, encoding='utf-8'))
    return {
        'radicals': {r['number']: r['character'] for r in d.get('radicals', [])},
        'kanji': {k['character']: k for k in d.get('kanji', [])},
        'license': d.get('license'),
    }


# Sua mot so tieu de bai cho ro nghia hon ban crawl. Key = (cap do, so bai).
# Chi sua cach dien dat, khong doi noi dung bai hoc.
TITLE_OVERRIDES = {
    ('N5', 1): 'Giới thiệu bản thân — tên, nghề nghiệp, quốc gia',
}


def load_kana_data(path):
    """Nap du lieu bang chu cai (xem kana_data.json).

    Bang chu cai la kien thuc pho thong, khong lay tu nguon co ban quyen.
    """
    if not path or not os.path.exists(path):
        return None
    return json.load(open(path, encoding='utf-8'))


def build(input_dir, kanji_data=None, kana_data=None):
    b = OrderedDict()
    b['roles'] = Batch('roles', ['name', 'description'])
    b['tag'] = Batch('tag', ['id', 'code', 'name', 'tag_type'])
    b['textbook'] = Batch('textbook', ['id', 'title', 'description', 'jlpt_level', 'status'])
    b['unit'] = Batch('unit', ['id', 'textbook_id', 'title', 'description', 'order_index'])
    b['lesson'] = Batch('lesson', ['id', 'unit_id', 'knowledge_tag_id', 'title', 'description',
                                   'order_index', 'estimated_minutes', 'lesson_type', 'skill',
                                   'situation_tag', 'is_review', 'status'])
    b['lesson_tag'] = Batch('lesson_tag', ['lesson_id', 'tag_id'])
    b['lesson_section'] = Batch('lesson_section', ['id', 'lesson_id', 'title', 'section_type', 'order_index'])
    b['grammar_point'] = Batch('grammar_point', ['id', 'lesson_id', 'tag_id', 'pattern',
                                                 'meaning', 'example', 'note', 'order_index'])
    b['vocabulary_entry'] = Batch('vocabulary_entry',
                                  ['id', 'expression', 'normalized_expression', 'reading',
                                   'normalized_reading', 'meaning', 'word_type', 'jlpt_level',
                                   'example_ja', 'example_vi', 'usage_scope', 'dictionary_status'])
    b['vocabulary_variant'] = Batch('vocabulary_variant',
                                    ['id', 'vocabulary_id', 'variant_expression',
                                     'normalized_expression', 'variant_type'])
    b['vocabulary_sense'] = Batch('vocabulary_sense', ['id', 'vocabulary_id', 'sense_order', 'usage_note'])
    b['vocabulary_gloss'] = Batch('vocabulary_gloss', ['id', 'sense_id', 'language_code', 'gloss_text'])
    b['radical'] = Batch('radical', ['id', 'radical_number', '`character`'])
    b['kanji'] = Batch('kanji', ['id', '`character`', 'unicode_code', 'radical_id',
                                 'stroke_count', 'han_viet', 'jlpt_level'])
    b['kanji_reading'] = Batch('kanji_reading', ['id', 'kanji_id', 'reading_type',
                                                 'reading_text', 'is_common'])
    b['vocabulary_kanji'] = Batch('vocabulary_kanji', ['vocabulary_id', 'kanji_id', 'character_position'])
    b['content_item'] = Batch('content_item', ['id', 'section_id', 'content_type',
                                               'grammar_point_id', 'title', 'content',
                                               'translated_text', 'order_index'])
    b['content_vocabulary'] = Batch('content_vocabulary', ['content_item_id', 'vocabulary_id'])
    b['content_voice'] = Batch('content_voice', ['code', 'name', 'gender', 'description', 'is_default'])
    b['dialogue_role'] = Batch('dialogue_role', ['id', 'lesson_id', 'code', 'name_vi',
                                                 'name_ja', 'description', 'voice_code'])
    b['dialogue_line_detail'] = Batch('dialogue_line_detail',
                                      ['content_item_id', 'dialogue_role_id', 'scene_code',
                                       'bubble_side', 'bubble_x', 'bubble_y'])
    b['dialogue_line_chunk'] = Batch('dialogue_line_chunk',
                                     ['id', 'content_item_id', 'order_index', 'surface', 'meaning_vi'])
    b['content_audio'] = Batch('content_audio', ['id', 'owner_type', 'owner_id', 'voice_code',
                                                 'audio_url', 'merged_url', 'start_ms', 'end_ms'])

    ids = {k: 0 for k in ['tag', 'textbook', 'unit', 'lesson', 'section', 'grammar',
                          'vocab', 'variant', 'sense', 'gloss', 'kanji', 'item',
                          'drole', 'chunk', 'audio', 'radical', 'reading']}

    def nid(k):
        ids[k] += 1
        return ids[k]

    audio_seen = set()

    def add_audio(owner_type, owner_id, files, level_dir, timing):
        """files: dict {voice_code: 'xxx.mp3'}; timing: {voice: (merged, start_ms, end_ms)}."""
        if not files:
            return
        for voice, fname in files.items():
            if not fname or voice not in AUDIO_VOICES:
                continue
            if (owner_type, owner_id, voice) in audio_seen:
                continue
            audio_seen.add((owner_type, owner_id, voice))
            merged, s_ms, e_ms = timing.get(voice, (None, None, None)) if timing else (None, None, None)
            b['content_audio'].add(nid('audio'), owner_type, owner_id, voice,
                                   '{}/audio/{}/{}'.format(level_dir, voice, fname),
                                   merged, s_ms, e_ms)

    vocab_index = {}   # (norm_expr, norm_reading) -> vocab_id
    vocab_written = {} # vocab_id -> set cac cach viet da gap
    variant_seen = set()
    fold_index = {}    # khoa theo cach MySQL so sanh -> khoa strict da dung
    kanji_index = {}   # character -> kanji_id
    cv_pairs = set()
    warnings = []
    stats = {'N5': {}, 'N4': {}}

    radical_ids = {}
    if kanji_data:
        for num, ch in sorted(kanji_data['radicals'].items()):
            r_id = nid('radical')
            radical_ids[num] = r_id
            b['radical'].add(r_id, num, ch)

    # ---------------------------------------------------------------------
    # Chu de hoc (tag_type='SITUATION'). Gan o muc BAI HOC qua bang lesson_tag,
    # KHONG dung cot lesson.situation_tag vi CHECK chi nhan TRAVEL/WORK/FOUNDATION.
    #
    # DAILY : toan bo N5/N4 Sekai la noi dung sinh hoat hang ngay -> gan het.
    # TRAVEL: chi gan bai co it nhat 2 tu vung du lich (19/50 bai).
    # IT    : CHUA CO DU LIEU. Bo giao trinh N5/N4 nhap mon khong co tu chuyen
    #         nganh cong nghe (chi 11 tu sinh hoat kieu dien thoai, may tinh).
    #         Van tao tag de giao dien san sang, nhung KHONG gan bai nao --
    #         tha de trong con hon gan bua.
    # ---------------------------------------------------------------------
    TOPICS = [
        ('TOPIC-DAILY',  'Sinh ho\u1ea1t h\u1eb1ng ng\u00e0y', 'Ch\u00e0o h\u1ecfi, gia \u0111\u00ecnh, th\u1eddi gian, \u0103n u\u1ed1ng, mua s\u1eafm, th\u00f3i quen'),
        ('TOPIC-TRAVEL', 'Du l\u1ecbch, \u0111i l\u1ea1i',     'Nh\u00e0 ga, s\u00e2n bay, kh\u00e1ch s\u1ea1n, ph\u01b0\u01a1ng ti\u1ec7n, h\u1ecfi \u0111\u01b0\u1eddng, \u0111\u1eb7t ch\u1ed7'),
        ('TOPIC-IT',     'C\u00f4ng ngh\u1ec7 th\u00f4ng tin', 'Ch\u01b0a c\u00f3 n\u1ed9i dung \u2014 gi\u00e1o tr\u00ecnh N5/N4 nh\u1eadp m\u00f4n kh\u00f4ng c\u00f3 t\u1eeb v\u1ef1ng chuy\u00ean ng\u00e0nh'),
    ]
    TRAVEL_WORDS = ['えき', 'ホテル', 'レストラン', 'くうこう', 'でんしゃ', 'ちかてつ', 'バス',
                    'タクシー', 'ひこうき', 'りょこう', 'きっぷ', 'みち', 'にもつ', 'パスポート',
                    'かんこう', 'あんない', 'ちず', 'よやく']
    TRAVEL_MIN = 2   # bai phai co it nhat 2 tu du lich moi duoc gan tag

    topic_ids = {}
    for code, name, desc in TOPICS:
        t_id = nid('tag')
        topic_ids[code] = t_id
        b['tag'].add(t_id, code, name, 'SITUATION')

    VOICE_META = {
        'chuan-nu':    ('Gi\u1ecdng n\u1eef chu\u1ea9n', 'FEMALE', 'Gi\u1ecdng \u0111\u1ecdc chu\u1ea9n, d\u00f9ng m\u1eb7c \u0111\u1ecbnh', 1),
        'chuan-nam':   ('Gi\u1ecdng nam chu\u1ea9n', 'MALE', 'Gi\u1ecdng \u0111\u1ecdc chu\u1ea9n nam', 0),
        'nam-tre':     ('Gi\u1ecdng nam tr\u1ebb', 'MALE', 'Gi\u1ecdng nam tr\u1ebb, t\u1ed1c \u0111\u1ed9 nhanh h\u01a1n', 0),
        'nu-tre':      ('Gi\u1ecdng n\u1eef tr\u1ebb', 'FEMALE', 'Gi\u1ecdng n\u1eef tr\u1ebb', 0),
        'ong-cu':      ('Gi\u1ecdng \u00f4ng c\u1ee5', 'MALE', 'Gi\u1ecdng l\u1edbn tu\u1ed5i, t\u1ed1c \u0111\u1ed9 ch\u1eadm', 0),
        'tinh-nghich': ('Gi\u1ecdng tinh ngh\u1ecbch', None, 'Gi\u1ecdng bi\u1ec3u c\u1ea3m, d\u00f9ng cho h\u1ed9i tho\u1ea1i', 0),
    }
    # Vai tro nguoi dung. Dung INSERT IGNORE theo ten (uk_role_name) va KHONG dat id
    # cung, de khong dam vao id ma may khac da co. Khong xoa roles o dau file.
    b['roles'].add('LEARNER', 'H\u1ecdc vi\u00ean')
    b['roles'].add('ADMIN', 'Qu\u1ea3n tr\u1ecb vi\u00ean')
    # Role rieng cho nguoi bien soan noi dung: cong bo/thu hoi giao trinh va bai hoc,
    # xem duoc bai o trang thai nhap. KHONG phai ADMIN nang cap -- ADMIN thuan chi
    # thay noi dung da cong bo nhu hoc vien.
    b['roles'].add('CONTENT_CREATOR', 'Ng\u01b0\u1eddi bi\u00ean so\u1ea1n n\u1ed9i dung')

    for code, (name, gender, desc, dft) in VOICE_META.items():
        b['content_voice'].add(code, name, gender, desc, dft)

    for level_dir, level in LEVELS:
        files = sorted(glob.glob(os.path.join(input_dir, level_dir, 'json', '*.json')))
        if not files:
            raise SystemExit('Khong tim thay JSON trong {}/{}/json'.format(input_dir, level_dir))

        # [Trang thai duyet] N5 da duyet, N4 con nhap lieu. Phan anh dung thuc te
        # noi dung duoc duyet dan, dong thoi cho phep test phan quyen
        # learner/admin ma khong phai UPDATE tay sau khi seed.
        tb_status = 'PUBLISHED' if level == 'N5' else 'DRAFT'
        tb_id = nid('textbook')
        b['textbook'].add(tb_id, 'Sekai {} \u2014 Gi\u00e1o tr\u00ecnh ti\u1ebfng Nh\u1eadt'.format(level),
                          'B\u1ed9 gi\u00e1o tr\u00ecnh {} s\u1ed1 ho\u00e1 t\u1eeb ngu\u1ed3n Sekai Gen, {} b\u00e0i.'.format(level, len(files)),
                          level, tb_status)

        st = stats[level] = {'lesson': 0, 'grammar': 0, 'vocab_raw': 0, 'scene': 0, 'line': 0, 'example': 0, 'lesson_published': 0, 'lesson_travel': 0}
        unit_id = None

        for idx, path in enumerate(files):
            data = json.load(open(path, encoding='utf-8'))
            meta = data.get('meta', {})
            bai = meta.get('bai')
            title = TITLE_OVERRIDES.get((level, bai)) or meta.get('ten') or 'Bài {}'.format(bai)

            if idx % LESSONS_PER_UNIT == 0:
                unit_no = idx // LESSONS_PER_UNIT + 1
                unit_id = nid('unit')
                b['unit'].add(unit_id, tb_id, '{} \u2014 Ph\u1ea7n {}'.format(level, unit_no),
                              'B\u00e0i {} \u0111\u1ebfn {}'.format(idx + 1, min(idx + LESSONS_PER_UNIT, len(files))),
                              unit_no)

            # tag KNOWLEDGE bat buoc cho lesson.knowledge_tag_id
            tag_id = nid('tag')
            b['tag'].add(tag_id, '{}-B{:02d}'.format(level, bai), '{} b\u00e0i {}: {}'.format(level, bai, title),
                         'KNOWLEDGE')

            # 20 bai dau cua N5 da duyet; 5 bai cuoi con DRAFT de kiem chung
            # truong hop lesson DRAFT nam trong textbook da PUBLISHED.
            lesson_status = 'PUBLISHED' if (tb_status == 'PUBLISHED' and idx < 20) else 'DRAFT'
            lesson_id = nid('lesson')
            b['lesson'].add(lesson_id, unit_id, tag_id, 'B\u00e0i {}: {}'.format(bai, title),
                            meta.get('ban_quyen'), idx % LESSONS_PER_UNIT + 1, 45,
                            'CURRICULUM', 'GRAMMAR', 'FOUNDATION', 0, lesson_status)
            b['lesson_tag'].add(lesson_id, tag_id)

            # chu de: DAILY cho moi bai; TRAVEL neu du nguong tu vung du lich
            b['lesson_tag'].add(lesson_id, topic_ids['TOPIC-DAILY'])
            travel_hits = sum(
                1 for t in data.get('tu', [])
                if any((t.get('kana') or '') == w or (t.get('kana') or '').startswith(w)
                       for w in TRAVEL_WORDS))
            if travel_hits >= TRAVEL_MIN:
                b['lesson_tag'].add(lesson_id, topic_ids['TOPIC-TRAVEL'])
                st['lesson_travel'] = st.get('lesson_travel', 0) + 1
            st['lesson'] += 1
            if lesson_status == 'PUBLISHED':
                st['lesson_published'] += 1

            # ban do: ten file mp3 -> (file gop, start_ms, end_ms) theo tung giong
            timing_by_file = {}
            for voice, info in (data.get('gop', {}).get('giong', {}) or {}).items():
                names = info.get('ten') or []
                marks = info.get('moc') or []
                merged = info.get('file')
                for i, nm in enumerate(names):
                    if i < len(marks) and isinstance(marks[i], (list, tuple)) and len(marks[i]) == 2:
                        # moc[i] = [thoi diem bat dau, THOI LUONG] (giay), khong phai [start, end]
                        t0, dur = marks[i]
                        timing_by_file.setdefault(nm, {})[voice] = (
                            merged, int(round(t0 * 1000)), int(round((t0 + dur) * 1000)))
                    else:
                        timing_by_file.setdefault(nm, {})[voice] = (merged, None, None)

            def timing_for(files):
                """Gom moc thoi gian tuong ung voi bo file cua mot cau."""
                out = {}
                for voice, fname in (files or {}).items():
                    out[voice] = (timing_by_file.get(fname, {}).get(voice) or (None, None, None))
                return out

            tu = data.get('tu', [])
            vidu = data.get('viDu', [])
            mau_chinh = data.get('mauChinh', {})

            # ---------- section 1: tu vung ----------
            sec_vocab = nid('section')
            b['lesson_section'].add(sec_vocab, lesson_id, 'T\u1eeb v\u1ef1ng', 'EXPLANATION', 1)
            item_vocab = nid('item')
            b['content_item'].add(item_vocab, sec_vocab, 'READING', None, 'Danh s\u00e1ch t\u1eeb v\u1ef1ng b\u00e0i {}'.format(bai),
                                  'B\u00e0i {} c\u00f3 {} t\u1eeb v\u1ef1ng.'.format(bai, len(tu)), None, 1)

            for t in tu:
                st['vocab_raw'] += 1
                kana = (t.get('kana') or '').strip()
                kanji_txt = (t.get('kanji') or '').strip()
                expr = kanji_txt or kana
                key = (norm(expr), norm(kana))
                if key not in vocab_index:
                    v_id = nid('vocab')
                    vocab_index[key] = v_id
                    vocab_written[v_id] = {expr}
                    b['vocabulary_entry'].add(
                        v_id, expr, key[0], kana, key[1], t.get('vi'), None, level,
                        t.get('vdJa'), t.get('vdVi'), 'TEXTBOOK', 'PUBLISHED')
                    s_id = nid('sense')
                    b['vocabulary_sense'].add(s_id, v_id, 1,
                                              'Nh\u00f3m ch\u1ee7 \u0111\u1ec1: {}'.format(t.get('nhom')) if t.get('nhom') else None)
                    b['vocabulary_gloss'].add(nid('gloss'), s_id, 'vi', t.get('vi') or '')
                    # kanji xuat hien trong tu
                    pos = 0
                    for ch in expr:
                        pos += 1
                        if not is_kanji(ch):
                            continue
                        if ch not in kanji_index:
                            k_id = nid('kanji')
                            kanji_index[ch] = k_id
                            kd = (kanji_data or {}).get('kanji', {}).get(ch)
                            if kd:
                                b['kanji'].add(k_id, ch, kd['unicode'],
                                               radical_ids.get(kd.get('radical_number')),
                                               kd.get('stroke_count'), kd.get('han_viet'),
                                               kd.get('jlpt_level') or level)
                                for rtype, rkey in (('ON', 'readings_on'), ('KUN', 'readings_kun')):
                                    seen_r = set()
                                    for r in kd.get(rkey) or []:
                                        if r['text'] in seen_r:
                                            continue
                                        seen_r.add(r['text'])
                                        b['kanji_reading'].add(nid('reading'), k_id, rtype,
                                                               r['text'], 1 if r['common'] else 0)
                            else:
                                b['kanji'].add(k_id, ch, '{:04X}'.format(ord(ch)),
                                               None, None, None, level)
                                warnings.append({'file': os.path.basename(path),
                                                 'type': 'kanji_khong_co_trong_kanjidic',
                                                 'character': ch})
                        b['vocabulary_kanji'].add(v_id, kanji_index[ch], pos)
                v_id = vocab_index[key]
                add_audio('VOCABULARY_WORD', v_id, t.get('tu'), level_dir, timing_for(t.get('tu')))
                vocab_written.setdefault(v_id, set())
                if expr not in vocab_written[v_id]:
                    if vocab_written[v_id] and (v_id, norm(expr)) not in variant_seen:
                        variant_seen.add((v_id, norm(expr)))
                        b['vocabulary_variant'].add(nid('variant'), v_id, expr, norm(expr), 'ORTHOGRAPHIC')
                        warnings.append({'file': os.path.basename(path), 'type': 'merged_variant',
                                         'vocabulary_id': v_id, 'variant': expr})
                    vocab_written[v_id].add(expr)
                if (item_vocab, v_id) not in cv_pairs:
                    cv_pairs.add((item_vocab, v_id))
                    b['content_vocabulary'].add(item_vocab, v_id)

            # ---------- section 2: ngu phap ----------
            sec_gram = nid('section')
            b['lesson_section'].add(sec_gram, lesson_id, 'Ng\u1eef ph\u00e1p', 'EXPLANATION', 2)
            gram_by_code = {}
            for g_idx, mc in enumerate(data.get('mauCau', []), start=1):
                example = None
                for k in (mc.get('vd') or []):
                    if isinstance(k, int) and 0 <= k < len(vidu) and 0 <= vidu[k] < len(tu):
                        src = tu[vidu[k]]
                        example = '{}\n{}'.format(src.get('vdJa', ''), src.get('vdVi', ''))
                        break
                    warnings.append({'file': os.path.basename(path), 'type': 'vd_out_of_range',
                                     'grammar': mc.get('ma'), 'index': k})
                meaning = mc.get('ten') or ''
                if mc.get('y'):
                    meaning = (meaning + '\n' + mc['y']).strip()
                g_id = nid('grammar')
                gram_by_code[mc.get('ma')] = g_id
                b['grammar_point'].add(g_id, lesson_id, None, mc.get('cong_thuc') or mc.get('ten') or '',
                                       meaning, example, mc.get('luu_y'), g_idx)
                st['grammar'] += 1

            # ---------- section 3: cau vi du ----------
            sec_ex = nid('section')
            b['lesson_section'].add(sec_ex, lesson_id, 'C\u00e2u v\u00ed d\u1ee5', 'EXAMPLE', 3)
            for k, tu_idx in enumerate(vidu):
                if not (0 <= tu_idx < len(tu)):
                    warnings.append({'file': os.path.basename(path), 'type': 'vidu_out_of_range', 'k': k})
                    continue
                src = tu[tu_idx]
                ma = mau_chinh.get(str(k))
                ex_item = nid('item')
                b['content_item'].add(ex_item, sec_ex, 'GRAMMAR', gram_by_code.get(ma),
                                      'V\u00ed d\u1ee5 {}'.format(k + 1),
                                      src.get('vdJa'), src.get('vdVi'), k + 1)
                add_audio('CONTENT_ITEM', ex_item, src.get('cau'), level_dir, timing_for(src.get('cau')))
                st['example'] += 1

            # ---------- section 4+: hoi thoai, moi canh mot section ----------
            roles = {r.get('ma'): r for r in data.get('hoiThoai', {}).get('vai', [])}
            role_ids = {}
            for r in data.get('hoiThoai', {}).get('vai', []):
                r_id = nid('drole')
                role_ids[r.get('ma')] = r_id
                giong = r.get('giong') if r.get('giong') in VOICES else None
                b['dialogue_role'].add(r_id, lesson_id, r.get('ma'), r.get('ten') or r.get('ma'),
                                       r.get('ja'), r.get('ta'), giong)
            for c_idx, canh in enumerate(data.get('hoiThoai', {}).get('canh', []), start=4):
                sec_dlg = nid('section')
                b['lesson_section'].add(sec_dlg, lesson_id,
                                        'H\u1ed9i tho\u1ea1i: {}'.format(canh.get('ten') or canh.get('ma')),
                                        'EXAMPLE', c_idx)
                st['scene'] += 1
                for l_idx, loi in enumerate(canh.get('loi', []), start=1):
                    role = roles.get(loi.get('vai'), {})
                    line_item = nid('item')
                    b['content_item'].add(line_item, sec_dlg, 'LISTENING', None,
                                          role.get('ten') or loi.get('vai') or 'Ng\u01b0\u1eddi n\u00f3i',
                                          loi.get('ja'), loi.get('vi'), l_idx)
                    neo = loi.get('neo') or {}
                    b['dialogue_line_detail'].add(line_item, role_ids.get(loi.get('vai')),
                                                  canh.get('ma'),
                                                  loi.get('ben') if loi.get('ben') in ('trai', 'phai') else None,
                                                  neo.get('x'), neo.get('y'))
                    for m_idx, manh in enumerate(loi.get('manh') or [], start=1):
                        b['dialogue_line_chunk'].add(nid('chunk'), line_item, m_idx,
                                                     manh.get('t'), manh.get('vi'))
                    add_audio('CONTENT_ITEM', line_item, loi.get('tieng'), level_dir,
                              timing_for(loi.get('tieng')))
                    st['line'] += 1
                    if not loi.get('tieng'):
                        warnings.append({'file': os.path.basename(path), 'type': 'dialogue_missing_audio',
                                         'scene': canh.get('ma'), 'line': l_idx})

            # canh bao tu thieu audio
            for t in tu:
                missing = [v for v in AUDIO_VOICES if not (t.get('tu') or {}).get(v)]
                if missing:
                    warnings.append({'file': os.path.basename(path), 'type': 'vocab_missing_audio',
                                     'word': t.get('kana'), 'voices': missing})

    # -------------------------------------------------------------------------
    # Giao trinh bang chu cai. Bo crawl Sekai khong co noi dung nay -- du lieu
    # duoi day tu soan, bang chu cai la kien thuc pho thong nen khong vuong
    # ban quyen. Khong co audio thu san; giao dien se doc bang TTS.
    # jlpt_level de NULL vi kana la kien thuc truoc N5, khong thuoc cap nao.
    # -------------------------------------------------------------------------
    if kana_data:
        kana_tb = nid('textbook')
        b['textbook'].add(kana_tb, 'B\u1ea3ng ch\u1eef c\u00e1i ti\u1ebfng Nh\u1eadt',
                          'Hiragana v\u00e0 Katakana \u2014 n\u1ec1n t\u1ea3ng c\u1ea7n h\u1ecdc tr\u01b0\u1edbc khi v\u00e0o N5.',
                          None, 'PUBLISHED')
        kana_no = 0
        for script, unit_title in (('hiragana', 'Hiragana'), ('katakana', 'Katakana')):
            kana_no += 1
            k_unit = nid('unit')
            b['unit'].add(k_unit, kana_tb, unit_title,
                          '{} \u2014 {} b\u00e0i, {} ch\u1eef.'.format(
                              unit_title, len(kana_data[script]),
                              sum(len(l['chars']) for l in kana_data[script])),
                          kana_no)
            for li, les in enumerate(kana_data[script], start=1):
                k_tag = nid('tag')
                b['tag'].add(k_tag, 'KANA-{}-{:02d}'.format(script[:1].upper(), li),
                             '{}: {}'.format(unit_title, les['title']), 'KNOWLEDGE')
                k_les = nid('lesson')
                b['lesson'].add(k_les, k_unit, k_tag,
                                'B\u00e0i {}: {}'.format(li, les['title']), les['note'],
                                li, 20, 'CURRICULUM', 'READING', 'FOUNDATION', 0, 'PUBLISHED')
                k_sec = nid('section')
                b['lesson_section'].add(k_sec, k_les, 'B\u1ea3ng ch\u1eef', 'EXPLANATION', 1)
                for ci, ch in enumerate(les['chars'], start=1):
                    b['content_item'].add(nid('item'), k_sec, 'READING', None, ch['romaji'],
                                          ch['char'], ch['romaji'], ci)

    # Khung giao trinh cho N3/N2/N1: chua co noi dung nen de DRAFT va khong co
    # unit/lesson nao. Muc dich la chung minh he thong xu ly du 5 cap JLPT lay tu
    # du lieu, khong hard-code N5/N4. Khi co giao trinh that thi seed noi dung vao.
    # Dat SAU vong lap LEVELS de N5 giu id 1, N4 id 2 -- code va test dang dua vao.
    for level in ('N3', 'N2', 'N1'):
        b['textbook'].add(nid('textbook'), 'Gi\u00e1o tr\u00ecnh ti\u1ebfng Nh\u1eadt {}'.format(level),
                          'Ch\u01b0a c\u00f3 n\u1ed9i dung. Khung gi\u00e1o tr\u00ecnh \u0111\u1ec3 ki\u1ec3m ch\u1ee9ng '
                          'h\u1ec7 th\u1ed1ng x\u1eed l\u00fd \u0111\u1ee7 5 c\u1ea5p JLPT.', level, 'DRAFT')

    return b, stats, warnings, {'vocab_unique': len(vocab_index), 'kanji_unique': len(kanji_index)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--input', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--report', default='validation.json')
    ap.add_argument('--kanji-data', default='kanji_data_n5n4.json',
                    help='File du lieu Kanji trich tu KANJIDIC2')
    ap.add_argument('--kana-data', default='kana_data.json',
                    help='File du lieu bang chu cai Hiragana/Katakana')
    args = ap.parse_args()

    kanji_data = load_kanji_data(args.kanji_data)
    if kanji_data is None:
        print('CANH BAO: khong thay {} -> cot Kanji se de NULL'.format(args.kanji_data))
    kana_data = load_kana_data(args.kana_data)
    if kana_data is None:
        print('CANH BAO: khong thay {} -> bo qua giao trinh bang chu cai'.format(args.kana_data))
    batches, stats, warnings, extra = build(args.input, kanji_data, kana_data)

    header = [
        '-- =====================================================================',
        '-- J-LAS seed data  [JLAS-W0070] So hoa Kanji, tu vung, ngu phap, hoi thoai',
        '-- Sinh tu crawl-aanime bang tools/generate_seed.py. KHONG sua file nay bang tay.',
        '-- File nay KHONG co lenh USE: chon schema truoc khi chay.',
        '--   mysql -u root -p <ten_db> < seed_data_jlas.sql',
        '-- Du lieu Kanji (bo thu, so net, am Han Viet, cach doc On/Kun) lay tu',
        '-- KANJIDIC2 (c) Electronic Dictionary Research and Development Group,',
        '-- giay phep CC BY-SA 4.0 -- https://www.edrdg.org/wiki/index.php/KANJIDIC_Project',
        '-- San pham su dung du lieu nay PHAI hien thi ghi nguon va giay phep.',
        '-- =====================================================================',
        'SET NAMES utf8mb4;',
        'SET FOREIGN_KEY_CHECKS = 0;',
        '',
        '-- Xoa du lieu seed cu theo thu tu nguoc FK (khong dung TRUNCATE, khong dung toi users/roles).',
        'DELETE FROM content_audio;',
        'DELETE FROM dialogue_line_chunk;',
        'DELETE FROM dialogue_line_detail;',
        'DELETE FROM dialogue_role;',
        'DELETE FROM content_vocabulary;',
        'DELETE FROM content_item;',
        'DELETE FROM kanji_reading;',
        'DELETE FROM vocabulary_kanji;',
        'DELETE FROM vocabulary_gloss;',
        'DELETE FROM vocabulary_variant;',
        'DELETE FROM vocabulary_sense;',
        'DELETE FROM vocabulary_entry;',
        'DELETE FROM kanji;',
        'DELETE FROM radical;',
        'DELETE FROM grammar_point;',
        'DELETE FROM lesson_section;',
        'DELETE FROM lesson_tag;',
        'DELETE FROM lesson;',
        'DELETE FROM unit;',
        'DELETE FROM textbook;',
        "DELETE FROM tag WHERE tag_type = 'KNOWLEDGE'"
        "  AND (code REGEXP '^N[45]-B[0-9]{2}$' OR code LIKE 'KANA-%');",
        "DELETE FROM tag WHERE tag_type = 'SITUATION' AND code LIKE 'TOPIC-%';",
        'DELETE FROM content_voice;',
        '',
    ]
    body = [batches[k].render() for k in batches]
    footer = ['', 'SET FOREIGN_KEY_CHECKS = 1;', '']

    with open(args.out, 'w', encoding='utf-8') as f:
        f.write('\n'.join(header) + '\n'.join(body) + '\n'.join(footer))

    with open(args.report, 'w', encoding='utf-8') as f:
        json.dump({'stats': stats, 'unique': extra, 'warning_count': len(warnings),
                   'warnings': warnings[:500]}, f, ensure_ascii=False, indent=2)

    print('Da ghi {}'.format(args.out))
    print('{:<22}{:>10}{:>10}'.format('Bang', 'So dong', ''))
    for k, v in batches.items():
        print('{:<22}{:>10}'.format(k, len(v.rows)))
    print('\nTheo cap do:')
    for lv, s in stats.items():
        print(' ', lv, s)
    print('\nTu vung duy nhat sau khu trung:', extra['vocab_unique'])
    print('Ky tu Kanji duy nhat:', extra['kanji_unique'])
    print('Canh bao:', len(warnings), '-> ', args.report)


if __name__ == '__main__':
    main()
