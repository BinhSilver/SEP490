#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
[JLAS2-20] Sinh sql/seeds/seed_data_vnjpclub.sql tu ban xuat vnjpclub (du lieu crawl da chuan bi:
45 giao trinh / 534 unit / 2866 lesson / 19518 tu vung / 2442 mau ngu phap / 5005 kanji / 9465 cau hoi).

Dung DOC LAP, khong dung toi tools/generate_seed.py va KHONG sua seed khac. File sinh ra chay SAU
sql/seeds/seed_data_jlas.sql (ban chuan; cac ban cu trong sql/seeds/_cu/ cung chay duoc) va cac seed khac:
  - Bang co ID tuong minh dung dai rieng 1000001-1999999, tat dinh qua cac lan sinh
    (thu tu N5 -> N1, theo giao trinh/unit/lesson trong curriculum).
  - Tu vung: INSERT IGNORE theo uk_vocabulary_normalized, khong ghi ID; moi lien ket tra ID theo
    (normalized_expression, normalized_reading). Tu da co chi lien ket, khong them sense, khong sua.
  - Kanji: tra theo ky tu; chu chua co thi INSERT IGNORE kem han_viet/meaning_vi tu nguon; chu da co khong UPDATE.
  - Dau file xoa du lieu cua chinh file nay (dai ID 1000001-1999999 + content_source VNJPCLUB),
    khong dung du lieu ngoai dai. Tu vung / kanji moi khong xoa khi chay lai (dung lai nho INSERT IGNORE).

Dau vao: thu muc ban xuat (*.json hoac *.json.gz): giao_trinh, unit, lesson, tu_vung, ngu_phap, vi_du, kanji,
tu_ghep, hoi_thoai, cau_song_ngu, bai_doc, bai_nghe, cau_hoi, phuong_an, audio_lien_ket.
Ban sao trong repo: data/vnjpclub/ (nen gzip; ban goc o D:\\project\\tool\\tool\\output\\export).

Chay:
    python tools/seed_vnjpclub.py                                  # doc data/vnjpclub
    python tools/seed_vnjpclub.py --vnjp-export <thu-muc-export> --out sql/seeds/seed_data_vnjpclub.sql
    python tools/seed_vnjpclub.py --vnjp-export <thu-muc-export> --copy-data   # chep lai data/vnjpclub (gzip)
