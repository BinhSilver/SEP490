import fs from 'node:fs/promises';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { readLearningContent } from './learning_content_store.mjs';

const BEGIN = '-- BEGIN generated communication-goal lessons (learning-content.json)';
const END = '-- END generated communication-goal lessons';
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const quote = (value) => value == null ? 'NULL'
  : `CONVERT(X'${Buffer.from(String(value), 'utf8').toString('hex')}' USING utf8mb4)`;
const raw = (sql) => ({ sql });
const valueSql = (value) => value && typeof value === 'object' && 'sql' in value ? value.sql : quote(value);
const LEVEL_RANK = { N5: 1, N4: 2, N3: 3, N2: 4, N1: 5 };
const variable = (prefix, key) => `@${prefix}_${key.replace(/[^a-zA-Z0-9]/g, '_').toLowerCase()}`;

// Furigana markup: {漢字|かんじ} groups inside the line text; stripping the groups must give the line back.
const RUBY = /\{([^{}|]+)\|([^{}|]+)\}/g;
const HAN = /[\p{Script=Han}々〆ヶ]/u;
const hiragana = (text) => text.replace(/[ァ-ヶ]/g, (c) => String.fromCharCode(c.charCodeAt(0) - 0x60));
export const stripRuby = (markup) => markup.replace(RUBY, '$1');

/** Reading of a verbatim excerpt of a line, taken from its furigana; null when absent or splitting a ruby group. */
export function rubyReading(markup, excerpt) {
  const groups = [];
  let last = 0;
  for (const match of markup.matchAll(RUBY)) {
    for (const ch of markup.slice(last, match.index)) groups.push({ base: ch, reading: ch });
    groups.push({ base: match[1], reading: match[2] });
    last = match.index + match[0].length;
  }
  for (const ch of markup.slice(last)) groups.push({ base: ch, reading: ch });
  const start = groups.map((group) => group.base).join('').indexOf(excerpt);
  if (!excerpt || start < 0) return null;
  const end = start + excerpt.length;
  let position = 0;
  let reading = '';
  for (const group of groups) {
    const from = position;
    position += group.base.length;
    if (position <= start || from >= end) continue;
    if (from < start || position > end) return null;
    reading += group.reading;
  }
  return reading;
}

function validateRubyText(markup, ja, where) {
  if (typeof markup !== 'string' || stripRuby(markup) !== ja) throw new Error(`${where} furigana does not match its text.`);
  for (const match of markup.matchAll(RUBY)) {
    if (!/^[ぁ-ゖー]+$/u.test(match[2])) throw new Error(`${where} reading must be hiragana: ${match[0]}`);
  }
  if (HAN.test(markup.replace(RUBY, ''))) throw new Error(`${where} has kanji without a reading.`);
}

/** Owner 2026-10-19 (G1–G3, §2.24): at every learner turn the learner picks the real reply among 3 options.
 * Returns the challenge as stored for the server: the answer is the real line, the client never receives which is right. */
export function buildDialogueChallenge(dialogue, aid, where) {
  const challenge = aid?.challenge;
  if (!challenge) throw new Error(`${where} needs a reply challenge.`);
  const learnerLines = dialogue.lines.filter((line) => line.speaker === challenge.learnerSpeaker);
  if (!learnerLines.length) throw new Error(`${where} challenge learner ${challenge.learnerSpeaker} never speaks.`);
  const turns = Array.isArray(challenge.turns) ? challenge.turns : [];
  if (turns.map((turn) => turn.line).join(',') !== learnerLines.map((line) => line.order).join(',')) {
    throw new Error(`${where} challenge must cover every learner turn in order.`);
  }
  return {
    learnerSpeaker: challenge.learnerSpeaker,
    turns: turns.map((turn) => {
      const index = dialogue.lines.findIndex((line) => line.order === turn.line);
      const line = dialogue.lines[index];
      if (!Array.isArray(turn.distractors) || turn.distractors.length !== 2) throw new Error(`${where}:${turn.line} needs two distractors.`);
      const texts = new Set([line.ja]);
      const distractors = turn.distractors.map((d, n) => {
        if (!d?.ja?.trim() || !d?.vi?.trim()) throw new Error(`${where}:${turn.line} distractor ${n + 1} is incomplete.`);
        validateRubyText(d.furigana, d.ja, `${where}:${turn.line} distractor ${n + 1}`);
        texts.add(d.ja);
        return { ja: d.ja, vi: d.vi, furigana: d.furigana };
      });
      if (texts.size !== 3) throw new Error(`${where}:${turn.line} options must be three distinct replies.`);
      if (!turn.why_vi?.trim()) throw new Error(`${where}:${turn.line} needs an explanation.`);
      return { line: turn.line, answer: { ja: line.ja, vi: line.vi, furigana: aid.furigana[index] }, distractors, why_vi: turn.why_vi };
    })
  };
}

