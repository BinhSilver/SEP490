// [JLAS-W0108] JLPT path skills seed CLI (Plan 3 Task 6): writes one level's skill content (question types, strategy
// lessons, practice sets, questions, dictation lines) into the `-- BEGIN GENERATED jpath-skills` region of
// sql/learning/learning_data_<L>.sql. Inputs (read-only): data/learning-path/skills/** (bank, checked by skill-check.mjs),
// dictation blanks (skill-dictation.mjs) and the skill audio manifest data/learning-path/audio/skills-<L>.json (a
// listening set whose mp3 is not on disk with the manifest's size and SHA-256 keeps audio_path NULL; re-run once the
// audio run finishes). The GENERATED jpath region of Plan 2 is never touched.
// Run: node tools/learning-path-source/skills/build_skill_seed.mjs --level N5 [--level N4 ...] [--check]
// Exit 0 = written / current, 1 = error or (with --check) drift.
import { existsSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { LEVELS, ROOT, sha256 } from '../compile/util.mjs';
import { readRegion } from '../seed/build_path_seed.mjs';
import { SKILLS_DIR, checkLevel, loadLevelInputs } from './skill-check.mjs';
import { dictationBlanks, levelVocab } from './skill-dictation.mjs';
import { loadSkillAudio, readSkillRegion, replaceSkillRegion, skillRegion, skillRows } from './skill-seed.mjs';

const MAX_FILE_BYTES = 50 * 1024 * 1024;
const rel = (file) => path.relative(ROOT, file).split(path.sep).join('/');
const resolve = (p) => path.resolve(ROOT, p);

/** SHA-256 over every bank file of the level (types, lessons, practice/<L>/*.json), in name order. */
function bankSha(level) {
  const files = [path.join(SKILLS_DIR, 'question-types.json'), path.join(SKILLS_DIR, 'strategy-lessons.json')];
  const dir = path.join(SKILLS_DIR, 'practice', level);
  if (existsSync(dir)) files.push(...readdirSync(dir).filter((f) => f.endsWith('.json')).sort().map((f) => path.join(dir, f)));
  return sha256(Buffer.concat(files.map((f) => readFileSync(f))));
}

/**
 * Builds (or with check=true compares) the jpath-skills region of one level.
 * @returns {{ok, drift, file, report, errors}}
 */
export function buildSkillSeed(level, {
  audioDir = 'data/learning-path/audio', audioRoot = 'data/content-audio', outDir = 'sql/learning/parts', check = false,
} = {}) {
  if (!LEVELS.includes(level)) throw new Error(`Unknown JLPT level ${level}`);
  const errors = [];
  const inputs = loadLevelInputs(level);
  const { accepted, excluded } = checkLevel(level, inputs);
  // owner 06/11: per-set translations (questions + options, listening lines, reading sentences) written outside the
  // bank in data/learning-path/skills/translations/<L>/<file>.json, keyed by set id
  const trDir = path.join(SKILLS_DIR, 'translations', level);
  if (existsSync(trDir)) {
    const tr = new Map();
    for (const f of readdirSync(trDir).filter((f) => f.endsWith('.json'))) {
      for (const [id, t] of Object.entries(JSON.parse(readFileSync(path.join(trDir, f), 'utf8')))) tr.set(id, t);
    }
    for (const set of accepted) if (tr.has(set.id)) set.translation = tr.get(set.id);
  }
  const rawById = new Map(inputs.sets.map((s) => [s.id, s]));
  const excludedSets = excluded.map((e) => rawById.get(e.id)).filter((s) => s && s.type_code && s.id);
  const { rows: blanks, empty } = dictationBlanks(accepted.filter((s) => Array.isArray(s.dictation)), levelVocab(level));

  const manifestFile = path.join(resolve(audioDir), `skills-${level}.json`);
  const manifestBytes = existsSync(manifestFile) ? readFileSync(manifestFile) : null;
  const audio = manifestBytes ? loadSkillAudio(JSON.parse(manifestBytes.toString('utf8')), audioRoot) : new Map();
  const audioOk = [...audio.values()].filter((a) => a.ok).length;

  const rows = skillRows(level, {
    types: inputs.types, lessons: inputs.lessons, accepted, excluded: excludedSets, dictationBlanks: blanks, audio,
  });
  const listening = rows.jpath_skill_set.filter((s) => s.skill === 'LISTENING');
  const report = {
    level,
    rows: Object.fromEntries(Object.entries(rows).map(([t, list]) => [t, list.length])),
    excluded: excluded.length,
    dictation_without_blank: empty.length,
    audio: {
      manifest: manifestBytes ? rel(manifestFile) : 'none', items: audio.size, ok: audioOk,
      listening_sets: listening.length, listening_with_audio: listening.filter((s) => s.audio_path).length,
      dictation_with_audio: rows.jpath_skill_dictation.filter((d) => d.audio_path).length,
    },
  };
  const header = [
    `bank: data/learning-path/skills (${level}) sha256 ${bankSha(level)}`,
    `audio: ${manifestBytes ? `${rel(manifestFile)} sha256 ${sha256(manifestBytes)} (ok ${audioOk}/${audio.size})` : 'none'}`,
    `listening sets with audio: ${report.audio.listening_with_audio}/${listening.length} (the others stay hidden)`,
  ];
  let region;
  try {
    region = skillRegion(level, rows, { header });
  } catch (e) {
    return { ok: false, drift: null, file: null, report, errors: [e.message] };
  }

  const outFile = path.join(resolve(outDir), `learning_data_${level}.sql`);
  if (!existsSync(outFile)) return { ok: false, drift: null, file: outFile, report, errors: [`${rel(outFile)} does not exist`] };
  const current = readFileSync(outFile, 'utf8');
  if (!readRegion(current)) {
    return { ok: false, drift: null, file: outFile, report, errors: [`${rel(outFile)} has no GENERATED jpath region`] };
  }
  const next = replaceSkillRegion(current, region);
  const drift = next !== current;
  report.seed_version = /^-- seed_version: (\w+)$/m.exec(region)[1];
  if (Buffer.byteLength(next) > MAX_FILE_BYTES) errors.push(`${rel(outFile)} would be above 50 MiB`);
  if (errors.length) return { ok: false, drift, file: outFile, report, errors };
  if (check) {
    return { ok: !drift, drift, file: outFile, report, errors: drift ? [`${rel(outFile)} jpath-skills region is stale`] : [] };
  }
  if (drift) writeFileSync(outFile, next);
  if (!readSkillRegion(readFileSync(outFile, 'utf8'))) errors.push(`${rel(outFile)}: region missing after write`);
  return { ok: errors.length === 0, drift, file: outFile, report, errors };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const levels = args.flatMap((a, i) => (a === '--level' ? [args[i + 1]] : []));
  const check = args.includes('--check');
  if (!levels.length || levels.some((l) => !LEVELS.includes(l))) {
    console.error('usage: build_skill_seed.mjs --level N5 [--level N4 ...] [--check]');
    process.exit(1);
  }
  let failed = false;
  for (const level of levels) {
    const r = buildSkillSeed(level, { check });
    const verb = check ? (r.drift ? 'STALE' : 'current') : (r.drift ? 'written' : 'unchanged');
    console.log(`${level}: ${r.ok ? verb : 'FAILED'} ${JSON.stringify(r.report)}`);
    for (const e of r.errors) console.error(`  ${e}`);
    if (!r.ok) failed = true;
  }
  process.exit(failed ? 1 : 0);
}
