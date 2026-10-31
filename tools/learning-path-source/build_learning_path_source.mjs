import { createHash } from 'node:crypto';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const OUTPUT = path.join(ROOT, 'data/learning-path');
const LIMIT = 20 * 1024 * 1024;
const LEVELS = ['N5', 'N4', 'N3', 'N2', 'N1'];
const GOALS = ['BA', 'TRAVEL', 'DAILY_LIFE'];
const EXPECTED_LEVELS = {
  N5: [1465, 311, 314, 62, 119, 100, 400],
  N4: [2016, 247, 434, 79, 142, 104, 400],
  N3: [5582, 194, 720, 54, 129, 54, 137],
  N2: [5058, 310, 821, 0, 0, 0, 0],
  N1: [4157, 1089, 483, 0, 0, 0, 0]
};
const EXPECTED_GOALS = { BA: [7, 140, 210, 14], TRAVEL: [6, 94, 0, 8], DAILY_LIFE: [7, 100, 0, 8] };

const sha = value => createHash('sha256').update(value).digest('hex');
const readJson = async relative => JSON.parse(await readFile(path.join(ROOT, relative), 'utf8'));
const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
const json = value => JSON.stringify(stable(value), null, 1) + '\n';
const count = (condition, message) => { if (!condition) throw new Error(message); };
export function normalizeJapanese(value) {
  return String(value ?? '').normalize('NFKC').replace(/[\u30A1-\u30F6]/g, c => String.fromCharCode(c.charCodeAt(0) - 0x60));
}

function sourceDescriptor(name, content, sectionSha) {
  return { name, sha256: sectionSha ?? sha(JSON.stringify(content)) };
}
function arrayCounts(value) {
  if (Array.isArray(value)) return { items: value.length };
  if (!value || typeof value !== 'object') return {};
  return Object.fromEntries(Object.entries(value).filter(([, v]) => Array.isArray(v)).map(([k, v]) => [k, v.length]));
}
function cloneWithFields(item, extra) { return { ...item, ...extra }; }

