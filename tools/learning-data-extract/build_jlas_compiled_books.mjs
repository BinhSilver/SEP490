// [JLAS-W0108] Owner D3-04 (2026-10-24): one J-LAS compiled book per JLPT level, N5…N1. Every book of a level in the repo
// is copied and merged per skill, deduplicated (a word / Kanji / pattern lives at the lowest level it appears), ordered easy
// → hard by its earliest relative position in its source books, and split evenly into chapters that each carry every skill.
// Books are provenance only (not shown). Each item keeps a numeric slot across rebuilds, so database ids stay stable.
//   node tools/learning-data-extract/build_jlas_compiled_books.mjs . [--check] [--dry-run]
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readLearningContent, writeLearningSections } from './learning_content_store.mjs';
import { grammarKey, normalizeJa } from './build_canonical_catalog.mjs';

export const LEVELS = ['N5', 'N4', 'N3', 'N2', 'N1'];
/** Chapters per level: the J-LAS chapter counts the team already uses (Sekai 25 + 25, Tiếng Nhật N3/N2/N1). */
export const CHAPTERS = Object.freeze({ N5: 25, N4: 25, N3: 27, N2: 30, N1: 52 });
/** Dialogues come from the chapter of the same number in these books (owned copies where Learning has one). */
export const DIALOGUE_BOOKS = Object.freeze({ N5: 1, N4: 4000002, N3: 4 });
/** Chapter books whose grammar points (with examples) are copied: textbook id → level. */
const GRAMMAR_BOOKS = Object.freeze({ 1: 'N5', 2: 'N4', 4: 'N3', 5: 'N2', 6: 'N1' });
/** LearningWorkloadPolicy / ComprehensionWorkloadPolicy: study + quiz minutes per member. */
export const MINUTES = Object.freeze({ vocabulary: 0.8, kanji: 1.05, grammar: 1.3, reading_question: 3, listening_clip: 15,
  dialogue_line: 0.5 });
const DIALOGUE_LINES = Object.freeze({ N5: 400, N4: 400, N3: 137, N2: 0, N1: 0 });
const VNJPCLUB = ['tu_vung', 'kanji', 'ngu_phap', 'vi_du'];
const KANJI = /[㐀-䶿一-鿿々]/gu;
const sha = (value) => createHash('sha256').update(value).digest('hex');
const shortId = (value) => sha(value).slice(0, 10);
const clean = (value) => String(value ?? '').replace(/\s+/g, ' ').trim();
const rank = (level) => LEVELS.indexOf(level);
const pushUnique = (list, value) => { const v = clean(value); if (v && !list.includes(v)) list.push(v); };
/** Dedupe identity: spec §4 normalization with spacing removed (ありがとう ございます = ありがとうございます). */
const vocabKey = (expression, reading) => `${normalizeJa(expression).replace(/\s+/g, '')}|${normalizeJa(reading || expression).replace(/\s+/g, '')}`;
/** Dictionary identity exactly as `vocabulary_entry.normalized_*` (spec §4): katakana → hiragana over NFKC, spacing kept. */
export const dbNormalize = (text) => normalizeJa(clean(text));

const GROUP = /\s*[（(]\s*(III|II|I|3|2|1)\s*[)）]\s*$/;
const GROUP_LABEL = { I: 'Động từ nhóm I', 1: 'Động từ nhóm I', II: 'Động từ nhóm II', 2: 'Động từ nhóm II', III: 'Động từ nhóm III', 3: 'Động từ nhóm III' };
/**
 * One source vocabulary row → the words it really names. "手伝う（I）" keeps its verb group as the word type, "起きる／起こる"
 * with "おきる／おこる" is two words, and a quoted or full sentence ("B「…」", "…です。") is not vocabulary.
 */
export function cleanVocabulary(expression, reading) {
  let e = clean(expression); let r = clean(reading || expression);
  if (!e || /[「」『』▭•]/.test(e) || /[。？！?!]$/.test(e)) return [];
  let wordType = null;
  const group = GROUP.exec(e);
  if (group) { wordType = GROUP_LABEL[group[1]]; e = e.replace(GROUP, ''); r = r.replace(GROUP, ''); }
  const es = e.split(/\s*[／/]\s*/).filter(Boolean);
  const rs = r.split(/\s*[／/]\s*/).filter(Boolean);
  // A slash inside brackets ("時計（をする／はずす）") is part of one entry, never two words.
  const balanced = (x) => (x.match(/[（(]/g) ?? []).length === (x.match(/[）)]/g) ?? []).length;
  const pairs = es.length > 1 && es.length === rs.length && [...es, ...rs].every(balanced) ? es.map((x, i) => [x, rs[i]]) : [[e, r]];
  return pairs.map(([x, y]) => ({ expression: x, reading: y, word_type: wordType }));
}

/** Source patterns with no example anywhere: J-LAS writes one (kept in the data as a supplement, not shown as such). */
export const SUPPLEMENTED_GRAMMAR_EXAMPLES = Object.freeze({
  受身形: '先生に褒められました。\nTôi đã được thầy giáo khen.',
  '～によって (B)': '台風によって、電車が止まりました。\nDo bão nên tàu điện đã dừng chạy.',
  '～によって (C)': 'この絵はピカソによって描かれました。\nBức tranh này do Picasso vẽ.',
});
/**
 * D3-08 fix table for source grammar rows with no meaning anywhere (grammar_point.meaning is required): J-LAS writes the
 * meaning from the book's own note, and where the source pattern is a sentence/heading or its example pair is misaligned
 * or mistranslated, the display pattern / example is corrected too. The id still comes from the source pattern.
 */
