import { existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readLearningContent, writeLearningSection } from './learning_content_store.mjs';

/**
 * [JLAS-W0108] OD-44 follow-up (owner 2026-10-20): N3 Reading/Listening for the J-LAS "Tiếng Nhật N3" chapters,
 * taken only from Speed Master N3 in the canonical VNJPClub comprehension source. Two reading passages and two
 * listening clips per chapter, easy to hard; only single-answer questions whose key is confirmed by an independent
 * source field are graded. HOLD/uncertain items are never used and stay in the source HOLD list.
 */
const SECTION = 'jlpt_speed_n3_comprehension';
const SOURCE_SECTION = 'jlpt_comprehension_n5_n3';
const BOOK = 'Speed Master N3';
const AUDIO_PATH = /^vnjpclub\/N3\/speed-n3-nghe-hieu\/[A-Za-z0-9_-]+\.mp3$/;

/** Slot 1 then slot 2 of every chapter; each slot runs through its types in order with a per-type quota. */
export const DEFAULT_PLAN = Object.freeze({
  chapters: 27,
  READING: [[['Đoạn văn ngắn', 17], ['Đoạn văn trung bình', 10]],
    [['Tìm kiếm thông tin', 11], ['Đoạn văn trung bình', 4], ['Đoạn văn dài', 12]]],
  LISTENING: [[['Hiểu nhiệm vụ', 15], ['Hiểu điểm chính', 12]], [['Diễn đạt lời nói', 18], ['Hiểu khái quát', 9]]],
});

/**
 * Held after a question-by-question answerability review (2026-10-20, four independent reviewers over every Speed
 * Master N3 candidate): the flattened source text lost material the question needs, or the source key is disputed.
 * They stay out of grading until the source is fixed; the HOLD list of the source section is unchanged.
 */
export const REVIEW_HOLDS = Object.freeze({
  'SPEED-N3-L063-RDG-001': 'review: mất xuống dòng giữa các tên truyện nên không đếm được số tên',
  'SPEED-N3-L064-RDG-001': 'review: đáp án nguồn (1) mâu thuẫn bảng phí huỷ trong bài (người rà soát chọn 3)',
  'SPEED-N3-L071-RDG-001': 'review: hai phương án cùng hợp lý, đáp án mơ hồ',
  'SPEED-N3-L103-RDG-001': 'review: bảng 〇 mất cấu trúc cột nên không xác định được',
  'SPEED-N3-L113-RDG-001': 'review: thiếu đoạn hội thoại mà câu hỏi nhắc tới',
  'SPEED-N3-L114-RDG-001': 'review: thiếu đoạn hội thoại mà câu hỏi nhắc tới',
  'SPEED-N3-L117-RDG-001': 'review: thiếu biểu đồ (phương án là 図1–図4)',
});

const clean = (value) => String(value ?? '').replace(/ /g, ' ').trim();
const lines = (text) => String(text ?? '').split(/\r?\n/).map(clean).filter(Boolean);
const toDigit = (value) => Number(String(value).replace(/[０-９]/g, (d) => '０１２３４５６７８９'.indexOf(d)));
const isQuestionLine = (line) => /^問\s*[0-9０-９]+/.test(line);
const norm = (value) => clean(value).normalize('NFKC').replace(/\s+/g, ' ').toLowerCase();

// "１ …", "1. …", "３．…", "２）…" — the source mixes all of these within one question (review 2026-10-20, L066).
const OPTION_MARK = /^[1-4１-４](?:\s|[.．、)）]\s*)/;
const isOptionLine = (line) => OPTION_MARK.test(line);

/**
 * Passage = every source line except the question blocks (問n + its numbered options) — an information-search
 * notice comes after the questions in the source. Translation = the "Dịch & Giải thích" part, where a question
 * block is 問n, its translated line and the numbered (original + translated) options.
 */
