"""Add a connected feedback branch to the existing restaurant, without new cases.

The skill transfer is original content: excess/adjustment (Minna lesson 44),
explanation/request (26/14), verification (40). Run before generate_seed.py.
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
path = ROOT / 'data/ai-call/catalog.json'
catalog = json.loads(path.read_text(encoding='utf-8'))
group = next(g for g in catalog['scenarios'] if g['slug'] == 'restaurant-order-and-pay')
case = group['cases'][0]
flow = case['flow']

steps = [
    ('QUALITY_REPORT', 'Báo món quá mặn hoặc quá cay',
     'Gọi nhân viên, nói món nào và vấn đề về vị của món vừa nhận.',
     'Chỉ mở nhánh khi khách phản hồi món đã nhận quá mặn hoặc quá cay; không tự gây sự cố. '
     'Xin lỗi, nhắc lại món và vấn đề khách vừa nêu. Không phủ nhận khẩu vị của khách. '
     'Câu khách có thể dùng: すみません。このうどんはしょっぱすぎます。 Hỏi khách muốn được hỗ trợ thế nào.',
     'すみません。このうどんはしょっぱすぎます。', 'triangle-exclamation'),
    ('QUALITY_REQUEST', 'Nêu cách muốn xử lý',
     'Đề nghị làm lại nhạt hơn, giảm cay hoặc đổi sang món khác; nêu điều muốn giữ hoặc thay đổi.',
     'Làm rõ yêu cầu làm lại nhạt hơn/ít cay hơn hoặc đổi món. Đó là yêu cầu sau khi nhận món, '
     'khác với ADJUST trước khi gọi món. Chưa khẳng định làm được hoặc miễn phí. '
     'Nếu yêu cầu chưa rõ, hỏi đúng một chi tiết; không chọn cách xử lý thay khách.',
     'もう少し薄い味で作り直してもらえますか。', 'sliders'),
    ('QUALITY_OPTIONS', 'Hỏi cách xử lý, thời gian và phí',
     'Hỏi hoặc xác nhận phương án quán có thể thực hiện cùng thời gian chờ và khoản phí nếu có.',
     'Dựa vào yêu cầu khách và đơn hiện tại, đưa phương án cụ thể: làm lại udon nhạt hơn khoảng 10 phút '
     'hoặc đổi món còn bán. Nếu khách đã biết một món hết, không đề xuất món đó. '
     'Trong tình huống mẫu quán có thể đồng ý làm lại cùng món không thu thêm, nhưng phải nói rõ điều đó '
     'sau khi kiểm tra; không mặc định mọi khiếu nại đều được miễn phí. Nếu đổi món có giá khác, '
     'nêu chênh lệch trước khi làm và chỉ khi có giá trong hội thoại. Nếu thời gian/giá chưa rõ thì hỏi lại quán. '
     'Ghi nhận câu hỏi hoặc sự xác nhận của khách về các điều kiện đã nói.',
     'どのくらいかかりますか。追加料金はありますか。', 'clock'),
    ('QUALITY_CONFIRM', 'Chốt phương án xử lý',
     'Chọn hoặc xác nhận rõ món, mức vị, thời gian và điều kiện xử lý đã được quán thông báo.',
     'Nhắc lại phương án vừa thống nhất và chờ khách đồng ý: món nào, vị nhạt/ít cay, thời gian chờ '
     'và điều kiện phí nếu có. Không tự làm khi khách chưa đồng ý. Nếu khách không chờ được hoặc '
     'chưa đồng ý điều kiện, quay lại QUALITY_OPTIONS, giữ nguyên món và vấn đề trong lịch sử.',
     'では、薄い味で作り直してください。10分なら待ちます。', 'clipboard-check'),
    ('QUALITY_CHECK', 'Kiểm tra món sau xử lý',
     'Xác nhận món được làm lại đúng yêu cầu và cảm ơn; nếu vẫn chưa phù hợp, mô tả vấn đề còn lại.',
     'Đưa đúng món đã thống nhất làm lại hoặc đổi. Hỏi vị bây giờ đã phù hợp chưa. '
     'Chỉ ghi QUALITY_CHECK hoàn thành khi khách xác nhận đã phù hợp. Nếu khách vẫn nêu vấn đề '
     'thì quay lại QUALITY_REPORT, không đánh dấu đã xử lý xong. Không tự tạo sự cố lần thứ hai. '
     'Khi đã phù hợp, về SERVED để khách tiếp tục bữa ăn, gọi thêm hoặc xin hóa đơn. '
     'Không thu tiền lần hai cho cùng món nếu quán đã đồng ý làm lại không thu thêm.',
     '今度はちょうどいいです。ありがとうございます。', 'bowl-food'),
]

def upsert(items, value, key='code'):
    for index, old in enumerate(items):
        if old[key] == value[key]:
            items[index] = value
            return
    items.append(value)

for index, (code, title, summary, prompt, reply, icon) in enumerate(steps):
    upsert(flow['goals'], dict(code=code, title=title, description=summary, type='MISSION',
                              displayOrder=20+index, required=False,
                              conditions=[dict(type='OBJECTIVE_FLAG', value=code)]))
    upsert(flow['states'], dict(code=code, name=title, summary=summary, prompt=prompt,
                               allowedGoalCodes=[code], suggestedReplies=[reply],
                               column=12+index, row=4, icon='fa-solid fa-'+icon, transitionText=title))

def connect(source, target, label, required=(), entry=()):
    edge = dict(source=source, target=target, label=label,
                requiredGoalCodes=list(required), entryGoalCodes=list(entry))
    for i, old in enumerate(flow['edges']):
        if (old['source'], old['target']) == (source, target):
            flow['edges'][i] = edge
            return
    flow['edges'].append(edge)

connect('SERVED', 'QUALITY_REPORT', 'Khách báo món quá mặn hoặc quá cay', entry=['QUALITY_REPORT'])
connect('QUALITY_REPORT', 'QUALITY_REQUEST', 'Đã nêu món và vấn đề', ['QUALITY_REPORT'], ['QUALITY_REQUEST'])
connect('QUALITY_REQUEST', 'QUALITY_OPTIONS', 'Đã nêu cách muốn xử lý', ['QUALITY_REQUEST'], ['QUALITY_OPTIONS'])
connect('QUALITY_OPTIONS', 'QUALITY_CONFIRM', 'Đã rõ thời gian và điều kiện, muốn chốt', ['QUALITY_OPTIONS'], ['QUALITY_CONFIRM'])
connect('QUALITY_OPTIONS', 'QUALITY_REQUEST', 'Điều kiện chưa phù hợp, muốn đổi yêu cầu', ['QUALITY_OPTIONS'], ['QUALITY_REQUEST'])
connect('QUALITY_CONFIRM', 'QUALITY_OPTIONS', 'Khách sửa lựa chọn hoặc cần làm rõ điều kiện')
connect('QUALITY_CONFIRM', 'QUALITY_CHECK', 'Đã đồng ý phương án, quán thực hiện', ['QUALITY_CONFIRM'])
connect('QUALITY_CHECK', 'SERVED', 'Món sau xử lý phù hợp', ['QUALITY_CHECK'], ['RECEIVE'])
connect('QUALITY_CHECK', 'QUALITY_REPORT', 'Khách báo vẫn chưa phù hợp', entry=['QUALITY_REPORT'])

states = {s['code']: s for s in flow['states']}
states['SERVED']['prompt'] = (
    'Đưa món, để khách kiểm tra và cảm ơn. Nếu khách nói món quá mặn hoặc quá cay thì đi QUALITY_REPORT, '
    'không ép khách cảm ơn/xác nhận món phù hợp trước khi báo vấn đề. Không tự tạo vấn đề về vị. '
    'Sau khi làm lại hoặc đổi món, giữ yêu cầu và kết quả xử lý trong lịch sử. '
    'Khi khách đã xác nhận món phù hợp, hỏi có cần thêm gì; chỉ gọi thêm hoặc đưa hóa đơn khi khách yêu cầu.'
)
states['SERVED']['summary'] = 'Kiểm tra món; nếu có vấn đề thì báo quán, nếu phù hợp thì tiếp tục bữa ăn.'
bill_adjustment = (
    ' Nếu trước đó quán đã làm lại hoặc đổi món, áp dụng đúng điều kiện phí đã thống nhất; '
    'không tính cả món được thay và món thay thế như hai phần đã gọi.'
)
states['BILL']['prompt'] = states['BILL']['prompt'].replace(bill_adjustment, '')+bill_adjustment
upsert(flow['paths'], dict(code='taste-feedback', title='Món quá mặn hoặc quá cay',
                         stateCodes=['SERVED', *[s[0] for s in steps], 'SERVED', 'BILL', 'PAYMENT', 'END_SUCCESS']))
upsert(flow['paths'], dict(code='wrong-then-feedback', title='Đổi món rồi kiểm tra vị',
                         stateCodes=['CONFIRM', 'WRONG_DISH', 'EXCHANGE', 'SERVED',
                                     *[s[0] for s in steps], 'SERVED', 'BILL', 'PAYMENT', 'END_SUCCESS']))
flow['skillTransfers'] = [dict(lessons=[8,14,26,40,44], stateCodes=[s[0] for s in steps],
    explanation='Chuyển kỹ năng nhận xét, giải thích, yêu cầu điều chỉnh và kiểm tra kết quả sang món ăn.')]

def term(term, reading, meaning, example):
    return dict(term=term, reading=reading, meaning=meaning, example=example, jlpt='N4')
for word in [
    term('しょっぱい', 'しょっぱい', 'mặn', 'このうどんはしょっぱいです。'),
    term('薄い味', 'うすいあじ', 'vị nhạt', '薄い味でお願いします。'),
    term('作り直す', 'つくりなおす', 'làm lại', '作り直してもらえますか。'),
    term('追加料金', 'ついかりょうきん', 'phí phát sinh', '追加料金はありますか。'),
    term('ちょうどいい', 'ちょうどいい', 'vừa phải, phù hợp', '今度はちょうどいいです。'),
]:
    upsert(group['learning']['vocabulary'], word, 'term')
for pattern in [
    dict(pattern='〜すぎます', explanation='Nói mức độ vượt quá mong muốn, chẳng hạn món ăn quá mặn hoặc quá cay.', example='このうどんはしょっぱすぎます。'),
    dict(pattern='〜てもらえますか', explanation='Nhờ người đối thoại hỗ trợ thực hiện một việc.', example='もう少し薄い味で作り直してもらえますか。'),
    dict(pattern='〜なら', explanation='Nêu điều kiện để chấp nhận một phương án.', example='10分なら待ちます。'),
]:
    upsert(group['learning']['grammar'], pattern, 'pattern')

lines = [
    ('ai','お待たせしました。うどんです。','Xin lỗi đã để quý khách chờ. Udon đây ạ.'),
    ('learner','すみません。このうどんはしょっぱすぎます。','Xin lỗi, phần udon này mặn quá.'),
    ('ai','申し訳ありません。どういたしましょうか。','Chúng tôi xin lỗi. Quý khách muốn chúng tôi xử lý thế nào ạ?'),
    ('learner','もう少し薄い味で作り直してもらえますか。','Có thể làm lại với vị nhạt hơn một chút được không?'),
    ('ai','作り直せますが、10分ほどかかります。','Chúng tôi có thể làm lại, nhưng mất khoảng 10 phút.'),
    ('learner','追加料金はありますか。','Có phí phát sinh không ạ?'),
    ('ai','同じうどんの作り直しですので、追加料金はありません。','Làm lại cùng món udon nên không có phí phát sinh ạ.'),
    ('learner','では、薄い味で作り直してください。10分なら待ちます。','Vậy xin làm lại với vị nhạt hơn. Nếu 10 phút thì tôi sẽ chờ.'),
    ('ai','お待たせしました。薄い味で作り直しました。いかがですか。','Xin lỗi đã để quý khách chờ. Chúng tôi đã làm lại nhạt hơn. Quý khách thấy thế nào ạ?'),
    ('learner','今度はちょうどいいです。ありがとうございます。','Lần này vừa vị rồi. Cảm ơn ạ.'),
    ('ai','ありがとうございます。ほかに何かいかがですか。','Cảm ơn quý khách. Quý khách có dùng thêm gì không ạ?'),
    ('learner','いいえ。お会計をお願いします。','Không ạ. Cho tôi thanh toán.'),
]
case['learning']['branchDialogues'] = [dict(code='taste-feedback', title='Món quá mặn: xin làm lại và kiểm tra',
    description='Cùng bối cảnh nhà hàng, vận dụng cách nói quá mức và yêu cầu điều chỉnh. Không cần tạo kịch bản cắt tóc riêng.',
    exampleDialogue=[dict(speaker=speaker, text=text, translation=translation) for speaker,text,translation in lines])]
path.write_text(json.dumps(catalog, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
print(f"Restaurant: {len(flow['states'])} nodes, {len(flow['edges'])} edges, {len(flow['paths'])} preview paths; IDs unchanged.")
