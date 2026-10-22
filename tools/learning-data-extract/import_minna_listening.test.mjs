import test from 'node:test';
import assert from 'node:assert/strict';
import { buildSection } from './import_minna_listening.mjs';

const lesson = (number) => ({
  bai: number, cap: number <= 25 ? 'N5' : 'N4', cau: [{
    ma: 'c1', audio_url: 'https://www.vnjpclub.com/Audio/minnaln/minna1/a/01A-1.mp3',
    audio_file: `audio/vnjpclub/${number <= 25 ? 'N5/minna-so-cap-1' : 'N4/minna-so-cap-2'}/bai-${String(number).padStart(2, '0')}/01A-1.mp3`,
    muc: [{ script: [{ ja: 'はい。' }] }],
  }],
  kiem_tra: [{ id: 'L-c1-1', cau_hoi: '何ですか。', phuong_an: [
    { k: '0', text: 'A' }, { k: '1', text: 'B' }, { k: '2', text: 'C' }, { k: '3', text: 'D' },
  ], dap_an: '1' }],
});
const source = () => Array.from({ length: 50 }, (_, index) => lesson(index + 1));

test('maps all 50 source lessons by number and level without dropping checks', () => {
  const section = buildSection(source(), 'a'.repeat(64));
  assert.deepEqual(section.counts, {
    N5: { lessons: 25, clips: 25, checks: 25 },
    N4: { lessons: 25, clips: 25, checks: 25 },
  });
  assert.equal(section.lessons[25].owned_lesson_code, 'LP-OWNED-SEKAI-N4-L26');
  assert.equal(section.publication, 'OWNER_AUTHORIZED_TEAM_SEED');
  assert.equal(section.rights, 'OWNER_CONFIRMED_TEAM_DISTRIBUTION');
  assert.equal(section.distribution_authorization, 'JLAS_OWNER_CONFIRMED_TEAM_DISTRIBUTION_2026-10-18');
});

test('rejects a check whose answer does not belong to its audio clip', () => {
  const rows = source();
  rows[0].kiem_tra[0].id = 'L-c2-1';
  assert.throws(() => buildSection(rows, 'a'.repeat(64)), /Invalid Minna check/);
});

test('rejects an unsafe audio basename from the source URL', () => {
  const rows = source();
  rows[0].cau[0].audio_url = 'https://www.vnjpclub.com/Audio/minnaln/minna1/a/unsafe%20name.mp3';
  assert.throws(() => buildSection(rows, 'a'.repeat(64)), /Invalid Minna local audio mapping/);
});
