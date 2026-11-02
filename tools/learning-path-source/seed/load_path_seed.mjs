// [JLAS-W0108] JLPT path seed loader: runs only the jpath SQL of Learning data files against one local database.
// Run: node tools/learning-path-source/seed/load_path_seed.mjs --db jlas_mirai
//        --files sql/learning/learning_data_common.sql,sql/learning/learning_data_N5.sql [--yaml <application-local.yaml>]
//        [--mysql <mysql.exe>]
// From learning_data_common.sql it takes the seed-sql.mjs base block (levels, kana); from learning_data_<L>.sql the
// GENERATED jpath region; any other file is run whole. The password goes to the mysql child process through MYSQL_PWD
// only; nothing prints the password, the URL or row content.
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { ROOT, argValue } from '../compile/util.mjs';
import { REGION_BEGIN, REGION_END, readRegion } from './build_path_seed.mjs';
import { BASE_MARKER } from './seed-sql.mjs';

export const CONTENT_TABLES = ['jpath_level', 'jpath_kana_row', 'jpath_kana', 'jpath_vocab_topic', 'jpath_grammar_unit',
  'jpath_vocabulary', 'jpath_kanji', 'jpath_kanji_compound', 'jpath_grammar', 'jpath_example', 'jpath_grammar_question',
  'jpath_confusable_pair'];
const DEFAULT_MYSQL = 'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe';
const DEFAULT_YAML = 'src/main/resources/application-local.yaml';

