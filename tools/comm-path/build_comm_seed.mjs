// [JLAS-W0108] Phase 2: communication path + AI-call scenario seed (owner 06/11).
//
// Reads the compiled communication content (data/learning-path/communication-compiled/<GOAL>.json), the AI-call
// scenarios (data/learning-path/comm-aicall/<GOAL>.json) and the optional TTS audio manifest
// (data/learning-path/audio/comm-<GOAL>.json), then writes sql/learning/learning_data_comm.sql with two regions:
//
//   a. -- BEGIN GENERATED cpath ... -- END GENERATED cpath
//      INSERT ... ON DUPLICATE KEY UPDATE into cpath_goal / cpath_topic / cpath_situation / cpath_line / cpath_item.
//   b. -- BEGIN GENERATED cpath-aicall ... -- END GENERATED cpath-aicall
//      INSERT ... ON DUPLICATE KEY UPDATE into scenario_role / scenario, plus scenario_evaluation_rule links.
//      Only rows whose code / slug starts with "cpath-" are touched; other kaiwa rows are never changed.
//
// Idempotent: every statement uses ON DUPLICATE KEY UPDATE (or INSERT IGNORE for the evaluation-rule bridge), so the
// file can be re-run with no change in the final state. It does not connect to any DB, does not modify Java/UI, and
// does not touch learning_data_N*.sql / learning_schema.sql / jlas_schema.sql.
//
// Run: node tools/comm-path/build_comm_seed.mjs

import { readFileSync, writeFileSync, existsSync, mkdirSync } from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), '../..');
const GOALS = ['TRAVEL', 'DAILY_LIFE', 'BA', 'WORK'];
const GOAL_ORDER = { TRAVEL: 1, DAILY_LIFE: 2, BA: 3, WORK: 4 };
const LEARNER = 'リン';
const PROMPT_TEMPLATE_CODE = 'scenario_practical_v2';
// The four scoring rules the existing scenarios are linked to (see sql/seeds/ai_call_scoring_rules.sql).
const SCORING_RULE_CODES = ['grammar_accuracy', 'vocabulary_usage', 'mission_completion', 'naturalness'];
const CPATH_PREFIX = 'cpath-';
const BRSE_CATEGORY = {
  code: 'it-brse', title: 'Công việc IT (BrSE)', titleJa: 'IT・ブリッジSE',
  description: 'Trao đổi với khách hàng Nhật trong dự án phần mềm: đặc tả, tiến độ, kiểm thử và phát hành.',
  icon: 'fa-solid fa-laptop-code', displayOrder: 9,
};

const sha256 = (buf) => crypto.createHash('sha256').update(buf).digest('hex');
const seedVersionOf = (compiledBuf) => sha256(compiledBuf).slice(0, 12);

// MySQL string literal escaping (backslash escapes, single quote via \', control chars).
function esc(value) {
  if (value === null || value === undefined) return 'NULL';
  const s = String(value);
  let out = '';
  for (let i = 0; i < s.length; i++) {
    const ch = s[i];
    switch (ch) {
      case '\\': out += '\\\\'; break;
      case '\'': out += '\\\''; break;
      case '\n': out += '\\n'; break;
      case '\r': out += '\\r'; break;
      case '\0': out += '\\0'; break;
      case '\x1a': out += '\\Z'; break;
      default: out += ch;
    }
  }
  return `'${out}'`;
}

// JSON value -> minified JSON string, escaped for a JSON column. null/undefined -> NULL literal.
function json(value) {
  if (value === null || value === undefined) return 'NULL';
  return esc(JSON.stringify(value));
}

function readJson(p) {
  return JSON.parse(readFileSync(p, 'utf8'));
}

function loadAudioManifest(goal) {
  const p = path.join(ROOT, 'data/learning-path/audio', `comm-${goal}.json`);
  if (!existsSync(p)) return null;
  const list = readJson(p);
  return new Map(list.map((e) => [e.id, e]));
}

