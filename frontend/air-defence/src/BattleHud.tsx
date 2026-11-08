import type {CSSProperties} from 'react';
import {playerShipName, type MatchInfo} from './game/useAirDefenseStore';
import type {AirPlayerView, LiveStatus} from './game/arenaTypes';
import './battle-hud-mobile.css';

const GAUGE_CIRCUMFERENCE = 2 * Math.PI * 19;
function GaugeRing({percent}: {percent: number}) {
  return <svg className="ad-vital__ring" viewBox="0 0 44 44" aria-hidden="true">
    <circle className="ad-vital__track" cx="22" cy="22" r="19" />
    <circle className="ad-vital__fill" cx="22" cy="22" r="19"
      strokeDasharray={GAUGE_CIRCUMFERENCE}
      strokeDashoffset={GAUGE_CIRCUMFERENCE * (1 - percent / 100)} />
  </svg>;
}

const SHIP_NAMES: Record<string, string> = {
  'plane-halo-fighter': 'Thiên Luân Phán Xét', 'plane-aegis-frame': 'Vệ Thần Thiên Giới',
  'plane-split-core': 'Vệ Binh Tinh Tú', 'plane-void-blade': 'Tử Thần',
  'plane-blue': 'Chiến cơ xanh'
};
interface HudProps {
  match: MatchInfo; wave: number; timeLeft: number | null; score: number; combo: number; credits: number;
  hp: number; maxHp: number; shield: number; charge: number; disabled: boolean; ship: string;
  liveStatus: LiveStatus; bgm: boolean; onMusic: () => void; onMenu: () => void; onBeam: () => void;
  /** Versus only: null while the opponent's state has not arrived yet. */
  opponent?: AirPlayerView | null; inboundBoss?: boolean;
}

function initialOf(name: string): string { return name.trim().charAt(0).toUpperCase() || '?'; }

