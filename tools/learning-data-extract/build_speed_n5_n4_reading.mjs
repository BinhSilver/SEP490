// [JLAS-W0108] OD-08 (owner 2026-10-24): Speed Master N5/N4 reading passages join the Sekai/Minna chapters as extra reading
// — N5 into chapters 13–25, N4 into chapters 26–50 — in the book's own easy → hard order. Questions go through the same
// checks as Speed Master N3 (single answer, key = correct-option flag, no option leaked into the passage); passages that
// need a picture are held. The 12 N4 passages still missing against the 48-passage blueprint are a separate, later step.
//   node tools/learning-data-extract/build_speed_n5_n4_reading.mjs . [--check] [--fresh]
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readLearningContent, writeLearningSection } from './learning_content_store.mjs';
import { checkQuestion } from './build_speed_n3_comprehension.mjs';

const SECTION = 'jlpt_speed_n5_n4_reading';
const SOURCE_SECTION = 'jlpt_comprehension_n5_n3';
export const CHAPTERS = Object.freeze({ N5: [13, 25], N4: [26, 50] });
const BOOK = Object.freeze({ N5: 'Speed Master N5', N4: 'Speed Master N4' });

const clean = (value) => String(value ?? '').replace(/ /g, ' ').trim();
const lines = (text) => String(text ?? '').split(/\r?\n/).map(clean).filter(Boolean);
const compact = (value) => clean(value).normalize('NFKC').replace(/\s+/g, '');
const JAPANESE = /[぀-ヿ一-鿿]/;
const OPTION_MARK = /^[1-4１-４](?:\s|[.．、)）]\s*)/;
/** Choices that are pictures ("絵１", "図2") or a passage that points at one cannot be answered from the text. */
const PICTURE_OPTION = /^(?:絵|図|イラスト|写真)\s*[0-9０-９]*$/;
/** "aとb", "c" — letters naming spots on a picture. */
const PICTURE_LABELS = /^[a-dA-D](?:と[a-dA-D])*$/;
const PICTURE_TEXT =/(?:絵|図|イラスト|写真|地図)を\s*(?:見て|みて)|下の(?:絵|図|イラスト|写真|地図)/;

/**
 * D3-07 (owner 2026-10-24) — passages held as "flattened table / information search", reviewed one by one 2026-10-26.
 * `passage` replaces the parsed lines (the export ran table cells together, dropped a line, or kept the question in the
 * passage); `options` puts back digits the export cut ("週間前" → "1週間前"); `fixes` names the source typos corrected.
 * `translation` is the page's own VNJPClub translation re-aligned to those lines (none is written by J-LAS).
 * `hold` keeps a passage held with the reviewed reason; `chapter` spreads releases that would pile into one chapter.
 * The source section is never edited.
 */
