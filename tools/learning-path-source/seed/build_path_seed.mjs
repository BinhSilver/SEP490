// [JLAS-W0108] JLPT path seed generator, part 3: the CLI that writes one level's jpath content into the GENERATED jpath
// region of sql/learning/learning_data_<L>.sql (owner 2026-11-01: all Learning SQL lives in sql/learning/; the base data
// — levels, kana — is the seed-sql.mjs block of learning_data_common.sql).
// Run: node tools/learning-path-source/seed/build_path_seed.mjs --level N5 [--check] [--audio-root <dir>]
// Exit 0 = written / current, 1 = error (limits, size, stale with --check), 2 = would unrelease a released level (no write).
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { LEVELS, LEVEL_RANK, ROOT, argValue, sha256 } from '../compile/util.mjs';
import { learningDataFile } from '../../learning-data-extract/learning_data_files.mjs';
import { TABLES, applyEdits, buildLevelRows, checkLimits, levelContentSql, loadAudio, releaseVerdict } from './seed-content.mjs';
import { BASE_MARKER, renderBaseBlock } from './seed-sql.mjs';

export const REGION_BEGIN = '-- BEGIN GENERATED jpath';
export const REGION_END = '-- END GENERATED jpath';
export const MAX_REGION_BYTES = 20 * 1024 * 1024;
export const MAX_FILE_BYTES = 50 * 1024 * 1024; // per Learning data file (verify_learning_sql.mjs)
const GENERATOR = 'tools/learning-path-source/seed/build_path_seed.mjs';
const VISIBLE = new Set(['REVIEWED', 'AI_CHECKED']);

/** The GENERATED jpath region of a data file: `text` from its BEGIN line to its END line (both included). */
export function readRegion(sql) {
  const begin = new RegExp(`^${REGION_BEGIN}\\r?$`, 'm').exec(sql);
  if (!begin) return null;
  const end = new RegExp(`^${REGION_END}\\r?$`, 'm');
  const rest = sql.slice(begin.index);
  const m = end.exec(rest);
  if (!m) return null;
  const stop = begin.index + m.index + REGION_END.length;
  return { start: begin.index, end: stop, text: sql.slice(begin.index, stop) };
}

const rel = (file) => path.relative(ROOT, file).split(path.sep).join('/');
const resolve = (p) => path.resolve(ROOT, p);
const readIf = (file) => (existsSync(file) ? readFileSync(file) : null);

/**
 * Builds (or with check=true compares) the jpath region of one level. Inputs: the compiled level and its lower levels,
 * the audio manifest checked against `audioRoot`, and the admin edits file. seedVersion = first 12 hex of the SHA-256 of
 * the final rows (after edits), so it changes whenever any seeded value changes.
 */