"""
import argparse
import gzip
import html
import json
import os
import re
import unicodedata
from collections import OrderedDict, defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TABLES = ['giao_trinh', 'unit', 'lesson', 'tu_vung', 'ngu_phap', 'vi_du', 'kanji', 'tu_ghep', 'hoi_thoai',
          'cau_song_ngu', 'bai_doc', 'bai_nghe', 'cau_hoi', 'phuong_an', 'audio_lien_ket']
# [JLAS2-17] Bang tuy chon: co thi doc / chep, thieu (ban xuat cu) thi coi nhu rong. lesson_nguon.anh_trang = anh cua trang
# nguon tu vung / ngu phap / kanji -> bang lesson_page_image (migration 037).
OPTIONAL_TABLES = ['lesson_nguon']
ID_BASE, ID_MAX = 1000001, 1999999          # dai ID danh rieng cho du lieu vnjpclub
AUTO_INCREMENT_AFTER = 2000001             # ban legacy (seed cu); ban moi dung PROFILE['auto_increment']
# Anh xa ma on dinh -> ID DB (giu ID cu khi them / bot bai). Khoi tao bang --bootstrap-id-map tu ban xuat cu.
ID_MAP_PATH = os.path.join(ROOT, 'data', 'vnjpclub', 'seed_id_map.json.gz')
SOURCE = 'VNJPCLUB'

# Quy uoc dai ID (bang co ID tuong minh):
#   1.000.001-1.999.999 seed vnjpclub | 2.000.001-2.999.999 du lieu tao tu giao dien (cu)
#   3.000.001-3.999.999 seed Minna    | >= 4.000.001 du lieu tao tu giao dien (moi)
# Ca hai seed dat AUTO_INCREMENT = 4.000.001 o cuoi file; lenh DELETE dau file chi xoa dung dai cua seed do.
PROFILES = {
    'vnjpclub': {
        'id_base': 1000001, 'id_max': 1999999, 'source': 'VNJPCLUB', 'auto_increment': 4000001,
        'data_dir': os.path.join(ROOT, 'data', 'vnjpclub'),
        'out': os.path.join(ROOT, 'sql', 'seeds', 'seed_data_vnjpclub.sql'),
        'title': 'noi dung vnjpclub.com', 'file': 'sql/seeds/seed_data_vnjpclub.sql',
        'tag_clause': "code LIKE 'VNJ-%' AND code NOT LIKE 'VNJ-MINNA-%'",
        'old_textbooks': True, 'skip_no_passage_lessons': (),
    },
    'minna': {
        # source_code = 'VNJPCLUB' (noi dung Minna lay tu vnjpclub.com; CHECK cua content_source chi cho
        # SEKAI/VNJPCLUB/AI_GENERATED). Hai seed phan biet bang dai ID, lenh xoa chi theo dai.
        'id_base': 3000001, 'id_max': 3999999, 'source': 'VNJPCLUB', 'auto_increment': 4000001,
        'data_dir': os.path.join(ROOT, 'data', 'vnjpclub_minna'),
        'out': os.path.join(ROOT, 'sql', 'seeds', 'seed_data_minna.sql'),
        'title': 'Minna no Nihongo (vnjpclub.com)', 'file': 'sql/seeds/seed_data_minna.sql',
        'tag_clause': "code LIKE 'VNJ-MINNA-%'",
        'old_textbooks': False,
        # Topic 25: doan van goc la ban do / tranh tuong tac, nguon khong co chu lan anh -> bo cau khong lam duoc
        'skip_no_passage_lessons': ('Đọc hiểu トピック25',),
    },
}
PROFILE = PROFILES['vnjpclub']
VOICE_CODE = 'nguon-sach'
LEVEL_ORDER = {'N5': 0, 'N4': 1, 'N3': 2, 'N2': 3, 'N1': 4}
PLACEHOLDER = {'LISTENING': '(Nghe và chọn đáp án đúng)', 'READING': '(Đọc và chọn đáp án đúng)'}
# Giao trinh cu: chi them content_source cap textbook khi tra duoc DUY NHAT theo tieu de.
OLD_TEXTBOOKS = [
    ('Sekai N5 — Giáo trình tiếng Nhật', 'SEKAI'),
    ('Sekai N4 — Giáo trình tiếng Nhật', 'SEKAI'),
    ('Tiếng Nhật N3', 'AI_GENERATED'),
    ('Tiếng Nhật N2', 'AI_GENERATED'),
    ('Tiếng Nhật N1', 'AI_GENERATED'),
    ('Tiếng Nhật chuyên ngành IT', 'AI_GENERATED'),
    ('Tiếng Nhật du lịch', 'AI_GENERATED'),
    ('Tiếng Nhật đời sống', 'AI_GENERATED'),
]
SKILL_BY_PART = {'vocabulary': 'VOCABULARY', 'grammar': 'GRAMMAR', 'kanji': 'KANJI',
                 'reading': 'READING', 'listening': 'LISTENING'}
# [JLAS2-17] Mo ta giao trinh cho hoc vien: 1 cau <= 120 ky tu, khong ghi nguon / giay phep (nguon giu o content_source,
# trang hoc hien dong nho "Nguon noi dung: vnjpclub.com"). Ky nang = loai noi dung co nhieu bai nhat trong giao trinh.
DESC_MAX = 120
SKILL_WORDS = [('tu_vung', 'từ vựng'), ('ngu_phap', 'ngữ pháp'), ('kanji', 'kanji'), ('bai_doc', 'đọc hiểu'),
               ('bai_nghe', 'nghe hiểu')]


def textbook_description(title, n_lessons, lessons_by_kind):
    ranked = sorted((k for k, _ in SKILL_WORDS if lessons_by_kind.get(k)),
                    key=lambda k: (-lessons_by_kind[k], [x for x, _ in SKILL_WORDS].index(k)))[:3]
    words = [dict(SKILL_WORDS)[k] for k in ranked]
    phrase = (' luyện ' + (', '.join(words[:-1]) + ' và ' + words[-1] if len(words) > 1 else words[0])) if words else ''
    text = 'Giáo trình {} gồm {} bài{}.'.format(title, n_lessons, phrase)
    if len(text) > DESC_MAX:
        text = 'Giáo trình {} gồm {} bài.'.format(title, n_lessons)
    return cut(text, DESC_MAX)


# ----------------------------------------------------------------------------- tien ich (giong generate_seed.py)
def esc(v):
    if v is None:
        return 'NULL'
    if isinstance(v, bool):
        return '1' if v else '0'
    if isinstance(v, (int, float)):
        return str(v)
    s = str(v).replace('\\', '\\\\').replace("'", "''")
    s = s.replace('\r\n', '\n').replace('\r', '\n').replace('\n', '\\n')
    return "'" + s + "'"


def norm(s):
    """Giong generate_seed.norm: strip -> NFKC -> lower -> katakana (U+30A1..U+30F6) sang hiragana."""
    s = unicodedata.normalize('NFKC', (s or '').strip()).lower()
    return ''.join(chr(ord(c) - 0x60) if '\u30a1' <= c <= '\u30f6' else c for c in s)


def vocab_key_of(word, reading):
    """(tu, cach doc, khoa chuan hoa) — cach doc rong thi lay chinh tu."""
    word = (word or '').strip()
    reading = (reading or '').strip() or word
    return word, reading, (norm(word), norm(reading))


def is_kanji(ch):
    return '\u4e00' <= ch <= '\u9fff'


def cut(s, n):
    s = (s or '').strip()
    return s if len(s) <= n else s[:n - 1] + '…'


NGOAC_RONG = re.compile(r'^[ \t　]*【[ \t　]*】[ \t　]*$')
POPUP_SCRIPT = re.compile(r'^[ \t　]*POPUP SCRIPT[ \t　]*$')


def bo_ngoac_rong(s):
    """Bo dong rac cua ban xuat vnjpclub: dong chi gom cap ngoac 【】 RONG (nhan muc trong; ngoac co chu thi giu)
    va dong 'POPUP SCRIPT' (nhan nut mo transcript tren trang goc). Trang hoc vien hien nguyen cac dong nay."""
    if s is None:
        return None
    lines = [ln for ln in s.split('\n') if not (NGOAC_RONG.match(ln) or POPUP_SCRIPT.match(ln))]
    return '\n'.join(lines) if lines else None


class Batch:
    def __init__(self, table, columns, verb='INSERT INTO', size=500):
        self.table, self.columns, self.verb, self.size = table, columns, verb, size
        self.rows = []

    def add(self, *values):
        assert len(values) == len(self.columns), (self.table, values)
        self.rows.append('(' + ', '.join(esc(v) for v in values) + ')')

    def render(self):
        if not self.rows:
            return ''
        cols = ', '.join(self.columns)
        out = ['-- {}: {} dong'.format(self.table, len(self.rows))]
        for i in range(0, len(self.rows), self.size):
            out.append('{} {} ({}) VALUES\n{};'.format(self.verb, self.table, cols, ',\n'.join(self.rows[i:i + self.size])))
        return '\n'.join(out) + '\n'


def is_minna_key(key):
    """Khoa anh xa thuoc Minna: ma nguon (ca khoa, hoac phan sau tien to 'LOAI|') bat dau bang 'MINNA-'."""
    return any(part.startswith('MINNA-') for part in key.split('|'))


def check_profile_key(kind, key):
    """Moi profile chi ghi khoa cua minh vao anh xa cua minh: vnjpclub tu choi MINNA-..., minna chi nhan MINNA-...
    (chan truong hop chay nham ban xuat Minna voi profile vnjpclub lam anh xa vnjpclub bi chen khoa Minna)."""
    minna = PROFILE.get('name') == 'minna'
    if is_minna_key(key) != minna:
        raise SystemExit(
            'Khoa {} "{}" khong thuoc profile {}: profile minna chi nhan khoa MINNA-..., profile vnjpclub khong nhan '
            'khoa MINNA-.... Kiem tra --profile / --vnjp-export / --id-map; khong ghi file nao.'.format(
                kind, key, PROFILE.get('name', 'vnjpclub')))


class IdAllocator:
    """Cap ID theo khoa on dinh (loai bang + ma nguon) thay vi tuan tu theo thu tu duyet.

    - Khoa da co trong anh xa -> dung lai ID cu (ke ca khi thu tu bai / unit doi, hoac co bai moi chen vao giua).
    - Khoa moi -> ID tiep sau ID lon nhat tung cap cho loai do (khong tai su dung ID cua muc da go).
    - Vuot ID_MAX -> dung, khong lan sang dai khac (2000001+ la AUTO_INCREMENT cua du lieu tao tu giao dien,
      3000001-3999999 danh cho Minna).
    Anh xa rong + bootstrap: cap tuan tu tu ID_BASE dung thu tu duyet cu, nen tai tao dung ID cua seed cu.
    """

    def __init__(self, mapping=None):
        self.map = defaultdict(dict, {k: dict(v) for k, v in (mapping or {}).items()})
        self.next = {k: max(v.values()) + 1 for k, v in self.map.items() if v}
        self.used = defaultdict(set)
        self.created = defaultdict(int)

    def __call__(self, kind, key):
        if key in self.used[kind]:
            raise SystemExit('Trung khoa ID on dinh: {} {}'.format(kind, key))
        check_profile_key(kind, key)
        self.used[kind].add(key)
        known = self.map[kind].get(key)
        if known is not None:
            return known
        new_id = self.next.get(kind, ID_BASE)
        if new_id > ID_MAX:
            raise SystemExit('Vuot dai ID danh rieng ({}-{}) cho {}'.format(ID_BASE, ID_MAX, kind))
        self.next[kind] = new_id + 1
        self.map[kind][key] = new_id
        self.created[kind] += 1
        return new_id

    def max_ids(self):
        return {k: max(v) for k, v in ((k, [self.map[k][x] for x in u]) for k, u in self.used.items()) if v}

    def retired(self):
        """Khoa co trong anh xa nhung khong con dung o ban xuat nay (muc da go); ID giu lai, khong cap cho muc khac."""
        return {k: sorted(set(v) - self.used[k]) for k, v in self.map.items() if set(v) - self.used[k]}


def load_id_map(path):
    if not os.path.exists(path):
        return None
    with gzip.open(path, 'rt', encoding='utf-8') as fh:
        return json.load(fh)


def save_id_map(path, alloc):
    data = {k: dict(sorted(v.items())) for k, v in sorted(alloc.map.items())}
    raw = json.dumps(data, ensure_ascii=False, indent=0, sort_keys=False).encode('utf-8')
    # mtime=0 + filename='': header gzip khong mang thoi gian / ten file -> file nen tat dinh qua cac lan chay
    with open(path, 'wb') as fh, gzip.GzipFile(filename='', mode='wb', compresslevel=9, fileobj=fh, mtime=0) as fo:
        fo.write(raw)


def passage_html(text):
    """Doan van -> HTML: escape, dong trong tach doan <p>, xuong dong trong doan thanh <br>; giu nguyen chu Nhat."""
    text = (text or '').replace('\r\n', '\n').replace('\r', '\n').strip()
    if not text:
        return None
    paras = [p.strip('\n') for p in re.split(r'\n[ \t　]*\n', text) if p.strip()]
    return ''.join('<p>' + '<br>'.join(html.escape(ln, quote=False) for ln in p.split('\n')) + '</p>' for p in paras)


def media_rel(local_path):
    """'media/N3/.../x.png' hoac '...\\media\\N3\\...' -> 'vnjpclub/N3/.../x.png' (tuong doi trong kho content.audio.path)."""
    if not local_path:
        return None
    return 'vnjpclub/' + local_path.replace('\\', '/').split('media/', 1)[-1]


COMMON_IMAGES = {}   # ma vat chua (bai_doc / bai_nghe) -> [anh dung chung], dien trong build()


def split_images(value):
    """'a.png;media/N5/.../b.png' -> ['vnjpclub/...', ...] (giu thu tu trang, bo rong / trung)."""
    out = []
    for x in (value or '').split(';'):
        rel = media_rel(x.strip()) if x.strip() else None
        if rel and rel not in out:
            out.append(rel)
    return out


def load_export(d):
    data = {}
    for n in TABLES:
        p = os.path.join(d, n + '.json')
        if os.path.exists(p):
            data[n] = json.load(open(p, encoding='utf-8'))
        elif os.path.exists(p + '.gz'):
            with gzip.open(p + '.gz', 'rt', encoding='utf-8') as fh:
                data[n] = json.load(fh)
        else:
            raise SystemExit('Thieu {} (.json/.json.gz) trong {}'.format(n, d))
    for n in OPTIONAL_TABLES:
        p = os.path.join(d, n + '.json')
        if os.path.exists(p):
            data[n] = json.load(open(p, encoding='utf-8'))
        elif os.path.exists(p + '.gz'):
            with gzip.open(p + '.gz', 'rt', encoding='utf-8') as fh:
                data[n] = json.load(fh)
        else:
            data[n] = []
    return data


def copy_data(src, dst):
    os.makedirs(dst, exist_ok=True)
    for n in TABLES + [t for t in OPTIONAL_TABLES if os.path.exists(os.path.join(src, t + '.json'))]:
        with open(os.path.join(src, n + '.json'), 'rb') as fi, gzip.GzipFile(os.path.join(dst, n + '.json.gz'), 'wb', 9, mtime=0) as fo:
            fo.write(fi.read())


# ----------------------------------------------------------------------------- sinh
def build(D, nid, legacy=False):
    """nid(kind, key): IdAllocator. legacy=True: tat cac phan moi (passage_html/image_url, audio bai doc,
    vi du tu vung theo lesson) de tai tao dung seed cu khi bootstrap anh xa ID."""
    B = OrderedDict()
    B['tag'] = Batch('tag', ['id', 'code', 'name', 'tag_type'])
    B['textbook'] = Batch('textbook', ['id', 'title', 'description', 'jlpt_level', 'status'])
    B['unit'] = Batch('unit', ['id', 'textbook_id', 'title', 'description', 'order_index'])
    B['lesson'] = Batch('lesson', ['id', 'unit_id', 'knowledge_tag_id', 'title', 'description', 'order_index',
                                   'estimated_minutes', 'lesson_type', 'skill', 'situation_tag', 'is_review', 'status'])
    B['lesson_tag'] = Batch('lesson_tag', ['lesson_id', 'tag_id'])
    B['lesson_section'] = Batch('lesson_section', ['id', 'lesson_id', 'title', 'section_type', 'order_index'])
    B['grammar_point'] = Batch('grammar_point', ['id', 'lesson_id', 'tag_id', 'pattern', 'meaning', 'example', 'note',
                                                 'order_index'])
    B['content_item'] = Batch('content_item', ['id', 'section_id', 'content_type', 'grammar_point_id', 'title', 'content',
                                               'translated_text', 'order_index'])
    B['content_audio'] = Batch('content_audio', ['id', 'owner_type', 'owner_id', 'voice_code', 'audio_url', 'merged_url',
                                                 'start_ms', 'end_ms'])
    if not legacy:
        # [JLAS2-17] Anh trang tu vung / ngu phap / kanji (migration 037); trang hoc hien o dau tab tuong ung.
        B['lesson_page_image'] = Batch('lesson_page_image', ['id', 'lesson_id', 'tab', 'image_url', 'order_index', 'stable_code'])
    B['assessment_quiz'] = Batch('assessment_quiz', ['id', 'lesson_id', 'unit_id', 'title', 'quiz_type', 'status'])
    B['assessment_question'] = Batch('assessment_question', [
        'id', 'knowledge_tag_id', 'grammar_point_id', 'skill', 'jlpt_level', 'difficulty', 'question_type', 'title',
        'question_text', 'answer_text', 'option_1', 'option_2', 'option_3', 'option_4', 'correct_option', 'explanation',
        'context_json', 'order_index', 'is_active'] + ([] if legacy else ['passage_html', 'image_url']))
    B['assessment_quiz_question'] = Batch('assessment_quiz_question', ['quiz_id', 'question_id', 'order_index',
                                                                       'raw_score'])
    B['content_source'] = Batch('content_source', ['id', 'entity_type', 'entity_id', 'source_code', 'stable_code',
                                                   'source_url', 'original_title', 'review_status', 'note'])
    # bang tam (tra ID tu vung / kanji luc chay)
    T = OrderedDict()
    T['kanji'] = Batch('kanji', ['`character`', 'unicode_code', 'han_viet', 'meaning_vi', 'jlpt_level'], 'INSERT IGNORE INTO')
    T['tmp_vnj_vocab'] = Batch('tmp_vnj_vocab', ['ne', 'nr', 'expression', 'reading', 'meaning', 'word_type', 'jlpt',
                                                 'ex_ja', 'ex_vi'], 'INSERT IGNORE INTO')
    T['tmp_vnj_sense'] = Batch('tmp_vnj_sense', ['ne', 'nr', 'sense_order', 'usage_note', 'gloss'], 'INSERT IGNORE INTO')
    T['tmp_vnj_vk'] = Batch('tmp_vnj_vk', ['ne', 'nr', 'ch', 'pos'], 'INSERT IGNORE INTO')
    T['tmp_vnj_cv'] = Batch('tmp_vnj_cv', ['item_id', 'ne', 'nr'], 'INSERT IGNORE INTO')
    T['tmp_vnj_lk'] = Batch('tmp_vnj_lk', ['lesson_id', 'ch', 'ord'], 'INSERT IGNORE INTO')
    T['tmp_vnj_vsrc'] = Batch('tmp_vnj_vsrc', ['id', 'ne', 'nr', 'stable_code', 'source_url', 'title', 'review'],
                              'INSERT IGNORE INTO')
    T['tmp_vnj_ksrc'] = Batch('tmp_vnj_ksrc', ['id', 'ch', 'stable_code', 'source_url', 'title', 'review'],
                              'INSERT IGNORE INTO')

    stats = defaultdict(int)
    skipped = defaultdict(list)

    def src(entity, eid, code, url, title, review, note=None, key=None):
        B['content_source'].add(nid('source', key or entity + '|' + code), entity, eid, SOURCE, code, cut(url, 1000) or None,
                                cut(title, 500) or None, review if review in ('NGUON', 'CHUA_DUYET', 'DA_DUYET') else 'CHUA_DUYET',
                                note)

    # --- chi muc
    def group(rows, key):
        g = defaultdict(list)
        for r in rows:
            g[r[key]].append(r)
        return g

    units_by_tb = group(D['unit'], 'giao_trinh_ma')
    lessons_by_unit = group(D['lesson'], 'unit_ma')
    voc_by_l = group(D['tu_vung'], 'lesson_ma')
    gra_by_l = group(D['ngu_phap'], 'lesson_ma')
    kan_by_l = group(D['kanji'], 'lesson_ma')
    rdg_by_l = group(D['bai_doc'], 'lesson_ma')
    lis_by_l = group(D['bai_nghe'], 'lesson_ma')
    q_by_l = group(D['cau_hoi'], 'lesson_ma')
    vidu_by_parent = group(D['vi_du'], 'ma_cha')
    tughep_by_kan = group(D['tu_ghep'], 'kanji_ma')
    dlg_by_parent = group(D['hoi_thoai'], 'ma_cha')
    pairs_by_parent = group(D['cau_song_ngu'], 'ma_cha')
    opt_by_q = group(D['phuong_an'], 'cau_hoi_ma')
    aud_by_parent = group(D['audio_lien_ket'], 'ma_cha')
    aud_by_q = group([a for a in D['audio_lien_ket'] if a['cau_hoi_ma']], 'cau_hoi_ma')
    parent_part = {r['ma']: r.get('phan') for n in ('tu_vung', 'ngu_phap', 'kanji', 'bai_doc', 'bai_nghe') for r in D[n]}

    # --- tu vung: gom theo khoa chuan hoa (thu tu N5 -> N1); nhieu nghia giua cac bo -> nhieu sense (chi tu moi)
    vocab = OrderedDict()                     # key -> {row, senses: OrderedDict(meaning -> note)}
    kanji_chars = OrderedDict()               # ky tu -> (han_viet, meaning_vi, jlpt)

    vocab_key = vocab_key_of

    def add_vocab(word, reading, meaning, word_type, level, ex, book, stable, url, title, review, item_id):
        word, reading, key = vocab_key(word, reading)
        if not word or not key[0]:
            skipped['tu_vung_rong'].append(stable)
            return
        if len(word) > 255 or len(reading) > 255:
            skipped['tu_vung_qua_dai'].append(stable)
            return
        if key not in vocab:
            vocab[key] = {'word': word, 'reading': reading, 'meaning': (meaning or '').strip(), 'word_type': word_type,
                          'level': level, 'ex': ex, 'senses': OrderedDict(), 'book': book}
            for pos, ch in enumerate(word, start=1):
                if is_kanji(ch):
                    kanji_chars.setdefault(ch, (None, None, level))
                    T['tmp_vnj_vk'].add(key[0], key[1], ch, pos)
        v = vocab[key]
        if not v['ex'] and ex:
            v['ex'] = ex
        m = (meaning or '').strip()
        if m and m not in v['senses']:
            v['senses'][m] = book
        T['tmp_vnj_vsrc'].add(nid('source', 'VOCABULARY|' + stable), key[0], key[1], stable, cut(url, 1000) or None,
                              cut(title, 500) or None,
                              review if review in ('NGUON', 'CHUA_DUYET', 'DA_DUYET') else 'CHUA_DUYET')
        if item_id:
            T['tmp_vnj_cv'].add(item_id, key[0], key[1])
        stats['muc_tu_vung'] += 1

    # [JLAS2-17] anh dung chung cua vat chua (bai_doc / bai_nghe.anh_chung) -> context_json.common_images cua cac cau con
    COMMON_IMAGES.clear()
    with_questions = {q['ma_cha'] for q in D['cau_hoi']}
    for r in D['bai_doc'] + D['bai_nghe']:
        imgs = split_images(r.get('anh_chung'))
        if imgs and not legacy:
            COMMON_IMAGES[r['ma']] = imgs
            if r['ma'] not in with_questions:
                skipped['anh_chung_khong_co_cau_hoi_de_luu'].append(r['ma'])
    kinds_by_tb = defaultdict(lambda: defaultdict(set))
    for n in ('tu_vung', 'ngu_phap', 'kanji', 'bai_doc', 'bai_nghe'):
        for r in D[n]:
            kinds_by_tb[r.get('lesson_ma')][n].add(r.get('lesson_ma'))
    lesson_tb = {l['ma']: u['giao_trinh_ma'] for u in D['unit'] for l in lessons_by_unit[u['ma']]}

    tb_list = sorted(D['giao_trinh'], key=lambda r: (LEVEL_ORDER[r['cap']], r['order_index']))
    for tb in tb_list:
        lv = tb['cap']
        tb_id = nid('textbook', tb['ma'])
        title = tb['ten'] if lv in tb['ten'] else '{} ({})'.format(tb['ten'], lv)
        lessons_by_kind = defaultdict(int)
        for lma, kinds in kinds_by_tb.items():
            if lesson_tb.get(lma) == tb['ma']:
                for n in kinds:
                    lessons_by_kind[n] += 1
        desc = textbook_description(title, tb['so_bai'], lessons_by_kind) if not legacy else (
            'Nguồn: vnjpclub.com — {} ({}), {} unit, {} bài. Mã nguồn: {}.\n'
            'Giấy phép: nội dung thuộc bản quyền vnjpclub.com và nhà xuất bản sách gốc; chưa có giấy phép phân phối '
            'lại — chỉ dùng thử nghiệm nội bộ.\nNội dung chưa duyệt (CHUA_DUYET).').format(
            tb['ten'], lv, tb['so_unit'], tb['so_bai'], tb['ma'])
        B['textbook'].add(tb_id, cut(title, 255), desc, lv, 'DRAFT')
        src('TEXTBOOK', tb_id, tb['ma'], tb.get('url_nguon'), tb['ten'], tb.get('trang_thai_duyet'))
        stats['textbook'] += 1
        # unit / lesson sap theo order_index cua ban xuat; ID khong phu thuoc thu tu nen doi vi tri khong dich ID
        for u in sorted(units_by_tb[tb['ma']], key=lambda r: r['order_index']):
            u_id = nid('unit', u['ma'])
            u_title = u['tieu_de_vi'] or u['tieu_de_ja'] or 'Unit {}'.format(u['order_index'])
            B['unit'].add(u_id, tb_id, cut(u_title, 255), '{}\nMã nguồn: {}'.format(u['tieu_de_ja'] or '', u['ma']).strip(),
                          u['order_index'])
            src('UNIT', u_id, u['ma'], u.get('url_nguon'), u['tieu_de_vi'] or u['tieu_de_ja'], u.get('trang_thai_duyet'))
            stats['unit'] += 1
            for les in sorted(lessons_by_unit[u['ma']], key=lambda r: r['order_index']):
                build_lesson(les, u_id, lv, tb, B, T, nid, src, stats, skipped, add_vocab, kanji_chars,
                             voc_by_l, gra_by_l, kan_by_l, rdg_by_l, lis_by_l, q_by_l, vidu_by_parent, tughep_by_kan,
                             dlg_by_parent, pairs_by_parent, opt_by_q, aud_by_parent, aud_by_q, parent_part, legacy)
    # tu vung -> bang tam
    for key, v in vocab.items():
        ex = v['ex'] or {}
        T['tmp_vnj_vocab'].add(key[0], key[1], v['word'], v['reading'], v['meaning'] or None,
                               cut(v['word_type'], 50) or None, v['level'], ex.get('cau_ja') or None,
                               ex.get('dich_vi') or None)
        # moi tu moi luon co it nhat 1 sense 'Sách: ...' (tu khong co nghia: gloss rong, giong seed cu);
        # sense nay cung la dau hieu on dinh 'tu do vnjpclub tao' khi chay lai.
        senses = list(v['senses'].items()) or [('', v['book'])]
        for i, (m, book) in enumerate(senses, start=1):
            T['tmp_vnj_sense'].add(key[0], key[1], i, cut('Sách: ' + book, 1000), cut(m, 2000))
    for ch, (hv, mv, lv) in kanji_chars.items():
        T['kanji'].add(ch, '{:04X}'.format(ord(ch)), cut(hv, 255) or None, mv or None, lv)
    stats['khoa_tu_vung'] = len(vocab)
    stats['ky_tu_kanji'] = len(kanji_chars)
    if not legacy:
        build_page_images(D.get('lesson_nguon') or [], B, nid, stats, skipped)
    return B, T, stats, skipped


def page_image_tab(source_code):
    """Tab hien anh trang theo trang nguon ('somatome-n3-han-tu/...' -> KANJI; ngu-phap / bunpo -> GRAMMAR; con lai
    (tu-vung, goi, tuvung, katakana) -> VOCAB)."""
    book = (source_code or '').split('/', 1)[0].lower()
    if 'han-tu' in book or 'kanji' in book:
        return 'KANJI'
    if 'ngu-phap' in book or 'bunpo' in book or 'bunpou' in book:
        return 'GRAMMAR'
    return 'VOCAB'


def build_page_images(rows, B, nid, stats, skipped):
    """[JLAS2-17] lesson_nguon.anh_trang ('a.png;media/N3/.../b.png') -> lesson_page_image: moi anh mot dong, thu tu
    theo trang nguon (order_index) roi thu tu anh trong trang. Khoa ID on dinh = ma trang nguon + so thu tu anh; bai khong
    duoc dung (bi bo) thi bo qua anh cua bai do."""
    used_lessons = nid.used.get('lesson', set())
    order = defaultdict(int)
    for r in sorted(rows, key=lambda x: (x['lesson_ma'], int(x.get('order_index') or 0), x['ma'])):
        imgs = split_images(r.get('anh_trang'))
        if not imgs:
            continue
        L = r['lesson_ma']
        if L not in used_lessons:
            skipped['anh_trang_bai_khong_co'].append(r['ma'])
            continue
        les_id = nid.map['lesson'][L]
        tab = page_image_tab(r.get('ma_nguon'))
        for k, rel in enumerate(imgs, start=1):
            order[(L, tab)] += 1
            code = '{}-IMG{:02d}'.format(r['ma'], k)
            B['lesson_page_image'].add(nid('page_image', code), les_id, tab, cut(rel, 500), order[(L, tab)], cut(code, 255))
            stats['anh_trang'] += 1
            stats['anh_trang_' + tab] += 1
        stats['anh_trang_trang_nguon'] += 1


def build_lesson(les, u_id, lv, tb, B, T, nid, src, stats, skipped, add_vocab, kanji_chars,
                 voc_by_l, gra_by_l, kan_by_l, rdg_by_l, lis_by_l, q_by_l, vidu_by_parent, tughep_by_kan,
                 dlg_by_parent, pairs_by_parent, opt_by_q, aud_by_parent, aud_by_q, parent_part, legacy=False):
    L = les['ma']
    vocs, gras, kans = voc_by_l[L], gra_by_l[L], kan_by_l[L]
    rdgs = [r for r in rdg_by_l[L] if (r.get('noi_dung') or '').strip()]
    liss = lis_by_l[L]
    qs = q_by_l[L]
    if not legacy and les.get('bai') in PROFILE['skip_no_passage_lessons']:
        kept = [q for q in qs if (q.get('doan_van') or '').strip() or q.get('anh_doan_van')]
        skipped['cau_hoi_khong_doan_van_bo'].extend(q['ma'] for q in qs if q not in kept)
        qs = kept
    is_practice = les.get('loai_bai') == 'practice'
    # ky nang chinh
    if gras:
        skill = 'GRAMMAR'
    elif (vocs or kans) and not (rdgs or liss):
        skill = 'VOCABULARY'
    elif liss and not rdgs:
        skill = 'LISTENING'
    elif rdgs and not liss:
        skill = 'READING'
    elif rdgs and liss:
        skill = 'LISTENING' if len(liss) >= len(rdgs) else 'READING'
    elif qs:
        lis_q = sum(1 for q in qs if q['loai_cha'] == 'LIS')
        skill = 'LISTENING' if lis_q * 2 > len(qs) else 'READING'
    else:
        skill = 'VOCABULARY'
    tag_id = nid('tag', L)
    title = les['bai'] or les['tieu_de_ja'] or 'Bài {}'.format(les['order_index'])
    B['tag'].add(tag_id, 'VNJ-' + L, cut('vnjpclub: ' + title, 255), 'KNOWLEDGE')
    les_id = nid('lesson', L)
    B['lesson'].add(les_id, u_id, tag_id, cut(title, 255),
                    '{}\nMã nguồn: {}'.format(les['tieu_de_ja'] or '', L).strip(), les['order_index'], 30,
                    'REVIEW' if is_practice else 'CURRICULUM', skill, 'FOUNDATION', 1 if is_practice else 0, 'DRAFT')
    B['lesson_tag'].add(les_id, tag_id)
    src('LESSON', les_id, L, les.get('url_nguon'), les['bai'] or les['tieu_de_ja'], les.get('trang_thai_duyet'))
    stats['lesson'] += 1
    stats['lesson_' + skill] += 1
    sec_order = [0]
    item_order = defaultdict(int)

    def section(title_, typ):
        sec_order[0] += 1
        sid = nid('section', L + '|' + title_)
        B['lesson_section'].add(sid, les_id, title_, typ, sec_order[0])
        return sid

    def item(sid, ctype, gp, title_, content, trans, code, url, otitle, review, key=None):
        """key: khoa ID on dinh (mac dinh = ma nguon code); audio dung ma dong lien ket vi mot ma_audio co the
        thuoc nhieu bai."""
        item_order[sid] += 1
        iid = nid('item', key or code)
        B['content_item'].add(iid, sid, ctype, gp, cut(title_, 255), bo_ngoac_rong(content), trans, item_order[sid])
        src('CONTENT_ITEM', iid, code, url, otitle, review, key='CONTENT_ITEM|' + (key or code))
        stats['content_item'] += 1
        return iid

    def audio(owner_id, a, key):
        B['content_audio'].add(nid('audio', key), 'CONTENT_ITEM', owner_id, VOICE_CODE, cut(media_rel(a['file_cuc_bo']), 500),
                               None, None, None)
        stats['audio'] += 1

    book = '{} ({})'.format(tb['ten'], lv)
    # 1. Tu vung
    if vocs:
        sid = section('Từ vựng', 'EXPLANATION')
        iid = item(sid, 'READING', None, 'Danh sách từ vựng', 'Bài có {} từ vựng.'.format(len(vocs)), None,
                   L + '-ITEM-TU-VUNG', les.get('url_nguon'), 'Danh sách từ vựng', 'NGUON')
        for r in vocs:
            exs = [e for e in vidu_by_parent.get(r['ma'], []) if e.get('cau_ja')]
            add_vocab(r['tu'], r['cach_doc'], r['nghia_vi'], r.get('tu_loai'), lv, exs[0] if exs else None, book,
                      r['ma'], r['url_nguon'], r['tu'], r.get('trang_thai_duyet'), iid)
        # 1b. Cau vi du cua tu vung THEO BAI: vi du cua chinh dong tu vung trong bai nay (khong dung vi du lan dau
        # gap toan cuc o vocabulary_entry.example_ja). Moi cau mot content_item, lien ket content_vocabulary toi tu.
        vex = [] if legacy else [(r, e) for r in vocs for e in vidu_by_parent.get(r['ma'], []) if e.get('cau_ja')]
        if vex:
            vsid = section('Câu ví dụ từ vựng', 'EXAMPLE')
            for r, e in vex:
                viid = item(vsid, 'GRAMMAR', None, cut(r['tu'] or 'Ví dụ', 255), e['cau_ja'], e.get('dich_vi') or None,
                            e['ma'], e.get('url_nguon') or r['url_nguon'], r['tu'], e.get('trang_thai_duyet'))
                word, reading, key = vocab_key_of(r['tu'], r['cach_doc'])
                if word and key[0] and len(word) <= 255 and len(reading) <= 255:
                    T['tmp_vnj_cv'].add(viid, key[0], key[1])
                stats['cau_vi_du_tu_vung'] += 1
    # 2. Ngu phap
    gp_ids = {}
    if gras:
        section('Ngữ pháp', 'EXPLANATION')
        for k, g in enumerate(gras, start=1):
            gid = nid('grammar', g['ma'])
            gp_ids[g['ma']] = gid
            mau = (g['mau'] or '').strip()
            nghia = (g['nghia_vi'] or '').strip()
            exs = [e for e in vidu_by_parent.get(g['ma'], []) if e.get('cau_ja')]
            example = '{}\n{}'.format(exs[0]['cau_ja'], exs[0].get('dich_vi') or '').strip() if exs else None
            B['grammar_point'].add(gid, les_id, None, cut((g['cau_truc'] or '').strip() or mau or '(không tên)', 500),
                                   (mau + ('\n' + nghia if nghia else '')).strip() or '(chưa có nghĩa)', example,
                                   (g['ghi_chu'] or '').strip() or None, k)
            src('GRAMMAR_POINT', gid, g['ma'], g['url_nguon'], mau or g['cau_truc'], g.get('trang_thai_duyet'))
            stats['grammar_point'] += 1
    # 3. Cau vi du
    gex = [(g, e) for g in gras for e in vidu_by_parent.get(g['ma'], []) if e.get('cau_ja')]
    if gex:
        sid = section('Câu ví dụ', 'EXAMPLE')
        for k, (g, e) in enumerate(gex, start=1):
            item(sid, 'GRAMMAR', gp_ids[g['ma']], 'Ví dụ {}'.format(k), e['cau_ja'], e.get('dich_vi') or None,
                 e['ma'], e['url_nguon'], 'Ví dụ {}'.format(k), e.get('trang_thai_duyet'))
            stats['cau_vi_du'] += 1
    # 4. Kanji
    if kans:
        sid = section('Kanji', 'EXPLANATION')
        lines = []
        for r in kans:
            ch = (r['kanji'] or '').strip()
            if ch:
                lines.append('{} ({}) — {}{}'.format(ch, r.get('han_viet') or '', r.get('nghia_vi') or '',
                                                     ' — ' + r['cach_doc'] if r.get('cach_doc') else '').strip())
        iid = item(sid, 'READING', None, 'Danh sách kanji', '\n'.join(lines) or None, None, L + '-ITEM-KANJI',
                   les.get('url_nguon'), 'Danh sách kanji', 'NGUON')
        for k, r in enumerate(kans, start=1):
            ch = (r['kanji'] or '').strip()
            if len(ch) != 1:
                skipped['kanji_khong_phai_1_ky_tu'].append(r['ma'])
                continue
            if ch not in kanji_chars or kanji_chars[ch][0] is None:
                kanji_chars[ch] = (r.get('han_viet') or None, r.get('nghia_vi') or None, lv)
            T['tmp_vnj_lk'].add(les_id, ch, k)
            T['tmp_vnj_ksrc'].add(nid('source', 'KANJI|' + r['ma']), ch, r['ma'], cut(r['url_nguon'], 1000) or None, ch,
                                  r.get('trang_thai_duyet') or 'NGUON')
            stats['muc_kanji'] += 1
            for w in tughep_by_kan.get(r['ma'], []):
                add_vocab(w['tu'], w['cach_doc'], w['nghia_vi'], None, lv, None, book, w['ma'], w['url_nguon'], w['tu'],
                          w.get('trang_thai_duyet'), iid)
                stats['muc_tu_ghep'] += 1
    # 5. Bai doc
    if rdgs:
        sid = section('Bài đọc', 'EXPLANATION')
        for r in rdgs:
            pairs = [p['dich_vi'] for p in pairs_by_parent.get(r['ma'], []) if (p.get('dich_vi') or '').strip()]
            riid = item(sid, 'READING', None, r['tieu_de'] or 'Bài đọc', r['noi_dung'], '\n'.join(pairs) or None, r['ma'],
                        r['url_nguon'], r['tieu_de'], r.get('trang_thai_duyet'))
            stats['bai_doc'] += 1
            # audio doc bai (vd. Luyen doc): gan thang vao content_item bai doc -> khung bai doc hien nut phat
            seen = set()
            for a in ([] if legacy else aud_by_parent.get(r['ma'], [])):
                if a['ma_audio'] in seen:
                    continue
                seen.add(a['ma_audio'])
                if not a['co_file'] or not a['file_cuc_bo']:
                    skipped['audio_404'].append(a['ma_audio'])
                    continue
                audio(riid, a, 'READING|' + a['ma'])
                stats['audio_bai_doc'] += 1
    # 6. Bai nghe: transcript + moi file audio mot content_item LISTENING mang content_audio
    if liss:
        sid = section('Bài nghe', 'EXAMPLE')
        for r in liss:
            lines = dlg_by_parent.get(r['ma'], [])
            ja = '\n'.join(('{}：'.format(x['nguoi_noi']) if x.get('nguoi_noi') else '') + x['cau_ja']
                           for x in lines if (x.get('cau_ja') or '').strip())
            vi = '\n'.join(x['dich_vi'] for x in lines if (x.get('dich_vi') or '').strip())
            if not vi:
                vi = '\n'.join(p['dich_vi'] for p in pairs_by_parent.get(r['ma'], []) if (p.get('dich_vi') or '').strip())
            content = ja or (r.get('noi_dung') or '').strip()
            if content:
                item(sid, 'LISTENING', None, r['tieu_de'] or 'Bài nghe', content, vi or None, r['ma'], r['url_nguon'],
                     r['tieu_de'], r.get('trang_thai_duyet'))
                stats['bai_nghe_transcript'] += 1
            seen = set()
            for a in aud_by_parent.get(r['ma'], []):
                if a['ma_audio'] in seen:
                    continue
                seen.add(a['ma_audio'])
                if not a['co_file'] or not a['file_cuc_bo']:
                    skipped['audio_404'].append(a['ma_audio'])
                    continue
                iid = item(sid, 'LISTENING', None, 'Audio {}'.format(len(seen)), None, None, a['ma_audio'],
                           a['url_audio'], os.path.basename(a['url_audio']), 'NGUON', key='AUDIO|' + a['ma'])
                audio(iid, a, 'AUDIO|' + a['ma'])
    # 7. Luyen tap + quiz
    if qs:
        sid = section('Luyện tập', 'EXERCISE')
        item(sid, 'READING', None, 'Luyện tập', 'Bài có {} câu hỏi — làm trong bài kiểm tra của bài học.'.format(len(qs)),
             None, L + '-ITEM-LUYEN-TAP', les.get('url_nguon'), 'Luyện tập', 'NGUON')
        quiz_id = nid('quiz', L)
        B['assessment_quiz'].add(quiz_id, les_id, None, cut('Luyện tập: ' + title, 255), 'LESSON', 'DRAFT')
        for k, q in enumerate(qs, start=1):
            add_question(q, k, quiz_id, les_id, tag_id, lv, B, nid, src, stats, opt_by_q, aud_by_q, parent_part, gp_ids,
                         legacy)


def add_question(q, k, quiz_id, les_id, tag_id, lv, B, nid, src, stats, opt_by_q, aud_by_q, parent_part, gp_ids,
                 legacy=False):
    opts = sorted(opt_by_q.get(q['ma'], []), key=lambda o: int(o['order_index']))
    part = parent_part.get(q['ma_cha'])
    skill = SKILL_BY_PART.get(part) or ('LISTENING' if q['loai_cha'] == 'LIS' else 'READING')
    is_fill = bool((q.get('dap_an_text') or '').strip()) and not opts
    qtext = (q.get('cau_hoi') or '').strip()
    empty = not qtext
    if empty:
        qtext = PLACEHOLDER['LISTENING' if skill == 'LISTENING' else 'READING']
    inline, why = False, None
    if is_fill:
        why = None
    elif q.get('answer_uncertain'):
        why = 'answer_uncertain'
    elif q['loai_cau'] != 'mot_dap_an':
        why = q['loai_cau']
    elif not (2 <= len(opts) <= 4):
        why = '{}_phuong_an'.format(len(opts))
    elif any(not (o['noi_dung'] or '').strip() or len(o['noi_dung']) > 255 for o in opts):
        why = 'phuong_an_rong_hoac_qua_255'
    elif not q.get('dap_an') or int(q['dap_an']) not in [int(o['order_index']) for o in opts]:
        why = 'khong_co_dap_an_chac_chan'
    elif [int(o['order_index']) for o in opts] != list(range(1, len(opts) + 1)):
        why = 'thu_tu_phuong_an_khong_lien_mach'
    else:
        inline = True
    ctx = {
        'source': SOURCE, 'stable_code': q['ma'], 'review_status': q.get('trang_thai_duyet'),
        'loai_cau': 'dien' if is_fill else q['loai_cau'], 'nguon_dap_an': q.get('nguon_dap_an') or None,
        'answer_uncertain': bool(q.get('answer_uncertain')), 'ly_do_uncertain': q.get('ly_do_uncertain') or None,
        'source_url': q.get('url_nguon'), 'parent_code': q['ma_cha'], 'label': q.get('nhan') or None,
        'question_translation': q.get('dich_cau_hoi') or None,
        'empty_question_text': empty, 'inline': inline, 'not_inline_reason': why,
        'answer': int(q['dap_an']) if str(q.get('dap_an') or '').isdigit() else None,
        'options': [{'index': int(o['order_index']), 'text': o['noi_dung'], 'translation': o.get('dich_vi') or None,
                     'is_correct': bool(o['la_dap_an']), 'reason': o.get('ly_do') or None} for o in opts],
        'audio_hint': [{'url': a['url_audio'], 'link_level': a['muc_lien_ket'], 'review_status': a['trang_thai_duyet']}
                       for a in aud_by_q.get(q['ma'], [])],
    }
    if is_fill:
        ctx['answer_text'] = q['dap_an_text']
    if not legacy and q.get('ma_doan_van'):
        # ma doan van: trang bai hoc biet doan van da hien bang content_item bai doc (cung ma) -> khong hien lap
        ctx['passage_code'] = q['ma_doan_van']
    o4 =[o['noi_dung'] for o in opts] + [None] * 4 if inline else [None] * 4
    qid = nid('question', q['ma'])
    extra = []
    if not legacy:
        # doan van cua cau doc hieu (cot moi cua ban xuat) + anh doan van (duong dan tuong doi trong kho media)
        ph = passage_html(q.get('doan_van'))
        # anh_doan_van co the gom nhieu anh noi bang ';' ('a.png;media/N2/.../b.png'): image_url = anh dau tien,
        # danh sach day du vao context_json.passage_images (cot image_url chi chua 1 duong dan)
        imgs = [media_rel(x.strip()) for x in (q.get('anh_doan_van') or '').split(';') if x.strip()]
        # [JLAS2-17] anh cua chinh cau hoi / phuong an (cot moi anh_cau_hoi, thu tu trang): day du vao
        # context_json.question_images; cau KHONG co anh doan van thi image_url = anh dau cua cau (API nhan ra
        # image_url nam trong question_images nen khong coi la anh doan van).
        qimgs = split_images(q.get('anh_cau_hoi'))
        if qimgs:
            ctx['question_images'] = qimgs
        common = COMMON_IMAGES.get(q['ma_cha'])
        if common:
            ctx['common_images'] = common
        img = imgs[0] if imgs else (qimgs[0] if qimgs else None)
        if len(imgs) > 1:
            ctx['passage_images'] = imgs
        extra = [ph, cut(img, 500) if img else None]
        stats['cau_hoi_co_doan_van'] += 1 if ph else 0
        stats['cau_hoi_co_anh_doan_van'] += 1 if imgs else 0
        stats['cau_hoi_co_anh_cau_hoi'] += 1 if qimgs else 0
        stats['cau_hoi_co_anh_chung'] += 1 if common else 0
    B['assessment_question'].add(
        qid, tag_id, gp_ids.get(q['ma_cha']), skill, lv, 'MEDIUM', 'FILL_BLANK' if is_fill else 'MULTIPLE_CHOICE',
        cut(q.get('nhan') or q.get('bai') or '', 255) or None, qtext,
        q['dap_an_text'] if is_fill else None, o4[0], o4[1], o4[2], o4[3],
        int(q['dap_an']) if inline else None, q.get('giai_thich') or None,
        json.dumps(ctx, ensure_ascii=False, separators=(',', ':')), k, 0, *extra)
    B['assessment_quiz_question'].add(quiz_id, qid, k, 1.00)
    src('QUESTION', qid, q['ma'], q.get('url_nguon'), qtext, q.get('trang_thai_duyet'))
    stats['cau_hoi'] += 1
    stats['cau_hoi_inline' if inline else ('cau_hoi_dien' if is_fill else 'cau_hoi_chi_context')] += 1
    if empty:
        stats['cau_hoi_rong_thay_chuoi'] += 1
    if why:
        stats['ly_do_' + why] += 1


# ----------------------------------------------------------------------------- render SQL
HEADER = """-- =====================================================================
-- J-LAS seed: noi dung vnjpclub.com  [JLAS2-20]
-- Sinh tu ban xuat vnjpclub (data/vnjpclub) bang tools/seed_vnjpclub.py. KHONG sua file nay bang tay.
-- Noi dung: {textbook} giao trinh, {unit} unit, {lesson} lesson, {cau_hoi} cau hoi, {khoa_tu_vung} tu vung (khoa chuan hoa).
-- Ban quyen noi dung thuoc vnjpclub.com va nha xuat ban sach goc; chua co giay phep phan phoi lai.
-- Moi giao trinh / bai o trang thai DRAFT, cau hoi is_active = 0 (chua duyet).
--
-- Thu tu chay (MySQL 8.0.16+), KHONG co lenh USE: chon schema truoc khi chay.
--   1) jlas_schema.sql
--   2) migration: sql/migrations/018_content_source.sql (neu DB tao truoc khi schema co bang content_source)
--      -> sql/jlas_migrations_all.sql (jlas_seed_all.sql can bang cua file nay) -> sql/migrations/019_situation_tag_ba.sql
--      -> sql/migrations/020_align_checks_credit_reference.sql (019/020: DB tao tu schema cu - WORK -> BA, CHECK
--      khop jlas_schema.sql, unique credit reference; chay lai duoc)
--   3) sql/seeds/seed_data_jlas.sql: ban chuan duy nhat, sinh bang tools/generate_seed.py (ban cu o
--      sql/seeds/_cu/). Situation dung 'BA' ([JLAS-W0108]), nap thang tren jlas_schema.sql hien tai.
--   4) cac seed khac: sql/seeds/jlas_seed_all.sql, sql/seeds/questionbank.sql,
--      sql/seeds/ai_call_scoring_rules.sql ...
--   5) sql/seeds/demo_content_creator.sql (tai khoan demo creator@jlab.local, chi moi truong dev/demo)
--   6) sql/seeds/seed_data_vnjpclub.sql  (file nay) CHAY CUOI CUNG: jlas_seed_all.sql xoa cung 5 tu vung
--      (今日, 食べる, 積ん読, 推し ...) nen chay file nay sau no de khong mat lien ket tu vung cua bai.
--   mysql -u root -p --default-character-set=utf8mb4 <ten_db> < sql/seeds/seed_data_vnjpclub.sql
--
-- Chay lai duoc: dau file xoa du lieu cua chinh file nay (ID 1000001-1999999 o cac bang co ID tuong minh,
-- content_source.source_code = 'VNJPCLUB'), khong dung du lieu ngoai dai. Tu vung / kanji moi KHONG xoa
-- (lan chay sau dung lai nho INSERT IGNORE theo khoa chuan hoa / ky tu). Cuoi file dat AUTO_INCREMENT cac
-- bang co ID tuong minh >= 2000001 de du lieu tao sau (giao dien, seed khac) khong roi vao dai danh rieng.
-- Neu da chay seed cu LAI sau file nay (seed cu xoa toan bo noi dung bai hoc), hay chay lai file nay.
-- =====================================================================
SET NAMES utf8mb4;
SET @vnj_prev_safe_updates = @@SQL_SAFE_UPDATES;
SET SQL_SAFE_UPDATES = 0;
SET FOREIGN_KEY_CHECKS = 0;

