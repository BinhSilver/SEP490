import test from 'node:test';
import assert from 'node:assert/strict';
import { renderSqlBlock, replaceSeedSection } from './build_sekai_n5_comprehension_sql.mjs';

const row = (skill, code) => ({
  source_code: code,
  lesson_code: 'LP-OWNED-SEKAI-N5-L01',
  jlpt_level: 'N5',
  skill,
  scene_code: 'c1',
  scene_title: 'Ở lớp',
  source_file: 'sekai/bai-01.json',
  source_sha256: 'a'.repeat(64),
  passage_html: '<p>これは本です。</p>',
  transcript_ja: skill === 'LISTENING' ? 'これは本です。' : null,
  audio_file: skill === 'LISTENING' ? 'sekai-n5/audio/chuan-nu/line.mp3' : null,
  audio_url: skill === 'LISTENING' ? '/uploads/content-audio/sekai-n5/audio/chuan-nu/line.mp3' : undefined,
  question_prompt: skill === 'LISTENING' ? 'Nghe câu thoại và chọn nghĩa đúng.' : 'Đọc đoạn hội thoại và chọn nghĩa đúng.',
  questions: [{ prompt: 'Câu thoại đầu tiên có nghĩa là gì?', answer_text: 'Đây là sách.',
    options: ['Đây là sách.', 'Đó là bàn.', 'Kia là trường.', 'Đây là gì?'], correct_option: 1,
    explanation: 'これは本です。 — Đây là sách.' }],
});

test('renders idempotent owned R/L rows with exact course and audio provenance', () => {
  const block = renderSqlBlock({
    exercises: [row('READING', 'LP-OWNED-SEKAI-N5-L01-READ-c1'),
      row('LISTENING', 'LP-OWNED-SEKAI-N5-L01-LISTEN-c1')],
  }, 'b'.repeat(64));
  assert.equal((block.match(/INSERT INTO assessment_question/g) || []).length, 2);
  assert.match(block, /LP-OWNED-SEKAI-N5-L01-LISTEN-c1/);
  assert.match(block, /\/uploads\/content-audio\/sekai-n5\/audio\/chuan-nu\/line\.mp3/);
  assert.ok(block.includes(Buffer.from('"additional_tags":["JLPT"]', 'utf8').toString('hex')));
  assert.match(block, /ON DUPLICATE KEY UPDATE/);
  assert.doesNotMatch(block, /DROP TABLE|TRUNCATE|DELETE FROM/i);
});

test('replaces only its own marked seed section and preserves other SQL', () => {
  const first = renderSqlBlock({ exercises: [row('READING', 'LP-OWNED-SEKAI-N5-L01-READ-c1')] }, 'b'.repeat(64));
  const second = renderSqlBlock({ exercises: [row('READING', 'LP-OWNED-SEKAI-N5-L02-READ-c1')] }, 'c'.repeat(64));
  const initial = '-- base\n' + first;
  const updated = replaceSeedSection(initial, second);
  assert.match(updated, /-- base/);
  assert.match(updated, /N5-L02-READ-c1/);
  assert.doesNotMatch(updated, /N5-L01-READ-c1/);
  assert.equal((replaceSeedSection(updated, second).match(/-- BEGIN data\/learning-owned\/learning-content\.json#sections\.sekai_n5_comprehension/g) || []).length, 1);
});

test('replacing an identical section preserves a Windows CRLF seed byte-for-byte', () => {
  const block = renderSqlBlock({ exercises: [row('READING', 'LP-OWNED-SEKAI-N5-L01-READ-c1')] }, 'b'.repeat(64));
  const seed = `-- base\n${block}`.replace(/\n/g, '\r\n');
  assert.equal(replaceSeedSection(seed, block), seed);
});