function buildMinnaReading(exercises) {
  const groups = new Map();
  for (const exercise of exercises) {
    const group = exercise.source_code.replace(/-Q\d+$/, '');
    if (!groups.has(group)) groups.set(group, []);
    groups.get(group).push(exercise);
  }
  return groups;
}
function buildMinnaListening(lessons) {
  const clips = new Map();
  for (const lesson of lessons) for (const clip of lesson.cau ?? []) {
    const group = `LP-OWNED-MINNA-${lesson.cap}-L${String(lesson.bai).padStart(2, '0')}-LISTEN-${clip.ma}`;
    count(!clips.has(group), `Duplicate Minna clip ${group}`);
    clips.set(group, { lesson: { ...lesson, cau: undefined }, clip });
  }
  return clips;
}
function buildAudioPaths(manifest) {
  const map = new Map();
  for (const [group, data] of Object.entries(manifest.groups)) for (const entry of data.entries) {
    for (const audioPath of entry.paths) {
      count(!map.has(audioPath), `Duplicate audio path ${audioPath}`);
      map.set(audioPath, { group, clip: entry.clip, paths: entry.paths, sha256: entry.sha256, bytes: entry.bytes, status: entry.status });
    }
  }
  return map;
}
function readingContent(item, minna, speed54, speed3) {
  const group = item.source_group;
  if (minna.has(group)) return { source_section: 'jlpt_minna_n5_n4_reading', exercises: minna.get(group) };
  if (speed54.has(group)) return { source_section: 'jlpt_speed_n5_n4_reading', exercise: speed54.get(group) };
  if (speed3.has(group)) return { source_section: 'jlpt_speed_n3_comprehension', exercise: speed3.get(group) };
  throw new Error(`Missing reading content for ${group}`);
}
function listeningContent(item, minna, speed3, audioPaths) {
  const group = item.source_group;
  if (minna.has(group)) {
    const content = minna.get(group);
    const audioPath = `minna/${content.clip.audio_file.replace(/^audio\//, '')}`;
    const audio = audioPaths.get(audioPath);
    count(audio, `Missing Minna audio hash for ${group}: ${audioPath}`);
    return { source_section: 'jlpt_minna_n5_n4_listening', ...content, audio };
  }
  if (speed3.has(group)) {
    const exercise = speed3.get(group);
    const audio = audioPaths.get(exercise.audio_path);
    count(audio, `Missing Speed audio hash for ${group}: ${exercise.audio_path}`);
    return { source_section: 'jlpt_speed_n3_comprehension', exercise, audio };
  }
  throw new Error(`Missing listening content for ${group}`);
}
function dialoguesFor(level, sections) {
  if (level === 'N3') return sections.grammar_n3_n1.N3.content.dialogues.map((dialogue, index) => ({
    ...dialogue, id: dialogue.code, source_section: 'grammar_n3_n1.N3.content.dialogues', source_index: index
  }));
  if (level === 'N2' || level === 'N1') return [];
  const lessons = sections.jlpt_sekai_n5_n4.lessons.filter(lesson => lesson.level === level);
  return lessons.flatMap(lesson => (lesson.content.hoiThoai?.canh ?? []).map(scene => ({
    id: `${lesson.owned_lesson_code}-${scene.ma}`,
    chapter: level === 'N4' ? lesson.source_lesson_number - 25 : lesson.source_lesson_number,
    source_lesson_code: lesson.owned_lesson_code,
    source_file: lesson.source_file,
    source_sha256: lesson.source_sha256,
    review_status: lesson.review_status,
    scene,
    lines: scene.loi
  })));
}
function sectionSources(level, sections, sectionSha, resourceHashes) {
  const keys = [`jlas_compiled_book_${level.toLowerCase()}`, 'vocabulary_n5_n1', 'kanji_n5_n1', 'jlas_unit_labels'];
  if (['N5', 'N4', 'N3'].includes(level)) keys.push('jlas_grammar_example_rewrites');
  if (['N5', 'N4'].includes(level)) keys.push('jlpt_sekai_n5_n4', 'jlpt_minna_n5_n4_reading', 'jlpt_minna_n5_n4_listening', 'jlpt_speed_n5_n4_reading');
  if (level === 'N3') keys.push('grammar_n3_n1', 'jlpt_speed_n3_comprehension', 'jlas_n3_grammar_application', 'jlas_n3_vocab_examples', 'jlas_store_pool');
  if (level === 'N4') keys.push('jlas_store_pool', 'jlpt_n4_kanji', 'jlas_n3_vocab_examples');
  if (['N2', 'N1'].includes(level)) keys.push('grammar_n3_n1');
  const sourceSections = keys.map(key => sourceDescriptor(`learning-content.json#sections.${key}`, sections[key], sectionSha[key]));
  const resourceNames = ['compiled-vocabulary-tiers', 'kanji-meaning-vi', 'word-type-reviewed'];
  if (['N5', 'N4'].includes(level)) resourceNames.push('grammar-application-reviewed');
  if (level === 'N3') resourceNames.push('grammar-application-supplement');
  return [...sourceSections, ...resourceNames.map(name => ({
    name: `src/main/resources/learning/${name}.json`, sha256: resourceHashes[name]
  }))];
}
function buildJlpt(level, sections, sectionSha, resources, resourceHashes, audioPaths) {
  const book = sections[`jlas_compiled_book_${level.toLowerCase()}`];
  const labels = sections.jlas_unit_labels.levels[level] ?? {};
  const vocabExamples = sections.jlas_n3_vocab_examples.items;
  const applications = level === 'N3' ? sections.jlas_n3_grammar_application.items : [];
  const byGrammarId = new Map();
  for (const item of applications) {
    if (!byGrammarId.has(item.grammar_id)) byGrammarId.set(item.grammar_id, []);
    byGrammarId.get(item.grammar_id).push(item);
  }
  const reviewedByPattern = new Map();
  const reviewedQuestions = resources['grammar-application-reviewed'].items.filter(item => item.level === level);
  for (const item of reviewedQuestions) {
    if (!reviewedByPattern.has(item.pattern)) reviewedByPattern.set(item.pattern, []);
    reviewedByPattern.get(item.pattern).push(item);
  }
  const minnaReading = buildMinnaReading(sections.jlpt_minna_n5_n4_reading.exercises);
  const minnaListening = buildMinnaListening(sections.jlpt_minna_n5_n4_listening.lessons);
  const speed54 = new Map(sections.jlpt_speed_n5_n4_reading.exercises.map(x => [x.source_group, x]));
  const speed3 = new Map(sections.jlpt_speed_n3_comprehension.exercises.map(x => [x.source_group, x]));
  const vocabulary = book.vocabulary.map(item => cloneWithFields(item, {
    normalized_expression: normalizeJapanese(item.expression),
    normalized_reading: normalizeJapanese(item.reading || item.expression),
    labels: labels.vocabulary?.[item.id] ?? null,
    reviewed_word_type: resources['word-type-reviewed'].expressions[item.expression] ?? null,
    jlas_example: vocabExamples[item.id] ?? null
  }));
  const kanji = book.kanji.map(item => cloneWithFields(item, {
    labels: labels.kanji?.[item.id] ?? null,
    reviewed_meaning_vi: resources['kanji-meaning-vi'].kanji[item.character] ?? null
  }));
  const grammar = book.grammar.map(item => cloneWithFields(item, {
    labels: labels.grammar?.[item.id] ?? null,
    application_questions: byGrammarId.get(item.id) ?? [],
    reviewed_application_questions: reviewedByPattern.get(item.pattern) ?? []
  }));
  const reading = book.reading.map(item => cloneWithFields(item, {
    content: readingContent(item, minnaReading, speed54, speed3)
  }));
  const listening = book.listening.map(item => cloneWithFields(item, {
    content: listeningContent(item, minnaListening, speed3, audioPaths)
  }));
  const dialogues = dialoguesFor(level, sections);
  const extra = sections.jlas_store_pool.passages.filter(x => x.level === level);
  const result = {
    level,
    status: ['N5', 'N4', 'N3'].includes(level) ? 'RELEASED' : 'NOT_RELEASED',
    code: book.code,
    title: book.title,
    original_status: book.status,
    summary: book.summary,
    sources: sectionSources(level, sections, sectionSha, resourceHashes),
    chapters: book.chapters,
    vocabulary,
    kanji,
    grammar,
    reading,
    listening,
    dialogues,
    extra_practice_pool: extra,
    reviewed_application_questions: reviewedQuestions,
    unlinked_reviewed_application_questions: reviewedQuestions.filter(item => !grammar.some(point => point.pattern === item.pattern)),
    ...(level === 'N3' ? { jlas_n3_vocab_examples: vocabExamples } : {}),
    gaps: []
  };
  const actual = [vocabulary.length, kanji.length, grammar.length, reading.length,
    reading.reduce((sum, item) => sum + item.questions, 0), listening.length,
    dialogues.reduce((sum, dialogue) => sum + (dialogue.lines?.length ?? 0), 0)];
  count(JSON.stringify(actual) === JSON.stringify(EXPECTED_LEVELS[level]), `${level} counts ${actual} != ${EXPECTED_LEVELS[level]}`);
  return { result, counts: {
    vocabulary: actual[0], kanji: actual[1], grammar: actual[2], reading: actual[3], reading_questions: actual[4],
    listening: actual[5], dialogue_lines: actual[6], extra_practice_pool: extra.length,
    extra_practice_questions: extra.reduce((sum, passage) => sum + passage.questions.length, 0),
    jlas_grammar_application: grammar.reduce((sum, item) => sum + item.application_questions.length, 0),
    jlas_vocab_examples: level === 'N3' ? Object.keys(vocabExamples).length : vocabulary.filter(item => item.jlas_example).length,
    reviewed_application_questions: reviewedQuestions.length,
    unlinked_reviewed_application_questions: result.unlinked_reviewed_application_questions.length
  } };
}
function buildCommunication(goal, sections, sectionSha) {
  const snapshot = sections.goal_source_snapshots[goal];
  const release = sections.communication_path_release;
  const policy = release.goals[goal];
  const content = snapshot.content;
  const topics = Object.entries(content.meta.topics).map(([code, names]) => {
    const levels = [...new Set(content.dialogues.filter(d => policy.dialogueTopics[d.id] === code).map(d => d.level))]
      .sort((a, b) => LEVELS.indexOf(a) - LEVELS.indexOf(b));
    return { code, name_ja: names.ja, name_vi: names.vi, level: levels[0] ?? null, levels };
  });
  const vocabulary = content.vocabulary.map(item => ({ ...item,
    normalized_expression: normalizeJapanese(item.word), normalized_reading: normalizeJapanese(item.reading || item.word)
  }));
  const dialogues = content.dialogues.map(item => ({ ...item, topic_code: policy.dialogueTopics[item.id] ?? null,
    dialogue_aids: policy.dialogueAids[item.id] ?? null
  }));
  const phrases = content.dialogues.flatMap(dialogue => dialogue.key_phrases.map((phrase, index) => ({
    id: `${dialogue.id}-P${String(index + 1).padStart(2, '0')}`, dialogue_id: dialogue.id, ...phrase
  })));
  const practice = Object.entries(policy.dialogueAids).filter(([, aids]) => aids.challenge).map(([dialogue_id, aids]) => ({ dialogue_id, challenge: aids.challenge }));
  const result = {
    goal, display_name_vi: policy.displayNameVi, survey: policy.survey, audio: release.audio,
    characters: content.meta.characters, topics, vocabulary, kanji: content.kanji,
    phrases, dialogues, practice, attribution: { ...release.attribution, source_license: content.meta.sources_and_license },
    source_meta: content.meta, policy: { ...policy, dialogueAids: undefined },
    sources: [
      { name: snapshot.source_path, sha256: snapshot.source_sha256, owned_root_code: snapshot.owned_root_code },
      sourceDescriptor('learning-content.json#sections.goal_source_snapshots', sections.goal_source_snapshots, sectionSha.goal_source_snapshots),
      sourceDescriptor('learning-content.json#sections.communication_path_release', release, sectionSha.communication_path_release)
    ],
    gaps: []
  };
  const actual = [topics.length, vocabulary.length, content.kanji.length, dialogues.length];
  count(JSON.stringify(actual) === JSON.stringify(EXPECTED_GOALS[goal]), `${goal} counts ${actual} != ${EXPECTED_GOALS[goal]}`);
  return { result, counts: { topics: actual[0], vocabulary: actual[1], kanji: actual[2], dialogues: actual[3], phrases: phrases.length } };
}

