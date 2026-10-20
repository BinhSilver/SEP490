import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildSection, parseListening, parseReading, DEFAULT_PLAN, REVIEW_HOLDS } from './build_speed_n3_comprehension.mjs';
import { readLearningContent } from './learning_content_store.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const HASH = 'b'.repeat(64);

const option = (text, correct) => ({ content: { noi_dung: text, la_dap_an: correct } });
const question = (prompt, key, texts, extra = {}) => ({
  answer_check: 'STRUCTURALLY_UNIQUE',
  content: { cau_hoi: prompt, loai_cau: 'mot_dap_an', dap_an: key, answer_uncertain: false, ma: `Q-${prompt}-${key}`, ...extra },
  options: texts.map((text, index) => option(text, index + 1 === key)),
});
let lessonNumber = 0;
function readingLesson(unit, { questions = 1, key = 2 } = {}) {
  lessonNumber++;
  const prompts = Array.from({ length: questions }, (_, i) => `問${i + 1} 正しいのはどれか。`);
  const body = ['【】', '問題1-1', `本文${lessonNumber}です。長い文章がここにあります。`, '二行目の本文です。',
    ...prompts.flatMap((p) => [p, '１ あ', '２ い', '３ う', '４ え']),
    'Dịch & Giải thích', `本文${lessonNumber}です。`, 'Đây là đoạn văn.',
    ...prompts.flatMap((p, i) => [p, `Câu hỏi ${i + 1}: điều nào đúng?`, '１ あ', '1 A'])].join('\n');
  return {
    source_id: `SPEED-N3-L${String(lessonNumber).padStart(3, '0')}-RDG-001`, level: 'N3', kind: 'READING',
    content: { sach: 'Speed Master N3', unit, noi_dung: body, lesson_ma: `SPEED-N3-L${lessonNumber}`, url_nguon: 'https://example.test/r' },
    questions: prompts.map((p) => question(p, key, ['あ', 'い', 'う', 'え'])),
    audio_links: [],
  };
}
function listeningLesson(unit, { key = 3, textKey = key, prompt = '', audio = 'vnjpclub/N3/speed-n3-nghe-hieu/9.mp3' } = {}) {
  lessonNumber++;
  const body = ['【】', 'Giữ nguyên style giống file gốc', '1 駅に行く', '☞ Script + Đáp Án',
    '男の人と女の人が話しています。女の人はこのあと何をしますか。', 'Ⓜ：どうする？', 'Ⓕ：食べよう。',
    '女の人はこのあと何をしますか。', `【正解】${textKey}`, '☞ Script + Dịch',
    '男の人と女の人が話しています。', 'Người đàn ông và người phụ nữ đang nói chuyện.', `【正解】${textKey}`].join('\n');
  return {
    source_id: `SPEED-N3-L${String(lessonNumber).padStart(3, '0')}-LIS-001`, level: 'N3', kind: 'LISTENING',
    content: { sach: 'Speed Master N3', unit, noi_dung: body, lesson_ma: `SPEED-N3-L${lessonNumber}`, url_nguon: 'https://example.test/l' },
    questions: [question(prompt, key, ['駅に行く', '家に帰る', '食事をする', '買い物をする'])],
    audio_links: [{ audio_path: audio, audio_resolution: 'MANIFEST_LOCAL_PATH' }],
  };
}
const SMALL_PLAN = {
  chapters: 2,
  READING: [[['Đoạn văn ngắn', 1], ['Đoạn văn trung bình', 1]], [['Tìm kiếm thông tin', 1], ['Đoạn văn dài', 1]]],
  LISTENING: [[['Hiểu nhiệm vụ', 1], ['Hiểu điểm chính', 1]], [['Diễn đạt lời nói', 1], ['Hiểu khái quát', 1]]],
};
function smallSource(extra = []) {
  lessonNumber = 0;
  return { lessons: [
    readingLesson('Đoạn văn ngắn'), readingLesson('Đoạn văn ngắn'), readingLesson('Đoạn văn trung bình', { questions: 3 }),
    readingLesson('Tìm kiếm thông tin', { questions: 2 }), readingLesson('Đoạn văn dài', { questions: 4 }),
    listeningLesson('Hiểu nhiệm vụ'), listeningLesson('Hiểu điểm chính'), listeningLesson('Hiểu điểm chính'),
    listeningLesson('Diễn đạt lời nói', { prompt: '何と言いますか。' }), listeningLesson('Hiểu khái quát'), ...extra,
  ] };
}

