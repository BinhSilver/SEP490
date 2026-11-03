// [JLAS-W0108] JLPT path skills (Đọc · Nghe) seed, Plan 3 Task 6: pure functions from the skill bank to table rows and
// to the `-- BEGIN GENERATED jpath-skills` region of sql/learning/learning_data_<L>.sql. The CLI is build_skill_seed.mjs.
// Rows hold SQL-ready values: JSON columns are already serialised strings (or null).
import { LEVELS, sha256, stable } from '../compile/util.mjs';
import { codePointLength, rowsToUpsert, sqlString } from '../seed/seed-sql.mjs';
import { loadAudio } from '../seed/seed-content.mjs';

export const SKILL_BEGIN = '-- BEGIN GENERATED jpath-skills';
export const SKILL_END = '-- END GENERATED jpath-skills';
const GENERATOR = 'tools/learning-path-source/skills/build_skill_seed.mjs';

const CC = ['status', 'authored', 'seed_version'];
/** Table key → SQL table, primary key and the columns a seed run writes (report_hidden and updated_at never). */
export const SKILL_TABLES = {
  jpath_skill_type: {
    pk: ['code'],
    cols: ['code', 'level', 'skill', 'name_vi', 'name_ja', 'sort_order', 'questions_in_exam', 'needs_image', 'chars_min',
      'chars_max', 'speakers', 'seconds_typical', 'seed_version'],
  },
  jpath_skill_lesson: {
    pk: ['type_code'], cc: true,
    cols: ['type_code', 'title_vi', 'goal_vi', 'steps_json', 'traps_json', 'tips_json', 'example_json',
      'example_answer_index', 'check_question_vi', 'check_options_json', 'check_answer_index', 'check_explanation_vi',
      'example_audio_path', 'example_audio_sha256', ...CC],
  },
  jpath_skill_set: {
    pk: ['code'], cc: true,
    cols: ['code', 'type_code', 'level', 'skill', 'sort_order', 'passage_ja', 'passage_vi', 'script_json', 'script_vi',
      'image_description_vi', 'new_words_json', 'char_count', 'question_count', 'audio_path', 'audio_sha256',
      'audio_seconds', ...CC],
  },
  jpath_skill_question: {
    pk: ['code'], cc: true,
    cols: ['code', 'set_code', 'sort_order', 'question_ja', 'options_json', 'answer_index', 'explanation_vi', ...CC],
  },
  jpath_skill_dictation: {
    pk: ['code'], cc: true,
    cols: ['code', 'set_code', 'sort_order', 'text_ja', 'blanks_json', 'audio_path', 'audio_sha256', ...CC],
  },
};

/** VARCHAR lengths of learning_schema.sql Part C (tables 28-32), counted in code points like MySQL. */
export const SKILL_LIMITS = {
  'jpath_skill_type.code': 24, 'jpath_skill_type.name_vi': 120, 'jpath_skill_type.name_ja': 60,
  'jpath_skill_lesson.type_code': 24, 'jpath_skill_lesson.title_vi': 200, 'jpath_skill_lesson.goal_vi': 500,
  'jpath_skill_lesson.check_question_vi': 500, 'jpath_skill_lesson.check_explanation_vi': 1000,
  'jpath_skill_lesson.example_audio_path': 255,
  'jpath_skill_set.code': 24, 'jpath_skill_set.image_description_vi': 1000, 'jpath_skill_set.audio_path': 255,
  'jpath_skill_question.code': 28, 'jpath_skill_question.question_ja': 500, 'jpath_skill_question.explanation_vi': 1000,
  'jpath_skill_dictation.code': 28, 'jpath_skill_dictation.text_ja': 255, 'jpath_skill_dictation.audio_path': 255,
};

const VISIBLE_STATUS = 'AI_CHECKED';
const HOLD = 'HOLD';
const json = (v) => (v === null || v === undefined ? null : JSON.stringify(v));
const blankToNull = (v) => (v === null || v === undefined || String(v).trim() === '' ? null : String(v));
const charsOf = (text) => String(text ?? '').replace(/\s/g, '').length;

