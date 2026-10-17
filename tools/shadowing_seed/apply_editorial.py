"""Apply editorial TSV corrections to cached audio evidence, never call AI APIs.

TSV columns: recognition row or inclusive range, Japanese, Vietnamese. A row omitted
from the authored TSV stays omitted; raw recognizer output is never auto-published.
"""
from pathlib import Path
import argparse
import json
import sys
import unicodedata
import fugashi
from enrich_offline import reading
from validate import DATA, read_json


def apply(source, evidence_dir):
    baseline = DATA / '.cache/before-mobile-editorial'
    old = read_json(baseline / f'{source}.cues.json')
    recognized = read_json(Path(evidence_dir) / f'{source}.json')
    available = set(range(1, len(old) + 1))
    tagger = fugashi.Tagger()
    rows = []
    included = set()
    for line in (DATA / 'editorial' / f'{source}.tsv').read_text(encoding='utf-8').splitlines():
        indexes, text, translation = line.split('\t')
        bounds = list(map(int, indexes.split('-')))
        first, last = bounds[0], bounds[-1]
        included.update(range(first, last + 1))
        start, end = recognized[first - 1]['start'], recognized[last - 1]['end']
        text = unicodedata.normalize('NFKC', text).strip()
        assert start < end and text and translation
        # Retain the closest existing cue ID where possible. The SQL patch refuses
        # retirement of any cue referenced by a learner's history.
        nearest = min(available, key=lambda n: abs(old[n-1]['start'] - start)) if available else None
        if nearest is not None:
            available.remove(nearest)
        rows.append(dict(order=len(rows)+1, start=start, end=end, text=text,
                         translation=translation, furigana=reading(text, tagger),
                         translation_source='EDITOR_CORRECTION', original_order=nearest,
                         evidence_rows=[first, last]))
    for a, b in zip(rows, rows[1:]):
        assert a['end'] <= b['start'], (source, a, b)
    target = DATA / '.cache' / f'{source}.cues.json'
    target.write_text(json.dumps(rows, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    catalog = read_json(DATA / 'catalog.json')
    video = next(v for v in catalog['videos'] if v['source_key'] == source)
    video['expected_cue_count'] = len(rows)
    video['caption_source'] = 'OFFLINE_ASR_EDITOR_CORRECTED'
    video['transcript_scope'] = ('Selected clear spoken dialogue after offline recognition and editorial correction. '
                                 'Introductory names, songs, English outro and uncertain audio are excluded. '
                                 'This is a learning transcript, not a verbatim accessibility transcript.')
    video['omitted_intervals'] = [dict(start=r['start'], end=r['end'], reason='Outside selected verified learning dialogue')
                                  for n, r in enumerate(recognized, 1) if n not in included]
    (DATA / 'catalog.json').write_text(json.dumps(catalog, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    return len(rows)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('sources', nargs='+')
    parser.add_argument('--evidence-dir', type=Path, default=DATA/'.cache/asr/editorial-repair')
    args = parser.parse_args()
    for source in args.sources:
        print(source, apply(source, args.evidence_dir))
