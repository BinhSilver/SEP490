import { statSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readLearningSection, writeLearningSection } from './learning_content_store.mjs';

const clean = (value) => typeof value === 'string' ? value.trim() : '';
const escapeHtml = (value) => value.replaceAll('&', '&amp;').replaceAll('<', '&lt;')
  .replaceAll('>', '&gt;').replaceAll('"', '&quot;').replaceAll("'", '&#39;');

/** Create small, lesson-bound comprehension checks from exact Sekai dialogue/script/audio pairs. */
export function buildExercises(lessons, audioExists) {
  if (!Array.isArray(lessons) || typeof audioExists !== 'function') {
    throw new TypeError('N5 lesson rows and an exact audio-file checker are required.');
  }
  const result = [];
  const lessonCodes = new Set();
  for (const lesson of lessons) {
    if (lesson.level !== 'N5') continue;
    const lessonCode = clean(lesson.owned_lesson_code);
    if (!lessonCode.startsWith('LP-OWNED-SEKAI-N5-L') || lessonCodes.has(lessonCode)) {
      throw new Error(`Invalid or duplicate N5 lesson code: ${lessonCode || '(empty)'}`);
    }
    if (!Number.isInteger(lesson.source_lesson_number) || lesson.source_lesson_number < 1) {
      throw new Error(`${lessonCode}: source lesson number is invalid.`);
    }
    lessonCodes.add(lessonCode);
    const scenes = lesson.content?.hoiThoai?.canh;
    if (!Array.isArray(scenes) || scenes.length === 0) {
      throw new Error(`${lessonCode}: dialogue scenes are missing.`);
    }
    const sceneCodes = new Set();
    for (const scene of scenes) {
      const sceneCode = clean(scene.ma);
      if (!sceneCode || sceneCodes.has(sceneCode)) {
        throw new Error(`${lessonCode}: duplicate scene code ${sceneCode || '(empty)'}.`);
      }
      sceneCodes.add(sceneCode);
      const lines = scene.loi;
      if (!Array.isArray(lines) || lines.length < 4
          || lines.some((line) => !clean(line.ja) || !clean(line.vi))) {
        throw new Error(`${lessonCode}/${sceneCode}: bilingual dialogue needs at least four complete lines.`);
      }
      const options = [];
      for (const line of lines) {
        const meaning = clean(line.vi);
        if (!options.includes(meaning)) options.push(meaning);
        if (options.length === 4) break;
      }
      if (options.length !== 4 || options[0] !== clean(lines[0].vi)) {
        throw new Error(`${lessonCode}/${sceneCode}: needs four unique Vietnamese meanings.`);
      }
      const audioName = clean(lines[0].tieng?.['chuan-nu']);
      const audioFile = audioName ? `sekai-n5/audio/chuan-nu/${audioName}` : '';
      if (!audioName || path.basename(audioName) !== audioName || !audioExists(audioFile)) {
        throw new Error(`${lessonCode}/${sceneCode}: exact dialogue audio file is missing.`);
      }
      const correctOption = 1 + ((Number(lesson.source_lesson_number) + sceneCode.codePointAt(0)) % 4);
      const orderedOptions = [...options];
      const correctMeaning = orderedOptions.shift();
      orderedOptions.splice(correctOption - 1, 0, correctMeaning);
      const scenePassage = lines.map((line) => `<p>${escapeHtml(clean(line.ja))}</p>`).join('');
      const common = {
        lesson_code: lessonCode,
        jlpt_level: 'N5',
        scene_code: sceneCode,
        scene_title: clean(scene.ten) || clean(scene.mach_ten),
        source_file: lesson.source_file,
        source_sha256: lesson.source_sha256,
        provenance: 'Sekai task-owned source copy; dialogue Japanese/Vietnamese pairs and exact chuan-nu recording.',
        questions: [{
          prompt: 'Câu thoại đầu tiên có nghĩa là gì?',
          answer_text: correctMeaning,
          options: orderedOptions,
          correct_option: correctOption,
          explanation: `${clean(lines[0].ja)} — ${correctMeaning}`,
        }],
      };
      result.push({
        ...common,
        skill: 'READING',
        source_code: `${lessonCode}-READ-${sceneCode}`,
        passage_html: scenePassage,
        transcript_ja: null,
        audio_file: null,
        question_prompt: 'Đọc đoạn hội thoại và chọn nghĩa của câu thoại đầu tiên.',
      });
      result.push({
        ...common,
        skill: 'LISTENING',
        source_code: `${lessonCode}-LISTEN-${sceneCode}`,
        passage_html: `<p>${escapeHtml(clean(lines[0].ja))}</p>`,
        transcript_ja: clean(lines[0].ja),
        audio_file: audioFile,
        audio_url: `/uploads/content-audio/${audioFile}`,
        question_prompt: 'Nghe câu thoại và chọn nghĩa đúng.',
      });
    }
  }
  return result;
}

export async function build(root, audioRoot) {
  const file = path.join(root, 'data/learning-owned/learning-content.json');
  const data = JSON.parse(await readFile(file, 'utf8'));
  const section = data.sections?.jlpt_sekai_n5_n4;
  const lessons = section?.lessons;
  const absoluteAudioRoot = path.resolve(audioRoot);
  const exercises = buildExercises(lessons, (name) => {
    const candidate = path.resolve(absoluteAudioRoot, name);
    return candidate.startsWith(`${absoluteAudioRoot}${path.sep}`)
      && statSync(candidate, { throwIfNoEntry: false })?.isFile() === true;
  });
  if (exercises.length !== 100) throw new Error(`Expected 100 N5 skill exercises; got ${exercises.length}.`);
  return {
    kind: 'JLPT_SEKAI_N5_DIALOGUE_COMPREHENSION',
    source_section: 'jlpt_sekai_n5_n4',
    source_section_sha256: section.section_sha256,
    level: 'N5',
    source_language: 'ja-vi',
    publication: 'TASK_OWNED_DEMO',
    quiz_eligible: true,
    counts: { lessons: 25, scenes: 50, reading: 50, listening: 50 },
    exercises,
  };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = path.resolve(process.argv[2] ?? '.');
  const audioRoot = path.resolve(process.argv[3] ?? 'uploads/content-audio');
  const section = await build(root, audioRoot);
  if (process.argv.includes('--check')) {
    const existing = await readLearningSection(root, 'sekai_n5_comprehension');
    if (JSON.stringify(existing) !== JSON.stringify(section)) throw new Error('N5 comprehension mapping is stale.');
  } else {
    await writeLearningSection(root, 'sekai_n5_comprehension', section);
  }
  console.log(JSON.stringify(section.counts));
}