export const REVIEWED_LAYOUTS = Object.freeze({
  'SPEED-N5-L085-RDG-001': { why: 'không phải bảng; bộ tách lấy nhầm câu hỏi vào đoạn văn',
    passage: ['こんな調査があります。', '13歳から 29歳の若い人に、「自分が好きですか」と聞きました。',
      'アメリカやドイツでは「はい」とこたえた人が多かったですが、日本では、「はい」とこたえた人が少なかったです。',
      'でも、日本では、「はい」とこたえたい人が「いいえ」とこたえると思います。', '日本では、「自分が好き」と言わないほうがいいからです。'], translation: [
    ['こんな調査があります。', 'Có cuộc khảo sát như thế này.'],
    ['13歳から 29歳の若い人に、「自分が好きですか」と聞きました。', 'Đã đặt câu hỏi người trẻ từ 13 tuổi đến 29 tuổi rằng "Bạn có yêu bản thân mình không?"'],
    ['アメリカやドイツでは「はい」とこたえた人が多かったですが、日本では、「はい」とこたえた人が少なかったです。', 'Ở Mỹ và Đức người trả lời "Có" thì nhiều, thế nhưng ở Nhật Bản người trả lời là "Có" thì ít.'],
    ['でも、日本では、「はい」とこたえたい人が「いいえ」とこたえると思います。', 'Thế nhưng, ở Nhật tôi nghĩ rằng người muốn trả lời là "Có" nhưng sẽ trả lời là "Không".'],
    ['日本では、「自分が好き」と言わないほうがいいからです。', 'Bởi vì ở Nhật không nên nói "Tôi yêu bản thân mình".']] },
  'SPEED-N5-L096-RDG-001': { hold: 'source table is inconsistent (7,524円 − 7,433円 ≠ 41円) and lost two company names' },
  'SPEED-N5-L100-RDG-001': { why: 'bỏ đề bài lẫn vào đầu đoạn văn',
    passage: ['カクさん、勉強会を、いつしましょう。', '来週、月よう日の午前中は時間がありますが、午後はいそがしいです。',
      '火よう日は午後、かいぎがありますが、3時に終わります。', '水よう日と木よう日は、東京にいません。', '金よう日は一日だいじょうぶです。',
      'カクさんはどうですか。', 'まり'], translation: [
    ['カクさん、勉強会を、いつしましょう。', 'Kaku này, chúng ta học nhóm khi nào?'],
    ['来週、月よう日の午前中は時間がありますが、午後はいそがしいです。', 'Tuần sau, sáng thứ Hai tôi có thời gian, nhưng buổi chiều thì bận.'],
    ['火よう日は午後、かいぎがありますが、3時に終わります。', 'Chiều thứ Ba tôi có cuộc họp, nhưng kết thúc lúc 3 giờ.'],
    ['水よう日と木よう日は、東京にいません。', 'Thứ Tư và thứ Năm thì tôi không ở Tokyo.'],
    ['金よう日は一日だいじょうぶです。', 'Thứ Sáu thì cả ngày đều được.'],
    ['カクさんはどうですか。', 'Còn anh Kaku thì như thế nào?']] },
  'SPEED-N5-L101-RDG-001': { hold: 'the question lost its lead-in (who すずきさん is, the child\'s age): not answerable from the page' },
  'SPEED-N4-L073-RDG-001': { why: 'tách lại cột "mục" và "nhận xét" bị dính liền',
    passage: ['これは、アルさんのスピーチについての先生からのコメントです。', 'スピーチタイトル：わたしのねこ「ミー」', '発表者：ダルディン・アル',
      '内容：アルさんのねこのことがよくわかる話でした。長さもちょうどよかったです。',
      '日本語：ぶんぽうはただしかったです。かんたんなことばをつかったので、みんなにわかりやすかったです。',
      '話し方：少しはやかったです。メモを急いで読みましたね。メモを見ない方がいいです。声は大きくてよかったです。',
      'パワーポイント：字が多くなくて、見やすかったです。'], translation: [
    ['これは、アルさんのスピーチについての先生からのコメントです。', 'Đây là bình luận từ giáo viên về bài phát biểu của Al.'],
    ['スピーチタイトル：わたしのねこ「ミー」', 'Tiêu đề bài hùng biện: Con mèo của tôi "Mi"'],
    ['発表者：ダルディン・アル', 'Người phát biểu: Dardin Al'],
    ['内容：アルさんのねこのことがよくわかる話でした。長さもちょうどよかったです。', 'Nội dung: Là một câu chuyện có thể hiểu rõ được về con mèo của Al. Có độ dài vừa đủ.'],
    ['日本語：ぶんぽうはただしかったです。かんたんなことばをつかったので、みんなにわかりやすかったです。', 'Tiếng Nhật: Đúng ngữ pháp. Dùng từ đơn giản nên mọi người dễ hiểu.'],
    ['話し方：少しはやかったです。メモを急いで読みましたね。メモを見ない方がいいです。声は大きくてよかったです。', 'Cách nói: Hơi nhanh một chút. Đã đọc vội tờ ghi chú nhỉ. Không nên nhìn vào tờ ghi chú. Giọng to rõ ràng.'],
    ['パワーポイント：字が多くなくて、見やすかったです。', 'Powerpoint: Chữ không nhiều và dễ nhìn.']] },
  'SPEED-N4-L074-RDG-001': { chapter: 32, why: 'không phải bảng; tách câu theo bản dịch nguồn',
    passage: ['東京で「そのしごと、自分がします。」と言ったら、「そのしごと、わたしがします。」という意味です。',
      'でも、先週大阪に行ったとき、大阪の友達が「自分、晩ごはんどうする？」とききました。', 'わたしはどうして自分の晩ごはんのことをわたしにきくのかわかりませんでした。',
      'あとで、大阪では、会話で「自分」と言ったら、「あなた」という意味になると聞きました。', '日本語は難しいですね。'],
    fixes: ['大阪っでは → 大阪では'], translation: [
    ['東京で「そのしごと、自分がします。」と言ったら、「そのしごと、わたしがします。」という意味です。', 'Ở Tokyo nếu nói "そのしごと、自分がします" có nghĩa là "tôi sẽ làm công việc đó."'],
    ['でも、先週大阪に行ったとき、大阪の友達が「自分、晩ごはんどうする？」とききました。', 'Tuy nhiên, tuần trước lúc mà tôi đi Osaka, người bạn ở Osaka hỏi tôi rằng "自分、晩ごはんどうする？"'],
    ['わたしはどうして自分の晩ごはんのことをわたしにきくのかわかりませんでした。', 'Tôi không hiểu vì sao cậu ấy lại hỏi tôi về bữa tối của chính mình.'],
    ['あとで、大阪では、会話で「自分」と言ったら、「あなた」という意味になると聞きました。', 'Sau đó, tôi nghe nói rằng ở Osaka, nếu nói "自分" trong hội thoại thì nó mang nghĩa là "bạn".']] },
  'SPEED-N4-L097-RDG-001': { hold: 'question 1 does not fit the passage: two adults and an 8-year-old pay 1,500円, not among 800/500/250/200円 (source key 500円)' },
  'SPEED-N4-L098-RDG-001': { chapter: 47, why: 'phương án bị mất số đầu (1週間前, 4時ごろ)',
    options: { 0: ['1週間前までに体育館を予約します。', '4時ごろ体育館に行って、人がいたら、終わるのを待ちます。',
      'その日の3時までに体育館を予約します。', '4時ごろ体育館に行って、人がいたら、いっしょに体育館を使います。'] } },
  'SPEED-N4-L099-RDG-001': { chapter: 48, why: 'bỏ đường kẻ',
    passage: ['【町に子どものプールを！】', 'この町には、おとなのプールしかありません。', '子どものために、小さいプールをつくりましょう！',
      '町のお金だけでは、あまりいいプールができません。', 'みんなのお金で、もっとすてきなプールをつくりましょう！', 'お金を払ったら以下のプレゼントがあります！',
      '（1）５千円：水泳用のぼうし', '（2）１万円：（1）と、ネームプレート（プールの建物に、あなたの名前を飾ります）',
      '（3）５万円：（1）と（2）、子どもプールのチケット（5回、使えます）', '（4）１０万円：（1）から（3）と、子どものプールのチケット（一年、使えます）',
      '※プールは3年後にできる予定です。'] },
  'SPEED-N4-L100-RDG-001': { chapter: 49, why: 'sửa 5 lỗi gõ của nguồn', fixes: ['冷たくなるまで持って → 冷たくなるまで待って',
    'そばにい置かない → そばに置かない', '原因にないます → 原因になります',
    'カメラ買った店 → カメラを買った店', '使うのすぐにやめて → 使うのをすぐにやめて'],
    passage: ['【カメラの使い方】', '故障？と思ったときは', '◆ 電池を入れても、電源が入らない', '一回、電池をとって、もう一度入れてください。',
      '電池が冷たいとき、動かない場合があります。', '◆ 写真を撮ることができない',
      '長い時間使ったとき、カメラが熱くて、ボタンが押せないことがあります。冷たくなるまで待ってください。', '安全に使うために',
      '◆ 電池は、火のそばに置かない', '火事やけがの原因になります。', '◆ ぬれた手でカメラを使わない', '故障や、けがの原因になります。',
      '◆ カメラから電池がとれない', '電池を入れたまま、カメラを買った店に持って行ってください。', '◆ 変な音が鳴ったり、においがしたりする',
      '使うのをすぐにやめてください。店ではなく、サービスセンター（0120-00xx-xxxx）に連絡ください。'] },
});

