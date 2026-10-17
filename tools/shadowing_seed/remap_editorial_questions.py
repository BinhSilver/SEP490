"""Re-anchor reviewed questions after editorial cue replacement, without paid calls."""
import json
from validate import DATA, read_json, materialize

OVERRIDES = {
    'AzZEEfy6YNw:04': '自宅に招き、ご馳走していた',
    'AzZEEfy6YNw:06': '世界中の人とつながりたい',
    'AzZEEfy6YNw:08': 'お互いの文化や歴史に触れることができます',
    'rnCXcKc8y0g:07': '自分から挨拶をすることを決めました',
    'Pj2eo2MnOUQ:01': 'ふるさと',
    'Pj2eo2MnOUQ:07': '思い出させてくれる心の道です',
    'P04pWou9oE4:08': '本当にビーチの目の前です。',
    'wbQgGx27AkQ:07': '知り合って',
    'z6O3dfDSHmA:02': '一番',
    'z6O3dfDSHmA:04': '忘れ物のふりして戻って',
    'z6O3dfDSHmA:07': 'あのペンが欲しい',
    'XV6g5Y7gNCs:06': '日本',
    'XtyYeTm1diw:07': '隣のエレベーターで',
    'XV6g5Y7gNCs:07': '旅行もできるようにします',
    'Hxh6VtpPGYU:07': '桃太郎を大切に育てました',
    'Hxh6VtpPGYU:08': '昔、あるところにおじいさんとおばあさんが住んでいました。',
    '3I1okHFvBgE:08': 'まあ確かに早めに出た方がいいね。',
}

def remap(sources):
    baseline = DATA / '.cache/before-mobile-editorial'
    old_questions = {q['key']:q for q in read_json(baseline/'questions.json')['questions']}
    doc = read_json(DATA/'questions.json')
    for q in doc['questions']:
        source = q['source_key']
        if source not in sources:
            continue
        old = read_json(baseline/f'{source}.cues.json')
        rows = read_json(DATA/f'.cache/{source}.cues.json')
        original = old_questions[q['key']]
        answer = OVERRIDES.get(q['key'], materialize(original, old)['answer'])
        matches = [r for r in rows if answer in r['text']]
        assert matches, (q['key'], answer)
        row = min(matches, key=lambda r:abs(r['start']-original['anchor_start']))
        if q['key']=='XV6g5Y7gNCs:06':
            row = rows[7]
        if q['kind']=='GAP' and q['level']=='HARD':
            answer = row['text']
        first = last = row['order']
        if q['kind']=='ACTION_SEQUENCE':
            first -= 1
        q.update(first_order=first,last_order=last,answer_order=row['order'],
                 answer_start=row['text'].index(answer)+1,answer_length=len(answer),
                 anchor_start=rows[first-1]['start'],anchor_end=rows[last-1]['end'])
        if q['kind']=='GAP':
            q['explanation_vi'] = 'Đáp án lấy trực tiếp từ lời nói trong đoạn: 「'+answer+'」。 '+row['translation']
    (DATA/'questions.json').write_text(json.dumps(doc,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

if __name__=='__main__':
    import sys
    remap(sys.argv[1:])