function pushGap(target, { code, skill, chapter = null, topic = null, count: amount, ids = [], severity, proposal }) {
  if (!amount) return;
  target.push({ code, skill, chapter, topic, count: amount, ids: ids.slice(0, 50), ids_total: ids.length, severity, proposal });
}
function byChapter(items, chapterOf = item => item.chapter) {
  const groups = new Map();
  for (const item of items) {
    const chapter = chapterOf(item);
    const key = chapter == null ? 'null' : String(chapter);
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(item);
  }
  return [...groups.entries()].sort(([a], [b]) => Number(a) - Number(b));
}
function missingExamples(data) {
  return data.vocabulary.filter(item => !item.example_ja?.trim() && !item.jlas_example?.example_ja?.trim());
}
function buildGapAnalysis(outputs, sections) {
  const summary = { jlpt: {}, communication: {}, sparseChapters: [], n3ExcludedVocab: sections.jlas_n3_vocab_examples.excluded,
    n3ExcludedGrammar: sections.jlas_n3_grammar_application.excluded };
  for (const level of LEVELS) {
    const data = outputs[`jlpt/${level}.json`].result;
    const gaps = [];
    const vocabChapter = new Map(data.chapters.flatMap(chapter => chapter.vocabulary.map(id => [id, chapter.number])));
    const kanjiChapter = new Map(data.chapters.flatMap(chapter => chapter.kanji.map(id => [id, chapter.number])));
    const grammarChapter = new Map(data.chapters.flatMap(chapter => chapter.grammar.map(id => [id, chapter.number])));
    const lineCount = data.dialogues.reduce((sum, dialogue) => sum + (dialogue.lines?.length ?? 0), 0);
    const chapterSkillCounts = {
      READING: data.chapters.map(chapter => ({ chapter: chapter.number, count: chapter.reading.length, ids: chapter.reading })),
      LISTENING: data.chapters.map(chapter => ({ chapter: chapter.number, count: chapter.listening.length, ids: chapter.listening })),
      DIALOGUE: level === 'N3' ? [] : data.chapters.map(chapter => {
        const dialogues = data.dialogues.filter(item => item.chapter === chapter.number);
        return { chapter: chapter.number, count: dialogues.reduce((sum, item) => sum + item.lines.length, 0), ids: dialogues.map(item => item.id) };
      })
    };
    if (level === 'N2' || level === 'N1') {
      const proposals = level === 'N2'
        ? { READING: 95, READING_QUESTIONS: 171, LISTENING: 125, DIALOGUE_LINES: 480 }
        : { READING: 164, READING_QUESTIONS: 295, LISTENING: 216, DIALOGUE_LINES: 832 };
      for (const [skill, amount, proposal] of [
        ['READING', proposals.READING, `Soạn khoảng ${proposals.READING} bài đọc (ước tính theo trung bình N4: 79/25 chương).`],
        ['READING_QUESTIONS', proposals.READING_QUESTIONS, `Soạn khoảng ${proposals.READING_QUESTIONS} câu hỏi đọc hiểu (theo tỷ lệ N4 142/79).`],
        ['LISTENING', proposals.LISTENING, `Soạn khoảng ${proposals.LISTENING} clip nghe; audio cần kế hoạch riêng.`],
        ['DIALOGUE', proposals.DIALOGUE_LINES, `Soạn khoảng ${proposals.DIALOGUE_LINES} dòng hội thoại (mốc 16 dòng/chương ở N5/N4).`]
      ]) pushGap(gaps, { code: `LEVEL_SKILL_ABSENT_${skill}`, skill, count: amount, ids: [], severity: 'BLOCKER', proposal });
    }
    if (level === 'N3' && lineCount < 400) pushGap(gaps, {
      code: 'DIALOGUE_BELOW_N5_N4_BASELINE', skill: 'DIALOGUE', count: 400 - lineCount,
      ids: data.dialogues.map(item => item.id), severity: 'IMPORTANT',
      proposal: `Bổ sung ${400 - lineCount} dòng để đạt mốc 400 như N5/N4; tương đương khoảng ${Math.ceil((400 - lineCount) / 11)} hội thoại 8–14 dòng.`
    });
    for (const [skill, chapters] of Object.entries(chapterSkillCounts)) {
      if (skill === 'DIALOGUE' && level === 'N3') continue;
      const mean = chapters.length ? chapters.reduce((sum, item) => sum + item.count, 0) / chapters.length : 0;
      const threshold = mean / 2;
      for (const item of chapters) {
        if ((level !== 'N2' && level !== 'N1') && (item.count === 0 || (mean > 0 && item.count < threshold))) {
          const amount = Math.max(1, Math.ceil(mean - item.count));
          pushGap(gaps, { code: item.count === 0 ? `CHAPTER_SKILL_EMPTY_${skill}` : `CHAPTER_SKILL_SPARSE_${skill}`,
            skill, chapter: item.chapter, count: amount, ids: item.ids, severity: level === 'N2' || level === 'N1' ? 'BLOCKER' : 'IMPORTANT',
            proposal: `Bổ sung khoảng ${amount} mục ${skill} để tiến gần trung bình ${mean.toFixed(1)} mục/chương.` });
          summary.sparseChapters.push({ level, chapter: item.chapter, skill, current: item.count, mean: Number(mean.toFixed(1)) });
        }
      }
    }

    const noExamples = missingExamples(data);
    const tierGroups = new Map();
    for (const item of noExamples) {
      const tier = item.labels?.tier ?? 'UNLABELED';
      const key = `${item.labels?.chapter ?? vocabChapter.get(item.id) ?? 'null'}|${tier}`;
      if (!tierGroups.has(key)) tierGroups.set(key, []);
      tierGroups.get(key).push(item);
    }
    for (const [key, items] of [...tierGroups].sort(([a], [b]) => a.localeCompare(b, 'en', { numeric: true }))) {
      const [chapterText, tier] = key.split('|');
      const excludedIds = new Set(sections.jlas_n3_vocab_examples.excluded.map(item => item.id));
      const allExcluded = level === 'N3' && items.every(item => excludedIds.has(item.id));
      pushGap(gaps, { code: allExcluded ? 'N3_EXCLUDED_VOCAB_EXAMPLES' : `VOCAB_EXAMPLE_MISSING_${tier}`,
        skill: 'VOCABULARY_EXAMPLE', chapter: chapterText === 'null' ? null : Number(chapterText),
        count: items.length, ids: items.map(item => item.id), severity: ['N2','N1'].includes(level) ? 'BLOCKER' : 'IMPORTANT',
        proposal: `Soạn ${items.length} câu ví dụ cho ${tier} (${items.length} mục)${tier === 'UNLABELED' ? '; cần phân tier sau khi duyệt' : ''}.` });
    }

    const applicationCount = item => level === 'N3' ? item.application_questions.length : item.reviewed_application_questions.length;
    const missingGrammar = data.grammar.filter(item => applicationCount(item) === 0);
    for (const [chapter, items] of byChapter(missingGrammar, item => item.chapter ?? item.labels?.chapter ?? grammarChapter.get(item.id))) pushGap(gaps, {
      code: 'GRAMMAR_WITHOUT_APPLICATION', skill: 'GRAMMAR_APPLICATION', chapter: Number(chapter), count: items.length,
      ids: items.map(item => item.id), severity: level === 'N2' || level === 'N1' ? 'BLOCKER' : 'IMPORTANT',
      proposal: `Soạn ít nhất ${items.length} câu áp dụng cho mẫu chưa có câu (mỗi mẫu ít nhất 1 câu).`
    });
    if (level === 'N3') {
      const excluded = sections.jlas_n3_grammar_application.excluded;
      for (const [chapter, items] of byChapter(excluded, item => Number(item.id.split('-')[3]?.slice(1)))) pushGap(gaps, {
        code: 'N3_REJECTED_GRAMMAR_APPLICATION', skill: 'GRAMMAR_APPLICATION', chapter: Number(chapter),
        count: items.length, ids: items.map(item => item.id), severity: 'IMPORTANT',
        proposal: `Viết lại ${items.length} câu bị loại theo lý do nguồn; kiểm tra giao với pattern chưa có câu để tránh đếm đôi.`
      });
    }

    const missingMeaning = data.kanji.filter(item => !item.meanings_vi?.length);
    for (const [chapter, items] of byChapter(missingMeaning, item => item.labels?.chapter ?? kanjiChapter.get(item.id))) pushGap(gaps, {
      code: 'KANJI_MISSING_REVIEWED_MEANING', skill: 'KANJI_MEANING', chapter: chapter === 'null' ? null : Number(chapter),
      count: items.length, ids: items.map(item => item.id), severity: ['N2','N1'].includes(level) ? 'BLOCKER' : 'IMPORTANT',
      proposal: `Gắn nghĩa Việt đã rà vào ${items.length} mục Kanji nếu khớp; nếu nguồn nghĩa không phù hợp thì rà lại trước khi cập nhật.`
    });
    const missingKanjiExamples = data.kanji.filter(item => !item.first_word && !item.example_words?.length);
    for (const [chapter, items] of byChapter(missingKanjiExamples, item => item.labels?.chapter ?? kanjiChapter.get(item.id))) pushGap(gaps, {
      code: 'KANJI_MISSING_EXAMPLE', skill: 'KANJI_EXAMPLE', chapter: chapter === 'null' ? null : Number(chapter),
      count: items.length, ids: items.map(item => item.id), severity: ['N2','N1'].includes(level) ? 'BLOCKER' : 'IMPORTANT',
      proposal: `Bổ sung ví dụ từ/cụm có chứa ${items.length} chữ Kanji.`
    });

    if (level === 'N3') {
      const crossLevel = ['JV-048ec6d0c7','JV-1db28191d4','JV-33568f3729','JV-a5047f25e4','JV-e4548487d6'];
      pushGap(gaps, { code: 'REVIEW_N3_EXAMPLES_LINKED_TO_N4_WORDS', skill: 'VOCABULARY_LEVEL_REVIEW', count: crossLevel.length,
        ids: crossLevel, severity: 'IMPORTANT', proposal: 'Xác nhận chuyển 5 ví dụ sang N4 theo cấp mục từ; giữ dấu vết nguồn bộ ví dụ N3 hoặc giữ N3 nếu owner xác nhận cách dạy.' });
    }
    if (level === 'N4') pushGap(gaps, {
      code: 'REVIEW_APPLICATION_PATTERN_LABEL', skill: 'GRAMMAR_APPLICATION_REVIEW', count: data.unlinked_reviewed_application_questions.length,
      ids: data.unlinked_reviewed_application_questions.map(item => item.id), severity: 'IMPORTANT',
      proposal: 'Đối chiếu hai câu với JG-3a hoặc thêm nhãn mẫu ～んです nếu owner duyệt; giữ nguyên câu và đáp án cho tới khi chốt mapping.'
    });
    data.gaps = gaps;
    summary.jlpt[level] = {
      vocabulary_missing_examples: noExamples.length,
      vocabulary_by_tier: Object.fromEntries(['CORE','EXTENDED','UNLABELED'].map(tier => [tier, noExamples.filter(item => (item.labels?.tier ?? 'UNLABELED') === tier).length])),
      grammar_patterns_without_application: missingGrammar.length,
      grammar_application_count: data.grammar.reduce((sum, item) => sum + applicationCount(item), 0),
      kanji_total: data.kanji.length,
      kanji_with_book_meaning: data.kanji.filter(item => item.meanings_vi?.length).length,
      kanji_without_example: missingKanjiExamples.length,
      reading: data.reading.length, reading_questions: data.reading.reduce((sum, item) => sum + item.questions, 0),
      listening: data.listening.length, dialogue_lines: lineCount,
      sparse_chapters: summary.sparseChapters.filter(item => item.level === level).length,
      gaps
    };
  }
  for (const goal of GOALS) {
    const data = outputs[`communication/${goal}.json`].result;
    const gaps = [];
    const topicsNeeded = Math.max(0, 12 - data.topics.length);
    pushGap(gaps, { code: 'COMM_TOPICS_BELOW_MINIMUM', skill: 'TOPIC', count: topicsNeeded, ids: data.topics.map(item => item.code),
      severity: 'IMPORTANT', proposal: `Mở rộng thêm ${topicsNeeded} chủ đề; mỗi chủ đề mới cần ít nhất 25 từ, 8 mẫu câu và 3 hội thoại.` });
    for (const topic of data.topics) {
      const vocabulary = data.vocabulary.filter(item => item.topic === topic.code);
      const dialogues = data.dialogues.filter(item => item.topic_code === topic.code);
      const phrases = data.phrases.filter(item => dialogues.some(dialogue => dialogue.id === item.dialogue_id));
      const lines = dialogues.reduce((sum, dialogue) => sum + dialogue.lines.length, 0);
      for (const [skill, have, target, ids, noun] of [
        ['VOCABULARY', vocabulary.length, 25, vocabulary.map(item => item.id), 'từ'],
        ['KEY_PHRASE', phrases.length, 8, phrases.map(item => item.id), 'mẫu câu'],
        ['DIALOGUE', dialogues.length, 3, dialogues.map(item => item.id), dialogues.length ? 'hội thoại' : 'hội thoại 8–14 dòng; gán cấp N5–N3']
      ]) {
        const deficit = Math.max(0, target - have);
        pushGap(gaps, { code: `COMM_TOPIC_${skill}_BELOW_MINIMUM`, skill, topic: topic.code, count: deficit,
          ids, severity: 'IMPORTANT', proposal: `Soạn thêm ${deficit} ${noun} để chủ đề đạt tối thiểu ${target}; hiện có ${have}.` });
      }
      summary.communication[goal] ??= [];
      summary.communication[goal].push({ topic: topic.code, level: topic.level, vocabulary: vocabulary.length, key_phrases: phrases.length,
        dialogues: dialogues.length, dialogue_lines: lines, gaps: gaps.filter(item => item.topic === topic.code) });
    }
    const shortDialogues = data.dialogues.filter(dialogue => dialogue.lines.length < 8 || dialogue.lines.length > 14);
    if (shortDialogues.length) pushGap(gaps, { code: 'COMM_DIALOGUE_LENGTH_OUTSIDE_TARGET', skill: 'DIALOGUE_LENGTH', count: shortDialogues.length,
      ids: shortDialogues.map(item => item.id), severity: 'NICE',
      proposal: 'Bổ sung/bớt dòng để mỗi hội thoại đạt 8–14 dòng; ưu tiên các hội thoại đang có 6–7 dòng.' });
    data.gaps = gaps;
    summary.communication[goal].push({ topic: null, topics_total: data.topics.length, topics_needed: topicsNeeded,
      vocabulary_to_minimum: gaps.filter(item => item.skill === 'VOCABULARY').reduce((n, item) => n + item.count, 0) + topicsNeeded * 25,
      phrases_to_minimum: gaps.filter(item => item.skill === 'KEY_PHRASE').reduce((n, item) => n + item.count, 0) + topicsNeeded * 8,
      dialogues_to_minimum: gaps.filter(item => item.skill === 'DIALOGUE').reduce((n, item) => n + item.count, 0) + topicsNeeded * 3,
      gaps });
  }
  return summary;
}