const QUESTION_PREFIX = /^問\s*[0-9０-９]+\s*[:：.．、]?\s*/;
const promptKey = (value) => compact(String(value ?? '').replace(QUESTION_PREFIX, ''));
/** "東京オリンピック: Thế vận hội Tokyo" — a vocabulary gloss printed under the passage; it belongs to the explanation. */
const GLOSS = /^[^:：]{1,30}[:：]\s*[^\u3040-\u30ff\u4e00-\u9fff]+$/;
/** A head line with Vietnamese in it (a gloss, a "(Chú ý)" note) is never passage text. */
const VIETNAMESE = /[ăâđêôơưạảấầẩẫậắằẳẵặẹẻẽếềểễệỉịọỏốồổỗộớờởỡợụủứừửữựỳỵỷỹ]/i;

/** "☞ Dịch & Đáp Án" (N5) / "Dịch & Giải thích" (N4): the translation and answers start here. */
const TRANSLATION_HEADING = /^[^\S\r\n]*(?:☞\s*)?Dịch\s*&\s*(?:Giải\s*thích|Đáp\s*[ÁáAa]n).*$/im;

/**
 * Unnumbered option block right under a prompt: the export cut a leading number off an option that starts with one
 * ("10月で…" stored as "0月で…", read as option mark 1). Each line is the stored option or digits + the stored option,
 * at least one is exact; the page line wins.
 */
