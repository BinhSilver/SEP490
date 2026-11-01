// [JLAS-W0108] §2.32 T6 (owner 2026-10-27): renders the store pool section into one block per level (learning_data_N4.sql, learning_data_N3.sql).
//   node tools/learning-data-extract/build_jlas_store_pool_sql.mjs . [--check] [--block-out file]
import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { learningDataFile, levelMarker, replaceDataBlock } from './learning_data_files.mjs';

const MARKER = 'data/learning-owned/learning-content.json#sections.jlas_store_pool';
const TAG = 'LP-OWNED-JLAS-STORE-POOL';
const CODE = /^(LP-OWNED-JLAS-(N[1-5])-POOL-READ-P\d+)-Q\d{3}$/;
const hex = (value) => `CONVERT(X'${Buffer.from(String(value), 'utf8').toString('hex')}' USING utf8mb4)`;
const nullableHex = (value) => value == null || value === '' ? 'NULL' : hex(value);
const escapeHtml = (value) => String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;')
  .replaceAll('>', '&gt;').replaceAll('"', '&quot;').replaceAll("'", '&#39;');
const norm = (value) => String(value ?? '').normalize('NFKC').trim().replace(/\s+/g, ' ').toLowerCase();
/** Store difficulty 1–5 onto the bank's three-step column. */
export const bankDifficulty = (level) => (level <= 2 ? 'EASY' : level === 3 ? 'MEDIUM' : 'HARD');

function validate(section, sectionHash) {
  if (!/^[a-f0-9]{64}$/.test(sectionHash ?? '')) throw new Error('Store pool section checksum is missing.');
  if (section?.kind !== 'LEARNING_STORE_POOL' || section.quiz_eligible !== false || section.smart_test_eligible !== false
      || section.required_part_eligible !== false || section.review_status !== 'CHUA_DUYET' || !Array.isArray(section.passages)) {
    throw new Error('Store pool section is not an extra-practice-only DRAFT section.');
  }
  const seen = new Set();
  for (const p of section.passages) {
    if (p.labels?.review !== 'DRAFT' || p.labels?.origin !== 'JLAS_SUPPLEMENT' || p.skill !== 'READING' || !p.text_lines?.length) {
      throw new Error(`Store pool passage ${p.group} must be a labelled DRAFT reading.`);
    }
    for (const q of p.questions) {
      const match = CODE.exec(q.source_code ?? '');
      const options = q.options ?? [];
      if (!match || match[1] !== p.group || match[2] !== p.level || seen.has(q.source_code) || options.length < 3 || options.length > 4
          || new Set(options.map(norm)).size !== options.length || options[q.correct_option - 1] !== q.answer_text) {
        throw new Error(`Invalid store pool question ${q.source_code}`);
      }
      seen.add(q.source_code);
    }
  }
}