test('reading passage stops before the first question and the translation is split per question', () => {
  lessonNumber = 0;
  const parsed = parseReading(readingLesson('Đoạn văn ngắn', { questions: 2 }).content.noi_dung);
  assert.deepEqual(parsed.passage, ['本文1です。長い文章がここにあります。', '二行目の本文です。']);
  assert.deepEqual(parsed.passageTranslation, ['本文1です。', 'Đây là đoạn văn.']);
  assert.equal(parsed.questionTranslations.length, 2);
  assert.match(parsed.questionTranslations[1].join('\n'), /Câu hỏi 2/);
});

test('a notice-style passage keeps its own bracketed title', () => {
  const parsed = parseReading(['【ABC英会話教室】', '（10月～3月）', '【場所】 ABCセンター', '問1 正しいのはどれか。', '１ あ',
    'Dịch & Giải thích', '【ABC英会話教室】', 'Lớp hội thoại'].join('\n'));
  assert.deepEqual(parsed.passage, ['【ABC英会話教室】', '（10月～3月）', '【場所】 ABCセンター']);
});

test('an information-search passage keeps the notice that follows the questions, without the questions', () => {
  const parsed = parseReading(['【】', '問題4-1', 'おばあさんは図書館に行きます。', '問1 ビデオを楽しめるのはどこか。', '１ 東図書館',
    '２ 西図書館', '問2 お昼も食べられるのはどこか。', '１ 東図書館', '２ 西図書館', '■図書館情報（〇は利用できます）', '東図書館', '〇',
    'Dịch & Giải thích', 'おばあさんは図書館に行きます。', 'Bà đi thư viện.', '問1 ビデオを楽しめるのはどこか。', 'Có thể xem video ở đâu?',
    '１ 東図書館', '1 Thư viện phía Đông', '問2 お昼も食べられるのはどこか。', 'Ăn trưa ở đâu?', '１ 東図書館', '1 Thư viện phía Đông',
    '■図書館情報（〇は利用できます）', 'Thông tin thư viện'].join('\n'));
  assert.deepEqual(parsed.passage, ['おばあさんは図書館に行きます。', '■図書館情報（〇は利用できます）', '東図書館', '〇']);
  assert.deepEqual(parsed.passageTranslation, ['おばあさんは図書館に行きます。', 'Bà đi thư viện.', '■図書館情報（〇は利用できます）', 'Thông tin thư viện']);
  assert.deepEqual(parsed.questionTranslations[1], ['問2 お昼も食べられるのはどこか。', 'Ăn trưa ở đâu?', '１ 東図書館', '1 Thư viện phía Đông']);
});

test('options numbered with a full-width dot never stay in the passage or the passage translation (source L066)', () => {
  const parsed = parseReading(['【】', '足の長さを測りましょう。', '問1 上の文の内容と違うものはどれか。', '１ 白い紙の上に立つ。', '２ 両足をそろえる。',
    '３．かかとに印をつける。', '４．親指に印をつける。', 'Dịch & Giải thích', '足の長さを測りましょう。', 'Hãy đo chiều dài bàn chân.',
    '問1 上の文の内容と違うものはどれか。', 'Điều nào khác với nội dung?', '１ 白い紙の上に立つ。', '1. Đứng trên giấy trắng.', '３．かかとに印をつける。',
    '3. Đánh dấu ở gót chân.'].join('\n'));
  assert.deepEqual(parsed.passage, ['足の長さを測りましょう。']);
  assert.deepEqual(parsed.passageTranslation, ['足の長さを測りましょう。', 'Hãy đo chiều dài bàn chân.']);
  assert.equal(parsed.questionTranslations[0].length, 6);
});

