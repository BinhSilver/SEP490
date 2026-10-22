import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { renderMinnaSql, replaceMinnaBlock } from './build_minna_listening_sql.mjs';

test('canonical Minna SQL keeps every check and a unique clip-bound source ID', async () => {
  const data = JSON.parse(await readFile('data/learning-owned/learning-content.json', 'utf8'));
  const block = renderMinnaSql(data.sections.jlpt_minna_n5_n4_listening,
    data.section_sha256.jlpt_minna_n5_n4_listening);
  assert.equal((block.match(/-- ROW LP-OWNED-MINNA-/g) ?? []).length, 1282);
  assert.match(block, /LP-OWNED-MINNA-N5-L01-LISTEN-c1-L-c1-1/);
  assert.match(block, /LP-OWNED-MINNA-N4-L50-LISTEN-c1-/);
  assert.doesNotMatch(block, /additional_tags.*JLPT/);
  const firstFile = data.sections.jlpt_minna_n5_n4_listening.lessons[0].cau[0].audio_file;
  assert.ok(block.includes(Buffer.from(`\"audio_file\":\"${firstFile}\"`).toString('hex')));
  assert.ok(block.includes(Buffer.from('"distribution_authorization":"JLAS_OWNER_CONFIRMED_TEAM_DISTRIBUTION_2026-10-18"').toString('hex')));
  const sql = await readFile('sql/seeds/learning_owned.sql', 'utf8');
  assert.equal(replaceMinnaBlock(sql, block), sql);
});
