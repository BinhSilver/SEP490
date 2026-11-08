// [JLAS-W0108] Plan 3 Task 3: checks the Reading/Listening practice banks (data/learning-path/skills/practice/<L>/*.json)
// against the question-type catalog and writes data/learning-path/skills/reports/<L>-excluded.csv + <L>-coverage.json.
// The banks are read-only input: a set that fails a rule is reported (excluded + reasons), never rewritten.
// CLI: node tools/learning-path-source/skills/skill-check.mjs --level N5 [--level N4 ...] [--check]
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { ROOT, json, readJson } from '../compile/util.mjs';

export const SKILLS_DIR = path.join(ROOT, 'data/learning-path/skills');
export const REPORTS_DIR = path.join(SKILLS_DIR, 'reports');
export const LEVELS = ['N5', 'N4', 'N3', 'N2', 'N1'];

// Same banned list as the Plan 2 seed check (word boundary, so ordinary words that contain the letters pass), plus the
// Japanese titles of the textbooks and the katakana of the reference course.
export const BANNED_LATIN = /\b(?:mirai|minna|shinkanzen|sou\s*matome|genki|marugoto|claude|anthropic|gpt|chatgpt|openai|gemini|deepseek)\b/i;
export const BANNED_JA = ['ミライ', 'みんなの日本語', '新完全マスター', '完全マスター', '総まとめ', '耳から覚える', 'げんき日本語'];
export const hasBannedName = text => BANNED_LATIN.test(text) || BANNED_JA.some(b => text.includes(b));

const SPEAKERS = new Set(['M', 'F', 'N']);
// Policy #34 per-session floor (spec §4.7). N2/N1 Reading also needs one passage of ≥ 600 characters.
export const FLOORS = {
  N5: { READING: { passages: 2, questions: 6 }, LISTENING: { questions: 10, dictation: 5 } },
  N4: { READING: { passages: 2, questions: 6 }, LISTENING: { questions: 10, dictation: 5 } },
  N3: { READING: { passages: 3, questions: 10 }, LISTENING: { questions: 12, dictation: 5 } },
  N2: { READING: { passages: 3, questions: 12, longChars: 600 }, LISTENING: { questions: 12, dictation: 5 } },
  N1: { READING: { passages: 3, questions: 12, longChars: 600 }, LISTENING: { questions: 12, dictation: 5 } }
};

const kindOf = code => String(code ?? '').split('-').slice(2).join('-');
export const expectedOptions = typeCode => ['UTTERANCE', 'RESPONSE'].includes(kindOf(typeCode)) ? 3 : 4;
const charsOf = text => String(text ?? '').replace(/\s/g, '').length;
const strings = (x, at, out = []) => {
  if (typeof x === 'string') out.push([at, x]);
  else if (Array.isArray(x)) x.forEach((v, i) => strings(v, `${at}[${i}]`, out));
  else if (x && typeof x === 'object') for (const [k, v] of Object.entries(x)) strings(v, at ? `${at}.${k}` : k, out);
  return out;
};

