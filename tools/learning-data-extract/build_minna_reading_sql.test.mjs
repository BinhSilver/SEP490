import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { renderMinnaReadingSql, replaceMinnaReadingBlock } from './build_minna_reading_sql.mjs';

test('canonical Minna reading SQL is additive, exact-level, and Learning-only', async () => {
  const data = JSON.parse(await readFile('data/learning-owned/learning-content.json', 'utf8'));
  const section = data.sections.jlpt_minna_n5_n4_reading;
  const block = renderMinnaReadingSql(section, data.section_sha256.jlpt_minna_n5_n4_reading);
  assert.equal((block.match(/-- ROW LP-OWNED-MINNA-/g) ?? []).length, 187);
  // Owner 2026-10-21 (OD-09): the three picture-less lesson-20 checks are switched off, never deleted.
  assert.match(block, /UPDATE assessment_question SET is_active=0 WHERE is_active=1 AND source_code IN \(/);
  for (const code of ['P032', 'P033', 'P034']) {
    assert.ok(block.includes(`'LP-OWNED-MINNA-N5-L20-READ-${code}-Q001'`), code);
    assert.doesNotMatch(block, new RegExp(`-- ROW LP-OWNED-MINNA-N5-L20-READ-${code}-Q001`));
  }
  assert.match(block, /LP-OWNED-MINNA-N5-L01-READ-P\d{3}-Q\d{3}/);
  assert.match(block, /LP-OWNED-MINNA-N4-L50-READ-P\d{3}-Q\d{3}/);
  assert.doesNotMatch(block, /additional_tags|DELETE FROM|FOREIGN_KEY_CHECKS/);
  assert.ok(block.includes(Buffer.from('"learning_path_only":true').toString('hex')));
  assert.ok(block.includes(Buffer.from('"smart_test_eligible":false').toString('hex')));
  assert.ok(block.includes(Buffer.from('"distribution_authorization":"JLAS_OWNER_CONFIRMED_TEAM_DISTRIBUTION_2026-10-18"').toString('hex')));
  const sql = await readFile('sql/seeds/learning_owned.sql', 'utf8');
  assert.equal(replaceMinnaReadingBlock(sql, block), sql);
});

