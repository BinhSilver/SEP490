import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

/**
 * Check if ffprobe is installed on PATH.
 */
export function hasFFprobe() {
  try {
    const res = spawnSync('ffprobe', ['-version'], { stdio: 'ignore' });
    return res.status === 0;
  } catch {
    return false;
  }
}

/**
 * Get duration of audio file using ffprobe.
 */
export function getAudioDuration(filePath) {
  const res = spawnSync('ffprobe', [
    '-v', 'error',
    '-show_entries', 'format=duration',
    '-of', 'default=noprint_wrappers=1:nokey=1',
    filePath
  ], { encoding: 'utf8' });
  if (res.status !== 0 || res.error) {
    return null;
  }
  const val = parseFloat(String(res.stdout).trim());
  return isNaN(val) ? null : val;
}

/**
 * Extract dialogue line timestamps from learning content.
 * Returns a map of normalized audio path -> { lastTimestamp, lineCount, details }.
 * Primary field used: `cau[].muc[].script[].t` (array of [start, end] in seconds)
 * and `cau[].muc[].t` in `sections.jlpt_minna_n5_n4_listening`.
 */
export function extractDialogueTimestamps(content) {
  const map = new Map();

  // 1. Minna N5 / N4 listening
  const minna = content.sections?.jlpt_minna_n5_n4_listening;
  if (minna?.lessons && Array.isArray(minna.lessons)) {
    for (const lesson of minna.lessons) {
      for (const cau of lesson.cau || []) {
        let maxTime = 0;
        let lineCount = 0;
        for (const muc of cau.muc || []) {
          if (Array.isArray(muc.t) && muc.t.length >= 2 && typeof muc.t[1] === 'number') {
            if (muc.t[1] > maxTime) maxTime = muc.t[1];
          }
          for (const line of muc.script || []) {
            lineCount++;
            if (Array.isArray(line.t) && line.t.length >= 2 && typeof line.t[1] === 'number') {
              if (line.t[1] > maxTime) maxTime = line.t[1];
            }
          }
        }

        const candidatePaths = [
          cau.audio_file,
          cau.source_audio_file,
          cau.audio_url
        ].filter(Boolean);

        for (const p of candidatePaths) {
          // N5 lesson 1 and N4 lesson 26 both ship "01A-1.mp3": key Minna clips by lesson + file, never by file alone.
          const norm = lessonAudioKey(lesson.bai, p);
          if (norm) {
            map.set(norm, {
              lastTimestamp: maxTime,
              lineCount,
              clipId: cau.ma,
              sourceSection: 'jlpt_minna_n5_n4_listening',
              timestampField: 'muc[].script[].t'
            });
          }
        }
      }
    }
  }

  // 2. Sekai N5 / N4 combined audio
  const sekai = content.sections?.jlpt_sekai_n5_n4;
  if (sekai?.lessons && Array.isArray(sekai.lessons)) {
    for (const lesson of sekai.lessons) {
      const gop = lesson.content?.gop?.giong;
      if (gop) {
        for (const [voiceKey, voiceData] of Object.entries(gop)) {
          if (voiceData.file && Array.isArray(voiceData.moc)) {
            let maxTime = 0;
            for (const item of voiceData.moc) {
              if (Array.isArray(item) && item.length >= 2) {
                const end = item[0] + item[1];
                if (end > maxTime) maxTime = end;
              }
            }
            const norm = normalizeAudioKey(voiceData.file);
            if (norm) {
              map.set(norm, {
                lastTimestamp: maxTime,
                lineCount: voiceData.moc.length,
                clipId: voiceData.file,
                sourceSection: 'jlpt_sekai_n5_n4',
                timestampField: 'content.gop.giong.*.moc'
              });
            }
          }
        }
      }
    }
  }

  return map;
}

/** "bai-01/01a-1.mp3" — a Minna clip's file name within its lesson (file names repeat across N5 and N4 lessons). */
export function lessonAudioKey(lessonNo, p) {
  const n = Number(lessonNo);
  const base = normalizeAudioKey(p);
  return Number.isInteger(n) && n > 0 && base ? `bai-${String(n).padStart(2, '0')}/${base}` : '';
}

/** The lookup keys of one manifest path: its "bai-NN/file" form when the path names the lesson, then the bare file. */
export function manifestAudioKeys(p) {
  const m = /bai-(\d+)\/([^/]+)$/i.exec(String(p ?? '').replace(/\\/g, '/'));
  return [m ? lessonAudioKey(m[1], m[2]) : '', normalizeAudioKey(p)].filter(Boolean);
}

export function normalizeAudioKey(p) {
  if (!p) return '';
  const clean = String(p).replace(/\\/g, '/');
  const basename = path.posix.basename(clean);
  return basename.toLowerCase();
}

/**
 * Compare manifest and audio directory against dialogue timestamps.
 */