/** Owner 2026-10-19 (C2/C4): every dialogue ships line furigana and 5–8 "Từ & cụm từ" taken from its lines. */
export function validateDialogueAid(dialogue, aid, where) {
  if (!aid || !Array.isArray(aid.furigana) || !Array.isArray(aid.phrases)) throw new Error(`${where} needs dialogue aids.`);
  if (aid.furigana.length !== dialogue.lines.length) throw new Error(`${where} needs furigana for every line.`);
  dialogue.lines.forEach((line, index) => {
    const markup = aid.furigana[index];
    if (stripRuby(markup) !== line.ja) throw new Error(`${where}:${line.order} furigana does not match the line.`);
    for (const match of markup.matchAll(RUBY)) {
      if (!/^[ぁ-ゖー]+$/u.test(match[2])) throw new Error(`${where}:${line.order} reading must be hiragana: ${match[0]}`);
    }
    if (HAN.test(markup.replace(RUBY, ''))) throw new Error(`${where}:${line.order} has kanji without a reading.`);
  });
  if (aid.phrases.length < 5 || aid.phrases.length > 8) throw new Error(`${where} needs 5–8 phrases.`);
  for (const phrase of aid.phrases) {
    const index = dialogue.lines.findIndex((line) => line.order === phrase.line);
    if (index < 0 || !dialogue.lines[index].ja.includes(phrase.ja)) throw new Error(`${where} phrase ${phrase.ja} is not in line ${phrase.line}.`);
    const reading = rubyReading(aid.furigana[index], phrase.ja);
    if (!reading || hiragana(reading) !== hiragana(phrase.reading || '')) throw new Error(`${where} phrase ${phrase.ja} reading differs from the line furigana.`);
    if (!phrase.vi?.trim()) throw new Error(`${where} phrase ${phrase.ja} needs a Vietnamese meaning.`);
  }
}

function upsert(table, columns, values, updateColumns, idVariable) {
  const updates = ['id=LAST_INSERT_ID(id)', ...updateColumns.map((column) => `${column}=VALUES(${column})`)];
  return `INSERT INTO ${table} (${columns.join(', ')}) VALUES (${values.map(valueSql).join(', ')})\n` +
    `ON DUPLICATE KEY UPDATE ${updates.join(', ')};\nSET ${idVariable}=LAST_INSERT_ID();\n`;
}

function normalize(text) {
  return [...String(text).normalize('NFKC')].map((char) => {
    const code = char.codePointAt(0);
    return code >= 0x30a1 && code <= 0x30f6 ? String.fromCodePoint(code - 0x60) : char;
  }).join('');
}

const expected = (section, lesson, items, grammar, roles) =>
  `INSERT INTO jlas_comm_expected (section_id,lesson_id,items,grammar,roles) VALUES (${section},${lesson},${items},${grammar},${roles})\n` +
  `ON DUPLICATE KEY UPDATE items=VALUES(items),grammar=VALUES(grammar),roles=VALUES(roles);`;

