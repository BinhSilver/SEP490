import fs from 'node:fs/promises';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { renderSeed } from './build_owned_supplement.mjs';
import { selectReviewedVocabularyRelease } from './build_reviewed_vocabulary_release.mjs';
import { readReviewedVocabularyBatchTexts } from './learning_content_store.mjs';

const sha256 = value => createHash('sha256').update(value).digest('hex');
const normalizeSqlEol = value => value.replace(/\r\n/g, '\n');
const sqlText = value => `CONVERT(0x${Buffer.from(String(value), 'utf8').toString('hex')} USING utf8mb4) COLLATE utf8mb4_0900_as_cs`;
const renamed = code => {
  if (!code.startsWith('LP-OWNED-')) throw new Error(`Unexpected owned key: ${code}`);
  const result = `LP-REVIEWED-${code.slice('LP-OWNED-'.length)}`;
  if (result.length > 64) throw new Error(`Reviewed key too long: ${result}`);
  return result;
};

/** A new catalog identity lets legacy lesson IDs, content and learner progress stay intact. */
export function snapshotReviewedBundle(selected) {
  const books = selected.books.map(book => ({ ...book, code: renamed(book.code), title: `Đã duyệt — ${book.title}` }));
  const units = selected.units.map(unit => ({ ...unit, code: renamed(unit.code), book_code: renamed(unit.book_code) }));
  const lessons = selected.lessons.map(lesson => ({ ...lesson, code: renamed(lesson.code), unit_code: renamed(lesson.unit_code),
    items: lesson.items.map(item => ({ ...item, source_id: `REVIEWED-20261012-${item.source_id}` })) }));
  for (const group of [books, units, lessons]) {
    if (new Set(group.map(x => x.code)).size !== group.length) throw new Error('Duplicate reviewed snapshot key');
  }
  if (lessons.some(lesson => lesson.items.some(item => `LP-OWNED-${item.source_id}`.length > 255))) {
    throw new Error('Reviewed provenance key too long');
  }
  return { version: selected.version, books, units, lessons };
}

export function buildUpgradeSql(selected, legacy) {
  if (!legacy.books?.length || new Set(legacy.books.map(book => book.code)).size !== legacy.books.length) {
    throw new Error('Missing or duplicate legacy books');
  }
  const snapshot = snapshotReviewedBundle(selected);
  const legacyCodes = legacy.books.map(book => book.code);
  if (legacyCodes.some(code => !code.startsWith('LP-OWNED-'))) throw new Error('Non-owned legacy book');
  const legacyIdentity = JSON.stringify({ owner: 'LEARNING_PATH', version: legacy.version,
    sha256: sha256(JSON.stringify(legacy)) });
  const oldBookWhere = `source_code IN (${legacyCodes.map(sqlText).join(',')}) AND BINARY description=BINARY ${sqlText(legacyIdentity)}`;
  let seed = renderSeed(snapshot, { publish: true });
  const transaction = 'START TRANSACTION;\n';
  if (!seed.includes(transaction) || !seed.endsWith('COMMIT;\n')) throw new Error('Unexpected seed transaction contract');
  const guard = `-- [JLAS-W0108] Exact v8 identity guard; do not switch an unknown catalog.\n`
    + `CREATE TEMPORARY TABLE IF NOT EXISTS reviewed_upgrade_guard (ok INT NOT NULL CHECK (ok=1));\n`
    + `INSERT INTO reviewed_upgrade_guard SELECT IF((SELECT COUNT(*) FROM textbook WHERE ${oldBookWhere} AND status='PUBLISHED')=${legacyCodes.length},1,0);\n`;
  seed = seed.replace(transaction, guard + transaction);
  const switchOld = `-- Existing path/activity/lesson IDs remain unchanged; only future scope selection changes.\n`
    + `UPDATE textbook SET is_learning_path_source=0 WHERE ${oldBookWhere};\n`
    + `INSERT INTO reviewed_upgrade_guard SELECT IF((SELECT COUNT(*) FROM textbook WHERE ${oldBookWhere} AND is_learning_path_source=0)=${legacyCodes.length},1,0);\n`;
  return seed.slice(0, -'COMMIT;\n'.length) + switchOld + 'COMMIT;\n';
}