export function buildSeed(level, {
  compiledDir = 'data/learning-path/compiled', audioDir = 'data/learning-path/audio', audioRoot = 'data/content-audio',
  editsDir = 'data/learning-path/edits', outDir = 'sql/learning', reportDir = 'target', check = false,
} = {}) {
  if (!LEVELS.includes(level)) throw new Error(`Unknown JLPT level ${level}`);
  const errors = [];
  const fail = (exitCode, report, ...msg) => ({ ok: false, exitCode, files: [], report, errors: [...errors, ...msg] });

  const compiledFile = path.join(resolve(compiledDir), `${level}.json`);
  const compiledBytes = readIf(compiledFile);
  if (!compiledBytes) return fail(1, null, `${rel(compiledFile)} does not exist`);
  const compiled = JSON.parse(compiledBytes.toString('utf8'));
  if (compiled.level !== level) return fail(1, null, `${rel(compiledFile)} holds level ${compiled.level}`);
  // owner 05/11: situation questions written outside the compile step join the level's grammar here
  const situationFile = path.join(resolve(compiledDir), '..', 'situation', `${level}.json`);
  const situationBytes = readIf(situationFile);
  if (situationBytes) {
    const byId = JSON.parse(situationBytes.toString('utf8'));
    for (const g of compiled.grammar ?? []) if (Array.isArray(byId[g.id])) g.situation_bank = byId[g.id];
  }

  // Every compiled level: lower levels feed cross-level pairs and compounds, all of them name the codes that exist.
  const sources = new Map();
  for (const l of LEVELS) {
    if (l === level) { sources.set(l, { compiled, bytes: compiledBytes }); continue; }
    const bytes = readIf(path.join(resolve(compiledDir), `${l}.json`));
    if (bytes) sources.set(l, { compiled: JSON.parse(bytes.toString('utf8')), bytes });
  }
  const knownCodes = new Map();
  for (const [l, { compiled: c }] of sources) for (const item of [...(c.vocabulary ?? []), ...(c.kanji ?? [])]) knownCodes.set(item.id, l);
  const lower = [...sources.entries()].filter(([l]) => LEVEL_RANK[l] < LEVEL_RANK[level]);
  const lowerLevels = lower.map(([, s]) => s.compiled);

  const manifestFile = path.join(resolve(audioDir), `${level}.json`);
  const manifestBytes = readIf(manifestFile);
  const audio = manifestBytes ? loadAudio(JSON.parse(manifestBytes.toString('utf8')), audioRoot) : new Map();
  const audioOk = [...audio.values()].filter((a) => a.ok).length;

  const editsFile = path.join(resolve(editsDir), `${level}.json`);
  const editsBytes = readIf(editsFile);
  const editsJson = editsBytes ? JSON.parse(editsBytes.toString('utf8')) : null;
  const edits = Array.isArray(editsJson) ? editsJson : editsJson?.edits ?? [];

  const built = buildLevelRows(compiled, { seedVersion: '', audio, knownCodes, lowerLevels });
  const { tables, applied, skipped } = applyEdits(built, edits);

  const inputs = {
    compiled: { file: rel(compiledFile), sha256: sha256(compiledBytes) },
    audio: manifestBytes
      ? { file: rel(manifestFile), sha256: sha256(manifestBytes), items: audio.size, ok: audioOk }
      : { file: 'none', sha256: 'none', items: 0, ok: 0 },
    edits: editsBytes
      ? { file: rel(editsFile), sha256: sha256(editsBytes), applied, skipped: skipped.length }
      : { file: 'none', sha256: 'none', applied: 0, skipped: 0 },
    lower_levels: lower.map(([l, s]) => ({ level: l, sha256: sha256(s.bytes) })),
  };
  const rows = Object.fromEntries(Object.entries(TABLES).map(([key, def]) => [def.sql, tables[key].length]));
  const counts = {
    accept_json: tables.grammar_question.filter((q) => q.accept_json !== null).length,
    vocabulary_audio: tables.vocabulary.filter((v) => v.audio_path !== null).length,
    example_audio: tables.example.filter((e) => e.audio_path !== null).length,
  };

  const limits = checkLimits(tables);
  if (limits.length) {
    const report = { level, seed_version: null, inputs, rows, counts, release: null, limits };
    return fail(1, report, `${limits.length} value(s) longer than their column`);
  }

  const seedVersion = sha256(JSON.stringify(tables)).slice(0, 12);
  for (const [key, def] of Object.entries(TABLES)) if (def.cc) for (const row of tables[key]) row.seed_version = seedVersion;
  const visibleVocab = new Set(lowerLevels.flatMap((c) => c.vocabulary ?? []).filter((v) => VISIBLE.has(v.status)).map((v) => v.id));
  // Owner 05/11 level caps: a kanji whose compounds all went to Mở rộng (HOLD, at this or a lower level) has no word
  // in the path either — it is checked like a kanji without words, not as a missing compound.
  const visibleHere = new Set([...visibleVocab, ...(compiled.vocabulary ?? []).filter((v) => VISIBLE.has(v.status)).map((v) => v.id)]);
  const kanjiWithoutWords = new Set((compiled.kanji ?? [])
    .filter((k) => !(k.compound_ids ?? []).some((id) => visibleHere.has(id))).map((k) => k.id));
  const verdict = releaseVerdict(level, tables, { visibleVocab, kanjiWithoutWords });
  const release = { released: verdict.released, reasons: verdict.reasons, note: verdict.note };

  const header = [
    `-- [JLAS-W0108] JLPT path v2 (jpath) ${level} data.`,
    `-- SINH TỰ ĐỘNG, KHÔNG SỬA TAY. ${GENERATOR}`,
    `-- seed_version: ${seedVersion}`,
    `-- compiled: ${inputs.compiled.file} sha256 ${inputs.compiled.sha256}`,
    `-- audio: ${inputs.audio.file === 'none' ? 'none' : `${inputs.audio.file} sha256 ${inputs.audio.sha256} (ok ${audioOk}/${audio.size})`}`,
    `-- edits: ${inputs.edits.file === 'none' ? 'none' : `${inputs.edits.file} sha256 ${inputs.edits.sha256}`}`,
    `-- lower levels: ${inputs.lower_levels.length ? inputs.lower_levels.map((l) => `${l.level} sha256 ${l.sha256}`).join(', ') : 'none'}`,
  ];
  const body = levelContentSql(level, tables, { seedVersion, verdict, header });
  const region = `${REGION_BEGIN}\n${body}${REGION_END}`;
  const report = { level, seed_version: seedVersion, inputs, rows, counts, release };

  const outFile = path.join(resolve(outDir), path.basename(learningDataFile(ROOT, level)));
  const current = readIf(outFile)?.toString('utf8');
  if (current === undefined) return fail(1, report, `${rel(outFile)} does not exist`);
  const old = readRegion(current);
  if (!old) return fail(1, report, `${rel(outFile)} has no GENERATED jpath region`);
  const newline = current.includes('\r\n') ? '\r\n' : '\n';
  const next = `${current.slice(0, old.start)}${region.replace(/\n/g, newline)}${current.slice(old.end)}`;

  if (new RegExp(`^-- release: ${level} released=1\\r?$`, 'm').test(old.text) && !verdict.released) {
    return fail(2, report, `${level} is released in ${rel(outFile)} but the new content is not releasable (${verdict.note}); nothing written`);
  }
  const regionBytes = Buffer.byteLength(region);
  if (regionBytes > MAX_REGION_BYTES) errors.push(`jpath ${level} region is ${regionBytes} bytes, above 20 MB`);
  if (Buffer.byteLength(next) > MAX_FILE_BYTES) errors.push(`${rel(outFile)} would be above 50 MiB`);
  if (errors.length) return fail(1, report);

  // Levels above this one read its items for their cross-level pairs: a generated higher region must be rebuilt.
  report.rebuild_higher = LEVELS.filter((l) => LEVEL_RANK[l] > LEVEL_RANK[level]).filter((l) => {
    const text = readIf(path.join(resolve(outDir), path.basename(learningDataFile(ROOT, l))))?.toString('utf8');
    const r = text && readRegion(text);
    return Boolean(r && /^-- seed_version: /m.test(r.text) && !r.text.includes(`${level} sha256 ${inputs.compiled.sha256}`));
  });

  if (check) {
    if (next !== current) return fail(1, report, `${rel(outFile)}: jpath ${level} region is stale`);
    return { ok: true, exitCode: 0, files: [outFile], report, errors };
  }
  if (next !== current) writeFileSync(outFile, next);
  const reportFile = path.join(resolve(reportDir), `jpath-seed-report-${level}.json`);
  mkdirSync(path.dirname(reportFile), { recursive: true });
  writeFileSync(reportFile, `${JSON.stringify(report, null, 1)}\n`);
  return { ok: true, exitCode: 0, files: [outFile, reportFile], report, errors };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const argv = process.argv.slice(2);
  const level = argValue(argv, '--level');
  const check = argv.includes('--check');
  if (!LEVELS.includes(level)) {
    console.error('Usage: node tools/learning-path-source/seed/build_path_seed.mjs --level N5 [--check] [--audio-root <dir>]');
    process.exit(1);
  }
  const out = buildSeed(level, { check, audioRoot: argValue(argv, '--audio-root', 'data/content-audio') });
  for (const e of out.errors) console.error(e);
  if (out.report?.limits) for (const l of out.report.limits.slice(0, 20)) console.error(`  ${l.table}.${l.column} ${l.code}: ${l.length} > ${l.limit}`);
  if (out.report?.seed_version) {
    const r = out.report;
    console.log(`${level} seed_version ${r.seed_version}; rows ${Object.entries(r.rows).map(([t, n]) => `${t}=${n}`).join(' ')}`);
    console.log(`audio ok ${r.inputs.audio.ok}/${r.inputs.audio.items}; edits ${r.inputs.edits.applied} applied, ${r.inputs.edits.skipped} skipped`);
    console.log(`release ${level}: ${r.release.released ? 'released' : `not released (${r.release.note})`}`);
    for (const l of r.rebuild_higher ?? []) console.log(`note: ${l} reads ${level} for cross-level pairs — re-run --level ${l}`);
  }
  // The base block (levels, kana) belongs to seed-sql.mjs; say so when it is stale, since its planned counts follow compiled.
  const common = learningDataFile(ROOT);
  // A Windows checkout (core.autocrlf) holds the file with CRLF: compare without line-ending differences.
  const lf = (t) => t.replace(/\r\n/g, '\n');
  if (existsSync(common) && !lf(readFileSync(common, 'utf8')).includes(lf(renderBaseBlock(ROOT)))) {
    console.error(`${BASE_MARKER} is stale: run node tools/learning-path-source/seed/seed-sql.mjs`);
    if (check && out.exitCode === 0) process.exit(1);
  }
  if (out.exitCode === 0) console.log(check ? `jpath ${level} region is current` : `Wrote jpath ${level} region and target/jpath-seed-report-${level}.json`);
  process.exit(out.exitCode);
}
