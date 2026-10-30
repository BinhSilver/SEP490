import { useEffect, useRef, useState } from 'react';
import { CatSpriteSheet, skinVisual, type CatSkinId } from '../../neko-catch/src/graphics/CatSpriteSheet';
import { SpriteRenderer } from '../../neko-catch/src/graphics/SpriteRenderer';
import { ParallaxBackground } from '../../neko-catch/src/graphics/ParallaxBackground';
import { ParticleSystem } from '../../neko-catch/src/graphics/ParticleSystem';
import { setAssetBase } from '../../neko-catch/src/assets';
import { LobbyCat } from '../../neko-catch/src/components/LobbyCat';
import { CowboyVictoryScene } from '../../neko-catch/src/components/CowboyVictoryScene';
import type { CatPlayer, FallingObject, BonusItemType, AnswerItemType } from '../../neko-catch/src/types/game';
import type { GameEffectsAudio } from './GameEffectsAudio';

export function CatScene({skin,action,audio}:{skin:CatSkinId;action:string;audio:GameEffectsAudio}) {
  const ref=useRef<HTMLCanvasElement>(null); const [step,setStep]=useState(3);
  useEffect(()=>{
    setAssetBase('/arena/app/neko/');CatSpriteSheet.setSkin(skin);CatSpriteSheet.init();SpriteRenderer.initHandDrawnAssets();
    audio.restartScene();setStep(3);
    const timers=new Set<number>();
    const later=(fn:()=>void,ms:number)=>{const id=window.setTimeout(()=>{timers.delete(id);fn();},ms);timers.add(id);};
    if(action==='countdown') {for(let n=3;n>=0;n--)later(()=>{setStep(n);audio.playEffect('cat',n?'countdown':'go');},(3-n)*700);}
    else if(!action.startsWith('pickup_'))audio.playEffect('cat',action);
    const state=(['idle','run_right','run_left','catch_success','catch_wrong','win','lose'].includes(action)?action:'idle') as CatPlayer['state'];
    // Nhân vật người / kaiju (gốc 3+ hoặc gói skin) thấp hơn mèo; trang phục cửa hàng theo nhân vật nó dùng lại.
    const cat:CatPlayer={x:320,y:310,width:110,height:skinVisual(skin).skin>=3?88:110,speed:0,state,frameIndex:0,
      basketWidth:70,basketHeight:32,basketOffsetX:0,basketOffsetY:-8,shieldActive:action==='shield'||action==='shield_block'};
    const particles=new ParticleSystem(),background=new ParallaxBackground();
    const floats:{text:string;color:string;y:number;born:number}[]=[];
    const bonus=action.split('_').slice(1).join('_') as BonusItemType;
    const bonusAction=action.startsWith('drop_')||action.startsWith('pickup_');
    const answerKinds:AnswerItemType[]=['fish_blue','fish_pink','fish_green','paw_cookie'];
    const answer=answerKinds.includes(action as AnswerItemType)?action as AnswerItemType:'fish_blue';
    const item:FallingObject={id:'preview-item',x:320,y:50,speed:0,width:95,height:75,type:bonusAction?bonus:answer,
      roundId:'preview',choiceId:null,text:bonusAction?'':'学校',japanese:true,isBonus:bonusAction,bonusType:bonusAction?bonus:undefined,rotation:0,rotSpeed:0};
    let raf=0,lastBurst=-3000,consumed=false;const start=performance.now();
    const float=(text:string,color:string,elapsed:number)=>floats.push({text,color,y:220,born:elapsed});
    const render=()=>{
      const ctx=ref.current?.getContext('2d'); const elapsed=performance.now()-start;
      if(ctx){
        ctx.clearRect(0,0,640,420);background.draw(ctx,640,420,elapsed);
        ctx.save();
        if(['catch_wrong','missed'].includes(action)&&elapsed<320)ctx.translate((Math.random()-.5)*9,(Math.random()-.5)*5);
        const showItem=bonusAction||answerKinds.includes(action as AnswerItemType)||['catch_success','catch_wrong','missed','freeze','pickup_freeze'].includes(action);
        const frozen=(action==='freeze'||(action==='pickup_freeze'&&consumed));
        if(showItem&&!consumed) {
          item.y=frozen?125:bonusAction&&action.startsWith('pickup_')?50+Math.min(1,elapsed/1100)*170:50+(elapsed/12)%200;
          SpriteRenderer.drawFallingItem(ctx,item);
          if(action.startsWith('pickup_')&&elapsed>=1100){
            consumed=true;particles.emitCorrect(320,220);cat.state='catch_success';cat.shieldActive=bonus==='shield';
            const labels={golden_fish:['+50','#FFD700'],clock:['+5 GIÂY','#00CEC9'],shield:['KHIÊN!','#00B894'],freeze:['ĐÓNG BĂNG 2s!','#38BDF8']};
            const [text,color]=labels[bonus];float(text,color,elapsed);audio.playEffect('cat',action);
          }
        }
        if(action==='missed')cat.state='catch_wrong';
        CatSpriteSheet.draw(ctx,cat,elapsed,skin,true);
        if(elapsed-lastBurst>1700) {
          if(action==='catch_success'){particles.emitCorrect(320,220);float('+10','#FFD700',elapsed);}
          if(action==='catch_wrong'||action==='missed'){particles.emitWrong(320,220);float(action==='missed'?'LỠ TỪ!':'SAI!','#FF4757',elapsed);}
          if(/^combo[369]$/.test(action)){particles.emitCombo(320,220);float(`+20 (COMBO ${action.slice(5)}!)`,'#FFD700',elapsed);}
          if(action==='win')particles.emitCombo(320,220);
          if(action==='shield_block'){particles.emitCorrect(320,220);float('ĐÃ CHẶN!','#00B894',elapsed);}
          lastBurst=elapsed;
        }
        particles.updateAndDraw(ctx);
        for(let i=floats.length-1;i>=0;i--){const f=floats[i],age=elapsed-f.born;if(age>1200){floats.splice(i,1);continue;}
          ctx.save();ctx.globalAlpha=1-age/1200;ctx.font='900 24px sans-serif';ctx.textAlign='center';ctx.fillStyle=f.color;ctx.shadowColor='rgba(0,0,0,.7)';ctx.shadowBlur=8;ctx.fillText(f.text,320,f.y-age/30);ctx.restore();}
        if(frozen){if(consumed)SpriteRenderer.drawFallingItem(ctx,{...item,type:'fish_blue',isBonus:false,bonusType:undefined,x:220,y:125,text:'学校'});
          const grad=ctx.createRadialGradient(320,210,70,320,210,350);grad.addColorStop(0,'transparent');grad.addColorStop(1,'rgba(2,132,199,.32)');ctx.fillStyle=grad;ctx.fillRect(0,0,640,420);}
        ctx.restore();
      }raf=requestAnimationFrame(render);
    };render();
    return ()=>{cancelAnimationFrame(raf);timers.forEach(clearTimeout);audio.stop();};
  },[skin,action,audio]);
  return <div className="ge-cat-scene">
    {action==='lobby'?<LobbyCat skinId={skin} className="ge-lobby-cat" forceMotion/>:
      action==='win'&&skinVisual(skin).skin===1?<CowboyVictoryScene forceMotion/>:<canvas ref={ref} width={640} height={420} aria-label={`${skinVisual(skin).name}: ${action}`} />}
    {(action==='countdown'||action==='go')&&<div className="ge-scene-overlay"><strong>{action==='go'?'GO!':step||'GO!'}</strong></div>}
    {action==='reveal'&&<div className="ge-feedback ge-feedback--answer">Đáp án đúng: 学校 · trường học</div>}
    {(action==='freeze'||action==='pickup_freeze')&&<div className="ge-feedback ge-feedback--ice">ĐÓNG BĂNG · 2s</div>}
    {action==='time_low'&&<div className="ge-feedback ge-feedback--danger">00:05</div>}
    {action==='paused'&&step>0&&<div className="ge-scene-overlay"><strong>Tạm dừng</strong><button onClick={()=>{audio.setEnabled(true);audio.playEffect('cat','paused');setStep(0);}}>Tiếp tục</button></div>}
  </div>;
}
