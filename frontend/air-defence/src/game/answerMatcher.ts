// ============================================================================
// CHẤM ĐÁP ÁN GÕ TAY — bản TypeScript của com.jlas.game.util.GameAnswerMatcher.
// Client dùng để bắn laser ngay lập tức; server chấm lại bằng bản Java với CÙNG luật, nên mọi thay đổi ở
// đây phải giữ khớp từng ký tự với file Java:
//  - HIRAGANA: chấp nhận kana hoặc romaji (katakana ≈ hiragana), gộp trường âm (gakko = がっこう, sense = せんせい).
//  - KANJI: so sau NFKC (gõ bằng bộ gõ tiếng Nhật), bỏ khoảng trắng/dấu câu.
//  - MEANING / HAN_VIET: tiếng Việt không phân biệt hoa thường, bỏ dấu (đ → d), dấu câu → khoảng trắng;
//    chấp nhận từng nghĩa tách bởi , ; / | và bản đã bỏ phần ghi chú trong ngoặc.
// ============================================================================

import { romajiToHiragana } from "./romajiConverter";
import type { DeckField } from "./arenaTypes";

const VARIANT_SPLIT = /[、,，;；/／|｜]/;
const PARENTHESES = /[(（[【][^)）\]】]*[)）\]】]/g;
// Java `\s` (không bật UNICODE_CHARACTER_CLASS) chỉ gồm [ \t\n\x0B\f\r].
const KANA_NOISE = /[ \t\n\x0B\f\r・･.。、,，~〜～!！?？「」『』"'‐-]/g;
const NON_WORD = /[^\p{L}\p{N}]+/gu;
const MARKS = /\p{M}+/gu;
const LATIN = /[a-z]/;
const TRAILING_LATIN = /[a-z']+$/;

const VOWEL_OF: Map<string, string> = (() => {
  const map = new Map<string, string>();
  const rows = [
    "aあかさたなはまやらわがざだばぱぁゃ",
    "iいきしちにひみりぎじぢびぴぃ",
    "uうくすつぬふむゆるぐずづぶぷゔぅゅ",
    "eえけせてねへめれげぜでべぺぇ",
    "oおこそとのほもよろをごぞどぼぽぉょ",
  ];
  for (const row of rows) {
    const vowel = row[0];
    for (let i = 1; i < row.length; i++) map.set(row[i], vowel);
  }
  return map;
})();

/** String.trim() của Java: chỉ cắt các ký tự <= U+0020 ở hai đầu. */
function javaTrim(value: string): string {
  let start = 0;
  let end = value.length;
  while (start < end && value.charCodeAt(start) <= 0x20) start++;
  while (end > start && value.charCodeAt(end - 1) <= 0x20) end--;
  return value.substring(start, end);
}

function isBlank(value: string): boolean {
  return value.trim() === "";
}

/** NFKC + chữ thường + katakana → hiragana, bỏ khoảng trắng và dấu câu. Kanji giữ nguyên. */
export function kanaKey(value: string): string {
  const normalized = value.normalize("NFKC").toLowerCase();
  let out = "";
  for (let i = 0; i < normalized.length; i++) {
    const code = normalized.charCodeAt(i);
    out += code >= 0x30a1 && code <= 0x30f6 ? String.fromCharCode(code - 0x60) : normalized[i];
  }
  return out.replace(KANA_NOISE, "");
}

function extendsVowel(previous: string, current: string | null): boolean {
  if (current === null) return false;
  return previous === current || (previous === "o" && current === "u") || (previous === "e" && current === "i");
}

/** Gộp trường âm: がっこう ≈ がっこ, せんせい ≈ せんせ, ー bị bỏ. */
export function looseKana(kana: string): string {
  let out = "";
  let previousVowel: string | null = null;
  for (let i = 0; i < kana.length; i++) {
    const c = kana[i];
    if (c === "ー") continue;
    const vowel = VOWEL_OF.get(c) ?? null;
    const pureVowel = c === "あ" || c === "い" || c === "う" || c === "え" || c === "お";
    if (pureVowel && previousVowel !== null && extendsVowel(previousVowel, vowel)) continue;
    out += c;
    previousVowel = vowel;
  }
  return out;
}

/** Chữ thường, bỏ dấu (đ → d), bỏ dấu câu: "Con mèo!" → "con meo". */
export function vietKey(value: string): string {
  const normalized = value.normalize("NFKC").toLowerCase().replace(/đ/g, "d");
  const stripped = normalized.normalize("NFD").replace(MARKS, "");
  return javaTrim(stripped.replace(NON_WORD, " "));
}

/** Cả chuỗi, từng nghĩa tách bởi , ; / | và bản đã bỏ phần trong ngoặc. */
export function variants(expected: string): string[] {
  const result = new Set<string>();
  const withoutNotes = javaTrim(expected.replace(PARENTHESES, " "));
  result.add(javaTrim(expected));
  result.add(withoutNotes);
  for (const source of [expected, withoutNotes]) {
    for (const part of source.split(VARIANT_SPLIT)) {
      const trimmed = javaTrim(part);
      if (trimmed !== "") {
        result.add(trimmed);
        result.add(javaTrim(trimmed.replace(PARENTHESES, " ")));
      }
    }
  }
  result.delete("");
  return Array.from(result);
}

function normalizeKanaInput(raw: string, allowRomaji: boolean): string {
  let input = javaTrim(raw.normalize("NFKC").toLowerCase());
  if (allowRomaji && LATIN.test(input)) {
    input = romajiToHiragana(input);
  }
  return input;
}

function matchesKana(expected: string, raw: string, allowRomaji: boolean): boolean {
  const inputKey = kanaKey(normalizeKanaInput(raw, allowRomaji));
  if (inputKey === "") return false;
  const inputLoose = looseKana(inputKey);
  for (const variant of variants(expected)) {
    const key = kanaKey(variant);
    if (key === "") continue;
    if (key === inputKey || looseKana(key) === inputLoose) return true;
  }
  return false;
}

function matchesVietnamese(expected: string, raw: string): boolean {
  const input = vietKey(raw);
  if (input === "") return false;
  for (const variant of variants(expected)) {
    if (input === vietKey(variant)) return true;
  }
  return false;
}

/** Luật chấm chính thức (giống hệt server). */
export function matchesAnswer(field: DeckField, expected: string | null | undefined, raw: string | null | undefined): boolean {
  if (expected == null || raw == null || isBlank(raw)) return false;
  switch (field) {
    case "KANJI":
      return matchesKana(expected, raw, false);
    case "HIRAGANA":
      return matchesKana(expected, raw, true);
    case "MEANING":
    case "HAN_VIET":
      return matchesVietnamese(expected, raw);
    default:
      return false;
  }
}

// ----------------------------------------------------------------------------
// So khớp dở dang (chỉ để bắn đạn trang trí khi đang gõ; không ảnh hưởng điểm)
// ----------------------------------------------------------------------------

const ROMAJI_CONTINUATIONS = ["a", "i", "u", "e", "o", "ya", "yu", "yo"];

function kanaPrefixOf(target: string, prefix: string): boolean {
  if (prefix === "") return false;
  const key = kanaKey(target);
  return key.startsWith(prefix) || looseKana(key).startsWith(looseKana(prefix));
}

function partialKana(expectedList: string[], raw: string, allowRomaji: boolean): boolean {
  const input = javaTrim(raw.normalize("NFKC").toLowerCase());
  if (input === "") return false;
  const candidates: string[] = [];
  if (allowRomaji && LATIN.test(input)) {
    // Phần romaji cuối có thể chưa gõ xong ("gak" → がk): thử bỏ đuôi la-tinh và thử nối thêm nguyên âm.
    const converted = romajiToHiragana(input);
    candidates.push(kanaKey(converted.replace(TRAILING_LATIN, "")));
    for (const ext of ROMAJI_CONTINUATIONS) {
      const extended = romajiToHiragana(input + ext).replace(TRAILING_LATIN, "");
      candidates.push(kanaKey(extended));
    }
  } else {
    candidates.push(kanaKey(input));
  }
  for (const expected of expectedList) {
    for (const variant of variants(expected)) {
      for (const candidate of candidates) {
        if (candidate !== "" && kanaPrefixOf(variant, candidate)) return true;
      }
    }
  }
  return false;
}

/**
 * Đầu vào đang gõ có thể là phần đầu của đáp án không? Dùng để ngắm đạn trang trí vào đúng mục tiêu.
 * Với đáp án Kanji, bộ gõ tiếng Nhật hiện kana trước khi chuyển chữ nên so thêm với cách đọc (nếu có).
 */
export function isPartialAnswer(field: DeckField, expected: string, raw: string, reading?: string | null): boolean {
  if (isBlank(raw)) return false;
  switch (field) {
    case "HIRAGANA":
      return partialKana([expected], raw, true);
    case "KANJI":
      return partialKana(reading ? [expected, reading] : [expected], raw, !!reading);
    case "MEANING":
    case "HAN_VIET": {
      const input = vietKey(raw);
      if (input === "") return false;
      return variants(expected).some((variant) => vietKey(variant).startsWith(input));
    }
    default:
      return false;
  }
}