/** Append whole reviewed books without changing the identities used by existing learners. */
export function buildAdditiveReviewedSql(previous, next) {
  const oldSnapshot = snapshotReviewedBundle(previous);
  const nextSnapshot = snapshotReviewedBundle(next);
  const oldByCode = group => new Map(group.map(row => [row.code, row]));
  for (const field of ['books', 'units', 'lessons']) {
    const current = oldByCode(nextSnapshot[field]);
    if (current.size !== nextSnapshot[field].length) throw new Error(`Duplicate next ${field} key`);
    for (const row of oldSnapshot[field]) {
      if (JSON.stringify(current.get(row.code)) !== JSON.stringify(row)) {
        throw new Error(`Changed existing ${field}: ${row.code}`);
      }
    }
  }
  const oldBooks = new Set(oldSnapshot.books.map(row => row.code));
  const oldUnits = new Set(oldSnapshot.units.map(row => row.code));
  const oldLessons = new Set(oldSnapshot.lessons.map(row => row.code));
  const books = nextSnapshot.books.filter(row => !oldBooks.has(row.code));
  const units = nextSnapshot.units.filter(row => !oldUnits.has(row.code));
  const lessons = nextSnapshot.lessons.filter(row => !oldLessons.has(row.code));
  if (!books.length || !lessons.length) throw new Error('No new reviewed book/lesson');
  const newBooks = new Set(books.map(row => row.code));
  const newUnits = new Set(units.map(row => row.code));
  for (const unit of units) {
    if (!newBooks.has(unit.book_code)) throw new Error(`New unit belongs to existing book: ${unit.code}`);
  }
  for (const lesson of lessons) {
    if (!newUnits.has(lesson.unit_code)) throw new Error(`New lesson belongs to existing book: ${lesson.code}`);
  }
  const oldIdentity = JSON.stringify({ owner: 'LEARNING_PATH', version: oldSnapshot.version,
    sha256: sha256(JSON.stringify(oldSnapshot)) });
  const oldBookCodes = oldSnapshot.books.map(row => sqlText(row.code)).join(',');
  const oldLessonCodes = oldSnapshot.lessons.map(row => sqlText(row.code)).join(',');
  const oldSourceCodes = oldSnapshot.lessons.flatMap(lesson => lesson.items.map(item => sqlText(`LP-OWNED-${item.source_id}`))).join(',');
  const guard = `-- [JLAS-W0108] Existing reviewed v2 identity and content must remain intact.\n`
    + `CREATE TEMPORARY TABLE IF NOT EXISTS reviewed_additive_guard (ok INT NOT NULL CHECK (ok=1));\n`
    + `INSERT INTO reviewed_additive_guard SELECT IF((SELECT COUNT(*) FROM textbook WHERE source_code IN (${oldBookCodes}) AND BINARY description=BINARY ${sqlText(oldIdentity)} AND status='PUBLISHED' AND is_learning_path_source=1)=${oldSnapshot.books.length},1,0);\n`
    + `INSERT INTO reviewed_additive_guard SELECT IF((SELECT COUNT(*) FROM lesson WHERE source_code IN (${oldLessonCodes}))=${oldSnapshot.lessons.length},1,0);\n`
    + `INSERT INTO reviewed_additive_guard SELECT IF((SELECT COUNT(*) FROM content_source WHERE source_code='VNJPCLUB' AND entity_type='CONTENT_ITEM' AND stable_code IN (${oldSourceCodes}))=${oldSnapshot.lessons.reduce((n, lesson) => n + lesson.items.length, 0)},1,0);\n`;
  const seed = renderSeed({ version: nextSnapshot.version, books, units, lessons }, { publish: true });
  return seed.replace('START TRANSACTION;\n', guard + 'START TRANSACTION;\n');
}

