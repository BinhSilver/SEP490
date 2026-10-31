import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const MARKER = 'data/learning-owned/learning-content.json#sections.jlpt_minna_n5_n4_listening';
const hex = (value) => `CONVERT(X'${Buffer.from(String(value), 'utf8').toString('hex')}' USING utf8mb4)`;
const escapeHtml = (value) => String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;')
  .replaceAll('>', '&gt;').replaceAll('"', '&quot;');

export function renderMinnaSql(section, sectionHash) {
  if (!/^[a-f0-9]{64}$/.test(sectionHash ?? '') || section?.lessons?.length !== 50
      || section.counts?.N5?.clips !== 100 || section.counts?.N4?.clips !== 104
      || section.counts?.N5?.checks !== 641 || section.counts?.N4?.checks !== 641
      || section.quiz_eligible !== false || section.smart_test_eligible !== false
      || section.rights !== 'OWNER_CONFIRMED_TEAM_DISTRIBUTION'
      || section.distribution_authorization !== 'JLAS_OWNER_CONFIRMED_TEAM_DISTRIBUTION_2026-10-18') {
    throw new Error('Canonical Minna listening section or checksum is incomplete.');
  }
  const lines = [
    `-- BEGIN ${MARKER} SHA256 ${sectionHash}`,
    '-- [JLAS-W0108] Minna Task25 listening source copy, lesson-mapped to owned Sekai N5/N4.',
    '-- Audio playback resolves exact verified local media; task-owned questions are excluded from Smart Test.',
    'START TRANSACTION;',
    `INSERT INTO tag (code,name,tag_type) VALUES (${hex('LP-OWNED-MINNA-LISTENING')},${hex('Learning-owned Minna listening checks')},'KNOWLEDGE') ON DUPLICATE KEY UPDATE name=VALUES(name);`,
    "SET @owned_minna_listen_tag=(SELECT id FROM tag WHERE code='LP-OWNED-MINNA-LISTENING' LIMIT 1);",
  ];
  const sourceCodes = new Set();
  let rowCount = 0;
  for (const lesson of section.lessons) {
    const level = lesson.cap;
    const number = String(lesson.bai).padStart(2, '0');
    if (!['N5', 'N4'].includes(level) || lesson.owned_lesson_code !== `LP-OWNED-SEKAI-${level}-L${number}`) {
      throw new Error(`Minna lesson ${lesson.bai} has no exact owned lesson mapping.`);
    }
    const clips = new Map(lesson.cau.map((clip) => [clip.ma, clip]));
    for (const check of lesson.kiem_tra) {
      const clipCode = check.id.match(/^[LW]-(c\d+)-/)?.[1];
      const clip = clips.get(clipCode);
      if (!clip) throw new Error(`Unmapped Minna check ${lesson.bai}/${check.id}`);
      const sourceCode = `LP-OWNED-MINNA-${level}-L${number}-LISTEN-${clipCode}-${check.id}`;
      if (sourceCodes.has(sourceCode)) throw new Error(`Duplicate Minna source code ${sourceCode}`);
      sourceCodes.add(sourceCode);
      const transcript = clip.muc.flatMap((item) => item.script).map((line) =>
        `<p>${escapeHtml(`${line.speaker ? `${line.speaker}: ` : ''}${line.ja}`)}</p>`).join('');
      const options = check.phuong_an.map((option) => option.text);
      const answerIndex = check.phuong_an.findIndex((option) => String(option.k) === String(check.dap_an));
      if (!transcript || options.length !== 4 || new Set(options.map((v) => v.trim().replace(/\s+/g, ' ').toLowerCase())).size !== 4
          || answerIndex < 0) throw new Error(`Invalid Minna check ${sourceCode}`);
      const context = JSON.stringify({ learning_owned: true, learning_path_only: true,
        study_eligible: true, quiz_eligible: false, smart_test_eligible: false,
        source: 'MINNA_CHOUKAI_TASK25', source_page: lesson.url, source_sha256: section.source_sha256,
        audio_file: clip.audio_file, lesson_code: lesson.owned_lesson_code, clip_code: clipCode, clip_title: clip.ten,
        check_id: check.id, source_status: 'DRAFT', rights: section.rights,
        distribution_authorization: section.distribution_authorization });
      if (!/^audio\/vnjpclub\/N[45]\/minna-so-cap-[12]\/bai-\d{2}\/[A-Za-z0-9_-]+\.mp3$/.test(clip.audio_file ?? '')) {
        throw new Error(`Invalid local audio file for ${sourceCode}`);
      }
      const title = `Minna bài ${lesson.bai} · Nghe ${clipCode.slice(1)}`;
      lines.push(`-- ROW ${sourceCode}`);
      lines.push(`INSERT INTO assessment_question (source_code,knowledge_tag_id,skill,jlpt_level,difficulty,question_type,title,passage_html,audio_url,question_text,answer_text,option_1,option_2,option_3,option_4,correct_option,explanation,context_json,is_active) VALUES (${hex(sourceCode)},@owned_minna_listen_tag,'LISTENING','${level}','EASY','MULTIPLE_CHOICE',${hex(title)},${hex(transcript)},${hex(clip.audio_url)},${hex(check.cau_hoi)},${hex(options[answerIndex])},${options.map(hex).join(',')},${answerIndex + 1},${hex(check.giai_thich ?? '')},${hex(context)},1) ON DUPLICATE KEY UPDATE knowledge_tag_id=VALUES(knowledge_tag_id),skill=VALUES(skill),jlpt_level=VALUES(jlpt_level),difficulty=VALUES(difficulty),question_type=VALUES(question_type),title=VALUES(title),passage_html=VALUES(passage_html),audio_url=VALUES(audio_url),question_text=VALUES(question_text),answer_text=VALUES(answer_text),option_1=VALUES(option_1),option_2=VALUES(option_2),option_3=VALUES(option_3),option_4=VALUES(option_4),correct_option=VALUES(correct_option),explanation=VALUES(explanation),context_json=VALUES(context_json),is_active=VALUES(is_active);`);
      rowCount++;
    }
  }
  if (rowCount !== 1282 || sourceCodes.size !== rowCount) throw new Error('Minna listening SQL lost checks.');
  lines.push('COMMIT;', `-- END ${MARKER}`, '');
  return lines.join('\n');
}

export function replaceMinnaBlock(sql, block) {
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
  const block = renderMinnaSql(data.sections?.jlpt_minna_n5_n4_listening,
    data.section_sha256?.jlpt_minna_n5_n4_listening);
  const sqlPath = path.join(root, 'sql/seeds/learning_owned_part2.sql');
  const sql = await readFile(sqlPath, 'utf8');
  const updated = replaceMinnaBlock(sql, block);
  if (process.argv.includes('--check')) {
    if (sql !== updated) throw new Error('Canonical Minna SQL block is stale.');
  } else {
    await writeFile(sqlPath, updated, 'utf8');
  }
  console.log('Rendered 1282 source-bound Minna listening checks in the consolidated Learning seed.');
}