function renderLessonScope(lessonId, goalId, topicId, order, key) {
  return `INSERT INTO lesson_learning_scope (lesson_id, goal_id, topic_id, competency_id, competency_level_id, learning_mode, required, scope_order, external_key, review_status)\n` +
    `VALUES (${lessonId}, ${goalId}, ${topicId}, NULL, NULL, NULL, TRUE, ${order}, ${quote(key)}, 'APPROVED')\n` +
    `ON DUPLICATE KEY UPDATE lesson_id=VALUES(lesson_id), goal_id=VALUES(goal_id), topic_id=VALUES(topic_id), scope_order=VALUES(scope_order), required=TRUE, review_status='APPROVED';\n`;
}

export function renderCommunicationPathSeed(data) {
  const config = data.sections.communication_path_release;
  const snapshots = data.sections.goal_source_snapshots;
  if (!config || !snapshots) throw new Error('Communication release configuration or owned source snapshots are missing.');
  // The validator procedure and the expected-shape table are created BEFORE the transaction: CREATE/DROP PROCEDURE
  // commit implicitly, so inside the transaction they would make the validator's SIGNAL unable to roll back.
  // V06: the block names the exact JSON sections it was rendered from, so a stale block is detectable.
  const hashes = data.section_sha256 ?? {};
  if (!/^[0-9a-f]{64}$/.test(hashes.communication_path_release ?? '') || !/^[0-9a-f]{64}$/.test(hashes.goal_source_snapshots ?? '')) {
    throw new Error('Communication source sections have no SHA-256 in learning-content.json.');
  }
  const out = [BEGIN, `-- SOURCE SHA256 communication_path_release=${hashes.communication_path_release} goal_source_snapshots=${hashes.goal_source_snapshots}`,
    '-- Generated. Edit the canonical JSON section and run this generator; do not hand-edit this block.',
    '-- Communication lessons have no scored quiz or JLPT grading. TTS is an explicit browser-speech sentinel.',
    `-- Attribution: ${config.attribution.jmdict} ${config.attribution.url}`,
    '-- Fails closed: any validator SIGNAL aborts before COMMIT, so the whole block rolls back.',
    'SET NAMES utf8mb4;', VALIDATOR,
    'DROP TEMPORARY TABLE IF EXISTS jlas_comm_expected;',
    'CREATE TEMPORARY TABLE jlas_comm_expected (section_id BIGINT UNSIGNED NOT NULL PRIMARY KEY, lesson_id BIGINT UNSIGNED NOT NULL, items INT UNSIGNED NOT NULL, grammar INT UNSIGNED NOT NULL, roles INT UNSIGNED NOT NULL);',
    'START TRANSACTION;'];
  const add = (sql) => out.push(sql);

  add(`INSERT INTO content_voice (code,name,gender,description,is_default) VALUES ('jlas-tts-ja','Giọng tổng hợp tiếng Nhật',NULL,'Web Speech API ja-JP; văn bản bổ sung J-LAS',TRUE) ON DUPLICATE KEY UPDATE name=VALUES(name),description=VALUES(description),is_default=TRUE;`);

  for (const [goalCode, goal] of Object.entries(config.goals)) {
    const pack = snapshots[goalCode];
    const content = pack?.content;
    if (!content?.meta?.topics || !Array.isArray(content.vocabulary) || !Array.isArray(content.dialogues)) {
      throw new Error(`Owned source snapshot incomplete for ${goalCode}.`);
    }
    const g = variable('goal', goalCode);
    const book = variable('book', goalCode);
    const unit = variable('unit', goalCode);
    add(`\n-- ${goalCode}: owned source ${pack.source_path}; SHA-256 ${pack.source_sha256}.`);
    add(upsert('learning_goal', ['goal_code','display_name_vi','description_vi','entry_policy','requires_assessment','active','external_key','source_code','review_status'],
      [goalCode, goal.displayNameVi, goal.descriptionVi, 'SURVEY_ONLY', 0, 1, `JLAS-COMM-GOAL-${goalCode}`, `JLAS-COMM-${goalCode}`, 'APPROVED'],
      ['display_name_vi','description_vi','entry_policy','requires_assessment','active','source_code','review_status'], g));
    add(upsert('textbook', ['source_code','title','description','jlpt_level','status','is_learning_path_source','version'],
      [`LP-OWNED-COMM-${goalCode}`, `J-LAS · ${goal.displayNameVi}`, goal.descriptionVi, null, 'PUBLISHED', 1, 1],
      ['title','description','status','is_learning_path_source'], book));
    add(`INSERT INTO unit (textbook_id,source_code,title,description,order_index) VALUES (${book},'COMM-PATHS','Các chủ đề và tình huống',${quote('Tình huống được chọn độc lập; người học có thể mở từng bài theo ý muốn.')},1)\n` +
      `ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id),title=VALUES(title),description=VALUES(description);\nSET ${unit}=LAST_INSERT_ID();`);
    // lesson has UNIQUE (unit_id, source_code) and UNIQUE (unit_id, order_index): park the current order first so a
    // re-ordered lesson upserts its own row (by source_code) instead of the row that held its new position.
    add(`UPDATE lesson SET order_index=order_index+1000000 WHERE unit_id=${unit} AND order_index<1000000;`);

    // Owner 2026-10-19: each dialogue belongs to an approved topic; a topic lists its vocabulary first, then
    // its dialogues easy to hard (N5 -> N1), keeping the source order for ties.
    const dialogueTopics = goal.dialogueTopics || {};
    for (const dialogue of content.dialogues) {
      if (!dialogue.lines?.length || !dialogue.key_phrases?.length) throw new Error(`${dialogue.id} needs dialogue lines and applied grammar key phrases.`);
      if (!goal.topicCodes.includes(dialogueTopics[dialogue.id])) throw new Error(`${goalCode} dialogue ${dialogue.id} has no approved topic.`);
      if (!LEVEL_RANK[dialogue.level]) throw new Error(`${dialogue.id} has no JLPT level.`);
    }
    const groups = goal.topicCodes.map((code) => ({
      code,
      dialogues: content.dialogues.map((dialogue, index) => ({ dialogue, index }))
        .filter((entry) => dialogueTopics[entry.dialogue.id] === code)
        .sort((a, b) => LEVEL_RANK[a.dialogue.level] - LEVEL_RANK[b.dialogue.level] || a.index - b.index)
        .map((entry) => entry.dialogue)
    }));
    const topicVars = new Map();
    const topicRecords = [];
    let topicOrder = 0;
    for (const group of groups) {
      const meta = content.meta.topics[group.code];
      if (!meta) throw new Error(`${goalCode} metadata has no topic ${group.code}.`);
      topicRecords.push({ code: group.code, titleVi: meta.vi, titleJa: meta.ja, description: `Từ vựng bổ sung J-LAS · ${meta.vi}`,
        order: ++topicOrder, parent: null, level: null });
      for (const dialogue of group.dialogues) {
        topicRecords.push({ code: `SITUATION-${dialogue.id}`, titleVi: dialogue.title_vi, titleJa: dialogue.title_ja,
          description: `Tình huống hội thoại bổ sung J-LAS · ${dialogue.situation_vi}`, order: ++topicOrder,
          parent: group.code, level: dialogue.level });
      }
    }
    for (const topic of topicRecords) {
      const t = variable('topic', `${goalCode}_${topic.code}`);
      topicVars.set(topic.code, t);
      add(upsert('learning_topic', ['goal_id','parent_topic_id','topic_code','title_vi','title_ja','topic_order','jlpt_level','description','active','external_key','source_code','review_status'],
        [raw(g), topic.parent ? raw(topicVars.get(topic.parent)) : null, topic.code, topic.titleVi, topic.titleJa, topic.order,
          topic.level, topic.description, 1, `JLAS-COMM-TOPIC-${goalCode}-${topic.code}`, `JLAS-COMM-${goalCode}`, 'APPROVED'],
        ['parent_topic_id','title_vi','title_ja','topic_order','jlpt_level','description','active','source_code','review_status'], t));
    }

    // Survey questions/options also have UNIQUE code + UNIQUE order: park the order (hidden from runtime meanwhile),
    // upsert by code, then retire anything still parked at a unique far order so the next run cannot collide.
    add(`UPDATE learning_survey_question SET question_order=question_order+1000,active=FALSE WHERE goal_id=${g} AND question_order<1000;`);
    add(`UPDATE learning_survey_option o JOIN learning_survey_question q ON q.id=o.question_id SET o.option_order=o.option_order+1000,o.review_status='NEEDS_REVIEW' WHERE q.goal_id=${g} AND o.option_order<1000;`);
    for (const [qIndex, question] of goal.survey.entries()) {
      const q = variable('question', `${goalCode}_${question.code}`);
      add(upsert('learning_survey_question', ['goal_id','survey_question_code','question_order','question_vi','selection_type','required','active','external_key','review_status'],
        [raw(g), question.code, qIndex + 1, question.question, question.selectionType, 1, 1, `JLAS-COMM-Q-${goalCode}-${question.code}`, 'APPROVED'],
        ['question_order','question_vi','selection_type','required','active','review_status'], q));
      for (const option of question.options) {
        const topic = option.topicCode ? topicVars.get(option.topicCode) : null;
        add(upsert('learning_survey_option', ['question_id','survey_option_code','option_order','label_vi','value_code','topic_id','competency_id','learning_mode','external_key','review_status'],
          [raw(q), option.code, option.order, option.label, option.value, topic ? raw(topic) : null, null, option.learningMode ?? null, `JLAS-COMM-OPT-${goalCode}-${option.code}`, 'APPROVED'],
          ['option_order','label_vi','value_code','topic_id','learning_mode','review_status'], variable('option', `${goalCode}_${option.code}`)));
      }
    }
    add(`UPDATE learning_survey_question SET question_order=100000+id WHERE goal_id=${g} AND question_order>=1000 AND question_order<100000;`);
    add(`UPDATE learning_survey_option o JOIN learning_survey_question q ON q.id=o.question_id SET o.option_order=100000+o.id WHERE q.goal_id=${g} AND o.option_order>=1000 AND o.option_order<100000;`);

    const tag = variable('tag', goalCode);
    add(upsert('tag', ['code','name','tag_type'], [`JLAS-COMM-${goalCode}-KNOWLEDGE`, goal.displayNameVi, 'KNOWLEDGE'], ['name','tag_type'], tag));
    let lessonOrder = 0;
    const createLesson = (sourceCode, title, description, topicCode, skill) => {
      const order = ++lessonOrder;
      const lesson = variable('lesson', `${goalCode}_${sourceCode}`);
      add(upsert('lesson', ['unit_id','source_code','knowledge_tag_id','title','description','order_index','estimated_minutes','lesson_type','skill','situation_tag','is_review','status'],
        [raw(unit), sourceCode, raw(tag), title, description, order, 20, 'CURRICULUM', skill, goalCode, 0, 'PUBLISHED'],
        ['knowledge_tag_id','title','description','order_index','estimated_minutes','lesson_type','skill','situation_tag','is_review','status'], lesson));
      add(`INSERT INTO lesson_section (lesson_id,title,section_type,order_index) VALUES (${lesson},${quote(title)},'EXPLANATION',1)\n` +
        `ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id),title=VALUES(title),section_type='EXPLANATION';\nSET ${variable('section', `${goalCode}_${sourceCode}`)}=LAST_INSERT_ID();`);
      add(renderLessonScope(lesson, g, topicVars.get(topicCode), order, `JLAS-COMM-SCOPE-${goalCode}-${sourceCode}`));
      return { lesson, section: variable('section', `${goalCode}_${sourceCode}`) };
    };

    const createVocabularyLesson = (topicCode) => {
      const words = content.vocabulary.filter((word) => word.topic === topicCode);
      if (words.length < 5) throw new Error(`${goalCode}/${topicCode} needs at least five vocabulary entries; got ${words.length}.`);
      const meta = content.meta.topics[topicCode];
      const sourceCode = `COMM-${goalCode}-V-${topicCode}`;
      const title = `J-LAS · Từ vựng · ${meta.vi}`;
      const desc = `Nội dung bổ sung J-LAS. Từ vựng nguồn ${pack.source_path}; đối chiếu từ điển JMdict/EDRDG (CC BY-SA 4.0) khi có mã JMdict. Không phải giáo trình chính thức.`;
      const { lesson, section } = createLesson(sourceCode, title, desc, topicCode, 'VOCABULARY');
      for (const [wordIndex, word] of words.entries()) {
        if (!word.word?.trim() || !word.reading?.trim() || !word.meaning_vi?.trim()) throw new Error(`Vocabulary incomplete: ${word.id}`);
        const expression = normalize(word.word);
        const reading = normalize(word.reading);
        const vocab = variable('vocab', `${goalCode}_${word.id}`);
        add(`INSERT INTO vocabulary_entry (expression,normalized_expression,reading,normalized_reading,meaning,jlpt_level,usage_scope,dictionary_status)\n` +
          `VALUES (${quote(word.word)},${quote(expression)},${quote(word.reading)},${quote(reading)},${quote(word.meaning_vi)},NULL,'TEXTBOOK','DRAFT')\n` +
          `ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id),meaning=IF(meaning IS NULL OR TRIM(meaning)='',VALUES(meaning),meaning);\nSET ${vocab}=LAST_INSERT_ID();`);
        const item = variable('item', `${goalCode}_${topicCode}_vocabulary_${wordIndex + 1}`);
        add(`INSERT INTO content_item (section_id,content_type,title,content,translated_text,order_index) VALUES (${section},'READING',${quote(`J-LAS · Từ vựng · ${word.word}`)},${quote(word.word)},${quote(word.meaning_vi)},${wordIndex + 1})\n` +
          `ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id),title=VALUES(title),content=VALUES(content),translated_text=VALUES(translated_text);\nSET ${item}=LAST_INSERT_ID();`);
        // A re-ordered list rewrites item k in place; drop its link to any other word so it never shows two words.
        add(`DELETE cv FROM content_vocabulary cv WHERE cv.content_item_id=${item} AND cv.vocabulary_id<>${vocab};`);
        add(`INSERT INTO content_vocabulary (content_item_id,vocabulary_id,learning_meaning_vi,learning_example_ja,learning_example_vi)\n` +
          `VALUES (${item},${vocab},${quote(word.meaning_vi)},NULL,NULL) ON DUPLICATE KEY UPDATE learning_meaning_vi=VALUES(learning_meaning_vi);`);
      }
      add(expected(section, lesson, words.length, 0, 0));
    };

    const createDialogueLesson = (dialogue) => {
      const sourceCode = `COMM-${goalCode}-D-${dialogue.id}`;
      const situationCode = `SITUATION-${dialogue.id}`;
      const title = `J-LAS · ${dialogue.title_vi}`;
      const desc = `${dialogue.situation_vi} Nội dung hội thoại và mẫu câu gốc J-LAS; audio dùng giọng tiếng Nhật tổng hợp trên trình duyệt, không phải bản ghi âm giáo trình.`;
      const { lesson, section } = createLesson(sourceCode, title, desc, situationCode, 'LISTENING');
      const grammarVars = [];
      for (const [index, phrase] of dialogue.key_phrases.entries()) {
        const example = dialogue.lines.find((line) => {
          const core = phrase.ja.replace(/[〜～]/g, '').trim();
          return core.length > 1 && line.ja.includes(core);
        }) || dialogue.lines[Math.min(index, dialogue.lines.length - 1)];
        const point = variable('grammar', `${goalCode}_${dialogue.id}_${index + 1}`);
        grammarVars.push(point);
        add(upsert('grammar_point', ['lesson_id','pattern','meaning','example','note','order_index'],
          [raw(lesson), phrase.ja, phrase.vi, example.ja, phrase.note ?? 'Mẫu câu được áp dụng trong hội thoại.', index + 1],
          ['pattern','meaning','example','note'], point));
      }
      const roleVars = new Map();
      const speakers = [...new Set(dialogue.lines.map((line) => line.speaker))];
      for (const [index, speaker] of speakers.entries()) {
        const role = variable('role', `${goalCode}_${dialogue.id}_${index + 1}`);
        roleVars.set(speaker, role);
        const roleName = content.meta.characters?.[speaker] || speaker;
        add(upsert('dialogue_role', ['lesson_id','code','name_vi','name_ja','description','voice_code'],
          [raw(lesson), `ROLE-${index + 1}`, roleName, speaker, roleName, null], ['name_vi','name_ja','description','voice_code'], role));
      }
      for (const [index, line] of dialogue.lines.entries()) {
        if (!line.ja?.trim() || !line.vi?.trim() || !roleVars.has(line.speaker)) throw new Error(`${dialogue.id} has an invalid dialogue line.`);
        const item = variable('item', `${goalCode}_${dialogue.id}_line_${index + 1}`);
        const phraseIndex = Math.min(index, grammarVars.length - 1);
        add(`INSERT INTO content_item (section_id,content_type,grammar_point_id,title,content,translated_text,order_index) VALUES (${section},'LISTENING',${grammarVars[phraseIndex]},${quote(`${dialogue.title_vi} · ${index + 1}`)},${quote(line.ja)},${quote(line.vi)},${line.order ?? index + 1})\n` +
          `ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id),grammar_point_id=VALUES(grammar_point_id),title=VALUES(title),content=VALUES(content),translated_text=VALUES(translated_text);\nSET ${item}=LAST_INSERT_ID();`);
        add(`INSERT INTO dialogue_line_detail (content_item_id,dialogue_role_id,scene_code,bubble_side) VALUES (${item},${roleVars.get(line.speaker)},${quote(dialogue.id)},'${index % 2 ? 'phai' : 'trai'}')\n` +
          `ON DUPLICATE KEY UPDATE dialogue_role_id=VALUES(dialogue_role_id),scene_code=VALUES(scene_code),bubble_side=VALUES(bubble_side);`);
        add(`INSERT INTO content_audio (owner_type,owner_id,voice_code,audio_url) VALUES ('CONTENT_ITEM',${item},'jlas-tts-ja','tts:ja-JP')\n` +
          `ON DUPLICATE KEY UPDATE audio_url=VALUES(audio_url);`);
      }
      // J-LAS supplement (owner 2026-10-19): furigana per line + "Từ & cụm từ"; a content change resets review.
      const aid = goal.dialogueAids?.[dialogue.id];
      validateDialogueAid(dialogue, aid, `${goalCode}/${dialogue.id}`);
      const aidJson = JSON.stringify({ furigana: aid.furigana, phrases: aid.phrases,
        challenge: buildDialogueChallenge(dialogue, aid, `${goalCode}/${dialogue.id}`) });
      const aidSha = createHash('sha256').update(aidJson).digest('hex');
      add(`INSERT INTO learning_dialogue_aid (lesson_id,aid_json,content_sha256,source_code,review_status) VALUES (${lesson},${quote(aidJson)},'${aidSha}',${quote(`JLAS-COMM-${goalCode}`)},'NEEDS_REVIEW')\n` +
        `ON DUPLICATE KEY UPDATE review_status=IF(content_sha256=VALUES(content_sha256),review_status,VALUES(review_status)),aid_json=VALUES(aid_json),content_sha256=VALUES(content_sha256),source_code=VALUES(source_code);`);
      add(expected(section, lesson, Math.max(...dialogue.lines.map((line, index) => line.order ?? index + 1)),
        dialogue.key_phrases.length, speakers.length));
    };

    for (const group of groups) {
      createVocabularyLesson(group.code);
      group.dialogues.forEach(createDialogueLesson);
    }
    // Lessons still parked were removed from the canonical JSON: archive them at a unique far order (never re-parked).
    add(`UPDATE lesson SET status='ARCHIVED',order_index=2000000+id WHERE unit_id=${unit} AND order_index>=1000000 AND order_index<2000000;`);
  }
  out.push('CALL jlas_validate_comm_path_v1();', 'COMMIT;',
    'DROP PROCEDURE IF EXISTS jlas_validate_comm_path_v1;', 'DROP TEMPORARY TABLE IF EXISTS jlas_comm_expected;', END);
  return out.join('\n') + '\n';
}