export async function writeAdditiveUpgrade(bundlePath, manifestPath, previousReportPath, previousSeedPath, outputDir) {
  const root = path.resolve(fileURLToPath(new URL('../../', import.meta.url)));
  const [bundleText, manifestText, previousReportText, previousSeed] = await Promise.all(
    [bundlePath, manifestPath, previousReportPath, previousSeedPath].map(file => fs.readFile(file, 'utf8')));
  const bundle = JSON.parse(bundleText);
  const parsedManifest = JSON.parse(manifestText);
  const manifest = parsedManifest.schema === 'JLAS_LEARNING_OWNED_CONTENT_V1'
    ? parsedManifest.sections.reviewed_vocabulary : parsedManifest;
  const previousReport = JSON.parse(previousReportText);
  const batches = (await readReviewedVocabularyBatchTexts(root, parsedManifest)).map(JSON.parse);
  const previousCodes = new Set(previousReport.approved_lesson_codes);
  if (previousCodes.size !== previousReport.approved_lesson_codes.length
    || previousReport.vocabulary !== 175 || previousReport.lessons !== 12) {
    throw new Error('Unexpected previous reviewed release identity');
  }
  const previousManifest = { ...manifest, version: previousReport.version,
    approved_lesson_codes: manifest.approved_lesson_codes.filter(code => previousCodes.has(code)) };
  if (previousManifest.approved_lesson_codes.length !== previousCodes.size) throw new Error('Previous lesson missing from next manifest');
  const previous = selectReviewedVocabularyRelease(bundle, batches, previousManifest);
  if (normalizeSqlEol(renderSeed(previous, { publish: true })) !== normalizeSqlEol(previousSeed))
    throw new Error('Previous seed mismatch');
  const next = selectReviewedVocabularyRelease(bundle, batches, manifest);
  const sql = buildAdditiveReviewedSql(previous, next);
  const previousBookCodes = new Set(previous.books.map(book => book.code));
  const previousLessonCodes = new Set(previous.lessons.map(lesson => lesson.code));
  const newLessons = next.lessons.filter(lesson => !previousLessonCodes.has(lesson.code));
  const report = { status: 'SCRATCH_CANDIDATE_NOT_LIVE_APPLIED', previous_version: previous.version,
    next_version: next.version, previous_seed_sha256: sha256(previousSeed),
    previous_vocabulary: previousReport.vocabulary,
    new_books: next.books.filter(book => !previousBookCodes.has(book.code)).length,
    new_lessons: newLessons.length,
    new_vocabulary: newLessons.reduce((n, lesson) => n + lesson.items.length, 0),
    sql_sha256: sha256(sql) };
  await fs.mkdir(outputDir, { recursive: true });
  await fs.writeFile(path.join(outputDir, 'add_reviewed_n3.sql'), sql);
  await fs.writeFile(path.join(outputDir, 'additive-manifest.json'), JSON.stringify(report, null, 2) + '\n');
  return report;
}

export async function writeUpgrade(bundlePath, manifestPath, legacyPath, outputDir) {
  const root = path.resolve(fileURLToPath(new URL('../../', import.meta.url)));
  const [bundleText, manifestText, legacyText] = await Promise.all([bundlePath, manifestPath, legacyPath].map(file => fs.readFile(file, 'utf8')));
  const bundle = JSON.parse(bundleText);
  const parsedManifest = JSON.parse(manifestText);
  const manifest = parsedManifest.schema === 'JLAS_LEARNING_OWNED_CONTENT_V1'
    ? parsedManifest.sections.reviewed_vocabulary : parsedManifest;
  const legacy = JSON.parse(legacyText);
  const batches = (await readReviewedVocabularyBatchTexts(root, parsedManifest)).map(JSON.parse);
  const selected = selectReviewedVocabularyRelease(bundle, batches, manifest);
  const sql = buildUpgradeSql(selected, legacy);
  const report = { status: 'SCRATCH_CANDIDATE_NOT_LIVE_APPLIED', reviewed_manifest_version: manifest.version,
    legacy_bundle_sha256: sha256(legacyText), selected_books: selected.books.length,
    new_lessons: selected.lessons.length, new_vocabulary: selected.lessons.reduce((n, lesson) => n + lesson.items.length, 0),
    legacy_book_codes: legacy.books.map(book => book.code), new_book_codes: snapshotReviewedBundle(selected).books.map(book => book.code),
    sql_sha256: sha256(sql) };
  await fs.mkdir(outputDir, { recursive: true });
  await fs.writeFile(path.join(outputDir, 'upgrade_reviewed_vocabulary.sql'), sql);
  await fs.writeFile(path.join(outputDir, 'upgrade-manifest.json'), JSON.stringify(report, null, 2) + '\n');
  return report;
}

if (process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url) {
  if (process.argv[2] === '--additive') {
    const [bundlePath, manifestPath, previousReportPath, previousSeedPath, outputDir] = process.argv.slice(3);
    if (!bundlePath || !manifestPath || !previousReportPath || !previousSeedPath || !outputDir) {
      throw new Error('Usage: build_reviewed_vocabulary_upgrade.mjs --additive <bundle> <next-manifest> <previous-report> <previous-seed> <output-dir>');
    }
    process.stdout.write(JSON.stringify(await writeAdditiveUpgrade(bundlePath, manifestPath, previousReportPath, previousSeedPath, outputDir)) + '\n');
    process.exit(0);
  }
  const [bundlePath, manifestPath, legacyPath, outputDir] = process.argv.slice(2);
  if (!bundlePath || !manifestPath || !legacyPath || !outputDir) {
    throw new Error('Usage: build_reviewed_vocabulary_upgrade.mjs <candidate-bundle> <manifest> <legacy-bundle> <output-dir>');
  }
  process.stdout.write(JSON.stringify(await writeUpgrade(bundlePath, manifestPath, legacyPath, outputDir)) + '\n');
}
