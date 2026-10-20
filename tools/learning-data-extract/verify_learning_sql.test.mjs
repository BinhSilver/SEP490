import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { verify } from './verify_learning_sql.mjs';

test('consolidated SQL checksum validator accepts Windows CRLF line endings', () => {
  const migration = fs.readFileSync('sql/migrations/022_learning_owned_consolidated.sql', 'utf8');
  const seed = fs.readFileSync('sql/seeds/learning_owned.sql', 'utf8');
  const toCrLf = (text) => text.split(/\r?\n/).join('\r\n');
  assert.match(verify(toCrLf(migration), toCrLf(seed)), /^[0-9a-f]{64}$/);
});

test('one consolidated Learning migration and one seed preserve owned schema and data', () => {
  const migration = fs.readFileSync('sql/migrations/022_learning_owned_consolidated.sql', 'utf8');
  const seed = fs.readFileSync('sql/seeds/learning_owned.sql', 'utf8');
  assert.match(verify(migration, seed), /^[0-9a-f]{64}$/);
  assert.throws(() => verify(`${migration}\nDROP TABLE users;`, seed), /Destructive/);
  assert.throws(() => verify(migration.replaceAll('learning_owned_source_draft', 'missing_table'), seed), /Missing/);
  assert.throws(() => verify(migration, seed.replace(
    'No teammate hierarchy or assessment bank writes.',
    'Changed teammate hierarchy or assessment bank writes.',
  )), /checksum mismatch/);
});

test('a DELETE in the seed is fail-closed: only the scoped communication link prune is allowed', () => {
  const migration = fs.readFileSync('sql/migrations/022_learning_owned_consolidated.sql', 'utf8');
  const seed = fs.readFileSync('sql/seeds/learning_owned.sql', 'utf8');
  const allowed = 'DELETE cv FROM content_vocabulary cv WHERE cv.content_item_id=@item_ba_com_vocabulary_1 AND cv.vocabulary_id<>@vocab_ba_ba_001;';
  assert.match(verify(migration, `${seed}\n${allowed}`), /^[0-9a-f]{64}$/);
  for (const destructive of [
    'DELETE x FROM lesson x WHERE x.id > 0;',
    'DELETE cv FROM content_vocabulary cv;',
    'DELETE cv FROM content_vocabulary cv WHERE cv.content_item_id>0;',
    '  delete from users;',
    'DROP DATABASE jlas;',
    'DROP PROCEDURE IF EXISTS other_proc;',
  ]) assert.throws(() => verify(migration, `${seed}\n${destructive}`), /Destructive|DELETE/, destructive);
});