/** One player's side of the versus rail. Both sides show the same readouts, so they compare at a glance. */
function DuelSide(p: {side: 'me' | 'rival'; name: string; avatarUrl?: string | null; hp: number; maxHp: number;
  score: number; wave: number; combo: number; shield?: number; offline?: boolean; down?: boolean; boss?: boolean}) {
  const pct = Math.max(0, Math.min(100, p.hp / Math.max(1, p.maxHp) * 100));
  const health = Math.max(0, Math.round(p.hp));
  const who = p.side === 'me' ? 'của bạn' : `của ${p.name}`;
  return <div className={`ad-duel-side ad-duel-side--${p.side} ${pct <= 25 ? 'is-low' : ''} ${p.offline ? 'is-offline' : ''}`}>
    <div className="ad-duel-side__head">
      <span className="ad-duel-side__avatar" aria-hidden="true">{initialOf(p.name)}
        {p.avatarUrl && <img src={p.avatarUrl} alt="" onError={(e) => { e.currentTarget.hidden = true; }}/>}</span>
      <span className="ad-duel-side__name" title={p.name}>{p.name}</span>
      {p.offline && <span className="ad-duel-tag is-amber" title="Mất kết nối"><i className="fa-solid fa-link-slash" aria-hidden="true"/><span>Mất kết nối</span></span>}
      {p.down && <span className="ad-duel-tag is-rose" title="Phòng tuyến thất thủ"><i className="fa-solid fa-skull" aria-hidden="true"/><span>Thất thủ</span></span>}
      {p.boss && <span className="ad-duel-tag is-rose" title="Trùm đang tới"><i className="fa-solid fa-triangle-exclamation" aria-hidden="true"/><span>Trùm đang tới</span></span>}
      <strong className="ad-duel-side__hp">{health}<small> / {p.maxHp}</small></strong>
    </div>
    <div className="ad-health__track" role="meter" aria-label={`Máu phòng tuyến ${who}`} aria-valuemin={0} aria-valuemax={p.maxHp} aria-valuenow={health}><span style={{width: `${pct}%`}}/></div>
    <dl className="ad-duel-side__stats">
      <div><dt>Điểm</dt><dd>{Math.round(p.score)}</dd></div>
      <div><dt>Đợt</dt><dd>{p.wave}</dd></div>
      <div><dt>Combo</dt><dd>×{p.combo}</dd></div>
      {!!p.shield && <div className="is-shield"><dt><i className="fa-solid fa-shield-halved" aria-hidden="true"/> Giáp</dt><dd>{p.shield}</dd></div>}
    </dl>
  </div>;
}
export function BattleHud(p: HudProps) {
  const ready = p.charge >= 100;
  const hpPct = Math.max(0, Math.min(100, p.hp / Math.max(1, p.maxHp) * 100));
  const health = Math.max(0, Math.round(p.hp));
  const chargePct = Math.max(0, Math.min(100, p.charge));
  const beamLabel = ready ? 'Bắn siêu pháo — đã nạp đầy' : `Siêu pháo đang nạp ${Math.round(chargePct)} phần trăm`;
  const seconds = Math.max(0, Math.ceil((p.timeLeft ?? 0) / 1000));
  const clock = `${Math.floor(seconds / 60).toString().padStart(2,'0')}:${(seconds % 60).toString().padStart(2,'0')}`;
  const tier = p.combo >= 30 ? 'legend' : p.combo >= 10 ? 'hot' : p.combo >= 5 ? 'warm' : 'base';
  const canPause = p.match.playMode === 'SOLO' && p.match.endsAt === null;
  const superButton = <button type="button" className={`ad-super ${ready ? 'is-ready' : ''}`} onClick={p.onBeam} disabled={!ready || p.disabled} aria-label={ready ? 'Siêu pháo sẵn sàng — bấm Shift để bắn' : `Siêu pháo đang nạp ${Math.round(p.charge)} phần trăm`} title="Bắn siêu pháo · Shift" style={{'--charge':`${p.charge}%`} as CSSProperties}>
    <span className="ad-super__title"><i className="fa-solid fa-bolt" aria-hidden="true"/> {ready ? 'Siêu pháo sẵn sàng' : 'Siêu pháo'}<kbd>Shift</kbd></span>
    <span className="ad-super__track" role="meter" aria-label="Năng lượng siêu pháo" aria-valuemin={0} aria-valuemax={100} aria-valuenow={p.charge}><span/></span><strong>{Math.round(p.charge)}<small>%</small></strong>
  </button>;
  return <>
    <header className="ad-hud">
      <div className="ad-hud__mission">
        <div className="ad-hud__mission-meta">
          <span className="ad-hud__wave"><span>{p.match.versus ? 'Đối kháng' : 'Luyện tập'}</span><b>Đợt {p.wave.toString().padStart(2,'0')}</b></span>
          <button type="button" className="ad-icon-button ad-hud__mobile-menu" onClick={p.onMenu}
            aria-label={canPause ? 'Tạm dừng, mở menu và cài đặt' : 'Mở menu và cài đặt trận đấu'} title="Menu và cài đặt">
            <i className="fa-solid fa-gear" aria-hidden="true"/>
          </button>
        </div>
        <h1 title={p.match.deckTitle}>{p.match.deckTitle}</h1>
        <span className="ad-hud__fields">{p.match.questionLabel} · {p.match.answerLabel}</span>
        {(p.liveStatus === 'offline' || p.liveStatus === 'connecting') && <span role="status" className="ad-hud__connection">{p.liveStatus === 'offline' ? 'Đang kết nối lại…' : 'Đang kết nối…'}</span>}
      </div>
      <div className="ad-hud__readouts">
        <div className={`ad-hud__stat ad-hud__time ${p.timeLeft !== null && p.timeLeft <= 30000 ? 'is-urgent' : ''}`}><span>{p.timeLeft === null ? 'Thời gian' : 'Còn lại'}</span><strong>{p.timeLeft === null ? '∞' : clock}</strong></div>
        <div className="ad-hud__stat ad-hud__score"><span>Điểm</span><strong>{Math.round(p.score)}</strong></div>
        <div className={`ad-hud__stat ad-hud__combo is-${tier}`}><span>Combo</span><strong key={p.combo}>×{p.combo}</strong></div>
        <div className="ad-hud__stat ad-hud__coins"><span>Xu</span><strong>+{p.credits}</strong></div>
        <div className="ad-hud__vitals">
          <div className={`ad-vital ad-vital--health ${hpPct <= 25 ? 'is-low' : ''}`}
            role="meter" aria-label="Máu phòng tuyến" aria-valuemin={0} aria-valuemax={p.maxHp}
            aria-valuenow={health} aria-valuetext={`${health} trên ${p.maxHp} máu, ${Math.round(hpPct)} phần trăm${p.shield > 0 ? `, giáp ${p.shield}` : ''}`}>
            <span className="ad-vital__face"><GaugeRing percent={hpPct}/><i className="fa-solid fa-heart" aria-hidden="true"/><strong>{Math.round(hpPct)}<small>%</small></strong>
              {p.shield > 0 && <span className="ad-vital__shield" aria-hidden="true"><i className="fa-solid fa-shield-halved"/> {p.shield}</span>}
            </span>
          </div>
          <button type="button" className={`ad-vital ad-vital--beam ${ready ? 'is-ready' : ''}`} onClick={p.onBeam}
            disabled={!ready || p.disabled} aria-label={beamLabel} title="Bắn siêu pháo · Shift">
            <span className="ad-vital__face"><GaugeRing percent={chargePct}/><i className="fa-solid fa-bolt" aria-hidden="true"/><strong>{Math.round(chargePct)}<small>%</small></strong></span>
          </button>
        </div>
      </div>
      <div className="ad-hud__actions">
        <button type="button" className="ad-icon-button" onClick={p.onMusic} aria-pressed={p.bgm} aria-label={p.bgm ? 'Tắt nhạc nền' : 'Bật nhạc nền'} title={p.bgm ? 'Tắt nhạc nền' : 'Bật nhạc nền'}><i className={`fa-solid ${p.bgm ? 'fa-music' : 'fa-volume-xmark'}`} aria-hidden="true"/></button>
        <button type="button" className="ad-icon-button" onClick={p.onMenu} aria-label={canPause ? 'Tạm dừng và mở menu (Esc)' : 'Mở menu trận đấu (Esc)'} title="Menu · Esc"><i className={`fa-solid ${canPause ? 'fa-pause' : 'fa-bars'}`} aria-hidden="true"/></button>
        <button type="button" className="ad-icon-button" onClick={p.onMenu} aria-label="Cài đặt âm thanh và hình ảnh" title="Cài đặt"><i className="fa-solid fa-gear" aria-hidden="true"/></button>
      </div>
    </header>
    {p.match.versus ? <div className="ad-status-rail is-duel">
      <DuelSide side="me" name="Bạn" hp={p.hp} maxHp={p.maxHp} score={p.score} wave={p.wave} combo={p.combo} shield={p.shield}/>
      <div className="ad-duel__versus" role="status" aria-live="polite">
        <b aria-hidden="true">VS</b>
        {p.opponent && <small>{lead(p.score - p.opponent.score)}</small>}
      </div>
      {p.opponent
        ? <DuelSide side="rival" name={p.opponent.displayName} avatarUrl={p.opponent.avatarUrl} hp={p.opponent.hp} maxHp={p.opponent.maxHp}
            score={p.opponent.score} wave={p.opponent.wave} combo={p.opponent.combo} offline={!p.opponent.online}
            down={p.opponent.finished && !p.opponent.alive} boss={p.inboundBoss}/>
        : <p className="ad-duel-side ad-duel-side--waiting">Đang chờ thông tin đối thủ…</p>}
      {superButton}
    </div> : <div className="ad-status-rail">
      <div className={`ad-health ${hpPct <= 25 ? 'is-low' : ''}`}>
        <div className="ad-health__caption"><span><i className="fa-solid fa-heart" aria-hidden="true"/> Phòng tuyến</span><strong>{Math.max(0, Math.round(p.hp))}<small> / {p.maxHp}</small></strong></div>
        <div role="meter" aria-label="Máu hành tinh của bạn" aria-valuemin={0} aria-valuemax={p.maxHp} aria-valuenow={Math.max(0,Math.round(p.hp))} className="ad-health__track"><span style={{width:`${hpPct}%`}}/></div>
        {p.shield > 0 && <span className="ad-health__shield"><i className="fa-solid fa-shield-halved" aria-hidden="true"/> Giáp {p.shield}</span>}
      </div>
      <div className="ad-ship-name"><i className="fa-solid fa-jet-fighter" aria-hidden="true"/>{SHIP_NAMES[p.ship] || playerShipName() || 'Chiến cơ'}</div>
      {superButton}
    </div>}
  </>;
}

function lead(diff: number): string {
  if (diff === 0) return 'Ngang điểm';
  return `${diff > 0 ? 'Dẫn' : 'Kém'} ${Math.abs(Math.round(diff))} điểm`;
}