function unquote(raw) {
  const v = raw.trim();
  if (v.length >= 2 && ((v[0] === "'" && v.at(-1) === "'") || (v[0] === '"' && v.at(-1) === '"'))) {
    return v[0] === "'" ? v.slice(1, -1).replace(/''/g, "'") : v.slice(1, -1);
  }
  return v.replace(/\s+#.*$/, '').trim();
}

/** `spring.datasource.url/username/password` from a flat-key YAML (values quoted or not). */
export function parseLocalDatasource(yamlText) {
  const out = {};
  for (const line of String(yamlText).split(/\r?\n/)) {
    const m = /^spring\.datasource\.(url|username|password)\s*:(.*)$/.exec(line);
    if (m && !(m[1] in out)) out[m[1]] = unquote(m[2]);
  }
  for (const key of ['url', 'username', 'password']) if (!(key in out)) throw new Error(`spring.datasource.${key} is missing`);
  return out;
}

/** Database name of a `jdbc:mysql://host[:port]/db?...` URL. */
export function databaseFromUrl(url) {
  const m = /^jdbc:mysql:\/\/[^/]+\/([^?;/]*)/.exec(String(url));
  if (!m || !m[1]) throw new Error('The datasource URL names no database');
  return decodeURIComponent(m[1]);
}

export function hostPortFromUrl(url) {
  const m = /^jdbc:mysql:\/\/([^/:?]+)(?::(\d+))?\//.exec(String(url));
  if (!m) throw new Error('The datasource URL names no host');
  return { host: m[1], port: m[2] ?? '3306' };
}

/** Throws unless `db` is the database of the datasource URL and is not the shared `jlas` database. */
export function assertSafeTarget(db, urlDb) {
  const name = String(db ?? '').trim();
  if (!name) throw new Error('No target database');
  if (name.toLowerCase() === 'jlas' || String(urlDb ?? '').trim().toLowerCase() === 'jlas') {
    throw new Error('Refusing to load into jlas');
  }
  if (name !== urlDb) throw new Error(`--db ${name} is not the database of the local datasource (${urlDb})`);
}

/** JLPT levels named by the files (`learning_data_N5.sql`, `jpath_content_N5.sql`), each once, low to high. */
export function levelsInFiles(files) {
  const order = ['N5', 'N4', 'N3', 'N2', 'N1'];
  const found = new Set();
  for (const f of files) {
    const m = /[_-](N[1-5])\.sql$/i.exec(path.basename(String(f).replace(/\\/g, '/')));
    if (m) found.add(m[1].toUpperCase());
  }
  return order.filter((l) => found.has(l));
}

const hasStatement = (sql) => sql.split(/\r?\n/).some((line) => line.trim() && !line.trim().startsWith('--'));

/** The jpath SQL of one file (see the header); never a USE line. */
export function extractJpathSql(text, file) {
  const name = path.basename(String(file).replace(/\\/g, '/'));
  let sql;
  if (/^learning_data_common\.sql$/.test(name)) {
    const begin = text.indexOf(`-- BEGIN ${BASE_MARKER} SHA256 `);
    const end = text.indexOf(`-- END ${BASE_MARKER}`, begin);
    if (begin < 0 || end < 0) throw new Error(`${name} has no ${BASE_MARKER} block`);
    sql = text.slice(text.indexOf('\n', begin) + 1, end);
  } else if (/^learning_data_N[1-5]\.sql$/.test(name)) {
    const region = readRegion(text);
    if (!region) throw new Error(`${name} has no GENERATED jpath region`);
    sql = region.text.slice(REGION_BEGIN.length).replace(/^\r?\n/, '');
    sql = sql.slice(0, sql.length - REGION_END.length);
  } else {
    sql = text;
  }
  sql = sql.replace(/\r\n/g, '\n');
  if (/^\s*USE\s/im.test(sql)) throw new Error(`${name} holds a USE line`);
  if (!hasStatement(sql)) throw new Error(`${name}: the jpath SQL is empty — run build_path_seed.mjs first`);
  return sql;
}

// ---------------------------------------------------------------- CLI
function runCli(argv) {
  const db = argValue(argv, '--db');
  const files = (argValue(argv, '--files', '') ?? '').split(',').map((f) => f.trim()).filter(Boolean);
  const yamlFile = path.resolve(ROOT, argValue(argv, '--yaml', DEFAULT_YAML));
  const mysql = argValue(argv, '--mysql', DEFAULT_MYSQL);
  if (!db || !files.length) {
    console.error('Usage: load_path_seed.mjs --db <db> --files <a.sql,b.sql> [--yaml <file>] [--mysql <mysql.exe>]');
    return 1;
  }
  let ds;
  let conn;
  try {
    ds = parseLocalDatasource(readFileSync(yamlFile, 'utf8'));
    assertSafeTarget(db, databaseFromUrl(ds.url));
    conn = hostPortFromUrl(ds.url);
  } catch (e) {
    console.error(`Unsafe or unreadable target: ${e.message}`);
    return 1;
  }
  const env = { ...process.env, MYSQL_PWD: ds.password };
  const baseArgs = ['-h', conn.host, '-P', conn.port, '-u', ds.username, '--default-character-set=utf8mb4'];
  const run = (args, input) => spawnSync(mysql, [...baseArgs, ...args, db], { env, input, encoding: 'utf8', maxBuffer: 256 * 1024 * 1024 });
  const errLine = (r) => (r.error ? r.error.message : (/^ERROR \d+ \([0-9A-Z]+\)(?: at line \d+)?/m.exec(r.stderr ?? '')?.[0] ?? `exit ${r.status}`));
  const query = (sql) => {
    const r = run(['-N', '-B', '-e', sql]);
    if (r.status !== 0) throw new Error(`query failed: ${errLine(r)}`);
    return r.stdout.trim();
  };

  try {
    if (query('SELECT DATABASE()') !== db) { console.error('SELECT DATABASE() does not match --db'); return 1; }
    if (query(`SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'jpath_level'`) !== '1') {
      console.error('jpath_level does not exist: run sql/learning/learning_schema.sql first');
      return 1;
    }
    const levels = levelsInFiles(files);
    if (levels.length) {
      const pending = Number(query(`SELECT COUNT(*) FROM jpath_content_edit WHERE level IN (${levels.map((l) => `'${l}'`).join(', ')}) AND exported_at IS NULL`));
      if (pending > 0) {
        console.error(`${pending} admin edit(s) of ${levels.join(', ')} are not exported: export the edits and rebuild the seed first`);
        return 1;
      }
    }
    const parts = files.map((f) => {
      const abs = path.resolve(ROOT, f);
      return { name: path.relative(ROOT, abs).split(path.sep).join('/'), sql: extractJpathSql(readFileSync(abs, 'utf8'), abs) };
    });
    for (const p of parts) {
      const r = run([], p.sql);
      if (r.status !== 0) { console.error(`${p.name}: ${errLine(r)} — stopped`); return 1; }
      console.log(`Loaded ${p.name} (jpath part)`);
    }
    const counts = query(CONTENT_TABLES.map((t) => `SELECT '${t}', COUNT(*) FROM ${t}`).join(' UNION ALL '));
    for (const line of counts.split(/\r?\n/)) console.log(line.replace('\t', ' '));
    return 0;
  } catch (e) {
    console.error(e.message);
    return 1;
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.exit(runCli(process.argv.slice(2)));
}
