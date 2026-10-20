import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

import { validateReleaseGateAudit } from './validate-release-gate-audit.mjs';

const auditSqlUrl = new URL('../../sql/audits/2026-10-03-full-spec-release-gate-audit.sql', import.meta.url);
const schemaUrl = new URL('../../jlas_schema.sql', import.meta.url);
const jlptSetupUrl = new URL('../../src/main/resources/db/migrations/jlpt_full_setup.sql', import.meta.url);

function normalizedCreateTable(sql, table) {
  const match = sql.match(new RegExp(`CREATE\\s+TABLE\\s+${table}\\s*\\([\\s\\S]*?\\)\\s*ENGINE=InnoDB[^;]*;`, 'i'));
  assert.ok(match, `missing CREATE TABLE ${table}`);
  return match[0].replace(/--[^\r\n]*/g, ' ').replace(/\s+/g, ' ').trim().toLowerCase();
}

test('full-spec release audit is read-only and references canonical schema tables', async () => {
  const [auditSql, schemaSql, jlptSetupSql] = await Promise.all([
    readFile(auditSqlUrl, 'utf8'),
    readFile(schemaUrl, 'utf8'),
    readFile(jlptSetupUrl, 'utf8'),
  ]);

  const result = validateReleaseGateAudit({ auditSql, schemaSql });

  assert.deepEqual(result.issues, []);
  assert.equal(result.auditCodes.length, 25);
  assert.equal(new Set(result.auditCodes).size, result.auditCodes.length);
  assert.ok(result.auditCodes.includes('A1_COMPLETED_PATH_WITHOUT_GOAL_PACKAGE'));
  assert.ok(result.auditCodes.includes('D7_SURVEY_ONLY_GOAL_REQUIRES_ASSESSMENT'));
  assert.doesNotMatch(auditSql, /\bjlpt_(?:exam_attempt|exam_section|section_result)\b/i);
  assert.doesNotMatch(auditSql, /@migration_010_cutover/i);
  for (const table of ['jlpt_part', 'jlpt_mondai', 'jlpt_question', 'jlpt_option', 'jlpt_audio_clip']) {
    assert.match(auditSql, new RegExp(`\\b${table}\\b`, 'i'));
    assert.match(schemaSql, new RegExp(`CREATE\\s+TABLE\\s+${table}\\b`, 'i'));
  }
  assert.doesNotMatch(schemaSql, /CREATE\s+TABLE\s+jlpt_(?:exam_attempt|exam_section|section_result)\b/i);
  for (const table of ['jlpt_exam', 'jlpt_part', 'jlpt_question_type', 'jlpt_mondai', 'jlpt_passage',
    'jlpt_question', 'jlpt_option', 'jlpt_audio_clip', 'jlpt_transcript_segment']) {
    assert.equal(normalizedCreateTable(schemaSql, table), normalizedCreateTable(jlptSetupSql, table),
      `${table} must stay byte-contract equivalent after whitespace normalization`);
  }
});

test('validator rejects mutating statements and unknown tables', () => {
  const result = validateReleaseGateAudit({
    auditSql: `DELETE FROM user_learning_path; SELECT * FROM invented_release_table;`,
    schemaSql: `CREATE TABLE user_learning_path (id BIGINT PRIMARY KEY);`,
  });

  assert.ok(result.issues.includes('MUTATING_SQL:DELETE'));
  assert.ok(result.issues.includes('UNKNOWN_TABLE:invented_release_table'));
});