// audio_path is the part after "data/content-audio/" (same convention as the JLPT seed). NULL when the manifest is
// missing or has no entry for the id.
function audioPath(manifest, id) {
  if (!manifest) return null;
  const e = manifest.get(id);
  if (!e || !e.file) return null;
  const file = String(e.file).replace(/\\/g, '/');
  const prefix = 'data/content-audio/';
  return file.startsWith(prefix) ? file.slice(prefix.length) : file;
}

function main() {
  const out = [];
  const line = (s = '') => out.push(s);

  // ---- load everything first so we can compute seed_version from raw bytes -----------------
  const goals = [];
  const kichban = new Map();
  for (const g of GOALS) {
    const compiledPath = path.join(ROOT, 'data/learning-path/communication-compiled', `${g}.json`);
    const buf = readFileSync(compiledPath);
    goals.push({ goal: g, buf, compiled: JSON.parse(buf.toString('utf8')), seedVersion: seedVersionOf(buf) });
    kichban.set(g, readJson(path.join(ROOT, 'data/learning-path/comm-aicall', `${g}.json`)));
  }

  // ---- header -----------------------------------------------------------------------------
  const topicCount = goals.reduce((n, g) => n + g.compiled.topics.length, 0);
  const situationCount = goals.reduce((n, g) => n + g.compiled.topics.reduce((m, t) => m + t.situations.length, 0), 0);
  const lineCount = goals.reduce((n, g) => n + g.compiled.topics.reduce((m, t) => m + t.situations.reduce((k, s) => k + s.lines.length, 0), 0), 0);
  const wordCount = goals.reduce((n, g) => n + g.compiled.topics.reduce((m, t) => m + t.words.length, 0), 0);
  const phraseCount = goals.reduce((n, g) => n + g.compiled.topics.reduce((m, t) => m + t.phrases.length, 0), 0);
  const scenarioCount = [...kichban.values()].reduce((n, k) => n + k.scenarios.length, 0);
  const roleCount = dedupeRoles(kichban).length;

  line('-- =============================================================================');
  line('-- [JLAS-W0108] Communication path content + AI-call scenarios (generated seed).');
  line('-- Generated by tools/comm-path/build_comm_seed.mjs. Do not edit by hand.');
  line('--');
  line('-- Content: cpath_goal x4, cpath_topic x' + topicCount + ', cpath_situation x' + situationCount + ',');
  line('--          cpath_line x' + lineCount + ', cpath_item x' + (wordCount + phraseCount) + ' (' + wordCount + ' WORD + ' + phraseCount + ' PHRASE).');
  line('-- Kaiwa:    scenario_role x' + roleCount + ', scenario x' + scenarioCount + ' (only cpath-* codes/slugs).');
  line('--');
  line('-- seed_version per goal (first 12 chars of SHA-256 of the compiled <GOAL>.json):');
  for (const g of goals) line(`--   ${g.goal.padEnd(10)} ${g.seedVersion}`);
  line('--');
  line('-- Idempotent: ON DUPLICATE KEY UPDATE / INSERT IGNORE only. Safe to re-run.');
  line('-- Requires the base kaiwa seed (jlas_seed_all.sql) and sql/seeds/ai_call_scoring_rules.sql to be loaded first');
  line('-- (category / scenario_role / prompt_template / evaluation_rule rows are referenced by code, never by id).');
  line('-- =============================================================================');
  line('');
  line('SET NAMES utf8mb4;');
  line('START TRANSACTION;');
  line('');

  // ---- region a: cpath content ------------------------------------------------------------
  line('-- =============================================================================');
  line('-- BEGIN GENERATED cpath');
  line('-- =============================================================================');

  // cpath_goal
  line('INSERT INTO cpath_goal (code, name_vi, sort_order, fields_json, test_scenario_slug, seed_version) VALUES');
  goals.forEach((g, gi) => {
    const test = kichban.get(g.goal).scenarios.find((s) => s.goal_test);
    const fieldsJson = g.goal === 'WORK' ? buildWorkFieldsJson(g.compiled.survey) : null;
    const sep = gi < goals.length - 1 ? ',' : '';
    line(`  (${esc(g.goal)}, ${esc(g.compiled.display_name_vi)}, ${GOAL_ORDER[g.goal]}, ${json(fieldsJson)}, ${esc(test ? test.scenario.slug : null)}, ${esc(g.seedVersion)})${sep}`);
  });
  line('AS n ON DUPLICATE KEY UPDATE name_vi = n.name_vi, sort_order = n.sort_order, fields_json = n.fields_json, test_scenario_slug = n.test_scenario_slug, seed_version = n.seed_version;');
  line('');

  // cpath_topic (grouped per goal so seed_version is coherent)
  line('INSERT INTO cpath_topic (code, goal_code, sort_order, level, name_ja, name_vi, seed_version) VALUES');
  {
    const rows = [];
    for (const g of goals) for (const t of g.compiled.topics) {
      rows.push(`  (${esc(t.code)}, ${esc(g.goal)}, ${t.order}, ${esc(t.level)}, ${esc(t.name_ja)}, ${esc(t.name_vi)}, ${esc(g.seedVersion)})`);
    }
    line(rows.join(',\n'));
  }
  line('AS n ON DUPLICATE KEY UPDATE goal_code = n.goal_code, sort_order = n.sort_order, level = n.level, name_ja = n.name_ja, name_vi = n.name_vi, seed_version = n.seed_version;');
  line('');

  // cpath_situation — scenario_slug comes from the matching non-test kich-ban scenario.
  const situationSlug = new Map();
  for (const g of GOALS) for (const s of kichban.get(g).scenarios) {
    if (s.goal_test || s.situation_id == null) continue;
    situationSlug.set(s.situation_id, s.scenario.slug);
  }
  line('INSERT INTO cpath_situation (code, topic_code, sort_order, level, title_ja, title_vi, situation_vi, challenge_json, scenario_slug, seed_version) VALUES');
  {
    const rows = [];
    for (const g of goals) for (const t of g.compiled.topics) for (const s of t.situations) {
      rows.push(`  (${esc(s.id)}, ${esc(t.code)}, ${s.order}, ${esc(s.level)}, ${esc(s.title_ja)}, ${esc(s.title_vi)}, ${esc(s.situation_vi)}, ${json(s.challenge)}, ${esc(situationSlug.get(s.id) ?? null)}, ${esc(g.seedVersion)})`);
    }
    line(rows.join(',\n'));
  }
  line('AS n ON DUPLICATE KEY UPDATE topic_code = n.topic_code, sort_order = n.sort_order, level = n.level, title_ja = n.title_ja, title_vi = n.title_vi, situation_vi = n.situation_vi, challenge_json = n.challenge_json, scenario_slug = n.scenario_slug, seed_version = n.seed_version;');
  line('');

  // cpath_line
  line('INSERT INTO cpath_line (situation_code, line_order, speaker, learner, ja, furigana, vi, audio_path, seed_version) VALUES');
  {
    const rows = [];
    for (const g of goals) {
      const manifest = loadAudioManifest(g.goal);
      for (const t of g.compiled.topics) for (const s of t.situations) {
        s.lines.forEach((l, i) => {
          const learner = l.speaker === LEARNER ? 1 : 0;
          const furi = s.furigana && s.furigana[i] ? s.furigana[i] : null;
          const ap = audioPath(manifest, `${s.id}-L${l.order}`);
          rows.push(`  (${esc(s.id)}, ${l.order}, ${esc(l.speaker)}, ${learner}, ${esc(l.ja)}, ${esc(furi)}, ${esc(l.vi)}, ${esc(ap)}, ${esc(g.seedVersion)})`);
        });
      }
    }
    line(rows.join(',\n'));
  }
  line('AS n ON DUPLICATE KEY UPDATE speaker = n.speaker, learner = n.learner, ja = n.ja, furigana = n.furigana, vi = n.vi, audio_path = n.audio_path, seed_version = n.seed_version;');
  line('');

  // cpath_item (words then phrases, per topic)
  line('INSERT INTO cpath_item (code, kind, topic_code, situation_code, sort_order, ja, reading, vi, note_vi, audio_path, seed_version) VALUES');
  {
    const rows = [];
    for (const g of goals) {
      const manifest = loadAudioManifest(g.goal);
      for (const t of g.compiled.topics) {
        t.words.forEach((w, i) => {
          const ap = audioPath(manifest, w.id);
          rows.push(`  (${esc(w.id)}, 'WORD', ${esc(t.code)}, NULL, ${i + 1}, ${esc(w.word)}, ${esc(w.reading)}, ${esc(w.meaning_vi)}, NULL, ${esc(ap)}, ${esc(g.seedVersion)})`);
        });
        t.phrases.forEach((p, i) => {
          const ap = audioPath(manifest, p.id);
          rows.push(`  (${esc(p.id)}, 'PHRASE', ${esc(t.code)}, ${esc(p.dialogue_id ?? null)}, ${i + 1}, ${esc(p.ja)}, ${esc(p.reading)}, ${esc(p.vi)}, ${esc(p.note ?? null)}, ${esc(ap)}, ${esc(g.seedVersion)})`);
        });
      }
    }
    line(rows.join(',\n'));
  }
  line('AS n ON DUPLICATE KEY UPDATE kind = n.kind, topic_code = n.topic_code, situation_code = n.situation_code, sort_order = n.sort_order, ja = n.ja, reading = n.reading, vi = n.vi, note_vi = n.note_vi, audio_path = n.audio_path, seed_version = n.seed_version;');
  line('');

  line('-- END GENERATED cpath');
  line('');

  // ---- region b: cpath AI-call scenarios -------------------------------------------------
  line('-- =============================================================================');
  line('-- BEGIN GENERATED cpath-aicall');
  line('-- =============================================================================');

  // scenario_role — deduped across goals (first occurrence in GOALS order wins).
  line('-- Roles (only cpath-* codes). Same code across goals is inserted once.');
  for (const r of dedupeRoles(kichban)) {
    line(`INSERT INTO scenario_role (role_kind, code, name, description, personality, speech_style) VALUES (${esc(r.role_kind)}, ${esc(r.code)}, ${esc(r.name)}, ${esc(r.description)}, ${esc(r.personality)}, ${esc(r.speech_style)}) AS n ON DUPLICATE KEY UPDATE name = n.name, description = n.description, personality = n.personality, speech_style = n.speech_style;`);
  }
  line('');

  // The BrSE goal has its own library topic so it does not crowd the general work one; every other topic comes
  // from the base kaiwa seed. Same row as sql/migrations/075_ai_call_scenario_topics.sql.
  line('-- Library topic used only by the BA (BrSE) goal; the other topics come from the base kaiwa seed.');
  line(`INSERT INTO category (code, title, title_ja, description, icon, display_order, is_active) VALUES (${esc(BRSE_CATEGORY.code)}, ${esc(BRSE_CATEGORY.title)}, ${esc(BRSE_CATEGORY.titleJa)}, ${esc(BRSE_CATEGORY.description)}, ${esc(BRSE_CATEGORY.icon)}, ${BRSE_CATEGORY.displayOrder}, 1) AS n ON DUPLICATE KEY UPDATE title = n.title, title_ja = n.title_ja, description = n.description, icon = n.icon, display_order = n.display_order, is_active = n.is_active;`);
  line('');

  // scenario — FKs via SELECT (never a hardcoded numeric id).
  line('-- Scenarios (only cpath-* slugs). category / roles / prompt_template resolved by code.');
  for (const g of GOALS) for (const s of kichban.get(g).scenarios) {
    const sc = s.scenario;
    const category = `(SELECT id FROM category WHERE code = ${esc(s.category_code)})`;
    const aiRole = `(SELECT id FROM scenario_role WHERE role_kind = 'AI' AND code = ${esc(s.ai_role_code)})`;
    const learnerRole = `(SELECT id FROM scenario_role WHERE role_kind = 'LEARNER' AND code = ${esc(s.learner_role_code)})`;
    const prompt = `(SELECT id FROM prompt_template WHERE code = ${esc(PROMPT_TEMPLATE_CODE)})`;
    line(`INSERT INTO scenario (category_id, ai_role_id, learner_role_id, prompt_template_id, title, slug, description, jlpt_level, difficulty, estimated_minutes, background, location, time_of_day, flow_json, learning_content_json, thumbnail_url, is_active) VALUES (${category}, ${aiRole}, ${learnerRole}, ${prompt}, ${esc(sc.title)}, ${esc(sc.slug)}, ${esc(sc.description ?? null)}, ${esc(sc.jlpt_level)}, ${esc(sc.difficulty)}, ${sc.estimated_minutes}, ${esc(sc.background ?? null)}, ${esc(sc.location ?? null)}, ${esc(sc.time_of_day ?? null)}, ${json(sc.flow_json)}, ${json(sc.learning_content_json ?? null)}, NULL, 1) AS n ON DUPLICATE KEY UPDATE category_id = n.category_id, ai_role_id = n.ai_role_id, learner_role_id = n.learner_role_id, prompt_template_id = n.prompt_template_id, title = n.title, description = n.description, jlpt_level = n.jlpt_level, difficulty = n.difficulty, estimated_minutes = n.estimated_minutes, background = n.background, location = n.location, time_of_day = n.time_of_day, flow_json = n.flow_json, learning_content_json = n.learning_content_json, thumbnail_url = n.thumbnail_url, is_active = n.is_active;`);
  }
  line('');

  // scenario_evaluation_rule — link cpath scenarios to the 4 scoring rules, same way as
  // sql/seeds/ai_call_scoring_rules.sql, but limited to cpath-* slugs.
  line('-- Link cpath scenarios to the 4 scoring rules (grammar/vocab/mission/natural), idempotent.');
  line('INSERT IGNORE INTO scenario_evaluation_rule (scenario_id, rule_id)');
  line('SELECT s.id, r.id FROM scenario s');
  line(`JOIN evaluation_rule r ON r.code IN (${SCORING_RULE_CODES.map(esc).join(', ')})`);
  line(`WHERE s.slug LIKE '${CPATH_PREFIX}%';`);
  line('');

  line('-- END GENERATED cpath-aicall');
  line('');
  line('COMMIT;');
  line('');

  const target = path.join(ROOT, 'sql/learning/parts', 'learning_data_comm.sql');
  mkdirSync(path.dirname(target), { recursive: true });
  writeFileSync(target, out.join('\n'));
  console.log(`wrote ${target}`);
  console.log(`content: ${goals.length} goals, ${topicCount} topics, ${situationCount} situations, ${lineCount} lines, ${wordCount + phraseCount} items (${wordCount} WORD + ${phraseCount} PHRASE)`);
  console.log(`kaiwa: ${roleCount} roles, ${scenarioCount} scenarios`);
}

// WORK goal: fields_json = [{code, labelVi, topicCodes}] from the survey options that carry `topicCodes`.
function buildWorkFieldsJson(survey) {
  const fields = [];
  for (const q of survey ?? []) {
    for (const opt of q.options ?? []) {
      if (Array.isArray(opt.topicCodes) && opt.topicCodes.length) {
        fields.push({ code: opt.code, labelVi: opt.label, topicCodes: opt.topicCodes });
      }
    }
  }
  return fields.length ? fields : null;
}

// Dedupe roles by (role_kind, code); first occurrence in GOALS order wins.
function dedupeRoles(kichban) {
  const seen = new Map();
  for (const g of GOALS) for (const r of kichban.get(g).roles ?? []) {
    const key = `${r.role_kind}|${r.code}`;
    if (!seen.has(key)) seen.set(key, r);
  }
  return [...seen.values()];
}

main();
