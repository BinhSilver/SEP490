import { useEffect, useRef, useState, type CSSProperties, type ReactNode } from "react";
import { useShallow } from "zustand/react/shallow";
import { useAirDefenseStore, isJapaneseField, playerSpaceshipCode, type MatchInfo } from "./game/useAirDefenseStore";
import { BattleHud } from "./BattleHud";
import { useVisualSettings } from "./game/visualSettings";
import { PixiBattleCanvas } from "./game/PixiBattleCanvas";
import { GAME_CONFIG } from "./game/gameConfig";
import { soundManager } from "./game/soundEffects";
import type { AugmentCard, WeakWord } from "./game/types";
import type { AirPlayerView, DeckField, ResultLine } from "./game/arenaTypes";
import { errorMessage, getArena, serverNow } from "./game/arenaClient";
import { backUrl, finishSoloMatch, leaveMatch, replaySolo, startMatchController, type HostConfig } from "./game/matchController";
import { AirSetup } from "./AirSetup";
import "./device-notice.css";

// ============================================================
// Font & nhãn trường thẻ
// ============================================================
const JP_FONT = "'Noto Sans JP', 'M PLUS Rounded 1c', sans-serif";
const LATIN_FONT = "'Inter', 'Segoe UI', sans-serif";

function fontFor(field: DeckField | null | undefined): string {
  return isJapaneseField(field) ? JP_FONT : LATIN_FONT;
}

const FIELD_SHORT: Record<DeckField, string> = {
  KANJI: "Kanji",
  HIRAGANA: "Cách đọc",
  MEANING: "Nghĩa",
  HAN_VIET: "Hán Việt",
};

const ALL_FIELDS: DeckField[] = ["KANJI", "HIRAGANA", "MEANING", "HAN_VIET"];

function fieldValue(word: Pick<WeakWord, "kanji" | "reading" | "meaning" | "hanViet">, field: DeckField): string | null {
  switch (field) {
    case "KANJI":
      return word.kanji;
    case "HIRAGANA":
      return word.reading;
    case "MEANING":
      return word.meaning;
    case "HAN_VIET":
      return word.hanViet;
    default:
      return null;
  }
}

/** Nhãn trường đặt giữa câu: "cách đọc", "nghĩa tiếng Việt" viết thường; "Kanji", "Hán Việt" giữ hoa. */
function labelInSentence(field: DeckField, label: string | null | undefined): string {
  const raw = label || FIELD_SHORT[field];
  return field === "HIRAGANA" || field === "MEANING" ? raw.charAt(0).toLowerCase() + raw.slice(1) : raw;
}

/** "Gõ {answerLabel}…" + gợi ý cách gõ theo trường đáp án. */
function answerPlaceholder(match: MatchInfo): string {
  const label = labelInSentence(match.answerField, match.answerLabel);
  const hint =
    match.answerField === "HIRAGANA"
      ? " (chữ Latinh hoặc chữ Nhật)"
      : match.answerField === "MEANING" || match.answerField === "HAN_VIET"
        ? " (không dấu cũng được)"
        : "";
  return `Gõ ${label}…${hint}`;
}

function formatClock(ms: number): string {
  const total = Math.max(0, Math.ceil(ms / 1000));
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
}

function initialOf(name: string | null | undefined): string {
  const trimmed = (name || "").trim();
  return trimmed ? trimmed.charAt(0).toUpperCase() : "?";
}

// ============================================================
// ORBITAL KANA — Shared Design Atoms
// ============================================================

/** White card with ambient shadow */
function OkCard({ children, className = "", style = {} }: { children: ReactNode; className?: string; style?: CSSProperties }) {
  return (
    <div className={`ok-card ${className}`} style={{ padding: 24, ...style }}>
      {children}
    </div>
  );
}

/** Light surface container */
function OkSurface({ children, className = "", style = {} }: { children: ReactNode; className?: string; style?: CSSProperties }) {
  return (
    <div
      className={className}
      style={{
        background: "var(--ok-surface-low)",
        borderRadius: 12,
        padding: 16,
        ...style,
      }}
    >
      {children}
    </div>
  );
}

interface OkButtonProps {
  children: ReactNode;
  onClick?: () => void;
  disabled?: boolean;
  className?: string;
  style?: CSSProperties;
  ariaLabel?: string;
}

/** Primary action button */
function OkBtnPrimary({ children, onClick, disabled = false, className = "", style = {}, ariaLabel }: OkButtonProps) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      aria-label={ariaLabel}
      className={`ok-btn ok-btn-primary ${className}`}
      style={{ opacity: disabled ? 0.5 : 1, cursor: disabled ? "not-allowed" : "pointer", ...style }}
    >
      {children}
    </button>
  );
}

/** Secondary lavender button */
function OkBtnSecondary({ children, onClick, disabled = false, className = "", style = {}, ariaLabel }: OkButtonProps) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      aria-label={ariaLabel}
      className={`ok-btn ok-btn-secondary ${className}`}
      style={{ opacity: disabled ? 0.5 : 1, ...style }}
    >
      {children}
    </button>
  );
}

/** Ghost button */
function OkBtnGhost({ children, onClick, disabled = false, className = "", style = {}, ariaLabel }: OkButtonProps) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      aria-label={ariaLabel}
      className={`ok-btn ok-btn-ghost ${className}`}
      style={{ opacity: disabled ? 0.5 : 1, ...style }}
    >
      {children}
    </button>
  );
}

/** Badge / pill */
function OkBadge({
  children,
  variant = "primary",
}: {
  children: ReactNode;
  variant?: "primary" | "gold" | "success" | "outline" | "error";
}) {
  const cls = {
    primary: "ok-badge ok-badge-primary",
    gold: "ok-badge ok-badge-gold",
    success: "ok-badge ok-badge-success",
    outline: "ok-badge ok-badge-outline",
    error: "ok-badge",
  }[variant];

  return (
    <span className={cls} style={variant === "error" ? { background: "var(--ok-error)", color: "#fff" } : {}}>
      {children}
    </span>
  );
}

// ============================================================
// BATTLE SCREEN HELPERS (dark mode — PixiJS wrapper)
// ============================================================
function BattleStat({ label, value, tone }: { label: string; value: string | number; tone?: string }) {
  return (
    <div style={{ minWidth: 0 }}>
      <p style={{ fontFamily: "monospace", fontSize: 9, letterSpacing: "0.16em", color: "#94a3b8", textTransform: "uppercase", margin: 0 }}>{label}</p>
      <p style={{ fontFamily: "Inter", fontSize: 18, fontWeight: 600, color: tone || "var(--battle-cyan)", margin: 0 }}>{value}</p>
    </div>
  );
}

function BattlePanel({ children, className = "", style = {} }: { children: ReactNode; className?: string; style?: CSSProperties }) {
  return <div className={`battle-panel rounded-2xl ${className}`} style={style}>{children}</div>;
}

function BattlePill({ children, tone = "cyan" }: { children: ReactNode; tone?: "cyan" | "amber" | "violet" | "rose" }) {
  const colors: Record<string, { border: string; bg: string; color: string }> = {
    cyan:   { border: "rgba(85,244,255,.3)",   bg: "rgba(85,244,255,.1)",   color: "var(--battle-cyan)"   },
    amber:  { border: "rgba(255,200,87,.3)",   bg: "rgba(255,200,87,.1)",   color: "var(--battle-amber)"  },
    violet: { border: "rgba(169,121,255,.3)",  bg: "rgba(169,121,255,.1)",  color: "var(--battle-violet)" },
    rose:   { border: "rgba(255,109,134,.3)",  bg: "rgba(255,109,134,.1)",  color: "var(--battle-rose)"   },
  };
  const c = colors[tone];
  return (
    <span
      style={{
        display: "inline-flex", alignItems: "center",
        borderRadius: 9999, border: `1px solid ${c.border}`,
        background: c.bg, color: c.color,
        padding: "0 10px",
        fontFamily: "monospace", fontSize: 9, letterSpacing: "0.12em",
        fontWeight: 700, textTransform: "uppercase",
        whiteSpace: "nowrap",
      }}
    >
      {children}
    </span>
  );
}

function BattleAction({
  children,
  onClick,
  muted = false,
  disabled = false,
  className = "",
  ariaLabel,
}: {
  children: ReactNode;
  onClick?: () => void;
  muted?: boolean;
  disabled?: boolean;
  className?: string;
  ariaLabel?: string;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      aria-label={ariaLabel}
      style={{
        minHeight: 40,
        borderRadius: 12,
        border: disabled ? "1px solid rgba(255,255,255,.1)" :
          muted ? "1px solid rgba(255,255,255,.15)" :
          "1px solid rgba(133,196,255,.6)",
        background: disabled ? "rgba(255,255,255,.05)" :
          muted ? "rgba(255,255,255,.05)" :
          "#55f4ff",
        color: disabled ? "#64748b" :
          muted ? "#e2e8f0" :
          "#0c1825",
        padding: "0 16px",
        fontWeight: 700,
        fontSize: 12,
        letterSpacing: "0.04em",
        cursor: disabled ? "not-allowed" : "pointer",
        opacity: disabled ? 0.4 : 1,
        display: "inline-flex", alignItems: "center", justifyContent: "center",
        gap: 6,
        transition: "all 0.15s",
        boxShadow: (!disabled && !muted) ? "0 0 24px rgba(85,244,255,.24)" : "none",
      }}
      className={className}
    >
      {children}
    </button>
  );
}