// Checks the written state inside the transaction. Lists can grow or be re-ordered safely, but a SHORTER list would leave
// stale rows behind (content items, grammar points, roles), so that needs a reviewed migration instead of a reseed.
const VALIDATOR = `DELIMITER //\nDROP PROCEDURE IF EXISTS jlas_validate_comm_path_v1//\nCREATE PROCEDURE jlas_validate_comm_path_v1()\nBEGIN\n` +
  `  IF EXISTS (SELECT 1 FROM content_vocabulary cv JOIN vocabulary_entry v ON v.id=cv.vocabulary_id JOIN content_item ci ON ci.id=cv.content_item_id JOIN lesson_section ls ON ls.id=ci.section_id JOIN lesson l ON l.id=ls.lesson_id JOIN unit u ON u.id=l.unit_id JOIN textbook t ON t.id=u.textbook_id WHERE t.source_code LIKE 'LP-OWNED-COMM-%' AND v.dictionary_status='HIDDEN') THEN\n` +
  `    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Communication release blocked: a source vocabulary entry is hidden in the canonical dictionary.';\n  END IF;\n` +
  `  IF EXISTS (SELECT 1 FROM content_item ci JOIN jlas_comm_expected e ON e.section_id=ci.section_id WHERE ci.order_index>e.items) THEN\n` +
  `    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Communication release blocked: stale content items remain beyond the generated list; a shorter list needs a migration.';\n  END IF;\n` +
  `  IF EXISTS (SELECT 1 FROM grammar_point gp JOIN jlas_comm_expected e ON e.lesson_id=gp.lesson_id WHERE gp.order_index>e.grammar) THEN\n` +
  `    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Communication release blocked: stale grammar points remain beyond the generated list; a shorter list needs a migration.';\n  END IF;\n` +
  `  IF EXISTS (SELECT 1 FROM dialogue_role r JOIN jlas_comm_expected e ON e.lesson_id=r.lesson_id WHERE r.code NOT REGEXP '^ROLE-[0-9]+$' OR CAST(SUBSTRING(r.code,6) AS UNSIGNED)>e.roles) THEN\n` +
  `    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Communication release blocked: stale dialogue roles remain beyond the generated list; a shorter list needs a migration.';\n  END IF;\n` +
  `END//\nDELIMITER ;`;

