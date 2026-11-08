// [JLAS-W0108] Day 2 V3 / V16 (24/10): SHA-256 manifest of every audio file the Learning listening runtime plays —
// Minna N5/N4 clips (jlpt_minna_n5_n4_listening) and Speed Master N3 clips (jlpt_speed_n3_comprehension). Paths are
// relative to content.audio.path (served at /uploads/content-audio/**). A missing file, a Git LFS pointer or a file that
// is not MP3 is reported, never hidden; with --check the manifest must match the files on this machine.
//   node tools/learning-data-extract/build_learning_audio_manifest.mjs . [--check]          (repo audio, data/content-audio)
//   node tools/learning-data-extract/build_learning_audio_manifest.mjs . --audio-root <dir>   (an installed audio folder)
import { createHash } from 'node:crypto';
import { readFile, writeFile, stat, open } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readLearningContent } from './learning_content_store.mjs';

const OUT = 'data/learning-owned/learning-audio-manifest.json';
const LFS_POINTER = 'version https://git-lfs.github.com/spec/';

/**
 * Every Learning listening clip, grouped by level. A Minna clip has two equivalent places (same bytes): the repo copy
 * vnjpclub/<level>/minna-so-cap-<n>/<source folder>_<file>.mp3 in data/content-audio (team Google Drive pack, not in Git), which the catalog
 * prefers, and minna/vnjpclub/<level>/minna-so-cap-<n>/bai-<nn>/<file>.mp3 from a separately installed package.
 */
export function referencedAudio(data) {
  const groups = { minna_n5: [], minna_n4: [], speed_n3: [] };
  for (const lesson of data.sections.jlpt_minna_n5_n4_listening?.lessons ?? []) {
    for (const clip of lesson.cau ?? []) {
      const local = /^audio\/(vnjpclub\/(N[54])\/minna-so-cap-([12])\/bai-\d{2}\/([A-Za-z0-9_-]+\.mp3))$/.exec(clip.audio_file ?? '');
      // /Audio/minnaln/minna1/a/01A-1.mp3, or the N5 listening task set /Audio/minna-task25/I/CD_B-15.mp3
      const remote = /\/Audio\/(?:minnaln\/minna([12])|minna-task25)\/([A-Za-z]+)\/([A-Za-z0-9_-]+\.mp3)$/.exec(clip.audio_url ?? '');
      if (!local || !remote || (remote[1] && remote[1] !== local[3]) || remote[3] !== local[4]) {
        throw new Error(`Minna clip ${clip.ma} has no matching local audio`);
      }
      groups[local[2] === 'N5' ? 'minna_n5' : 'minna_n4'].push({ clip: clip.ma,
        paths: [`vnjpclub/${local[2]}/minna-so-cap-${local[3]}/${remote[2]}_${remote[3]}`, `minna/${local[1]}`] });
    }
  }
  for (const exercise of data.sections.jlpt_speed_n3_comprehension?.exercises ?? []) {
    if (exercise.skill !== 'LISTENING') continue;
    if (!/^vnjpclub\/N3\/speed-n3-nghe-hieu\/[A-Za-z0-9_-]+\.mp3$/.test(exercise.audio_path ?? '')) {
      throw new Error(`Speed N3 clip ${exercise.source_group} has no local audio_path`);
    }
    groups.speed_n3.push({ clip: exercise.source_group, paths: [exercise.audio_path] });
  }
  for (const list of Object.values(groups)) list.sort((a, b) => a.paths[0].localeCompare(b.paths[0]));
  return groups;
}

/** MP3 = an ID3v2 tag or an MPEG audio frame sync at the start of the file. */
export function looksLikeMp3(head) {
  if (head.length >= 3 && head.toString('latin1', 0, 3) === 'ID3') return true;
  return head.length >= 2 && head[0] === 0xff && (head[1] & 0xe0) === 0xe0;
}

