"""Assign scenario characters and reusable Fish Audio voices without changing routes."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DOC = ROOT / 'docs/ai-call/DANH-SACH-VAI-AI-VA-GIONG.md'
CATALOG = ROOT / 'data/ai-call/catalog.json'

# Reuse a profile between stories; every character in one story gets a different reference_id.
VOICES = {
    'welcome': '9ac93029cca64a4f938969a97235e045',
    'service': '835eddd64e474271a042b175425afc39',
    'cashier': '997db0af55f0411e82e7b0e2df8c6faa',
    'manager': 'cc0b286e294a43d7bdfbe1f26446b984',
    'friend': 'da9c1d6ff8664d26926112ac79a1d32b',
    'teacher-male': '3155b947de204555be8fff36a2bed0da',
    'teacher-female': '55d359edddc747b1a883d9baefe320d6',
    'calm-male': '45c5d3723c9c42f598e4776dcfd5f02d',
    'kamijou-touma': 'a003f93264534ec1aeafb1d07dcc5d59',
    'izuku-midoriya': '6a33cc6c58994fa1af41f578075e8357',
}
# AI id, display name, preferred voice, states; the first character is the default.
CAST = {
 'restaurant-order-and-pay': [
  ('AI02','Nhân viên phục vụ','service',[]),
  ('AI01','Nhân viên đón khách','welcome',['GREETING','WAIT','WAIT_CHOICE','SEATING','RESTAURANT_HOURS','RESTAURANT_TIME','STORY_SECOND_VISIT']),
  ('AI03','Thu ngân','cashier',['BILL','SPLIT','OWN_SHARE','PAYMENT','STORY_RECEIPT','STORY_LEAVE']),
  ('AI04','Quản lý nhà hàng','manager',['QUALITY_OPTIONS','QUALITY_CONFIRM'])],
 'first-meeting-introduction': [
  ('AI05','Người bạn mới','friend',[]),
  ('AI06','Người tổ chức buổi gặp mặt','teacher-female',['INTAKE','ISSUE_INTAKE','GREETING'])],
 'station-directions-and-transfer': [
  ('AI08','Nhân viên hướng dẫn tại ga','service',[]),
  ('AI07','Nhân viên bán vé','cashier',['TICKET_HELP','TICKET_CHECK']),
  ('AI09','Nhân viên trên tàu','welcome',['CHECK_ROUTE','TRANSFER','VERIFY'])],
 'convenience-store-checkout': [
  ('AI11','Thu ngân','cashier',[]),
  ('AI10','Nhân viên hỗ trợ tại kệ hàng','service',['ITEM_IDENTIFY','ITEM_SUBSTITUTE'])],
 'clothes-shopping-size-fitting': [
  ('AI12','Nhân viên tư vấn','service',[]),
  ('AI13','Thu ngân','cashier',['STORY_TRANSACTION','STORY_CLOTHES_CHECK']),
  ('AI14','Nhân viên đổi trả','welcome',['EXPLAIN_SIZE','SHOW_RECEIPT','CONFIRM_SIZE'])],
 'clinic-appointment': [('AI15','Lễ tân phòng khám','welcome',[])],
 'pharmacy-cold-medicine': [
  ('AI16','Dược sĩ','teacher-female',[]),
  ('AI17','Thu ngân','cashier',['STORY_PHARMACY_PLAN'])],
 'office-reschedule-meeting': [
  ('AI18','Đồng nghiệp','friend',[]),
  ('AI19','Trưởng nhóm','manager',['LEAVE_REQUEST','LEAVE_HANDOVER']),
  ('AI20','Trợ lý văn phòng','welcome',['CONTACT_PERSON','CONTACT_MESSAGE'])],
 'school-absence-and-homework': [
  ('AI21','Giáo viên','teacher-male',[]),
  ('AI22','Bạn cùng lớp','friend',['MISSED_LESSON','CATCH_UP','STORY_STUDY_PLAN'])],
 'hotel-check-in': [
  ('AI23','Lễ tân','welcome',[]),
  ('AI24','Nhân viên buồng phòng','service',['LOST_IDENTIFY','ROOM_RECHECK']),
  ('AI25','Nhân viên kỹ thuật','teacher-male',['WIFI_REPORT','WIFI_RECHECK']),
  ('AI26','Quản lý khách sạn','manager',['ACCEPT_SOLUTION'])],
 'part-time-job-interview': [
  ('AI28','Người phỏng vấn','manager',[]),
  ('AI27','Nhân viên tiếp nhận ứng viên','welcome',['JOB_DOCS','JOB_DOCS_CONFIRM','STORY_APPLICATION','STORY_RECRUITMENT_REPLY']),
  ('AI29','Người phụ trách ca làm','service',['AVAILABILITY','CONSTRAINT','OFFER','AGREEMENT'])],
 'service-complaint-return': [
  ('AI30','Nhân viên chăm sóc khách hàng','service',[]),
  ('AI31','Nhân viên kỹ thuật hoặc bảo hành','teacher-male',['REPAIR_AGAIN','REPAIR_FOLLOW']),
  ('AI32','Quản lý cửa hàng','manager',['RESOLUTION','OPTIONS','CHOOSE','RETURN_CONDITION','RETURN_CONFIRM'])],
 'project-deadline-negotiation': [
  ('AI33','Quản lý dự án','manager',[]),
  ('AI34','Đồng nghiệp phụ trách kiểm thử','friend',['RESOURCE_GAP','RESOURCE_PLAN']),
  ('AI35','Đại diện khách hàng','welcome',['CLARIFY_SCOPE','REVIEW_CHANGE','REVIEW_COMMIT'])],
 'rental-contract-negotiation': [
  ('AI37','Nhân viên quản lý nhà','manager',[]),
  ('AI36','Nhân viên môi giới','welcome',['ROOM_REQUIREMENTS','ROOM_COMPARE']),
  ('AI38','Nhân viên sửa chữa','teacher-male',['VISIT','RENT_RECHECK'])],
}
OPENINGS = {
 'GREETING': 'いらっしゃいませ。何名様ですか。',
 'SEATING': 'こちらのお席へどうぞ。',
 'BILL': 'お会計ですね。こちらで承ります。',
 'QUALITY_OPTIONS': '責任者です。お話は伺いました。対応を確認します。',
 'TICKET_HELP': '切符の購入をお手伝いします。',
 'CHECK_ROUTE': '車内の係員です。乗り換えについて確認しますね。',
 'ITEM_IDENTIFY': 'お探しの商品をお手伝いします。',
 'STORY_TRANSACTION': 'お会計、または交換のお手続きを承ります。',
 'EXPLAIN_SIZE': '交換のご相談を承ります。',
 'STORY_PHARMACY_PLAN': '会計担当です。薬剤師への確認が終わってから、ご購入のお手続きを承ります。',
 'LEAVE_REQUEST': 'お疲れさまです。勤務時間の相談ですね。',
 'CONTACT_PERSON': 'お電話ありがとうございます。ご用件を伺います。',
 'MISSED_LESSON': 'こんにちは。休んだ日の授業について、一緒に確認しよう。',
 'STORY_STUDY_PLAN': '課題の準備、一緒に確認しよう。',
 'STORY_SUBMISSION': '提出する課題について確認しますね。',
 'LOST_IDENTIFY': '客室担当です。お忘れ物について確認いたします。',
 'ROOM_RECHECK': '客室担当です。お部屋の状態をご確認ください。',
 'WIFI_REPORT': '技術担当です。接続の状況を確認いたします。',
 'ACCEPT_SOLUTION': '責任者です。ご案内した対応について確認いたします。',
 'JOB_DOCS': '受付担当です。応募書類を確認します。',
 'STORY_APPLICATION': '受付担当です。書類のお手続きを確認します。',
 'AVAILABILITY': 'シフト担当です。勤務できる時間について確認します。',
 'CONSTRAINT': 'シフト担当です。勤務時間のご相談を承ります。',
 'REPAIR_AGAIN': '修理担当です。不具合の状況を確認いたします。',
 'RESOLUTION': '責任者です。対応方法をご相談いたします。',
 'OPTIONS': '責任者です。可能な対応を確認いたします。',
 'RETURN_CONDITION': '責任者です。交換や返金の条件を確認いたします。',
 'RESOURCE_GAP': 'テスト担当です。作業の状況を一緒に確認しましょう。',
 'CLARIFY_SCOPE': '追加の依頼内容について確認しましょう。',
 'REVIEW_CHANGE': '納品後の確認事項についてご相談します。',
 'ROOM_REQUIREMENTS': 'お部屋探しを担当します。ご希望を伺います。',
 'VISIT': '修理担当です。点検の日時を確認いたします。',
 'RENT_RECHECK': '修理担当です。修理後の状態をご確認ください。',
}
INITIAL_OPENINGS = {
 'first-meeting-introduction':'こんにちは。交流会へようこそ。どんなお話をしたいですか。',
 'station-directions-and-transfer':'こんにちは。どちらへ行かれますか。',
 'convenience-store-checkout':'いらっしゃいませ。ご用件を伺います。',
 'clothes-shopping-size-fitting':'いらっしゃいませ。どのようなお洋服をお探しですか。',
 'clinic-appointment':'お電話ありがとうございます。診察のご予約でしょうか。',
 'pharmacy-cold-medicine':'こんにちは。お薬について、どのようなご相談ですか。',
 'office-reschedule-meeting':'お疲れさまです。どうしましたか。',
 'school-absence-and-homework':'こんにちは。どのようなご連絡ですか。',
 'hotel-check-in':'いらっしゃいませ。ご用件をお伺いします。',
 'part-time-job-interview':'こんにちは。本日はよろしくお願いします。',
 'service-complaint-return':'ご相談を承ります。どのような問題がありましたか。',
 'project-deadline-negotiation':'お疲れさまです。進捗について確認しましょう。',
 'rental-contract-negotiation':'お世話になっております。本日はどのようなご相談ですか。',
}

def assign_cast(catalog, choices):
    assigned = {}
    corrections = []
    for group in catalog['scenarios']:
        definitions = CAST[group['slug']]
        used = set()
        speakers = []
        for code,name,profile,states in definitions:
            voice = choices.get(code, VOICES[profile])
            if voice in used:
                # Prefer the matching reusable profile before taking another unused voice.
                replacement = next((v for v in [VOICES[profile], *VOICES.values()] if v not in used), None)
                if replacement is None:
                    raise ValueError(f"Thiếu giọng cho {name} trong {group['title']}; cần thêm mã giọng khác các vai còn lại.")
                corrections.append({'code':code,'requested':voice,'assigned':replacement})
                voice = replacement
            used.add(voice)
            speaker = {'code':code,'name':name,'voiceId':voice,
                       'description':f'Đóng vai {name.lower()} trong tình huống này.',
                       'speechStyle':'Nói tiếng Nhật tự nhiên theo vai, câu ngắn, lịch sự và rõ ràng.'}
            if code == 'AI05' or code == 'AI22':
                speaker['speechStyle']='Thân thiện, tự nhiên giữa bạn bè; giữ mức ngôn ngữ phù hợp người học.'
            if code == 'AI21':
                speaker['voiceVariants']={'male':VOICES['teacher-male'],'female':VOICES['teacher-female']}
                used.update(speaker['voiceVariants'].values())
            speakers.append(speaker)
            assigned[code]=speaker
        state_roles = {state:code for code,_,_,states in definitions for state in states}
        for case in group['cases']:
            flow=case['flow']
            flow['speakers']=speakers
            for state in flow['states']:
                state['speakerCode']=state_roles.get(state['code'],definitions[0][0])
                if group['slug']=='first-meeting-introduction' and state['code']=='GREETING':
                    state['openingLine']='こんにちは。交流会へようこそ。お名前を教えていただけますか。'
                else:
                    state['openingLine']=OPENINGS.get(state['code'],'よろしくお願いします。引き続き確認しますね。')
                if state['code']==flow['initialState'] and group['slug'] in INITIAL_OPENINGS:
                    state['openingLine']=INITIAL_OPENINGS[group['slug']]
            by_code={s['code']:s for s in flow['states']}
            for stage in flow.get('stages',[]):
                roles=[]
                for state_code in stage['stateCodes']:
                    role=by_code[state_code]['speakerCode']
                    if state_code in flow['terminalStateCodes'] and roles: role=roles[-1]
                    # Pending medication checks remain with the pharmacist; no automatic purchase.
                    if group['slug']=='pharmacy-cold-medicine' and stage['code']!='standard': role='AI16'
                    # An exchange transaction stays with the exchange clerk, not a new sales cashier.
                    if group['slug']=='clothes-shopping-size-fitting' and stage['code']=='exchange' and state_code.startswith('STORY_'): role='AI14'
                    roles.append(role)
                stage['speakerCodes']=roles
                stage['speakers']=speakers
    return assigned, corrections

def refresh_existing_cast(catalog):
    choices={s['code']:s['voiceId'] for g in catalog['scenarios'] for s in g['cases'][0]['flow'].get('speakers',[])}
    if choices: assign_cast(catalog, choices)

def main():
    text = DOC.read_text(encoding='utf-8')
    choices = {}
    for line in text.splitlines():
        match = re.match(r'\| (AI\d\d) \|', line)
        ids = re.findall(r'\b[0-9a-f]{32}\b', line)
        if match and ids: choices[match[1]] = ids[0]
    catalog = json.loads(CATALOG.read_text(encoding='utf-8'))
    assigned, corrections = assign_cast(catalog, choices)
    from author_scene_images import refresh_scene_images
    refresh_scene_images(catalog)
    CATALOG.write_text(json.dumps(catalog,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    manifest={'provider':'fish.audio','voices':VOICES,'assignments':assigned,'resolvedDuplicates':corrections,
              'policy':'Reuse between scenarios; distinct voice IDs for all characters within a scenario.'}
    (ROOT/'data/ai-call/voices.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    # Normalize user-entered IDs into the actual voice column and preserve all descriptions.
    lines=[]
    for line in text.splitlines():
        match=re.match(r'\| (AI\d\d) \|',line)
        if match:
            columns=line.split('|')
            columns[5]=' '+assigned[match[1]]['voiceId']+' '
            line='|'.join(columns[:6])+'|'
        lines.append(line)
    text='\n'.join(lines)+'\n'
    text=text.replace('Đây là danh sách đề xuất cho bản nâng cấp nhiều nhân vật, chưa phải các vai đã được triển khai.', 'Danh sách nhân vật và phân giọng cho bản nâng cấp AI Call nhiều nhân vật.')
    text=text.replace('Có thể dùng cùng một giọng cho nhiều vai.', 'Có thể dùng lại giọng giữa các kịch bản; các vai trong cùng kịch bản dùng giọng khác nhau.')
    text=text.replace('- Các vai có thể dùng chung giọng; không cần 38 giọng khác nhau.', '- Có thể dùng lại giọng giữa các kịch bản, nhưng không dùng một mã giọng cho hai vai trong cùng kịch bản. Không cần 38 giọng khác nhau.')
    header='## Phân giọng đã chốt theo yêu cầu'
    if header not in text:
        text+='\n'+header+'\n\nĐã phân 38 vai bằng 7 mã giọng có sẵn. AI05 giữ giọng bạn chọn; AI06 dùng giọng `55d359edddc747b1a883d9baefe320d6` để không trùng với AI05. Đây là phân công giọng theo vai; chưa xác nhận chất giọng bằng nghe thử.\n\nGiáo viên AI21 mặc định giọng nam `3155b947de204555be8fff36a2bed0da`; có thể chọn giọng nữ `55d359edddc747b1a883d9baefe320d6`. Bạn cùng lớp AI22 dùng giọng khác cả hai lựa chọn.\n'
    DOC.write_text(text,encoding='utf-8')
    used_voices = {voice for speaker in assigned.values()
                   for voice in [speaker['voiceId'], *speaker.get('voiceVariants', {}).values()]}
    print(f'Assigned {len(assigned)} characters using {len(used_voices)} voices '
          f'from {len(VOICES)} reusable profiles; resolved {len(corrections)} duplicate assignment(s).')

if __name__=='__main__':main()
