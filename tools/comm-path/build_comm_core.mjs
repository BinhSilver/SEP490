// [JLAS-W0108] F70 (owner 12/11): the base set ("Bài nền") of the communication path, filtered from the JLPT store.
//
// Reads data/learning-path/compiled/N5–N3.json (the JLPT content, already in jpath_* tables) and the communication
// content (data/learning-path/communication-compiled/<GOAL>.json), then writes sql/learning/parts/learning_data_comm_core.sql:
// INSERT ... ON DUPLICATE KEY UPDATE into cpath_core_item. No new content is written: every row points to a jpath code,
// so audio, examples and meanings are the JLPT ones, and progress is shared with the JLPT path (jpath_user_item).
//
// Owner sizes (12/11): common set ~1,000 words + ~60 spoken grammar patterns + ~20 keigo items; WORK/BA add ~300 words.
// Depth: Cơ bản = first third, Sơ cấp = first two thirds, Chuyên sâu = all (rank: level, situation use, tier, books, order).
// Keigo: Công việc/BrSE from the start (ranked thirds); Đời sống only at Chuyên sâu (depth 3). Kanji is not taught.
//
// Run: node tools/comm-path/build_comm_core.mjs [--check]   (--check: fail when the committed part is stale)

import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), '../..');
const OUT = path.join(ROOT, 'sql/learning/parts/learning_data_comm_core.sql');

export const VOCAB_QUOTA = { N5: 450, N4: 330, N3: 220 };
export const WORK_VOCAB = 300;

/** Topics left out of the common set: written / academic subjects a speaker rarely needs (owner: "khi nói"). */
export const EXCLUDED_TOPICS = new Set([
  'Chính trị', 'Kinh tế', 'Xã hội', 'Xã hội của chúng ta', 'Xã hội quốc tế', 'Truyền thông đại chúng', 'Thi cử',
  'Trường đại học của Nhật Bản', 'Đại học, Cao học', 'Ủy ban (Văn phòng Nhà nước)', 'Thế giới, môi trường',
  'Môi trường thế giới', 'Quan hệ quốc tế', 'Từ dễ nhầm lẫn ①', 'Từ dễ nhầm lẫn ②', 'Từ nhiều nghĩa', 'Đất nước',
]);

/** Work topics: the WORK/BA extra words come from here (plus words used in the WORK/BA dialogues). */
export const WORK_TOPICS = new Set([
  'Công việc', 'Công việc ①', 'Công việc ②', 'Công ty', 'Việc làm', 'Quan hệ trên dưới', 'Dữ liệu', 'Bằng máy vi tính',
  'Làm việc, lao động', 'Là công việc như thế nào?', 'Tìm việc, việc làm', 'Nghỉ việc, chuyển chỗ làm', 'Công việc hàng ngày',
  'Sản xuất', 'Các ngành công nghiệp', 'Máy tính (Điện thoại thông minh)', 'Lời hứa', 'Thiết kế',
]);

/** Spoken grammar (owner: ~60 patterns, no written style such as 〜に際して). N5 by book order, N4/N3 hand picked. */
export const GRAMMAR_N5_SKIP = /\((Past|Negative|Negative-Past)\)|^(それ|あれ|その|あの|そこ|あそこ)$/;
export const GRAMMAR_N5_COUNT = 25;
export const GRAMMAR_N4 = [
  '〜てしまう', '〜ておく', '〜てみる', '〜ていただけませんか', 'んです・のです', '〜かもしれない', 'れる・られる (Potential)',
  '〜た方(ほう)がいい', '〜つもり', '予定(よてい)だ', 'う・よう (Volitional)', '〜ながら', '〜たばかり', '〜のに', '〜なら', '〜ば',
  'し (Reasons)', 'まだ〜ていません', '〜にする', 'たらいい・といい',
];
export const GRAMMAR_N3 = [
  '〜って', 'ところだった 1', '序(つい)でに', 'お陰(かげ)で', '〜せいで', '〜て欲(ほ)しい', 'たらどう', '事(こと)にする',
  'Thể thường + はずだ', '筈(はず)がない', '訳(わけ)ではない', 'V từ điển / N の + 場合は', 'てからでないと', '内(うち)に', '〜ものだから',
];