// ============================================================
// Cài đặt âm thanh (trong menu trận đấu)
// ============================================================
function AudioSettingsPanel() {
  const { audioSettings, updateAudioSettings } = useAirDefenseStore(
    useShallow((s) => ({ audioSettings: s.audioSettings, updateAudioSettings: s.updateAudioSettings }))
  );

  const rows = [
    {
      label: "Âm thanh chung",
      desc: "Bật/tắt và chỉnh âm lượng cho cả trò chơi.",
      icon: "fa-volume-high",
      enabled: audioSettings.masterEnabled,
      volume: audioSettings.masterVolume as number | null,
      onToggle: () => updateAudioSettings({ masterEnabled: !audioSettings.masterEnabled }),
      onVolume: (v: number) => updateAudioSettings({ masterVolume: v }),
      test: undefined as (() => void) | undefined,
    },
    {
      label: "Nhạc nền",
      desc: "Nhạc tự đổi theo diễn biến trận.",
      icon: "fa-music",
      enabled: audioSettings.bgmEnabled,
      volume: audioSettings.bgmVolume as number | null,
      onToggle: () => updateAudioSettings({ bgmEnabled: !audioSettings.bgmEnabled }),
      onVolume: (v: number) => updateAudioSettings({ bgmVolume: v }),
      test: undefined as (() => void) | undefined,
    },
    {
      label: "Tiếng hiệu ứng",
      desc: "Tiếng bắn, tiếng nổ và tiếng nhặt vật phẩm.",
      icon: "fa-bolt",
      enabled: audioSettings.sfxEnabled,
      volume: audioSettings.sfxVolume as number | null,
      onToggle: () => updateAudioSettings({ sfxEnabled: !audioSettings.sfxEnabled }),
      onVolume: (v: number) => updateAudioSettings({ sfxVolume: v }),
      test: () => soundManager.playLaser(),
    },
    {
      label: "Tiếng gõ phím",
      desc: "Phát tiếng lách cách mỗi khi gõ đáp án.",
      icon: "fa-keyboard",
      enabled: audioSettings.keystrokeEnabled,
      volume: null as number | null,
      onToggle: () => updateAudioSettings({ keystrokeEnabled: !audioSettings.keystrokeEnabled }),
      onVolume: null as ((v: number) => void) | null,
      test: () => soundManager.playKeyStroke(),
    },
  ];

  return (
    <div>
      <div style={{ display: "flex", flexDirection: "column", gap: 0, position: "relative" }}>
        {rows.map((row, i) => (
          <div key={row.label} style={{
            display: "flex", alignItems: "center", justifyContent: "space-between",
            gap: 16, padding: "14px 16px",
            borderRadius: 12,
            background: "var(--ok-surface-low)",
            marginBottom: i < rows.length - 1 ? 10 : 0,
          }}>
            <div style={{ display: "flex", alignItems: "center", gap: 14, flex: 1, minWidth: 0 }}>
              <div style={{
                width: 42, height: 42, borderRadius: 12,
                background: "var(--ok-secondary-fixed)",
                display: "flex", alignItems: "center", justifyContent: "center",
                color: "var(--ok-on-secondary-fixed-variant)", flexShrink: 0,
              }}>
                <i className={`fa-solid ${row.icon}`} style={{ fontSize: 20 }} aria-hidden="true" />
              </div>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontWeight: 700, fontSize: 15, color: "var(--ok-on-surface)" }}>{row.label}</div>
                <div style={{ fontSize: 12, color: "var(--ok-on-surface-variant)", marginTop: 2 }}>{row.desc}</div>
                {row.volume !== null && row.onVolume && (
                  <div style={{ marginTop: 10, display: "flex", alignItems: "center", gap: 12 }}>
                    <input
                      type="range" min={0} max={100} value={row.volume}
                      onChange={(e) => row.onVolume!(Number(e.target.value))}
                      className="ok-range"
                      aria-label={`Âm lượng ${row.label}`}
                      style={{ flex: 1 }}
                    />
                    <span style={{ fontWeight: 700, fontSize: 12, color: "var(--ok-primary-container)", width: 36, textAlign: "right" }}>{row.volume}%</span>
                  </div>
                )}
                {row.test && (
                  <button
                    type="button"
                    onClick={row.test}
                    style={{
                      marginTop: 8,
                      padding: "5px 12px", borderRadius: 8,
                      border: "1px solid var(--ok-outline-variant)",
                      background: "var(--ok-surface-container)", cursor: "pointer", minHeight: 44,
                      fontSize: 12, fontWeight: 600, color: "var(--ok-on-surface-variant)",
                    }}
                  >
                    Thử âm thanh
                  </button>
                )}
              </div>
            </div>

            {/* Toggle switch */}
            <label className="ok-toggle" style={{ flexShrink: 0 }}>
              <input type="checkbox" checked={row.enabled} onChange={row.onToggle} aria-label={`Bật/tắt ${row.label}`} />
              <span className="ok-toggle-slider" />
            </label>
          </div>
        ))}
      </div>

      <div style={{ marginTop: 14, display: "flex", justifyContent: "flex-end" }}>
        <OkBtnGhost
          onClick={() => updateAudioSettings({ masterEnabled: true, masterVolume: 80, bgmEnabled: true, bgmVolume: 70, sfxEnabled: true, sfxVolume: 85, keystrokeEnabled: true })}
        >
          <i className="fa-solid fa-arrow-rotate-left" style={{ fontSize: 18 }} aria-hidden="true" />
          Khôi phục mặc định
        </OkBtnGhost>
      </div>
    </div>
  );
}

// ============================================================
// Hướng dẫn nhanh (rút gọn từ Cẩm nang Chỉ huy)
// ============================================================
function QuickGuide({ match }: { match: MatchInfo }) {
  const typingHint =
    match.answerField === "HIRAGANA"
      ? "Gõ chữ Latinh (vd. gakkou) hoặc gõ chữ Nhật đều được."
      : match.answerField === "KANJI"
        ? "Dùng bộ gõ tiếng Nhật để nhập Kanji, chọn chữ xong rồi nhấn Enter."
        : "Không cần gõ dấu tiếng Việt. Nếu thẻ có nhiều nghĩa (cách nhau bởi dấu phẩy), gõ một nghĩa là đủ.";

  const steps = [
    {
      step: 1,
      title: "Đọc câu hỏi trên máy bay địch",
      desc: `Mỗi địch mang ${labelInSentence(match.questionField, match.questionLabel)} của một thẻ trong bộ “${match.deckTitle}”. Gõ ${labelInSentence(match.answerField, match.answerLabel)} tương ứng rồi nhấn Enter — tia Laser tự khoá vào địch gần phòng tuyến nhất.`,
      color: "var(--ok-primary-fixed)",
    },
    { step: 2, title: "Cách gõ đáp án", desc: typingHint, color: "var(--ok-primary-fixed)" },
    { step: 3, title: "Combo", desc: "Mỗi máy bay bị hạ được 1 điểm. Bắn trúng liên tiếp tăng combo; gõ sai hoặc để địch chạm vạch là mất combo.", color: "var(--ok-tertiary-fixed)" },
    { step: 4, title: "Giữ máu cho hành tinh", desc: "Địch chạm vạch sẽ trừ máu hành tinh. Hết máu là thua. Tốc độ tăng sau mỗi 3 thẻ đã qua, đạt tối đa 2 lần sau 10 thẻ.", color: "var(--ok-error-container)" },
  ];

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(220px, 1fr))", gap: 12 }}>
        {steps.map((s) => (
          <OkSurface key={s.step} style={{ padding: 14 }}>
            <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 6 }}>
              <span style={{
                width: 24, height: 24, borderRadius: 8,
                background: s.color, display: "flex", alignItems: "center", justifyContent: "center",
                fontSize: 12, fontWeight: 700, color: s.step === 4 ? "var(--ok-on-error-container)" : s.step === 3 ? "var(--ok-on-tertiary-fixed)" : "var(--ok-on-primary-fixed)", flexShrink: 0,
              }}>{s.step}</span>
              <h3 style={{ fontSize: 14, fontWeight: 700, color: "var(--ok-on-surface)", margin: 0 }}>{s.title}</h3>
            </div>
            <p style={{ fontSize: 13, color: "var(--ok-on-surface-variant)", lineHeight: "18px", margin: 0 }}>{s.desc}</p>
          </OkSurface>
        ))}
      </div>
      <OkSurface style={{ background: "var(--ok-tertiary-fixed)", padding: 14 }}>
        <ul style={{ margin: 0, padding: "0 0 0 18px", fontSize: 13, color: "var(--ok-on-surface-variant)", lineHeight: "22px" }}>
          <li><strong>Siêu pháo (phím Shift)</strong> khi thanh năng lượng đầy: quét sạch địch thường, trừ {GAME_CONFIG.PLAYER.hyperBeamBossDamage} máu của trùm.</li>
          <li><strong>Vật phẩm rơi:</strong> <i className="fa-solid fa-gem" aria-hidden="true" /> pha lê cộng xu, <i className="fa-solid fa-heart" aria-hidden="true" /> hộp hồi {GAME_CONFIG.LOOT.repairValue} máu, <i className="fa-solid fa-atom" aria-hidden="true" /> cầu nạp {GAME_CONFIG.LOOT.hyperOrbCharge}% năng lượng siêu pháo.</li>
          <li><strong>Nâng cấp:</strong> sau mỗi {GAME_CONFIG.ENEMIES.augmentDraftInterval} đợt được chọn 1 nâng cấp sức mạnh.</li>
          {match.versus ? (
            <>
              <li><strong>Đối kháng:</strong> đạt combo {match.attackMilestones.join(", ")} sẽ gửi một con trùm sang hành tinh đối thủ. Nâng cấp được chọn tự động sau {Math.round(GAME_CONFIG.ENEMIES.versusAugmentAutoPickMs / 1000)} giây.</li>
              <li>Trận kết thúc khi một bên hết máu hoặc hết giờ. Hết giờ thì ai còn nhiều máu hơn thắng; bằng máu thì so điểm.</li>
            </>
          ) : (
            <li><strong>Luyện tập:</strong> chơi tới khi hành tinh thất thủ, hoặc mở menu để kết thúc và xem tổng kết.</li>
          )}
          <li><strong>Esc</strong> mở menu trận đấu.</li>
        </ul>
      </OkSurface>
    </div>
  );
}

