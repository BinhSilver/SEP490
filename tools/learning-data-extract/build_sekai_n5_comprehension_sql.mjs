import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const MARKER = 'data/learning-owned/learning-content.json#sections.sekai_n5_comprehension';
const hex = (value) => `CONVERT(X'${Buffer.from(String(value), 'utf8').toString('hex')}' USING utf8mb4)`;
const nullableHex = (value) => value == null ? 'NULL' : hex(value);

function validateExercise(row) {
  if (!/^LP-OWNED-SEKAI-N5-L\d{2}-(READ|LISTEN)-c\d+$/.test(row.source_code)
      || row.jlpt_level !== 'N5' || !['READING', 'LISTENING'].includes(row.skill)
      || !row.lesson_code || !row.source_file || !/^[a-f0-9]{64}$/i.test(row.source_sha256)
      || !row.passage_html || !row.question_prompt || row.questions?.length !== 1) {
    throw new Error(`Invalid owned N5 comprehension row: ${row.source_code}`);
  }
  if ((row.skill === 'LISTENING' && (!row.audio_url || !row.transcript_ja || !row.audio_file))
      || (row.skill === 'READING' && (row.audio_url || row.audio_file))) {
    throw new Error(`Invalid exact audio/source mapping for ${row.source_code}`);
  }
  const question = row.questions[0];
  if (question.options?.length !== 4 || new Set(question.options).size !== 4
      || question.options[question.correct_option - 1] !== question.answer_text) {
    throw new Error(`Invalid unique answer options for ${row.source_code}`);
  }
}

export function renderSqlBlock(section, canonicalSectionSha256) {
  if (!/^[a-f0-9]{64}$/i.test(canonicalSectionSha256 ?? '') || !Array.isArray(section?.exercises)
      || section.exercises.length === 0) {
    throw new Error('The canonical N5 comprehension section must be hash-verified and non-empty.');
  }
  const sourceCodes = new Set();
  for (const row of section.exercises) {
    validateExercise(row);
    if (sourceCodes.has(row.source_code)) throw new Error(`Duplicate owned source code: ${row.source_code}`);
    sourceCodes.add(row.source_code);
  }
  const tagValues = [
    ['LP-OWNED-SEKAI-N5-READING', 'Sekai N5 reading lesson questions'],
    ['LP-OWNED-SEKAI-N5-LISTENING', 'Sekai N5 listening lesson questions'],
  ];
  const lines = [
    `-- BEGIN ${MARKER} SHA256 ${canonicalSectionSha256}`,
    '-- [JLAS-W0108] Owned Reading/Listening question copies from exact Sekai N5 dialogue pairs.',
    '-- Idempotent by source_code; JLPT tag stays in context while the knowledge tag is task-owned.',
    ...tagValues.map(([code, name]) => `INSERT INTO tag (code,name,tag_type) VALUES (${hex(code)},${hex(name)},'KNOWLEDGE') ON DUPLICATE KEY UPDATE name=VALUES(name);`),
    "SET @owned_sekai_n5_read_tag=(SELECT id FROM tag WHERE code='LP-OWNED-SEKAI-N5-READING' LIMIT 1);",
    "SET @owned_sekai_n5_listen_tag=(SELECT id FROM tag WHERE code='LP-OWNED-SEKAI-N5-LISTENING' LIMIT 1);",
  ];
  for (const row of section.exercises) {
    const question = row.questions[0];
    const options = question.options.map((value) => hex(value));
    const context = JSON.stringify({
      additional_tags: ['JLPT'],
      learning_owned: true,
      lesson_code: row.lesson_code,
      scene_code: row.scene_code,
      source_file: row.source_file,
      source_sha256: row.source_sha256,
    });
    const tag = row.skill === 'READING' ? '@owned_sekai_n5_read_tag' : '@owned_sekai_n5_listen_tag';
    lines.push(`-- ROW ${row.source_code}`);
    if (row.audio_url) lines.push(`-- AUDIO ${row.audio_url}`);
    lines.push(`INSERT INTO assessment_question (source_code,knowledge_tag_id,skill,jlpt_level,difficulty,question_type,title,passage_html,audio_url,question_text,answer_text,option_1,option_2,option_3,option_4,correct_option,explanation,context_json,is_active) VALUES (${hex(row.source_code)},${tag},'${row.skill}','N5','EASY','MULTIPLE_CHOICE',${hex(row.scene_title)},${hex(row.passage_html)},${nullableHex(row.audio_url)},${hex(row.question_prompt)},${hex(question.answer_text)},${options.join(',')},${question.correct_option},${hex(question.explanation)},${hex(context)},1) ON DUPLICATE KEY UPDATE knowledge_tag_id=VALUES(knowledge_tag_id),skill=VALUES(skill),jlpt_level=VALUES(jlpt_level),difficulty=VALUES(difficulty),question_type=VALUES(question_type),title=VALUES(title),passage_html=VALUES(passage_html),audio_url=VALUES(audio_url),question_text=VALUES(question_text),answer_text=VALUES(answer_text),option_1=VALUES(option_1),option_2=VALUES(option_2),option_3=VALUES(option_3),option_4=VALUES(option_4),correct_option=VALUES(correct_option),explanation=VALUES(explanation),context_json=VALUES(context_json),is_active=VALUES(is_active);`);
  }
  lines.push(`-- END ${MARKER}`, '');
  return lines.join('\n');
}

export function replaceSeedSection(sql, block) {
  const newline = sql.includes('\r\n') ? '\r\n' : '\n';
  const formattedBlock = block.replace(/\r?\n/g, newline);
  const begin = `-- BEGIN ${MARKER} SHA256 `;
  const end = `-- END ${MARKER}`;
  const start = sql.indexOf(begin);
  if (start < 0) return `${sql.trimEnd()}${newline}${newline}${formattedBlock}`;
  const endStart = sql.indexOf(end, start);
  if (endStart < 0) throw new Error(`Unclosed consolidated SQL section: ${MARKER}`);
  const afterEnd = sql.indexOf('\n', endStart);
  return `${sql.slice(0, start)}${formattedBlock}${afterEnd < 0 ? '' : sql.slice(afterEnd + 1)}`;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = path.resolve(process.argv[2] ?? '.');
  const dataPath = path.join(root, 'data/learning-owned/learning-content.json');
  const sqlPath = path.join(root, 'sql/seeds/learning_owned.sql');
  const data = JSON.parse(await readFile(dataPath, 'utf8'));
  const section = data.sections?.sekai_n5_comprehension;
  const sectionHash = data.section_sha256?.sekai_n5_comprehension;
  if (section?.exercises?.length !== 100) throw new Error('Expected exactly 100 N5 comprehension questions.');
  const block = renderSqlBlock(section, sectionHash);
  const current = await readFile(sqlPath, 'utf8');
  const updated = replaceSeedSection(current, block);
  if (process.argv.includes('--check')) {
    if (current !== updated) throw new Error('Consolidated SQL comprehension section is stale.');
  } else {
    await writeFile(sqlPath, updated, 'utf8');
  }
  console.log(`Rendered ${section.exercises.length} exact-source N5 Reading/Listening questions.`);
}
