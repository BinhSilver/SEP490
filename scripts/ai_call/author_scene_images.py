"""Attach authored illustrations to scene occurrences, including shared states at a new venue."""
import json
import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

def refresh_scene_images(catalog):
    manifest = json.loads((ROOT/'data/ai-call/scene-images.json').read_text(encoding='utf-8'))
    urls = {j['key']:j['url'] for j in manifest['images']}
    def image(key): return urls[key]
    overrides = {
        'restaurant-order-and-pay': {
            'restaurant-entry':'GREETING SEATING STORY_LEAVE END_SUCCESS END_LEAVE STORY_FIRST_GOODBYE',
            'restaurant-waiting':'WAIT WAIT_CHOICE',
            'restaurant-sold-out':'SOLD_OUT',
            'restaurant-wrong-dish':'WRONG_DISH EXCHANGE',
            'restaurant-ingredients':'INGREDIENTS RESTRICTION MENU_COMPARE MENU_CHOOSE MENU_APPEARANCE',
            'restaurant-adjust':'ADJUST CONFIRM_ADJUST MENU_CONDITION',
            'restaurant-taste':'QUALITY_REPORT QUALITY_REQUEST QUALITY_OPTIONS QUALITY_CONFIRM QUALITY_CHECK',
            'restaurant-payment':'BILL SPLIT OWN_SHARE PAYMENT STORY_RECEIPT',
            'restaurant-other-shop':'STORY_SECOND_VISIT',
            'restaurant-closing':'RESTAURANT_HOURS RESTAURANT_TIME',
        },
        'first-meeting-introduction': {'meeting-group':'INTAKE GREETING ORIGIN NAME_FIX ORIGIN_FIX NAME_REPEAT', 'meeting-plans':'HOBBY SHARE ASK_BACK INVITE MEET_CONFLICT MEET_CONFIRM MEET_EXPERIENCE STORY_MEET_AGAIN'},
        'station-directions-and-transfer': {'station-tickets':'TICKET_HELP TICKET_CHECK', 'station-transfer':'CHECK_ROUTE TRANSFER VERIFY LINE PLATFORM NEXT_TRAIN ARRIVAL PLAN STORY_GATE', 'station-access':'ACCESS_HELP ACCESS_CONFIRM'},
        'convenience-store-checkout': {'convenience-payment':'RETRY OTHER_PAYMENT FINISH_PAY', 'convenience-shelves':'ITEM_IDENTIFY ITEM_SUBSTITUTE'},
        'clothes-shopping-size-fitting': {'clothes-fitting':'SIZE FITTING ASK_SIZE TRY_OTHER FIT_REPORT FIT_RECHECK', 'clothes-exchange':'EXPLAIN_SIZE SHOW_RECEIPT CONFIRM_SIZE'},
        'clinic-appointment': {'clinic-booking':'REQUEST_DAY ALTERNATIVE_DAY CONFIRM_BOOKING IDENTIFY_BOOKING REQUEST_CHANGE VERIFY_CHANGE CLINIC_PAPERS CLINIC_NEXT CLINIC_HOURS CLINIC_TIME_CHECK STORY_BOOKING_RECORD'},
        'pharmacy-cold-medicine': {'pharmacy-label':'USAGE MED_REPEAT MED_LABEL MED_UNCERTAIN MED_FOLLOWUP DISCLOSE ASK_COMBINATION FOLLOW_CHECK STORY_PHARMACY_CHECK'},
        'office-reschedule-meeting': {'office-online':'REQUEST_ONLINE CHECK_TOOL PREPARE MEETING_PREPARED MEETING_BACKUP', 'office-handover':'LEAVE_REQUEST LEAVE_HANDOVER'},
        'school-absence-and-homework': {'school-study':'MISSED_LESSON CATCH_UP MAKEUP_PLAN MAKEUP_CONFIRM HOMEWORK_HELP STUDY_HABIT STORY_STUDY_PLAN', 'school-submit':'STORY_SUBMISSION STORY_TEACHER_REPLY'},
        'hotel-check-in': {'hotel-room':'REPORT REQUEST_HELP ACCEPT_SOLUTION WIFI_REPORT WIFI_RECHECK ROOM_RECHECK', 'hotel-lost-property':'LOST_REPORT LOST_IDENTIFY LOST_PICKUP'},
        'part-time-job-interview': {'interview-documents':'JOB_DOCS JOB_DOCS_CONFIRM STORY_APPLICATION AVAILABILITY CONSTRAINT OFFER AGREEMENT'},
        'service-complaint-return': {'returns-repair':'REPAIR_AGAIN REPAIR_FOLLOW'},
        'project-deadline-negotiation': {'project-review':'REVIEW_CHANGE REVIEW_COMMIT STORY_HANDOFF STORY_REVIEW_ACK'},
        'rental-contract-negotiation': {'rental-repair':'DETAILS VISIT COST_NOTICE RENT_RECHECK RENT_NEXT'},
    }
    for group in catalog['scenarios']:
        slug=group['slug']
        if slug in urls: group['thumbnail']=image(slug)
        default=group['thumbnail']
        mapping={code:image(key) for key,codes in overrides[slug].items() for code in codes.split()}
        for case in group['cases']:
            flow=case['flow']
            for state in flow['states']: state['imageUrl']=mapping.get(state['code'],default)
            states={s['code']:s for s in flow['states']}
            for stage in flow.get('stages',[]):
                scenes=[]; second_venue=False; contextual=None
                for code in stage['stateCodes']:
                    second_venue |= code=='STORY_SECOND_VISIT'
                    url=states[code]['imageUrl']
                    if second_venue and code!='STORY_SECOND_VISIT': url=image('restaurant-other-meal')
                    if slug=='hotel-check-in':
                        if url in (image('hotel-room'),image('hotel-lost-property')):contextual=url
                        if code=='STORY_HOTEL_VERIFY' and contextual is None:contextual=image('hotel-room')
                    elif slug=='rental-contract-negotiation' and stage['code'] in ('repair','repair-recheck'):
                        if code=='STORY_RENT_ACTION':contextual=image('rental-repair')
                    elif slug=='service-complaint-return' and stage['code']=='repair-recurrence':
                        if code=='REPAIR_AGAIN':contextual=image('returns-repair')
                    if contextual:url=contextual
                    scenes.append(url)
                stage['imageUrls']=scenes
                # Show the distinctive beat rather than another generic opening image.
                stage['imageUrl']=next((url for url in scenes if url!=scenes[0] and url!=default),scenes[0])
                if slug=='restaurant-order-and-pay' and stage['code']=='normal':stage['imageUrl']=default
                if 'STORY_SECOND_VISIT' in stage['stateCodes']:stage['imageUrl']=image('restaurant-other-shop')

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--check',action='store_true',help='Validate mappings and packaged files without writing data.')
    args=parser.parse_args()
    path=ROOT/'data/ai-call/catalog.json'
    catalog=json.loads(path.read_text(encoding='utf-8'))
    original=json.dumps(catalog,ensure_ascii=False,sort_keys=True)
    refresh_scene_images(catalog)
    if args.check:
        if original!=json.dumps(catalog,ensure_ascii=False,sort_keys=True):raise ValueError('Catalog image mapping is stale.')
        used=set()
        for group in catalog['scenarios']:
            used.add(group['thumbnail'])
            for case in group['cases']:
                used.update(s['imageUrl'] for s in case['flow']['states'])
                for stage in case['flow'].get('stages',[]):
                    if len(stage['imageUrls'])!=len(stage['stateCodes']):raise ValueError('Invalid scene route.')
                    used.update(stage['imageUrls'])
        for url in used:
            if not (ROOT/'src/main/resources/static'/url.lstrip('/')).is_file():raise ValueError('Missing scene image: '+url)
        manifest=json.loads((ROOT/'data/ai-call/scene-images.json').read_text(encoding='utf-8'))
        if any(j['url'] not in used for j in manifest['images']):raise ValueError('Unused scene illustration.')
        print(f'Validated {len(used)} packaged images and all route mappings.')
        return
    path.write_text(json.dumps(catalog,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Scene images attached to all scenario states and stage snapshots.')

if __name__=='__main__': main()