export const SUPPLEMENTED_GRAMMAR = Object.freeze({
  '～意志形＋と思う': { meaning: 'Định làm ~: người nói nêu ý định của bản thân (ngôi thứ ba dùng ～と思っている).' },
  '～は・・・が＋形容詞/状態を表わす動詞': { meaning: 'Nói về tính chất, trạng thái: 「～」 là toàn thể, 「・・・」 là một bộ phận của nó.' },
  '～てもいいです／～てはいけません': { meaning: 'Làm ~ cũng được (cho phép, nhượng bộ) / Không được làm ~ (cấm đoán).',
    example: 'ここに座ってもいいですか。\nTôi ngồi ở đây có được không?\n花ちゃん、一人で川に行ってはいけないよ。\nBé Hana, không được ra sông một mình đâu đấy.' },
  'ドアが開いているよ。': { pattern: '自動詞＋ている（結果の状態）', meaning: 'Trạng thái kết quả còn lại sau một thay đổi (cửa đang mở, máy đang hỏng).',
    example: 'ドアが開いているよ。\nCửa đang mở đấy.\nコンピューターがこわれているから、仕事ができない。\nVì máy tính đang bị hỏng nên tôi không thể làm việc được.' },
  '～てみます': { meaning: 'Thử làm ~ (để xem thế nào); cũng dùng để gợi ý nhẹ nhàng.' },
  'Chuyển động ～ていく・～てくる': { pattern: '～ていく・～てくる（移動）',
    meaning: '～てくる: di chuyển lại gần người nói, hoặc đi làm gì rồi quay lại; ～ていく: di chuyển ra xa người nói, hoặc làm gì rồi mới đi.' },
  '～まだもう': { pattern: 'もう／まだ', meaning: 'もう: đã (đạt tới trạng thái), thêm nữa; まだ: vẫn chưa, vẫn còn.',
    example: 'トム「もう昼ご飯を食べた？」サラ「ううん、まだ食べていない。」\nTom: "Bạn ăn trưa rồi à?" Sarah: "Chưa, mình vẫn chưa ăn."' },
  'Các loại từ (品詞)': { pattern: '品詞の転換（動詞→名詞、形容詞＋さ）',
    meaning: 'Chuyển loại từ: động từ dùng như danh từ (料理、帰り、山登り); tính từ + さ thành danh từ (高さ、便利さ).',
    example: '山登りは楽しいですよ。\nLeo núi vui lắm đấy.\n東京スカイツリーの高さは643メートルです。\nChiều cao của tháp Tokyo Skytree là 643 mét.' },
  '～ておく／～てある': { meaning: '～ておく: làm sẵn, chuẩn bị trước; ～てある: (ai đó đã làm nên) kết quả vẫn còn đó.',
    example: '書いておく／書いてある\nViết sẵn / Đã được viết sẵn\n他の人のかさと間違えないように、ここに名前を書いておこう。\nHãy viết sẵn tên vào chỗ này để không nhầm với ô của người khác.' },
  '～ように／～ために（目的）': { meaning: 'Để ~ (mục đích): ように đi với động từ khả năng/không ý chí, ために đi với động từ ý chí.' },
  '～ような／～ように（例示）': { meaning: 'Giống như ~, như là ~ (nêu ví dụ).' },
  '～べきだ': { meaning: 'Nên / phải làm ~ (điều đương nhiên nên làm).',
    example: '買うべきだ\nNên mua\n約束したことは、しっかりやるべきだ。\nĐã hứa rồi thì phải làm cho đến nơi đến chốn.' },
  '～ば（いい）と思う': { meaning: 'Ước gì ~, mong là ~ (nêu hy vọng).' },
  '～（さ）せられる／～される': { meaning: 'Bị bắt làm ~ (sai khiến bị động): cảm giác khó chịu khi phải làm theo ý người khác.' },
  '～ほど．．．はない': { meaning: 'Không có ~ nào … bằng ~ (= ~ là … nhất).' },
  '～ばかりか．．．も': { meaning: 'Không chỉ ~ mà còn …' },
  '～の（主格）': { meaning: 'Trong mệnh đề bổ nghĩa cho danh từ, が của chủ ngữ có thể đổi thành の (父が作った料理 → 父の作った料理).',
    example: '不動産屋のすすめる部屋\nCăn phòng mà văn phòng bất động sản giới thiệu\n父の作った料理はおいしい。\nMón ăn bố tôi nấu rất ngon.' },
});
/** Source "patterns" that do not name one pattern ("て・・た"): held for the owner, not put in a chapter. */
export const HELD_GRAMMAR = Object.freeze({ 'て・・た': 'nhãn không rõ là mẫu nào (Shinkanzen N4), chờ owner rà' });

