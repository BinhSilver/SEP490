// Builds the one compiled source of a JLPT level: Mirai frame → merge → authoring packets → checks → statuses.
// Output data/learning-path/compiled/<level>.json is deterministic; --check compares bytes instead of writing.
import { existsSync } from 'node:fs';
import path from 'node:path';
import { LEVELS, ROOT, argValue, normalizeJapanese, readJson, writeOrCheck } from './util.mjs';
import { compileFromInputs, loadCompileInputs, loadReference } from './inputs.mjs';
import { applyPackets, authoringDir, emitReviewPackets, readPackets, reviewCorrections } from './authoring.mjs';
import { applyStatuses, buildLevelIndex, runChecks, WARNING_CODES } from './checks.mjs';
import { writeReport, REPORT_DIR } from './report.mjs';

export const compiledFile = level => path.join(ROOT, 'data/learning-path/compiled', `${level}.json`);

// Word and kanji levels come from the merged levels (a kana Mirai word counts at its merged word's level, a reviewed
// spelling fix counts), so they are compiled once per inputs and corrections.
const levelIndexes = new WeakMap();
function levelIndex(inputs, corrections) {
  const key = JSON.stringify([[...(corrections.identity ?? [])], [...(corrections.identityByForm ?? [])], corrections.sameWord ?? []]);
  if (!levelIndexes.has(inputs)) levelIndexes.set(inputs, new Map());
  const cache = levelIndexes.get(inputs);
  if (!cache.has(key)) cache.set(key, buildLevelIndex({
    levels: LEVELS.map(l => compileFromInputs({ ...inputs, corrections }, l)), dictionary: inputs.dictionary }));
  return cache.get(key);
}

function checkContext(inputs, level, outFile, corrections = {}) {
  const { levelOfWord, kanaLevel, kanjiLevel } = levelIndex(inputs, corrections);

  const miraiReference = new Map();
  let miraiExamples = [];
  for (const l of LEVELS) {
    const ref = loadReference(l);
    const byRef = new Map(ref.vocabulary.map(r => [r.mirai_ref, r]));
    for (const f of inputs.frames[l].vocabulary) {
      const r = byRef.get(f.mirai_ref);
      if (r) miraiReference.set(`${normalizeJapanese(f.expression)}|${normalizeJapanese(f.reading)}`, r);
    }
    if (l === level) miraiExamples = [...ref.vocabulary.map(r => r.example_ja), ...ref.grammar.flatMap(g => g.examples)].filter(Boolean);
  }
  const src = inputs.jlasLevels[level];
  const expectedIds = new Set([...src.vocabulary, ...src.kanji, ...src.grammar].map(x => x.id));
  const otherLevelIds = new Set();
  for (const l of LEVELS.filter(x => x !== level)) {
    const file = path.join(path.dirname(outFile), `${l}.json`);
    if (!existsSync(file)) continue;
    const other = readJson(file);
    for (const x of [...other.vocabulary, ...other.kanji, ...other.grammar, ...(other.merged_ids ?? [])]) otherLevelIds.add(x.id);
  }
  return { levelOfWord, kanaLevel, kanjiLevel, miraiReference, miraiExamples, expectedIds, otherLevelIds };
}

export function buildLevel(level, { inputs = loadCompileInputs(), outFile = compiledFile(level), reportDir = REPORT_DIR,
  packetsDir = authoringDir(level), check = false, date = new Date().toISOString().slice(0, 10) } = {}) {
  const packets = readPackets(packetsDir);
  const corrections = reviewCorrections(packets);
  const applied = applyPackets(compileFromInputs({ ...inputs, corrections }, level), packets);
  const result = runChecks(applied, checkContext(inputs, level, outFile, corrections));
  const compiled = applyStatuses(applied, result);
  const ok = writeOrCheck(outFile, { ...compiled, generated_by: 'tools/learning-path-source/compile/build_level_source.mjs' }, check);
  if (!check) writeReport({ level, compiled, result, date }, reportDir);
  const statusOf = new Map([...compiled.vocabulary, ...compiled.kanji, ...compiled.grammar].map(x => [x.id, x.status]));
  const blockingNonDraft = result.errors.filter(e => !e.warning && !WARNING_CODES.has(e.code)
    && statusOf.has(e.id) && statusOf.get(e.id) !== 'DRAFT');
  return { ok, result, compiled, blockingNonDraft };
}

// Review context beyond the item: the J-LAS dictionary meaning it links to, this build's findings on it, and the
// words it shares a spelling with (any level) so a reviewer can say "same word".
export function reviewExtra(inputs, compiled, result) {
  const findings = new Map();
  for (const e of result.errors) { if (!findings.has(e.id)) findings.set(e.id, []); findings.get(e.id).push(e.detail ? `${e.code}: ${e.detail}` : e.code); }
  const levels = new Map([[compiled.level, compiled]]);
  const levelOf = l => { if (!levels.has(l)) levels.set(l, compileFromInputs(inputs, l)); return levels.get(l); };
  const twinsOf = new Map();
  for (const t of compiled.spelling_twins ?? []) {
    const twin = levelOf(t.twin_level).vocabulary.find(v => v.id === t.twin_id);
    if (!twinsOf.has(t.id)) twinsOf.set(t.id, []);
    twinsOf.get(t.id).push({ id: t.twin_id, level: t.twin_level, expression: twin?.expression ?? null, meaning_vi: twin?.meaning_vi ?? null, topic_vi: twin?.topic_vi ?? null });
  }
  return (kind, item) => ({
    ...(kind === 'REVIEW_VOCAB' ? { dictionary_meaning: inputs.dictionary.entryById.get(item.dictionary_entry_id)?.meaning_vi ?? null,
      twins: twinsOf.get(item.id) ?? [] } : {}),
    findings: findings.get(item.id) ?? []
  });
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'))) {
  const argv = process.argv.slice(2);
  const level = argValue(argv, '--level', 'N5');
  const check = argv.includes('--check');
  // --out/--report-dir let an author validate packets into a scratch folder; --errors-json dumps every finding.
  const outFile = argValue(argv, '--out', compiledFile(level));
  const reportDir = argValue(argv, '--report-dir', REPORT_DIR);
  const inputs = loadCompileInputs();
  const { ok, result, compiled, blockingNonDraft } = buildLevel(level, { inputs, check, outFile, reportDir });
  if (argv.includes('--emit-review')) {
    const written = emitReviewPackets(compiled, authoringDir(level), { extra: reviewExtra(inputs, compiled, result) });
    console.log(`${level}: ${written.length} review packets written`);
  }
  const errorsJson = argValue(argv, '--errors-json');
  if (errorsJson) writeOrCheck(errorsJson, result.errors, false);
  const count = list => list.reduce((a, x) => (a[x.status] = (a[x.status] ?? 0) + 1, a), {});
  console.log(`${level}: vocab ${JSON.stringify(count(compiled.vocabulary))} kanji ${JSON.stringify(count(compiled.kanji))} grammar ${JSON.stringify(count(compiled.grammar))}`);
  console.log(`errors ${JSON.stringify(result.stats.errors_by_code)}`);
  if (check) console.log(ok ? 'check ok' : 'CHECK FAILED: compiled file differs');
  if (!ok) process.exit(1);
  if (blockingNonDraft.length) process.exit(2);
}
