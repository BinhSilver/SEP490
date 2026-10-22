import fs from 'node:fs/promises';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { readLearningSection } from './learning_content_store.mjs';

// [JLAS-W0108] Owner 2026-10-21 (OD-13): a Kanji's "meaning" is its Hán Việt plus the KANJIDIC2 English meanings,
// credited in place. The shared `kanji` table belongs to Dictionary and has no English column, so Learning keeps its
// own copy: a small classpath file generated from the canonical `kanji_n5_n1` section. JLPT levels come from the
// Waller lists (OpenJLPT) and stay NOT_VERIFIED.
const OUTPUT = 'src/main/resources/learning/kanji-gloss.json';
const MAX_MEANINGS = 3;
const LEVELS = new Set(['N5', 'N4', 'N3', 'N2', 'N1']);

export function buildKanjiGloss(section, sourceSha256) {
  const license = section.content.license ?? {};
  const kanji = {};
  for (const entry of section.content.kanji ?? []) {
    const meanings = (entry.meanings_en ?? []).map((value) => String(value).trim()).filter(Boolean);
    if (!LEVELS.has(entry.jlpt_level) || meanings.length === 0) continue;
    kanji[entry.character] = meanings.slice(0, MAX_MEANINGS);
  }
  return {
    ownedRootCode: section.owned_root_code,
    sourceSha256,
    attribution: { source: license.source, publisher: license.publisher, license: license.license, url: license.url,
      levelSource: license.level_source, levelStatus: 'NOT_VERIFIED' },
    kanji,
  };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = path.resolve(process.argv[2] ?? '.');
  const section = await readLearningSection(root, 'kanji_n5_n1');
  const sha = createHash('sha256').update(JSON.stringify(section)).digest('hex');
  const text = JSON.stringify(buildKanjiGloss(section, sha)) + '\n';
  const target = path.join(root, OUTPUT);
  if (process.argv.includes('--check')) {
    if ((await fs.readFile(target, 'utf8')) !== text) throw new Error(`${OUTPUT} is stale`);
  } else {
    await fs.mkdir(path.dirname(target), { recursive: true });
    await fs.writeFile(target, text, 'utf8');
  }
  console.log(JSON.stringify({ kanji: Object.keys(JSON.parse(text).kanji).length, bytes: Buffer.byteLength(text) }));
}