/** Relative position (0–1) of every lesson of a VNJPClub book, by the number in its lesson code. */
export function lessonPositions(rows) {
  const byBook = new Map();
  for (const row of rows) {
    if (!row.sach || !row.lesson_ma) continue;
    if (!byBook.has(row.sach)) byBook.set(row.sach, new Set());
    byBook.get(row.sach).add(row.lesson_ma);
  }
  const positions = new Map();
  for (const [book, codes] of byBook) {
    const ordered = [...codes].sort((a, b) => Number(/(\d+)$/.exec(a)?.[1] ?? 0) - Number(/(\d+)$/.exec(b)?.[1] ?? 0) || a.localeCompare(b));
    ordered.forEach((code, index) => positions.set(`${book}|${code}`, (index + 0.5) / ordered.length));
  }
  return positions;
}

/** Splits items (already easy → hard) into `count` contiguous, near-equal slices; slice i is chapter i + 1. */
export function deal(items, count) {
  return Array.from({ length: count }, (_, i) => items.slice(Math.floor((i * items.length) / count), Math.floor(((i + 1) * items.length) / count)));
}

/**
 * D3-07 (2026-10-26): like {@link deal}, but an item already placed in `previous` chapters keeps its chapter and its
 * order there, so the "Pk" codes of question copies already in a learner's path never shift. A new item joins, at the
 * end, the chapter of the placed item just before it (easy → hard); with nothing placed yet it is a plain deal.
 */
export function dealStable(items, count, previousChapters, field) {
  const before = new Map();
  (previousChapters ?? []).forEach((chapter, index) => (chapter[field] ?? []).forEach((id, order) => before.set(id, { index, order })));
  if (!items.some((x) => before.has(x.id))) return deal(items, count);
  const out = Array.from({ length: count }, () => []);
  let last = 0;
  const fresh = [];
  for (const item of items) {
    const at = before.get(item.id);
    if (at && at.index < count) { out[at.index].push({ item, order: at.order }); last = at.index; }
    else fresh.push({ item, index: last });
  }
  for (const list of out) list.sort((a, b) => a.order - b.order);
  for (const { item, index } of fresh) out[index].push({ item });
  return out.map((list) => list.map((x) => x.item));
}

function sourceRows(data, vnjp) {
  const rows = { vocabulary: [], grammar: [] };
  for (const lesson of data.sections.jlpt_sekai_n5_n4.lessons) {
    const first = lesson.level === 'N5' ? 1 : 26;
    const position = (Number(lesson.source_lesson_number) - first + 0.5) / 25;
    for (const w of lesson.content.tu ?? []) {
      rows.vocabulary.push({ level: lesson.level, book: `Sekai ${lesson.level}`, position, expression: w.kanji || w.kana,
        reading: w.kana, meaning_vi: w.vi, example_ja: w.vdJa, example_vi: w.vdVi, topic: w.nhom ?? null });
    }
  }
  for (const level of ['N3', 'N2', 'N1']) {
    const snapshot = data.sections[`jlas_${level.toLowerCase()}_backbone_snapshot`];
    if (!snapshot) throw new Error(`Missing jlas_${level.toLowerCase()}_backbone_snapshot`);
    const max = Math.max(...snapshot.vocabulary.map((w) => w.chapter));
    for (const w of snapshot.vocabulary) rows.vocabulary.push({ level, book: `Tiếng Nhật ${level}`, position: (w.chapter - 0.5) / max, ...w });
  }
  const grammarBooks = data.sections.jlas_book_grammar_snapshot;
  if (!grammarBooks) throw new Error('Missing jlas_book_grammar_snapshot');
  for (const g of grammarBooks.rows) {
    const level = GRAMMAR_BOOKS[g.textbook];
    const chapters = grammarBooks.books[g.textbook].chapters;
    rows.grammar.push({ level, book: level === 'N5' || level === 'N4' ? `Sekai ${level}` : `Tiếng Nhật ${level}`,
      position: (g.chapter - 0.5) / chapters, pattern: g.pattern, meaning: g.meaning, example: g.example, note: g.note });
  }
  const vocabPos = lessonPositions(vnjp.tu_vung);
  for (const r of vnjp.tu_vung) {
    if (!LEVELS.includes(r.cap)) continue;
    rows.vocabulary.push({ level: r.cap, book: r.sach, position: vocabPos.get(`${r.sach}|${r.lesson_ma}`) ?? 1,
      expression: r.tu, reading: r.cach_doc || r.tu, meaning_vi: r.nghia_vi, word_type: r.tu_loai || null });
  }
  const examples = new Map();
  for (const e of vnjp.vi_du) {
    if (!e.ma_cha || !e.cau_ja) continue;
    if (!examples.has(e.ma_cha)) examples.set(e.ma_cha, []);
    examples.get(e.ma_cha).push([clean(e.cau_ja), clean(e.dich_vi)].filter(Boolean).join('\n'));
  }
  const grammarPos = lessonPositions(vnjp.ngu_phap);
  for (const r of vnjp.ngu_phap) {
    if (!LEVELS.includes(r.cap)) continue;
    // Mimikara "X 〜。→〜" rows are correct-the-mistake exercises, not patterns.
    if (/^[XＸ×]\s/.test(clean(r.mau)) || /→/.test(r.mau ?? '')) continue;
    rows.grammar.push({ level: r.cap, book: r.sach, position: grammarPos.get(`${r.sach}|${r.lesson_ma}`) ?? 1,
      pattern: r.mau || r.cau_truc, meaning: clean(r.nghia_vi), example: (examples.get(r.ma) ?? []).slice(0, 2).join('\n'),
      note: clean(r.ghi_chu) });
  }
  return rows;
}

