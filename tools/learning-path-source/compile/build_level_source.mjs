// Builds the one compiled source of a JLPT level: Mirai frame → merge → authoring packets → checks → statuses.
// Output data/learning-path/compiled/<level>.json is deterministic; --check compares bytes instead of writing.
import { existsSync } from 'node:fs';
import path from 'node:path';
import { LEVELS, LEVEL_RANK, ROOT, argValue, normalizeJapanese, readJson, writeOrCheck } from './util.mjs';
import { compileFromInputs, loadCompileInputs, loadReference } from './inputs.mjs';
import { applyPackets, authoringDir, readPackets } from './authoring.mjs';
import { applyStatuses, runChecks, WARNING_CODES } from './checks.mjs';
import { writeReport, REPORT_DIR } from './report.mjs';

export const compiledFile = level => path.join(ROOT, 'data/learning-path/compiled', `${level}.json`);

function checkContext(inputs, level, outFile) {
  const levelOfWord = new Map();
  const note = (expression, lvl) => {
    if (!expression || !lvl || !(lvl in LEVEL_RANK)) return;
    const k = normalizeJapanese(expression), cur = levelOfWord.get(k);
    if (!cur || LEVEL_RANK[lvl] < LEVEL_RANK[cur]) levelOfWord.set(k, lvl);
  };
  for (const l of LEVELS) {
    for (const v of inputs.jlasLevels[l].vocabulary) note(v.expression, l);
    for (const v of inputs.frames[l].vocabulary) note(v.expression, l);
  }
  for (const e of inputs.dictionary.entryById.values()) note(e.expression, e.jlpt_level);

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
  return { levelOfWord, miraiReference, miraiExamples, expectedIds, otherLevelIds };
}

export function buildLevel(level, { inputs = loadCompileInputs(), outFile = compiledFile(level), reportDir = REPORT_DIR,
  packetsDir = authoringDir(level), check = false, date = new Date().toISOString().slice(0, 10) } = {}) {
  const applied = applyPackets(compileFromInputs(inputs, level), readPackets(packetsDir));
  const result = runChecks(applied, checkContext(inputs, level, outFile));
  const compiled = applyStatuses(applied, result);
  const ok = writeOrCheck(outFile, { ...compiled, generated_by: 'tools/learning-path-source/compile/build_level_source.mjs' }, check);
  if (!check) writeReport({ level, compiled, result, date }, reportDir);
  const statusOf = new Map([...compiled.vocabulary, ...compiled.kanji, ...compiled.grammar].map(x => [x.id, x.status]));
  const blockingNonDraft = result.errors.filter(e => !e.warning && !WARNING_CODES.has(e.code)
    && statusOf.has(e.id) && statusOf.get(e.id) !== 'DRAFT');
  return { ok, result, compiled, blockingNonDraft };
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'))) {
  const argv = process.argv.slice(2);
  const level = argValue(argv, '--level', 'N5');
  const check = argv.includes('--check');
  // --out/--report-dir let an author validate packets into a scratch folder; --errors-json dumps every finding.
  const outFile = argValue(argv, '--out', compiledFile(level));
  const reportDir = argValue(argv, '--report-dir', REPORT_DIR);
  const { ok, result, compiled, blockingNonDraft } = buildLevel(level, { check, outFile, reportDir });
  const errorsJson = argValue(argv, '--errors-json');
  if (errorsJson) writeOrCheck(errorsJson, result.errors, false);
  const count = list => list.reduce((a, x) => (a[x.status] = (a[x.status] ?? 0) + 1, a), {});
  console.log(`${level}: vocab ${JSON.stringify(count(compiled.vocabulary))} kanji ${JSON.stringify(count(compiled.kanji))} grammar ${JSON.stringify(count(compiled.grammar))}`);
  console.log(`errors ${JSON.stringify(result.stats.errors_by_code)}`);
  if (check) console.log(ok ? 'check ok' : 'CHECK FAILED: compiled file differs');
  if (!ok) process.exit(1);
  if (blockingNonDraft.length) process.exit(2);
}