export function parseReading(text) {
  const [head, ...rest] = String(text ?? '').split(/Dịch\s*&\s*Giải thích/);
  const passage = [];
  let inQuestion = false;
  for (const line of lines(head)) {
    if (isQuestionLine(line)) { inQuestion = true; continue; }
    if (inQuestion && isOptionLine(line)) continue;
    inQuestion = false;
    if (line === '【】' || /^問題\s*[0-9０-９]+(-[0-9０-９]+)?$/.test(line)) continue;
    passage.push(line);
  }
  const passageTranslation = [];
  const questionTranslations = [];
  let block = null;
  for (const line of lines(rest.join('\n'))) {
    if (isQuestionLine(line)) { block = { lines: [line], translated: false }; questionTranslations.push(block.lines); continue; }
    if (block && isOptionLine(line)) { block.lines.push(line); block.translated = true; continue; }
    if (block && !block.translated && block.lines.length === 1) { block.lines.push(line); continue; }
    block = null;
    passageTranslation.push(line);
  }
  return { passage, passageTranslation, questionTranslations };
}

/** Script = the "Script + Đáp Án" lines without the key; the key line is read separately; translation follows. */
export function parseListening(text) {
  const source = String(text ?? '');
  const scriptStart = source.search(/☞\s*Script\s*\+\s*Đáp Án/i);
  const translationStart = source.search(/☞\s*Script\s*\+\s*Dịch/i);
  if (scriptStart < 0 || translationStart < scriptStart) return { script: [], translation: [], key: null, prompt: '' };
  const scriptPart = lines(source.slice(scriptStart, translationStart)).slice(1);
  const keyLine = scriptPart.find((line) => /^【正解】/.test(line));
  const key = keyLine ? toDigit(keyLine.replace(/^【正解】\s*/, '').charAt(0)) : null;
  const script = scriptPart.filter((line) => !/^【正解】/.test(line));
  const translation = lines(source.slice(translationStart)).slice(1).filter((line) => !/^【正解】/.test(line));
  const prompt = [...script].reverse().find((line) => /[かカ][。？?]$/.test(line) && !/^[ⓂⒻ]/.test(line)) ?? '';
  return { script, translation, key: Number.isInteger(key) && key > 0 ? key : null, prompt: clean(prompt) };
}

function checkQuestion(q, keyInText) {
  const c = q.content ?? {};
  const options = (q.options ?? []).map((o) => clean(o.content?.noi_dung));
  const flagged = (q.options ?? []).map((o, i) => (o.content?.la_dap_an === true ? i + 1 : 0)).filter(Boolean);
  if (q.answer_check !== 'STRUCTURALLY_UNIQUE') return 'answer held for mapping review';
  if (c.loai_cau !== 'mot_dap_an') return `not single-answer (${c.loai_cau})`;
  if (c.answer_uncertain === true || c.answer_uncertain === 'True') return 'answer uncertain in source';
  if (options.length < 3 || options.length > 4 || options.some((o) => !o)) return 'options are not 3–4 printed choices';
  if (new Set(options.map(norm)).size !== options.length) return 'duplicate options';
  if (!Number.isInteger(c.dap_an) || flagged.length !== 1 || flagged[0] !== c.dap_an) return 'answer key and correct-option flag disagree';
  if (keyInText != null && keyInText !== c.dap_an) return 'answer key disagrees with the script';
  return null;
}

function readingItem(lesson) {
  const parsed = parseReading(lesson.content?.noi_dung);
  const questions = lesson.questions ?? [];
  if (parsed.passage.join('').length < 20) return { reason: 'passage not found' };
  if (!questions.length) return { reason: 'no questions' };
  const out = [];
  for (const [index, q] of questions.entries()) {
    const problem = checkQuestion(q, null);
    if (problem) return { reason: problem };
    const prompt = clean(q.content.cau_hoi);
    if (!prompt) return { reason: 'question prompt missing' };
    const translated = parsed.questionTranslations[index] ?? [];
    const explanation = [...(index === 0 && parsed.passageTranslation.length ? ['Dịch đoạn văn:', ...parsed.passageTranslation] : []),
      ...(translated.length ? ['Dịch câu hỏi:', ...translated] : []), clean(q.content.giai_thich)].filter(Boolean).join('\n');
    out.push({ prompt, options: q.options.map((o) => clean(o.content.noi_dung)), correct_option: q.content.dap_an, explanation, question_id: q.content.ma ?? null });
  }
  // A numbered passage line equal to one of the item's options means the answer choices leaked into the passage.
  const optionTexts = new Set(out.flatMap((q) => q.options.map(norm)));
  if (parsed.passage.some((line) => isOptionLine(line) && optionTexts.has(norm(line.replace(OPTION_MARK, ''))))) {
    return { reason: 'option text appears in the passage' };
  }
  return { text: parsed.passage, questions: out };
}

