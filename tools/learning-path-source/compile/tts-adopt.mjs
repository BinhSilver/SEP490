// [JLAS-W0108] After a TTS run was killed (no manifest written), record the audio files it already made, so the next
// run reuses them instead of synthesizing them again. A file is adopted only when it is in the current plan, is not
// already in the manifest, was written after --since (the run's start) and is complete (>= 1 KB, older than 60 s).
// Run: node tools/learning-path-source/compile/tts-adopt.mjs --since 2026-11-06T22:00 [--level N1] [--comm BA,WORK]
import { readFileSync, writeFileSync, existsSync, statSync } from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { planAudio, compiledLevelFile, audioManifestFile } from './tts.mjs';
import { planGoal } from '../../comm-path/comm-tts.mjs';

const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), '../../..');
const argv = process.argv.slice(2);
const arg = (k, d) => { const i = argv.indexOf(k); return i >= 0 ? argv[i + 1] : d; };
const since = new Date(arg('--since', '')).getTime();
if (!Number.isFinite(since)) throw new Error('--since is required (ISO time)');
const now = Date.now();
const sha = buf => crypto.createHash('sha256').update(buf).digest('hex');

function adopt(plan, previous) {
  const known = new Set(previous.map(e => e.file));
  const added = [];
  for (const it of plan) {
    if (known.has(it.file)) continue;
    const abs = path.resolve(ROOT, it.file);
    if (!existsSync(abs)) continue;
    const st = statSync(abs);
    if (st.mtimeMs < since || st.size < 1024 || now - st.mtimeMs < 60000) continue;
    const buf = readFileSync(abs);
    added.push({ id: it.id, file: it.file, sha256: sha(buf), bytes: buf.length, text_sha256: it.sha_text });
    known.add(it.file);
  }
  return added;
}

const level = arg('--level', null);
if (level) {
  const plan = planAudio(JSON.parse(readFileSync(compiledLevelFile(level), 'utf8')));
  const file = audioManifestFile(level);
  const doc = existsSync(file) ? JSON.parse(readFileSync(file, 'utf8')) : { level, items: [] };
  const added = adopt(plan, doc.items);
  doc.items = [...doc.items, ...added].sort((a, b) => (a.file < b.file ? -1 : 1));
  writeFileSync(file, JSON.stringify(doc, null, 1) + '\n');
  console.log(`${level}: adopted ${added.length}, manifest now ${doc.items.length} / plan ${plan.length}`);
}
for (const goal of (arg('--comm', '') || '').split(',').filter(Boolean)) {
  const p = planGoal(JSON.parse(readFileSync(path.join(ROOT, 'data/learning-path/communication-compiled', `${goal}.json`), 'utf8')));
  const plan = [...p.female, ...p.male];
  const file = path.join(ROOT, 'data/learning-path/audio', `comm-${goal}.json`);
  const prev = existsSync(file) ? JSON.parse(readFileSync(file, 'utf8')) : [];
  const added = adopt(plan, prev);
  writeFileSync(file, JSON.stringify([...prev, ...added].sort((a, b) => (a.file < b.file ? -1 : 1)), null, 1) + '\n');
  console.log(`comm ${goal}: adopted ${added.length}, manifest now ${prev.length + added.length} / plan ${plan.length}`);
}
