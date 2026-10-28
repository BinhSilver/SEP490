// [JLAS-W0108] Per-clip / per-check audit of the canonical Minna N5/N4 listening section.
// Read-only: checks answer keys against the source excerpt, script/translation coverage, the real MP3 stream
// (frame parse -> decodable duration, not just SHA) against the script timestamps, and optionally that the running
// app serves each clip. Usage:
//   node tools/learning-data-extract/audit_minna_listening.mjs . <content-audio-dir> [report.json] [--base=http://localhost:8080]
import { readFile, writeFile, stat } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const BLANK = /［[\s　]*］/;
// Particles and the copula legitimately follow a noun after a space; they are not part of the preceding word.
const WORD_AFTER_NOUN = /^(は|が|を|に|へ|で|と|も|の|や|か|から|まで|より|です|でした|じゃ|では|だ|ね|よ)$/;

/** Text as heard: no spaces (furigana removal leaves "来 ました"), no sentence punctuation. */
export function normalizeJa(text) {
  return String(text ?? '').replace(/[\s　]+/g, '').replace(/[。、．，！？!?「」『』…]/g, '');
}

/** Sums MPEG-1/2/2.5 Layer III frames after an optional ID3v2 tag. Returns null when no frame chain is found. */
export function mp3Stream(buffer) {
  const BITRATES = { V1: [0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320],
    V2: [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160] };
  const RATES = { 3: [44100, 48000, 32000], 2: [22050, 24000, 16000], 0: [11025, 12000, 8000] };
  let offset = 0;
  if (buffer.length > 10 && buffer.toString('latin1', 0, 3) === 'ID3') {
    const size = (buffer[6] & 0x7f) << 21 | (buffer[7] & 0x7f) << 14 | (buffer[8] & 0x7f) << 7 | (buffer[9] & 0x7f);
    offset = 10 + size;
  }
  let frames = 0; let seconds = 0; let skipped = 0;
  while (offset + 4 <= buffer.length) {
    const b1 = buffer[offset + 1]; const b2 = buffer[offset + 2];
    const version = (b1 >> 3) & 0x3; const layer = (b1 >> 1) & 0x3;
    const bitrateIndex = b2 >> 4; const rateIndex = (b2 >> 2) & 0x3; const padding = (b2 >> 1) & 0x1;
    const valid = buffer[offset] === 0xff && (b1 & 0xe0) === 0xe0 && version !== 1 && layer === 1
      && bitrateIndex > 0 && bitrateIndex < 15 && rateIndex < 3;
    if (!valid) { offset++; skipped++; continue; }
    const bitrate = (version === 3 ? BITRATES.V1 : BITRATES.V2)[bitrateIndex] * 1000;
    const sampleRate = RATES[version][rateIndex];
    const samples = version === 3 ? 1152 : 576;
    const length = Math.floor((samples / 8) * bitrate / sampleRate) + padding;
    if (length <= 4) { offset++; skipped++; continue; }
    frames++; seconds += samples / sampleRate; offset += length;
  }
  return frames === 0 ? null : { frames, seconds, skippedBytes: skipped };
}

/** Pure checks on one lesson's canonical data; media/HTTP results are merged in by the caller. */
export function auditLesson(lesson) {
  const findings = [];
  const add = (level, scope, code, detail) => findings.push({ level, lesson: lesson.bai, cap: lesson.cap, scope, code, detail });
  const clips = new Map(lesson.cau.map((clip) => [clip.ma, clip]));
  for (const clip of lesson.cau) {
    const lines = clip.muc.flatMap((item) => item.script ?? []);
    if (lines.length === 0) add('ERROR', clip.ma, 'NO_SCRIPT', clip.ten);
    const noVi = lines.filter((line) => !String(line.vi ?? '').trim()).length;
    if (noVi) add('WARN', clip.ma, 'SCRIPT_LINE_WITHOUT_TRANSLATION', `${noVi}/${lines.length}`);
  }
  for (const check of lesson.kiem_tra) {
    const clipCode = check.id.match(/^[LW]-(c\d+)-/)?.[1];
    const clip = clips.get(clipCode);
    const scope = check.id;
    if (!clip) { add('ERROR', scope, 'UNMAPPED_CLIP', ''); continue; }
    const options = check.phuong_an.map((option) => option.text);
    const answer = check.phuong_an.find((option) => String(option.k) === String(check.dap_an))?.text;
    if (answer == null) { add('ERROR', scope, 'KEY_NOT_AN_OPTION', check.dap_an); continue; }
    const excerpt = normalizeJa(check.trich_ja);
    const lines = clip.muc.flatMap((item) => item.script ?? []);
    if (!excerpt) add('ERROR', scope, 'NO_SOURCE_EXCERPT', '');
    else if (!lines.some((line) => normalizeJa(line.ja).includes(excerpt) || excerpt.includes(normalizeJa(line.ja))))
      add('WARN', scope, 'EXCERPT_NOT_IN_SCRIPT', check.trich_ja);
    if (!String(check.giai_thich ?? '').trim()) add('WARN', scope, 'NO_EXPLANATION', check.id.startsWith('W-') ? 'W' : 'L');
    if (!String(check.trich_vi ?? '').trim()) add('WARN', scope, 'NO_EXCERPT_TRANSLATION', '');
    // Minna spaces between words ("どうぞ よろしく") on purpose; a space inside one word ("来 ました", "初 めまして")
    // is left over from removed furigana. Only a split that the clean script line writes as one word counts.
    const cleanScript = lines.map((line) => String(line.ja ?? '')).join('|');
    const splits = [check.cau_hoi, ...options].flatMap((text) => [...String(text).matchAll(/([一-龯々]+) ([ぁ-ん]+)/g)])
      .filter((match) => !WORD_AFTER_NOUN.test(match[2]) && cleanScript.includes(match[1] + match[2]));
    if (splits.length) add('INFO', scope, 'WORD_SPLIT_BY_SPACE', splits.map((match) => match[0]).join(' / '));
    if (check.id.startsWith('W-')) {
      if (!BLANK.test(check.cau_hoi)) { add('ERROR', scope, 'W_WITHOUT_BLANK', check.cau_hoi); continue; }
      const filled = (text) => normalizeJa(check.cau_hoi.replace(BLANK, text));
      if (filled(answer) !== excerpt) add('ERROR', scope, 'KEY_DOES_NOT_REBUILD_EXCERPT', `${answer} | ${check.trich_ja}`);
      const alsoRight = options.filter((option) => option !== answer && filled(option) === excerpt);
      if (alsoRight.length) add('ERROR', scope, 'DISTRACTOR_ALSO_REBUILDS_EXCERPT', alsoRight.join(' / '));
    }
  }
  return findings;
}

