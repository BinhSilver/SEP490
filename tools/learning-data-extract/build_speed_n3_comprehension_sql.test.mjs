import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { renderSpeedN3Sql, replaceSpeedN3Block } from './build_speed_n3_comprehension_sql.mjs';
import { readLearningContent } from './learning_content_store.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const data = await readLearningContent(ROOT);
const SECTION = data.sections.jlpt_speed_n3_comprehension;
const HASH = data.section_sha256.jlpt_speed_n3_comprehension;
const copy = () => JSON.parse(JSON.stringify(SECTION));
const decode = (sql, code) => {
  const hex = Buffer.from(code, 'utf8').toString('hex');
  return sql.split('\n').find((line) => line.includes(`VALUES (CONVERT(X'${hex}'`));
};

test('renders one active Learning-only row per graded question, inside one transaction', () => {
  const sql = renderSpeedN3Sql(SECTION, HASH);
  const rows = sql.split('\n').filter((line) => line.startsWith('INSERT INTO assessment_question'));
  assert.equal(rows.length, SECTION.counts.reading_questions + SECTION.counts.listening_questions);
  assert.match(sql, /^-- BEGIN data\/learning-owned\/learning-content\.json#sections\.jlpt_speed_n3_comprehension SHA256 [a-f0-9]{64}/);
  assert.ok(sql.indexOf('START TRANSACTION;') < sql.indexOf('UPDATE assessment_question SET is_active=0'));
  assert.ok(sql.indexOf('UPDATE assessment_question SET is_active=0') < sql.indexOf('INSERT INTO assessment_question'),
    'rows dropped from the canonical set are retired before the upsert re-activates the current ones');
  assert.match(sql, /COMMIT;\n-- END data\/learning-owned\/learning-content\.json#sections\.jlpt_speed_n3_comprehension\n$/);
  assert.doesNotMatch(sql, /\bDELETE\b|\bDROP\b|\bTRUNCATE\b/);
  // MySQL rehearsal 2026-10-20: comparing tag.code with a CONVERT(... USING utf8mb4) literal mixes collations.
  assert.ok(sql.split('\n').includes("SET @owned_speed_n3_tag=(SELECT id FROM tag WHERE code='LP-OWNED-SPEED-N3-COMPREHENSION' LIMIT 1);"));
  assert.ok(!/WHERE [a-z_.]+=CONVERT\(/.test(sql), 'no column is compared with a converted literal');
  const listen = decode(sql, 'LP-OWNED-SPEED-N3-C01-LISTEN-P1-Q001');
  assert.ok(listen, 'listening row present');
  assert.match(listen, /'LISTENING','N3'/);
  assert.match(listen, new RegExp(Buffer.from('/uploads/content-audio/vnjpclub/N3/speed-n3-nghe-hieu/', 'utf8').toString('hex')));
  const read = decode(sql, 'LP-OWNED-SPEED-N3-C01-READ-P1-Q001');
  assert.match(read, /'READING','N3'/);
  assert.match(read, /,NULL,/, 'a reading row has no audio');
  assert.match(sql, new RegExp(Buffer.from('"smart_test_eligible":false', 'utf8').toString('hex')));
  assert.match(sql, new RegExp(Buffer.from('"quiz_eligible":false', 'utf8').toString('hex')));
});

test('fails closed on a stale hash, a held flag change, a broken key or a chapter without two exercises', () => {
  assert.throws(() => renderSpeedN3Sql(SECTION, 'c'.repeat(63)), /checksum/);
  const eligible = copy(); eligible.smart_test_eligible = true;
  assert.throws(() => renderSpeedN3Sql(eligible, HASH), /Learning-only/);
  const key = copy(); key.exercises[0].questions[0].correct_option = 9;
  assert.throws(() => renderSpeedN3Sql(key, HASH), /Invalid Speed Master N3 question/);
  const answer = copy(); answer.exercises[0].questions[0].answer_text = 'khác';
  assert.throws(() => renderSpeedN3Sql(answer, HASH), /Invalid Speed Master N3 question/);
  const missing = copy(); missing.exercises = missing.exercises.filter((e) => !(e.chapter === 5 && e.skill === 'LISTENING' && e.slot === 2));
  assert.throws(() => renderSpeedN3Sql(missing, HASH), /chapter 5 LISTENING/);
  const audio = copy(); audio.exercises.find((e) => e.skill === 'LISTENING').audio_url = 'https://remote.example/a.mp3';
  assert.throws(() => renderSpeedN3Sql(audio, HASH), /audio/);
});

test('the block replaces itself in place and is appended once when absent', () => {
  const block = renderSpeedN3Sql(SECTION, HASH);
  const once = replaceSpeedN3Block('-- head\n', block);
  assert.equal(replaceSpeedN3Block(once, block), once);
  assert.equal(once.split('-- BEGIN data/learning-owned/learning-content.json#sections.jlpt_speed_n3_comprehension').length, 2);
  const crlf = replaceSpeedN3Block('-- head\r\n', block);
  assert.ok(!/[^\r]\n/.test(crlf), 'keeps the file newline style');
});