test('an item whose passage still shows one of its own numbered options is held, never graded', () => {
  const source = smallSource();
  const leak = source.lessons[0];
  leak.content = { ...leak.content, noi_dung: leak.content.noi_dung.replace('二行目の本文です。', '２．い') };
  const section = buildSection(source, HASH, SMALL_PLAN);
  assert.ok(section.held.some((h) => h.source_id === leak.source_id && /option text/.test(h.reason)), JSON.stringify(section.held));
  assert.ok(section.exercises.every((e) => e.source_id !== leak.source_id));
});

test('a rebuild keeps every chapter slot on its source and replaces only a newly unusable item in place', () => {
  const first = buildSection(smallSource(), HASH, SMALL_PLAN);
  const source = smallSource();
  const pointIndex = source.lessons.findIndex((l) => l.source_id === first.exercises.find((e) => e.chapter === 2
    && e.skill === 'LISTENING' && e.slot === 1).source_id);
  source.lessons[pointIndex] = { ...source.lessons[pointIndex], questions: [{ ...source.lessons[pointIndex].questions[0],
    content: { ...source.lessons[pointIndex].questions[0].content, answer_uncertain: true } }] };
  const rebuilt = buildSection(source, HASH, SMALL_PLAN, first);
  const slot = (s, c, k, p) => s.exercises.find((e) => e.chapter === c && e.skill === k && e.slot === p);
  for (const e of first.exercises) {
    if (e.chapter === 2 && e.skill === 'LISTENING' && e.slot === 1) continue;
    assert.equal(slot(rebuilt, e.chapter, e.skill, e.slot).source_id, e.source_id, `${e.source_group} moved`);
  }
  const replaced = slot(rebuilt, 2, 'LISTENING', 1);
  assert.notEqual(replaced.source_id, slot(first, 2, 'LISTENING', 1).source_id);
  assert.equal(replaced.type, 'Hiểu điểm chính', 'replaced by a reserve item of the same type');
  const noSpare = smallSource();
  const taskIndex = noSpare.lessons.findIndex((l) => l.content.unit === 'Hiểu nhiệm vụ');
  noSpare.lessons[taskIndex] = { ...noSpare.lessons[taskIndex], questions: [{ ...noSpare.lessons[taskIndex].questions[0], answer_check: 'HOLD_ANSWER_MAPPING' }] };
  assert.throws(() => buildSection(noSpare, HASH, SMALL_PLAN, first), /no usable replacement.*Hiểu nhiệm vụ/);
});

test('listening keeps the Japanese script, the in-text key and the bilingual translation separately', () => {
  lessonNumber = 0;
  const parsed = parseListening(listeningLesson('Hiểu nhiệm vụ').content.noi_dung);
  assert.equal(parsed.key, 3);
  assert.equal(parsed.prompt, '女の人はこのあと何をしますか。');
  assert.ok(parsed.script.includes('Ⓜ：どうする？'));
  assert.ok(!parsed.script.some((line) => /正解/.test(line)), 'the answer never leaks into the script');
  assert.deepEqual(parsed.translation, ['男の人と女の人が話しています。', 'Người đàn ông và người phụ nữ đang nói chuyện.']);
});