export function checkAudioDurations({ audioDir, manifestPath, contentPath, durationGetter = getAudioDuration }) {
  if (!fs.existsSync(manifestPath)) {
    throw new Error(`Manifest not found: ${manifestPath}`);
  }
  if (!fs.existsSync(contentPath)) {
    throw new Error(`Content file not found: ${contentPath}`);
  }

  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  const content = JSON.parse(fs.readFileSync(contentPath, 'utf8'));
  const timestampMap = extractDialogueTimestamps(content);

  const results = [];
  const groups = manifest.groups || {};

  for (const [groupName, group] of Object.entries(groups)) {
    for (const entry of group.entries || []) {
      const paths = entry.paths || [];
      for (const relPath of paths) {
        const fullPath = path.resolve(audioDir, relPath);
        if (!fs.existsSync(fullPath)) {
          continue;
        }

        // The file on disk may be a renamed copy (vnjpclub/…/a_01A-1.mp3) while the dialogue keeps the source name
        // (01A-1.mp3): look the timestamps up under every path the manifest lists for this clip.
        const tsInfo = [relPath, ...paths].flatMap(manifestAudioKeys).map((key) => timestampMap.get(key)).find(Boolean);

        const realDuration = durationGetter(fullPath);
        const lastTimestamp = tsInfo ? tsInfo.lastTimestamp : null;
        let diff = null;
        let isFlagged = false;
        let flagReason = null;

        if (realDuration !== null && lastTimestamp !== null) {
          diff = realDuration - lastTimestamp;
          if (lastTimestamp > realDuration) {
            isFlagged = true;
            flagReason = 'Mốc vượt quá độ dài file';
          } else if (Math.abs(diff) > 2.0) {
            isFlagged = true;
            flagReason = 'Chênh lệch > 2 giây';
          }
        }

        results.push({
          group: groupName,
          clip: entry.clip,
          file: relPath,
          fullPath,
          realDuration,
          lastTimestamp,
          diff,
          isFlagged,
          flagReason,
          timestampField: tsInfo?.timestampField || 'N/A'
        });
      }
    }
  }

  return results;
}

export function runCLI() {
  const audioDir = process.env.CONTENT_AUDIO_PATH || 'data/content-audio';
  const manifestPath = 'data/learning-owned/learning-audio-manifest.json';
  const contentPath = 'data/learning-owned/learning-content.json';
  const outPath = 'target/audio-duration-report.json';

  console.log('=== J-LAS DIALOGUE AUDIO DURATION CHECKER ===');
  console.log(`Audio directory: ${audioDir}`);
  console.log(`Manifest: ${manifestPath}`);
  console.log(`Content: ${contentPath}`);

  if (!hasFFprobe()) {
    console.warn('\n[THÔNG BÁO] Không tìm thấy "ffprobe" trên PATH.');
    console.warn('Theo quy định trong docs/learning/tin-nhan-antigravity-2026-10-27.md §6:');
    console.warn('-> "Không có ffprobe trên PATH thì báo và dừng, không cài gì."');
    console.warn('Tiến trình kiểm tra audio dừng tại đây.\n');
    return { stopped: true, reason: 'ffprobe not on PATH' };
  }

  console.log('Đang kiểm tra độ dài audio qua ffprobe...');
  const results = checkAudioDurations({ audioDir, manifestPath, contentPath });

  const summary = {
    generatedAt: new Date().toISOString(),
    totalAudioChecked: results.length,
    flaggedCount: results.filter(r => r.isFlagged).length,
    timestampFieldUsed: 'muc[].script[].t (start_seconds, end_seconds) in sections.jlpt_minna_n5_n4_listening',
    results
  };

  const outDir = path.dirname(outPath);
  if (!fs.existsSync(outDir)) {
    fs.mkdirSync(outDir, { recursive: true });
  }
  fs.writeFileSync(outPath, JSON.stringify(summary, null, 2), 'utf8');
  console.log(`Đã ghi báo cáo vào: ${outPath}`);

  console.log('\n--- KẾT QUẢ KIỂM TRA ĐỘ DÀI ---');
  console.log('File | Độ dài thật (s) | Mốc cuối (s) | Chênh lệch (s) | Trạng thái');
  console.log('-----------------------------------------------------------------------');
  for (const r of results.slice(0, 30)) {
    const status = r.isFlagged ? `[CẢNH BÁO: ${r.flagReason}]` : 'OK';
    console.log(`${r.file} | ${r.realDuration?.toFixed(2)} | ${r.lastTimestamp?.toFixed(2)} | ${r.diff?.toFixed(2)} | ${status}`);
  }
  if (results.length > 30) {
    console.log(`... và ${results.length - 30} file khác (xem chi tiết trong ${outPath})`);
  }

  return summary;
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'))) {
  runCLI();
}
