// [JLAS-W0108] OD-D2-03 (owner 2026-10-23): every Minna listening fill-in check ("W-…") gets an explanation quoted from
// its own source excerpt (trich_ja / trich_vi / t) — nothing is invented, and the text says it is a transcript quote.
// The same pass removes the spaces the furigana stripping left inside Japanese words ("来 ました" → "来ました") in the
// prompt, options and excerpt, so the sentence reads as in the script. Checks that already have an explanation keep it.
//   node tools/learning-data-extract/attach_minna_listening_explanations.mjs . [--check]
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readLearningSection, writeLearningSection } from './learning_content_store.mjs';

const SECTION = 'jlpt_minna_n5_n4_listening';
const JAPANESE = '\\u3000-\\u303f\\u3040-\\u30ff\\u31f0-\\u31ff\\u3400-\\u4dbf\\u4e00-\\u9fff\\uff01-\\uff0f\\uff1a-\\uff20\\uff5b-\\uff65';
const SPLIT = new RegExp(`(?<=[${JAPANESE}]) +(?=[${JAPANESE}])`, 'g');

/** Spaces between two Japanese characters are a stripping artefact; spaces next to Latin text or digits stay. */
export function joinJapanese(text) {
  return typeof text === 'string' ? text.replace(SPLIT, '') : text;
}

function clock(seconds) {
  const whole = Math.max(0, Math.floor(Number(seconds) || 0));
  return `${Math.floor(whole / 60)}:${String(whole % 60).padStart(2, '0')}`;
}

/** The learner-facing explanation built only from the check's own source excerpt. */
export function transcriptExplanation(check) {
  const ja = joinJapanese(String(check.trich_ja ?? '').trim());
  const vi = String(check.trich_vi ?? '').trim();
  const answer = (check.phuong_an ?? []).find((option) => String(option.k) === String(check.dap_an));
  if (!ja || !answer) return null;
  const at = Array.isArray(check.t) && check.t.length === 2 ? ` (${clock(check.t[0])}–${clock(check.t[1])})` : '';
  return `Lời giải trích lời thoại${at}: 「${ja}」${vi ? ` — ${vi}` : ''} Đáp án: ${joinJapanese(answer.text)}.`;
}

/** Pure: the section with transcript explanations on empty fill-in checks and Japanese words joined. */
export function attachExplanations(section) {
  let filled = 0;
  let joined = 0;
  const lessons = section.lessons.map((lesson) => ({
    ...lesson,
    kiem_tra: lesson.kiem_tra.map((check) => {
      const next = { ...check,
        cau_hoi: joinJapanese(check.cau_hoi),
        trich_ja: joinJapanese(check.trich_ja),
        phuong_an: (check.phuong_an ?? []).map((option) => ({ ...option, text: joinJapanese(option.text) })) };
      if (next.cau_hoi !== check.cau_hoi || next.phuong_an.some((option, i) => option.text !== check.phuong_an[i].text)) joined++;
      if (/^W-/.test(check.id) && !String(check.giai_thich ?? '').trim()) {
        const explanation = transcriptExplanation(check);
        if (explanation) {
          next.giai_thich = explanation;
          next.giai_thich_nguon = 'TRICH_LOI_THOAI';
          filled++;
        }
      }
      return next;
    }),
  }));
  // Joining must never merge two options into the same text (the SQL builder requires four distinct options).
  const broken = lessons.flatMap((lesson) => lesson.kiem_tra).find((check) => typeof check.cau_hoi !== 'string'
    || new Set(check.phuong_an.map((option) => String(option.text).trim().toLowerCase())).size !== check.phuong_an.length);
  if (broken) throw new Error(`Joining Japanese words broke check ${broken.id}.`);
  const previous = section.transcript_explanations ?? {};
  return {
    ...section,
    lessons,
    transcript_explanations: {
      method: 'TRANSCRIPT_EXCERPT', fields: ['trich_ja', 'trich_vi', 't'], owner_decision: 'OD-D2-03 2026-10-23',
      review_status: 'GENERATED_FROM_SOURCE_NOT_REVIEWED',
      filled: (previous.filled ?? 0) + filled, spacing_joined_checks: (previous.spacing_joined_checks ?? 0) + joined,
    },
  };
}

async function main() {
  const root = path.resolve(process.argv[2] ?? '.');
  const section = await readLearningSection(root, SECTION);
  const updated = attachExplanations(section);
  const summary = updated.transcript_explanations;
  if (process.argv.includes('--check')) {
    if (JSON.stringify(updated.lessons) !== JSON.stringify(section.lessons)) throw new Error('Minna listening explanations are stale.');
  } else if (JSON.stringify(updated.lessons) !== JSON.stringify(section.lessons)) {
    await writeLearningSection(root, SECTION, updated);
  }
  const empty = updated.lessons.flatMap((l) => l.kiem_tra).filter((c) => !String(c.giai_thich ?? '').trim()).length;
  console.log(`explanations filled=${summary.filled} spacing-joined checks=${summary.spacing_joined_checks} still empty=${empty}`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main();
