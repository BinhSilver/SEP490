import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readLearningContent } from './learning_content_store.mjs';
import { renderCommunicationPathSeed } from './build_communication_path_seed.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const data = await readLearningContent(ROOT);
const sql = renderCommunicationPathSeed(data);
const sqlText = (value) => `CONVERT(X'${Buffer.from(String(value), 'utf8').toString('hex')}' USING utf8mb4)`;

test('generates all three survey-only communication catalogs from the owned source snapshots', () => {
  for (const goal of ['BA', 'TRAVEL', 'DAILY_LIFE']) {
    assert.ok(sql.includes(`VALUES (${sqlText(goal)},`), `${goal} goal is present`);
    assert.ok(sql.includes(sqlText(`JLAS-${goal}-MODE`)));
    assert.ok(sql.includes(sqlText(`JLAS-${goal}-TIME`)));
  }
  assert.equal((sql.match(/INSERT INTO learning_goal \(/g) || []).length, 3);
  assert.equal((sql.match(/INSERT INTO learning_survey_question \(/g) || []).length, 9);
});

test('publishes every source vocabulary group and dialogue situation with applied grammar and safe TTS', () => {
  const packs = data.sections.goal_source_snapshots;
  const goals = ['BA', 'TRAVEL', 'DAILY_LIFE'];
  const expectedLessons = goals.reduce((total, goal) => {
    const content = packs[goal].content;
    return total + Object.keys(content.meta.topics).length + content.dialogues.length;
  }, 0);
  const expectedPhrases = goals.reduce((total, goal) => total + packs[goal].content.dialogues
    .reduce((count, dialogue) => count + dialogue.key_phrases.length, 0), 0);
  const expectedLines = goals.reduce((total, goal) => total + packs[goal].content.dialogues
    .reduce((count, dialogue) => count + dialogue.lines.length, 0), 0);
  assert.equal((sql.match(/INSERT INTO lesson \(/g) || []).length, expectedLessons);
  assert.equal((sql.match(/INSERT INTO grammar_point \(/g) || []).length, expectedPhrases);
  assert.equal((sql.match(/INSERT INTO dialogue_line_detail \(/g) || []).length, expectedLines);
  assert.equal((sql.match(/INSERT INTO content_audio \(/g) || []).length, expectedLines);
  assert.ok(sql.includes("'tts:ja-JP'"));
  assert.ok(sql.includes(sqlText('LP-OWNED-COMM-TRAVEL')));
  assert.ok(sql.includes('-- Attribution: JMdict / EDRDG, CC BY-SA 4.0 https://www.edrdg.org/edrdg/licence.html'));
});

test('keeps communication seed out of scored quiz and JLPT question tables', () => {
  assert.doesNotMatch(sql, /INSERT INTO (learning_quiz_session|assessment_question|assessment_answer)/);
  assert.ok(sql.includes(sqlText('SURVEY_ONLY')));
  assert.ok(sql.includes("v.dictionary_status='HIDDEN'"));
  assert.match(sql, /SIGNAL SQLSTATE '45000'/);
});

test('uses SQL variables as references instead of string-quoting them', () => {
  assert.match(sql, /VALUES \(@goal_ba,/);
  assert.match(sql, /VALUES \(@question_ba_jlas_ba_focus,/);
  assert.doesNotMatch(sql, /CONVERT\(X'40676f616c/);
});

test('survey focus options show the learner-facing Vietnamese topic name, never a raw topic code', () => {
  const packs = data.sections.goal_source_snapshots;
  for (const [goal, config] of Object.entries(data.sections.communication_path_release.goals)) {
    const topics = packs[goal].content.meta.topics;
    for (const question of config.survey) {
      for (const option of question.options.filter((item) => item.topicCode)) {
        assert.doesNotMatch(option.label, /^Chủ đề [A-Z_]+$/, `${option.code} still shows its code`);
        assert.equal(option.label, topics[option.topicCode].vi, `${option.code} label`);
        assert.ok(sql.includes(sqlText(topics[option.topicCode].vi)), `${option.code} seeded label`);
      }
    }
  }
});

test('each dialogue situation is grouped under its approved topic with its level, ordered easy to hard', () => {
  const at = (code) => sql.indexOf(sqlText(code));
  // Travel: topic vocabulary first, then its dialogues; TROUBLE lists the N4 dialogue before the two N3 ones.
  assert.ok(at('COMM-TRAVEL-V-AIR') < at('COMM-TRAVEL-D-TRV-01'));
  assert.ok(at('COMM-TRAVEL-D-TRV-01') < at('COMM-TRAVEL-V-TRAIN'));
  assert.ok(at('COMM-TRAVEL-V-TROUBLE') < at('COMM-TRAVEL-D-TRV-06'));
  assert.ok(at('COMM-TRAVEL-D-TRV-06') < at('COMM-TRAVEL-D-TRV-07'));
  // BrSE PM: the N3 meeting-scheduling dialogue (BRSE-08) comes before the N2 ones of the same topic.
  assert.ok(at('COMM-BA-D-BRSE-08') < at('COMM-BA-D-BRSE-04'));
  for (const [goal, config] of Object.entries(data.sections.communication_path_release.goals)) {
    for (const dialogue of data.sections.goal_source_snapshots[goal].content.dialogues) {
      const parent = config.dialogueTopics[dialogue.id];
      assert.ok(parent, `${dialogue.id} has an approved topic`);
      const row = sql.split('\n').find((line) => line.startsWith('INSERT INTO learning_topic') && line.includes(sqlText(`SITUATION-${dialogue.id}`)));
      assert.ok(row, `${dialogue.id} topic row`);
      assert.match(row, /parent_topic_id/);
      assert.ok(row.includes(`@topic_${goal.toLowerCase()}_${parent.toLowerCase()}`), `${dialogue.id} parent ${parent}: ${row.slice(0, 200)}`);
      assert.ok(row.includes(sqlText(dialogue.level)), `${dialogue.id} level ${dialogue.level}`);
    }
  }
});

test('lesson upserts never collide on order: each unit parks its lesson order before re-ordering', () => {
  // Regression 2026-10-19: lesson has UNIQUE (unit_id, source_code) and UNIQUE (unit_id, order_index). Re-ordering
  // lessons made ON DUPLICATE KEY UPDATE hit the row holding the target order and overwrite another lesson.
  for (const goal of ['BA', 'TRAVEL', 'DAILY_LIFE']) {
    const unitVar = `@unit_${goal.toLowerCase()}`;
    const park = sql.indexOf(`UPDATE lesson SET order_index=order_index+1000000 WHERE unit_id=${unitVar} AND order_index<1000000;`);
    const firstLesson = sql.indexOf(`INSERT INTO lesson (unit_id,`, sql.indexOf(`SET ${unitVar}=LAST_INSERT_ID();`));
    assert.ok(park > 0, `${goal} parks lesson order`);
    assert.ok(park < firstLesson, `${goal} parks before the first lesson upsert`);
  }
});

test('rubyReading reads a verbatim excerpt from line furigana and refuses to split a ruby group', async () => {
  const { rubyReading, stripRuby } = await import('./build_communication_path_seed.mjs');
  const markup = 'お{願|ねが}いします。{預|あず}ける{荷物|にもつ}は{一|ひと}つです。';
  assert.equal(stripRuby(markup), 'お願いします。預ける荷物は一つです。');
  assert.equal(rubyReading(markup, '預ける荷物'), 'あずけるにもつ');
  assert.equal(rubyReading(markup, '荷'), null);
  assert.equal(rubyReading(markup, '見つからない'), null);
});

test('every communication dialogue line carries reviewed furigana and 5–8 Từ & cụm từ entries (J-LAS supplement)', async () => {
  const { stripRuby, rubyReading } = await import('./build_communication_path_seed.mjs');
  const release = data.sections.communication_path_release;
  assert.equal(release.dialogueAidsSource?.reviewStatus, 'NEEDS_REVIEW');
  assert.match(release.dialogueAidsSource?.label || '', /bổ sung J-LAS/);
  const hira = (text) => text.replace(/[ァ-ヶ]/g, (c) => String.fromCharCode(c.charCodeAt(0) - 0x60));
  let dialogues = 0;
  for (const [goal, config] of Object.entries(release.goals)) {
    for (const dialogue of data.sections.goal_source_snapshots[goal].content.dialogues) {
      const aid = config.dialogueAids?.[dialogue.id];
      assert.ok(aid, `${dialogue.id} has dialogue aids`);
      assert.equal(aid.furigana.length, dialogue.lines.length, `${dialogue.id} furigana per line`);
      dialogue.lines.forEach((line, index) => assert.equal(stripRuby(aid.furigana[index]), line.ja, `${dialogue.id}:${line.order}`));
      assert.ok(aid.phrases.length >= 5 && aid.phrases.length <= 8, `${dialogue.id} has 5–8 phrases`);
      for (const phrase of aid.phrases) {
        const index = dialogue.lines.findIndex((line) => line.order === phrase.line);
        assert.ok(index >= 0 && dialogue.lines[index].ja.includes(phrase.ja), `${dialogue.id} ${phrase.ja} is in line ${phrase.line}`);
        assert.equal(hira(phrase.reading), hira(rubyReading(aid.furigana[index], phrase.ja) || ''), `${dialogue.id} ${phrase.ja} reading`);
        assert.ok(phrase.vi.trim(), `${dialogue.id} ${phrase.ja} meaning`);
      }
      dialogues++;
    }
  }
  assert.equal(dialogues, 30);
  assert.equal((sql.match(/INSERT INTO learning_dialogue_aid \(/g) || []).length, 30);
  assert.ok(sql.includes("'NEEDS_REVIEW'"));
});

// ---- 2026-10-19 adversarial review: four generator hazards that could corrupt or silently keep stale data ----

const goalBlocks = () => {
  const blocks = {};
  const marks = ['BA', 'TRAVEL', 'DAILY_LIFE'].map((goal) => ({ goal, at: sql.indexOf(`\n-- ${goal}: owned source`) }));
  marks.forEach((mark, i) => { blocks[mark.goal] = sql.slice(mark.at, i + 1 < marks.length ? marks[i + 1].at : sql.indexOf('COMMIT;')); });
  return blocks;
};

test('hazard 1: survey questions and options park their order first and retire leftovers, so a re-order or removal cannot collide', () => {
  for (const [goal, block] of Object.entries(goalBlocks())) {
    const g = `@goal_${goal.toLowerCase()}`;
    const parkQ = block.indexOf(`UPDATE learning_survey_question SET question_order=question_order+1000,active=FALSE WHERE goal_id=${g} AND question_order<1000;`);
    const parkO = block.indexOf(`UPDATE learning_survey_option o JOIN learning_survey_question q ON q.id=o.question_id SET o.option_order=o.option_order+1000,o.review_status='NEEDS_REVIEW' WHERE q.goal_id=${g} AND o.option_order<1000;`);
    const firstQ = block.indexOf('INSERT INTO learning_survey_question (');
    const lastO = block.lastIndexOf('INSERT INTO learning_survey_option (');
    const retireQ = block.indexOf(`UPDATE learning_survey_question SET question_order=100000+id WHERE goal_id=${g} AND question_order>=1000 AND question_order<100000;`);
    const retireO = block.indexOf(`UPDATE learning_survey_option o JOIN learning_survey_question q ON q.id=o.question_id SET o.option_order=100000+o.id WHERE q.goal_id=${g} AND o.option_order>=1000 AND o.option_order<100000;`);
    assert.ok(parkQ > 0 && parkO > 0 && parkQ < firstQ && parkO < firstQ, `${goal} parks before the survey upserts`);
    assert.ok(retireQ > lastO && retireO > lastO, `${goal} retires leftovers after the survey upserts`);
  }
});

test('hazard 2: every vocabulary item prunes its other links, and the validator blocks rows a shrunk list would leave behind', () => {
  const prunes = sql.match(/^DELETE cv FROM content_vocabulary cv WHERE cv\.content_item_id=@item_[a-z0-9_]+ AND cv\.vocabulary_id<>@vocab_[a-z0-9_]+;$/gm) || [];
  const links = sql.match(/^INSERT INTO content_vocabulary \(/gm) || [];
  assert.equal(prunes.length, links.length);
  assert.equal((sql.match(/^INSERT INTO jlas_comm_expected \(/gm) || []).length, 50, 'one expected-shape row per lesson');
  for (const message of ['stale content items', 'stale grammar points', 'stale dialogue roles']) {
    assert.ok(sql.includes(`Communication release blocked: ${message}`), message);
  }
});

test('hazard 3: the validator is created before the transaction and called inside it, so its SIGNAL rolls the whole block back', () => {
  const create = sql.indexOf('CREATE PROCEDURE jlas_validate_comm_path_v1()');
  const start = sql.indexOf('START TRANSACTION;');
  const call = sql.indexOf('CALL jlas_validate_comm_path_v1();');
  const commit = sql.indexOf('COMMIT;');
  const drop = sql.indexOf('DROP PROCEDURE IF EXISTS jlas_validate_comm_path_v1;', commit);
  assert.ok(create > 0 && create < start && start < call && call < commit && commit < drop, `${create} ${start} ${call} ${commit} ${drop}`);
  const inside = sql.slice(start, commit);
  assert.doesNotMatch(inside, /\b(?:CREATE|DROP|ALTER)\s+(?:PROCEDURE|TABLE|FUNCTION|TRIGGER)\b/, 'no implicit-commit DDL inside the transaction');
});

test('hazard 4: lessons no longer generated are archived at a unique far order after the upserts', () => {
  for (const [goal, block] of Object.entries(goalBlocks())) {
    const retire = block.indexOf(`UPDATE lesson SET status='ARCHIVED',order_index=2000000+id WHERE unit_id=@unit_${goal.toLowerCase()} AND order_index>=1000000 AND order_index<2000000;`);
    assert.ok(retire > block.lastIndexOf('INSERT INTO lesson ('), `${goal} retires leftover parked lessons after its lesson upserts`);
  }
});

test('hazard 1 (rehearsal finding): a survey question upsert also updates its order, so a parked question gets its new position back', () => {
  const rows = sql.split('\n').filter((line) => line.startsWith('ON DUPLICATE KEY UPDATE') && sql.includes('INSERT INTO learning_survey_question'));
  const questionUpserts = sql.split('INSERT INTO learning_survey_question (').slice(1).map((chunk) => chunk.split(';')[0]);
  assert.equal(questionUpserts.length, 9);
  for (const upsertSql of questionUpserts) assert.match(upsertSql, /question_order=VALUES\(question_order\)/);
  assert.ok(rows.length > 0);
});

test('every communication dialogue ships a choose-the-right-reply challenge covering each learner turn (J-LAS supplement)', async () => {
  const { stripRuby } = await import('./build_communication_path_seed.mjs');
  let turns = 0;
  for (const [goal, config] of Object.entries(data.sections.communication_path_release.goals)) {
    for (const dialogue of data.sections.goal_source_snapshots[goal].content.dialogues) {
      const challenge = config.dialogueAids[dialogue.id].challenge;
      assert.ok(challenge, `${dialogue.id} has a challenge`);
      const learnerLines = dialogue.lines.filter((line) => line.speaker === challenge.learnerSpeaker).map((line) => line.order);
      assert.deepEqual(challenge.turns.map((turn) => turn.line), learnerLines, `${dialogue.id} covers every learner turn`);
      for (const turn of challenge.turns) {
        const real = dialogue.lines.find((line) => line.order === turn.line).ja;
        assert.equal(turn.distractors.length, 2);
        assert.equal(new Set([real, ...turn.distractors.map((d) => d.ja)]).size, 3, `${dialogue.id}:${turn.line} three distinct options`);
        for (const d of turn.distractors) {
          assert.equal(stripRuby(d.furigana), d.ja, `${dialogue.id}:${turn.line} furigana`);
          assert.ok(d.vi.trim());
        }
        assert.ok(turn.why_vi.trim());
        turns++;
      }
    }
  }
  assert.equal(turns, 130);
  const aidRows = sql.split('\n').filter((line) => line.startsWith('INSERT INTO learning_dialogue_aid'));
  assert.ok(aidRows.every((row) => row.includes(Buffer.from('"challenge"', 'utf8').toString('hex').toUpperCase()) || row.includes(Buffer.from('"challenge"', 'utf8').toString('hex'))),
    'the challenge travels inside the aid row');
});