// ============================================================
// Menu trận đấu: tạm dừng (chơi đơn), âm thanh, hướng dẫn, rời trận
// ============================================================
function VisualSettingsPanel() {
  const visual = useVisualSettings();
  return <div className="ad-visual-settings">
    <label><span><strong>Giảm chuyển động</strong><small>Giữ hiệu ứng chính, giảm chuyển cảnh và nhấp nháy.</small></span><input type="checkbox" checked={visual.reducedMotion} onChange={(e) => visual.update({reducedMotion:e.target.checked})}/></label>
    <label><span><strong>Rung màn hình</strong><small>Phản hồi nhẹ khi bị đánh hoặc dùng siêu pháo.</small></span><input type="checkbox" checked={visual.screenShake} disabled={visual.reducedMotion} onChange={(e) => visual.update({screenShake:e.target.checked})}/></label>
    <label><span><strong>Hạt hiệu ứng</strong><small>Giảm số hạt trên máy có cấu hình thấp.</small></span><select value={visual.particles} onChange={(e) => visual.update({particles:e.target.value as "high" | "low" | "off"})}><option value="high">Cao</option><option value="low">Thấp</option><option value="off">Tắt</option></select></label>
    <label><span><strong>Hiển thị FPS</strong><small>Theo dõi tốc độ khung hình thực tế.</small></span><input type="checkbox" checked={visual.showFps} onChange={(e) => visual.update({showFps:e.target.checked})}/></label>
    <p className="ad-settings-note">Cài đặt hình ảnh được lưu trên thiết bị này.</p>
  </div>;
}

function MatchMenu() {
  const reducedMotion = useVisualSettings((s) => s.reducedMotion);
  const { match, outcome, closeSettings } = useAirDefenseStore(
    useShallow((s) => ({ match: s.match, outcome: s.outcome, closeSettings: s.closeSettings }))
  );
  const [tab, setTab] = useState<"audio" | "visual" | "guide">("audio");
  const [confirmLeave, setConfirmLeave] = useState(false);
  const [busy, setBusy] = useState(false);
  const panelRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const panel = panelRef.current;
    panel?.focus();
    const trapFocus = (e: KeyboardEvent) => {
      if (e.key !== "Tab" || !panel) return;
      const buttons = [...panel.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), select:not(:disabled), a[href]')];
      const first = buttons[0], last = buttons[buttons.length - 1];
      if (!first) return;
      if (e.shiftKey && (document.activeElement === first || document.activeElement === panel)) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && (document.activeElement === last || document.activeElement === panel)) { e.preventDefault(); first.focus(); }
    };
    document.addEventListener("keydown", trapFocus);
    return () => document.removeEventListener("keydown", trapFocus);
  }, []);

  if (!match) return null;
  const solo = match.playMode === "SOLO";
  const canPause = solo && match.endsAt === null;
  const canFinish = solo && outcome === "playing";

  const onLeave = async () => {
    setBusy(true);
    await leaveMatch();
  };

  const onFinish = () => {
    closeSettings();
    void finishSoloMatch();
  };

  const tabStyle = (active: boolean): CSSProperties => ({
    padding: "8px 14px", borderRadius: 10, border: "none",
    background: active ? "var(--ok-secondary-fixed)" : "transparent",
    color: active ? "var(--ok-on-secondary-fixed-variant)" : "var(--ok-on-surface-variant)",
    fontWeight: active ? 700 : 500, fontSize: 14, cursor: "pointer",
    display: "inline-flex", alignItems: "center", gap: 6,
  });

  return (
    <div
      className={`ad-menu-backdrop ${reducedMotion ? "ad-reduced-motion" : ""}`}
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) closeSettings();
      }}
    >
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="ad-menu-title"
        tabIndex={-1}
        className="ad-settings-drawer"
      >
        <div style={{ display: "flex", alignItems: "flex-start", justifyContent: "space-between", gap: 16, marginBottom: 18 }}>
          <div>
            <OkBadge variant={canPause ? "gold" : "error"}>
              <i className={`fa-solid ${canPause ? "fa-pause" : "fa-bolt"}`} style={{ fontSize: 12 }} aria-hidden="true" />
              &nbsp;{canPause ? (outcome === "playing" ? "Đã tạm dừng" : "Menu") : "Trận vẫn đang diễn ra"}
            </OkBadge>
            <h2 id="ad-menu-title" style={{ fontSize: 26, fontWeight: 700, letterSpacing: "-0.02em", color: "var(--ok-primary)", margin: "10px 0 4px" }}>
              Cài đặt trận đấu
            </h2>
            {!canPause && (
              <p style={{ fontSize: 14, color: "var(--ok-on-surface-variant)", margin: 0 }}>
                {solo ? "Trận có giới hạn thời gian tiếp tục chạy khi mở cài đặt." : "Trận đối kháng tiếp tục chạy khi mở cài đặt."}
              </p>
            )}
          </div>
          <button
            type="button"
            onClick={closeSettings}
            aria-label="Đóng menu"
            style={{
              width: 44, height: 44, borderRadius: 10, flexShrink: 0,
              border: "1px solid var(--ok-outline-variant)",
              background: "var(--ok-surface-low)",
              display: "flex", alignItems: "center", justifyContent: "center",
              cursor: "pointer", color: "var(--ok-on-surface-variant)",
            }}
          >
            <i className="fa-solid fa-xmark" style={{ fontSize: 20 }} aria-hidden="true" />
          </button>
        </div>

        {/* Một hàng: Tiếp tục (icon) · Kết thúc · Rời trận (icon) | Âm thanh · Hướng dẫn */}
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 10, flexWrap: "wrap", marginBottom: 14 }}>
          <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <OkBtnPrimary onClick={closeSettings} ariaLabel="Tiếp tục" style={{ width: 52, height: 44, padding: 0 }}>
              <i className="fa-solid fa-play" style={{ fontSize: 18 }} aria-hidden="true" title="Tiếp tục" />
            </OkBtnPrimary>
            {solo && (
              <OkBtnSecondary onClick={onFinish} disabled={!canFinish || busy} style={{ height: 44 }}>Kết thúc</OkBtnSecondary>
            )}
            <OkBtnGhost onClick={() => setConfirmLeave(true)} disabled={busy} ariaLabel="Rời trận"
              style={{ color: "var(--ok-error)", width: 52, height: 44, padding: 0 }}>
              <i className="fa-solid fa-right-from-bracket" style={{ fontSize: 18 }} aria-hidden="true" title="Rời trận" />
            </OkBtnGhost>
          </div>
          <div role="tablist" aria-label="Nội dung menu" style={{ display: "flex", gap: 4, padding: 4, background: "var(--ok-surface-low)", borderRadius: 12 }}>
            <button type="button" role="tab" aria-selected={tab === "audio"} onClick={() => setTab("audio")} style={tabStyle(tab === "audio")}>
              <i className="fa-solid fa-sliders" style={{ fontSize: 18 }} aria-hidden="true" />
              Âm thanh
            </button>
            <button type="button" role="tab" aria-selected={tab === "visual"} onClick={() => setTab("visual")} style={tabStyle(tab === "visual")}>
              <i className="fa-solid fa-display" aria-hidden="true"/> Hình ảnh
            </button>
            <button type="button" role="tab" aria-selected={tab === "guide"} onClick={() => setTab("guide")} style={tabStyle(tab === "guide")}>
              <i className="fa-solid fa-book-open" style={{ fontSize: 18 }} aria-hidden="true" />
              Hướng dẫn
            </button>
          </div>
        </div>

        {confirmLeave && (
          <div
            role="alertdialog"
            aria-labelledby="ad-leave-title"
            style={{
              marginBottom: 16, padding: 16, borderRadius: 12,
              background: "#ffdad6", border: "1px solid rgba(186,26,26,.3)",
            }}
          >
            <p id="ad-leave-title" style={{ margin: "0 0 10px", fontSize: 14, fontWeight: 600, color: "#410002" }}>
              {match.versus
                ? "Rời trận lúc này đồng nghĩa với THUA trận đối kháng. Bạn chắc chắn muốn rời?"
                : "Rời trận lúc này sẽ bỏ dở lượt chơi (không có màn tổng kết). Bạn chắc chắn muốn rời?"}
            </p>
            <div style={{ display: "flex", gap: 10, flexWrap: "wrap" }}>
              <button
                type="button"
                onClick={onLeave}
                disabled={busy}
                className="ok-btn ok-btn-danger"
              >
                {busy ? "Đang rời trận…" : "Rời trận"}
              </button>
              <OkBtnGhost onClick={() => setConfirmLeave(false)} disabled={busy}>Ở lại</OkBtnGhost>
            </div>
          </div>
        )}

        <div role="tabpanel">{tab === "audio" ? <AudioSettingsPanel /> : tab === "visual" ? <VisualSettingsPanel /> : <QuickGuide match={match} />}</div>
      </div>
    </div>
  );
}