-- Xoa du lieu vnjpclub cu (thu tu nguoc FK, chi trong dai danh rieng)
DELETE FROM assessment_quiz_question WHERE quiz_id BETWEEN {lo} AND {hi} OR question_id BETWEEN {lo} AND {hi};
DELETE FROM assessment_question WHERE id BETWEEN {lo} AND {hi};
DELETE FROM assessment_quiz WHERE id BETWEEN {lo} AND {hi};
DELETE FROM content_audio WHERE id BETWEEN {lo} AND {hi};
DELETE FROM content_vocabulary WHERE content_item_id BETWEEN {lo} AND {hi};
DELETE FROM content_item WHERE id BETWEEN {lo} AND {hi};
DELETE FROM lesson_kanji WHERE lesson_id BETWEEN {lo} AND {hi};
DELETE FROM grammar_point WHERE id BETWEEN {lo} AND {hi};
DELETE FROM lesson_section WHERE id BETWEEN {lo} AND {hi};
DELETE FROM lesson_tag WHERE lesson_id BETWEEN {lo} AND {hi};
DELETE FROM lesson WHERE id BETWEEN {lo} AND {hi};
DELETE FROM unit WHERE id BETWEEN {lo} AND {hi};
DELETE FROM textbook WHERE id BETWEEN {lo} AND {hi};
DELETE FROM tag WHERE id BETWEEN {lo} AND {hi} OR (tag_type = 'KNOWLEDGE' AND {tag_clause});
DELETE FROM content_source WHERE source_code = '{source}' OR id BETWEEN {lo} AND {hi};

