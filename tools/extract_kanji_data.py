#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
[JLAS-W0070/W0065] Trich du lieu Kanji tu KANJIDIC2 cho cac chu xuat hien trong
bo giao trinh Sekai N5/N4.

Chi trich dung nhung chu can dung (~533) de file trong repo nho, thay vi keo ca
KANJIDIC2 17 MB.

NGUON VA GIAY PHEP — BAT BUOC GHI NGUON:
    KANJIDIC2, Electronic Dictionary Research and Development Group (EDRDG).
    Giay phep Creative Commons Attribution-ShareAlike 4.0 (CC BY-SA 4.0).
    https://www.edrdg.org/wiki/index.php/KANJIDIC_Project
    San pham su dung du lieu nay PHAI hien thi ghi nguon va giay phep.

Chay:
    python3 extract_kanji_data.py --kanjidic kanjidic2-all.json \
        --input crawl-aanime/output --out kanji_data_n5n4.json
"""
import argparse, glob, json, os, unicodedata

LEVELS = [('sekai-n5', 'N5'), ('sekai-n4', 'N4')]
LICENSE = {
    'source': 'KANJIDIC2',
    'publisher': 'Electronic Dictionary Research and Development Group (EDRDG)',
    'license': 'CC BY-SA 4.0',
    'url': 'https://www.edrdg.org/wiki/index.php/KANJIDIC_Project',
    'note': 'San pham su dung du lieu nay phai hien thi ghi nguon va giay phep.',
    'is_common_note': 'KANJIDIC2 khong co truong cach doc thong dung. Co is_common trong file nay la suy doan theo thu tu (cach doc dau tien cua moi loai ON/KUN), dung de goi y hoc truoc chu khong phai du lieu goc.',
}


def is_kanji(ch):
    return '\u4e00' <= ch <= '\u9fff'


def radical_char(number):
    """So bo thu (1-214) -> ky tu.

    Khoi Unicode Kangxi Radicals U+2F00..U+2FD5 chua dung 214 bo thu theo dung
    thu tu. NFKC doi sang dang CJK thong thuong de hien thi cho quen mat:
    U+2F00 (⼀) -> U+4E00 (一).
    """
    if not (1 <= number <= 214):
        return None
    return unicodedata.normalize('NFKC', chr(0x2F00 + number - 1))


def collect_chars(input_dir):
    """Thu thap ky tu Kanji theo cap do; N5 uu tien neu chu xuat hien o ca hai."""
    found = {}
    for level_dir, level in LEVELS:
        files = sorted(glob.glob(os.path.join(input_dir, level_dir, 'json', '*.json')))
        for path in files:
            data = json.load(open(path, encoding='utf-8'))
            for t in data.get('tu', []):
                for ch in (t.get('kanji') or ''):
                    if is_kanji(ch):
                        found.setdefault(ch, level)
    return found


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--kanjidic', required=True)
    ap.add_argument('--input', required=True)
    ap.add_argument('--out', required=True)
    args = ap.parse_args()

    kd = {c['literal']: c for c in json.load(open(args.kanjidic, encoding='utf-8'))['characters']}
    chars = collect_chars(args.input)

    out, missing, no_hanviet = [], [], []
    radicals = {}

    for ch, level in sorted(chars.items()):
        k = kd.get(ch)
        if not k:
            missing.append(ch)
            continue

        groups = (k.get('readingMeaning') or {}).get('groups') or []
        on, kun, hv = [], [], None
        for g in groups:
            for r in g.get('readings', []):
                if r['type'] == 'ja_on':
                    on.append({'text': r['value']})
                elif r['type'] == 'ja_kun':
                    kun.append({'text': r['value']})
                elif r['type'] == 'vietnam' and hv is None:
                    hv = r['value']

        # LUU Y: KANJIDIC2 KHONG co truong "cach doc thong dung". Co 'common'
        # duoi day la SUY DOAN theo thu tu: KANJIDIC2 xep cach doc chinh len
        # truoc, nen danh dau cach doc DAU TIEN cua moi loai (ON/KUN).
        # Day khong phai du lieu goc -- dung de goi y "hoc cai nay truoc",
        # khong phai khang dinh ve tan suat su dung.
        for lst in (on, kun):
            for i, r in enumerate(lst):
                r['common'] = (i == 0)
        if hv is None:
            no_hanviet.append(ch)

        # tat ca am Han Viet, ghep bang dau phay (vi du: Gian, Nhan)
        all_hv = [r['value'] for g in groups for r in g.get('readings', [])
                  if r['type'] == 'vietnam']

        rad_no = None
        for r in k.get('radicals', []):
            if r.get('type') == 'classical':
                rad_no = r.get('value')
                break
        if rad_no and rad_no not in radicals:
            radicals[rad_no] = radical_char(rad_no)

        misc = k.get('misc') or {}
        strokes = (misc.get('strokeCounts') or [None])[0]
        ucs = next((c['value'] for c in k.get('codepoints', []) if c['type'] == 'ucs'), None)

        out.append({
            'character': ch,
            'unicode': (ucs or '{:04X}'.format(ord(ch))).upper(),
            'jlpt_level': level,          # theo giao trinh, KHONG lay misc.jlptLevel
            'stroke_count': strokes,
            'radical_number': rad_no,
            'han_viet': ', '.join(all_hv) if all_hv else None,
            'grade': misc.get('grade'),
            'frequency': misc.get('frequency'),
            'readings_on': on,
            'readings_kun': kun,
            # nghia tieng Anh giu lai de tham chieu khi dich sang tieng Viet sau;
            # KHONG do thang vao kanji.meaning_vi.
            'meanings_en': [m['value'] for g in groups
                            for m in g.get('meanings', []) if m['lang'] == 'en'],
        })

    payload = {
        'license': LICENSE,
        'generated_from': os.path.basename(args.kanjidic),
        'kanji_count': len(out),
        'radicals': [{'number': n, 'character': c} for n, c in sorted(radicals.items())],
        'kanji': out,
    }
    with open(args.out, 'w', encoding='utf-8') as f:
        json.dump(payload, f, ensure_ascii=False, indent=1)

    print('Da ghi {} ({} kanji, {} bo thu)'.format(args.out, len(out), len(radicals)))
    if missing:
        print('Khong tim thay trong KANJIDIC2:', len(missing), missing[:20])
    if no_hanviet:
        print('Khong co am Han Viet:', len(no_hanviet), no_hanviet[:20])


if __name__ == '__main__':
    main()