/** Keeps every item's slot from the previous build; new items get the next free slots in their order. */
function assignSlots(items, previous) {
  const known = new Map((previous ?? []).filter((x) => Number.isInteger(x.slot)).map((x) => [x.id, x.slot]));
  let next = Math.max(0, ...known.values());
  for (const item of items) item.slot = known.get(item.id) ?? ++next;
}

/**
 * Owner 2026-10-27: typing errors in the digitization team's word list, fixed in Learning's copy only (their data is never
 * edited). Keyed by the id the misspelt word had (`JV-` + hash of its cleaned expression|reading) → the right spelling;
 * found when example sentences were written for every N3 word. Phrases and valid conjugations are left as they are.
 */
export const SOURCE_WORD_FIXES = Object.freeze({
  'JV-740627b051': ['酔っ払う', 'よっぱらう'], 'JV-b380727ef8': ['冷える', 'ひえる'], 'JV-b778393778': ['魚を焼く', 'さかなをやく'],
  'JV-d7c1435569': ['砂糖', 'さとう'], 'JV-2abba7cd65': ['旅館に泊まる', 'りょかんにとまる'], 'JV-7b89c69cfb': ['応援する', 'おうえんする'],
  'JV-83390b4f2f': ['スポーツジム', 'スポーツジム'], 'JV-9f8766ea66': ['見物（をする）', 'けんぶつ（をする）'], 'JV-2f33f84210': ['トン', 'トン'],
  'JV-7e7cc36a78': ['絵はがき', 'えはがき'], 'JV-a691a1b0c5': ['はがきを出す', 'はがきをだす'], 'JV-4b84234997': ['まったく', 'まったく'],
  'JV-ace2cdd3e0': ['印象・評価', 'いんしょう・ひょうか'], 'JV-dc547ed78b': ['陽気な', 'ようきな'],
  'JV-29ceea8933': ['気持ち（が）悪い生き物', 'きもち（が）わるいいきもの'], 'JV-ed980ca6eb': ['詳しい地図', 'くわしいちず'],
  'JV-1da0c13b50': ['鍋で煮る', 'なべでにる'], 'JV-056c25550f': ['たまに会う', 'たまにあう'],
  'JV-06b9293aff': ['普通（は）行かない', 'ふつう（は）いかない'], 'JV-368a226833': ['意外に軽い', 'いがいにかるい'],
  'JV-3b19795c31': ['普段（は）静か', 'ふだん（は）しずか'], 'JV-4ef092cf26': ['たいてい断る', 'たいていことわる'],
  'JV-cf6a8fad89': ['結構好き', 'けっこうすき'], 'JV-914fcef4c4': ['ジムに通う', 'ジムにかよう'],
  'JV-67bc41e23a': ['コンクールに出る', 'コンクールにでる'], 'JV-58444e8255': ['革のコート', 'かわのコート'],
  'JV-b8c0768320': ['ボランティア', 'ボランティア'], 'JV-5c96497892': ['シートベルトを締める', 'シートベルトをしめる'],
  'JV-e62685dcf7': ['誤りを見落とす', 'あやまりをみおとす'], 'JV-13c5810560': ['宿題を出す', 'しゅくだいをだす'],
  'JV-2c5d8162ab': ['年をとる', 'としをとる'], 'JV-1478df866a': ['交通事故が起こる', 'こうつうじこがおこる'],
  'JV-fefa62bb26': ['話題にする', 'わだいにする'], 'JV-5657148390': ['どこかの国から攻撃を受ける', 'どこかのくにからこうげきをうける'],
  'JV-ecd1b349fe': ['駅で友達を見かける', 'えきでともだちをみかける'],
  // Owner 2026-10-28: 感想する glossed "khô ráo" is 乾燥する, a word N3 already has (chapter 11) — the fix merges them.
  'JV-65dcfc4915': ['乾燥する', 'かんそうする'],
});

/** The id a SOURCE_WORD_FIXES word has once fixed (unchanged for any other id). */
export function fixedWordId(id) {
  const fix = SOURCE_WORD_FIXES[id];
  return fix ? `JV-${shortId(vocabKey(fix[0], fix[1]))}` : id;
}

/** The cleaned word with a SOURCE_WORD_FIXES spelling applied (unchanged when it is not a known typing error). */
export function fixSourceWord(word) {
  const fix = SOURCE_WORD_FIXES[`JV-${shortId(vocabKey(word.expression, word.reading))}`];
  return fix ? { ...word, expression: fix[0], reading: fix[1] } : word;
}

