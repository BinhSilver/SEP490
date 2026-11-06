// [JLAS-W0108] Independent verifier for sql/learning/learning_data_comm.sql (owner 06/11).
//
// Re-reads the source (compiled content + kich-ban scenarios) and the generated SQL, then checks:
//   1. row counts per table match the source (4 goals / 35 topics / 106 situations / all lines / all items),
//   2. every content row's values match the source exactly (no escaping/ordering bug),
//   3. no duplicate codes/slugs and all foreign keys resolve,
//   4. no value is longer than its column in target/cpath-schema.sql (and jlas_schema.sql for the kaiwa tables),
//   5. the file is under 20 MB and contains the two region markers.
//
// Run: node tools/comm-path/verify_comm_seed.mjs

import { readFileSync, existsSync } from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), '../..');
const GOALS = ['TRAVEL', 'DAILY_LIFE', 'BA', 'WORK'];
const GOAL_ORDER = { TRAVEL: 1, DAILY_LIFE: 2, BA: 3, WORK: 4 };
const LEARNER = 'リン';
const VALID_CATEGORY_CODES = ['food-drink', 'introduction', 'daily-life', 'transport', 'shopping', 'health-service', 'work-study', 'travel'];
const SCORING_RULE_CODES = ['grammar_accuracy', 'vocabulary_usage', 'mission_completion', 'naturalness'];

const SQL_FILE = path.join(ROOT, 'sql/learning', 'learning_data_comm.sql');
const SCHEMA_FILE = path.join(ROOT, 'target', 'cpath-schema.sql');
const KAIWA_SCHEMA_FILE = path.join(ROOT, 'jlas_schema.sql');
const MAX_FILE_BYTES = 20 * 1024 * 1024;