/** Rules of one set on its own (cross-set duplicates are checked by checkLevel). */
export function checkSet(set, type) {
  const reasons = [];
  const questions = Array.isArray(set?.questions) ? set.questions : [];
  const script = Array.isArray(set?.script) ? set.script : [];
  const reading = type?.skill === 'READING';
  const derived = {
    charCount: reading || !type ? charsOf(set?.passage_ja) : script.reduce((n, l) => n + charsOf(l?.ja), 0),
    questionCount: questions.length,
    optionCount: questions.reduce((n, q) => Math.max(n, Array.isArray(q?.options) ? q.options.length : 0), 0)
  };
  if (!type) {
    reasons.push(`TYPE_UNKNOWN ${set?.type_code}`);
    return { ok: false, reasons, derived };
  }
  if (set.type_code !== type.code || set.level !== type.level) reasons.push(`LEVEL_MISMATCH ${set.type_code}/${set.level}`);
  if (!String(set.id ?? '').startsWith(`${type.code}-`)) reasons.push(`ID_FORMAT ${set.id}`);

  if (reading) {
    if (!String(set.passage_ja ?? '').trim()) reasons.push('PASSAGE_MISSING');
    else {
      const min = Math.floor(type.passage.chars_min * 0.8);
      const max = Math.ceil(type.passage.chars_max * 1.2);
      if (derived.charCount < min || derived.charCount > max) reasons.push(`PASSAGE_LENGTH ${derived.charCount} not in [${min}, ${max}]`);
    }
  } else {
    if (script.length === 0 || script.some(l => !String(l?.ja ?? '').trim())) reasons.push('SCRIPT_MISSING');
    const bad = [...new Set(script.map(l => l?.speaker).filter(s => !SPEAKERS.has(s)))];
    if (bad.length) reasons.push(`SPEAKER_INVALID ${bad.join('/')}`);
    const voices = new Set(script.map(l => l?.speaker).filter(s => s !== 'N' && SPEAKERS.has(s))).size;
    const allowed = (type.audio?.speakers ?? 0) + 1;
    if (voices > allowed) reasons.push(`TOO_MANY_SPEAKERS ${voices} > ${allowed}`);
    const dictation = Array.isArray(set.dictation) ? set.dictation : [];
    if (dictation.length < 1 || dictation.length > 2) reasons.push(`DICTATION_COUNT ${dictation.length}`);
    dictation.forEach((line, i) => {
      if (!String(line ?? '').trim() || !script.some(l => String(l?.ja ?? '').includes(line))) reasons.push(`DICTATION_NOT_IN_SCRIPT #${i + 1}`);
    });
  }

  if (questions.length === 0) reasons.push('QUESTIONS_MISSING');
  const want = expectedOptions(type.code);
  questions.forEach((q, i) => {
    const options = Array.isArray(q?.options) ? q.options : [];
    if (!String(q?.question_ja ?? '').trim()) reasons.push(`QUESTION_MISSING Q${i + 1}`);
    if (options.length !== want) reasons.push(`OPTION_COUNT Q${i + 1} ${options.length} != ${want}`);
    if (!Number.isInteger(q?.answer_index) || q.answer_index < 0 || q.answer_index >= options.length) {
      reasons.push(`ANSWER_INDEX Q${i + 1} ${q?.answer_index}`);
    }
    const seen = new Set(options.map(o => String(o ?? '').trim()));
    if (seen.size !== options.length || seen.has('')) reasons.push(`OPTION_DUPLICATE Q${i + 1}`);
  });

  // Owner question 1, default (a): an image type shows image_description_vi as a text "situation" card.
  if (type.needs_image && !String(set.image_description_vi ?? '').trim()) reasons.push('IMAGE_DESCRIPTION_MISSING');

  // The field is named, never the text.
  for (const [at, text] of strings(set, '')) if (hasBannedName(text)) reasons.push(`BANNED_NAME ${at}`);

  return { ok: reasons.length === 0, reasons, derived };
}

// Catalog order: Reading types before Listening, then the type's exam order, then set id.
const skillRank = t => (t?.skill === 'READING' ? 0 : 1);
const byOrder = types => (a, b) => skillRank(types.get(a.type_code)) - skillRank(types.get(b.type_code))
  || (types.get(a.type_code)?.order ?? 99) - (types.get(b.type_code)?.order ?? 99)
  || String(a.id).localeCompare(String(b.id));

/**
 * Checks every set of a level. Sets are visited in id order so the first copy of a duplicated passage / script line wins.
 * A narrator line equal to one of the set's own questions (男の人はまず何をしますか。) is the standard exam prompt and may repeat.
 */
export function checkLevel(level, { types, lessons, sets, counts }) {
  const levelTypes = types.filter(t => t.level === level);
  const typeByCode = new Map(types.map(t => [t.code, t]));
  const lessonCodes = new Set((lessons ?? []).map(l => l.type_code));
  const passages = new Map();
  const lines = new Map();
  const ids = new Set();
  const accepted = [];
  const excluded = [];
  for (const set of [...sets].sort((a, b) => String(a.id).localeCompare(String(b.id)))) {
    const type = typeByCode.get(set.type_code);
    const { reasons, derived } = checkSet(set, type);
    if (type && type.level !== level) reasons.push(`OTHER_LEVEL ${type.level}`);
    if (type && !lessonCodes.has(type.code)) reasons.push('TYPE_WITHOUT_LESSON');
    if (ids.has(set.id)) reasons.push('DUPLICATE_ID');
    ids.add(set.id);
    const passage = String(set.passage_ja ?? '').trim();
    if (passage && passages.has(passage)) reasons.push(`DUPLICATE_PASSAGE ${passages.get(passage)}`);
    const prompts = new Set((set.questions ?? []).map(q => String(q?.question_ja ?? '').trim()));
    const own = [...new Set((set.script ?? []).map(l => String(l?.ja ?? '').trim()))]
      .filter(l => l && !prompts.has(l));
    for (const l of own) if (lines.has(l) && lines.get(l) !== set.id) reasons.push(`DUPLICATE_SCRIPT_LINE ${lines.get(l)}`);
    if (reasons.length) {
      excluded.push({ id: set.id, type_code: set.type_code, reasons: [...new Set(reasons)] });
      continue;
    }
    if (passage) passages.set(passage, set.id);
    for (const l of own) lines.set(l, set.id);
    accepted.push({ ...set, derived });
  }
  accepted.sort(byOrder(typeByCode));
  return { accepted, excluded, coverage: coverage(level, accepted, levelTypes, { counts }) };
}