/** Pure: the five compiled books from the canonical sources; `previous` (level → stored book) keeps slots stable. */
export function compileBooks(data, vnjp, previous = {}) {
  const rows = sourceRows(data, vnjp);
  const vocab = new Map();
  for (const r of rows.vocabulary) {
    for (const word of cleanVocabulary(r.expression, r.reading).map(fixSourceWord)) {
      const key = vocabKey(word.expression, word.reading);
      if (!key.replace('|', '')) continue;
      let item = vocab.get(key);
      if (!item) {
        item = { id: `JV-${shortId(key)}`, key, expression: word.expression, reading: word.reading, level: r.level,
          position: r.position, meanings_vi: [], word_type: null, example_ja: null, example_vi: null, topic: null,
          variants: [], books: [] };
        vocab.set(key, item);
      }
      if (rank(r.level) < rank(item.level) || (r.level === item.level && r.position < item.position)) {
        item.level = r.level; item.position = r.position;
      }
      pushUnique(item.meanings_vi, r.meaning_vi);
      item.word_type ??= word.word_type ?? (r.word_type ? clean(r.word_type) : null);
      if (!item.example_ja && r.example_ja) { item.example_ja = clean(r.example_ja); item.example_vi = clean(r.example_vi) || null; }
      item.topic ??= r.topic ?? null;
      const variant = [dbNormalize(word.expression), dbNormalize(word.reading)];
      if (!item.variants.some((v) => v[0] === variant[0] && v[1] === variant[1])) item.variants.push(variant);
      pushUnique(item.books, r.book);
    }
  }
  // Owner 2026-10-27: a word whose sources have no example takes the checked J-LAS example (build_n3_supplement_content),
  // labelled in its translation; a source example always wins.
  const supplement = data.sections.jlas_n3_vocab_examples;
  for (const item of vocab.values()) {
    const ex = supplement?.items?.[item.id];
    if (item.example_ja || !ex) continue;
    item.example_ja = ex.example_ja;
    item.example_vi = `${ex.example_vi} · Ví dụ ${supplement.label}`;
  }
  const dic = new Map(data.sections.kanji_n5_n1.content.kanji.map((k) => [k.character, k]));
  const kanjiPos = lessonPositions(vnjp.kanji);
  const kanji = new Map();
  const touchKanji = (character, level, position, row) => {
    let item = kanji.get(character);
    if (!item) {
      const d = dic.get(character);
      item = { id: `JK-${character.codePointAt(0).toString(16).toUpperCase()}`, character, level, position,
        han_viet: d?.han_viet ?? null, readings_on: (d?.readings_on ?? []).map((x) => x.text),
        readings_kun: (d?.readings_kun ?? []).map((x) => x.text), stroke_count: d?.stroke_count ?? null, meanings_vi: [], books: [] };
      kanji.set(character, item);
    }
    if (rank(level) < rank(item.level) || (level === item.level && position < item.position)) { item.level = level; item.position = position; }
    if (row) { item.han_viet ??= clean(row.han_viet) || null; pushUnique(item.books, row.sach); }
  };
  // Candidates: Kanji a book teaches (VNJPClub Kanji lists) and Kanji inside the words; a Kanji no book uses is not added.
  // The iteration mark 々 is listed by some books but is not a Kanji to learn.
  for (const r of vnjp.kanji) if (r.kanji && r.kanji !== '々' && LEVELS.includes(r.cap)) touchKanji(r.kanji, r.cap, kanjiPos.get(`${r.sach}|${r.lesson_ma}`) ?? 1, r);
  const wordsInOrder = () => [...vocab.values()].sort((a, b) => rank(a.level) - rank(b.level) || a.position - b.position);
  for (const word of wordsInOrder()) {
    for (const character of word.expression.match(KANJI) ?? []) if (character !== '々') touchKanji(character, word.level, word.position, null);
  }
  // The official JLPT level (KANJIDIC2 / DB label) decides when it exists: an easier word may use a harder Kanji, read
  // inside that word and taught at its own level.
  for (const item of kanji.values()) {
    const level = dic.get(item.character)?.jlpt_level;
    if (LEVELS.includes(level)) item.level = level;
  }
  for (const r of vnjp.kanji) if (r.kanji && kanji.has(r.kanji)) pushUnique(kanji.get(r.kanji).meanings_vi, r.nghia_vi);
  for (const word of wordsInOrder()) {
    for (const character of word.expression.match(KANJI) ?? []) {
      const item = kanji.get(character);
      if (item && item.level === word.level && !item.first_word) { item.first_word = word.id; item.position = word.position; }
    }
  }
  const grammar = new Map();
  const heldGrammar = [];
  for (const r of rows.grammar) {
    if (HELD_GRAMMAR[clean(r.pattern)]) { heldGrammar.push({ level: r.level, pattern: clean(r.pattern), book: r.book, reason: HELD_GRAMMAR[clean(r.pattern)] }); continue; }
    const key = grammarKey(r.pattern);
    if (!key) continue;
    let item = grammar.get(key);
    if (!item) {
      item = { id: `JG-${shortId(key)}`, key, pattern: clean(r.pattern), level: r.level, position: r.position,
        meaning: null, example: null, note: null, books: [] };
      grammar.set(key, item);
    }
    if (rank(r.level) < rank(item.level) || (r.level === item.level && r.position < item.position)) { item.level = r.level; item.position = r.position; }
    item.meaning ??= clean(r.meaning) ? String(r.meaning).trim() : null;
    item.example ??= String(r.example ?? '').trim() || null;
    item.note ??= String(r.note ?? '').trim() || null;
    pushUnique(item.books, r.book);
  }
  for (const item of grammar.values()) {
    if (!item.example && SUPPLEMENTED_GRAMMAR_EXAMPLES[item.pattern]) {
      item.example = SUPPLEMENTED_GRAMMAR_EXAMPLES[item.pattern];
      item.example_supplemented = true;
    }
    const fix = item.meaning ? null : SUPPLEMENTED_GRAMMAR[item.pattern];
    if (fix) {
      item.source_pattern = item.pattern;
      Object.assign(item, fix, { meaning_supplemented: true });
    }
  }
  // F1-2 follow-up (owner 2026-10-28): an example that used a word a later chapter teaches is replaced by the checked
  // J-LAS rewrite (build_grammar_example_rewrites.mjs), labelled like every other J-LAS example.
  const rewrites = data.sections.jlas_grammar_example_rewrites;
  for (const item of grammar.values()) {
    const rewrite = rewrites?.items?.[`JG-${shortId(item.key)}`];
    if (!rewrite) continue;
    item.example = `${rewrite.example_ja}\n${rewrite.example_vi} · Ví dụ ${rewrites.label}`;
    item.example_rewritten = true;
  }
  const comprehension = readyComprehension(data);
  const books = {};
  for (const level of LEVELS) {
    const order = (list) => list.filter((x) => x.level === level).sort((a, b) => a.position - b.position || a.id.localeCompare(b.id));
    // A row with no Vietnamese meaning in any book is a section heading ("～っぽい", "あ～き"), not a word to learn.
    const v = order([...vocab.values()].filter((x) => x.meanings_vi.length));
    const k = order([...kanji.values()]);
    const g = order([...grammar.values()]);
    const r = order(comprehension.reading);
    const l = order(comprehension.listening);
    assignSlots(v, previous[level]?.vocabulary);
    assignSlots(g, previous[level]?.grammar);
    const count = CHAPTERS[level];
    const [dv, dk, dg] = [v, k, g].map((list) => deal(list, count));
    // Reading/Listening question copies are coded by chapter + passage order: keep earlier placements (D3-07).
    const dr = dealStable(r, count, previous[level]?.chapters, 'reading');
    const dl = dealStable(l, count, previous[level]?.chapters, 'listening');
    const chapters = Array.from({ length: count }, (_, i) => ({ number: i + 1,
      vocabulary: dv[i].map((x) => x.id), kanji: dk[i].map((x) => x.id), grammar: dg[i].map((x) => x.id),
      reading: dr[i].map((x) => x.id), listening: dl[i].map((x) => x.id),
      dialogue_source: DIALOGUE_BOOKS[level] ? { textbook: DIALOGUE_BOOKS[level], chapter: i + 1 } : null }));
    // §2.32 (T1): every passage/clip of the store carries its labels; nothing is in the separate pool yet.
    attachLabels(r, dr, level, 'READING', v, g);
    attachLabels(l, dl, level, 'LISTENING', v, g);
    const readingQuestions = r.reduce((n, x) => n + x.questions, 0);
    const minutes = Math.ceil(v.length * MINUTES.vocabulary + k.length * MINUTES.kanji + g.length * MINUTES.grammar
      + readingQuestions * MINUTES.reading_question + l.length * MINUTES.listening_clip + DIALOGUE_LINES[level] * MINUTES.dialogue_line);
    books[level] = {
      level, code: `LP-OWNED-JLAS-${level}-COMPILED`, title: `J-LAS ${level} — Giáo trình tổng hợp`, status: 'COMPILED', chapters,
      summary: {
        chapters: count, vocabulary: v.length, kanji: k.length, grammar: g.length,
        grammar_without_example: g.filter((x) => !x.example).length,
        grammar_meaning_supplemented: g.filter((x) => x.meaning_supplemented).length,
        headings_without_meaning: order([...vocab.values()]).filter((x) => !x.meanings_vi.length).length,
        kanji_with_vietnamese_meaning: k.filter((x) => x.meanings_vi.length).length,
        reading_passages: r.length, reading_questions: readingQuestions, listening_clips: l.length, dialogue_lines: DIALOGUE_LINES[level],
        pending_comprehension_pool: comprehension.pending[level] ?? null,
        minutes, days: Object.fromEntries([30, 45, 60, 90].map((m) => [m, Math.ceil(minutes / m)])),
      },
      vocabulary: v, kanji: k, grammar: g, reading: r, listening: l,
      // §2.32: store items not placed in any chapter live in their own section, jlas_store_pool (T6).
      pool: { reading: [], listening: [] },
      held_grammar: heldGrammar.filter((x) => x.level === level),
    };
  }
  return books;
}