function listeningItem(lesson) {
  const parsed = parseListening(lesson.content?.noi_dung);
  const audio = lesson.audio_links ?? [];
  const questions = lesson.questions ?? [];
  if (questions.length !== 1) return { reason: `${questions.length} questions in one clip` };
  if (audio.length !== 1 || audio[0].audio_resolution !== 'MANIFEST_LOCAL_PATH' || !AUDIO_PATH.test(audio[0].audio_path ?? '')) {
    return { reason: 'clip has no single local audio file' };
  }
  if (!parsed.script.length) return { reason: 'script not found' };
  if (parsed.key == null) return { reason: 'answer key not found in the script' };
  const q = questions[0];
  const problem = checkQuestion(q, parsed.key);
  if (problem) return { reason: problem };
  const prompt = clean(q.content.cau_hoi) || parsed.prompt;
  if (!prompt) return { reason: 'question prompt missing' };
  return {
    text: parsed.script, audio_path: audio[0].audio_path,
    questions: [{ prompt, options: q.options.map((o) => clean(o.content.noi_dung)), correct_option: q.content.dap_an,
      explanation: parsed.translation.length ? ['Dịch:', ...parsed.translation].join('\n') : '', question_id: q.content.ma ?? null }],
  };
}

const lessonNumber = (lesson) => Number(/-L(\d+)-/.exec(lesson.source_id ?? '')?.[1] ?? Number.MAX_SAFE_INTEGER);

/**
 * {@code previous} (the stored section) freezes the chapter-slot → source assignment: a rebuild keeps every slot on
 * its source item and replaces only an item that became unusable, with the first usable spare of the same type, so
 * one new hold never re-deals the other slots under the same source codes (review 2026-10-20). Without a previous
 * section the plan deals the slots once.
 */
