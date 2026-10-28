// [JLAS-W0108] Adds the source's own line-by-line Vietnamese translation to each Learning-owned Minna reading passage.
// Source: the textbook team's VNJPClub Minna export (data/vnjpclub_minna/cau_song_ngu.json.gz, rows whose ma_cha is the
// passage id). It is read, never edited; the Learning copy keeps the source SHA-256. A passage receives a translation only
// when every Japanese source line equals the passage's own line (spacing/brackets ignored); otherwise it is listed as held.
//   node tools/learning-data-extract/attach_minna_reading_translations.mjs . [--check]
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { gunzipSync } from 'node:zlib';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readLearningSection, writeLearningSection } from './learning_content_store.mjs';

const SOURCE = 'data/vnjpclub_minna/cau_song_ngu.json.gz';
const comparable = (text) => String(text ?? '').normalize('NFKC').replace(/[\s\[\]［］]/g, '');

/** Japanese lines of a passage as shown to the learner (its first line is the 【…】 heading, not passage text). */
export function passageLines(passageHtml) {
  return [...String(passageHtml ?? '').matchAll(/<p>([\s\S]*?)<\/p>/g)].map((match) => match[1]
    .replace(/<[^>]+>/g, '').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&amp;/g, '&').trim())
    .filter((line) => line && !/^【.*】$/.test(line));
}

/** Pure: returns the section with `passage_translation` on every exercise whose passage lines all match the source. */
export function attachTranslations(section, sourceRows, sourceSha256) {
  const byPassage = new Map();
  for (const row of sourceRows) {
    if (!byPassage.has(row.ma_cha)) byPassage.set(row.ma_cha, []);
    byPassage.get(row.ma_cha).push(row);
  }
  const held = [];
  const translationByPassage = new Map();
  for (const exercise of section.exercises) {
    if (translationByPassage.has(exercise.passage_id)) continue;
    const lines = passageLines(exercise.passage_html);
    const rows = (byPassage.get(exercise.passage_id) ?? []).slice().sort((a, b) => a.order_index - b.order_index);
    const ok = rows.length === lines.length && rows.every((row, index) =>
      comparable(row.cau_ja) === comparable(lines[index]) && String(row.dich_vi ?? '').trim());
    if (!ok) {
      held.push({ passage_id: exercise.passage_id, reason: rows.length ? 'LINES_DO_NOT_MATCH' : 'NO_SOURCE_TRANSLATION' });
      translationByPassage.set(exercise.passage_id, null);
      continue;
    }
    translationByPassage.set(exercise.passage_id, rows.map((row, index) => ({ ja: lines[index], vi: String(row.dich_vi).trim() })));
  }
  const exercises = section.exercises.map((exercise) => {
    const translation = translationByPassage.get(exercise.passage_id);
    const { passage_translation: _old, ...rest } = exercise;
    return translation ? { ...rest, passage_translation: translation } : rest;
  });
  return {
    ...section,
    exercises,
    passage_translation_source: {
      file: SOURCE, sha256: sourceSha256, field: 'cau_song_ngu.dich_vi', review_status: 'SOURCE_TEXT_NOT_REVIEWED_BY_JLAS',
      passages_translated: [...translationByPassage.values()].filter(Boolean).length, held,
    },
  };
}

async function main() {
  const root = path.resolve(process.argv[2] ?? '.');
  const raw = await readFile(path.join(root, SOURCE));
  const rows = JSON.parse(gunzipSync(raw).toString('utf8'));
  const section = await readLearningSection(root, 'jlpt_minna_n5_n4_reading');
  const updated = attachTranslations(section, rows, createHash('sha256').update(raw).digest('hex'));
  const summary = updated.passage_translation_source;
  if (process.argv.includes('--check')) {
    if (JSON.stringify(updated) !== JSON.stringify(section)) throw new Error('Minna reading translations are stale.');
  } else {
    await writeLearningSection(root, 'jlpt_minna_n5_n4_reading', updated);
  }
  console.log(`passages translated=${summary.passages_translated} held=${summary.held.length}`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main();