function repairedOptions(block, options) {
  if (block.length < options.length) return null;
  const keys = block.slice(0, options.length).map(compact);
  let exact = 0;
  for (let i = 0; i < options.length; i++) {
    const want = compact(options[i]);
    if (keys[i] === want) { exact++; continue; }
    if (!(want && keys[i].endsWith(want) && /^[0-9]+$/.test(keys[i].slice(0, keys[i].length - want.length)))) return null;
  }
  return exact && exact < options.length ? block.slice(0, options.length) : null;
}

/**
 * Speed Master N5/N4 page: passage (with the question, its options and vocabulary glosses mixed in), then the translation
 * heading with the passage translated line by line (Japanese, Vietnamese, …) and the translated questions and options
 * (after a "&&&" rule on N5, from the first "問n" line on N4).
 * Kept: the passage lines only. Question lines, the options under them, any line equal to an option and glosses are taken
 * out; glosses and the Vietnamese lines become the explanation.
 */
export function parseSpeedPage(text, questions) {
  const source = String(text ?? '');
  const heading = TRANSLATION_HEADING.exec(source);
  const head = heading ? source.slice(0, heading.index) : source;
  const tail = heading ? source.slice(heading.index + heading[0].length) : '';
  const prompts = new Map(questions.map((q, index) => [promptKey(q.prompt), index]));
  const options = new Set(questions.flatMap((q) => q.options.map(compact)));
  const headLines = lines(head);
  const passage = [];
  const glosses = [];
  const repaired = new Map();
  let inQuestion = false;
  headLines.forEach((line, at) => {
    if (line === '【】' || /^問題\s*[0-9０-９]+(-[0-9０-９]+)?$/.test(line)) return;
    if (prompts.has(promptKey(line))) {
      const index = prompts.get(promptKey(line));
      const fixed = repairedOptions(headLines.slice(at + 1), questions[index].options);
      if (fixed) { repaired.set(index, fixed); fixed.forEach((o) => options.add(compact(o))); }
      inQuestion = true;
      return;
    }
    if (inQuestion && OPTION_MARK.test(line)) return;
    if (options.has(compact(line)) || options.has(compact(line.replace(OPTION_MARK, '')))) return;
    if (GLOSS.test(line) || VIETNAMESE.test(line)) { glosses.push(line); return; }
    inQuestion = false;
    passage.push(line);
  });
  const rule = /-{3,}\s*&&&\s*-{3,}/.exec(tail);
  const firstQuestion = /^\s*問\s*[0-9０-９]/m.exec(tail);
  let promptAt = -1;
  for (let at = 0; promptAt < 0 && at < tail.length;) {
    const end = tail.indexOf('\n', at);
    if (prompts.has(promptKey(tail.slice(at, end < 0 ? tail.length : end)))) promptAt = at;
    at = end < 0 ? tail.length : end + 1;
  }
  const cut = rule ? rule.index : firstQuestion ? firstQuestion.index : promptAt >= 0 ? promptAt : tail.length;
  const after = rule ? rule.index + rule[0].length : cut;
  return { passage, glosses, repaired, passageTranslation: translationPairs(tail.slice(0, cut)),
    questionTranslations: questionTranslations(tail.slice(after), [...prompts.keys()]) };
}

