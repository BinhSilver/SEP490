import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { buildMinnaAudioPlan, copyMinnaAudioPlan } from './sync_minna_local_audio.mjs';

test('audio sync resolves exact URL crosswalks and copies only verified source media', async () => {
  const temp = await mkdtemp(path.join(os.tmpdir(), 'jlas-minna-audio-'));
  try {
    const mediaRoot = path.join(temp, 'media', 'vnjpclub');
    const source = path.join(mediaRoot, 'N5', 'minna-so-cap-1', 'a_01A-1.mp3');
    await mkdir(path.dirname(source), { recursive: true });
    await writeFile(source, Buffer.concat([Buffer.from('ID3'), Buffer.alloc(1200, 7)]));
    const lesson = [{ bai: 1, cap: 'N5', cau: [{ ma: 'c1',
      audio_url: 'https://www.vnjpclub.com/Audio/minnaln/minna1/a/01A-1.mp3' }] }];
    const crosswalk = [{ url_audio: lesson[0].cau[0].audio_url,
      file_cuc_bo: 'media/N5/minna-so-cap-1/a_01A-1.mp3', co_file: 'True' }];
    const plan = buildMinnaAudioPlan(lesson, crosswalk, mediaRoot);
    assert.equal(plan.length, 1);
    assert.equal(plan[0].sourcePath, source);
    assert.equal(plan[0].destinationRelative, 'vnjpclub/N5/minna-so-cap-1/bai-01/01A-1.mp3');
    const result = await copyMinnaAudioPlan(plan, path.join(temp, 'content-audio', 'minna'));
    assert.equal(result.files_verified, 1);
    assert.equal(result.files_copied, 1);
    assert.deepEqual(await readFile(path.join(temp, 'content-audio', 'minna', ...plan[0].destinationRelative.split('/'))),
      await readFile(source));
  } finally {
    await rm(temp, { recursive: true, force: true });
  }
});

test('audio sync rejects absent or ambiguous exact URL crosswalks', () => {
  const lesson = [{ bai: 1, cap: 'N5', cau: [{ ma: 'c1', audio_url: 'https://www.vnjpclub.com/a.mp3' }] }];
  assert.throws(() => buildMinnaAudioPlan(lesson, [], 'C:/media'), /expected one exact audio crosswalk/i);
  assert.throws(() => buildMinnaAudioPlan(lesson, [
    { url_audio: 'https://www.vnjpclub.com/a.mp3', file_cuc_bo: 'media/N5/a.mp3', co_file: true },
    { url_audio: 'https://www.vnjpclub.com/a.mp3', file_cuc_bo: 'media/N5/b.mp3', co_file: true },
  ], 'C:/media'), /expected one exact audio crosswalk/i);
});
