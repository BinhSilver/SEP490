// [JLAS-W0108] OD-08 (owner 2026-10-24): renders the Speed Master N5/N4 reading section into its learning_owned.sql block.
//   node tools/learning-data-extract/build_speed_n5_n4_reading_sql.mjs . [--check]
import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { CHAPTERS } from './build_speed_n5_n4_reading.mjs';

const MARKER = 'data/learning-owned/learning-content.json#sections.jlpt_speed_n5_n4_reading';
const TAG = 'LP-OWNED-SPEED-N5N4-READING';
const hex = (value) => `CONVERT(X'${Buffer.from(String(value), 'utf8').toString('hex')}' USING utf8mb4)`;
const nullableHex = (value) => value == null || value === '' ? 'NULL' : hex(value);
const escapeHtml = (value) => String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;')
  .replaceAll('>', '&gt;').replaceAll('"', '&quot;').replaceAll("'", '&#39;');
const norm = (value) => String(value ?? '').normalize('NFKC').trim().replace(/\s+/g, ' ').toLowerCase();
const CODE = /^LP-OWNED-SPEED-(N5|N4)-L(\d{2})-READ-P(\d+)-Q\d{3}$/;

function validate(section, sectionHash) {
  if (!/^[a-f0-9]{64}$/.test(sectionHash ?? '')) throw new Error('Speed Master N5/N4 section checksum is missing.');
  if (section?.quiz_eligible !== false || section.smart_test_eligible !== false || section.study_eligible !== true
      || section.rights !== 'OWNER_CONFIRMED_TEAM_DISTRIBUTION'
      || section.distribution_authorization !== 'JLAS_OWNER_CONFIRMED_TEAM_DISTRIBUTION_2026-10-24'
      || section.review_status !== 'CHUA_DUYET' || !Array.isArray(section.exercises)) {
    throw new Error('Speed Master N5/N4 section is not a Learning-only, owner-authorized copy.');
  }
  const seen = new Set();
  const questionsByLevel = { N5: 0, N4: 0 };
  for (const exercise of section.exercises) {
    const [first, last] = CHAPTERS[exercise.level] ?? [];
    if (!first || exercise.chapter < first || exercise.chapter > last || !Array.isArray(exercise.text_lines)
        || !exercise.text_lines.length || !exercise.questions?.length) {
      throw new Error(`Invalid Speed Master N5/N4 exercise ${exercise.source_group}`);
    }
    for (const question of exercise.questions) {
      const match = CODE.exec(question.source_code ?? '');
      const options = question.options ?? [];
      if (!match || match[1] !== exercise.level || Number(match[2]) !== exercise.chapter || Number(match[3]) !== exercise.slot
          || !question.source_code.startsWith(`${exercise.source_group}-Q`) || seen.has(question.source_code)
          || !String(question.prompt ?? '').trim() || options.length < 3 || options.length > 4
          || options.some((o) => !String(o ?? '').trim()) || new Set(options.map(norm)).size !== options.length
          || !Number.isInteger(question.correct_option) || question.correct_option < 1 || question.correct_option > options.length
          || options[question.correct_option - 1] !== question.answer_text) {
        throw new Error(`Invalid Speed Master N5/N4 question ${question.source_code}`);
      }
      seen.add(question.source_code);
      questionsByLevel[exercise.level]++;
    }
  }
  for (const level of ['N5', 'N4']) {
    if (questionsByLevel[level] !== section.counts?.[level]?.questions) {
      throw new Error(`Speed Master ${level} counts do not match its exercises.`);
    }
  }
}