function renderGapReport(summary, outputs) {
  const lines = ['# Báo cáo thiếu hụt lộ trình J-LAS — Giai đoạn 2', '',
    'Báo cáo được sinh từ nguồn hiện hành; chưa soạn nội dung học. Mọi ID trong bảng là tham chiếu nguồn. Các đề xuất JLPT dùng sách N4/N5 làm mốc hoặc chỉ ra quy mô thiếu hụt, cần owner duyệt trước Giai đoạn 3/4.', '',
    '## Tổng quan theo cấp × kỹ năng', '',
    '| Cấp | Đọc / câu | Nghe | Hội thoại | Từ thiếu ví dụ | Ngữ pháp thiếu câu | Kanji thiếu nghĩa | Kanji thiếu ví dụ |',
    '|---|---:|---:|---:|---:|---:|---:|---:|'];
  for (const level of LEVELS) {
    const s = summary.jlpt[level];
    lines.push(`| ${level} | ${s.reading} / ${s.reading_questions} | ${s.listening} | ${s.dialogue_lines} dòng | ${s.vocabulary_missing_examples} | ${s.grammar_patterns_without_application} mẫu | ${s.kanji_total - s.kanji_with_book_meaning}/${s.kanji_total} thiếu | ${s.kanji_without_example} |`);
  }
  lines.push('', 'N5–N3: không có chương Đọc/Nghe dưới một nửa trung bình cấp; báo cáo chương rỗng/thưa chỉ ghi khi count bằng 0 hoặc thấp hơn một nửa trung bình theo chương. Hội thoại N3 không có phân chương trong nguồn nên được đo toàn cấp.', '',
    '## Từ vựng chưa có ví dụ theo cấp, chương và tier', '',
    '| Cấp | Tier | Chương | Thiếu | ID mẫu (tối đa 50) |', '|---|---|---:|---:|---|');
  for (const level of LEVELS) for (const gap of outputs[`jlpt/${level}.json`].result.gaps.filter(item => item.skill === 'VOCABULARY_EXAMPLE'))
    lines.push(`| ${level} | ${gap.code.replace('VOCAB_EXAMPLE_MISSING_','')} | ${gap.chapter ?? '—'} | ${gap.count} | ${gap.ids.join(', ')}${gap.ids_total > gap.ids.length ? ` … (${gap.ids_total} tổng)` : ''} |`);
  lines.push('', `N3 example section có ${summary.n3ExcludedVocab.length} mục bị loại; IDs: ${summary.n3ExcludedVocab.map(item=>item.id).join(', ')}. Đây cũng là các mục thiếu ví dụ trong N3 để tránh tính hai lần.`, '',
    '## Ngữ pháp chưa có câu áp dụng', '',
    '| Cấp | Chương | Pattern thiếu | Câu/nguyên nhân bị loại | Đề xuất |', '|---|---:|---:|---:|---|');
  for (const level of LEVELS) for (const gap of outputs[`jlpt/${level}.json`].result.gaps.filter(item => item.skill === 'GRAMMAR_APPLICATION'))
    lines.push(`| ${level} | ${gap.chapter ?? '—'} | ${gap.code === 'GRAMMAR_WITHOUT_APPLICATION' ? gap.count : '—'} | ${gap.code === 'N3_REJECTED_GRAMMAR_APPLICATION' ? gap.count : '—'} | ${gap.proposal} IDs: ${gap.ids.join(', ')}${gap.ids_total > gap.ids.length ? ` … (${gap.ids_total} tổng)` : ''} |`);
  lines.push('', '## Kanji cần rà', '',
    '| Cấp | Vấn đề | Chương | Số chữ | ID mẫu |', '|---|---|---:|---:|---|');
  for (const level of LEVELS) for (const gap of outputs[`jlpt/${level}.json`].result.gaps.filter(item => item.skill === 'KANJI_MEANING' || item.skill === 'KANJI_EXAMPLE'))
    lines.push(`| ${level} | ${gap.skill} | ${gap.chapter ?? '—'} | ${gap.count} | ${gap.ids.join(', ')}${gap.ids_total > gap.ids.length ? ` … (${gap.ids_total} tổng)` : ''} |`);
  lines.push('', '## Giao tiếp theo chủ đề so với chuẩn Giai đoạn 3', '',
    '| Nhu cầu | Chủ đề | Cấp hiện có | Từ | Mẫu câu | Hội thoại | Dòng thoại |', '|---|---|---|---:|---:|---:|---:|');
  for (const goal of GOALS) {
    for (const row of summary.communication[goal].filter(item => item.topic))
      lines.push(`| ${goal} | ${row.topic} | ${row.level ?? 'chưa gán'} | ${row.vocabulary} / 25 | ${row.key_phrases} / 8 | ${row.dialogues} / 3 | ${row.dialogue_lines} |`);
    const total = summary.communication[goal].at(-1);
    lines.push(`| ${goal} | **Tổng thiếu để đạt chuẩn** | — | ${total.vocabulary_to_minimum} | ${total.phrases_to_minimum} | ${total.dialogues_to_minimum} | — |`);
  }
  lines.push('', 'Hội thoại hiện có ngoài độ dài 8–14 dòng cần chỉnh:', '');
  for (const goal of GOALS) {
    const gap = outputs[`communication/${goal}.json`].result.gaps.find(item => item.skill === 'DIALOGUE_LENGTH');
    if (gap) lines.push(`- **${goal} (${gap.count}):** ${gap.ids.join(', ')}.`);
  }
  lines.push('', 'Nghĩa Kanji trong bảng tổng quan được đếm từ trường nghĩa Việt của sách tổng hợp. Nghĩa tra cứu đã có trong resource riêng vẫn được giữ ở mục Kanji; với các ID thiếu trường sách, đối chiếu nghĩa resource rồi gắn vào nếu phù hợp.', '');
  lines.push('', '## Mục cần owner/AI chính quyết định', '',
    '| ID | Vấn đề | Đề xuất |', '|---|---|---|');
  for (const id of ['JV-048ec6d0c7','JV-1db28191d4','JV-33568f3729','JV-a5047f25e4','JV-e4548487d6'])
    lines.push(`| ${id} | Ví dụ mang nhãn N3 nhưng từ đã xếp ở N4 | Chuyển ví dụ sang N4 theo cấp mục từ, giữ dấu vết nguồn N3; chỉ giữ N3 nếu owner xác nhận từ được dạy trước. |`);
  lines.push(
    '| LP-GRAM-N4-26-noi-01 | Câu dùng ～んです nhưng không khớp pattern sách tổng hợp | Giữ câu; owner duyệt thêm alias pattern ～んです để hai câu nối đúng mục. |',
    '| LP-GRAM-N4-26-noi-02 | Câu dùng ～んです nhưng không khớp pattern sách tổng hợp | Giữ câu; owner duyệt thêm alias pattern ～んです để hai câu nối đúng mục. |',
    '| DAILY_LIFE.TIME | Có 12 từ nhưng không có mẫu câu/hội thoại, không suy ra cấp | Gán N5–N3 và bổ sung theo chuẩn tối thiểu của mỗi chủ đề. |');
  lines.push('', '## Tổng đề xuất soạn bổ sung', '',
    '| Giai đoạn / hạng mục | Số lượng đề xuất | Ước lượng công | Ưu tiên |', '|---|---:|---:|---|');
  const comm = GOALS.map(goal => summary.communication[goal].at(-1));
  const commTopics = comm.reduce((n, item) => n + item.topics_needed, 0);
  const commVocab = comm.reduce((n, item) => n + item.vocabulary_to_minimum, 0);
  const commPhrases = comm.reduce((n, item) => n + item.phrases_to_minimum, 0);
  const commDialogues = comm.reduce((n, item) => n + item.dialogues_to_minimum, 0);
  const commDialogueEdits = GOALS.reduce((n, goal) => n + outputs[`communication/${goal}.json`].result.gaps
    .filter(item => item.skill === 'DIALOGUE_LENGTH').reduce((sum, item) => sum + item.count, 0), 0);
  const n2 = summary.jlpt.N2, n1 = summary.jlpt.N1;
  const n5n4Grammar = ['N5','N4'].reduce((n,l)=>n+summary.jlpt[l].grammar_patterns_without_application,0);
  const n2n1Grammar = ['N2','N1'].reduce((n,l)=>n+summary.jlpt[l].grammar_patterns_without_application,0);
  const jlptVocab = ['N5','N4','N3','N2','N1'].reduce((n,l)=>n+summary.jlpt[l].vocabulary_missing_examples,0);
  const kanjiMeaning = LEVELS.reduce((n,l)=>n+summary.jlpt[l].kanji_total-summary.jlpt[l].kanji_with_book_meaning,0);
  const kanjiExample = LEVELS.reduce((n,l)=>n+summary.jlpt[l].kanji_without_example,0);
  lines.push(`| Giai đoạn 3 · mở rộng chủ đề giao tiếp | ${commTopics} chủ đề; ${commVocab} từ có ví dụ; ${commPhrases} mẫu câu; ${commDialogues} hội thoại mới; sửa ${commDialogueEdits} hội thoại ngắn | Khoảng 60–80 giờ soạn + rà | 1 |`,
    `| Giai đoạn 4 · ví dụ từ vựng JLPT | ${jlptVocab} ví dụ (CORE/EXTENDED N5–N3; N2/N1 chưa gắn tier) | Khoảng 60–100 giờ AI soạn + rà | 2 |`,
    `| Giai đoạn 4 · ngữ pháp N5–N4 | ${n5n4Grammar} pattern chưa có câu áp dụng ngoài các câu đã duyệt | Khoảng 45–70 giờ soạn + kiểm đáp án | 2 |`,
    `| Giai đoạn 4 · ngữ pháp N3/N2/N1 | N3: 26 câu bị loại + 7 pattern chưa có câu; N2/N1: ${n2n1Grammar} pattern chưa có câu | Khoảng 70–110 giờ soạn + kiểm đáp án | 2 |`,
    `| Giai đoạn 4 · Đọc/Nghe/Hội thoại N2/N1 | N2: ${n2.reading}→95 bài đọc, ${n2.listening}→125 clip, 480 dòng thoại; N1: ${n1.reading}→164 bài đọc, ${n1.listening}→216 clip, 832 dòng thoại | Khoảng 180–260 giờ, gồm viết lời nghe nhưng chưa sản xuất audio | 2 |`,
    `| Giai đoạn 4 · Kanji | ${kanjiMeaning} chữ thiếu nghĩa rà; ${kanjiExample} chữ thiếu ví dụ | Khoảng 40–70 giờ tra/rà và viết ví dụ | 2 |`,
    `| Giai đoạn 4 · hội thoại N3 | 263 dòng để đạt mốc 400 | Khoảng 10–16 giờ soạn + rà | 2 |`,
    '', 'Ước lượng là công soạn và rà nội dung; chưa tính thu âm, xin quyền phân phối, tích hợp pipeline hay kiểm thử trên DB. Dự báo đọc/nghe N2/N1 dùng trung bình N4 theo chương, hội thoại dùng 16 dòng/chương như N5/N4. Câu hỏi đọc hiểu dự tính theo tỷ lệ N4 142 câu/79 bài. Giai đoạn 3 được xếp trước Giai đoạn 4 như yêu cầu.',
    '', `### Quy tắc đo chương thưa\n\nChương được đánh dấu rỗng nếu có 0 mục; đánh dấu thưa nếu số mục thấp hơn một nửa trung bình theo chương của cùng cấp và kỹ năng. Hiện N5–N3 không có chương Đọc/Nghe rỗng hoặc dưới ngưỡng này. Hội thoại N3 chỉ có tổng cấp, không có ánh xạ chương trong nguồn.`, '');
  return lines.join('\n');
}