/**
 * Skill audio manifest (data/learning-path/audio/skills-<L>.json) → Map keyed by file name without `.mp3`
 * (`<setId>`, `<setId>-d<n>`, `lesson-<type>`). `ok` only when the file exists with the manifest's size and SHA-256.
 */
export function loadSkillAudio(manifest, audioRoot = 'data/content-audio') {
  const seconds = new Map((manifest?.items ?? [])
    .map((i) => [String(i.file).split('/').pop().replace(/\.mp3$/i, ''), i.audio_seconds ?? null]));
  const out = new Map();
  for (const [key, a] of loadAudio(manifest, audioRoot)) out.set(key, { ...a, audio_seconds: seconds.get(key) ?? null });
  return out;
}

const audioOf = (audio, key) => {
  const a = audio?.get(key);
  return a?.ok ? a : null;
};

function setRow(level, set, sortOrder, typeByCode, audio, status) {
  const type = typeByCode.get(set.type_code);
  if (!type) throw new Error(`${set.id}: unknown type ${set.type_code}`);
  const listening = type.skill === 'LISTENING';
  const script = Array.isArray(set.script) ? set.script.map((l) => ({ speaker: l.speaker, ja: l.ja })) : null;
  const a = listening ? audioOf(audio, set.id) : null;
  const questions = Array.isArray(set.questions) ? set.questions : [];
  return {
    code: set.id,
    type_code: set.type_code,
    level,
    skill: type.skill,
    sort_order: sortOrder,
    passage_ja: listening ? null : blankToNull(set.passage_ja),
    passage_vi: listening ? null : blankToNull(set.passage_vi),
    script_json: listening ? json(script ?? []) : null,
    script_vi: listening ? blankToNull(set.script_vi) : null,
    image_description_vi: blankToNull(set.image_description_vi),
    new_words_json: json((set.new_words ?? []).map((w) => ({ expression: w.expression, reading: w.reading, meaning_vi: w.meaning_vi }))),
    char_count: set.derived?.charCount ?? (listening ? (script ?? []).reduce((n, l) => n + charsOf(l.ja), 0) : charsOf(set.passage_ja)),
    question_count: questions.length,
    audio_path: a ? a.audio_path : null,
    audio_sha256: a ? a.audio_sha256 : null,
    audio_seconds: a ? a.audio_seconds : null,
    status,
    authored: 1,
    seed_version: null,
  };
}

/**
 * Rows of the five skill content tables of one level. accepted = skill-check accepted sets (status AI_CHECKED), excluded =
 * full excluded sets (status HOLD, ids kept so they are never reused). dictationBlanks = skill-dictation rows (lines
 * without a blank have no row). audio = loadSkillAudio map: a listening set without verified audio keeps audio_path NULL.
 * seed_version stays null here; skillRegion stamps it.
 */