// ============================================================
// SCREEN: AugmentDraft (10_augment_modal)
// ============================================================
export function AugmentDraft({ preview }: { preview?: { sound: (action:string) => void; autoSelect?:boolean } } = {}) {
  const { draftAugments, remainingRerolls, selectAugment, rerollAugments, versus } = useAirDefenseStore(
    useShallow((s) => ({
      draftAugments: s.draftAugments,
      remainingRerolls: s.remainingRerolls,
      selectAugment: s.selectAugment,
      rerollAugments: s.rerollAugments,
      versus: !!s.match?.versus,
    }))
  );
  const [selectedCard, setSelectedCard] = useState<AugmentCard | null>(null);
  const [secondsLeft, setSecondsLeft] = useState(Math.round(GAME_CONFIG.ENEMIES.versusAugmentAutoPickMs / 1000));
  const selectedRef = useRef(false);

  const handleSelect = (card: AugmentCard) => {
    if (selectedRef.current) return;
    selectedRef.current = true;
    setSelectedCard(card);
    if (preview) preview.sound('augment_select');
    else soundManager.playAugmentSelect();
    if (!preview) setTimeout(() => { selectAugment(card); }, 700);
  };

  useEffect(() => {
    if (!preview?.autoSelect || !draftAugments[0]) return;
    const id = setTimeout(() => handleSelect(draftAugments[0]), 650);
    return () => clearTimeout(id);
  }, [preview?.autoSelect, draftAugments]);

  // Đối kháng: thế giới của đối thủ vẫn chạy — sau 10 giây tự chọn ngẫu nhiên 1 lõi.
  useEffect(() => {
    if (!versus) return;
    const deadline = Date.now() + GAME_CONFIG.ENEMIES.versusAugmentAutoPickMs;
    const id = setInterval(() => {
      const left = deadline - Date.now();
      setSecondsLeft(Math.max(0, Math.ceil(left / 1000)));
      if (left <= 0) {
        clearInterval(id);
        if (!selectedRef.current) {
          const pool = useAirDefenseStore.getState().draftAugments;
          if (pool.length > 0) handleSelect(pool[Math.floor(Math.random() * pool.length)]);
        }
      }
    }, 200);
    return () => clearInterval(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [versus]);


  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="ad-augment-title"
      style={{
        position: preview ? "absolute" : "fixed", inset: 0, zIndex: 50,
        background: "rgba(0,0,0,.35)",
        backdropFilter: "blur(8px)",
        display: "flex", alignItems: "center", justifyContent: "center",
        padding: preview ? 12 : 24,
      }}
    >
      {selectedCard && (
        <div style={{
          position: preview ? "absolute" : "fixed", inset: 0, zIndex: 60,
          display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center",
          background: "rgba(233,221,255,.4)", backdropFilter: "blur(12px)",
        }}>
          <div style={{ textAlign: "center" }} aria-live="polite">
            <div style={{ fontSize: 10, fontWeight: 700, letterSpacing: "0.12em", textTransform: "uppercase", color: "var(--ok-secondary)", marginBottom: 8 }}>
              NÂNG CẤP HỆ THỐNG HOÀN TẤT
            </div>
            <h2 style={{ fontSize: 40, fontWeight: 800, color: "var(--ok-primary)", letterSpacing: "-0.03em" }}>
              <i className="fa-solid fa-star" style={{ fontSize: 26 }} aria-hidden="true" /> ĐÃ KÍCH HOẠT: {selectedCard.title} <i className="fa-solid fa-star" style={{ fontSize: 26 }} aria-hidden="true" />
            </h2>
          </div>
        </div>
      )}

      <div style={{
        position: "relative",
        width: "100%", maxWidth: 900,
        maxHeight: "92vh", overflowY: "auto",
        background: "#fff",
        borderRadius: 20,
        boxShadow: "0 20px 50px rgba(34,0,93,.14)",
        padding: 32,
      }}>
        {/* Decorative glow */}
        <div style={{
          position: "absolute", top: -80, left: "50%", transform: "translateX(-50%)",
          width: 320, height: 160, borderRadius: "50%",
          background: "var(--ok-primary-fixed)", opacity: 0.4, filter: "blur(40px)",
          pointerEvents: "none",
        }} />

        {/* Header */}
        <div style={{ textAlign: "center", marginBottom: 28, position: "relative" }}>
          <OkBadge variant="primary">
            <i className="fa-solid fa-sliders" style={{ fontSize: 14 }} aria-hidden="true" />
            &nbsp;HỆ THỐNG NÂNG CẤP VŨ TRỤ
          </OkBadge>
          <h2 id="ad-augment-title" style={{ fontSize: 32, fontWeight: 700, color: "var(--ok-primary)", margin: "12px 0 8px", letterSpacing: "-0.02em" }}>
            Chọn 1 lõi bổ trợ
          </h2>
          <p style={{ fontSize: 16, color: "var(--ok-on-surface-variant)", margin: 0 }}>
            Sau mỗi {GAME_CONFIG.ENEMIES.augmentDraftInterval} đợt, chọn 1 nâng cấp để tăng sức mạnh cho máy bay của bạn.
          </p>
          {versus && !selectedCard && (
            <p style={{ fontSize: 13, fontWeight: 700, color: "var(--ok-error)", marginTop: 8 }} aria-live="polite">
              Đối thủ vẫn đang chiến đấu — tự chọn ngẫu nhiên sau {secondsLeft} giây
            </p>
          )}
        </div>

        {/* Cards */}
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(220px, 1fr))", gap: 20, marginBottom: 28 }}>
          {draftAugments.map((card, index) => {
            const isChosen = selectedCard?.id === card.id;
            const isOther = !!selectedCard && !isChosen;

            return (
              <div
                key={card.id}
                role="button"
                tabIndex={selectedCard ? -1 : 0}
                aria-label={`Chọn nâng cấp ${card.title}: ${card.description}`}
                onClick={() => !selectedCard && handleSelect(card)}
                onKeyDown={(e) => {
                  if ((e.key === "Enter" || e.key === " ") && !selectedCard) {
                    e.preventDefault();
                    handleSelect(card);
                  }
                }}
                onMouseEnter={() => preview ? preview.sound('augment_hover') : soundManager.playAugmentHover()}
                style={{
                  position: "relative",
                  cursor: selectedCard ? "default" : "pointer",
                  borderRadius: 16,
                  padding: preview ? 12 : 24,
                  background: isChosen ? "#f7f2ff" : "#fff",
                  border: isChosen
                    ? "2px solid var(--ok-primary-container)"
                    : "1px solid var(--ok-outline-variant)",
                  boxShadow: isChosen
                    ? "0 4px 20px rgba(79,55,138,.12)"
                    : "var(--ok-shadow-1)",
                  opacity: isOther ? 0.3 : 1,
                  transform: isOther ? "scale(0.96)" : "none",
                  transition: "all 0.2s",
                  display: "flex", flexDirection: "column", justifyContent: "space-between",
                  minHeight: preview ? 160 : 220,
                }}
              >
                <div>
                  <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 20 }}>
                    <div style={{
                      width: 56, height: 56, borderRadius: 16,
                      background: isChosen ? "var(--ok-secondary-fixed)" : "var(--ok-surface-low)",
                      display: "flex", alignItems: "center", justifyContent: "center",
                      color: isChosen ? "var(--ok-primary-container)" : "var(--ok-on-surface-variant)",
                    }}>
                      <i className={`fa-solid ${card.icon}`} style={{ fontSize: 26 }} aria-hidden="true" />
                    </div>
                    <div style={{
                      minWidth: 24, height: 24, borderRadius: "50%",
                      background: isChosen ? "var(--ok-primary)" : "var(--ok-surface-low)",
                      display: "flex", alignItems: "center", justifyContent: "center",
                      color: isChosen ? "#fff" : "var(--ok-on-surface-variant)",
                      fontSize: 12, fontWeight: 700,
                    }}>
                      {isChosen ? <i className="fa-solid fa-check" style={{ fontSize: 16 }} aria-hidden="true" /> : index + 1}
                    </div>
                  </div>
                  <h3 style={{ fontSize: 22, fontWeight: 700, color: isChosen ? "var(--ok-primary)" : "var(--ok-on-surface)", margin: "0 0 8px", letterSpacing: "-0.01em" }}>
                    {card.title}
                  </h3>
                  <p style={{ fontSize: 14, color: "var(--ok-on-surface-variant)", lineHeight: "20px", margin: 0 }}>
                    {card.description}
                  </p>
                </div>

                <div style={{ marginTop: 20, paddingTop: 12, borderTop: "1px solid var(--ok-surface-container)" }}>
                  <OkBadge variant={isChosen ? "primary" : "outline"}>
                    <i className="fa-solid fa-bolt" style={{ fontSize: 14 }} aria-hidden="true" />
                    &nbsp;{String(card.category)}
                  </OkBadge>
                </div>
              </div>
            );
          })}
        </div>

        {/* Footer */}
        <div style={{ display: "flex", alignItems: "center", justifyContent: "center", gap: 16 }}>
          <OkBtnGhost
            onClick={() => { if (preview) preview.sound('augment_hover'); rerollAugments(); }}
            disabled={remainingRerolls <= 0 || !!selectedCard}
          >
            <i className="fa-solid fa-rotate-right" style={{ fontSize: 18 }} aria-hidden="true" />
            Đổi lõi (Reroll) · Còn {remainingRerolls} lần
          </OkBtnGhost>
        </div>
      </div>
    </div>
  );
}