// Owner 2026-11-01: one block per JLPT level (in learning_data_N4.sql / learning_data_N3.sql); no level renders all.
export function renderStorePoolSql(section, sectionHash, onlyLevel = null) {
  validate(section, sectionHash);
  const lines = [
    `-- BEGIN ${levelMarker(MARKER, onlyLevel)} SHA256 ${sectionHash}`,
    '-- [JLAS-W0108] §2.32 T6 (owner 2026-10-27): Learning store pool — extra practice only (context_json.pool, review DRAFT);',
    '-- never a required part, Smart Test or scored quiz. Rows dropped from the section are retired, current rows upserted.',
    'START TRANSACTION;',
    `INSERT INTO tag (code,name,tag_type) VALUES (${hex(TAG)},${hex('Learning store pool (extra practice)')},'KNOWLEDGE') ON DUPLICATE KEY UPDATE name=VALUES(name);`,
    `SET @owned_store_pool_tag=(SELECT id FROM tag WHERE code='${TAG}' LIMIT 1);`,
    `UPDATE assessment_question SET is_active=0 WHERE source_code LIKE 'LP-OWNED-JLAS-${onlyLevel ?? 'N_'}-POOL-%';`,
  ];
  for (const p of section.passages.filter((x) => !onlyLevel || x.level === onlyLevel)) {
    const html = p.text_lines.map((line) => `<p>${escapeHtml(line)}</p>`).join('');
    for (const q of p.questions) {
      const context = JSON.stringify({
        learning_owned: true, learning_path_only: true, study_eligible: true, quiz_eligible: false, smart_test_eligible: false,
        pool: true, labels: p.labels, answers_by: p.answers_by, source: p.source,
        review_status: section.review_status, label: section.label, rights: section.rights,
        distribution_authorization: section.distribution_authorization,
        ...(p.passage_translation?.length ? { passage_translation: p.passage_translation } : {}),
      });
      const options = [0, 1, 2, 3].map((index) => nullableHex(q.options[index]));
      lines.push(`-- ROW ${q.source_code}`);
      lines.push(`INSERT INTO assessment_question (source_code,knowledge_tag_id,skill,jlpt_level,difficulty,question_type,title,passage_html,audio_url,question_text,answer_text,option_1,option_2,option_3,option_4,correct_option,explanation,context_json,is_active) VALUES (${hex(q.source_code)},@owned_store_pool_tag,'READING','${p.level}','${bankDifficulty(p.labels.difficulty)}','MULTIPLE_CHOICE',${hex(p.title)},${hex(html)},NULL,${hex(q.prompt)},${hex(q.answer_text)},${options.join(',')},${q.correct_option},${hex(q.explanation)},${hex(context)},1) ON DUPLICATE KEY UPDATE knowledge_tag_id=VALUES(knowledge_tag_id),skill=VALUES(skill),jlpt_level=VALUES(jlpt_level),difficulty=VALUES(difficulty),question_type=VALUES(question_type),title=VALUES(title),passage_html=VALUES(passage_html),audio_url=VALUES(audio_url),question_text=VALUES(question_text),answer_text=VALUES(answer_text),option_1=VALUES(option_1),option_2=VALUES(option_2),option_3=VALUES(option_3),option_4=VALUES(option_4),correct_option=VALUES(correct_option),explanation=VALUES(explanation),context_json=VALUES(context_json),is_active=1;`);
    }
  }
  lines.push('COMMIT;', `-- END ${levelMarker(MARKER, onlyLevel)}`, '');
  return lines.join('\n');
}

export function replaceStorePoolBlock(sql, block, level = null) {
  if (level) return replaceDataBlock(sql, levelMarker(MARKER, level), block);
  const newline = sql.includes('\r\n') ? '\r\n' : '\n';
  const formattedBlock = block.replace(/\r?\n/g, newline);
  const begin = `-- BEGIN ${MARKER} SHA256 `;
  const end = `-- END ${MARKER}`;
  const start = sql.indexOf(begin);
  if (start < 0) return `${sql.trimEnd()}${newline}${newline}${formattedBlock}`;
  const endStart = sql.indexOf(end, start);
  if (endStart < 0) throw new Error(`Unclosed SQL section: ${MARKER}`);
  const afterEnd = sql.indexOf('\n', endStart);
  return `${sql.slice(0, start)}${formattedBlock}${afterEnd < 0 ? '' : sql.slice(afterEnd + 1)}`;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = path.resolve(process.argv[2] ?? '.');
  const data = JSON.parse(await readFile(path.join(root, 'data/learning-owned/learning-content.json'), 'utf8'));
  const section = data.sections?.jlas_store_pool;
  const levels = ['N5', 'N4', 'N3', 'N2', 'N1'].filter((level) => section.passages.some((p) => p.level === level));
  const blocks = [];
  for (const level of levels) {
    const block = renderStorePoolSql(section, data.section_sha256?.jlas_store_pool, level);
    blocks.push(block);
    const sqlPath = learningDataFile(root, level);
    const current = await readFile(sqlPath, 'utf8');
    const updated = replaceStorePoolBlock(current, block, level);
    if (process.argv.includes('--check')) {
      if (current !== updated) throw new Error(`Store pool ${level} SQL block is stale.`);
    } else if (!process.argv.includes('--dry-run')) {
      await writeFile(sqlPath, updated, 'utf8');
    }
  }
  if (process.argv.includes('--block-out')) {
    await writeFile(process.argv[process.argv.indexOf('--block-out') + 1], blocks.join('\n'), 'utf8');
  }
  console.log(`Rendered ${section.passages.reduce((n, p) => n + p.questions.length, 0)} store pool rows.`);
}
