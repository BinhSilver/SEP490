// [JLAS-W0108] Phase 2: Azure TTS for the communication path (owner 06/11). Reads
// data/learning-path/communication-compiled/<GOAL>.json and makes one mp3 per dialogue line (リン, the learner, in a
// female voice; the other side in a male voice), per word (its reading) and per key phrase. Files go to
// data/content-audio/comm-path/<GOAL>/, the manifest to data/learning-path/audio/comm-<GOAL>.json. A file is reused
// when the manifest says it was made from the same text (see tts.mjs synthesizeAll).
// Run (key from the environment): node tools/comm-path/comm-tts.mjs [--goal TRAVEL] [--limit N] [--dry-run]
import { readFileSync, writeFileSync, existsSync, mkdirSync } from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { synthesizeAll, spokenText } from '../learning-path-source/compile/tts.mjs';

const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), '../..');
const GOALS = ['TRAVEL', 'DAILY_LIFE', 'BA', 'WORK'];
const FEMALE = 'ja-JP-NanamiNeural';
const MALE = 'ja-JP-KeitaNeural';
const LEARNER = 'リン';

const sha = t => crypto.createHash('sha256').update(String(t)).digest('hex');
const item = (id, kind, text, file) => ({ id, kind, text: spokenText(text), file, sha_text: sha(spokenText(text)) });

/** Plan of one goal split by voice: {female: [...], male: [...]}. */
export function planGoal(compiled) {
  const base = `data/content-audio/comm-path/${compiled.goal}`;
  const female = [];
  const male = [];
  for (const t of compiled.topics ?? []) {
    for (const s of t.situations ?? []) {
      for (const l of s.lines ?? []) {
        if (!l.ja) continue;
        (l.speaker === LEARNER ? female : male).push(item(`${s.id}-L${l.order}`, 'LINE', l.ja, `${base}/${s.id}-L${l.order}.mp3`));
      }
    }
    for (const w of t.words ?? []) if (w.reading || w.word) female.push(item(w.id, 'WORD', w.reading || w.word, `${base}/${w.id}.mp3`));
    for (const p of t.phrases ?? []) if (p.ja) female.push(item(p.id, 'PHRASE', p.ja.replace(/〜/g, ''), `${base}/${p.id}.mp3`));
  }
  return { female, male };
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'))) {
  const argv = process.argv.slice(2);
  const arg = (k, d) => { const i = argv.indexOf(k); return i >= 0 ? argv[i + 1] : d; };
  const goals = arg('--goal') ? [arg('--goal')] : GOALS;
  const limit = arg('--limit', null);
  const dryRun = argv.includes('--dry-run');
  for (const goal of goals) {
    const compiled = JSON.parse(readFileSync(path.join(ROOT, 'data/learning-path/communication-compiled', `${goal}.json`), 'utf8'));
    const plan = planGoal(compiled);
    const manifestFile = path.join(ROOT, 'data/learning-path/audio', `comm-${goal}.json`);
    const previous = existsSync(manifestFile) ? JSON.parse(readFileSync(manifestFile, 'utf8')) : [];
    const done = [];
    for (const [voice, list] of [[FEMALE, plan.female], [MALE, plan.male]]) {
      const made = await synthesizeAll(list, { limit, dryRun, voice, manifest: previous, rootDir: ROOT });
      done.push(...made);
    }
    if (!dryRun) {
      mkdirSync(path.dirname(manifestFile), { recursive: true });
      const byFile = new Map(previous.map(e => [e.file, e]));
      for (const e of done) byFile.set(e.file, e);
      writeFileSync(manifestFile, JSON.stringify([...byFile.values()].sort((a, b) => (a.file < b.file ? -1 : 1)), null, 1) + '\n');
    }
    console.log(goal, 'female', plan.female.length, 'male', plan.male.length, dryRun ? '(dry run)' : 'written');
  }
}