function readme(sectionPaths, outputs) {
  const dispositions = Object.entries(sectionPaths).map(([name, files]) => `| \`${name}\` | ${files.map(f => `\`${f}\``).join(', ')} | ${
    name.startsWith('historical_') || ['owned_draft_source_manifest','source_aligned_pilot_archive'].includes(name) ? 'Lưu lịch sử, không dùng làm lộ trình hiện hành' :
    /review_draft|review_batches|question_candidates|selection|correction|reviewed_vocabulary/.test(name) ? 'Giữ bản duyệt/đề xuất để đối chiếu, chưa tự phát hành' :
    'Giữ nguyên nguồn; trường phù hợp đã gộp vào lộ trình'
  } |`);
  return `# Nguồn dữ liệu lộ trình học J-LAS\n\n`+
    `Bộ xuất ở giai đoạn 1 (28/10/2026), chưa bổ sung nội dung và chưa chuyển pipeline. Chạy \`node tools/learning-path-source/build_learning_path_source.mjs\` để sinh lại; thêm \`--check\` để so byte, không ghi file. Không sửa tay các JSON đầu ra.\n\n`+
    `## Cấu trúc\n\n`+
    `- \`jlpt/kana.json\`: bảng Kana nguồn và quy tắc âm đặc biệt.\n`+
    `- \`jlpt/N5.json\` … \`N1.json\`: mỗi cấp gồm chương (danh sách ID theo kỹ năng), mục từ vựng/Kanji/ngữ pháp, bài Đọc/Nghe với nội dung và đáp án nguồn, hội thoại, kho Luyện thêm, nguồn gốc và \`gaps[]\` do script tính.\n`+
    `- \`gaps-report.md\`: báo cáo thiếu hụt theo cấp/chương/chủ đề và tổng đề xuất Giai đoạn 3/4.\n`+
    `- \`communication/BA.json\`, \`TRAVEL.json\`, \`DAILY_LIFE.json\`: khảo sát, chủ đề, từ/Kanji, mẫu câu, hội thoại, luyện tập, giọng đọc và ghi công.\n`+
    `- \`source-sections/\`: bản lưu nguyên giá trị của 42 section, 5 tài nguyên runtime, manifest audio và bảng Kana nguồn. \`index.json\` giữ metadata cấp gốc và thứ tự bài của section lớn bị chia. Có thể dựng lại dữ liệu cũ từ đây.\n`+
    `- \`manifest.json\`: SHA-256 của từng file sinh ra và số mục trong file.\n\n`+
    `## Schema và trạng thái\n\n`+
    `Mỗi mục JLPT giữ nguyên ID, trường gốc, nghĩa, ví dụ, đáp án, trạng thái, \`books\` và nhãn nguồn. Các trường thêm: \`normalized_expression\`, \`normalized_reading\` = katakana→hiragana(NFKC) (giữ \`ー\`, \`・\`), \`labels\` từ \`jlas_unit_labels\`, \`jlas_example\` và \`application_questions\` cho nội dung N3. \`RELEASED\`/\`NOT_RELEASED\` là trạng thái lộ trình; \`original_status\`, review/publication ở từng nguồn vẫn được giữ. Dữ liệu nguồn chưa duyệt không được tự đổi thành đã duyệt.\n\n`+
    `Âm thanh chỉ lưu đường dẫn và SHA-256 từ \`learning-audio-manifest.json\`; không sao chép MP3. BA/TRAVEL/DAILY_LIFE dùng giọng tổng hợp trên trình duyệt. Nguồn gốc JMdict/KANJIDIC2 giữ trong \`attribution\` và bản lưu nguồn.\n\n`+
    `Cấp độ chủ đề giao tiếp được suy ra từ hội thoại đang có; \`DAILY_LIFE.TIME\` chưa có hội thoại nên \`level: null\`. BA có hội thoại nguồn mức N2; giai đoạn 1 giữ nguyên cấp nguồn, chưa viết lại xuống N3.\n\n`+
    `Trong 5.583 ví dụ từ vựng J-LAS mang nhãn N3, 5 ID thuộc danh mục từ N4 sau khi sách tổng hợp khử trùng. N4 gắn ví dụ vào đúng từ; N3 giữ toàn bộ map \`jlas_n3_vocab_examples\` để không mất mục và giữ số đếm nguồn.\n\n`+
    `Có 2 câu ngữ pháp N4 đã duyệt (\`LP-GRAM-N4-26-noi-01\`, \`LP-GRAM-N4-26-noi-02\`) dùng nhãn mẫu \`～んです\` không khớp chuỗi pattern sách tổng hợp; chúng nằm trong \`unlinked_reviewed_application_questions\` để người duyệt nối chính xác sau.\n\n`+
    `## Đối chiếu số lượng\n\n`+
    `| Cấp | Từ | Kanji | Ngữ pháp | Đọc | Câu đọc | Nghe | Dòng hội thoại |\n|---|---:|---:|---:|---:|---:|---:|---:|\n`+
    LEVELS.map(level => { const c=outputs[`jlpt/${level}.json`].counts; return `| ${level} | ${c.vocabulary} | ${c.kanji} | ${c.grammar} | ${c.reading} | ${c.reading_questions} | ${c.listening} | ${c.dialogue_lines} |`; }).join('\n')+'\n\n'+
    `| Giao tiếp | Chủ đề | Từ | Kanji | Hội thoại |\n|---|---:|---:|---:|---:|\n`+
    GOALS.map(goal => { const c=outputs[`communication/${goal}.json`].counts; return `| ${goal} | ${c.topics} | ${c.vocabulary} | ${c.kanji} | ${c.dialogues} |`; }).join('\n')+'\n\n'+
    `N3: ${outputs['jlpt/N3.json'].counts.jlas_grammar_application} câu ngữ pháp J-LAS, ${outputs['jlpt/N3.json'].counts.jlas_vocab_examples} ví dụ J-LAS. Kho Luyện thêm: N3 ${outputs['jlpt/N3.json'].counts.extra_practice_pool} đoạn/${outputs['jlpt/N3.json'].counts.extra_practice_questions} câu, N4 ${outputs['jlpt/N4.json'].counts.extra_practice_pool} bài/${outputs['jlpt/N4.json'].counts.extra_practice_questions} câu.\n\n`+
    `## Đối chiếu toàn bộ 41 section\n\nKhông bỏ section nào; bản nháp và lịch sử chỉ được lưu để tra cứu, chưa gộp vào nội dung phát hành.\n\n| Section | Bản lưu | Cách dùng |\n|---|---|---|\n`+
    dispositions.join('\n')+'\n';
}

