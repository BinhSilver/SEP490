import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

import { validateReleaseGateAudit } from './validate-release-gate-audit.mjs';

const auditSqlUrl = new URL('../../sql/audits/2026-10-03-full-spec-release-gate-audit.sql', import.meta.url);
const schemaUrl = new URL('../../jlas_schema.sql', import.meta.url);

test('full-spec release audit is read-only and references canonical schema tables', async () => {
  const [auditSql, schemaSql] = await Promise.all([
    readFile(auditSqlUrl, 'utf8'),
    readFile(schemaUrl, 'utf8'),
  ]);

  const result = validateReleaseGateAudit({ auditSql, schemaSql });

  assert.deepEqual(result.issues, []);
  assert.equal(result.auditCodes.length, 25);
  assert.equal(new Set(result.auditCodes).size, result.auditCodes.length);
  assert.ok(result.auditCodes.includes('A1_POST_CUTOVER_COMPLETED_WITHOUT_ATTEMPT'));
  assert.ok(result.auditCodes.includes('D7_SURVEY_ONLY_GOAL_REQUIRES_ASSESSMENT'));
});

test('validator rejects mutating statements and unknown tables', () => {
  const result = validateReleaseGateAudit({
    auditSql: `DELETE FROM user_learning_path; SELECT * FROM invented_release_table;`,
    schemaSql: `CREATE TABLE user_learning_path (id BIGINT PRIMARY KEY);`,
  });

  assert.ok(result.issues.includes('MUTATING_SQL:DELETE'));
  assert.ok(result.issues.includes('UNKNOWN_TABLE:invented_release_table'));
});
