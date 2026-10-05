"""Author game-stage prerequisites and full practice routes inside existing graphs."""
import json
from collections import deque
from pathlib import Path
from generate_seed import validate

ROOT=Path(__file__).resolve().parents[2]
DEPENDENCIES={
 'restaurant-order-and-pay':{'adjust':['ingredients'],'taste-feedback':['adjust'],
     'wrong-then-feedback':['wrong-dish','taste-feedback'],'split-bill':['add-more'],
     'appearance-conditions':['ingredients','menu-compare'],
     'sold-out-other-restaurant':['sold-out'],'crowded-other-restaurant':['crowded'],
     'closing-other-restaurant':['opening-hours']},
 'first-meeting-introduction':{'meet-reschedule':['shared-hobby'],'experience':['shared-hobby']},
 'station-directions-and-transfer':{'sign-help':['wrong-line'],'luggage-access':['ticket-help']},
 'convenience-store-checkout':{'quantity-fix':['stock-substitute']},
 'clothes-shopping-size-fitting':{'fit-feedback':['size-unavailable'],'exchange':['fit-feedback']},
 'clinic-appointment':{'verify-hours':['reschedule']},
 'pharmacy-cold-medicine':{'missing-medication-info':['existing-medication']},
 'office-reschedule-meeting':{'preparation':['online-meeting'],'leave-handover':['time-rejected']},
 'school-absence-and-homework':{'makeup-plan':['homework-deadline'],'homework-help':['return-to-school']},
 'hotel-check-in':{'wifi-help':['service-permission'],'room-recheck':['room-problem']},
 'part-time-job-interview':{'ability-preparation':['no-experience']},
 'service-complaint-return':{'return-conditions':['exchange-unavailable'],'repair-recurrence':['return-conditions']},
 'project-deadline-negotiation':{'resource-gap':['delay'],'handoff-review':['scope-change']},
 'rental-contract-negotiation':{'house-rules':['room-search'],'repair-recheck':['repair']},
}

def route(flow,source,target):
    queue=deque([[source]]);seen=set()
    while queue:
        path=queue.popleft();tail=path[-1]
        if tail==target:return path
        if tail in seen:continue
        seen.add(tail)
        queue.extend(path+[e['target']] for e in flow['edges'] if e['source']==tail)
    raise ValueError('No route to '+target)

def main():
    file=ROOT/'data/ai-call/catalog.json';catalog=json.loads(file.read_text(encoding='utf-8'))
    count=0
    for group in catalog['scenarios']:
        flow=group['cases'][0]['flow'];first=flow['paths'][0]['code'];plans=[]
        states={s['code']:s for s in flow['states']}
        for path in flow['paths']:
            preview=path['stateCodes']
            full=route(flow,flow['initialState'],preview[0])+preview[1:]
            if full[-1] not in flow['terminalStateCodes']:
                full+=route(flow,full[-1],'END_SUCCESS')[1:]
            prerequisites=[] if path['code']==first else DEPENDENCIES.get(group['slug'],{}).get(path['code'],[first])
            goals=list(dict.fromkeys(code for state in full for code in states[state]['allowedGoalCodes']))
            plans.append(dict(code=path['code'],title=path['title'],prerequisites=prerequisites,
                              stateCodes=full,requiredGoalCodes=goals))
        by_code={p['code']:p for p in plans};visiting=set()
        def tier(code):
            p=by_code[code]
            if 'tier' in p:return p['tier']
            if code in visiting:raise ValueError('Cyclic stage prerequisite: '+code)
            visiting.add(code)
            p['tier']=1+max((tier(c) for c in p['prerequisites']),default=-1)
            visiting.remove(code);return p['tier']
        for p in plans:tier(p['code'])
        rows={}
        for index,p in enumerate(plans):
            p['number']=index+1;p['row']=rows.get(p['tier'],0);rows[p['tier']]=p['row']+1
        flow['stages']=plans;count+=len(plans)
    from author_cast import refresh_existing_cast
    refresh_existing_cast(catalog)
    from author_scene_images import refresh_scene_images
    refresh_scene_images(catalog)
    validate(catalog['scenarios'])
    file.write_text(json.dumps(catalog,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(f'Authored {count} stages in 14 existing scenarios; prerequisites form an acyclic map.')

if __name__=='__main__':main()