/** Japanese line, then its Vietnamese line(s): [{ja, vi}] like Minna's context_json.passage_translation. */
function translationPairs(part) {
  const pairs = [];
  for (const line of lines(part)) {
    if (JAPANESE.test(line)) pairs.push({ ja: line, vi: '' });
    else if (pairs.length) pairs.at(-1).vi = clean(`${pairs.at(-1).vi} ${line}`);
  }
  return pairs.filter((pair) => pair.vi);
}

/**
 * Translated questions: "問n" (alone, or followed by the prompt) or the Japanese prompt starts a question; its first
 * Vietnamese line is the prompt, each later one the translation of the Japanese option above it.
 */
function questionTranslations(part, promptKeys) {
  const out = promptKeys.map(() => []);
  let index = -1;
  let awaitingPrompt = false;
  let option = 0;
  for (const line of lines(part)) {
    if (/&&&/.test(line)) continue;
    if (JAPANESE.test(line)) {
      const marker = QUESTION_PREFIX.exec(line);
      const known = promptKeys.indexOf(promptKey(line));
      if (marker || known >= 0 || index < 0) {
        const numbered = marker ? Number(/[0-9０-９]+/.exec(marker[0])[0].normalize('NFKC')) - 1 : -1;
        index = known >= 0 ? known : numbered >= 0 ? numbered : index + 1;
        if (index >= out.length) return out.map(() => []); // more questions than the source has: keep no translation
        awaitingPrompt = Boolean(marker) && !clean(line.replace(QUESTION_PREFIX, ''));
        option = 0;
        if (!awaitingPrompt) out[index].push({ kind: 'prompt', vi: '' });
        continue;
      }
      if (awaitingPrompt) { awaitingPrompt = false; out[index].push({ kind: 'prompt', vi: '' }); continue; }
      option++;
      out[index].push({ kind: 'option', number: option, vi: '' });
      continue;
    }
    const last = index >= 0 ? out[index].at(-1) : null;
    if (last) last.vi = clean(`${last.vi} ${line}`);
  }
  return out.map((parts) => parts.filter((p) => p.vi).map((p) => p.kind === 'prompt' ? `Câu hỏi: ${p.vi}` : `${p.number}. ${p.vi}`));
}

