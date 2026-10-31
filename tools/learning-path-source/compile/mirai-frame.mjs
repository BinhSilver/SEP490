// Mirai frame for the JLPT path source: only lists, topic names, order and lesson grouping are kept.
// Paid grammar keeps pattern + order only. Free meanings/examples go to a local reference file under target/
// (never into the repo or the compiled source) and are used only to flag copying.
import path from 'node:path';
import { ROOT, argValue, readJson, slugVi, writeOrCheck } from './util.mjs';

const levelFile = (packDir, name) => path.join(packDir, 'data', name);
const lower = level => level.toLowerCase();
const unitNumber = sourceUnit => Number(String(sourceUnit ?? '').match(/(\d+)/)?.[1] ?? 0);
const topicName = (topic, level) => String(topic ?? '').replace(new RegExp(`^\\d+_${level}_`), '').trim();

function vocabularyItems(packDir, level) {
  return readJson(levelFile(packDir, `miraivn-vocabulary-full-${lower(level)}.json`)).items
    .slice().sort((a, b) => a.core_order - b.core_order);
}

function grammarItems(packDir, level) {
  return readJson(levelFile(packDir, `grammar_${lower(level)}_core.json`)).slice()
    .sort((a, b) => unitNumber(a.source_unit) - unitNumber(b.source_unit) || a.priority - b.priority || a.grammar_id - b.grammar_id);
}

export function loadMiraiFrame(packDir, level) {
  const vocabulary = vocabularyItems(packDir, level).map((v, i) => {
    const topicVi = topicName(v.topic, level);
    return { order: i + 1, topic_key: slugVi(topicVi), topic_vi: topicVi, expression: v.word_ja,
      reading: v.word_hira_kana, mirai_ref: `mirai:v:${v.id}` };
  });
  const kanji = readJson(levelFile(packDir, `miraivn-kanji-${lower(level)}.json`)).items
    .map((k, i) => ({ order: i + 1, character: k.char, mirai_ref: `mirai:k:${k.char}` }));
  const grammar = grammarItems(packDir, level).map((g, i) => {
    const unitNo = unitNumber(g.source_unit);
    return { order: i + 1, unit_no: unitNo, unit_key: `bai-${String(unitNo).padStart(2, '0')}`, pattern: g.grammar_point,
      free: g.is_free_preview === true, mirai_ref: `mirai:g:${g.grammar_id}` };
  });
  return { level, vocabulary, kanji, grammar };
}

export function loadMiraiReference(packDir, level) {
  const vocabulary = vocabularyItems(packDir, level).filter(v => v.isFreePreview === true)
    .map(v => ({ mirai_ref: `mirai:v:${v.id}`, meaning_vi: v.word_vi ?? null, example_ja: v.example_ja ?? null,
      example_vi: v.example_vi ?? null }));
  const details = readJson(levelFile(packDir, `grammar_${lower(level)}_details.json`));
  const grammar = grammarItems(packDir, level).filter(g => g.is_free_preview === true).map(g => ({
    mirai_ref: `mirai:g:${g.grammar_id}`, meaning_vi: g.meaning_vi ?? null,
    examples: (details[g.grammar_id]?.usages ?? []).map(u => u.example_ja).filter(Boolean)
  }));
  return { level, vocabulary, grammar };
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'))) {
  const argv = process.argv.slice(2);
  const pack = argValue(argv, '--pack', 'D:/rea/Mirai_Full_Project');
  const level = argValue(argv, '--level', 'N5');
  const check = argv.includes('--check');
  const frame = loadMiraiFrame(pack, level);
  const ok = writeOrCheck(path.join(ROOT, 'data/learning-path/frame', `${level}.json`), frame, check);
  writeOrCheck(path.join(ROOT, 'target/mirai-reference', `${level}.json`), loadMiraiReference(pack, level), false);
  console.log(`${level}: ${frame.vocabulary.length} words, ${frame.kanji.length} kanji, ${frame.grammar.length} grammar${check ? (ok ? ' (check ok)' : ' (CHECK FAILED)') : ''}`);
  if (!ok) process.exit(1);
}
