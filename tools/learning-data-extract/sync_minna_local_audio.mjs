import { createHash } from 'node:crypto';
import { copyFile, mkdir, readFile, stat } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readLearningContent } from './learning_content_store.mjs';

const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex');

/** Resolve each clip through the exact source URL→local-file crosswalk, never by a similar filename. */
export function buildMinnaAudioPlan(source, crosswalk, mediaRoot) {
  if (!Array.isArray(source) || !Array.isArray(crosswalk) || !mediaRoot) {
    throw new Error('Minna source, audio crosswalk and media root are required.');
  }
  const byUrl = new Map();
  for (const row of crosswalk) {
    const url = String(row.url_audio ?? '').trim();
    const relative = String(row.file_cuc_bo ?? '').replaceAll('\\', '/');
    if (!url || !relative.startsWith('media/') || relative.split('/').some((part) => part === '..')) continue;
    const entries = byUrl.get(url) ?? new Set();
    if (row.co_file === true || row.co_file === 'True') entries.add(relative);
    byUrl.set(url, entries);
  }
  const root = path.resolve(mediaRoot);
  const plan = new Map();
  for (const lesson of source) {
    const number = Number(lesson.bai);
    const level = number <= 25 ? 'N5' : 'N4';
    const volume = level === 'N5' ? 'minna-so-cap-1' : 'minna-so-cap-2';
    if (!Number.isInteger(number) || number < 1 || number > 50 || lesson.cap !== level) {
      throw new Error(`Invalid Minna lesson level/number: ${lesson.bai}`);
    }
    for (const clip of lesson.cau ?? []) {
      let url;
      try { url = new URL(clip.audio_url); } catch { throw new Error(`Invalid Minna audio URL at lesson ${number}/${clip.ma}`); }
      if (url.protocol !== 'https:' || url.hostname !== 'www.vnjpclub.com' || url.username || url.password) {
        throw new Error(`Untrusted Minna audio URL at lesson ${number}/${clip.ma}`);
      }
      const basename = path.posix.basename(url.pathname);
      if (!/^[A-Za-z0-9_-]+\.mp3$/.test(basename)) {
        throw new Error(`Invalid Minna audio filename at lesson ${number}/${clip.ma}`);
      }
      const matches = [...(byUrl.get(url.href) ?? [])];
      if (matches.length !== 1) {
        throw new Error(`Expected one exact audio crosswalk for lesson ${number}/${clip.ma}; found ${matches.length}.`);
      }
      const sourceRelative = matches[0].slice('media/'.length).split('/');
      const sourcePath = path.resolve(root, ...sourceRelative);
      if (!sourcePath.startsWith(`${root}${path.sep}`)) throw new Error('Minna media path escapes its source root.');
      const destinationRelative = path.posix.join('vnjpclub', level, volume,
        `bai-${String(number).padStart(2, '0')}`, basename);
      const existingPlan = plan.get(destinationRelative);
      if (existingPlan && path.resolve(existingPlan.sourcePath) !== sourcePath) {
        throw new Error(`Conflicting audio sources for ${destinationRelative}`);
      }
      plan.set(destinationRelative, { sourcePath, destinationRelative, sourceRelative: matches[0] });
    }
  }
  return [...plan.values()];
}

async function fingerprint(file) {
  const bytes = await readFile(file);
  if (bytes.length < 1000 || !(bytes.subarray(0, 3).toString() === 'ID3' || bytes[0] === 0xff)) {
    throw new Error(`Invalid or empty MP3: ${file}`);
  }
  return { size: bytes.length, sha: sha256(bytes) };
}

/** Copy only verified files to a separate content store; never overwrite different media. */
export async function copyMinnaAudioPlan(plan, destinationBase) {
  const destinationRoot = path.resolve(destinationBase);
  const checked = [];
  for (const item of plan) {
    const relative = item.destinationRelative.replaceAll('\\', '/');
    if (relative.startsWith('/') || relative.split('/').some((part) => part === '..')) {
      throw new Error(`Unsafe Minna destination path: ${relative}`);
    }
    const source = path.resolve(item.sourcePath);
    const fingerprintSource = await fingerprint(source);
    checked.push({ ...item, ...fingerprintSource, destination: path.resolve(destinationRoot, ...relative.split('/')) });
  }
  // Check every pre-existing target before writing any file, so a conflict cannot leave a partial copy.
  for (const item of checked) {
    if (!item.destination.startsWith(`${destinationRoot}${path.sep}`)) throw new Error('Minna destination escapes content audio root.');
    try {
      const existing = await stat(item.destination);
      const actual = await fingerprint(item.destination);
      if (actual.size !== item.size || actual.sha !== item.sha) {
        throw new Error(`Existing Minna media differs; refusing overwrite: ${item.destinationRelative}`);
      }
      item.alreadyPresent = true;
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
      item.alreadyPresent = false;
    }
  }
  for (const item of checked) {
    if (item.alreadyPresent) continue;
    await mkdir(path.dirname(item.destination), { recursive: true });
    await copyFile(item.sourcePath, item.destination);
    const copied = await fingerprint(item.destination);
    if (copied.size !== item.size || copied.sha !== item.sha) throw new Error(`Minna media copy verification failed: ${item.destinationRelative}`);
    item.alreadyPresent = false;
  }
  return {
    files_verified: checked.length,
    files_copied: checked.filter((item) => !item.alreadyPresent).length,
    unique_source_files: new Set(checked.map((item) => item.sourcePath)).size,
    bytes_copied: checked.filter((item) => !item.alreadyPresent).reduce((total, item) => total + item.size, 0),
  };
}