// ============================================================
// HUD đối thủ (đối kháng) — từ state.players + AIR_PROGRESS + SESSION_PRESENCE
// ============================================================
function OpponentBar({ opponent, inboundBoss }: { opponent: AirPlayerView | null; inboundBoss: boolean }) {
  if (!opponent) {
    return (
      <BattlePanel style={{ flexShrink: 0, padding: "8px 16px", fontFamily: "monospace", fontSize: 11, color: "#94a3b8" }}>
        Đang chờ thông tin đối thủ…
      </BattlePanel>
    );
  }
  const pct = opponent.maxHp > 0 ? Math.max(0, Math.min(100, (opponent.hp / opponent.maxHp) * 100)) : 0;
  const down = opponent.finished && !opponent.alive;
  const hpColor = pct > 50 ? "var(--battle-cyan)" : pct > 25 ? "var(--battle-amber)" : "var(--battle-rose)";

  return (
    <BattlePanel style={{ flexShrink: 0, display: "flex", alignItems: "center", justifyContent: "space-between", gap: 16, padding: "8px 16px", flexWrap: "wrap" }}>
      <div style={{ display: "flex", alignItems: "center", gap: 12, minWidth: 0, flex: "1 1 320px" }} aria-label={`Đối thủ ${opponent.displayName}`}>
        {opponent.avatarUrl ? (
          <img
            src={opponent.avatarUrl}
            alt=""
            style={{ width: 32, height: 32, borderRadius: "50%", objectFit: "cover", border: "1px solid rgba(169,121,255,.5)", flexShrink: 0, opacity: opponent.online ? 1 : 0.5 }}
          />
        ) : (
          <span style={{
            width: 32, height: 32, borderRadius: "50%", flexShrink: 0,
            background: "rgba(169,121,255,.2)",
            display: "flex", alignItems: "center", justifyContent: "center",
            fontWeight: 700, fontSize: 13, color: "var(--battle-violet)",
            opacity: opponent.online ? 1 : 0.5,
          }}>{initialOf(opponent.displayName)}</span>
        )}
        <div style={{ minWidth: 0, flex: 1 }}>
          <div style={{ display: "flex", alignItems: "center", gap: 8, flexWrap: "wrap" }}>
            <span style={{ fontWeight: 700, fontSize: 13, color: "#fff", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", maxWidth: 220 }}>
              {opponent.displayName}
            </span>
            <span style={{ fontFamily: "monospace", fontSize: 9, color: "#94a3b8" }}>ĐỐI THỦ</span>
            {!opponent.online && <BattlePill tone="amber">Mất kết nối</BattlePill>}
            {down && <BattlePill tone="rose">Hành tinh thất thủ</BattlePill>}
          </div>
          <div style={{ display: "flex", alignItems: "center", gap: 8, marginTop: 4 }}>
            <div
              role="meter"
              aria-label="Máu hành tinh đối thủ"
              aria-valuemin={0}
              aria-valuemax={opponent.maxHp}
              aria-valuenow={opponent.hp}
              style={{ flex: 1, maxWidth: 260, height: 6, borderRadius: 9999, background: "rgba(255,255,255,.1)", overflow: "hidden" }}
            >
              <div style={{ height: "100%", width: `${pct}%`, background: hpColor, transition: "width .3s" }} />
            </div>
            <span style={{ fontFamily: "monospace", fontSize: 10, fontWeight: 700, color: hpColor }}>
              MÁU {opponent.hp}/{opponent.maxHp}
            </span>
          </div>
        </div>
      </div>
      <div style={{ display: "flex", alignItems: "center", gap: 18 }}>
        <BattleStat label="ĐIỂM" value={opponent.score.toLocaleString()} tone="var(--battle-violet)" />
        <BattleStat label="ĐỢT" value={opponent.wave} tone="var(--battle-violet)" />
        <BattleStat label="COMBO" value={`×${opponent.combo}`} tone="var(--battle-violet)" />
        {inboundBoss && <BattlePill tone="rose"><i className="fa-solid fa-triangle-exclamation" aria-hidden="true" /> TRÙM ĐANG TỚI</BattlePill>}
      </div>
    </BattlePanel>
  );
}

// ============================================================
// Đồng hồ đối kháng: endsAt - serverNow(); tới 0 thì dừng mô phỏng
// ============================================================
function useTimeLeft(endsAt: number | null): number | null {
  const [left, setLeft] = useState<number | null>(() => (endsAt === null ? null : endsAt - serverNow()));
  useEffect(() => {
    if (endsAt === null) {
      setLeft(null);
      return;
    }
    const tick = () => {
      const ms = endsAt - serverNow();
      setLeft(ms);
      if (ms <= 0) useAirDefenseStore.getState().markTimeUp();
    };
    tick();
    const id = setInterval(tick, 250);
    return () => clearInterval(id);
  }, [endsAt]);
  return left;
}

// ============================================================
// SCREEN: Battle (dark theme — PixiJS)
// ============================================================
function Battle() {
  const [input, setInput] = useState("");
  const [feedback, setFeedback] = useState<"correct" | "wrong" | null>(null);
  const feedbackTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const reducedMotion = useVisualSettings((s) => s.reducedMotion);
  useEffect(() => () => { if (feedbackTimer.current) clearTimeout(feedbackTimer.current); }, []);
  const inputRef = useRef<HTMLInputElement>(null);
  const {
    match, players, outcome, screen, paused, isSettingsOpen, introActive, liveStatus, banner, notice, finishingPresentation,
    wave, hp, maxHp, shield, score, combo, creditsEarned, hyperBeamCharge, hyperBeamPhase, inboundBoss, bgmEnabled,
    submitAnswer, fireHyperBeam, aimTypingBullet, openSettings, toggleBgm, setNotice,
  } = useAirDefenseStore(
    useShallow((s) => ({
      match: s.match,
      players: s.players,
      outcome: s.outcome,
      finishingPresentation: s.finishingPresentation,
      screen: s.screen,
      paused: s.paused,
      isSettingsOpen: s.isSettingsOpen,
      introActive: s.introState.active,
      liveStatus: s.liveStatus,
      banner: s.banner,
      notice: s.notice,
      wave: s.wave,
      hp: s.hp,
      maxHp: s.maxHp,
      shield: s.shield,
      score: s.score,
      combo: s.combo,
      creditsEarned: s.creditsEarned,
      hyperBeamCharge: s.hyperBeamCharge,
      hyperBeamPhase: s.hyperBeamPhase,
      inboundBoss: s.inboundBoss,
      bgmEnabled: s.bgmEnabled,
      submitAnswer: s.submitAnswer,
      fireHyperBeam: s.fireHyperBeam,
      aimTypingBullet: s.aimTypingBullet,
      openSettings: s.openSettings,
      toggleBgm: s.toggleBgm,
      setNotice: s.setNotice,
    }))
  );

  const rawTimeLeft = useTimeLeft(match?.endsAt ?? null);
  // Trong lúc đếm ngược đồng hồ đứng ở đúng thời lượng đã chọn (endsAt tính từ startAt).
  const timeLeft = rawTimeLeft === null || !match || match.endsAt === null
    ? rawTimeLeft
    : Math.min(rawTimeLeft, match.endsAt - match.startAt);
  const beamReady = hyperBeamCharge >= GAME_CONFIG.PLAYER.hyperBeamMaxCharge;
  const inputDisabled = outcome !== "playing" || paused || screen === "augment";

  // Phím tắt toàn cục: SHIFT = Hyper Beam, Esc = menu.
  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.isComposing || e.keyCode === 229) return;
      const st = useAirDefenseStore.getState();
      if (e.key === "Escape") {
        if (st.screen === "augment") return;
        e.preventDefault();
        if (st.isSettingsOpen) st.closeSettings();
        else st.openSettings();
        return;
      }
      if (e.key === "Shift" && !e.repeat && !st.isSettingsOpen && st.hyperBeamCharge >= GAME_CONFIG.PLAYER.hyperBeamMaxCharge) {
        e.preventDefault();
        st.fireHyperBeam();
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, []);

  // Trả focus về ô nhập khi đóng menu / chọn lõi xong.
  useEffect(() => {
    if (!isSettingsOpen && screen !== "augment" && outcome === "playing") {
      inputRef.current?.focus();
    }
  }, [isSettingsOpen, screen, outcome]);

  if (!match) return null;

  const opponent = match.versus ? players.find((p) => p.userId !== match.meId) ?? null : null;
  const placeholder = answerPlaceholder(match);
  const hpPct = maxHp > 0 ? Math.max(0, Math.min(100, (hp / maxHp) * 100)) : 0;

  const handleFire = () => {
    if (!input.trim() || inputDisabled) return;
    const st = useAirDefenseStore.getState();
    // Đang intro / chuyển đợt: giữ nguyên chữ đã gõ để bắn ngay khi địch xuất hiện.
    if (st.introState.active || st.isTransitioning) return;
    const correct = submitAnswer(input);
    // This projectile is cosmetic; the existing answer matcher still resolves the hit.
    useAirDefenseStore.getState().fireTypingBullet(correct ? useAirDefenseStore.getState().lastLaserTarget : null);
    setFeedback(correct ? "correct" : "wrong");
    if (feedbackTimer.current) clearTimeout(feedbackTimer.current);
    feedbackTimer.current = setTimeout(() => setFeedback(null), 700);
    setInput("");
  };

  const waitingText =
    outcome === "defeated"
      ? match.versus
        ? "Hành tinh thất thủ — chờ kết quả…"
        : "Hành tinh thất thủ — đang tổng kết…"
      : outcome === "timeUp"
        ? "Hết giờ — đang chờ kết quả…"
        : outcome === "stopping"
          ? "Đang kết thúc trận…"
          : null;

  return (
    <div className={`battle-shell ad-battle ${reducedMotion ? "ad-reduced-motion" : ""}`}>
      <BattleHud match={match} wave={wave} timeLeft={timeLeft} score={score} combo={combo}
        credits={creditsEarned} hp={hp} maxHp={maxHp} shield={shield} charge={hyperBeamCharge}
        disabled={inputDisabled || introActive} ship={playerSpaceshipCode()} liveStatus={liveStatus}
        bgm={bgmEnabled} onMusic={toggleBgm} onMenu={openSettings} onBeam={fireHyperBeam} />

      {/* PvP opponent bar */}
      {match.versus && <OpponentBar opponent={opponent} inboundBoss={inboundBoss} />}

      {/* Canvas */}
      <div className={`ad-battle-viewport ${hpPct <= 25 ? "is-low-hp" : ""} ${beamReady ? "is-super-ready" : ""} is-beam-${hyperBeamPhase}`}>
        <PixiBattleCanvas />

        {/* Banner đòn tấn công (nhận / gửi mini-boss) */}
        {banner && (
          <div
            key={banner.id}
            role="status"
            aria-live="assertive"
            style={{
              position: "absolute", top: 64, left: "50%", transform: "translateX(-50%)",
              zIndex: 45, pointerEvents: "none", maxWidth: "min(560px, 92%)",
              padding: "10px 18px", borderRadius: 16, textAlign: "center",
              background: banner.kind === "incoming" ? "rgba(46,10,70,.92)" : "rgba(6,40,52,.92)",
              border: banner.kind === "incoming" ? "1px solid rgba(195,166,255,.9)" : "1px solid rgba(85,244,255,.8)",
              boxShadow: banner.kind === "incoming" ? "0 0 32px rgba(169,121,255,.6)" : "0 0 28px rgba(85,244,255,.5)",
              backdropFilter: "blur(8px)",
            }}
          >
            <div style={{ fontFamily: "monospace", fontSize: 12, fontWeight: 800, letterSpacing: "0.06em", color: banner.kind === "incoming" ? "#e9ddff" : "var(--battle-cyan)" }}>
              <i className={`fa-solid ${banner.kind === "incoming" ? "fa-triangle-exclamation" : "fa-rocket"}`} style={{ marginRight: 6 }} aria-hidden="true" />{banner.title}
            </div>
            {banner.detail && (
              <div style={{ fontFamily: "monospace", fontSize: 10, color: "#cbd5e1", marginTop: 2 }}>{banner.detail}</div>
            )}
          </div>
        )}

        {/* Chờ kết quả (hành tinh thất thủ / hết giờ / đang kết thúc) */}
        {finishingPresentation && (
          <div role="status" aria-live="polite" style={{ position: 'absolute', bottom: 16, left: '50%', transform: 'translateX(-50%)',
            zIndex: 50, pointerEvents: 'none', padding: '8px 14px', borderRadius: 10, background: 'rgba(3,6,14,.7)',
            color: '#fff', fontSize: 13, textAlign: 'center', width: 'max-content', maxWidth: 'calc(100% - 32px)' }}>
            Trận đấu kết thúc · Đang tổng kết…
          </div>
        )}
        {waitingText && !finishingPresentation && (
          <div
            role="status"
            aria-live="polite"
            style={{
              position: "absolute", inset: 0, zIndex: 50,
              display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", gap: 14,
              background: "rgba(3,6,14,.72)", backdropFilter: "blur(4px)", borderRadius: 16, textAlign: "center", padding: 16,
            }}
          >
            <p style={{ fontFamily: "monospace", fontSize: 12, letterSpacing: "0.3em", color: outcome === "defeated" ? "var(--battle-rose)" : "var(--battle-amber)", margin: 0, textTransform: "uppercase" }}>
              {outcome === "defeated" ? "Phòng tuyến sụp đổ" : outcome === "timeUp" ? "Hết giờ tác chiến" : "Rút quân"}
            </p>
            <h2 style={{ fontFamily: "Inter", fontSize: 30, fontWeight: 800, color: "#fff", margin: 0 }}>{waitingText}</h2>
            {opponent && (
              <p style={{ fontFamily: "monospace", fontSize: 12, color: "#cbd5e1", margin: 0 }}>
                {opponent.displayName}: {opponent.score.toLocaleString()} điểm · máu {opponent.hp}/{opponent.maxHp} · đợt {opponent.wave}
              </p>
            )}
          </div>
        )}

        {/* Thông báo lỗi */}
        {notice && (
          <div
            role="alert"
            style={{
              position: "absolute", bottom: 12, left: "50%", transform: "translateX(-50%)", zIndex: 55,
              display: "flex", alignItems: "center", gap: 12, maxWidth: "92%",
              background: "rgba(60,10,20,.92)", border: "1px solid rgba(255,109,134,.7)", color: "#ffd9df",
              padding: "8px 12px", borderRadius: 12, fontSize: 13,
            }}
          >
            <span>{notice}</span>
            <button
              type="button"
              onClick={() => setNotice(null)}
              aria-label="Đóng thông báo"
              style={{ background: "none", border: "none", color: "#ffd9df", cursor: "pointer", fontWeight: 700 }}
            >
              <i className="fa-solid fa-xmark" aria-hidden="true" />
            </button>
          </div>
        )}
      </div>

      <form className={`ad-weapon-console ${feedback ? `is-${feedback}` : ""}`} onSubmit={(e) => e.preventDefault()}>
        <div className="ad-weapon-console__label"><i className="fa-solid fa-crosshairs" aria-hidden="true"/><span>Đáp án<strong>{match.answerLabel}</strong></span></div>
        <div className="ad-weapon-console__input" style={{'--input-power': `${Math.min(100,input.length * 8)}%`} as CSSProperties}>
          <input ref={inputRef} value={input} onChange={(e) => {
              const nextValue = e.target.value;
              if (nextValue !== "" && nextValue !== input) aimTypingBullet(nextValue);
              setInput(nextValue); soundManager.playKeyStroke();
            }} onKeyDown={(e) => {
              // Enter during IME composition selects a character, never fires.
              if (e.key === "Enter" && !e.nativeEvent.isComposing && e.nativeEvent.keyCode !== 229) {
                e.preventDefault(); handleFire();
              }
            }} placeholder={introActive ? "Chuẩn bị… địch sắp xuất hiện" : placeholder}
            aria-label={`Ô nhập đáp án — ${placeholder}`} autoFocus autoComplete="off" autoCorrect="off"
            autoCapitalize="off" spellCheck={false} lang={isJapaneseField(match.answerField) ? "ja" : "vi"}
            disabled={inputDisabled} style={{fontFamily:fontFor(match.answerField)}} />
          <span className="ad-input-power" aria-hidden="true"/>
          <span className="ad-weapon-console__feedback" role="status" aria-live="polite">{feedback === "correct" ? "正解 · Trúng mục tiêu" : feedback === "wrong" ? "Chưa khớp đáp án" : ""}</span>
        </div>
        <button type="button" className="ad-fire-button" onClick={handleFire} disabled={inputDisabled || !input.trim()} aria-label="Bắn (Enter)"><i className="fa-solid fa-location-arrow" aria-hidden="true"/><span>Bắn<kbd>Enter ↵</kbd></span></button>
      </form>

    </div>
  );
}

// ============================================================
// SCREEN: Kết quả / Debrief (09_results)
// ============================================================
function WeakWordRow({ word, match }: { word: WeakWord; match: MatchInfo }) {
  const extras = ALL_FIELDS
    .filter((f) => f !== match.questionField && f !== match.answerField)
    .map((f) => [f, fieldValue(word, f)] as const)
    .filter(([, v]) => !!v && v.trim() !== "" && v !== word.prompt && v !== word.answer);

  return (
    <li style={{
      display: "flex", alignItems: "center", justifyContent: "space-between", gap: 12,
      padding: "10px 0",
      borderBottom: "1px solid var(--ok-surface-container)",
    }}>
      <div style={{ minWidth: 0 }}>
        <div style={{ display: "flex", alignItems: "baseline", gap: 10, flexWrap: "wrap" }}>
          <span lang={isJapaneseField(match.questionField) ? "ja" : "vi"} style={{ fontSize: 20, fontWeight: 700, color: "var(--ok-primary)", fontFamily: fontFor(match.questionField), overflowWrap: "anywhere" }}>
            {word.prompt}
          </span>
          <i className="fa-solid fa-arrow-right" style={{ fontSize: 11, color: "var(--ok-on-surface-variant)" }} aria-hidden="true" />
          <span lang={isJapaneseField(match.answerField) ? "ja" : "vi"} style={{ fontSize: 15, fontWeight: 600, color: "var(--ok-on-surface)", fontFamily: fontFor(match.answerField), overflowWrap: "anywhere" }}>
            {word.answer}
          </span>
        </div>
        {extras.length > 0 && (
          <div style={{ fontSize: 12, color: "var(--ok-on-surface-variant)", marginTop: 2, display: "flex", gap: 10, flexWrap: "wrap" }}>
            {extras.map(([f, v]) => (
              <span key={f}>
                {FIELD_SHORT[f]}: <span style={{ fontFamily: fontFor(f) }} lang={isJapaneseField(f) ? "ja" : "vi"}>{v}</span>
              </span>
            ))}
          </div>
        )}
      </div>
      <div style={{ textAlign: "right", flexShrink: 0 }}>
        <div style={{ fontSize: 11, color: "var(--ok-error)", fontWeight: 600 }}>{word.note}</div>
        {word.count > 1 && <div style={{ fontSize: 10, color: "var(--ok-on-surface-variant)" }}>×{word.count} lần</div>}
      </div>
    </li>
  );
}

function ResultLineRow({ line, meId }: { line: ResultLine; meId: number }) {
  const isMe = line.userId === meId;
  return (
    <div
      style={{
        display: "flex", alignItems: "center", justifyContent: "space-between", gap: 12,
        padding: "12px 14px",
        borderRadius: 12,
        background: isMe ? "var(--ok-secondary-fixed)" : "var(--ok-surface-low)",
        border: isMe ? "1px solid var(--ok-outline-variant)" : "1px solid transparent",
      }}
    >
      <div style={{ display: "flex", alignItems: "center", gap: 12, minWidth: 0 }}>
        <span style={{ fontWeight: 800, fontSize: 14, width: 28, color: line.rank === 1 ? "#9a6400" : "var(--ok-on-surface-variant)" }}>
          #{line.rank}
        </span>
        <div style={{ minWidth: 0 }}>
          <div style={{ fontWeight: 700, fontSize: 14, color: "var(--ok-on-surface)", display: "flex", alignItems: "center", gap: 6, flexWrap: "wrap" }}>
            {line.displayName}
            {isMe && <span style={{ fontSize: 10, color: "var(--ok-primary-container)", fontWeight: 600 }}>(BẠN)</span>}
            {line.winner && <OkBadge variant="gold">Thắng</OkBadge>}
          </div>
          <div style={{ fontSize: 12, color: "var(--ok-on-surface-variant)", marginTop: 2 }}>{line.detail}</div>
        </div>
      </div>
      <span style={{ fontWeight: 700, fontSize: 15, color: "var(--ok-primary-container)", fontFamily: "monospace", flexShrink: 0 }}>
        {line.score.toLocaleString()}
      </span>
    </div>
  );
}

function ResultScreen() {
  const { match, result, aborted, score, bestCombo, wave, hits, wrongInputs, weakWords } = useAirDefenseStore(
    useShallow((s) => ({
      match: s.match,
      result: s.result,
      aborted: s.aborted,
      score: s.score,
      bestCombo: s.bestCombo,
      wave: s.wave,
      hits: s.hits,
      wrongInputs: s.wrongInputs,
      weakWords: s.weakWords,
    }))
  );
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!match) return null;
  const meId = match.meId;
  const versus = match.versus;
  const myLine = result?.lines.find((l) => l.userId === meId) ?? null;
  const finalScore = myLine ? myLine.score : score;
  const won = !!result && !result.draw && result.winnerUserId === meId;

  // Chỉ còn tiêu đề ngắn; nhãn và câu mô tả dưới tiêu đề đã bỏ cho gọn.
  const headline = aborted || !result ? "Trận đấu đã bị huỷ"
    : result.draw ? "Hoà!"
      : won ? (versus ? "Bạn thắng!" : "Rút quân an toàn")
        : versus ? "Đối thủ thắng" : "Hành tinh thất thủ";

  const stars = finalScore >= 50 ? 3 : finalScore >= 20 ? 2 : 1;
  const sortedWeak = [...weakWords].sort((a, b) => b.count - a.count);

  const onReplay = async () => {
    setBusy(true);
    setError(null);
    try {
      await replaySolo();
    } catch (e) {
      setError(errorMessage(e, "Không tạo được ván mới. Vui lòng thử lại."));
      setBusy(false);
    }
  };

  const actions = (
          <div style={{ display: "flex", flexDirection: "column", gap: 12, justifyContent: "center", minWidth: 0 }}>
            {versus ? (
              <OkBtnPrimary onClick={() => { window.location.href = backUrl(); }} style={{ width: "100%", height: 52, fontSize: 16, justifyContent: "center" }}>
                <i className="fa-solid fa-door-open" style={{ fontSize: 20 }} aria-hidden="true" />
                Về phòng
              </OkBtnPrimary>
            ) : (
              <>
                <OkBtnPrimary onClick={onReplay} disabled={busy} style={{ width: "100%", height: 52, fontSize: 16, justifyContent: "center" }}>
                  <i className="fa-solid fa-rotate-left" style={{ fontSize: 20 }} aria-hidden="true" />
                  {busy ? "Đang tạo ván mới…" : "Chơi lại"}
                </OkBtnPrimary>
                <OkBtnSecondary onClick={() => { window.location.href = "/games"; }} style={{ width: "100%", height: 48, fontSize: 15, justifyContent: "center" }}>
                  <i className="fa-solid fa-house" style={{ fontSize: 18 }} aria-hidden="true" />
                  Về trang Trò chơi
                </OkBtnSecondary>
              </>
            )}
            {error && (
              <p role="alert" style={{ margin: 0, fontSize: 13, color: "var(--ok-error)", fontWeight: 600 }}>{error}</p>
            )}
          </div>
  );

  return (
    <div className="ad-app" style={{ overflowY: "auto", background: "var(--ok-background)" }}>
      <div className="ok-page" style={{ maxWidth: 960 }}>
        {/* Decorative blobs */}
        <div style={{ position: "relative" }}>
          <div style={{
            position: "absolute", top: -40, left: "50%", transform: "translateX(-50%)",
            width: 320, height: 320, borderRadius: "50%",
            background: "var(--ok-primary-fixed)", opacity: 0.35, filter: "blur(64px)",
            pointerEvents: "none",
          }} />
        </div>

        <div style={{ textAlign: "center", marginBottom: 20, position: "relative" }}>
          <h1 style={{ fontSize: 36, fontWeight: 800, color: "var(--ok-on-surface)", margin: 0, letterSpacing: "-0.02em" }}>
            {headline}
          </h1>
        </div>

        {/* Điểm + nút Chơi lại / Về trang Trò chơi cùng một khung */}
        {aborted && <OkCard style={{ marginBottom: 24 }}>{actions}</OkCard>}
        {!aborted && (
          <OkCard className="ad-result-hero" style={{ marginBottom: 24, position: "relative", overflow: "hidden" }}>
            <div style={{
              position: "absolute", inset: 0,
              background: "linear-gradient(180deg, rgba(233,221,255,.2) 0%, transparent 60%)",
              pointerEvents: "none",
            }} />
            <div style={{ position: "relative", textAlign: "center" }}>
              <div style={{ fontSize: 10, fontWeight: 700, letterSpacing: "0.12em", textTransform: "uppercase", color: "var(--ok-secondary)", marginBottom: 4 }}>
                Tổng điểm chiến dịch
              </div>
              <div style={{
                fontSize: 64, fontWeight: 800, lineHeight: 1,
                color: "var(--ok-primary-container)", letterSpacing: "-0.04em",
                marginBottom: 8,
              }}>
                {finalScore.toLocaleString()}
              </div>
              <div style={{ fontSize: 14, fontWeight: 600, color: "var(--ok-secondary)", marginBottom: 12 }}>PTS</div>
              <div style={{ fontSize: 26, letterSpacing: 4 }} aria-label={`${stars} trên 3 sao`}>
                {[1, 2, 3].map((s) => (
                  <i key={s} className="fa-solid fa-star" style={{ color: "var(--ok-tertiary-fixed-dim)", opacity: s <= stars ? 1 : 0.2 }} aria-hidden="true" />
                ))}
              </div>
            </div>
            <div style={{ position: "relative" }}>{actions}</div>
          </OkCard>
        )}

        {/* Bảng kết quả từ server */}
        {result && result.lines.length > 0 && (
          <OkCard style={{ marginBottom: 24 }}>
            <h2 style={{ fontSize: 18, fontWeight: 600, margin: "0 0 14px", color: "var(--ok-on-surface)" }}>
              <i className="fa-solid fa-trophy" style={{ color: "var(--ok-tertiary-fixed-dim)", marginRight: 8 }} aria-hidden="true" />
              {versus ? "Kết quả đối kháng" : "Kết quả lượt chơi"}
            </h2>
            <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
              {result.lines.map((line) => (
                <ResultLineRow key={line.userId} line={line} meId={meId} />
              ))}
            </div>
          </OkCard>
        )}

        {/* Stats grid */}
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(170px, 1fr))", gap: 16, marginBottom: 24 }}>
          {[
            { label: "ĐIỂM SỐ", value: finalScore.toLocaleString(), icon: "fa-trophy", color: "var(--ok-primary-container)" },
            { label: "COMBO CAO NHẤT", value: `×${bestCombo}`, icon: "fa-fire", color: "var(--ok-secondary)" },
            { label: "ĐỢT ĐẠT ĐƯỢC", value: wave, icon: "fa-satellite-dish", color: "var(--ok-on-surface-variant)" },
            { label: "BẮN TRÚNG / GÕ SAI", value: `${hits} / ${wrongInputs}`, icon: "fa-crosshairs", color: "var(--ok-tertiary-container)" },
          ].map((stat) => (
            <OkCard key={stat.label} style={{ textAlign: "center", padding: "20px 16px" }}>
              <i className={`fa-solid ${stat.icon}`} style={{ fontSize: 24, color: stat.color, display: "block", marginBottom: 8 }} aria-hidden="true" />
              <div style={{ fontSize: 24, fontWeight: 800, color: stat.color, lineHeight: 1 }}>{stat.value}</div>
              <div style={{ fontSize: 10, fontWeight: 700, letterSpacing: "0.06em", textTransform: "uppercase", color: "var(--ok-on-surface-variant)", marginTop: 4 }}>{stat.label}</div>
            </OkCard>
          ))}
        </div>

        {/* Từ cần ôn: rộng bằng các khung phía trên */}
        <div style={{ paddingBottom: 32 }}>
          <OkCard>
            <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 12 }}>
              <h2 style={{ fontSize: 18, fontWeight: 600, color: "var(--ok-on-surface)", margin: 0 }}>Từ vựng cần ôn luyện</h2>
              <OkBadge variant={weakWords.length > 0 ? "error" : "success"}>{weakWords.length} từ</OkBadge>
            </div>
            <p style={{ fontSize: 12, color: "var(--ok-on-surface-variant)", margin: "0 0 8px" }}>
              Hỏi: {match.questionLabel} <i className="fa-solid fa-arrow-right" style={{ fontSize: 10 }} aria-hidden="true" /> Đáp: {match.answerLabel}
            </p>
            <div style={{ maxHeight: 300, overflowY: "auto" }}>
              {sortedWeak.length > 0 ? (
                <ul style={{ listStyle: "none", margin: 0, padding: 0 }}>
                  {sortedWeak.map((w) => (
                    <WeakWordRow key={w.wordId} word={w} match={match} />
                  ))}
                </ul>
              ) : (
                <div style={{ textAlign: "center", padding: "30px 0", color: "var(--ok-on-surface-variant)", fontSize: 14 }}>
                  <i className="fa-solid fa-circle-check" style={{ color: "var(--ok-success)", marginRight: 6 }} aria-hidden="true" />
                  Tuyệt vời! Không có từ vựng nào bị lỡ phản xạ.
                </div>
              )}
            </div>
          </OkCard>

        </div>
      </div>
    </div>
  );
}