export function buildSection(source, sourceSectionHash, plan = DEFAULT_PLAN, previous = null) {
  if (!/^[a-f0-9]{64}$/.test(sourceSectionHash ?? '') || !Array.isArray(source?.lessons)) {
    throw new Error('The canonical comprehension source and its SHA-256 are required.');
  }
  const pool = source.lessons.filter((l) => l.level === 'N3' && l.content?.sach === BOOK && (l.questions ?? []).length)
    .sort((a, b) => lessonNumber(a) - lessonNumber(b) || String(a.source_id).localeCompare(String(b.source_id)));
  const items = new Map(pool.map((l) => [l.source_id, REVIEW_HOLDS[l.source_id] ? { reason: REVIEW_HOLDS[l.source_id] }
    : l.kind === 'READING' ? readingItem(l) : listeningItem(l)]));
  const usable = (lesson) => lesson && !items.get(lesson.source_id).reason;
  const typeOf = (lesson) => clean(lesson.content.unit);
  const assignments = [];
  const used = new Set();
  if (previous?.exercises?.length) {
    const previousIds = new Set(previous.exercises.map((e) => e.source_id));
    const ordered = [...previous.exercises].sort((a, b) => a.skill.localeCompare(b.skill) || a.slot - b.slot || a.chapter - b.chapter);
    for (const old of ordered) {
      let lesson = pool.find((l) => l.source_id === old.source_id);
      if (!usable(lesson) || used.has(lesson.source_id)) {
        lesson = pool.find((l) => l.kind === old.skill && typeOf(l) === old.type && usable(l)
          && !used.has(l.source_id) && !previousIds.has(l.source_id));
        if (!lesson) throw new Error(`no usable replacement for ${old.source_group} (${old.type})`);
      }
      used.add(lesson.source_id);
      assignments.push({ skill: old.skill, slot: old.slot, chapter: old.chapter, type: old.type, lesson });
    }
  } else {
    for (const skill of ['READING', 'LISTENING']) {
      plan[skill].forEach((slotTypes, slotIndex) => {
        const slot = slotIndex + 1;
        let chapter = 0;
        for (const [type, quota] of slotTypes) {
          const taken = pool.filter((l) => l.kind === skill && typeOf(l) === type && usable(l) && !used.has(l.source_id)).slice(0, quota);
          if (taken.length !== quota) throw new Error(`${skill} slot ${slot} type ${type}: ${quota} needed, ${taken.length} usable`);
          for (const lesson of taken) {
            used.add(lesson.source_id);
            assignments.push({ skill, slot, chapter: ++chapter, type, lesson });
          }
        }
        if (chapter !== plan.chapters) throw new Error(`${skill} slot ${slot} fills ${chapter}/${plan.chapters} chapters`);
      });
    }
  }
  const exercises = assignments.map(({ skill, slot, chapter, type, lesson }) => {
    const item = items.get(lesson.source_id);
    const group = `LP-OWNED-SPEED-N3-C${String(chapter).padStart(2, '0')}-${skill === 'READING' ? 'READ' : 'LISTEN'}-P${slot}`;
    return {
      source_group: group, chapter, skill, slot, type,
      source_id: lesson.source_id, source_lesson_code: lesson.content.lesson_ma ?? null,
      source_url: lesson.content.url_nguon ?? null,
      title: `${BOOK} · ${type}`,
      text_lines: item.text,
      audio_path: item.audio_path ?? null,
      audio_url: item.audio_path ? `/uploads/content-audio/${item.audio_path}` : null,
      questions: item.questions.map((q, i) => ({ ...q, source_code: `${group}-Q${String(i + 1).padStart(3, '0')}`,
        answer_text: q.options[q.correct_option - 1] })),
    };
  });
  // Every source item is checked: unusable ones are reported as held, usable unused ones stay in reserve.
  const held = [];
  const reserve = [];
  for (const lesson of pool) {
    const entry = { source_id: lesson.source_id, skill: lesson.kind, type: typeOf(lesson) };
    if (items.get(lesson.source_id).reason) held.push({ ...entry, reason: items.get(lesson.source_id).reason });
    else if (!used.has(lesson.source_id)) reserve.push({ ...entry, questions: lesson.questions.length });
  }
  const reading = exercises.filter((e) => e.skill === 'READING');
  const listening = exercises.filter((e) => e.skill === 'LISTENING');
  return {
    kind: 'JLPT_N3_SPEED_MASTER_COMPREHENSION',
    source_owner: 'vnjpclub',
    source_book: BOOK,
    source_section: SOURCE_SECTION,
    source_section_sha256: sourceSectionHash,
    owned_book: { title: 'Tiếng Nhật N3', jlpt_level: 'N3', chapter_rule: '(unit order - 1) * 5 + lesson order' },
    owner_approval: 'OD-44 follow-up 2026-10-20: Speed Master N3 only, 2 reading + 2 listening per chapter',
    publication: 'OWNER_AUTHORIZED_TEAM_SEED',
    study_eligible: true,
    quiz_eligible: false,
    smart_test_eligible: false,
    rights: 'OWNER_CONFIRMED_TEAM_DISTRIBUTION',
    distribution_authorization: 'JLAS_OWNER_CONFIRMED_TEAM_DISTRIBUTION_2026-10-20',
    review_status: 'CHUA_DUYET',
    label: 'Nguồn VNJPClub · Speed Master N3 · chưa duyệt',
    counts: {
      chapters: plan.chapters,
      reading_passages: reading.length,
      reading_questions: reading.reduce((n, e) => n + e.questions.length, 0),
      listening_clips: listening.length,
      listening_questions: listening.reduce((n, e) => n + e.questions.length, 0),
    },
    held,
    reserve,
    exercises,
  };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = path.resolve(process.argv[2] ?? '.');
  const audioRootFlag = process.argv.indexOf('--audio-root');
  const data = await readLearningContent(root);
  // The stored section freezes the chapter-slot assignment; --fresh re-deals from the plan (first build only).
  const previous = process.argv.includes('--fresh') ? null : data.sections[SECTION] ?? null;
  const section = buildSection(data.sections[SOURCE_SECTION], data.section_sha256[SOURCE_SECTION], DEFAULT_PLAN, previous);
  if (audioRootFlag > 0) {
    const audioRoot = path.resolve(process.argv[audioRootFlag + 1]);
    const missing = section.exercises.filter((e) => e.audio_path && !existsSync(path.join(audioRoot, e.audio_path)));
    if (missing.length) throw new Error(`Missing local audio: ${missing.map((e) => e.audio_path).join(', ')}`);
  }
  if (process.argv.includes('--check')) {
    if (JSON.stringify(data.sections[SECTION]) !== JSON.stringify(section)) throw new Error(`${SECTION} is stale.`);
  } else {
    await writeLearningSection(root, SECTION, section);
  }
  console.log(JSON.stringify({ counts: section.counts, held: section.held.length, reserve: section.reserve.length }));
}