const failures = [];
const checks = [];
function check(name, ok, detail = '') {
  checks.push(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ' — ' + detail : ''}`);
  if (!ok) failures.push(name);
}
const sha256 = (buf) => crypto.createHash('sha256').update(buf).digest('hex');
const readJson = (p) => JSON.parse(readFileSync(p, 'utf8'));

// ---------------------------------------------------------------------------
// SQL tokenizer (enough for our generated file: '...' strings with \-escapes,
// integers, NULL, and (SELECT ...) subqueries as opaque tokens).
// ---------------------------------------------------------------------------
function decodeSqlString(raw) {
  // raw includes surrounding quotes and \ escapes.
  let out = '';
  for (let i = 1; i < raw.length - 1; i++) {
    const ch = raw[i];
    if (ch === '\\') {
      const nx = raw[i + 1];
      out += ({ n: '\n', r: '\r', 0: '\0', Z: '\x1a', t: '\t', '\\': '\\', "'": "'" })[nx] ?? nx;
      i++;
    } else out += ch;
  }
  return out;
}

function parseParen(sql, openIdx) {
  let i = openIdx + 1;
  const items = [];
  let current = '';
  let depth = 0;
  while (i < sql.length) {
    const ch = sql[i];
    if (ch === "'") {
      const start = i;
      i++;
      while (i < sql.length && !(sql[i] === "'" && sql[i - 1] !== '\\')) i++;
      i++; // closing quote
      current += sql.slice(start, i);
      continue;
    }
    if (ch === '(') { depth++; current += ch; i++; continue; }
    if (ch === ')') {
      if (depth === 0) { items.push(current.trim()); return [items, i + 1]; }
      depth--; current += ch; i++; continue;
    }
    if (ch === ',' && depth === 0) { items.push(current.trim()); current = ''; i++; continue; }
    current += ch; i++;
  }
  throw new Error('unterminated parenthesis in SQL');
}

function decodeValue(raw) {
  const t = raw.trim();
  if (t === 'NULL') return { kind: 'null', value: null };
  if (t.startsWith("'")) return { kind: 'string', value: decodeSqlString(t) };
  if (t.startsWith('(')) return { kind: 'expr', raw: t };
  if (/^-?\d+$/.test(t)) return { kind: 'number', value: Number(t) };
  return { kind: 'raw', value: t };
}

// Extract every `INSERT INTO <table> (<cols>) VALUES ...;` as {table, columns, rows}.
function parseInserts(sql) {
  const inserts = [];
  const re = /INSERT\s+INTO\s+`?([a-zA-Z0-9_]+)`?\s*\(([^)]*)\)\s*VALUES\s*/g;
  let m;
  while ((m = re.exec(sql)) !== null) {
    const table = m[1];
    const columns = m[2].split(',').map((c) => c.trim());
    const valuesStart = re.lastIndex;
    // Parse rows until `AS n` / `ON DUPLICATE` / `;`.
    let i = valuesStart;
    const rows = [];
    for (;;) {
      while (i < sql.length && /\s/.test(sql[i])) i++;
      if (i >= sql.length) break;
      if (sql.startsWith('AS n', i) || sql.startsWith('ON DUPLICATE', i)) break;
      if (sql[i] === ',') { i++; continue; }
      if (sql[i] === ';') break;
      if (sql[i] !== '(') throw new Error(`expected '(' at ${i} for ${table}, got "${sql[i]}"`);
      const [items, next] = parseParen(sql, i);
      rows.push(items.map(decodeValue));
      i = next;
    }
    inserts.push({ table, columns, rows });
  }
  return inserts;
}

function rowToObj(insert, row) {
  const o = {};
  insert.columns.forEach((c, i) => { o[c] = row[i]; });
  return o;
}

// ---------------------------------------------------------------------------
// Column lengths from a CREATE TABLE schema (VARCHAR(n) only).
// ---------------------------------------------------------------------------
function varcharLengths(schemaText) {
  const map = new Map();
  const tableRe = /CREATE TABLE\s+(?:IF NOT EXISTS\s+)?`?([a-zA-Z0-9_]+)`?/g;
  let m;
  while ((m = tableRe.exec(schemaText)) !== null) {
    const table = m[1];
    const blockStart = schemaText.indexOf('(', m.index);
    // crude: find matching close paren by depth
    let depth = 0, blockEnd = blockStart;
    for (let i = blockStart; i < schemaText.length; i++) {
      if (schemaText[i] === '(') depth++;
      else if (schemaText[i] === ')') { depth--; if (depth === 0) { blockEnd = i; break; } }
    }
    const block = schemaText.slice(blockStart, blockEnd);
    const cols = new Map();
    const colRe = /`?([a-zA-Z0-9_]+)`?\s+VARCHAR\((\d+)\)/g;
    let cm;
    while ((cm = colRe.exec(block)) !== null) cols.set(cm[1], Number(cm[2]));
    map.set(table, cols);
  }
  return map;
}

// ---------------------------------------------------------------------------
// Load source
// ---------------------------------------------------------------------------
const compiled = {};
const kichban = {};
for (const g of GOALS) {
  const p = path.join(ROOT, 'data/learning-path/communication-compiled', `${g}.json`);
  compiled[g] = { buf: readFileSync(p), data: readJson(p) };
  kichban[g] = readJson(path.join(ROOT, 'target/ai-moi-kich-ban/out', `${g}.json`));
}

const expected = buildExpected();

function buildExpected() {
  const goals = new Map();
  const topics = new Map();
  const situations = new Map();
  const lines = new Map();
  const items = new Map();
  for (const g of GOALS) {
    const sv = sha256(compiled[g].buf).slice(0, 12);
    const test = kichban[g].scenarios.find((s) => s.goal_test);
    goals.set(g, {
      code: g, name_vi: compiled[g].data.display_name_vi, sort_order: GOAL_ORDER[g],
      fields_json: g === 'WORK' ? workFields(compiled[g].data.survey) : null,
      test_scenario_slug: test ? test.scenario.slug : null, seed_version: sv,
    });
    for (const t of compiled[g].data.topics) {
      topics.set(t.code, { code: t.code, goal_code: g, sort_order: t.order, level: t.level, name_ja: t.name_ja, name_vi: t.name_vi, seed_version: sv });
      for (const s of t.situations) {
        const slug = slugFor(kichban[g], s.id);
        situations.set(s.id, { code: s.id, topic_code: t.code, sort_order: s.order, level: s.level, title_ja: s.title_ja, title_vi: s.title_vi, situation_vi: s.situation_vi, challenge_json: s.challenge ? JSON.stringify(s.challenge) : null, scenario_slug: slug, seed_version: sv });
        s.lines.forEach((l, i) => {
          const furi = s.furigana && s.furigana[i] ? s.furigana[i] : null;
          lines.set(`${s.id}|${l.order}`, { situation_code: s.id, line_order: l.order, speaker: l.speaker, learner: l.speaker === LEARNER ? 1 : 0, ja: l.ja, furigana: furi, vi: l.vi, seed_version: sv });
        });
      }
      t.words.forEach((w, i) => items.set(w.id, { code: w.id, kind: 'WORD', topic_code: t.code, situation_code: null, sort_order: i + 1, ja: w.word, reading: w.reading, vi: w.meaning_vi, note_vi: null, seed_version: sv }));
      t.phrases.forEach((p, i) => items.set(p.id, { code: p.id, kind: 'PHRASE', topic_code: t.code, situation_code: p.dialogue_id ?? null, sort_order: i + 1, ja: p.ja, reading: p.reading, vi: p.vi, note_vi: p.note ?? null, seed_version: sv }));
    }
  }
  return { goals, topics, situations, lines, items };
}
function slugFor(kich, situationId) {
  const s = kich.scenarios.find((x) => !x.goal_test && x.situation_id === situationId);
  return s ? s.scenario.slug : null;
}
function workFields(survey) {
  const fields = [];
  for (const q of survey ?? []) for (const opt of q.options ?? []) {
    if (Array.isArray(opt.topicCodes) && opt.topicCodes.length) fields.push({ code: opt.code, labelVi: opt.label, topicCodes: opt.topicCodes });
  }
  return fields.length ? fields : null;
}

// ---------------------------------------------------------------------------
// Load generated SQL and parse
// ---------------------------------------------------------------------------
const sqlText = readFileSync(SQL_FILE, 'utf8');
const inserts = parseInserts(sqlText);
const byTable = {};
for (const ins of inserts) (byTable[ins.table] = byTable[ins.table] || []).push(ins);
const rowsOf = (table) => (byTable[table] || []).flatMap((ins) => ins.rows.map((r) => rowToObj(ins, r)));

const schemaVarchar = varcharLengths(readFileSync(SCHEMA_FILE, 'utf8'));
const kaiwaVarchar = varcharLengths(readFileSync(KAIWA_SCHEMA_FILE, 'utf8'));

// ---------------------------------------------------------------------------
// 0. structural / size checks
// ---------------------------------------------------------------------------
check('file under 20 MB', readFileSync(SQL_FILE).length < MAX_FILE_BYTES, `${(readFileSync(SQL_FILE).length / 1024 / 1024).toFixed(2)} MB`);
check('region cpath markers present', sqlText.includes('-- BEGIN GENERATED cpath') && sqlText.includes('-- END GENERATED cpath'));
check('region cpath-aicall markers present', sqlText.includes('-- BEGIN GENERATED cpath-aicall') && sqlText.includes('-- END GENERATED cpath-aicall'));
check('no USE statement', !/^\s*USE\s+/m.test(sqlText));
check('no client mysql command', !/^\s*mysql\s/m.test(sqlText));

// ---------------------------------------------------------------------------
// 1. counts
// ---------------------------------------------------------------------------
const counts = (table) => rowsOf(table).length;
check('cpath_goal = 4', counts('cpath_goal') === 4, `got ${counts('cpath_goal')}`);
check('cpath_topic = 35', counts('cpath_topic') === 35, `got ${counts('cpath_topic')}`);
check('cpath_situation = 106', counts('cpath_situation') === 106, `got ${counts('cpath_situation')}`);
check('cpath_line = 722', counts('cpath_line') === 722, `got ${counts('cpath_line')}`);
check('cpath_item = 1185', counts('cpath_item') === 1185, `got ${counts('cpath_item')}`);
check('scenario_role = 57 (cpath-)', counts('scenario_role') === 57, `got ${counts('scenario_role')}`);
check('scenario = 110 (cpath-)', counts('scenario') === 110, `got ${counts('scenario')}`);

// ---------------------------------------------------------------------------
// 2. content value reconciliation + length + FK + duplicate checks
// ---------------------------------------------------------------------------
function s(decoded) { return decoded && decoded.kind === 'string' ? decoded.value : (decoded && decoded.kind === 'number' ? decoded.value : decoded && decoded.kind === 'null' ? null : decoded ? (decoded.raw ?? decoded.value) : undefined); }

function assertColLen(table, col, value) {
  const map = schemaVarchar.get(table) || kaiwaVarchar.get(table);
  if (!map || !(col in map)) return true;
  if (value == null) return true;
  return String(value).length <= map[col];
}

// goals
{
  const rows = rowsOf('cpath_goal');
  const seen = new Set();
  for (const r of rows) {
    const e = expected.goals.get(s(r.code));
    if (!e) { check(`cpath_goal ${s(r.code)} known`, false); continue; }
    if (seen.has(s(r.code))) { check(`cpath_goal ${s(r.code)} unique`, false); }
    seen.add(s(r.code));
    const okName = s(r.name_vi) === e.name_vi;
    const okOrder = s(r.sort_order) === e.sort_order;
    const okSlug = s(r.test_scenario_slug) === e.test_scenario_slug;
    const okSv = s(r.seed_version) === e.seed_version;
    const okFields = (s(r.fields_json) ?? null) === (e.fields_json ? JSON.stringify(e.fields_json) : null);
    if (!okName || !okOrder || !okSlug || !okSv || !okFields) {
      check(`cpath_goal ${e.code} matches source`, false, JSON.stringify({ name: okName, order: okOrder, slug: okSlug, seed: okSv, fields: okFields }));
    }
  }
  check('cpath_goal all present & unique', seen.size === 4 && rows.length === 4, `unique=${seen.size}`);
}

// topics
{
  const rows = rowsOf('cpath_topic');
  const seen = new Set();
  for (const r of rows) {
    const code = s(r.code); const e = expected.topics.get(code);
    if (!e) { check(`cpath_topic ${code} known`, false); continue; }
    seen.add(code);
    const ok = ['goal_code', 'sort_order', 'level', 'name_ja', 'name_vi', 'seed_version'].every((k) => s(r[k]) === e[k]);
    if (!ok) check(`cpath_topic ${code} matches source`, false);
  }
  check('cpath_topic unique & complete', seen.size === 35, `unique=${seen.size}`);
}

// situations
{
  const rows = rowsOf('cpath_situation');
  const seen = new Set();
  const slugs = new Map();
  for (const r of rows) {
    const code = s(r.code); const e = expected.situations.get(code);
    if (!e) { check(`cpath_situation ${code} known`, false); continue; }
    seen.add(code);
    const ok = ['topic_code', 'sort_order', 'level', 'title_ja', 'title_vi', 'situation_vi', 'scenario_slug', 'seed_version'].every((k) => s(r[k]) === e[k]);
    const okChallenge = s(r.challenge_json) === e.challenge_json;
    if (!ok || !okChallenge) check(`cpath_situation ${code} matches source`, false);
    const slug = s(r.scenario_slug);
    if (slug != null) {
      if (slugs.has(slug)) check(`cpath_situation scenario_slug ${slug} unique`, false);
      slugs.set(slug, code);
    }
  }
  check('cpath_situation unique & complete', seen.size === 106, `unique=${seen.size}`);
  check('cpath_situation scenario_slug all unique', slugs.size === 106, `${slugs.size} slugs`);
}

// lines
{
  const rows = rowsOf('cpath_line');
  const seen = new Set();
  for (const r of rows) {
    const key = `${s(r.situation_code)}|${s(r.line_order)}`; const e = expected.lines.get(key);
    if (!e) { check(`cpath_line ${key} known`, false); continue; }
    seen.add(key);
    const ok = ['speaker', 'learner', 'ja', 'furigana', 'vi', 'seed_version'].every((k) => s(r[k]) === e[k]);
    if (!ok) check(`cpath_line ${key} matches source`, false);
    // audio_path must be NULL while manifest is absent
    if (s(r.audio_path) !== null) check(`cpath_line ${key} audio_path NULL`, false, `got ${s(r.audio_path)}`);
  }
  check('cpath_line unique & complete', seen.size === 722, `unique=${seen.size}`);
}

// items
{
  const rows = rowsOf('cpath_item');
  const seen = new Set();
  let words = 0, phrases = 0;
  for (const r of rows) {
    const code = s(r.code); const e = expected.items.get(code);
    if (!e) { check(`cpath_item ${code} known`, false); continue; }
    seen.add(code);
    if (e.kind === 'WORD') words++; else phrases++;
    const ok = ['kind', 'topic_code', 'situation_code', 'sort_order', 'ja', 'reading', 'vi', 'note_vi', 'seed_version'].every((k) => s(r[k]) === e[k]);
    if (!ok) check(`cpath_item ${code} matches source`, false);
    if (s(r.audio_path) !== null) check(`cpath_item ${code} audio_path NULL`, false, `got ${s(r.audio_path)}`);
  }
  check('cpath_item unique & complete', seen.size === 1185, `unique=${seen.size}`);
  check('cpath_item kind split 880/305', words === 880 && phrases === 305, `WORD=${words} PHRASE=${phrases}`);
}

// ---------------------------------------------------------------------------
// 3. length checks (content tables vs cpath-schema.sql)
// ---------------------------------------------------------------------------
{
  const stringCols = { cpath_goal: ['code', 'name_vi', 'test_scenario_slug', 'seed_version'], cpath_topic: ['code', 'goal_code', 'level', 'name_ja', 'name_vi', 'seed_version'], cpath_situation: ['code', 'topic_code', 'level', 'title_ja', 'title_vi', 'situation_vi', 'scenario_slug', 'seed_version'], cpath_line: ['situation_code', 'speaker', 'ja', 'furigana', 'vi', 'audio_path', 'seed_version'], cpath_item: ['code', 'kind', 'topic_code', 'situation_code', 'ja', 'reading', 'vi', 'note_vi', 'audio_path', 'seed_version'] };
  let bad = [];
  for (const [table, cols] of Object.entries(stringCols)) {
    for (const r of rowsOf(table)) for (const c of cols) {
      if (!assertColLen(table, c, s(r[c]))) bad.push(`${table}.${c} len ${String(s(r[c])).length}`);
    }
  }
  check('no content value exceeds column length', bad.length === 0, bad.slice(0, 5).join(', ') || 'all within limits');
}

// ---------------------------------------------------------------------------
// 4. kaiwa checks: roles + scenarios + eval rules
// ---------------------------------------------------------------------------
{
  const roles = rowsOf('scenario_role');
  const roleKeys = new Set();
  const roleNames = new Map();
  for (const r of roles) {
    const kind = s(r.role_kind), code = s(r.code);
    const key = `${kind}|${code}`;
    if (roleKeys.has(key)) check(`scenario_role ${key} unique`, false);
    roleKeys.add(key);
    roleNames.set(key, { name: s(r.name), description: s(r.description), personality: s(r.personality), speech_style: s(r.speech_style) });
    if (!code.startsWith('cpath-')) check(`scenario_role code ${code} is cpath-`, false);
    if (!assertColLen('scenario_role', 'code', code) || !assertColLen('scenario_role', 'name', s(r.name))) check(`scenario_role ${code} within length`, false);
  }

  const scenarios = rowsOf('scenario');
  const slugs = new Set();
  let goalTest = 0;
  for (const sc of scenarios) {
    const slug = s(sc.slug);
    if (slugs.has(slug)) check(`scenario slug ${slug} unique`, false);
    slugs.add(slug);
    if (!slug.startsWith('cpath-')) check(`scenario slug ${slug} is cpath-`, false);
    // extract codes from opaque subquery tokens
    const catCode = extractCode(sc.category_id);
    const aiRole = extractCode(sc.ai_role_id);
    const learnerRole = extractCode(sc.learner_role_id);
    const promptCode = extractCode(sc.prompt_template_id);
    if (!VALID_CATEGORY_CODES.includes(catCode)) check(`scenario ${slug} valid category`, false, `got ${catCode}`);
    if (aiRole && !roleKeys.has(`AI|${aiRole}`)) check(`scenario ${slug} ai_role exists`, false, aiRole);
    if (learnerRole && !roleKeys.has(`LEARNER|${learnerRole}`)) check(`scenario ${slug} learner_role exists`, false, learnerRole);
    if (promptCode !== 'scenario_practical_v2') check(`scenario ${slug} prompt_template ok`, false, `got ${promptCode}`);
    if (!/^(N5|N4|N3|N2|N1)$/.test(s(sc.jlpt_level))) check(`scenario ${slug} jlpt_level valid`, false, s(sc.jlpt_level));
    if (!(s(sc.estimated_minutes) > 0)) check(`scenario ${slug} estimated_minutes>0`, false);
    if (s(sc.slug) === null) { }
    if (slug.startsWith('cpath-test-')) goalTest++;
    // JSON columns must parse
    try { JSON.parse(s(sc.flow_json)); } catch { check(`scenario ${slug} flow_json valid JSON`, false); }
    if (s(sc.learning_content_json) != null) { try { JSON.parse(s(sc.learning_content_json)); } catch { check(`scenario ${slug} learning_content_json valid JSON`, false); } }
    for (const c of ['slug', 'title', 'jlpt_level', 'difficulty', 'location', 'time_of_day']) if (!assertColLen('scenario', c, s(sc[c]))) check(`scenario ${slug} ${c} within length`, false);
  }
  check('scenario slugs unique & cpath-', slugs.size === 110, `unique=${slugs.size}`);
  check('scenario has 4 cpath-test-* (goal tests)', goalTest === 4, `got ${goalTest}`);

  // evaluation rule bridge statement
  const evalOk = /INSERT\s+IGNORE\s+INTO\s+scenario_evaluation_rule[\s\S]*?JOIN\s+evaluation_rule\s+r\s+ON\s+r\.code\s+IN\s*\([^)]*grammar_accuracy[^)]*\)[\s\S]*?WHERE\s+s\.slug\s+LIKE\s+'cpath-%'/.test(sqlText);
  check('scenario_evaluation_rule links cpath- scenarios to 4 scoring rules', evalOk);
  const allFour = SCORING_RULE_CODES.every((c) => sqlText.includes(`'${c}'`));
  check('all 4 scoring rule codes referenced', allFour);

  // cross-check scenario slugs against expected situation->slug and test slugs
  const expectedSlugs = new Set();
  for (const g of GOALS) for (const sc of kichban[g].scenarios) expectedSlugs.add(sc.scenario.slug);
  check('scenario slug set matches kich-ban', [...slugs].sort().join('|') === [...expectedSlugs].sort().join('|'));
}

function extractCode(decoded) {
  if (!decoded) return null;
  if (decoded.kind === 'expr') {
    const m = decoded.raw.match(/code\s*=\s*'([^']*)'/);
    return m ? m[1] : null;
  }
  if (decoded.kind === 'string') return decoded.value;
  return null;
}

// ---------------------------------------------------------------------------
// summary
// ---------------------------------------------------------------------------
console.log(checks.join('\n'));
console.log('\n---');
if (failures.length === 0) {
  console.log('ALL CHECKS PASSED');
  process.exit(0);
} else {
  console.log(`${failures.length} CHECK(S) FAILED:`);
  failures.forEach((f) => console.log('  - ' + f));
  process.exit(1);
}