// ============================================================
// Màn chờ / lỗi
// ============================================================
function LoadingScreen({ versus }: { versus: boolean }) {
  return (
    <div className="ad-app battle-shell" style={{ display: "flex", alignItems: "center", justifyContent: "center", background: "var(--battle-bg)" }}>
      <div role="status" aria-live="polite" style={{ textAlign: "center" }}>
        <p style={{ fontFamily: "monospace", fontSize: 12, color: "var(--battle-cyan)", letterSpacing: "0.4em", textTransform: "uppercase", margin: 0 }}>
          QUÉT DỮ LIỆU HỆ THỐNG
        </p>
        <h1 style={{ fontFamily: "Inter", fontSize: 28, fontWeight: 800, color: "#fff", margin: "8px 0 0" }}>
          {versus ? "Đang kết nối trận đối kháng…" : "Đang chuẩn bị trận đấu…"}
        </h1>
      </div>
    </div>
  );
}

function ErrorScreen({ message }: { message: string }) {
  return (
    <div className="ad-app" style={{ display: "flex", alignItems: "center", justifyContent: "center", background: "var(--ok-background)", padding: 16 }}>
      <OkCard style={{ maxWidth: 520, textAlign: "center" }}>
        <i className="fa-solid fa-circle-exclamation" style={{ fontSize: 40, color: "var(--ok-error)" }} aria-hidden="true" />
        <h1 style={{ fontSize: 22, fontWeight: 700, margin: "8px 0", color: "var(--ok-on-surface)" }}>Không vào được trận Bắn máy bay</h1>
        <p role="alert" style={{ fontSize: 14, color: "var(--ok-on-surface-variant)", margin: "0 0 20px" }}>{message}</p>
        <div style={{ display: "flex", gap: 12, justifyContent: "center", flexWrap: "wrap" }}>
          <OkBtnPrimary onClick={() => window.location.reload()}>Thử lại</OkBtnPrimary>
          <OkBtnGhost onClick={() => { window.location.href = backUrl(); }}>Quay lại</OkBtnGhost>
        </div>
      </OkCard>
    </div>
  );
}

