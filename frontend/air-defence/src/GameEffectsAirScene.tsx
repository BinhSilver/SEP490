import { useEffect, useState } from 'react';
import { PixiBattleCanvas } from './game/PixiBattleCanvas';
import { AUGMENTS_POOL, getComboMilestoneData, setPlaneSkin, useAirDefenseStore } from './game/useAirDefenseStore';
import { AugmentDraft } from './App';
import type { TargetWord, LootItem } from './game/types';
import type { GameEffectsAudio } from './GameEffectsAudio';

// Only presentation state is changed here. No controller, session or score API is started.
function resetAir() {
  useAirDefenseStore.setState({ paused:true, screen:'endless', mode:'endless', bootStatus:'ready', match:null,
    outcome:'playing', finishingPresentation:false, finishPresentationUntil:null,
    hp:100, maxHp:100, shield:0, combo:0, hyperBeamCharge:0, hyperBeamActive:false, hyperBeamPhase:'idle',
    targets:[], lootItems:[], floatingTexts:[], draftAugments:[], activeAugments:[], freezeTimer:0,
    lastLaserTarget:null, typingBulletTarget:null, dangerZoneActive:false, screenShake:false,
    inboundBoss:false, activeComboMilestone:null, comboBreakActive:false, wrongInputs:0, isTransitioning:false,
    introState:{active:false,phase:'done'},
    waveTransition:{active:false,phase:'none',clearedWave:0,incomingWave:1,isBoss:false} });
}
function target(type:TargetWord['type']='MONSTER_NORMAL', opponent=false):TargetWord {
  return {id:'preview-target',wordId:'preview',prompt:'学校',answer:'trường học',kanji:'学校',reading:'がっこう',
    meaning:'trường học',hanViet:'học hiệu',posX:50,posY:28,speed:0,type,currentHp:5,maxHp:5,
    ...(opponent?{fromAttack:true,fromName:'Đối thủ'}:{})};
}
const lootTypes = {credit:'CREDIT_CRYSTAL',repair:'REPAIR_PACK',energy:'HYPER_ORB'} as const;
function loot(type:LootItem['type'],index=0):LootItem {
  return {id:`preview-loot-${index}`,type,x:50+index*15,y:35,vx:0,vy:0,value:10,collected:false,spawnTime:Date.now()};
}
/** Máy bay cần xem: máy bay gốc (renderCode = mã) hoặc trang phục cửa hàng (gói skin, bộ vũ khí mượn). */
export interface PlaneSkin { code:string; renderCode:string; name:string; image?:string; packPath?:string|null; weaponTheme?:string|null }
export function AirScene({skin,action,replay,audio}:{skin:PlaneSkin;action:string;replay:number;audio:GameEffectsAudio}) {
  const [ready,setReady]=useState(false);
  const skinKey=`${skin.code}|${skin.renderCode}|${skin.packPath||''}|${skin.weaponTheme||''}`;
  useEffect(()=>{
    audio.restartScene(); setPlaneSkin(skin.code,skin.renderCode,skin.image,skin.packPath,skin.weaponTheme,skin.name); resetAir(); setReady(true);
    const timers=new Set<number>(); let animation=0;
    const later=(fn:()=>void,ms:number)=>{ const id=window.setTimeout(()=>{timers.delete(id);fn();},ms);timers.add(id); };
    const patch=useAirDefenseStore.setState;
    const shoot=()=>{const s=useAirDefenseStore.getState();patch({typingBulletCount:s.typingBulletCount+1,typingBulletTarget:{x:50,y:28}});audio.playEffect('air','fire');};
    const impact=()=>{patch({lastLaserTarget:{x:50,y:28}});audio.playEffect('air',action==='boss_hit'?'boss_hit':'impact');later(()=>patch({lastLaserTarget:null}),180);};
    const run=()=>{
      resetAir();
      const enemyType:TargetWord['type']=action==='enemy_fast'?'MONSTER_FAST':action==='enemy_mine'?'SPACE_MINE':
        ['boss','attack_boss','boss_hit','boss_explode','bgm_boss'].includes(action)?'MINI_BOSS':'MONSTER_NORMAL';
      if (['fire','impact','explode','enemy_normal','enemy_fast','enemy_mine','boss','attack_boss','boss_hit','boss_explode','bgm_boss'].includes(action))
        patch({targets:[target(enemyType,action==='attack_boss')]});
      if (!['hit','defeat','shield_block','impact','explode','boss_hit','boss_explode'].includes(action) && !action.startsWith('pickup_')) audio.playEffect('air',action);
      if (action==='fire') shoot();
      if (action==='impact'||action==='boss_hit') later(()=>{impact(); if(action==='boss_hit')patch({targets:[{...target('MINI_BOSS'),currentHp:3}]});},500);
      if (action==='explode'||action==='boss_explode') {
        later(shoot,350); later(()=>{impact();patch({targets:[{...target(enemyType),isDead:true,currentHp:0}]});audio.playEffect('air',action);},850);
      }
      if (action==='hit'||action==='defeat') later(()=>{patch({hp:action==='defeat'?0:65,screenShake:true});audio.playEffect('air',action);},500);
      if (action==='miss') later(()=>{patch({wrongInputs:1,comboBreakActive:true});},450);
      if (action==='danger') patch({hp:15,dangerZoneActive:true});
      if (action==='shield'||action==='shield_block') {patch({shield:3});if(action==='shield_block')later(()=>{patch({shield:2,screenShake:true});audio.playEffect('air',action);},650);}
      if (action==='charge') {
        // The real sequence: a 900 ms charge, the release, then rest before the loop repeats.
        patch({hyperBeamPhase:'charge'});
        later(()=>patch({hyperBeamPhase:'firing',hyperBeamActive:true}),900);
        later(()=>patch({hyperBeamPhase:'idle',hyperBeamActive:false}),1500);
      }
      if (action==='beam') patch({hyperBeamPhase:'firing',hyperBeamActive:true});
      if (action==='beam_ready') patch({hyperBeamCharge:100});
      if (action==='beam_cooldown') {patch({hyperBeamPhase:'firing',hyperBeamActive:true});later(()=>patch({hyperBeamPhase:'cooldown',hyperBeamActive:false}),1000);}
      if (/^combo(?:5|10|20|50)$/.test(action)) {const combo=Number(action.slice(5));patch({combo,activeComboMilestone:getComboMilestoneData(combo,50,38)});}
      if (action==='combo_gain') {patch({combo:2});later(()=>patch({combo:3}),500);}
      if (action==='combo_break') {patch({combo:10});later(()=>{patch({combo:0,comboBreakActive:true});audio.playEffect('air','combo_break');},500);}
      if (action==='boss_alert') patch({inboundBoss:true});
      if (['wave_clear','warp','wave_incoming','boss_wave'].includes(action)) patch({waveTransition:{active:true,
        phase:action==='wave_clear'?'cleared':action==='warp'?'warp':'incoming',clearedWave:4,incomingWave:5,isBoss:action==='boss_wave'}});
      if (action.startsWith('intro_')) {
        const phase=action==='intro_boot'?'boot':action==='intro_warp'?'warpin':'ready';
        patch({match:{sessionId:'effects-preview',playMode:'SOLO',versus:false,meId:1,roomId:null,deckId:0,deckTitle:'Xuất kích',
          questionField:'KANJI',answerField:'MEANING',questionLabel:'Kanji',answerLabel:'Nghĩa',attackMilestones:[],startAt:Date.now()+4200,endsAt:null,words:[]},
          introState:{active:true,phase}});
      }
      if (action.startsWith('loot_')||action.startsWith('pickup_')) {
        const kind=action.split('_')[1] as keyof typeof lootTypes;
        const items=action==='loot_magnet'?Object.values(lootTypes).map((type,i)=>loot(type,i-1)):[loot(lootTypes[kind])];
        patch({lootItems:items,...(kind==='repair'?{hp:40}:{})});
        if (action.startsWith('pickup_')||action==='loot_magnet') {
          const start=performance.now();
          const move=()=>{
            const t=Math.min(1,(performance.now()-start)/1500),ease=t*t;
            patch({lootItems:items.map(item=>({...item,x:item.x+(50-item.x)*ease,y:item.y+(80-item.y)*ease,collected:t===1}))});
            if (t<1) animation=requestAnimationFrame(move);
            else {patch({hp:kind==='repair'?70:100,hyperBeamCharge:kind==='energy'?50:0,
              floatingTexts:[{id:'pickup',text:kind==='repair'?'+30 HP':kind==='energy'?'+50 NĂNG LƯỢNG':'+10 XU',color:kind==='repair'?'#4ade80':'#fbbf24',x:50,y:70,opacity:1,life:60}]});
              audio.playEffect('air',action==='loot_magnet'?'pickup_credit':action);}
          }; animation=requestAnimationFrame(move);
        }
      }
      if (action==='augment_draft'||action.startsWith('augment:')) patch({draftAugments:action==='augment_draft'?AUGMENTS_POOL.slice(0,3):AUGMENTS_POOL.filter(a=>a.id===action.split(':')[1]),remainingRerolls:3});
    };
    run();
    // Repeat finite combat reactions without interrupting music, draft interactions or long introductions.
    const repeat=window.setInterval(()=>{
      if(action==='fire')shoot();
      else if(['hit','impact','explode','boss_hit','boss_explode','shield_block','combo_break','charge'].includes(action)||/^combo(?:5|10|20|50)$/.test(action))run();
    },action==='fire'?200:2600);
    return ()=>{timers.forEach(clearTimeout);clearInterval(repeat);cancelAnimationFrame(animation);audio.stop();resetAir();};
  // eslint-disable-next-line react-hooks/exhaustive-deps
  },[skinKey,action,replay,audio]);
  return ready?<><PixiBattleCanvas key={`${skinKey}-${replay}`} />
    {(action==='augment_draft'||action.startsWith('augment:'))&&<div className="ge-augment"><AugmentDraft key={`${action}-${replay}`} preview={{sound:id=>audio.playEffect('air',id),autoSelect:action.startsWith('augment:')}} /></div>}
  </>:<p>Đang tải máy bay…</p>;
}
