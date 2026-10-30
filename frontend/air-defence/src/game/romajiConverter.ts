// ============================================================================
// ROMAJI → HIRAGANA CHO AIR DEFENCE
// Bản TypeScript giữ đúng từng luật của GameAnswerMatcher.romajiToHiragana (server Java) để client bắn
// laser ngay khi gõ đúng, còn server kiểm lại cho kết quả giống hệt: Hepburn, Kunrei, kiểu gõ bàn phím
// (nn, n', xtu, ltu…), phụ âm kép → っ, "-" → ー. Ký tự không nhận ra được giữ nguyên.
// ============================================================================

const ROMAJI_ROWS: ReadonlyArray<readonly [string, string]> = [
  ["a", "あ"], ["i", "い"], ["u", "う"], ["e", "え"], ["o", "お"],
  ["ka", "か"], ["ki", "き"], ["ku", "く"], ["ke", "け"], ["ko", "こ"],
  ["kya", "きゃ"], ["kyu", "きゅ"], ["kyo", "きょ"],
  ["sa", "さ"], ["shi", "し"], ["si", "し"], ["su", "す"], ["se", "せ"], ["so", "そ"],
  ["sha", "しゃ"], ["shu", "しゅ"], ["sho", "しょ"], ["she", "しぇ"],
  ["sya", "しゃ"], ["syu", "しゅ"], ["syo", "しょ"],
  ["ta", "た"], ["chi", "ち"], ["ti", "ち"], ["tsu", "つ"], ["tu", "つ"], ["te", "て"], ["to", "と"],
  ["cha", "ちゃ"], ["chu", "ちゅ"], ["cho", "ちょ"], ["che", "ちぇ"],
  ["tya", "ちゃ"], ["tyu", "ちゅ"], ["tyo", "ちょ"], ["cya", "ちゃ"], ["cyu", "ちゅ"], ["cyo", "ちょ"],
  ["na", "な"], ["ni", "に"], ["nu", "ぬ"], ["ne", "ね"], ["no", "の"],
  ["nya", "にゃ"], ["nyu", "にゅ"], ["nyo", "にょ"],
  ["ha", "は"], ["hi", "ひ"], ["fu", "ふ"], ["hu", "ふ"], ["he", "へ"], ["ho", "ほ"],
  ["hya", "ひゃ"], ["hyu", "ひゅ"], ["hyo", "ひょ"],
  ["fa", "ふぁ"], ["fi", "ふぃ"], ["fe", "ふぇ"], ["fo", "ふぉ"],
  ["ma", "ま"], ["mi", "み"], ["mu", "む"], ["me", "め"], ["mo", "も"],
  ["mya", "みゃ"], ["myu", "みゅ"], ["myo", "みょ"],
  ["ya", "や"], ["yu", "ゆ"], ["yo", "よ"],
  ["ra", "ら"], ["ri", "り"], ["ru", "る"], ["re", "れ"], ["ro", "ろ"],
  ["rya", "りゃ"], ["ryu", "りゅ"], ["ryo", "りょ"],
  ["la", "ら"], ["li", "り"], ["lu", "る"], ["le", "れ"], ["lo", "ろ"],
  ["wa", "わ"], ["wo", "を"], ["wi", "うぃ"], ["we", "うぇ"],
  ["ga", "が"], ["gi", "ぎ"], ["gu", "ぐ"], ["ge", "げ"], ["go", "ご"],
  ["gya", "ぎゃ"], ["gyu", "ぎゅ"], ["gyo", "ぎょ"],
  ["za", "ざ"], ["ji", "じ"], ["zi", "じ"], ["zu", "ず"], ["ze", "ぜ"], ["zo", "ぞ"],
  ["ja", "じゃ"], ["ju", "じゅ"], ["jo", "じょ"], ["je", "じぇ"],
  ["jya", "じゃ"], ["jyu", "じゅ"], ["jyo", "じょ"], ["zya", "じゃ"], ["zyu", "じゅ"], ["zyo", "じょ"],
  ["da", "だ"], ["di", "ぢ"], ["du", "づ"], ["dzu", "づ"], ["de", "で"], ["do", "ど"],
  ["dya", "ぢゃ"], ["dyu", "ぢゅ"], ["dyo", "ぢょ"],
  ["ba", "ば"], ["bi", "び"], ["bu", "ぶ"], ["be", "べ"], ["bo", "ぼ"],
  ["bya", "びゃ"], ["byu", "びゅ"], ["byo", "びょ"],
  ["pa", "ぱ"], ["pi", "ぴ"], ["pu", "ぷ"], ["pe", "ぺ"], ["po", "ぽ"],
  ["pya", "ぴゃ"], ["pyu", "ぴゅ"], ["pyo", "ぴょ"],
  ["va", "ゔぁ"], ["vi", "ゔぃ"], ["vu", "ゔ"], ["ve", "ゔぇ"], ["vo", "ゔぉ"],
  ["xa", "ぁ"], ["xi", "ぃ"], ["xu", "ぅ"], ["xe", "ぇ"], ["xo", "ぉ"],
  ["xya", "ゃ"], ["xyu", "ゅ"], ["xyo", "ょ"], ["xtu", "っ"], ["xtsu", "っ"], ["ltu", "っ"],
  ["ltsu", "っ"],
];

const ROMAJI = new Map<string, string>(ROMAJI_ROWS);

function isVowel(c: string): boolean {
  return c === "a" || c === "i" || c === "u" || c === "e" || c === "o";
}

/** Romaji (Hepburn/Kunrei/kiểu gõ bàn phím) → hiragana; ký tự không nhận ra được giữ nguyên. */
export function romajiToHiragana(romaji: string): string {
  const s = romaji.toLowerCase();
  let out = "";
  let i = 0;
  while (i < s.length) {
    const c = s[i];
    const next = i + 1 < s.length ? s[i + 1] : "";
    if (c === "-") {
      out += "ー";
      i++;
      continue;
    }
    if (c === "n") {
      if (next === "'") {
        out += "ん";
        i += 2;
        continue;
      }
      if (next === "n") {
        const after = i + 2 < s.length ? s[i + 2] : "";
        // "nni" = ん + に; "nn" đứng trước phụ âm/cuối từ = ん (kiểu gõ bàn phím).
        out += "ん";
        i += isVowel(after) || after === "y" ? 1 : 2;
        continue;
      }
      if (next === "" || (!isVowel(next) && next !== "y")) {
        out += "ん";
        i++;
        continue;
      }
    }
    if (c >= "a" && c <= "z" && !isVowel(c) && c === next) {
      out += "っ";
      i++;
      continue;
    }
    if (c === "t" && next === "c" && i + 2 < s.length && s[i + 2] === "h") {
      out += "っ";
      i++;
      continue;
    }
    let matched = false;
    for (let length = 4; length >= 1; length--) {
      if (i + length > s.length) continue;
      const kana = ROMAJI.get(s.substring(i, i + length));
      if (kana !== undefined) {
        out += kana;
        i += length;
        matched = true;
        break;
      }
    }
    if (!matched) {
      out += c;
      i++;
    }
  }
  return out;
}