// ---- pace model (Plan 2 JpathPacingEngine.walk at the default survey: 30 min, skill days WED+SAT, no rest day,
// COMBINED, no kana rows; the 3rd skill day of the practice stage for WED+SAT is MON, policy #14) ----
const LOWER = new Set(['N5', 'N4']);
const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
export function recommend(level, minutes = 30) {
  const lower = LOWER.has(level);
  return {
    vocab: clamp(Math.round(0.40 * minutes / 0.6), 5, 50),
    kanji: clamp(Math.round(0.33 * minutes / 1.25), 2, lower ? 20 : 15),
    grammar: clamp(Math.round(0.27 * minutes / 4.0), 1, lower ? 5 : 4)
  };
}

/** Skill days (= DAY sessions per skill) of one level from the first day until the level is ready (practice + 14 days). */
export function expectedSkillSessions(level, counts, { minutes = 30 } = {}) {
  const per = recommend(level, minutes);
  const tabs = ['vocab', 'kanji', 'grammar'];
  const done = { vocab: 0, kanji: 0, grammar: 0 };
  const allDone = () => tabs.every(t => done[t] >= counts[t]);
  const reached = () => tabs.every(t => done[t] * 5 >= counts[t] * 4);
  // Day 0 is a Monday (0 MON … 2 WED … 5 SAT … 6 SUN).
  const SKILL = new Set([2, 5]);
  const THIRD = 0;
  let learn = 0;
  let practice = 0;
  let practiceDay = null;
  let day = 0;
  for (; !(allDone() && practiceDay !== null); day++) {
    if (day > 3650) throw new Error(`pace walk for ${level} did not end`);
    const dow = day % 7;
    const inPractice = practiceDay !== null;
    const skill = (inPractice && allDone()) || SKILL.has(dow) || (inPractice && dow === THIRD);
    if (skill) inPractice ? practice++ : learn++;
    else for (const t of tabs) done[t] = Math.min(counts[t], done[t] + per[t]);
    if (practiceDay === null && reached()) practiceDay = day;
  }
  // After the last new item every day is a skill day until practice start + 14.
  for (; day <= practiceDay + 14; day++) practice++;
  return { learn, practice, total: learn + practice };
}

export function levelCounts(level) {
  const file = path.join(ROOT, 'data/learning-path/compiled', `${level}.json`);
  const c = readJson(file);
  return { vocab: c.vocabulary.length, kanji: c.kanji.length, grammar: c.grammar.length };
}

/** Sessions of one type the pool covers at the policy #34 floor, taking sets in sort order, before any set is reused. */
function sessionsSupported(level, skill, sets) {
  const floor = FLOORS[level][skill];
  let sessions = 0;
  let cur = { passages: 0, questions: 0, dictation: 0, long: false };
  const met = () => skill === 'READING'
    ? cur.passages >= floor.passages && cur.questions >= floor.questions && (!floor.longChars || cur.long)
    : cur.questions >= floor.questions && cur.dictation >= floor.dictation;
  for (const s of sets) {
    cur.passages++;
    cur.questions += (s.questions ?? []).length;
    cur.dictation += (s.dictation ?? []).length;
    if (floor.longChars && (s.derived?.charCount ?? 0) >= floor.longChars) cur.long = true;
    if (met()) {
      sessions++;
      cur = { passages: 0, questions: 0, dictation: 0, long: false };
    }
  }
  return sessions;
}

/**
 * One row per type of the level (catalog order): accepted sets/questions, DAY sessions the pool supports before reuse
 * (policy #34 floor, #39 reuse) and DAY sessions expected at the default pace, split evenly over the skill's types.
 */
