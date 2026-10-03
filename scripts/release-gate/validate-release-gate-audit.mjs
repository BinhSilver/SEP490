import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const MUTATING_KEYWORDS = [
  'ALTER',
  'CALL',
  'CREATE',
  'DELETE',
  'DROP',
  'EXECUTE',
  'GRANT',
  'INSERT',
  'LOAD',
  'REPLACE',
  'REVOKE',
  'TRUNCATE',
  'UPDATE',
];

function stripComments(sql) {
  return sql
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/--[^\r\n]*/g, ' ');
}

function canonicalName(identifier) {
  return identifier.replaceAll('`', '').split('.').at(-1).toLowerCase();
}

export function validateReleaseGateAudit({ auditSql, schemaSql }) {
  const issues = [];
  const executableSql = stripComments(auditSql);

  for (const keyword of MUTATING_KEYWORDS) {
    if (new RegExp(`\\b${keyword}\\b`, 'i').test(executableSql)) {
      issues.push(`MUTATING_SQL:${keyword}`);
    }
  }

  const schemaTables = new Set(
    [...schemaSql.matchAll(/\bCREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(`?[\w]+`?)/gi)]
      .map((match) => canonicalName(match[1])),
  );

  const referencedTables = new Set(
    [...executableSql.matchAll(/\b(?:FROM|JOIN)\s+(`?[\w]+`?(?:\.`?[\w]+`?)?)/gi)]
      .map((match) => canonicalName(match[1])),
  );

  for (const table of [...referencedTables].sort()) {
    if (!schemaTables.has(table)) {
      issues.push(`UNKNOWN_TABLE:${table}`);
    }
  }

  const auditCodes = [...auditSql.matchAll(/'([A-D][0-9]_[A-Z0-9_]+)'\s+AS\s+audit_code/gi)]
    .map((match) => match[1].toUpperCase());
  const uniqueCodes = new Set();
  for (const code of auditCodes) {
    if (uniqueCodes.has(code)) {
      issues.push(`DUPLICATE_AUDIT_CODE:${code}`);
    }
    uniqueCodes.add(code);
  }

  if (auditCodes.length === 0) {
    issues.push('AUDIT_CODE_MISSING');
  }
  if (!/@migration_010_cutover\s*=/.test(executableSql)) {
    issues.push('MIGRATION_010_CUTOVER_MISSING');
  }

  return { auditCodes, issues };
}

async function main() {
  const auditSqlUrl = new URL('../../sql/audits/2026-10-03-full-spec-release-gate-audit.sql', import.meta.url);
  const schemaUrl = new URL('../../jlas_schema.sql', import.meta.url);
  const [auditSql, schemaSql] = await Promise.all([
    readFile(auditSqlUrl, 'utf8'),
    readFile(schemaUrl, 'utf8'),
  ]);
  const result = validateReleaseGateAudit({ auditSql, schemaSql });
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
  if (result.issues.length > 0) process.exitCode = 1;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  await main();
}