test('two reading and two listening exercises per chapter, easy to hard, with stable source codes', () => {
  const section = buildSection(smallSource(), HASH, SMALL_PLAN);
  assert.equal(section.counts.chapters, 2);
  const byChapter = (skill, chapter) => section.exercises.filter((e) => e.skill === skill && e.chapter === chapter);
  for (const chapter of [1, 2]) {
    assert.equal(byChapter('READING', chapter).length, 2);
    assert.equal(byChapter('LISTENING', chapter).length, 2);
  }
  assert.deepEqual(byChapter('READING', 1).map((e) => e.type), ['Đoạn văn ngắn', 'Tìm kiếm thông tin']);
  assert.deepEqual(byChapter('READING', 2).map((e) => e.type), ['Đoạn văn trung bình', 'Đoạn văn dài']);
  assert.deepEqual(byChapter('LISTENING', 2).map((e) => e.type), ['Hiểu điểm chính', 'Hiểu khái quát']);
  const long = byChapter('READING', 2)[1];
  assert.deepEqual(long.questions.map((q) => q.source_code), [1, 2, 3, 4].map((n) => `LP-OWNED-SPEED-N3-C02-READ-P2-Q00${n}`));
  assert.match(long.questions[0].explanation, /Đây là đoạn văn/, 'the passage translation goes with the first question');
  assert.doesNotMatch(long.questions[1].explanation, /Đây là đoạn văn/);
  const clip = byChapter('LISTENING', 1)[0];
  assert.equal(clip.questions[0].source_code, 'LP-OWNED-SPEED-N3-C01-LISTEN-P1-Q001');
  assert.equal(clip.audio_url, '/uploads/content-audio/vnjpclub/N3/speed-n3-nghe-hieu/9.mp3');
  assert.equal(clip.questions[0].prompt, '女の人はこのあと何をしますか。', 'a blank source prompt takes the spoken question');
  assert.equal(section.reserve.length, 2, 'unused valid items stay in reserve: 1 short passage + 1 point clip');
  assert.equal(section.smart_test_eligible, false);
  assert.equal(section.review_status, 'CHUA_DUYET');
});

test('a question whose key disagrees with the script or is held is never graded and the plan fails loudly', () => {
  const held = smallSource();
  held.lessons[5] = { ...held.lessons[5], questions: [{ ...held.lessons[5].questions[0], answer_check: 'HOLD_ANSWER_MAPPING' }] };
  assert.throws(() => buildSection(held, HASH, SMALL_PLAN), /Hiểu nhiệm vụ.*1 needed, 0 usable/);
  lessonNumber = 0;
  const disagree = smallSource();
  disagree.lessons[5] = listeningLesson('Hiểu nhiệm vụ', { key: 3, textKey: 2 });
  assert.throws(() => buildSection(disagree, HASH, SMALL_PLAN), /Hiểu nhiệm vụ.*1 needed, 0 usable/);
});

test('a spare item replaces an unusable one of the same type and the unusable one is reported as held', () => {
  const source = smallSource();
  source.lessons[0] = { ...source.lessons[0], questions: [{ ...source.lessons[0].questions[0],
    content: { ...source.lessons[0].questions[0].content, answer_uncertain: true } }] };
  const section = buildSection(source, HASH, SMALL_PLAN);
  assert.equal(section.exercises.find((e) => e.chapter === 1 && e.skill === 'READING').source_id, 'SPEED-N3-L002-RDG-001');
  assert.equal(section.held.length, 1);
  assert.match(section.held[0].reason, /answer/);
});

test('the real canonical source fills 27 chapters with 54 passages and 54 clips, holding only reviewed problems', async () => {
  const data = await readLearningContent(ROOT);
  const section = buildSection(data.sections.jlpt_comprehension_n5_n3, data.section_sha256.jlpt_comprehension_n5_n3, DEFAULT_PLAN);
  assert.equal(section.counts.chapters, 27);
  assert.equal(section.counts.reading_passages, 54);
  assert.equal(section.counts.listening_clips, 54);
  assert.equal(section.counts.listening_questions, 54);
  assert.ok(section.counts.reading_questions >= 100, String(section.counts.reading_questions));
  const held = section.held.map((h) => h.source_id).sort();
  for (const id of Object.keys(REVIEW_HOLDS)) assert.ok(held.includes(id), `${id} must be held`);
  assert.ok(section.exercises.every((e) => !REVIEW_HOLDS[e.source_id]), 'a reviewed problem is never graded');
  assert.equal(section.reserve.filter((r) => r.skill === 'READING').length, 2);
  assert.equal(section.reserve.filter((r) => r.skill === 'LISTENING').length, 12);
  for (const exercise of section.exercises) {
    for (const q of exercise.questions) {
      assert.ok(q.options.length >= 3 && q.options.length <= 4, q.source_code);
      assert.equal(q.options[q.correct_option - 1], q.answer_text, q.source_code);
      assert.ok(q.prompt.trim(), q.source_code);
    }
  }
});