export function renderSpeedN5N4Sql(section, sectionHash) {
  validate(section, sectionHash);
  const lines = [
    `-- BEGIN ${MARKER} SHA256 ${sectionHash}`,
    '-- [JLAS-W0108] OD-08 (owner 2026-10-24): Speed Master N5 reading → Sekai N5 chapters 13–25, N4 → Sekai N4 chapters 26–50.',
    '-- Learning-path study material only (never Smart Test or a scored quiz); source VNJPClub, CHUA_DUYET.',
    '-- Rows dropped from the canonical set are retired first; current rows are upserted by source_code and re-activated.',
    'START TRANSACTION;',
    `INSERT INTO tag (code,name,tag_type) VALUES (${hex(TAG)},${hex('Learning-owned Speed Master N5/N4 reading')},'KNOWLEDGE') ON DUPLICATE KEY UPDATE name=VALUES(name);`,
    // A plain ASCII literal: comparing tag.code with CONVERT(... USING utf8mb4) mixes collations in MySQL.
    `SET @owned_speed_n5n4_tag=(SELECT id FROM tag WHERE code='${TAG}' LIMIT 1);`,
    "UPDATE assessment_question SET is_active=0 WHERE source_code LIKE 'LP-OWNED-SPEED-N5-L%' OR source_code LIKE 'LP-OWNED-SPEED-N4-L%';",
  ];
  for (const exercise of section.exercises) {
    const html = exercise.text_lines.map((line) => `<p>${escapeHtml(line)}</p>`).join('');
    for (const question of exercise.questions) {
      const context = JSON.stringify({
        learning_owned: true, learning_path_only: true, study_eligible: true, quiz_eligible: false, smart_test_eligible: false,
        source: `SPEED_MASTER_${exercise.level}_VNJPCLUB`, source_id: exercise.source_id, source_lesson_code: exercise.source_lesson_code,
        source_url: exercise.source_url, source_section_sha256: section.source_section_sha256,
        chapter: exercise.chapter, slot: exercise.slot, type: exercise.type,
        options_repaired_from_page: question.options_repaired_from_page === true,
        review_status: section.review_status, label: section.label, rights: section.rights,
        distribution_authorization: section.distribution_authorization,
        // Source line-by-line translation; kept out of passage_html so the exercise revision does not depend on it.
        ...(exercise.passage_translation?.length ? { passage_translation: exercise.passage_translation } : {}),
      });
      const options = [0, 1, 2, 3].map((index) => nullableHex(question.options[index]));
      lines.push(`-- ROW ${question.source_code}`);
      lines.push(`INSERT INTO assessment_question (source_code,knowledge_tag_id,skill,jlpt_level,difficulty,question_type,title,passage_html,audio_url,question_text,answer_text,option_1,option_2,option_3,option_4,correct_option,explanation,context_json,is_active) VALUES (${hex(question.source_code)},@owned_speed_n5n4_tag,'READING','${exercise.level}','EASY','MULTIPLE_CHOICE',${hex(exercise.title)},${hex(html)},NULL,${hex(question.prompt)},${hex(question.answer_text)},${options.join(',')},${question.correct_option},${nullableHex(question.explanation)},${hex(context)},1) ON DUPLICATE KEY UPDATE knowledge_tag_id=VALUES(knowledge_tag_id),skill=VALUES(skill),jlpt_level=VALUES(jlpt_level),difficulty=VALUES(difficulty),question_type=VALUES(question_type),title=VALUES(title),passage_html=VALUES(passage_html),audio_url=VALUES(audio_url),question_text=VALUES(question_text),answer_text=VALUES(answer_text),option_1=VALUES(option_1),option_2=VALUES(option_2),option_3=VALUES(option_3),option_4=VALUES(option_4),correct_option=VALUES(correct_option),explanation=VALUES(explanation),context_json=VALUES(context_json),is_active=1;`);
    }
  }
  lines.push('COMMIT;', `-- END ${MARKER}`, '');
  return lines.join('\n');
}

export function replaceSpeedN5N4Block(sql, block) {
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
  const section = data.sections?.jlpt_speed_n5_n4_reading;
  const block = renderSpeedN5N4Sql(section, data.section_sha256?.jlpt_speed_n5_n4_reading);
  const sqlPath = path.join(root, 'sql/seeds/learning_owned_part2.sql');
  const current = await readFile(sqlPath, 'utf8');
  const updated = replaceSpeedN5N4Block(current, block);
  if (process.argv.includes('--check')) {
    if (current !== updated) throw new Error('Speed Master N5/N4 SQL block is stale.');
  } else {
    await writeFile(sqlPath, updated, 'utf8');
  }
  if (process.argv.includes('--block-out')) {
    await writeFile(process.argv[process.argv.indexOf('--block-out') + 1], block, 'utf8');
  }
  console.log(`Rendered ${section.counts.N5.questions + section.counts.N4.questions} Speed Master N5/N4 Learning rows.`);
}