export async function generate({ check = false, outputDir = OUTPUT } = {}) {
  const input = await readJson('data/learning-owned/learning-content.json');
  const sections = input.sections;
  const audioManifest = await readJson('data/learning-owned/learning-audio-manifest.json');
  const kanaSource = await readJson('data/seed_jlas/kana_data.json');
  const kanaSourceHash = sha(await readFile(path.join(ROOT, 'data/seed_jlas/kana_data.json')));
  const resourceNames = ['compiled-vocabulary-tiers','grammar-application-reviewed','grammar-application-supplement','kanji-meaning-vi','word-type-reviewed'];
  const resources = Object.fromEntries(await Promise.all(resourceNames.map(async name => [name, await readJson(`src/main/resources/learning/${name}.json`)])));
  const resourceHashes = Object.fromEntries(await Promise.all(resourceNames.map(async name => [name,
    sha(await readFile(path.join(ROOT, `src/main/resources/learning/${name}.json`)))])));
  count(Object.keys(sections).length === 42, `Expected 42 sections, found ${Object.keys(sections).length}`);
  const files = new Map();
  const sectionPaths = {};
  const sectionIndex = {
    schema: input.schema, scope: input.scope, availability: input.availability,
    section_sha256: input.section_sha256, review_audit_archive: input.review_audit_archive,
    sections: sectionPaths
  };
  for (const [name, value] of Object.entries(sections)) {
    if (name !== 'jlpt_comprehension_n5_n3') {
      const relative = `source-sections/${name}.json`;
      files.set(relative, { value, counts: arrayCounts(value) });
      sectionPaths[name] = [relative];
      continue;
    }
    const common = Object.fromEntries(Object.entries(value).filter(([key]) => key !== 'lessons'));
    const order = value.lessons.map(lesson => lesson.level);
    sectionIndex.comprehension_common = common;
    sectionIndex.comprehension_lesson_order = order;
    sectionPaths[name] = [];
    for (const level of ['N5', 'N4', 'N3']) {
      const relative = `source-sections/jlpt_comprehension_n5_n3_${level.toLowerCase()}.json`;
      const lessons = value.lessons.filter(lesson => lesson.level === level);
      files.set(relative, { value: lessons, counts: { lessons: lessons.length } });
      sectionPaths[name].push(relative);
    }
  }
  for (const name of resourceNames) files.set(`source-sections/resources/${name}.json`, { value: resources[name], counts: arrayCounts(resources[name]) });
  files.set('source-sections/learning-audio-manifest.json', { value: audioManifest, counts: Object.fromEntries(Object.entries(audioManifest.groups).map(([key, value]) => [key, value.entries.length])) });
  files.set('source-sections/kana-data.json', { value: kanaSource, counts: Object.fromEntries(Object.entries(kanaSource).map(([key, value]) => [key, value.length])) });
  files.set('source-sections/index.json', { value: sectionIndex, counts: { sections: Object.keys(sections).length } });
  const audioPaths = buildAudioPaths(audioManifest);
  const outputs = {};
  for (const level of LEVELS) {
    const output = buildJlpt(level, sections, input.section_sha256, resources, resourceHashes, audioPaths);
    outputs[`jlpt/${level}.json`] = output;
    files.set(`jlpt/${level}.json`, { value: output.result, counts: output.counts });
  }
  for (const goal of GOALS) {
    const output = buildCommunication(goal, sections, input.section_sha256);
    outputs[`communication/${goal}.json`] = output;
    files.set(`communication/${goal}.json`, { value: output.result, counts: output.counts });
  }
  count(outputs['jlpt/N3.json'].counts.jlas_grammar_application === 1404, 'N3 J-LAS grammar application count must be 1404');
  count(outputs['jlpt/N3.json'].counts.jlas_vocab_examples === 5583, 'N3 J-LAS vocabulary example count must be 5583');
  count(outputs['jlpt/N4.json'].counts.extra_practice_pool === 12 && outputs['jlpt/N4.json'].counts.extra_practice_questions === 17 &&
    outputs['jlpt/N3.json'].counts.extra_practice_pool === 7 && outputs['jlpt/N3.json'].counts.extra_practice_questions === 13,
  'Extra practice pool counts must be N4 12/17 and N3 7/13');
  files.set('jlpt/kana.json', { value: {
    alphabet: kanaSource, special_sounds: sections.kana_special_sounds,
    sources: [
      sourceDescriptor('data/seed_jlas/kana_data.json', kanaSource, kanaSourceHash),
      sourceDescriptor('learning-content.json#sections.kana_special_sounds', sections.kana_special_sounds, input.section_sha256.kana_special_sounds)
    ], gaps: []
  }, counts: { hiragana_groups: kanaSource.hiragana.length, katakana_groups: kanaSource.katakana.length, special_sound_lessons: sections.kana_special_sounds.lessons.length } });
  const gapSummary = buildGapAnalysis(outputs, sections);
  for (const level of LEVELS) files.set(`jlpt/${level}.json`, { value: outputs[`jlpt/${level}.json`].result,
    counts: { ...outputs[`jlpt/${level}.json`].counts, gaps: outputs[`jlpt/${level}.json`].result.gaps.length } });
  for (const goal of GOALS) files.set(`communication/${goal}.json`, { value: outputs[`communication/${goal}.json`].result,
    counts: { ...outputs[`communication/${goal}.json`].counts, gaps: outputs[`communication/${goal}.json`].result.gaps.length } });
  const gapReport = renderGapReport(gapSummary, outputs);
  files.set('gaps-report.md', { text: gapReport, counts: {
    jlpt_gaps: LEVELS.reduce((sum, level) => sum + outputs[`jlpt/${level}.json`].result.gaps.length, 0),
    communication_gaps: GOALS.reduce((sum, goal) => sum + outputs[`communication/${goal}.json`].result.gaps.length, 0)
  } });
  const readmeText = readme(sectionPaths, outputs);
  files.set('README.md', { text: readmeText, counts: { sections: Object.keys(sections).length } });
  const manifestEntries = [];
  const rendered = new Map();
  for (const [relative, data] of [...files].sort(([a], [b]) => a.localeCompare(b, 'en'))) {
    const text = data.text ?? json(data.value);
    const bytes = Buffer.byteLength(text);
    count(bytes <= LIMIT, `${relative} exceeds 20 MiB (${bytes} bytes)`);
    rendered.set(relative, text);
    manifestEntries.push({ path: relative, sha256: sha(text), bytes, counts: data.counts });
  }
  const manifest = { schema: 'JLAS_LEARNING_PATH_SOURCE_V1', generated_from: 'data/learning-owned/learning-content.json',
    source_sha256: sha(await readFile(path.join(ROOT, 'data/learning-owned/learning-content.json'))),
    audio_listing_sha256: audioManifest.listing_sha256,
    files: manifestEntries
  };
  rendered.set('manifest.json', json(manifest));
  const mismatches = [];
  for (const [relative, text] of rendered) {
    const file = path.join(outputDir, relative);
    if (check) {
      let current;
      try { current = await readFile(file, 'utf8'); } catch { mismatches.push(`${relative}: missing`); continue; }
      if (current !== text) mismatches.push(`${relative}: differs`);
    } else {
      await mkdir(path.dirname(file), { recursive: true });
      await writeFile(file, text, 'utf8');
    }
  }
  if (check && mismatches.length) throw new Error(`--check failed (${mismatches.length}): ${mismatches.slice(0, 10).join(', ')}`);
  return { checked: check, files: rendered.size, counts: Object.fromEntries(Object.entries(outputs).map(([key, value]) => [key, value.counts])) };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const check = process.argv.includes('--check');
  const unknown = process.argv.slice(2).filter(arg => arg !== '--check');
  if (unknown.length) throw new Error(`Unknown arguments: ${unknown.join(' ')}`);
  generate({ check }).then(result => console.log(JSON.stringify(result, null, 2))).catch(error => { console.error(error); process.exitCode = 1; });
}
