import { useEffect, useState } from 'react';
import { useVisualSettings } from './game/visualSettings';
import { CAT_SKINS, type CatSkinId } from '../../neko-catch/src/graphics/CatSpriteSheet';
import { setAssetBase } from '../../neko-catch/src/assets';
import { GameEffectsAudio, type PreviewGame } from './GameEffectsAudio';
import { GAME_EFFECTS, effectGroups } from './GameEffectsCatalog';
import { AirScene } from './GameEffectsAirScene';
import { CatScene } from './GameEffectsCatScene';
import { MemoryScene } from './GameEffectsMemoryScene';

const PLANES = [
  ['plane-halo-fighter', 'Thiên Luân Phán Xét'], ['plane-aegis-frame', 'Vệ Thần Thiên Giới'],
  ['plane-split-core', 'Vệ Binh Tinh Tú'], ['plane-void-blade', 'Tử Thần'],
  ['plane-blue', 'Máy bay xanh']
];
export default function GameEffectsPreview() {
  const [game, setGame] = useState<PreviewGame>('air');
  const [audio] = useState(() => new GameEffectsAudio());
  const [plane, setPlane] = useState(PLANES[0][0]);
  const [cat, setCat] = useState<CatSkinId>(3);
  const [action, setAction] = useState('beam');
  const [replay, setReplay] = useState(0);
  const visuals = useVisualSettings();
  const actions = GAME_EFFECTS[game];
  const label = actions.find(effect => effect.id === action)?.label || '';
  const skinName = game === 'air' ? PLANES.find(([id]) => id === plane)?.[1] : game === 'cat' ? CAT_SKINS[cat].name : 'Lật thẻ';
  useEffect(() => {
    // Demo every animation without changing the player's saved visual preferences.
    useVisualSettings.setState({ reducedMotion: false, screenShake: true, particles: 'high' });
    const silence = () => audio.setEnabled(false);
    const onVisibility = () => { if (document.hidden) silence(); };
    window.addEventListener('pagehide', silence);
    document.addEventListener('visibilitychange', onVisibility);
    return () => { window.removeEventListener('pagehide', silence); document.removeEventListener('visibilitychange', onVisibility); audio.stop(); };
  }, [audio]);
  useEffect(() => {
    setAssetBase('/arena/app/neko/');
    const root = document.getElementById('game-effects-root');
    const syncView = () => {
      let theme = 'light';
      try {
        const parentTheme = window.parent !== window ? window.parent.document.documentElement.getAttribute('data-theme') : null;
        theme = parentTheme || localStorage.getItem('jlas-theme') || (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
      } catch { /* Keep the default theme if storage or the parent is unavailable. */ }
      if (document.documentElement.getAttribute('data-theme') !== theme) document.documentElement.setAttribute('data-theme', theme);
      const frame = window.frameElement as HTMLIFrameElement | null;
      if (frame && root) {
        const height = Math.max(520, Math.ceil(root.scrollHeight) + 4);
        if (frame.style.height !== `${height}px`) frame.style.height = `${height}px`;
      }
    };
    syncView();
    // Follow the workspace theme even when preference storage is unavailable.
    window.addEventListener('storage', syncView);
    const timer = window.setInterval(syncView, 500);
    return () => { window.removeEventListener('storage', syncView); clearInterval(timer); };
  }, []);
  return <main className="ge-viewer">
    <nav className="ge-games" aria-label="Chọn trò chơi">{([['air', 'Bắn máy bay'], ['cat', 'Bắt cá'], ['memory', 'Lật thẻ']] as const).map(([id, name]) =>
      <button key={id} aria-pressed={game === id} onClick={() => { audio.setGame(id); audio.setEnabled(true); setGame(id); setAction(id === 'air' ? 'beam' : id === 'cat' ? 'idle' : 'hidden'); setReplay(n => n + 1); }}>{name}</button>)}</nav>
    <div className="ge-layout"><section className="ge-stage-section" aria-label="Xem trước hiệu ứng">
      <header className="ge-stage-header"><div><span>XEM TRƯỚC</span><h2>{skinName}</h2></div><span className="ge-state" role="status">{label}</span></header>
      <div className={`ge-stage ge-stage--${game}`}>
        {game === 'air' ? <AirScene skin={plane} action={action} replay={replay} audio={audio} /> : game === 'cat'
          ? <CatScene key={replay} skin={cat} action={action} audio={audio} /> : <MemoryScene action={action} replay={replay} audio={audio} />}
      </div>
      <footer className="ge-stage-footer"><span>{actions.length} hiệu ứng · {game === 'air' ? `${PLANES.length} máy bay` : game === 'cat' ? '7 nhân vật' : 'Thẻ Kanji và nghĩa'}</span>
        <button className="ge-replay" onClick={() => { audio.setEnabled(true); setReplay(n => n + 1); }}><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true"><path d="M3 10a9 9 0 1 1 2 9M3 4v6h6"/></svg>Phát lại</button></footer>
    </section><aside className="ge-controls" aria-label="Điều khiển hiệu ứng">
      {game !== 'memory' && <label className="ge-field">{game === 'air' ? 'Máy bay' : 'Nhân vật'}
        <select value={game === 'air' ? plane : cat} onChange={e => { audio.setEnabled(true); game === 'air' ? setPlane(e.target.value) : setCat(Number(e.target.value) as CatSkinId); setReplay(n => n + 1); }}>
          {(game === 'air' ? PLANES : CAT_SKINS.map(s => [String(s.id), s.name])).map(([id, name]) => <option key={id} value={id}>{name}</option>)}
        </select></label>}
      <label className="ge-field">Hiệu ứng<select value={action} onChange={e => { audio.setEnabled(true); setAction(e.target.value); setReplay(n => n + 1); }}>
        {effectGroups(game).map(group => <optgroup key={group} label={group}>{actions.filter(effect => effect.group === group).map(effect => <option key={effect.id} value={effect.id}>{effect.label}</option>)}</optgroup>)}
      </select></label>
      {game === 'air' && <label className="ge-field">Hạt sáng<select value={visuals.particles} onChange={e => useVisualSettings.setState({ particles: e.target.value as 'high' | 'low' | 'off' })}>
        <option value="high">Đầy đủ</option><option value="low">Ít</option><option value="off">Tắt</option></select></label>}
    </aside></div>
  </main>;
}
