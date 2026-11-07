import { useEffect, useState } from 'react';
import { useVisualSettings } from './game/visualSettings';
import { CAT_SKINS, registerShopSkins, skinFromCode, skinVisual, type CatSkinId } from '../../neko-catch/src/graphics/CatSpriteSheet';
import { setAssetBase } from '../../neko-catch/src/assets';
import { GameEffectsAudio, type PreviewGame } from './GameEffectsAudio';
import { effectGroups, previewEffects } from './GameEffectsCatalog';
import { AirScene, type PlaneSkin } from './GameEffectsAirScene';
import { CatScene } from './GameEffectsCatScene';
import { MemoryScene } from './GameEffectsMemoryScene';

const PLANES = [
  ['plane-halo-fighter', 'Thiên Luân Phán Xét'], ['plane-aegis-frame', 'Vệ Thần Thiên Giới'],
  ['plane-split-core', 'Vệ Binh Tinh Tú'], ['plane-void-blade', 'Tử Thần'],
  ['plane-blue', 'Máy bay xanh']
];

/**
 * Mở từ trang admin "Trang phục game" (popup) cho đúng một skin:
 * ?game=air|cat&skin=<mã>&id=<id>&render=<renderCode>&image=…&pack=<thư mục gói>&weapon=<bộ vũ khí>&name=<tên>.
 * Khi đó trang khoá vào game và skin đó, chỉ còn chọn hiệu ứng.
 */
interface FocusSkin extends PlaneSkin { game: 'air' | 'cat'; id: number }
function focusFromUrl(): FocusSkin | null {
  const params = new URLSearchParams(window.location.search);
  const code = params.get('skin');
  if (!code) return null;
  return { game: params.get('game') === 'cat' ? 'cat' : 'air', code, id: Number(params.get('id')) || 0,
    renderCode: params.get('render') || code, image: params.get('image') || '', packPath: params.get('pack'),
    weaponTheme: params.get('weapon'), name: params.get('name') || code };
}
function focusCat(focus: FocusSkin | null): CatSkinId | null {
  if (!focus || focus.game !== 'cat') return null;
  registerShopSkins([{ id: focus.id, code: focus.code, renderCode: focus.renderCode, image: focus.image || '', name: focus.name, packPath: focus.packPath }]);
  return skinFromCode(focus.code);
}

export default function GameEffectsPreview() {
  const [focus] = useState(focusFromUrl);
  const [game, setGame] = useState<PreviewGame>(focus?.game ?? 'air');
  const [audio] = useState(() => new GameEffectsAudio());
  const [plane, setPlane] = useState(PLANES[0][0]);
  const [cat, setCat] = useState<CatSkinId>(() => focusCat(focus) ?? 3);
  const planeSkin: PlaneSkin = focus?.game === 'air' ? focus : { code: plane, renderCode: plane, name: PLANES.find(([id]) => id === plane)?.[1] || plane };
  const [action, setAction] = useState(focus?.game === 'cat' ? 'idle' : 'beam');
  const [replay, setReplay] = useState(0);
  const actions = previewEffects(game, !!focus);
  const skinName = focus ? focus.name : game === 'air' ? planeSkin.name : game === 'cat' ? skinVisual(cat).name : 'Lật thẻ';
  useEffect(() => {
    // Luôn xem ở mức đầy đủ nhất (hạt sáng, rung màn hình, chuyển động); không đổi thiết lập đã lưu của người chơi.
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
    {!focus && <nav className="ge-games" aria-label="Chọn trò chơi">{([['air', 'Bắn máy bay'], ['cat', 'Bắt cá'], ['memory', 'Lật thẻ']] as const).map(([id, name]) =>
      <button key={id} aria-pressed={game === id} onClick={() => { audio.setGame(id); audio.setEnabled(true); setGame(id); setAction(id === 'air' ? 'beam' : id === 'cat' ? 'idle' : 'hidden'); setReplay(n => n + 1); }}>{name}</button>)}</nav>}
    <div className="ge-layout"><section className="ge-stage-section" aria-label="Xem trước hiệu ứng">
      {/* Mở trong popup theo một skin: popup đã có tiêu đề là tên skin, nên không lặp lại ở đây. */}
      {!focus && <header className="ge-stage-header"><div><span>XEM TRƯỚC</span><h2>{skinName}</h2></div></header>}
      <div className={`ge-stage ge-stage--${game}`}>
        {game === 'air' ? <AirScene skin={planeSkin} action={action} replay={replay} audio={audio} /> : game === 'cat'
          ? <CatScene key={replay} skin={cat} action={action} audio={audio} /> : <MemoryScene action={action} replay={replay} audio={audio} />}
      </div>
    </section><aside className="ge-controls" aria-label="Điều khiển hiệu ứng">
      {game !== 'memory' && !focus && <label className="ge-field">{game === 'air' ? 'Máy bay' : 'Nhân vật'}
        <select value={game === 'air' ? plane : cat} onChange={e => { audio.setEnabled(true); game === 'air' ? setPlane(e.target.value) : setCat(Number(e.target.value) as CatSkinId); setReplay(n => n + 1); }}>
          {(game === 'air' ? PLANES : CAT_SKINS.map(s => [String(s.id), s.name])).map(([id, name]) => <option key={id} value={id}>{name}</option>)}
        </select></label>}
      <label className="ge-field">Hiệu ứng<select value={action} onChange={e => { audio.setEnabled(true); setAction(e.target.value); setReplay(n => n + 1); }}>
        {effectGroups(actions).map(group => <optgroup key={group} label={group}>{actions.filter(effect => effect.group === group).map(effect => <option key={effect.id} value={effect.id}>{effect.label}</option>)}</optgroup>)}
      </select></label>
    </aside></div>
  </main>;
}