/** Keigo (owner: ~20 items), most common first. Codes are jpath codes (several are "Mở rộng" in the JLPT path). */
export const KEIGO = [
  ['VOCAB', 'JV-c0d73e7632'], // いらっしゃる
  ['VOCAB', 'JV-511fad41fc'], // おっしゃる
  ['VOCAB', 'JV-8d7b7cf870'], // 伺う
  ['VOCAB', 'JV-d545223351'], // 申す
  ['VOCAB', 'JV-6f03ec00a2'], // 頂く
  ['VOCAB', 'JV-f439142961'], // 下さる
  ['VOCAB', 'JV-ead5a422b9'], // 参る
  ['VOCAB', 'JV-0f457a0aad'], // 居る（おる）
  ['GRAMMAR', 'JG-cffcb487d6'], // お〜ください
  ['GRAMMAR', 'JG-edeea93d76'], // お〜になる
  ['GRAMMAR', 'JG-a4ba626093'], // お〜する
  ['GRAMMAR', 'JG-99451ddbdd'], // V させて いただきます
  ['GRAMMAR', 'JG-9162fb507d'], // V-て おります
  ['GRAMMAR', 'JG-1ec5a8d787'], // N + でございます
  ['VOCAB', 'JV-2604df655f'], // 召し上がる
  ['VOCAB', 'JV-f309ddbb16'], // ご覧になる
  ['VOCAB', 'JV-269b79fac4'], // 拝見する
  ['VOCAB', 'JV-95317c1842'], // なさる
  ['VOCAB', 'JV-7c4a5a51e4'], // 失礼します
  ['GRAMMAR', 'JG-88bf5bfafc'], // V (ら)れます
];

const LEVELS = ['N5', 'N4', 'N3'];
const LEVEL_RANK = { N5: 0, N4: 1, N3: 2, N2: 3, N1: 4 };

function readJson(file) { return JSON.parse(readFileSync(file, 'utf8')); }

/** The word as written in a dialogue: drops "（←いる）", "＜する＞", "(する)" notes. */
export function baseForm(expression) {
  return String(expression || '').replace(/[（(＜<][^）)＞>]*[）)＞>]/g, '').trim();
}

/** Dialogue text (lines, words, phrases) of the given goals, for "used in a situation" ranking. */
export function dialogueText(goals) {
  return goals.map((g) => {
    const f = path.join(ROOT, 'data/learning-path/communication-compiled', g + '.json');
    if (!existsSync(f)) return '';
    const d = readJson(f);
    const parts = [];
    for (const t of d.topics || []) {
      for (const s of t.situations || []) {
        for (const l of s.lines || []) parts.push(l.ja);
        for (const w of s.words || s.vocabulary || []) parts.push(w.ja || w.word || '');
        for (const p of s.phrases || []) parts.push(p.ja || '');
      }
      for (const w of t.words || t.vocabulary || []) parts.push(w.ja || w.word || '');
      for (const p of t.phrases || []) parts.push(p.ja || "");
    }
    return parts.join('\n');
  }).join('\n');
}

export function usedIn(text, expression) {
  const b = baseForm(expression);
  return b.length >= 2 && text.includes(b);
}

function sourceCount(v) { return (v.provenance && Array.isArray(v.provenance.sources)) ? v.provenance.sources.length : 0; }

/** Rank inside one level: used in a dialogue, CORE tier, more source books, then the book order. */
export function rankWords(words, text) {
  return words.map((v) => ({ v, used: usedIn(text, v.expression) ? 1 : 0 }))
    .sort((a, b) => (b.used - a.used)
      || ((a.v.tier === 'CORE' ? 0 : 1) - (b.v.tier === 'CORE' ? 0 : 1))
      || (sourceCount(b.v) - sourceCount(a.v))
      || ((a.v.order || 0) - (b.v.order || 0)))
    .map((x) => x.v);
}

