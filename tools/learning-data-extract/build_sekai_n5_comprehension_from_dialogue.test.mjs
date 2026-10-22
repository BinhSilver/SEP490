import test from 'node:test';
import assert from 'node:assert/strict';
import { buildExercises } from './build_sekai_n5_comprehension_from_dialogue.mjs';

function lesson() {
  return {
    owned_lesson_code: 'LP-OWNED-SEKAI-N5-L01',
    level: 'N5',
    source_lesson_number: 1,
    source_file: 'data/seed_jlas/sekai/sekai-n5/json/bai-01.json',
    source_sha256: 'a'.repeat(64),
    content: { hoiThoai: { canh: [{ ma: 'c1', ten: 'Ở lớp', loi: [
      { ja: 'これは本です。', vi: 'Đây là quyển sách.', tieng: { 'chuan-nu': 'a.mp3' } },
      { ja: 'それは机です。', vi: 'Đó là cái bàn.', tieng: { 'chuan-nu': 'b.mp3' } },
      { ja: 'あれは先生です。', vi: 'Kia là giáo viên.', tieng: { 'chuan-nu': 'c.mp3' } },
      { ja: 'これは何ですか。', vi: 'Đây là cái gì?', tieng: { 'chuan-nu': 'd.mp3' } },
    ] }] } },
  };
}

test('builds lesson-bound reading and listening exercises from exact bilingual source lines', () => {
  const rows = buildExercises([lesson()], (file) => ['a.mp3', 'b.mp3', 'c.mp3', 'd.mp3']
    .some((name) => file.endsWith(name)));
  assert.equal(rows.length, 2);
  const reading = rows.find((row) => row.skill === 'READING');
  const listening = rows.find((row) => row.skill === 'LISTENING');
  assert.equal(reading.lesson_code, 'LP-OWNED-SEKAI-N5-L01');
  assert.match(reading.passage_html, /これは本です。/);
  assert.equal(reading.audio_file, null);
  assert.equal(listening.audio_file, 'sekai-n5/audio/chuan-nu/a.mp3');
  assert.equal(listening.transcript_ja, 'これは本です。');
  for (const row of rows) {
    assert.equal(row.questions.length, 1);
    const question = row.questions[0];
    assert.equal(question.options.length, 4);
    assert.equal(new Set(question.options).size, 4);
    assert.equal(question.options[question.correct_option - 1], 'Đây là quyển sách.');
  }
});

test('rejects source scenes without four unique bilingual choices or exact audio', () => {
  const source = lesson();
  source.content.hoiThoai.canh[0].loi[3].vi = 'Đây là quyển sách.';
  assert.throws(() => buildExercises([source], () => true), /four unique Vietnamese meanings/);
  assert.throws(() => buildExercises([lesson()], () => false), /audio file is missing/);
});

test('rejects duplicate scene identities instead of creating ambiguous source codes', () => {
  const source = lesson();
  source.content.hoiThoai.canh.push(source.content.hoiThoai.canh[0]);
  assert.throws(() => buildExercises([source], () => true), /duplicate scene code/);
});