SET FOREIGN_KEY_CHECKS = 1;

-- Giong doc audio goc cua sach
INSERT IGNORE INTO content_voice (code, name, gender, description, is_default) VALUES
('{voice}', 'Audio gốc', NULL, 'Audio gốc kèm sách (vnjpclub.com)', 0);

-- Bang tam (chi ton tai trong phien chay file nay)
DROP TEMPORARY TABLE IF EXISTS tmp_vnj_vocab, tmp_vnj_sense, tmp_vnj_vk, tmp_vnj_cv, tmp_vnj_lk, tmp_vnj_vsrc, tmp_vnj_ksrc;
CREATE TEMPORARY TABLE tmp_vnj_vocab (
    ne VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
    nr VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
    expression VARCHAR(255) NOT NULL, reading VARCHAR(255) NOT NULL, meaning TEXT NULL, word_type VARCHAR(50) NULL,
    jlpt VARCHAR(10) NULL, ex_ja TEXT NULL, ex_vi TEXT NULL, existed TINYINT(1) NOT NULL DEFAULT 0,
    vnj_word TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (ne, nr)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TEMPORARY TABLE tmp_vnj_sense (
    ne VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
    nr VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
    sense_order SMALLINT UNSIGNED NOT NULL, usage_note VARCHAR(1000) NULL, gloss VARCHAR(2000) NOT NULL,
    PRIMARY KEY (ne, nr, sense_order)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TEMPORARY TABLE tmp_vnj_vk (
    ne VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
    nr VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
    ch VARCHAR(8) NOT NULL, pos SMALLINT UNSIGNED NOT NULL,
    PRIMARY KEY (ne, nr, pos)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TEMPORARY TABLE tmp_vnj_cv (
    item_id BIGINT UNSIGNED NOT NULL,
    ne VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
    nr VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
    PRIMARY KEY (item_id, ne, nr)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TEMPORARY TABLE tmp_vnj_lk (
    lesson_id BIGINT UNSIGNED NOT NULL, ch VARCHAR(8) NOT NULL, ord INT UNSIGNED NOT NULL,
    PRIMARY KEY (lesson_id, ch)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TEMPORARY TABLE tmp_vnj_vsrc (
    id BIGINT UNSIGNED NOT NULL,
    ne VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
    nr VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
    stable_code VARCHAR(255) NOT NULL, source_url VARCHAR(1000) NULL, title VARCHAR(500) NULL, review VARCHAR(20) NOT NULL,
    PRIMARY KEY (id), UNIQUE KEY (stable_code)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TEMPORARY TABLE tmp_vnj_ksrc (
    id BIGINT UNSIGNED NOT NULL, ch VARCHAR(8) NOT NULL,
    stable_code VARCHAR(255) NOT NULL, source_url VARCHAR(1000) NULL, title VARCHAR(500) NULL, review VARCHAR(20) NOT NULL,
    PRIMARY KEY (id), UNIQUE KEY (stable_code)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

"""

LINK_SQL = """
-- ---------------------------------------------------------------------
-- Tu vung: tu da co (theo khoa chuan hoa) chi lien ket; tu moi INSERT IGNORE (khong ghi ID)
-- ---------------------------------------------------------------------
UPDATE tmp_vnj_vocab t JOIN vocabulary_entry v
    ON v.normalized_expression = t.ne AND v.normalized_reading = t.nr
SET t.existed = 1;
-- tu do vnjpclub tao o lan chay truoc (co sense 'Sách: ...') van tinh la tu moi cua vnjpclub
UPDATE tmp_vnj_vocab t JOIN vocabulary_entry v
    ON v.normalized_expression = t.ne AND v.normalized_reading = t.nr
SET t.vnj_word = 1
WHERE EXISTS (SELECT 1 FROM vocabulary_sense s WHERE s.vocabulary_id = v.id AND s.usage_note LIKE 'Sách: %');
UPDATE tmp_vnj_vocab SET vnj_word = 1 WHERE existed = 0;
INSERT IGNORE INTO vocabulary_entry (expression, normalized_expression, reading, normalized_reading, meaning,
                                     word_type, jlpt_level, example_ja, example_vi, usage_scope, dictionary_status)
SELECT t.expression, t.ne, t.reading, t.nr, t.meaning, t.word_type, t.jlpt, t.ex_ja, t.ex_vi, 'TEXTBOOK', 'DRAFT'
FROM tmp_vnj_vocab t WHERE t.existed = 0 ORDER BY t.jlpt, t.ne, t.nr;
-- sense / gloss chi cho tu moi (moi nghia khac nhau giua cac bo = mot sense, usage_note ghi ten sach)
INSERT INTO vocabulary_sense (vocabulary_id, sense_order, usage_note)
SELECT v.id, s.sense_order, s.usage_note
FROM tmp_vnj_sense s
JOIN tmp_vnj_vocab t ON t.ne = s.ne AND t.nr = s.nr AND t.existed = 0
JOIN vocabulary_entry v ON v.normalized_expression = s.ne AND v.normalized_reading = s.nr;
INSERT INTO vocabulary_gloss (sense_id, language_code, gloss_text)
SELECT vs.id, 'vi', s.gloss
FROM tmp_vnj_sense s
JOIN tmp_vnj_vocab t ON t.ne = s.ne AND t.nr = s.nr AND t.existed = 0
JOIN vocabulary_entry v ON v.normalized_expression = s.ne AND v.normalized_reading = s.nr
JOIN vocabulary_sense vs ON vs.vocabulary_id = v.id AND vs.sense_order = s.sense_order;
INSERT IGNORE INTO vocabulary_kanji (vocabulary_id, kanji_id, character_position)
SELECT v.id, k.id, x.pos
FROM tmp_vnj_vk x
JOIN tmp_vnj_vocab t ON t.ne = x.ne AND t.nr = x.nr AND t.existed = 0
JOIN vocabulary_entry v ON v.normalized_expression = x.ne AND v.normalized_reading = x.nr
JOIN kanji k ON k.`character` = x.ch;
INSERT IGNORE INTO content_vocabulary (content_item_id, vocabulary_id)
SELECT c.item_id, v.id
FROM tmp_vnj_cv c JOIN vocabulary_entry v ON v.normalized_expression = c.ne AND v.normalized_reading = c.nr;
INSERT INTO content_source (id, entity_type, entity_id, source_code, stable_code, source_url, original_title,
                            review_status, note)
SELECT s.id, 'VOCABULARY', v.id, '{source}', s.stable_code, s.source_url, s.title, s.review,
       CASE WHEN t.vnj_word = 1 THEN 'Tu moi tu vnjpclub' ELSE 'Dung lai tu da co (khong sua)' END
FROM tmp_vnj_vsrc s
JOIN tmp_vnj_vocab t ON t.ne = s.ne AND t.nr = s.nr
JOIN vocabulary_entry v ON v.normalized_expression = s.ne AND v.normalized_reading = s.nr;

-- ---------------------------------------------------------------------
-- Kanji cua bai (tra theo ky tu)
-- ---------------------------------------------------------------------
INSERT IGNORE INTO lesson_kanji (lesson_id, kanji_id, order_index)
SELECT l.lesson_id, k.id, l.ord FROM tmp_vnj_lk l JOIN kanji k ON k.`character` = l.ch;
INSERT INTO content_source (id, entity_type, entity_id, source_code, stable_code, source_url, original_title,
                            review_status, note)
SELECT s.id, 'KANJI', k.id, '{source}', s.stable_code, s.source_url, s.title, s.review, NULL
FROM tmp_vnj_ksrc s JOIN kanji k ON k.`character` = s.ch;
"""

OLD_SRC_SQL = """INSERT INTO content_source (id, entity_type, entity_id, source_code, stable_code, source_url, original_title,
                            review_status, note)
SELECT {id}, 'TEXTBOOK', t.id, '{code}', {stable}, NULL, t.title,
       {review}, 'Du lieu cu (truoc vnjpclub); tra theo tieu de duy nhat'
FROM textbook t
WHERE t.title = {title} AND t.id < {lo} AND (SELECT COUNT(*) FROM textbook x WHERE x.title = {title}) = 1;
"""

FOOTER = """
-- Dua AUTO_INCREMENT ra ngoai dai danh rieng (du lieu tao sau khong roi vao 1000001-1999999)
{alters}
DROP TEMPORARY TABLE IF EXISTS tmp_vnj_vocab, tmp_vnj_sense, tmp_vnj_vk, tmp_vnj_cv, tmp_vnj_lk, tmp_vnj_vsrc, tmp_vnj_ksrc;
SET SQL_SAFE_UPDATES = @vnj_prev_safe_updates;
"""


STATUS_SNAPSHOT_V2 = """-- [JLAS2-17] GIU TRANG THAI CONG BO: doc trang thai hien tai (theo ID on dinh) truoc khi xoa, ap lai sau khi nap.
-- Muc moi mac dinh DRAFT; quiz luyen tap theo trang thai bai (bai cong bo -> quiz cong bo + cau hoi bat).
DROP TEMPORARY TABLE IF EXISTS tmp_vnj_status;
CREATE TEMPORARY TABLE tmp_vnj_status (
    kind VARCHAR(20) NOT NULL, id BIGINT UNSIGNED NOT NULL, status VARCHAR(20) NULL, active TINYINT(1) NULL,
    PRIMARY KEY (kind, id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO tmp_vnj_status (kind, id, status) SELECT 'textbook', id, status FROM textbook WHERE id BETWEEN {lo} AND {hi};
INSERT INTO tmp_vnj_status (kind, id, status) SELECT 'lesson', id, status FROM lesson WHERE id BETWEEN {lo} AND {hi};
INSERT INTO tmp_vnj_status (kind, id, status) SELECT 'quiz', id, status FROM assessment_quiz WHERE id BETWEEN {lo} AND {hi};
INSERT INTO tmp_vnj_status (kind, id, active) SELECT 'question', id, is_active FROM assessment_question WHERE id BETWEEN {lo} AND {hi};

"""

STATUS_RESTORE_V2 = """
-- [JLAS2-17] Ap lai trang thai cong bo da doc o dau file (muc khong con trong seed thi bo qua; muc moi giu DRAFT)
UPDATE textbook t JOIN tmp_vnj_status s ON s.kind = 'textbook' AND s.id = t.id SET t.status = s.status
WHERE t.id BETWEEN {lo} AND {hi};
UPDATE lesson l JOIN tmp_vnj_status s ON s.kind = 'lesson' AND s.id = l.id SET l.status = s.status
WHERE l.id BETWEEN {lo} AND {hi};
UPDATE assessment_quiz z JOIN tmp_vnj_status s ON s.kind = 'quiz' AND s.id = z.id SET z.status = s.status
WHERE z.id BETWEEN {lo} AND {hi};
UPDATE assessment_question q JOIN tmp_vnj_status s ON s.kind = 'question' AND s.id = q.id SET q.is_active = s.active
WHERE q.id BETWEEN {lo} AND {hi};
-- Quiz luyen tap theo trang thai bai: bai PUBLISHED -> quiz PUBLISHED + bat cau hoi; bai khac -> quiz DRAFT
UPDATE assessment_question q
  JOIN assessment_quiz_question qq ON qq.question_id = q.id
  JOIN assessment_quiz z ON z.id = qq.quiz_id
  JOIN lesson l ON l.id = z.lesson_id
SET q.is_active = 1
WHERE z.id BETWEEN {lo} AND {hi} AND z.quiz_type = 'LESSON' AND l.status = 'PUBLISHED';
UPDATE assessment_quiz z JOIN lesson l ON l.id = z.lesson_id
SET z.status = CASE WHEN l.status = 'PUBLISHED' THEN 'PUBLISHED' ELSE 'DRAFT' END
WHERE z.id BETWEEN {lo} AND {hi} AND z.quiz_type = 'LESSON';
DROP TEMPORARY TABLE IF EXISTS tmp_vnj_status;
"""

DELETE_BLOCK_V2 = """-- Xoa du lieu cu cua CHINH seed nay — chi theo dai ID {lo}-{hi}, khong theo source_code / code, nen khong dung
-- toi seed kia hay du lieu tao tu giao dien (2.xxx.xxx, >= 4.000.001). FOREIGN_KEY_CHECKS = 0: khong xoa day chuyen.
--   assessment_quiz_question: chi lien ket cua quiz seed (quiz giao dien dung cau hoi seed van giu lien ket)
--   lesson_tag: chi cap (bai seed, tag seed) — tag do giao dien gan vao bai seed van giu
DELETE FROM assessment_quiz_question WHERE quiz_id BETWEEN {lo} AND {hi};
DELETE FROM assessment_question WHERE id BETWEEN {lo} AND {hi};
DELETE FROM assessment_quiz WHERE id BETWEEN {lo} AND {hi};
DELETE FROM content_audio WHERE id BETWEEN {lo} AND {hi};
DELETE FROM lesson_page_image WHERE id BETWEEN {lo} AND {hi};
DELETE FROM content_vocabulary WHERE content_item_id BETWEEN {lo} AND {hi};
DELETE FROM content_item WHERE id BETWEEN {lo} AND {hi};
DELETE FROM lesson_kanji WHERE lesson_id BETWEEN {lo} AND {hi};
DELETE FROM grammar_point WHERE id BETWEEN {lo} AND {hi};
DELETE FROM lesson_section WHERE id BETWEEN {lo} AND {hi};
DELETE FROM lesson_tag WHERE lesson_id BETWEEN {lo} AND {hi} AND tag_id BETWEEN {lo} AND {hi};
DELETE FROM lesson WHERE id BETWEEN {lo} AND {hi};
DELETE FROM unit WHERE id BETWEEN {lo} AND {hi};
DELETE FROM textbook WHERE id BETWEEN {lo} AND {hi};
DELETE FROM tag WHERE id BETWEEN {lo} AND {hi};
DELETE FROM content_source WHERE id BETWEEN {lo} AND {hi};
"""

HEADER_INTRO_V2 = """-- =====================================================================
-- J-LAS seed: {title}  [JLAS2-20]
-- Sinh bang tools/seed_vnjpclub.py --profile {profile} (du lieu: {data_dir}, anh xa ID: {data_dir}/seed_id_map.json.gz).
-- KHONG sua file nay bang tay.
-- Noi dung: {textbook} giao trinh, {unit} unit, {lesson} lesson, {cau_hoi} cau hoi, {khoa_tu_vung} tu vung (khoa chuan hoa).
-- Ban quyen noi dung thuoc vnjpclub.com va nha xuat ban sach goc; chua co giay phep phan phoi lai.
-- Muc MOI o trang thai DRAFT, cau hoi is_active = 0 (chua duyet). Muc DA CO giu trang thai cong bo hien tai
-- (doc truoc khi xoa, ap lai sau khi nap); quiz luyen tap theo trang thai bai.
--
-- QUY UOC DAI ID (bang co ID tuong minh):
--   1.000.001-1.999.999  seed vnjpclub (sql/seeds/seed_data_vnjpclub.sql)
--   2.000.001-2.999.999  du lieu tao tu giao dien (cu)
--   3.000.001-3.999.999  seed Minna (sql/seeds/seed_data_minna.sql)
--   >= 4.000.001         du lieu tao tu giao dien (moi)
-- File nay chi xoa / ghi dai {lo}-{hi} (moi lenh DELETE deu theo dai ID), khong dung toi 2.xxx.xxx,
-- >= 4.000.001 hay dai cua seed kia. content_source.source_code = '{source}'. Cuoi file dat AUTO_INCREMENT = {auto}
-- cho cac bang seed co ghi.
--
-- Thu tu chay (MySQL 8.0.16+), KHONG co lenh USE: chon schema truoc khi chay.
--   1) jlas_schema.sql -> migration (sql/migrations/018_content_source.sql, sql/jlas_migrations_all.sql,
--      sql/migrations/019_situation_tag_ba.sql, sql/migrations/020_align_checks_credit_reference.sql; DB cu)
--      + sql/migrations/037_lesson_page_image.sql (DB tao truoc khi schema co bang lesson_page_image) [JLAS2-17]
--   2) sql/seeds/seed_data_jlas.sql (tools/generate_seed.py) -> sql/seeds/jlas_seed_all.sql, questionbank.sql, ...
--   3) sql/seeds/demo_content_creator.sql (chi dev/demo)
--   4) sql/seeds/seed_data_vnjpclub.sql -> 5) sql/seeds/seed_data_minna.sql (vnjpclub truoc, Minna sau)
--   mysql -u root -p --default-character-set=utf8mb4 <ten_db> < {file}
-- Chay lai duoc. Tu vung / kanji moi KHONG xoa khi chay lai (dung lai nho INSERT IGNORE theo khoa chuan hoa / ky tu).
-- Neu chay lai seed_data_jlas.sql (xoa toan bo noi dung bai hoc) thi phai chay lai ca hai seed nay.
-- =====================================================================
"""


def render(B, T, stats, nid, legacy=False):
    lo, hi = ID_BASE, ID_MAX
    counts = {k: stats.get(k, 0) for k in ('textbook', 'unit', 'lesson', 'cau_hoi', 'khoa_tu_vung')}
    if legacy:
        head = HEADER.format(lo=lo, hi=hi, voice=VOICE_CODE, tag_clause="code LIKE 'VNJ-%'", source=SOURCE, **counts)
    else:
        body = HEADER.split('-- =====================================================================\n', 2)[2]
        start = body.index('-- Xoa du lieu vnjpclub cu')
        end = body.index('\nSET FOREIGN_KEY_CHECKS = 1;')
        body = body[:start] + STATUS_SNAPSHOT_V2 + DELETE_BLOCK_V2.rstrip('\n') + body[end:]
        head = HEADER_INTRO_V2.format(title=PROFILE['title'], profile=PROFILE['name'], file=PROFILE['file'],
                                      data_dir=os.path.relpath(PROFILE['data_dir'], ROOT).replace(os.sep, '/'),
                                      lo=lo, hi=hi, source=SOURCE, auto=PROFILE['auto_increment'], **counts) + \
            body.format(lo=lo, hi=hi, voice=VOICE_CODE, tag_clause=PROFILE['tag_clause'], source=SOURCE)
    out = [head]
    out.append('-- Kanji: chu chua co thi them (kem han_viet/meaning_vi tu nguon); chu da co bo qua, khong UPDATE')
    out.append(T['kanji'].render())
    for k in ('tmp_vnj_vocab', 'tmp_vnj_sense', 'tmp_vnj_vk'):
        out.append(T[k].render())
    for k, b in B.items():
        if k == 'content_source':
            continue
        out.append(b.render())
    for k in ('tmp_vnj_cv', 'tmp_vnj_lk', 'tmp_vnj_vsrc', 'tmp_vnj_ksrc'):
        out.append(T[k].render())
    out.append(LINK_SQL.format(source=SOURCE))
    out.append('-- content_source: muc vnjpclub (textbook / unit / lesson / grammar_point / content_item / cau hoi)')
    out.append(B['content_source'].render())
    # du lieu cu: chi cap textbook, tra theo tieu de duy nhat
    out.append('-- content_source cho du lieu cu (cap textbook; khong tra duoc thi bo qua)')
    for title, code in (OLD_TEXTBOOKS if (legacy or PROFILE['old_textbooks']) else []):
        oid = nid('source', 'OLD-TEXTBOOK:' + title)
        review = ("CASE WHEN t.status = 'PUBLISHED' THEN 'DA_DUYET' ELSE 'CHUA_DUYET' END"
                  if title.startswith('Sekai N5') else "'CHUA_DUYET'")
        out.append(OLD_SRC_SQL.format(id=oid, code=code, stable=esc('OLD-TEXTBOOK:' + title), review=review,
                                      title=esc(title), lo=lo))
    tables = ['tag', 'textbook', 'unit', 'lesson', 'lesson_section', 'grammar_point', 'content_item', 'content_audio',
              'assessment_quiz', 'assessment_question', 'content_source']
    if not legacy:
        tables.insert(tables.index('content_audio') + 1, 'lesson_page_image')
    auto = AUTO_INCREMENT_AFTER if legacy else PROFILE['auto_increment']
    alters = '\n'.join('ALTER TABLE {} AUTO_INCREMENT = {};'.format(t, auto) for t in tables)
    footer = FOOTER.format(alters=alters)
    if not legacy:
        footer = STATUS_RESTORE_V2.format(lo=lo, hi=hi) + footer
        footer = footer.replace('-- Dua AUTO_INCREMENT ra ngoai dai danh rieng (du lieu tao sau khong roi vao 1000001-1999999)',
                                '-- AUTO_INCREMENT = {} theo quy uoc dai ID: du lieu tao tu giao dien sau seed nam o >= 4.000.001, '
                                'ngoai moi dai seed'.format(auto))
    out.append(footer)
    return '\n'.join(x for x in out if x)


def main():
    ap = argparse.ArgumentParser(description='Sinh seed_data_vnjpclub.sql tu ban xuat vnjpclub [JLAS2-20]')
    ap.add_argument('--profile', choices=sorted(PROFILES), default='vnjpclub',
                    help='vnjpclub (dai 1000001-1999999, data/vnjpclub) | minna (dai 3000001-3999999, data/vnjpclub_minna)')
    ap.add_argument('--vnjp-export', default=None,
                    help='Thu muc ban xuat (*.json hoac *.json.gz); mac dinh thu muc du lieu cua profile')
    ap.add_argument('--out', default=None, help='Mac dinh sql/seeds/seed_data_<profile>.sql')
    ap.add_argument('--report', default=None, help='Ghi thong ke JSON (tuy chon)')
    ap.add_argument('--copy-data', action='store_true',
                    help='Chep (gzip) cac bang JSON can dung tu --vnjp-export vao data/vnjpclub')
    ap.add_argument('--id-map', default=None, help='File anh xa ma on dinh -> ID (mac dinh <thu muc du lieu>/seed_id_map.json.gz)')
    ap.add_argument('--new-id-map', action='store_true',
                    help='Tao anh xa ID MOI (rong) cho bo du lieu chua tung co seed (vd. Minna lan dau); khong dung khi da co anh xa')
    ap.add_argument('--bootstrap-id-map', action='store_true',
                    help='Tao anh xa ID tu ban xuat CU (cap tuan tu nhu seed cu, che do --legacy); KHONG dung voi ban xuat moi')
    ap.add_argument('--legacy', action='store_true',
                    help='Sinh dung dinh dang seed cu (khong passage_html/image_url, audio bai doc, vi du tu vung theo bai)')
    a = ap.parse_args()
    global PROFILE, ID_BASE, ID_MAX, SOURCE
    PROFILE = dict(PROFILES[a.profile], name=a.profile)
    ID_BASE, ID_MAX, SOURCE = PROFILE['id_base'], PROFILE['id_max'], PROFILE['source']
    a.vnjp_export = a.vnjp_export or PROFILE['data_dir']
    a.out = a.out or PROFILE['out']
    a.id_map = a.id_map or os.path.join(PROFILE['data_dir'], 'seed_id_map.json.gz')
    if a.copy_data:
        copy_data(a.vnjp_export, PROFILE['data_dir'])
        a.vnjp_export = PROFILE['data_dir']
        print('Da chep du lieu vao', PROFILE['data_dir'])
    if a.new_id_map:
        if os.path.exists(a.id_map):
            raise SystemExit('Da co anh xa ID ' + a.id_map + ' — khong tao moi de len')
        alloc, legacy = IdAllocator(), a.legacy
    elif a.bootstrap_id_map:
        if os.path.exists(a.id_map):
            raise SystemExit('Da co anh xa ID ' + a.id_map + ' — khong bootstrap de len')
        alloc, legacy = IdAllocator(), True
    else:
        mapping = load_id_map(a.id_map)
        if mapping is None:
            raise SystemExit('Thieu anh xa ID ' + a.id_map + ' — chay --bootstrap-id-map voi ban xuat cu truoc')
        for kind, keys in mapping.items():  # anh xa da bi chen khoa cua profile khac -> dung truoc khi ghi gi
            for key in keys:
                check_profile_key(kind, key)
        alloc, legacy = IdAllocator(mapping), a.legacy
    D = load_export(a.vnjp_export)
    B, T, stats, skipped = build(D, alloc, legacy)
    sql = render(B, T, stats, alloc, legacy)
    with open(a.out, 'w', encoding='utf-8', newline='\n') as f:
        f.write(sql)
    save_id_map(a.id_map, alloc)
    rows = {k: len(b.rows) for k, b in list(B.items()) + list(T.items())}
    retired = alloc.retired()
    info = {'stats': dict(stats), 'rows': rows, 'skipped': {k: len(v) for k, v in skipped.items()},
            'skipped_examples': {k: v[:10] for k, v in skipped.items()},
            'id_max': alloc.max_ids(), 'id_moi': dict(alloc.created),
            'khoa_khong_con_dung': {k: len(v) for k, v in retired.items()},
            'khoa_khong_con_dung_vd': {k: v[:10] for k, v in retired.items()}}
    if a.report:
        with open(a.report, 'w', encoding='utf-8') as f:
            json.dump(info, f, ensure_ascii=False, indent=1)
    print('Da ghi', a.out)
    for k, v in rows.items():
        print('  {:<26}{:>8}'.format(k, v))
    print('Thong ke:', json.dumps(dict(stats), ensure_ascii=False))
    print('Bo qua:', json.dumps(info['skipped'], ensure_ascii=False))
    print('ID lon nhat:', json.dumps(info['id_max']))
    print('ID moi cap:', json.dumps(info['id_moi']))
    print('Khoa khong con dung (giu ID, khong cap lai):', json.dumps(info['khoa_khong_con_dung']))
    print('Da ghi anh xa ID', a.id_map)


if __name__ == '__main__':
    main()
