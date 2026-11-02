// Builds the one compiled source of a JLPT level: Mirai frame → merge → authoring packets → checks → statuses.
// Output data/learning-path/compiled/<level>.json is deterministic; --check compares bytes instead of writing.
import { existsSync } from 'node:fs';
import path from 'node:path';
import { LEVELS, ROOT, argValue, normalizeJapanese, readJson, writeOrCheck } from './util.mjs';
import { compileFromInputs, loadCompileInputs, loadReference } from './inputs.mjs';
import { grammarFamilyKey } from './normalize.mjs';
import { applyPackets, authoringDir, correctionDirs as allCorrectionDirs, emitReviewPackets, globalCorrections, readPackets } from './authoring.mjs';
import { applyStatuses, buildLevelIndex, runChecks, WARNING_CODES } from './checks.mjs';
import { writeReport, REPORT_DIR } from './report.mjs';

export const compiledFile = level => path.join(ROOT, 'data/learning-path/compiled', `${level}.json`);

// Every level compiled in memory with the same corrections: word and kanji levels come from them (a kana Mirai word
// counts at its merged word's level, a reviewed spelling fix counts), and they tell where each J-LAS id went.
const levelCompiles = new WeakMap();
function compiledLevels(inputs, corrections) {
  const key = JSON.stringify([[...(corrections.identity ?? [])], [...(corrections.identityByForm ?? [])], corrections.sameWord ?? [],
    corrections.notSameWord ?? [], [...(corrections.dropVariant ?? [])].map(([id, s]) => [id, [...s]]),
    [...(corrections.grammarPattern ?? [])], corrections.samePattern ?? []]);
  if (!levelCompiles.has(inputs)) levelCompiles.set(inputs, new Map());
  const cache = levelCompiles.get(inputs);
  if (!cache.has(key)) {
    const levels = LEVELS.map(l => compileFromInputs({ ...inputs, corrections }, l));
    cache.set(key, { levels, index: buildLevelIndex({ levels, dictionary: inputs.dictionary }) });
  }
  return cache.get(key);
}

const idsOf = c => [...c.vocabulary, ...c.kanji, ...c.grammar, ...(c.merged_ids ?? [])].map(x => x.id);

function checkContext(inputs, level, outFile, corrections = {}) {
  const { levels, index: { levelOfWord, kanaLevel, kanjiLevel } } = compiledLevels(inputs, corrections);

  // Mirai reference (free preview) of every level: word meanings by spelling|reading, grammar meanings by family key,
  // and every example sentence — read only to flag copying.
  const miraiReference = new Map(), miraiGrammar = new Map(), miraiExamples = [];
  for (const l of LEVELS) {
    const ref = loadReference(l);
    const byRef = new Map(ref.vocabulary.map(r => [r.mirai_ref, r]));
    for (const f of inputs.frames[l].vocabulary) {
      const r = byRef.get(f.mirai_ref);
      const key = `${normalizeJapanese(f.expression)}|${normalizeJapanese(f.reading)}`;
      if (r && !miraiReference.has(key)) miraiReference.set(key, r);
    }
    const gByRef = new Map(ref.grammar.map(g => [g.mirai_ref, g]));
    for (const f of inputs.frames[l].grammar ?? []) {
      const g = gByRef.get(f.mirai_ref);
      if (g?.meaning_vi && !miraiGrammar.has(grammarFamilyKey(f.pattern))) miraiGrammar.set(grammarFamilyKey(f.pattern), g.meaning_vi);
    }
    miraiExamples.push(...[...ref.vocabulary.map(r => r.example_ja), ...ref.grammar.flatMap(g => g.examples)].filter(Boolean));
  }
  const src = inputs.jlasLevels[level];
  // Expected: every J-LAS id of the level, and every id (and merged id) the previous compiled file had.
  const expectedIds = new Set([...src.vocabulary, ...src.kanji, ...src.grammar].map(x => x.id));
  if (existsSync(outFile)) for (const id of idsOf(readJson(outFile))) expectedIds.add(id);
  // Another level counts as its compiled file beside this one; with no file there yet (a scratch --out), as this
  // compiler builds it. An id the compiler puts in a level whose file lacks it is lost until that level is rebuilt.
  const otherLevelIds = new Set(), compiledAt = new Map();
  for (const [i, l] of LEVELS.entries()) {
    if (l === level) continue;
    for (const id of idsOf(levels[i])) if (!compiledAt.has(id)) compiledAt.set(id, l);
    const file = path.join(path.dirname(outFile), `${l}.json`);
    for (const id of existsSync(file) ? idsOf(readJson(file)) : idsOf(levels[i])) otherLevelIds.add(id);
  }
  const lostDetail = id => compiledAt.has(id) ? `compiled into ${compiledAt.get(id)}; ${compiledAt.get(id)}.json is stale` : '';
  return { levelOfWord, kanaLevel, kanjiLevel, miraiReference, miraiGrammar, miraiExamples, expectedIds, otherLevelIds, lostDetail };
}

export function buildLevel(level, { inputs = loadCompileInputs(), outFile = compiledFile(level), reportDir = REPORT_DIR,
  packetsDir = authoringDir(level), correctionDirs = allCorrectionDirs(), check = false,
  date = new Date().toISOString().slice(0, 10) } = {}) {
  const packets = readPackets(packetsDir);
  // Review corrections come from every level's packets (packetsDir included even when it is a scratch folder).
  const dirs = [...new Set([...correctionDirs, packetsDir].map(d => path.resolve(d)))];
  const corrections = globalCorrections(dirs);
  const applied = applyPackets(compiledLevels(inputs, corrections).levels[LEVELS.indexOf(level)], packets);
  const result = runChecks(applied, checkContext(inputs, level, outFile, corrections));
  const compiled = applyStatuses(applied, result);
  const ok = writeOrCheck(outFile, { ...compiled, generated_by: 'tools/learning-path-source/compile/build_level_source.mjs' }, check);
  if (!check) writeReport({ level, compiled, result, date }, reportDir);
  const statusOf = new Map([...compiled.vocabulary, ...compiled.kanji, ...compiled.grammar].map(x => [x.id, x.status]));
  const blockingNonDraft = result.errors.filter(e => !e.warning && !WARNING_CODES.has(e.code)
    && statusOf.has(e.id) && statusOf.get(e.id) !== 'DRAFT');
  return { ok, result, compiled, blockingNonDraft, corrections };
}

// Review context beyond the item: the J-LAS dictionary meaning it links to, this build's findings on it, and the
// words it shares a spelling with (any level) so a reviewer can say "same word".
export function reviewExtra(inputs, compiled, result, corrections = {}) {
  const findings = new Map();
  for (const e of result.errors) { if (!findings.has(e.id)) findings.set(e.id, []); findings.get(e.id).push(e.detail ? `${e.code}: ${e.detail}` : e.code); }
  const levels = new Map([[compiled.level, compiled]]);
  const levelOf = l => levels.get(l) ?? compiledLevels(inputs, corrections).levels[LEVELS.indexOf(l)];
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
  const { ok, result, compiled, blockingNonDraft, corrections } = buildLevel(level, { inputs, check, outFile, reportDir });
  if (argv.includes('--emit-review')) {
    const written = emitReviewPackets(compiled, authoringDir(level), { extra: reviewExtra(inputs, compiled, result, corrections) });
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