export async function writeCommunicationPathSeed(root = ROOT, { check = false } = {}) {
  const canonical = await readLearningContent(root);
  const generated = renderCommunicationPathSeed(canonical);
  const seedPath = path.join(root, 'sql/learning/parts/learning_data_common.sql');
  const original = await fs.readFile(seedPath, 'utf8');
  const start = original.indexOf(BEGIN);
  const finish = start < 0 ? -1 : original.indexOf(END, start);
  const current = start < 0 || finish < 0 ? null : original.slice(start, finish + END.length) + '\n';
  // A Windows checkout (core.autocrlf) turns the seed's LF into CRLF; only the content counts.
  if (check) return current?.replace(/\r\n/g, '\n') === generated;
  const prefix = start < 0 ? original.trimEnd() + '\n\n' : original.slice(0, start).trimEnd() + '\n\n';
  const suffix = finish < 0 ? '' : original.slice(finish + END.length).trimStart();
  await fs.writeFile(seedPath, prefix + generated + (suffix ? `\n${suffix}` : ''), 'utf8');
  return true;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const check = process.argv.includes('--check');
  const ok = await writeCommunicationPathSeed(ROOT, { check });
  if (check && !ok) { console.error('learning_data_common.sql communication block is stale.'); process.exitCode = 1; }
  else console.log(check ? 'Communication path seed block is current.' : 'Generated communication path data in sql/learning/learning_data_common.sql.');
}