export function coverage(level, accepted, types, { counts } = {}) {
  const levelTypes = types.filter(t => t.level === level)
    .sort((a, b) => skillRank(a) - skillRank(b) || a.order - b.order);
  const expected = expectedSkillSessions(level, counts ?? levelCounts(level));
  return levelTypes.map(t => {
    const sets = accepted.filter(s => s.type_code === t.code).sort((a, b) => String(a.id).localeCompare(String(b.id)));
    const perSkill = levelTypes.filter(x => x.skill === t.skill).length;
    const row = {
      type: t.code,
      skill: t.skill,
      sets: sets.length,
      questions: sets.reduce((n, s) => n + (s.questions ?? []).length, 0),
      sessionsSupported: sessionsSupported(level, t.skill, sets),
      sessionsExpected: Math.ceil(expected.total / perSkill)
    };
    if (t.skill === 'LISTENING') row.dictation = sets.reduce((n, s) => n + (s.dictation ?? []).length, 0);
    return row;
  });
}

const csvCell = v => /[",\n]/.test(String(v)) ? `"${String(v).replace(/"/g, '""')}"` : String(v);
export function excludedCsv(excluded) {
  return ['id,type_code,reasons', ...excluded.map(e => [e.id, e.type_code, e.reasons.join('; ')].map(csvCell).join(','))]
    .join('\n') + '\n';
}

export function loadLevelInputs(level) {
  const types = readJson(path.join(SKILLS_DIR, 'question-types.json'));
  const lessons = readJson(path.join(SKILLS_DIR, 'strategy-lessons.json'));
  const dir = path.join(SKILLS_DIR, 'practice', level);
  const sets = existsSync(dir) ? readdirSync(dir).filter(f => f.endsWith('.json')).sort()
    .flatMap(f => readJson(path.join(dir, f))) : [];
  return { types, lessons, sets };
}

/** The coverage file: pace assumptions + rows; no dates, so a rerun on the same banks is byte-identical. */
export function coverageReport(level, result, counts) {
  const c = counts ?? levelCounts(level);
  const e = expectedSkillSessions(level, c);
  return {
    level,
    pace: { minutes: 30, skill_days: 'WED,SAT (+MON in practice stage)', items: c, per_day: recommend(level),
      skill_sessions_learn: e.learn, skill_sessions_practice: e.practice, skill_sessions_total: e.total },
    floor: FLOORS[level],
    sets_total: result.accepted.length + result.excluded.length,
    accepted: result.accepted.length,
    excluded: result.excluded.length,
    short_types: result.coverage.filter(r => r.sessionsSupported < r.sessionsExpected).map(r => r.type),
    types: result.coverage
  };
}

export function writeReports(level, { check = false } = {}) {
  const inputs = loadLevelInputs(level);
  const counts = levelCounts(level);
  const result = checkLevel(level, { ...inputs, counts });
  const files = {
    [path.join(REPORTS_DIR, `${level}-excluded.csv`)]: excludedCsv(result.excluded),
    [path.join(REPORTS_DIR, `${level}-coverage.json`)]: json(coverageReport(level, result, counts))
  };
  const drift = [];
  for (const [file, text] of Object.entries(files)) {
    if (check) {
      if (!existsSync(file) || readFileSync(file, 'utf8') !== text) drift.push(path.relative(ROOT, file));
    } else {
      mkdirSync(path.dirname(file), { recursive: true });
      writeFileSync(file, text);
    }
  }
  return { result, drift };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const levels = args.flatMap((a, i) => a === '--level' ? [args[i + 1]] : []);
  const check = args.includes('--check');
  if (levels.length === 0 || levels.some(l => !LEVELS.includes(l))) {
    console.error('usage: skill-check.mjs --level N5 [--level N4 ...] [--check]');
    process.exit(2);
  }
  let failed = false;
  for (const level of levels) {
    const { result, drift } = writeReports(level, { check });
    const reasons = {};
    for (const e of result.excluded) for (const r of e.reasons) { const k = r.split(' ')[0]; reasons[k] = (reasons[k] ?? 0) + 1; }
    console.log(`${level}: ${result.accepted.length} accepted, ${result.excluded.length} excluded`
      + (Object.keys(reasons).length ? ` (${Object.entries(reasons).map(([k, n]) => `${k} ${n}`).join(', ')})` : ''));
    for (const c of result.coverage) {
      console.log(`  ${c.type.padEnd(16)} sets ${String(c.sets).padStart(3)}  q ${String(c.questions).padStart(3)}  sessions ${c.sessionsSupported}/${c.sessionsExpected}${c.sessionsSupported < c.sessionsExpected ? '  SHORT' : ''}`);
    }
    if (check && drift.length) { failed = true; console.error(`  drift: ${drift.join(', ')}`); }
  }
  process.exit(failed ? 1 : 0);
}