/** 1 / 2 / 3 by thirds of a ranked list (Cơ bản / Sơ cấp / Chuyên sâu). */
export function depthOf(index, total) {
  if (index < Math.ceil(total / 3)) return 1;
  if (index < Math.ceil((2 * total) / 3)) return 2;
  return 3;
}

/** Spreads n items evenly over 0..9999 (from..to), so vocab and grammar interleave in lessons. */
function spread(i, n, from = 0, to = 10000) { return Math.floor(from + ((i + 0.5) / n) * (to - from)); }

export function build() {
  const compiled = {};
  for (const L of [...LEVELS, 'N2', 'N1']) compiled[L] = readJson(path.join(ROOT, 'data/learning-path/compiled', L + '.json'));
  const studyVocab = (L) => compiled[L].vocabulary.filter((v) => v.queue === 'STUDY' && v.status !== 'HOLD');
  const studyGrammar = (L) => compiled[L].grammar.filter((g) => g.queue === 'STUDY' && g.status !== 'HOLD' && !g.parent_id);
  const byId = new Map();
  for (const L of Object.keys(compiled)) {
    for (const v of compiled[L].vocabulary) byId.set(v.id, { type: 'VOCAB', level: L, item: v });
    for (const g of compiled[L].grammar) byId.set(g.id, { type: 'GRAMMAR', level: L, item: g });
  }
  const commonText = dialogueText(['DAILY_LIFE', 'WORK', 'BA', 'TRAVEL']);
  const workText = dialogueText(['WORK', 'BA']);

  // ---- common vocabulary: per-level quota of the ranked conversational topics
  const common = [];
  const taken = new Set();
  for (const L of LEVELS) {
    const pool = studyVocab(L).filter((v) => !EXCLUDED_TOPICS.has(v.topic_vi) && !WORK_TOPICS.has(v.topic_vi));
    for (const v of rankWords(pool, commonText).slice(0, VOCAB_QUOTA[L])) {
      if (taken.has(v.id)) continue;
      taken.add(v.id);
      common.push({ type: 'VOCAB', kind: 'VOCAB', code: v.id, level: L });
    }
  }

  // ---- WORK/BA extra vocabulary: work topics and words of the work dialogues, not in the common set
  const workPool = [];
  for (const L of LEVELS) {
    for (const v of studyVocab(L)) {
      if (taken.has(v.id)) continue;
      const used = usedIn(workText, v.expression);
      if (!WORK_TOPICS.has(v.topic_vi) && !used) continue;
      workPool.push({ v, L, used: used ? 1 : 0, topic: WORK_TOPICS.has(v.topic_vi) ? 1 : 0 });
    }
  }
  workPool.sort((a, b) => (b.used - a.used) || (b.topic - a.topic) || (LEVEL_RANK[a.L] - LEVEL_RANK[b.L])
    || ((a.v.tier === 'CORE' ? 0 : 1) - (b.v.tier === 'CORE' ? 0 : 1)) || ((a.v.order || 0) - (b.v.order || 0)));
  const work = workPool.slice(0, WORK_VOCAB)
    .sort((a, b) => (LEVEL_RANK[a.L] - LEVEL_RANK[b.L]) || 0)
    .map((x) => ({ type: 'VOCAB', kind: 'VOCAB', code: x.v.id, level: x.L }));
  work.forEach((x) => taken.add(x.code));

  // ---- spoken grammar
  const grammar = [];
  const n5 = studyGrammar('N5').sort((a, b) => a.order - b.order).filter((g) => !GRAMMAR_N5_SKIP.test(g.pattern)).slice(0, GRAMMAR_N5_COUNT);
  for (const g of n5) grammar.push({ type: 'GRAMMAR', kind: 'GRAMMAR', code: g.id, level: 'N5' });
  for (const [L, list] of [['N4', GRAMMAR_N4], ['N3', GRAMMAR_N3]]) {
    const pool = studyGrammar(L);
    for (const p of list) {
      const g = pool.find((x) => x.pattern === p);
      if (!g) throw new Error(`F70: grammar pattern not in the ${L} study queue: ${p}`);
      grammar.push({ type: 'GRAMMAR', kind: 'GRAMMAR', code: g.id, level: L });
    }
  }

  // ---- keigo
  const keigo = KEIGO.map(([type, code]) => {
    const hit = byId.get(code);
    if (!hit || hit.type !== type) throw new Error(`F70: keigo code missing in compiled data: ${code}`);
    return { type, kind: 'KEIGO', code, level: hit.level };
  });

  // ---- rows: depth by thirds, sort_order spreads each list over the same 0..9999 line so lessons interleave
  const rows = [];
  const add = (scope, list, depthFn, from = 0, to = 10000) => list.forEach((x, i) => rows.push({
    scope, ...x, depth: depthFn(i, list.length), sort: spread(i, list.length, from, to),
  }));
  add('COMMON', common, depthOf);
  add('COMMON', grammar, depthOf);
  add('COMMON', keigo, () => 3, 6667, 10000);  // Đời sống: keigo only at Chuyên sâu, in the last third
  add('WORK', work, depthOf);
  add('WORK', keigo, depthOf);                 // Công việc/BrSE: keigo is required, from Cơ bản
  return { rows, counts: { common: common.length, work: work.length, grammar: grammar.length, keigo: keigo.length } };
}