export async function auditMinnaAudioPlan(plan, destinationBase) {
  const destinationRoot = path.resolve(destinationBase);
  let matched = 0;
  let missing = 0;
  let conflict = 0;
  let bytesMissing = 0;
  const conflicts = [];
  for (const item of plan) {
    const source = await fingerprint(item.sourcePath);
    const destination = path.resolve(destinationRoot, ...item.destinationRelative.split('/'));
    if (!destination.startsWith(`${destinationRoot}${path.sep}`)) throw new Error('Minna destination escapes content audio root.');
    try {
      const actualStat = await stat(destination);
      const actual = await fingerprint(destination);
      if (actualStat.size === source.size && actual.sha === source.sha) matched++;
      else { conflict++; conflicts.push(item.destinationRelative); }
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
      missing++;
      bytesMissing += source.size;
    }
  }
  return { destinations: plan.length, matched, missing, conflict, bytes_missing: bytesMissing, conflicts };
}

/** Copy media from the supplied package, proven by the canonical JSON hash and exact audio crosswalk. */
export async function syncMinnaAudio(root, sourceFile, crosswalkFile) {
  const sourceBytes = await readFile(sourceFile);
  const source = JSON.parse(sourceBytes.toString('utf8').replace(/^\uFEFF/, ''));
  const canonical = (await readLearningContent(root)).sections?.jlpt_minna_n5_n4_listening;
  if (canonical?.source_sha256 !== sha256(sourceBytes) || !Array.isArray(source) || source.length !== 50) {
    throw new Error('Local audio source does not match the canonical Minna content checksum.');
  }
  const clips = source.flatMap((lesson) => lesson.cau ?? []);
  if (clips.length !== 204) throw new Error(`Expected 204 Minna clips, got ${clips.length}.`);
  const manifest = crosswalkFile
    ? path.resolve(crosswalkFile)
    : path.resolve(path.dirname(sourceFile), '..', 'du_lieu', 'audio_lien_ket.json');
  const crosswalk = JSON.parse((await readFile(manifest)).toString('utf8').replace(/^\uFEFF/, ''));
  const mediaRoot = path.resolve(path.dirname(sourceFile), '..', 'media', 'vnjpclub');
  const plan = buildMinnaAudioPlan(source, crosswalk, mediaRoot);
  if (plan.length !== 204) throw new Error(`Expected 204 distinct Minna lesson audio destinations, got ${plan.length}.`);
  const config = await readFile(path.join(root, 'src/main/resources/application-local.yaml'), 'utf8');
  const configured = config.match(/^content\.audio\.path:\s*['"]?([^'"\r\n]+)['"]?\s*$/m)?.[1];
  const contentAudioRoot = process.env.CONTENT_AUDIO_PATH || configured;
  if (!contentAudioRoot) throw new Error('No configured content.audio.path for local media.');
  const destinationBase = path.join(path.resolve(root, contentAudioRoot), 'minna');
  const manifestHash = sha256(await readFile(manifest));
  if (process.argv.includes('--check')) {
    return { ...(await auditMinnaAudioPlan(plan, destinationBase)), source_hash: sha256(sourceBytes), manifest_hash: manifestHash };
  }
  const result = await copyMinnaAudioPlan(plan, destinationBase);
  return { ...result, source_hash: sha256(sourceBytes), manifest_hash: manifestHash };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = path.resolve(process.argv[2] ?? '.');
  const source = process.argv[3];
  if (!source) throw new Error('Pass the supplied luyen_nghe.json path explicitly.');
  const crosswalk = process.argv.slice(4).find((argument) => argument !== '--check');
  console.log(JSON.stringify(await syncMinnaAudio(root, path.resolve(source), crosswalk)));
}