const JA_KANJI = /[一-鿿々]/g;
const JA_CHARS = /[぀-ヿ一-鿿々ー]/g;
const MAX_REFS = 30;

/** Particles and endings found in almost every text: they never identify a pattern on their own. */
const COMMON = new Set(['の', 'は', 'が', 'を', 'に', 'で', 'と', 'も', 'へ', 'や', 'か', 'ね', 'よ', 'て', 'た', 'だ', 'い', 'な',
  'ます', 'ました', 'ません', 'です', 'でした', 'ない', 'する', 'します', 'した', 'こと', 'もの', 'これ', 'それ']);

/**
 * The ways a grammar pattern can be found in a text: "～の/こと" → [["の"], ["こと"]] (alternatives), each a list of Japanese
 * pieces that must all appear. Pieces that are only a common particle/ending are dropped; an alternative left with no piece
 * of at least two characters cannot identify the pattern and is dropped too.
 */
export function grammarFragments(pattern) {
  return String(pattern ?? '').replace(/[（(][^）)]*[）)]/g, '').split(/[／/]/)
    .map((alt) => alt.split(/[～〜~・,、…\s+＋A-Za-zＡ-Ｚ0-9０-９]+/).map((x) => x.trim())
      .filter((x) => x && !COMMON.has(x) && (x.match(JA_CHARS) ?? []).length >= 1))
    .filter((pieces) => pieces.some((x) => (x.match(JA_CHARS) ?? []).length >= 2));
}