function sqlStr(s) { return s == null ? 'NULL' : "'" + String(s).replace(/\\/g, '\\\\').replace(/'/g, "''") + "'"; }

export function render({ rows, counts }) {
  const body = rows.map((r) => `${r.scope}|${r.type}|${r.code}|${r.kind}|${r.level}|${r.depth}|${r.sort}`).join('\n');
  const version = crypto.createHash('sha256').update(body).digest('hex').slice(0, 12);
  const out = [];
  out.push('-- =============================================================================');
  out.push('-- [JLAS-W0108] F70: communication base set (Bài nền), filtered from the JLPT store (generated seed).');
  out.push('-- Generated by tools/comm-path/build_comm_core.mjs. Do not edit by hand.');
  out.push(`-- Rows: ${rows.length} (common words ${counts.common}, grammar ${counts.grammar}, keigo ${counts.keigo} x2 scopes, work words ${counts.work}).`);
  out.push(`-- seed_version ${version}. Idempotent: rows of an older version are removed, the rest upserted.`);
  out.push('-- =============================================================================');
  out.push('');
  out.push('SET NAMES utf8mb4;');
  out.push('START TRANSACTION;');
  out.push('');
  out.push('-- BEGIN GENERATED cpath-core');
  const chunk = 400;
  for (let i = 0; i < rows.length; i += chunk) {
    out.push('INSERT INTO cpath_core_item (scope, ref_type, ref_code, kind, level, depth, sort_order, seed_version) VALUES');
    out.push(rows.slice(i, i + chunk).map((r) => `  (${sqlStr(r.scope)}, ${sqlStr(r.type)}, ${sqlStr(r.code)}, ${sqlStr(r.kind)}, ${sqlStr(r.level)}, ${r.depth}, ${r.sort}, ${sqlStr(version)})`).join(',\n'));
    out.push('AS n ON DUPLICATE KEY UPDATE ref_type = n.ref_type, kind = n.kind, level = n.level, depth = n.depth, sort_order = n.sort_order, seed_version = n.seed_version;');
    out.push('');
  }
  out.push(`DELETE FROM cpath_core_item WHERE seed_version IS NULL OR seed_version <> ${sqlStr(version)};`);
  out.push('-- END GENERATED cpath-core');
  out.push('');
  out.push('COMMIT;');
  out.push('');
  return out.join('\n');
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === path.resolve(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'));
if (isMain) {
  const built = build();
  const text = render(built);
  if (process.argv.includes('--check')) {
    const now = existsSync(OUT) ? readFileSync(OUT, 'utf8') : '';
    if (now !== text) { console.error('learning_data_comm_core.sql is stale: run node tools/comm-path/build_comm_core.mjs'); process.exit(1); }
    console.log('learning_data_comm_core.sql is up to date.');
  } else {
    writeFileSync(OUT, text);
    console.log(`wrote ${path.relative(ROOT, OUT)}: ${built.rows.length} rows`, built.counts);
  }
}
