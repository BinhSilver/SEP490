/** Màn hình trong trận: chiến đấu (vô tận / đối kháng), tổng kết. */
export type Screen = "endless" | "pvp" | "debrief";

export type GameMode = "endless" | "pvp";

export interface TargetWord {
  /** id duy nhất của phi thuyền trên màn hình (một từ có thể xuất hiện nhiều lần). */
  id: string;
  /** id từ trong dãy chung của trận ("w12") — gửi kèm /kill để server chấm lại. */
  wordId: string;
  /** Nội dung trường câu hỏi, hiện trên thân địch. */
  prompt: string;
  /** Nội dung trường đáp án, thứ người chơi phải gõ. */
  answer: string;
  kanji: string | null;
  reading: string | null;
  meaning: string | null;
  hanViet: string | null;
  posX: number;
  posY: number;
  speed: number;
  type: "MONSTER_NORMAL" | "MONSTER_FAST" | "SPACE_MINE" | "MINI_BOSS";
  maxHp?: number;
  currentHp?: number;
  isDead?: boolean;
  /** Mini-boss do đối thủ gửi sang (đòn combo trong trận đối kháng). */
  fromAttack?: boolean;
  /** Tên người gửi mini-boss (chỉ có khi fromAttack). */
  fromName?: string;
}

export type LootItemType = "CREDIT_CRYSTAL" | "REPAIR_PACK" | "HYPER_ORB";

export interface LootItem {
  id: string;
  type: LootItemType;
  x: number;      // % of canvas width
  y: number;      // % of canvas height
  vx: number;     // horizontal velocity
  vy: number;     // vertical velocity
  value: number;  // amount of credit / hp / charge
  collected: boolean;
  spawnTime: number;
}

export interface FloatingText {
  id: string;
  text: string;
  color: string;
  x: number;
  y: number;
  opacity: number;
  life: number;
}

/** Từ cần ôn: người chơi gõ sai hoặc để địch mang từ đó chạm phòng tuyến. */
export interface WeakWord {
  wordId: string;
  prompt: string;
  answer: string;
  kanji: string | null;
  reading: string | null;
  meaning: string | null;
  hanViet: string | null;
  note: string;
  count: number;
}

export interface AudioSettings {
  masterEnabled: boolean;
  masterVolume: number;     // 0 -> 100
  bgmEnabled: boolean;
  bgmVolume: number;        // 0 -> 100
  sfxEnabled: boolean;
  sfxVolume: number;        // 0 -> 100
  keystrokeEnabled: boolean;
}
