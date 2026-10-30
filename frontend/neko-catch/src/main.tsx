import React from 'react';
import ReactDOM from 'react-dom/client';
import type { PlayMode } from './arena/types';
import { NekoCatchGame, type HostConfig } from './components/NekoCatchGame';
import { registerShopSkins, skinFromCode } from './graphics/CatSpriteSheet';
import './index.css';

const FONT_HREF =
  'https://fonts.googleapis.com/css2?family=Fredoka:wght@500;600;700' +
  '&family=M+PLUS+Rounded+1c:wght@500;700;800' +
  '&family=Nunito:wght@500;600;700;800;900&display=swap';

/** The Thymeleaf host page has no font links; add them once (Vietnamese + Japanese glyph coverage). */
function ensureFonts() {
  if (document.querySelector('link[data-neko-fonts]')) return;
  const head = document.head;
  for (const origin of ['https://fonts.googleapis.com', 'https://fonts.gstatic.com']) {
    const preconnect = document.createElement('link');
    preconnect.rel = 'preconnect';
    preconnect.href = origin;
    if (origin.includes('gstatic')) preconnect.crossOrigin = 'anonymous';
    head.appendChild(preconnect);
  }
  const link = document.createElement('link');
  link.rel = 'stylesheet';
  link.href = FONT_HREF;
  link.dataset.nekoFonts = 'true';
  head.appendChild(link);
}

function readHostConfig(root: HTMLElement): HostConfig {
  const data = root.dataset;
  if (data.skin && data.skinId) registerShopSkins([{ id: Number(data.skinId), code: data.skin, name: data.skinName || '', renderCode: data.skinRenderCode || data.skin, image: data.skinImage || '', packPath: data.skinPack || null }]);
  const mode: PlayMode = String(data.mode || '').toUpperCase() === 'VERSUS' ? 'VERSUS' : 'SOLO';
  const meId = Number(data.meId);
  const fallbackBack = mode === 'VERSUS' ? '/games/lobby' : '/games';
  return {
    sessionId: (data.sessionId || '').trim(),
    mode,
    roomId: data.roomId || null,
    backUrl: (data.backUrl || '').trim() || fallbackBack,
    meId: Number.isFinite(meId) && meId > 0 ? meId : null,
    skin: skinFromCode(data.skin),
  };
}

const root = document.getElementById('neko-root');
if (root) {
  ensureFonts();
  const config = readHostConfig(root);
  ReactDOM.createRoot(root).render(
    <React.StrictMode>
      <NekoCatchGame host={config} />
    </React.StrictMode>
  );
}