export function skillRows(level, { types, lessons, accepted, excluded = [], dictationBlanks = [], audio = new Map() }) {
  if (!LEVELS.includes(level)) throw new Error(`Unknown level ${level}`);
  const levelTypes = types.filter((t) => t.level === level)
    .sort((a, b) => (a.skill === b.skill ? 0 : a.skill === 'READING' ? -1 : 1) || a.order - b.order);
  const typeByCode = new Map(levelTypes.map((t) => [t.code, t]));

  const jpath_skill_type = levelTypes.map((t) => ({
    code: t.code, level, skill: t.skill, name_vi: t.name_vi, name_ja: t.name_ja, sort_order: t.order,
    questions_in_exam: t.questions_in_exam, needs_image: t.needs_image ? 1 : 0,
    chars_min: t.passage?.chars_min ?? null, chars_max: t.passage?.chars_max ?? null,
    speakers: t.audio?.speakers ?? null, seconds_typical: t.audio?.seconds_typical ?? null, seed_version: null,
  }));

  const jpath_skill_lesson = lessons.filter((l) => typeByCode.has(l.type_code))
    .sort((a, b) => a.type_code.localeCompare(b.type_code))
    .map((l) => {
      const { answer_index: exampleAnswer, ...example } = l.worked_example ?? {};
      if (!Number.isInteger(exampleAnswer)) throw new Error(`${l.type_code}: worked example has no answer_index`);
      if (!Number.isInteger(l.check?.answer_index)) throw new Error(`${l.type_code}: check has no answer_index`);
      const a = typeByCode.get(l.type_code).skill === 'LISTENING' ? audioOf(audio, `lesson-${l.type_code}`) : null;
      return {
        type_code: l.type_code, title_vi: l.title_vi, goal_vi: l.goal_vi,
        steps_json: json(l.steps_vi ?? []), traps_json: json(l.traps_vi ?? []), tips_json: json(l.tips_vi ?? []),
        example_json: json(stable(example)), example_answer_index: exampleAnswer,
        check_question_vi: l.check.question_vi, check_options_json: json(l.check.options_vi ?? []),
        check_answer_index: l.check.answer_index, check_explanation_vi: l.check.explanation_vi,
        example_audio_path: a ? a.audio_path : null, example_audio_sha256: a ? a.audio_sha256 : null,
        status: VISIBLE_STATUS, authored: 1, seed_version: null,
      };
    });

  // sort_order = position of the set in its type, by id, over accepted and excluded together (stable when one moves).
  const all = [
    ...accepted.map((s) => ({ set: s, status: VISIBLE_STATUS })),
    ...excluded.map((s) => ({ set: s, status: HOLD })),
  ].filter(({ set }) => typeByCode.has(set.type_code));
  const seen = new Set();
  for (const { set } of all) {
    if (seen.has(set.id)) throw new Error(`Set ${set.id} is both accepted and excluded`);
    seen.add(set.id);
  }
  const byType = new Map();
  for (const entry of all) {
    if (!byType.has(entry.set.type_code)) byType.set(entry.set.type_code, []);
    byType.get(entry.set.type_code).push(entry);
  }
  const jpath_skill_set = [];
  const jpath_skill_question = [];
  const statusBySet = new Map();
  for (const t of levelTypes) {
    const entries = (byType.get(t.code) ?? []).sort((a, b) => String(a.set.id).localeCompare(String(b.set.id)));
    entries.forEach(({ set, status }, i) => {
      jpath_skill_set.push(setRow(level, set, i + 1, typeByCode, audio, status));
      statusBySet.set(set.id, status);
      (set.questions ?? []).forEach((q, n) => jpath_skill_question.push({
        code: `${set.id}-Q${n + 1}`, set_code: set.id, sort_order: n + 1, question_ja: q.question_ja,
        options_json: json(q.options ?? []), answer_index: q.answer_index, explanation_vi: q.explanation_vi ?? '',
        status, authored: 1, seed_version: null,
      }));
    });
  }

  const jpath_skill_dictation = [...dictationBlanks]
    .filter((r) => statusBySet.has(r.set))
    .sort((a, b) => a.code.localeCompare(b.code))
    .map((r) => {
      const a = audioOf(audio, `${r.set}-d${r.sort_order}`);
      return {
        code: r.code, set_code: r.set, sort_order: r.sort_order, text_ja: r.text,
        blanks_json: json(r.blanks.map((b) => ({ start: b.start, end: b.end, answer: b.answer, accept: b.accept }))),
        audio_path: a ? a.audio_path : null, audio_sha256: a ? a.audio_sha256 : null,
        status: statusBySet.get(r.set), authored: 1, seed_version: null,
      };
    });

  return { jpath_skill_type, jpath_skill_lesson, jpath_skill_set, jpath_skill_question, jpath_skill_dictation };
}

/** Values longer than their column, as `table.column code (length > max)`. */
export function checkSkillLimits(rows) {
  const out = [];
  for (const [key, max] of Object.entries(SKILL_LIMITS)) {
    const [table, col] = key.split('.');
    for (const row of rows[table] ?? []) {
      const v = row[col];
      if (typeof v === 'string' && codePointLength(v) > max) {
        out.push(`${key} ${row.code ?? row.type_code} (${codePointLength(v)} > ${max})`);
      }
    }
  }
  return out;
}

/** First 12 hex of the SHA-256 of the rows (without seed_version): changes whenever any seeded value changes. */
export function skillSeedVersion(rows) {
  const plain = Object.fromEntries(Object.entries(rows).map(([t, list]) => [t, list.map(({ seed_version, ...r }) => r)]));
  return sha256(JSON.stringify(plain)).slice(0, 12);
}