// ============================================================
// ROOT APP — chỉ có chế độ tích hợp JLAS Arena
// ============================================================
export default function App({ host }: { host: HostConfig }) {
  const [available, setAvailable] = useState(() => getArena().canPlayGame('AIR_DEFENSE'));
  useEffect(() => {
    const arena = getArena();
    const update = () => setAvailable(arena.canPlayGame('AIR_DEFENSE'));
    arena.phoneMedia.addEventListener('change', update);
    return () => arena.phoneMedia.removeEventListener('change', update);
  }, []);
  if (!available) return (
    <main className="ad-device-notice">
      <i className="fa-solid fa-desktop" aria-hidden="true" />
      <h1>Chơi Bắn máy bay trên máy tính</h1>
      <p>Game cần bàn phím để gõ đáp án. Bạn hãy mở bằng máy tính để chơi.</p>
      <a href="/games">Về trang Trò chơi</a>
    </main>
  );
  return <AirDefenseApp host={host} />;
}

function AirDefenseApp({ host }: { host: HostConfig }) {
  const { bootStatus, bootError, screen, outcome, isSettingsOpen } = useAirDefenseStore(
    useShallow((s) => ({
      bootStatus: s.bootStatus,
      bootError: s.bootError,
      screen: s.screen,
      outcome: s.outcome,
      isSettingsOpen: s.isSettingsOpen,
    }))
  );

  useEffect(() => {
    if (!host.sessionId) {
      useAirDefenseStore.getState().setBootError("Không tìm thấy trận đấu. Hãy mở lại từ trang Trò chơi.");
      return;
    }
    return startMatchController(host);
  }, [host]);

  if (bootStatus === "error") {
    return <ErrorScreen message={bootError || "Có lỗi xảy ra, vui lòng thử lại."} />;
  }
  if (bootStatus === "loading") {
    return <LoadingScreen versus={host.playMode === "VERSUS"} />;
  }
  if (bootStatus === "setup") {
    return <AirSetup />;
  }
  if (screen === "debrief") {
    return <ResultScreen />;
  }

  return (
    <div className="ad-app" style={{ overflow: "hidden", display: "flex", flexDirection: "column", background: "var(--battle-bg)" }}>
      <main style={{ flex: 1, minHeight: 0, overflow: "hidden", padding: "16px 20px", display: "flex", flexDirection: "column" }}>
        <Battle />
      </main>
      {screen === "augment" && outcome === "playing" && <AugmentDraft />}
      {isSettingsOpen && <MatchMenu />}
    </div>
  );
}
