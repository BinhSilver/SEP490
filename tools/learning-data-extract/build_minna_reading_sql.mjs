import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const MARKER = 'data/learning-owned/learning-content.json#sections.jlpt_minna_n5_n4_reading';
const hex = (value) => `CONVERT(X'${Buffer.from(String(value), 'utf8').toString('hex')}' USING utf8mb4)`;
const nullableHex = (value) => value == null || value === '' ? 'NULL' : hex(value);

function validate(section, sectionHash) {
  const held = section?.held_checks ?? [];
  if (!/^[a-f0-9]{64}$/.test(sectionHash ?? '') || (section?.exercises?.length ?? 0) + held.length !== 190
      || section.counts?.N5?.checks !== section.exercises.filter((row) => row.level === 'N5').length
      || section.counts?.N4?.checks !== section.exercises.filter((row) => row.level === 'N4').length
      || held.some((row) => !/^LP-OWNED-MINNA-(N5|N4)-L\d{2}-READ-P\d{3}-Q\d{3}$/.test(row.source_code ?? '') || !row.reason)
      || section.quiz_eligible !== false || section.smart_test_eligible !== false
      || section.rights !== 'OWNER_CONFIRMED_TEAM_DISTRIBUTION'
      || section.distribution_authorization !== 'JLAS_OWNER_CONFIRMED_TEAM_DISTRIBUTION_2026-10-18') {
    throw new Error('Canonical Minna reading section/checksum is incomplete or not Learning-only.');
  }
  const sourceCodes = new Set();
  const perLevelChapter = new Map();
  for (const row of section.exercises) {
    const matcher = /^LP-OWNED-MINNA-(N5|N4)-L(\d{2})-READ-P\d{3}-Q\d{3}$/.exec(row.source_code ?? '');
    const options = row.options ?? [];
    if (!matcher || row.level !== matcher[1] || row.chapter !== Number(matcher[2])
        || !row.passage_html || !row.question_prompt || !row.answer_text
        || options.length < 2 || options.length > 4 || new Set(options.map((value) => value.normalize('NFKC').trim().toLowerCase())).size !== options.length
        || row.correct_option < 1 || row.correct_option > options.length
        || options[row.correct_option - 1] !== row.answer_text || sourceCodes.has(row.source_code)) {
      throw new Error(`Invalid Minna reading question ${row.source_code}`);
    }
    sourceCodes.add(row.source_code);
    const levelChapter = `${row.level}:${row.chapter}`;
    perLevelChapter.set(levelChapter, (perLevelChapter.get(levelChapter) ?? 0) + 1);
  }
  for (const [level, first] of [['N5', 1], ['N4', 26]]) {
    for (let chapter = first; chapter < first + 25; chapter++) {
      if (!perLevelChapter.has(`${level}:${chapter}`)) throw new Error(`No reading check for ${level} chapter ${chapter}`);
    }
  }
}

export function renderMinnaReadingSql(section, sectionHash) {
  validate(section, sectionHash);
  const lines = [
    `-- BEGIN ${MARKER} SHA256 ${sectionHash}`,
    '-- [JLAS-W0108] Source-bound Minna reading checks, available only in the Learning path.',
    '-- Owner-authorized team seed; Learning-path reading only, never scored quiz or Smart Test material.',
    '-- Additive and idempotent by source_code; does not delete or touch learner/path progress tables.',
    'START TRANSACTION;',
    `INSERT INTO tag (code,name,tag_type) VALUES (${hex('LP-OWNED-MINNA-READING')},${hex('Learning-owned Minna reading checks')},'KNOWLEDGE') ON DUPLICATE KEY UPDATE name=VALUES(name);`,
    "SET @owned_minna_read_tag=(SELECT id FROM tag WHERE code='LP-OWNED-MINNA-READING' LIMIT 1);",
  ];
  for (const row of section.exercises) {
    const context = JSON.stringify({
      learning_owned: true,
      learning_path_only: true,
      study_eligible: true,
      quiz_eligible: false,
      smart_test_eligible: false,
      source: 'MINNA_N5_N4_READING',
      source_hashes: section.source_hashes,
      source_lesson_code: row.source_lesson_code,
      source_unit_code: row.source_unit_code,
      passage_id: row.passage_id,
      question_id: row.question_id,
      source_status: 'DRAFT',
      rights: section.rights,
      distribution_authorization: section.distribution_authorization,
    });
    const options = [0, 1, 2, 3].map((index) => nullableHex(row.options[index]));
    lines.push(`-- ROW ${row.source_code}`);
    lines.push(`INSERT INTO assessment_question (source_code,knowledge_tag_id,skill,jlpt_level,difficulty,question_type,title,passage_html,audio_url,question_text,answer_text,option_1,option_2,option_3,option_4,correct_option,explanation,context_json,is_active) VALUES (${hex(row.source_code)},@owned_minna_read_tag,'READING','${row.level}','EASY','MULTIPLE_CHOICE',${hex(row.passage_title)},${hex(row.passage_html)},NULL,${hex(row.question_prompt)},${hex(row.answer_text)},${options.join(',')},${row.correct_option},${nullableHex(row.explanation)},${hex(context)},1) ON DUPLICATE KEY UPDATE knowledge_tag_id=VALUES(knowledge_tag_id),skill=VALUES(skill),jlpt_level=VALUES(jlpt_level),difficulty=VALUES(difficulty),question_type=VALUES(question_type),title=VALUES(title),passage_html=VALUES(passage_html),audio_url=NULL,question_text=VALUES(question_text),answer_text=VALUES(answer_text),option_1=VALUES(option_1),option_2=VALUES(option_2),option_3=VALUES(option_3),option_4=VALUES(option_4),correct_option=VALUES(correct_option),explanation=VALUES(explanation),context_json=VALUES(context_json),is_active=1;`);
  }
  // Owner 2026-10-21 (OD-09): held checks (e.g. a missing picture) are switched off; rows and attempts stay.
  const held = section.held_checks ?? [];
  if (held.length) {
    lines.push(`-- HELD ${held.map((row) => `${row.source_code}:${row.reason}`).join(' ')}`);
    lines.push(`UPDATE assessment_question SET is_active=0 WHERE is_active=1 AND source_code IN (${held
      .map((row) => `'${row.source_code}'`).join(',')});`);
  }
  lines.push('COMMIT;', `-- END ${MARKER}`, '');
  return lines.join('\n');
}

export function replaceMinnaReadingBlock(sql, block) {
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
  const section = data.sections?.jlpt_minna_n5_n4_reading;
  const sectionHash = data.section_sha256?.jlpt_minna_n5_n4_reading;
  const block = renderMinnaReadingSql(section, sectionHash);
  const sqlPath = path.join(root, 'sql/seeds/learning_owned.sql');
  const current = await readFile(sqlPath, 'utf8');
  const updated = replaceMinnaReadingBlock(current, block);
  if (process.argv.includes('--check')) {
    if (current !== updated) throw new Error('Canonical Minna reading SQL block is stale.');
  } else {
    await writeFile(sqlPath, updated, 'utf8');
  }
  console.log(`Rendered ${section.exercises.length} source-bound Minna reading checks for the Learning path`
    + ` (${(section.held_checks ?? []).length} held).`);
}