const upsert = (table, rows) => {
  const def = SKILL_TABLES[table];
  return rowsToUpsert(table, def.cols, rows, def.cols.filter((c) => !def.pk.includes(c))).trimEnd();
};

/**
 * The whole region text (BEGIN and END lines included) of one level, in one transaction: upserts in FK order, then
 * every content row of the level this seed version did not write moves to HOLD (never deleted, ids never reused).
 * `header` lines (comments) go after the BEGIN line.
 */
export function skillRegion(level, rows, { header = [] } = {}) {
  if (!LEVELS.includes(level)) throw new Error(`Unknown level ${level}`);
  const limits = checkSkillLimits(rows);
  if (limits.length) throw new Error(`${limits.length} value(s) longer than their column: ${limits.slice(0, 5).join('; ')}`);
  const version = skillSeedVersion(rows);
  const stamped = Object.fromEntries(Object.entries(rows).map(([t, list]) => [t, list.map((r) => ({ ...r, seed_version: version }))]));
  const L = sqlString(level);
  const v = sqlString(version);
  const stale = (alias) => `(${alias}.seed_version IS NULL OR ${alias}.seed_version <> ${v})`;
  const counts = Object.entries(stamped).map(([t, list]) => `${t} ${list.length}`).join(', ');
  const parts = [
    SKILL_BEGIN,
    `-- [JLAS-W0108] JLPT path skills (Đọc · Nghe) ${level} data.`,
    `-- SINH TỰ ĐỘNG, KHÔNG SỬA TAY. ${GENERATOR}`,
    `-- seed_version: ${version}`,
    ...header.map((h) => `-- ${h}`),
    `-- rows: ${counts}`,
    'SET NAMES utf8mb4;',
    'START TRANSACTION;',
    rowsToUpsert('jpath_level', ['level', 'sort_order'], [{ level, sort_order: LEVELS.indexOf(level) + 1 }], ['sort_order']).trimEnd(),
    ...Object.keys(SKILL_TABLES).map((t) => upsert(t, stamped[t])),
    `UPDATE jpath_skill_set s SET s.status = 'HOLD' WHERE s.level = ${L} AND ${stale('s')};`,
    `UPDATE jpath_skill_lesson l JOIN jpath_skill_type t ON t.code = l.type_code SET l.status = 'HOLD' WHERE t.level = ${L} AND ${stale('l')};`,
    `UPDATE jpath_skill_question q JOIN jpath_skill_set s ON s.code = q.set_code SET q.status = 'HOLD' WHERE s.level = ${L} AND ${stale('q')};`,
    `UPDATE jpath_skill_dictation d JOIN jpath_skill_set s ON s.code = d.set_code SET d.status = 'HOLD' WHERE s.level = ${L} AND ${stale('d')};`,
    'COMMIT;',
    SKILL_END,
  ];
  return parts.filter((p) => p !== '').join('\n');
}

const beginRe = new RegExp(`^${SKILL_BEGIN}\\r?$`, 'm');
const endRe = new RegExp(`^${SKILL_END}\\r?$`, 'm');

/** The jpath-skills region of a data file ({start, end, text}, BEGIN to END line included), or null. */
export function readSkillRegion(sql) {
  const b = beginRe.exec(sql);
  if (!b) return null;
  const m = endRe.exec(sql.slice(b.index));
  if (!m) throw new Error('Unclosed GENERATED jpath-skills region');
  const end = b.index + m.index + SKILL_END.length;
  return { start: b.index, end, text: sql.slice(b.index, end) };
}

/**
 * Replaces the jpath-skills region of a data file, or appends it at the end of the file when it has none. Nothing
 * else changes (the GENERATED jpath region of Plan 2 included). Keeps the file's newline style.
 */
export function replaceSkillRegion(sql, region) {
  const newline = sql.includes('\r\n') ? '\r\n' : '\n';
  const formatted = region.replace(/\r?\n/g, newline);
  const old = readSkillRegion(sql);
  if (!old) return `${sql.replace(/(\r?\n)+$/, '')}${newline}${newline}${formatted}${newline}`;
  return `${sql.slice(0, old.start)}${formatted}${sql.slice(old.end)}`;
}