/** One source passage; pictures and information-search layouts (tables flattened by the export) are held for review. */
export function speedReadingItem(lesson) {
  const questions = lesson.questions ?? [];
  if (!questions.length) return { reason: 'no questions' };
  const options = questions.flatMap((q) => (q.options ?? []).map((o) => clean(o.content?.noi_dung)));
  if (options.some((o) => PICTURE_OPTION.test(o))) return { reason: 'answer choices are pictures' };
  if (options.some((o) => PICTURE_LABELS.test(compact(o)))) return { reason: 'answer choices are picture labels' };
  for (const q of questions) {
    const stems = (q.options ?? []).map((o) => compact(o.content?.noi_dung).replace(/[0-9]+$/, ''));
    if (stems.length > 1 && stems[0] && stems.every((s, i) => s === stems[0] && compact(q.options[i].content?.noi_dung) !== s)) {
      return { reason: 'answer choices are numbered pictures' };
    }
  }
  const reviewed = REVIEWED_LAYOUTS[lesson.source_id];
  if (reviewed?.hold) return { reason: `${reviewed.hold} (reviewed 2026-10-26)` };
  if (!reviewed && clean(lesson.content?.unit) === 'Tìm kiếm thông tin') {
    return { reason: 'information-search layout (flattened table) needs review' };
  }
  const out = [];
  for (const q of questions) {
    const problem = checkQuestion(q, null);
    if (problem) return { reason: problem };
    const prompt = clean(q.content.cau_hoi);
    if (!prompt) return { reason: 'question prompt missing' };
    out.push({ prompt, options: q.options.map((o) => clean(o.content.noi_dung)), correct_option: q.content.dap_an,
      giai_thich: clean(q.content.giai_thich), question_id: q.content.ma ?? null });
  }
  const parsed = parseSpeedPage(lesson.content?.noi_dung, out);
  if (reviewed?.passage) parsed.passage = reviewed.passage;
  for (const [index, options] of Object.entries(reviewed?.options ?? {})) parsed.repaired.set(Number(index), options);
  if (parsed.passage.join('').length < 20) return { reason: 'passage not found' };
  if (parsed.passage.some((line) => PICTURE_TEXT.test(line))) return { reason: 'passage points at a picture' };
  // A table or form flattened by the export translates as blocks, not line by line: its layout needs review first.
  const translated = parsed.passageTranslation.map((pair) => compact(pair.ja)).join('').length;
  if (!reviewed && translated < compact(parsed.passage.join('')).length * 0.8) {
    return { reason: 'table layout flattened by the export needs review' };
  }
  // A reviewed layout keeps only the source translations that still match one of its lines.
  const keys = new Set(parsed.passage.map(compact));
  return {
    text: parsed.passage,
    passage_translation: reviewed?.translation ? reviewed.translation.map(([ja, vi]) => ({ ja, vi }))
      : reviewed ? parsed.passageTranslation.filter((pair) => keys.has(compact(pair.ja))) : parsed.passageTranslation,
    ...(reviewed ? { layout_reviewed: { on: '2026-10-26', why: reviewed.why, ...(reviewed.fixes ? { fixes: reviewed.fixes } : {}) } } : {}),
    questions: out.map(({ giai_thich, prompt, ...q }, index) => ({ ...q,
      prompt: clean(prompt.replace(QUESTION_PREFIX, '')) || prompt,
      ...(parsed.repaired.has(index) ? { options: parsed.repaired.get(index), options_repaired_from_page: true } : {}),
      explanation: [
        ...(parsed.questionTranslations[index] ?? []),
        ...(index === 0 && parsed.glosses.length ? ['Từ vựng:', ...parsed.glosses] : []),
        giai_thich].filter(Boolean).join('\n') || null })),
  };
}

const lessonNumber = (lesson) => Number(/-L(\d+)-/.exec(lesson.source_id ?? '')?.[1] ?? Number.MAX_SAFE_INTEGER);
const groupCode = (level, chapter, slot) => `LP-OWNED-SPEED-${level}-L${String(chapter).padStart(2, '0')}-READ-P${slot}`;

/**
 * {@code previous} (the stored section) freezes passage → chapter/slot: a rebuild keeps every placed passage on its group
 * and drops one that became unusable (no re-dealing under the same codes); new usable passages wait in reserve.
 */