/**
 * §2.32 T1 — labels of each passage/clip, deterministic from the source text: the chapter it sits in, the level's words and
 * grammar patterns its text uses (ids of this compiled book, in book order, at most 30 each), and a 1–5 difficulty: the
 * quintile of its length × (1 + kanji share) among the same level and skill. The text itself is not stored.
 */
const labelWords = (vocabulary) => vocabulary.filter((w) => {
  const e = String(w.expression ?? '');
  return (e.match(JA_KANJI) ?? []).length ? e.length >= 2 : e.length >= 3;
});
const labelPatterns = (grammar) => grammar.map((p) => ({ id: p.id, ways: grammarFragments(p.pattern) })).filter((p) => p.ways.length);
const weight = (x) => {
  const text = x._text ?? '';
  const chars = (text.match(JA_CHARS) ?? []).length;
  return chars * (1 + (chars ? (text.match(JA_KANJI) ?? []).length / chars : 0));
};
const patternsIn = (patterns, text) => patterns
  .filter((p) => p.ways.some((pieces) => pieces.every((piece) => (text ?? '').includes(piece)))).map((p) => p.id);

export function attachLabels(items, dealt, level, skill, vocabulary, grammar) {
  const chapterOf = new Map();
  dealt.forEach((list, index) => list.forEach((x) => chapterOf.set(x.id, index + 1)));
  const words = labelWords(vocabulary);
  const patterns = labelPatterns(grammar);
  // A pattern found in more than 40% of the level's passages of this skill cannot tell passages apart: not a label.
  const found = new Map(items.map((x) => [x.id, patternsIn(patterns, x._text)]));
  const spread = new Map();
  for (const ids of found.values()) for (const id of ids) spread.set(id, (spread.get(id) ?? 0) + 1);
  const telling = (id) => spread.get(id) <= items.length * 0.4;
  const ranked = [...items].sort((a, b) => weight(a) - weight(b) || a.id.localeCompare(b.id));
  const rank = new Map(ranked.map((x, i) => [x.id, i]));
  for (const x of items) {
    const text = x._text ?? '';
    const meta = x._meta ?? {};
    x.labels = {
      level, chapter_hint: chapterOf.get(x.id) ?? null, skill, format: meta.format ?? null,
      grammar_refs: found.get(x.id).filter(telling).slice(0, MAX_REFS),
      vocab_refs: words.filter((w) => text.includes(w.expression)).map((w) => w.id).slice(0, MAX_REFS),
      difficulty: items.length ? Math.min(5, Math.floor((rank.get(x.id) * 5) / items.length) + 1) : 3,
      minutes: skill === 'READING' ? x.questions * MINUTES.reading_question : MINUTES.listening_clip,
      has_translation: Boolean(meta.has_translation), has_audio: Boolean(meta.has_audio),
      origin: 'SOURCE', review: 'ACCEPTED',
    };
    delete x._text; delete x._meta;
  }
}

/**
 * §2.32 T6 — labels of store pool passages (never placed in a chapter), measured against the level's chapter passages
 * {@code peers} (still carrying {@code _text}) so the chapter labels stay exactly as they are: the same words/patterns
 * rule (a pattern found in more than 40% of the peers is not telling), and the 1–5 difficulty is the quintile the pool
 * passage's weight takes among the peers. The pool passage keeps its own chapter hint, origin and review.
 */
export function poolLabels(item, peers, level, skill, vocabulary, grammar) {
  const words = labelWords(vocabulary);
  const patterns = labelPatterns(grammar);
  const spread = new Map();
  for (const peer of peers) for (const id of patternsIn(patterns, peer._text)) spread.set(id, (spread.get(id) ?? 0) + 1);
  const text = item.text_lines.join(' ');
  const below = peers.filter((peer) => weight(peer) < weight({ _text: text })).length;
  return {
    level, chapter_hint: item.chapter_hint, skill, format: item.format ?? null,
    grammar_refs: patternsIn(patterns, text).filter((id) => (spread.get(id) ?? 0) <= peers.length * 0.4).slice(0, MAX_REFS),
    vocab_refs: words.filter((w) => text.includes(w.expression)).map((w) => w.id).slice(0, MAX_REFS),
    difficulty: peers.length ? Math.min(5, Math.floor((below * 5) / peers.length) + 1) : 3,
    minutes: skill === 'READING' ? item.questions.length * MINUTES.reading_question : MINUTES.listening_clip,
    has_translation: (item.passage_translation ?? []).length > 0, has_audio: false,
    origin: item.origin, review: item.review,
  };
}

