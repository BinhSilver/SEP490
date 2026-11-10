import { useCallback, useEffect, useState } from 'react';
import { MemoryCardButton } from '../../memory/src/MemoryCardButton';
import { PlayerPanel, ResultPanel, type MemoryEffectPlayer, type MemoryResultView } from '../../memory/src/MemoryEffects';
import '../../memory/src/coin-toss.css';

/** Bản xem trước của đồng xu Lật thẻ (cùng CSS với CoinTossOverlay); không import component vì types.ts của memory khai báo window.JlasArena khác kiểu. */
function PreviewCoin() {
  return <div className="coin-stage" aria-hidden="true"><div className="coin-shadow" /><div className="coin-flight">
    <div className="coin-body" style={{ '--coin-end': '2160deg' } as React.CSSProperties}>
      <div className="coin-face coin-face--front"><strong className="coin-face__brand">KotsuKotsu</strong></div>
      <div className="coin-face coin-face--back"><img className="coin-face__mascot" src="/images/mascots/kitsu/kitsu-coin-smile.png" alt="" /></div>
    </div></div></div>;
}
import type { MemoryCard } from '../../memory/src/cardTypes';
import type { GameEffectsAudio } from './GameEffectsAudio';

const players:MemoryEffectPlayer[]=[{userId:1,displayName:'Bạn',pairsFound:5,mistakes:1,moves:12,streak:2,connected:true},
  {userId:2,displayName:'Đối thủ',pairsFound:3,mistakes:2,moves:10,streak:1,connected:true}];
export function MemoryScene({action,replay,audio}:{action:string;replay:number;audio:GameEffectsAudio}) {
  const [revealed,setRevealed]=useState(false),[result,setResult]=useState(false),[turn,setTurn]=useState(0),[countdown,setCountdown]=useState(3),[diceDone,setDiceDone]=useState(false);
  const onCountdown=useCallback((step:number)=>audio.playEffect('memory',`countdown${step}`),[audio]);
  useEffect(()=>{
    audio.restartScene();setRevealed(false);setResult(['complete','win','lose','draw','aborted'].includes(action));setTurn(action==='turn_other'?1:0);setCountdown(3);setDiceDone(false);
    const timers=new Set<number>();const later=(fn:()=>void,ms:number)=>{const id=window.setTimeout(()=>{timers.delete(id);fn();},ms);timers.add(id);};
    if(['flip','match','mismatch','combo2','combo5','complete_board'].includes(action)) {
      audio.playEffect('memory','flip');later(()=>{setRevealed(true);audio.playEffect('memory',action==='complete_board'?'match':action);},200);
      if(action==='mismatch')later(()=>setRevealed(false),1300);
      if(action==='complete_board')later(()=>{setResult(true);audio.playEffect('memory','complete');},1500);
    }else if(action==='countdown'){for(let n=3;n>=0;n--)later(()=>{setCountdown(n);onCountdown(n);},(3-n)*700);}
    else audio.playEffect('memory',action);
    if(action==='turn_timeout')later(()=>setTurn(1),1600);
    // Đồng xu đáp sau ~3,6 giây (CoinTossOverlay.LAND_AT), rồi tới lượt người thắng.
    if(action==='dice')later(()=>{setTurn(0);setDiceDone(true);},3800);
    return ()=>{timers.forEach(clearTimeout);audio.stop();};
  },[action,replay,audio,onCountdown]);
  const state:MemoryResultView={playMode:['win','lose','draw'].includes(action)?'MULTIPLAYER':'SOLO',status:action==='aborted'?'ABORTED':'FINISHED',
    outcome:'CLEARED',config:{questionField:'KANJI',answerField:'MEANING'},players:action==='draw'?players.map(p=>({...p,pairsFound:4,mistakes:1})):players,
    pairsMatched:8,pairsTotal:8,movesUsed:20,elapsedMs:65000,
    result:{success:true,draw:action==='draw',winnerUserId:action==='lose'?2:1,durationMs:65000}};
  const matched=['match','combo2','combo5','complete_board'].includes(action);
  return <div id="memory-root" className="ge-memory-scene">
    {result?<ResultPanel state={state} userId={1} backUrl="#game-effects-root" onReplay={()=>{audio.setEnabled(true);audio.playEffect('memory','click');setResult(false);setRevealed(false);}} replaying={false}/>:
      action==='dice'&&!diceDone?<div className="ge-scene-overlay"><PreviewCoin/><p>Tung đồng xu chọn người đi trước</p></div>:
      action.startsWith('turn_')||diceDone?<div className="ge-memory-turns"><PlayerPanel player={players[0]} isMe side="sakura" active={turn===0}/><PlayerPanel player={players[1]} side="kaito" active={turn===1}/>
        <p role="status">{action==='turn_timeout'?(turn?'Hết lượt · Đến lượt đối thủ':'Thời gian lượt: 00:02'):turn?'Đến lượt đối thủ':'Đến lượt của bạn'}</p></div>:
      <><div className="ge-memory-pair">{['学校',action==='mismatch'?'bệnh viện':'trường học'].map((text,index)=>{
        const card:MemoryCard={cardInstanceId:`preview-${index}`,position:index,state:revealed?matched?'MATCHED':'TEMP_REVEALED':'HIDDEN',content:text,face:index?'MEANING':'KANJI'};
        return <MemoryCardButton key={`${index}-${replay}`} card={card} disabled={false} onFlip={()=>{audio.setEnabled(true);audio.playEffect('memory','flip');setRevealed(true);}} reduceMotion={false} feedback={revealed?(matched?'match':action==='mismatch'?'mismatch':null):null}/>;
      })}</div><p>{revealed?matched?'Ghép đúng':action==='mismatch'?'Ghép sai':'Mặt thẻ':'Bấm vào thẻ để lật thử'}</p>
      {/^combo[25]$/.test(action)&&revealed&&<div className="ge-feedback">COMBO ×{action.slice(5)}</div>}
      {action==='countdown'&&<div className="ge-scene-overlay"><strong>{countdown||'GO!'}</strong></div>}
      {action==='time_low'&&<div className="ge-feedback ge-feedback--danger">00:05</div>}
      {action==='paused'&&countdown>0&&<div className="ge-scene-overlay"><strong>Tạm dừng</strong><button onClick={()=>{audio.setEnabled(true);audio.playEffect('memory','click');setCountdown(0);}}>Tiếp tục</button></div>}
      {action==='click'&&<button className="memory-btn memory-btn--primary" onClick={()=>{audio.setEnabled(true);audio.playEffect('memory','click');}}>Bấm thử</button>}
      </>}
  </div>;
}