export function buildSection(source, sourceSectionHash, previous = null) {
  if (!/^[a-f0-9]{64}$/.test(sourceSectionHash ?? '') || !Array.isArray(source?.lessons)) {
    throw new Error('The canonical comprehension source and its SHA-256 are required.');
  }
  const exercises = [];
  const held = [];
  const reserve = [];
  for (const level of ['N5', 'N4']) {
    const pool = source.lessons.filter((l) => l.level === level && l.kind === 'READING' && l.content?.sach === BOOK[level])
      .sort((a, b) => lessonNumber(a) - lessonNumber(b) || String(a.source_id).localeCompare(String(b.source_id)));
    const items = new Map(pool.map((l) => [l.source_id, speedReadingItem(l)]));
    const usable = pool.filter((l) => !items.get(l.source_id).reason);
    const [first, last] = CHAPTERS[level];
    const span = last - first + 1;
    const placed = new Map();
    const before = (previous?.exercises ?? []).filter((e) => e.level === level);
    if (before.length) {
      for (const old of before) if (usable.some((l) => l.source_id === old.source_id)) placed.set(old.source_id, old);
      // D3-07: a passage released by REVIEWED_LAYOUTS joins the chapter of the placed passage just before it in the
      // book, after that chapter's slots; every earlier placement stays where it was. Any other new passage waits in reserve.
      for (const lesson of usable) {
        if (placed.has(lesson.source_id) || !REVIEWED_LAYOUTS[lesson.source_id]) continue;
        const earlier = usable.filter((l) => placed.has(l.source_id) && lessonNumber(l) <= lessonNumber(lesson));
        const anchor = earlier.length ? earlier[earlier.length - 1] : usable.find((l) => placed.has(l.source_id));
        if (!anchor) continue;
        const chapter = REVIEWED_LAYOUTS[lesson.source_id].chapter ?? placed.get(anchor.source_id).chapter;
        const slot = Math.max(0, ...[...placed.values()].filter((p) => p.chapter === chapter).map((p) => p.slot)) + 1;
        placed.set(lesson.source_id, { chapter, slot });
      }
    } else {
      const perChapter = new Map();
      usable.forEach((lesson, index) => {
        const chapter = first + Math.floor(index * span / usable.length);
        const slot = (perChapter.get(chapter) ?? 0) + 1;
        perChapter.set(chapter, slot);
        placed.set(lesson.source_id, { chapter, slot });
      });
    }
    for (const lesson of pool) {
      const item = items.get(lesson.source_id);
      const entry = { level, source_id: lesson.source_id, type: clean(lesson.content.unit) };
      if (item.reason) { held.push({ ...entry, reason: item.reason }); continue; }
      const where = placed.get(lesson.source_id);
      if (!where) { reserve.push({ ...entry, questions: item.questions.length }); continue; }
      const group = groupCode(level, where.chapter, where.slot);
      exercises.push({
        source_group: group, level, chapter: where.chapter, slot: where.slot, type: entry.type,
        source_id: lesson.source_id, source_lesson_code: lesson.content.lesson_ma ?? null, source_url: lesson.content.url_nguon ?? null,
        title: `${BOOK[level]} · ${entry.type}`, text_lines: item.text, passage_translation: item.passage_translation,
        ...(item.layout_reviewed ? { layout_reviewed: item.layout_reviewed } : {}),
        questions: item.questions.map((q, i) => ({ ...q, source_code: `${group}-Q${String(i + 1).padStart(3, '0')}`,
          answer_text: q.options[q.correct_option - 1] })),
      });
    }
  }
  exercises.sort((a, b) => a.chapter - b.chapter || a.slot - b.slot);
  const count = (level) => exercises.filter((e) => e.level === level);
  return {
    kind: 'JLPT_N5_N4_SPEED_MASTER_READING',
    source_owner: 'vnjpclub',
    source_books: Object.values(BOOK),
    source_section: SOURCE_SECTION,
    source_section_sha256: sourceSectionHash,
    chapter_rule: 'Minna/Sekai chapter number: N5 1–25, N4 26–50; Speed Master N5 → 13–25, N4 → 26–50, book order',
    owner_approval: 'OD-08 2026-10-24: Speed Master N5 → chương 13–25, N4 → chương 26–50, dễ → khó theo sách; 12 bài N4 còn thiếu làm sau',
    publication: 'OWNER_AUTHORIZED_TEAM_SEED',
    study_eligible: true,
    quiz_eligible: false,
    smart_test_eligible: false,
    rights: 'OWNER_CONFIRMED_TEAM_DISTRIBUTION',
    distribution_authorization: 'JLAS_OWNER_CONFIRMED_TEAM_DISTRIBUTION_2026-10-24',
    review_status: 'CHUA_DUYET',
    label: 'Nguồn VNJPClub · Speed Master · chưa duyệt',
    counts: Object.fromEntries(['N5', 'N4'].map((level) => [level, {
      passages: count(level).length,
      questions: count(level).reduce((n, e) => n + e.questions.length, 0),
      chapters: new Set(count(level).map((e) => e.chapter)).size,
    }])),
    held,
    reserve,
    exercises,
  };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = path.resolve(process.argv[2] ?? '.');
  const data = await readLearningContent(root);
  const previous = process.argv.includes('--fresh') ? null : data.sections[SECTION] ?? null;
  const section = buildSection(data.sections[SOURCE_SECTION], data.section_sha256[SOURCE_SECTION], previous);
  if (process.argv.includes('--check')) {
    if (JSON.stringify(data.sections[SECTION]) !== JSON.stringify(section)) throw new Error(`${SECTION} is stale.`);
  } else {
    await writeLearningSection(root, SECTION, section);
  }
  console.log(JSON.stringify({ counts: section.counts, held: section.held.length, reserve: section.reserve.length }));
}
