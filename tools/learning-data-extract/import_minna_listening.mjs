import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readLearningSection, writeLearningSection } from './learning_content_store.mjs';

const SECTION = 'jlpt_minna_n5_n4_listening';
const sha256 = (value) => createHash('sha256').update(value).digest('hex');

/** Preserve the supplied source in the one canonical Learning data file. */
export function buildSection(source, sourceHash) {
  if (!Array.isArray(source) || source.length !== 50 || !/^[a-f0-9]{64}$/.test(sourceHash)) {
    throw new Error('Expected exactly 50 checksum-identified Minna lessons.');
  }
  const counts = { N5: { lessons: 0, clips: 0, checks: 0 }, N4: { lessons: 0, clips: 0, checks: 0 } };
  const audioAnomalies = [];
  const lessons = source.map((lesson, index) => {
    const number = index + 1;
    const level = number <= 25 ? 'N5' : 'N4';
    if (lesson.bai !== number || lesson.cap !== level || !Array.isArray(lesson.cau)
        || !lesson.cau.length || !Array.isArray(lesson.kiem_tra) || !lesson.kiem_tra.length) {
      throw new Error(`Invalid Minna lesson number, level or content at ${number}.`);
    }
    const clips = new Map();
    for (const clip of lesson.cau) {
      if (!/^c\d+$/.test(clip.ma) || clips.has(clip.ma)
          || !/^https:\/\/www\.vnjpclub\.com\/Audio\/[^?#]+\.mp3$/.test(clip.audio_url ?? '')
          || !Array.isArray(clip.muc) || !clip.muc.length
          || clip.muc.some((item) => !item.script?.length || !item.script.every((line) => line.ja))) {
        throw new Error(`Invalid Minna audio/script mapping at lesson ${number}, clip ${clip.ma}.`);
      }
      if (!clip.audio_url.includes('/Audio/minnaln/')) {
        audioAnomalies.push({ lesson: number, clip: clip.ma, audio_url: clip.audio_url });
      }
      const localPrefix = `audio/vnjpclub/${level}/${level === 'N5' ? 'minna-so-cap-1' : 'minna-so-cap-2'}/bai-${String(number).padStart(2, '0')}/`;
      const audioBasename = new URL(clip.audio_url).pathname.split('/').at(-1);
      if (typeof clip.audio_file !== 'string' || !clip.audio_file.toLowerCase().endsWith('.mp3')
          || !/^[A-Za-z0-9_-]+\.mp3$/.test(audioBasename ?? '')) {
        throw new Error(`Invalid Minna local audio mapping at lesson ${number}, clip ${clip.ma}.`);
      }
      clip.source_audio_file = clip.audio_file;
      clip.audio_file = `${localPrefix}${audioBasename}`;
      clips.set(clip.ma, clip);
    }
    const ids = new Set();
    const checksByClip = new Map([...clips.keys()].map((code) => [code, 0]));
    for (const check of lesson.kiem_tra) {
      const clipCode = check.id?.match(/^[LW]-(c\d+)-/)?.[1];
      if (!clips.has(clipCode) || ids.has(check.id) || !check.cau_hoi
          || check.phuong_an?.length !== 4 || !check.phuong_an.every((option) => option.text)
          || new Set(check.phuong_an.map((option) => option.text.trim().replace(/\s+/g, ' ').toLowerCase())).size !== 4
          || !check.phuong_an.some((option) => String(option.k) === String(check.dap_an))) {
        throw new Error(`Invalid Minna check at lesson ${number}, ID ${check.id}.`);
      }
      ids.add(check.id);
      checksByClip.set(clipCode, checksByClip.get(clipCode) + 1);
    }
    if ([...checksByClip.values()].some((count) => count === 0)) {
      throw new Error(`A Minna audio clip at lesson ${number} has no check.`);
    }
    counts[level].lessons++;
    counts[level].clips += clips.size;
    counts[level].checks += ids.size;
    return { ...lesson, owned_lesson_code: `LP-OWNED-SEKAI-${level}-L${String(number).padStart(2, '0')}` };
  });
  return {
    kind: 'JLPT_MINNA_CHOUKAI_TASK25_LESSON_AUDIO',
    source_name: 'minna_N5_N4_day_du/du_lieu_goc/luyen_nghe.json',
    source_sha256: sourceHash,
    source_owner: 'vnjpclub',
    audio_status: 'LOCAL_FILES_REQUIRED',
    audio_anomalies: audioAnomalies,
    publication: 'OWNER_AUTHORIZED_TEAM_SEED',
    study_eligible: true,
    quiz_eligible: false,
    smart_test_eligible: false,
    rights: 'OWNER_CONFIRMED_TEAM_DISTRIBUTION',
    distribution_authorization: 'JLAS_OWNER_CONFIRMED_TEAM_DISTRIBUTION_2026-10-18',
    counts,
    lessons,
  };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = path.resolve(process.argv[2] ?? '.');
  const input = process.argv[3];
  if (!input) throw new Error('Pass the user-supplied Minna JSON path explicitly.');
  const bytes = await readFile(path.resolve(input));
  const section = buildSection(JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/, '')), sha256(bytes));
  if (process.argv.includes('--check')) {
    const current = await readLearningSection(root, SECTION);
    if (JSON.stringify(current) !== JSON.stringify(section)) throw new Error('Canonical Minna section is stale.');
  } else {
    await writeLearningSection(root, SECTION, section);
  }
  console.log(JSON.stringify(section.counts));
}