const stripHtml = (html) => String(html ?? '').replace(/<[^>]+>/g, '\n').replace(/&[a-z]+;/g, ' ');

/** Reading/listening already parsed and checked (Minna, Speed Master), by the source group code of their DB rows. */
export function readyComprehension(data) {
  const reading = []; const listening = [];
  const groups = new Map();
  for (const row of data.sections.jlpt_minna_n5_n4_reading?.exercises ?? []) {
    const group = row.source_code.replace(/-Q\d+$/, '');
    const local = row.level === 'N4' ? row.chapter - 25 : row.chapter;
    if (!groups.has(group)) groups.set(group, { id: `JR-${shortId(group)}`, source_group: group, level: row.level, position: (local - 0.5) / 25, questions: 0,
      _text: stripHtml(row.passage_html), _meta: { format: 'Minna · Đọc hiểu', has_translation: (row.passage_translation ?? []).length > 0 } });
    groups.get(group).questions++;
  }
  reading.push(...groups.values());
  for (const e of data.sections.jlpt_speed_n5_n4_reading?.exercises ?? []) {
    const [first, last] = e.level === 'N5' ? [13, 25] : [26, 50];
    reading.push({ id: `JR-${shortId(e.source_group)}`, source_group: e.source_group, level: e.level,
      position: (e.chapter - first + 0.5) / (last - first + 1), questions: e.questions.length,
      _text: (e.text_lines ?? []).join(' '), _meta: { format: e.type ?? null, has_translation: (e.passage_translation ?? []).length > 0 } });
  }
  for (const e of data.sections.jlpt_speed_n3_comprehension?.exercises ?? []) {
    const item = { id: `J${e.skill === 'READING' ? 'R' : 'L'}-${shortId(e.source_group)}`, source_group: e.source_group, level: 'N3',
      position: (e.chapter - 0.5) / 27, questions: e.questions.length,
      _text: (e.text_lines ?? []).join(' '), _meta: { format: e.type ?? null,
        has_translation: (e.passage_translation ?? []).length > 0 || /Dịch( đoạn văn)?:/.test(e.questions?.[0]?.explanation ?? ''),
        has_audio: Boolean(e.audio_url) } };
    (e.skill === 'READING' ? reading : listening).push(item);
  }
  for (const lesson of data.sections.jlpt_minna_n5_n4_listening?.lessons ?? []) {
    const n = Number(lesson.bai);
    const level = n > 25 ? 'N4' : 'N5';
    for (const clip of lesson.cau ?? []) {
      const group = `LP-OWNED-MINNA-${level}-L${String(n).padStart(2, '0')}-LISTEN-${clip.ma}`;
      const script = (clip.muc ?? []).flatMap((m) => m.script ?? []);
      listening.push({ id: `JL-${shortId(group)}`, source_group: group, level, position: ((level === 'N4' ? n - 25 : n) - 0.5) / 25, questions: 1,
        _text: script.map((x) => x.ja ?? '').join(' '), _meta: { format: 'Minna · Nghe hiểu',
          has_translation: script.some((x) => x.vi), has_audio: Boolean(clip.audio_url) } });
    }
  }
  const used = new Set(['Speed Master N5', 'Speed Master N4', 'Speed Master N3']);
  const pending = {};
  for (const l of data.sections.jlpt_comprehension_n5_n3?.lessons ?? []) {
    if (used.has(l.content?.sach) || !(l.questions ?? []).length) continue;
    const ok = l.questions.every((q) => q.answer_check === 'STRUCTURALLY_UNIQUE');
    const p = (pending[l.level] ??= { reading: 0, listening: 0, with_unchecked_keys: 0 });
    if (!ok) p.with_unchecked_keys++;
    else if (l.kind === 'READING') p.reading++; else p.listening++;
  }
  return { reading, listening, pending };
}

export async function loadVnjp(root) {
  const files = {}; const inputs = {};
  for (const name of VNJPCLUB) {
    const file = `data/vnjpclub/${name}.json/${name}.json`;
    const text = await readFile(path.join(root, file), 'utf8');
    files[name] = JSON.parse(text);
    inputs[file] = sha(text.replace(/\r\n/g, '\n'));
  }
  return { files, inputs };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = path.resolve(process.argv[2] ?? '.');
  const data = await readLearningContent(root);
  const { files, inputs } = await loadVnjp(root);
  const previous = Object.fromEntries(LEVELS.map((level) => [level, data.sections[`jlas_compiled_book_${level.toLowerCase()}`]]));
  const books = compileBooks(data, files, previous);
  const sections = {};
  for (const level of LEVELS) {
    const section = `jlas_compiled_book_${level.toLowerCase()}`;
    sections[section] = { ...books[level], inputs: { ...inputs, sekai: data.section_sha256.jlpt_sekai_n5_n4,
      kanjidic: data.section_sha256.kanji_n5_n1, grammar_books: data.section_sha256.jlas_book_grammar_snapshot } };
    if (process.argv.includes('--check') && JSON.stringify(data.sections[section]) !== JSON.stringify(sections[section])) {
      throw new Error(`${section} is stale.`);
    }
    console.log(level, JSON.stringify(sections[section].summary));
  }
  if (!process.argv.includes('--check') && !process.argv.includes('--dry-run')) await writeLearningSections(root, sections);
}