/** The first of a clip's equivalent paths present under the audio root; which one is machine-specific and not recorded. */
async function describe(audioRoot, clip) {
  for (const relative of clip.paths) {
    const file = path.join(audioRoot, relative);
    let info;
    try { info = await stat(file); } catch { continue; }
    const handle = await open(file, 'r');
    try {
      const head = Buffer.alloc(64);
      const { bytesRead } = await handle.read(head, 0, 64, 0);
      const start = head.subarray(0, bytesRead);
      if (start.toString('utf8').startsWith(LFS_POINTER)) return { ...clip, bytes: info.size, status: 'LFS_POINTER' };
      const sha256 = createHash('sha256').update(await readFile(file)).digest('hex');
      return { ...clip, bytes: info.size, sha256, status: looksLikeMp3(start) ? 'OK' : 'NOT_MP3' };
    } finally {
      await handle.close();
    }
  }
  return { ...clip, status: 'MISSING' };
}

export async function buildManifest(data, audioRoot) {
  const groups = {};
  for (const [name, clips] of Object.entries(referencedAudio(data))) {
    const entries = [];
    for (const clip of clips) entries.push(await describe(audioRoot, clip));
    const problems = entries.filter((entry) => entry.status !== 'OK');
    groups[name] = {
      files: entries.length, ok: entries.length - problems.length,
      bytes: entries.reduce((n, entry) => n + (entry.bytes ?? 0), 0),
      problems: problems.map(({ clip, paths, status }) => ({ clip, paths, status })), entries,
    };
  }
  const listing = Object.values(groups).flatMap((group) => group.entries.map((entry) => `${entry.clip}\t${entry.sha256 ?? entry.status}`));
  return {
    kind: 'LEARNING_LISTENING_AUDIO_MANIFEST',
    served_at: '/uploads/content-audio/',
    relative_to: 'content.audio.path (application-local.yaml / CONTENT_AUDIO_PATH; default data/content-audio, unpacked from the team Google Drive audio pack — not in Git)',
    sources: ['jlpt_minna_n5_n4_listening', 'jlpt_speed_n3_comprehension'],
    note: 'Minna clips: the repo copy (data/content-audio, git lfs pull) is played first; minna/ from a separately '
      + 'installed package is the fallback. Both are the same bytes.',
    listing_sha256: createHash('sha256').update(listing.join('\n')).digest('hex'),
    groups,
  };
}

/**
 * The manifest is written from the local audio folder (data/content-audio, unpacked from the Drive pack) only. Another installed folder (the team
 * package re-encodes vnjpclub audio to mono 64 kbps) is checked for presence and MP3 headers; its re-encoded files are
 * counted, not failed.
 */
export function compareInstalled(manifest, installed) {
  const result = { missing: [], variants: 0 };
  for (const [name, group] of Object.entries(manifest.groups)) {
    group.entries.forEach((entry, index) => {
      const other = installed.groups[name]?.entries[index];
      if (!other || other.clip !== entry.clip || other.status !== 'OK') result.missing.push(entry.clip);
      else if (other.sha256 !== entry.sha256) result.variants++;
    });
  }
  return result;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = path.resolve(process.argv[2] ?? '.');
  const repoAudio = path.join(root, 'data/content-audio');
  const at = process.argv.indexOf('--audio-root');
  const audioRoot = path.resolve(at > 0 ? process.argv[at + 1] : repoAudio);
  const data = await readLearningContent(root);
  const manifest = await buildManifest(data, audioRoot);
  const text = JSON.stringify(manifest, null, 1) + '\n';
  const current = (await readFile(path.join(root, OUT), 'utf8').catch(() => '')).replace(/\r\n/g, '\n');
  if (audioRoot !== path.resolve(repoAudio)) {
    const { missing, variants } = compareInstalled(JSON.parse(current), manifest);
    console.log(`installed folder ${audioRoot}: missing ${missing.length}, re-encoded copies ${variants}`);
    if (missing.length) throw new Error(`Missing or unplayable clips: ${missing.slice(0, 10).join(', ')}`);
  } else if (process.argv.includes('--check')) {
    if (current !== text) throw new Error(`${OUT} does not match the audio files under ${audioRoot}.`);
  } else {
    await writeFile(path.join(root, OUT), text, 'utf8');
  }
  for (const [name, group] of Object.entries(manifest.groups)) {
    console.log(`${name}: ${group.ok}/${group.files} OK, ${(group.bytes / 1e6).toFixed(1)} MB, problems ${group.problems.length}`);
  }
  console.log(`listing SHA-256 ${manifest.listing_sha256}`);
}
