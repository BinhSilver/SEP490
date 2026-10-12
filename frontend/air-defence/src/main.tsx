import ReactDOM from "react-dom/client";
import App from "./App";
import type { HostConfig } from "./game/matchController";
import type { PlayMode } from "./game/arenaTypes";
import { setPlaneSkin } from "./game/useAirDefenseStore";
import { setAssetBase } from "../../neko-catch/src/assets";
import "./index.css";

// Trang Thymeleaf templates/air-defense/board.html render:
// <div id="air-defense-root" data-session-id data-mode data-back-url data-me-id data-skin>
const rootElement = document.getElementById("air-defense-root");
const previewElement = document.getElementById("game-effects-root");

if (previewElement) {
  // Set the shared renderer's asset path before its eager sprite preload.
  setAssetBase('/arena/app/neko/');
  import('./GameEffectsPreview').then(({ default: GameEffectsPreview }) => {
    ReactDOM.createRoot(previewElement).render(<GameEffectsPreview />);
  }).catch(() => { previewElement.textContent = 'Không tải được hiệu ứng. Hãy tải lại trang.'; });
} else if (!rootElement) {
  throw new Error("Không tìm thấy #air-defense-root");
} else {

// `npm run dev`: cho phép truyền qua query (?sid=…&mode=VERSUS&back=/games&me=12) khi không có data-*.
const query = new URLSearchParams(window.location.search);
const data = rootElement.dataset;

function parseMode(value: string | null | undefined): PlayMode | null {
  const mode = (value || "").toUpperCase();
  return mode === "SOLO" || mode === "VERSUS" ? mode : null;
}

function parseId(value: string | null | undefined): number | null {
  const id = Number(value);
  return Number.isFinite(id) && id > 0 ? id : null;
}

const host: HostConfig = {
  sessionId: data.sessionId || query.get("sid") || "",
  playMode: parseMode(data.mode || query.get("mode")),
  backUrl: data.backUrl || query.get("back") || "/games",
  meId: parseId(data.meId || query.get("me")),
};

setPlaneSkin(data.skin || query.get("skin"), data.skinRenderCode, data.skinImage);

ReactDOM.createRoot(rootElement).render(<App host={host} />);
}