async function main() {
  const root = path.resolve(process.argv[2] ?? '.');
  const audioDir = process.argv[3];
  const reportPath = process.argv[4] && !process.argv[4].startsWith('--') ? process.argv[4] : null;
  const base = process.argv.find((arg) => arg.startsWith('--base='))?.slice('--base='.length);
  const data = JSON.parse(await readFile(path.join(root, 'data/learning-owned/learning-content.json'), 'utf8'));
  const section = data.sections.jlpt_minna_n5_n4_listening;
  const findings = [];
  const clipsOut = [];
  for (const lesson of section.lessons) {
    findings.push(...auditLesson(lesson));
    for (const clip of lesson.cau) {
      const add = (level, code, detail) => findings.push({ level, lesson: lesson.bai, cap: lesson.cap, scope: clip.ma, code, detail });
      const relative = String(clip.audio_file ?? '').replace(/^audio\//, '');
      const row = { lesson: lesson.bai, cap: lesson.cap, clip: clip.ma, file: clip.audio_file };
      const maxT = Math.max(0, ...clip.muc.flatMap((item) => item.script ?? []).map((line) => Number(line.t?.[1] ?? 0)),
        ...lesson.kiem_tra.filter((check) => check.id.includes(`-${clip.ma}-`)).map((check) => Number(check.t?.[1] ?? 0)));
      row.maxTimestamp = maxT;
      if (audioDir) {
        const file = path.join(audioDir, 'minna', relative);
        try {
          const info = await stat(file);
          const stream = mp3Stream(await readFile(file));
          row.bytes = info.size;
          row.seconds = stream ? Math.round(stream.seconds * 100) / 100 : null;
          if (!stream) add('ERROR', 'AUDIO_NOT_DECODABLE_MP3', relative);
          else if (maxT > stream.seconds + 1) add('ERROR', 'TIMESTAMP_BEYOND_AUDIO', `${maxT}s > ${row.seconds}s`);
        } catch { add('ERROR', 'AUDIO_FILE_MISSING', relative); }
      }
      if (base) {
        const url = `${base}/uploads/content-audio/minna/${relative}`;
        try {
          const response = await fetch(url, { headers: { Range: 'bytes=0-1' } });
          row.http = response.status;
          row.contentType = response.headers.get('content-type');
          if (![200, 206].includes(response.status)) add('ERROR', 'AUDIO_NOT_SERVED', `${response.status} ${url}`);
          else if (!String(row.contentType).startsWith('audio/')) add('WARN', 'AUDIO_CONTENT_TYPE', String(row.contentType));
          else if (response.status !== 206) add('INFO', 'NO_RANGE_SUPPORT', url);
          await response.arrayBuffer();
        } catch (error) { add('ERROR', 'AUDIO_FETCH_FAILED', String(error.message)); }
      }
      clipsOut.push(row);
    }
  }
  const summary = {};
  for (const finding of findings) {
    const key = `${finding.cap} ${finding.level} ${finding.code}`;
    summary[key] = (summary[key] ?? 0) + 1;
  }
  const checks = section.lessons.reduce((sum, lesson) => sum + lesson.kiem_tra.length, 0);
  console.log(`clips=${clipsOut.length} checks=${checks} findings=${findings.length}`);
  for (const [key, count] of Object.entries(summary).sort()) console.log(`${key}: ${count}`);
  if (reportPath) await writeFile(reportPath, JSON.stringify({ generated: 'audit_minna_listening', summary, clips: clipsOut, findings }, null, 1));
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main();
