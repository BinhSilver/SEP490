import { readFile } from 'node:fs/promises';
import path from 'node:path';

const root = path.resolve(process.argv[2] ?? '.');
const data = JSON.parse(await readFile(path.join(root, 'data/learning-owned/learning-content.json'), 'utf8'));
const lessons = data.sections?.jlpt_minna_n5_n4_listening?.lessons ?? [];
const clips = lessons.flatMap((lesson) => lesson.cau.map((clip) => ({ lesson: lesson.bai, clip: clip.ma,
  url: clip.audio_url })));
if (clips.length !== 204) throw new Error(`Expected 204 source audio clips, got ${clips.length}.`);
let cursor = 0;
const failures = [];
const successes = [];
async function worker() {
  while (cursor < clips.length) {
    const clip = clips[cursor++];
    try {
      const response = await fetch(clip.url, { method: 'HEAD', signal: AbortSignal.timeout(15_000) });
      const type = response.headers.get('content-type') ?? '';
      const size = Number(response.headers.get('content-length'));
      if (response.status !== 200 || !type.toLowerCase().startsWith('audio/') || !(size > 0)) {
        failures.push({ ...clip, status: response.status, type, size });
      } else successes.push({ ...clip, size });
    } catch (error) {
      failures.push({ ...clip, error: error.message });
    }
  }
}
await Promise.all(Array.from({ length: 6 }, () => worker()));
const result = { checked: clips.length, playableByHead: successes.length, failed: failures.length,
  bytes: successes.reduce((total, item) => total + item.size, 0), failures };
console.log(JSON.stringify(result));
if (failures.length) process.exitCode = 1;
