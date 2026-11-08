// [JLAS-W0108] Phase 2 (owner 06/11): one compiled source per communication goal for the new communication path.
// Inputs (read only): data/learning-path/communication/<GOAL>.json (generated from learning-content.json) and
// data/learning-path/communication-additions/<GOAL>.json (the 05/11 additions; WORK is a new goal there).
// Output: data/learning-path/communication-compiled/<GOAL>.json — topics in study order, each with its situations
// (dialogues with lines, key phrases, role-play turns), words and phrases; plus a check report.
// Run: node tools/comm-path/build_comm_source.mjs [--check]
import { readFileSync, writeFileSync, existsSync, mkdirSync } from 'node:fs';
import path from 'node:path';

const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), '../..');
const BASE = path.join(ROOT, 'data/learning-path/communication');
const ADD = path.join(ROOT, 'data/learning-path/communication-additions');
const OUT = path.join(ROOT, 'data/learning-path/communication-compiled');
export const GOALS = ['TRAVEL', 'DAILY_LIFE', 'BA', 'WORK'];
export const MIN = Object.freeze({ words: 25, phrases: 8, situations: 3 });
const LEVEL_RANK = { N5: 0, N4: 1, N3: 2, N2: 3, N1: 4 };

const read = f => (existsSync(f) ? JSON.parse(readFileSync(f, 'utf8')) : null);
const byId = list => new Map((list ?? []).map(x => [x.id ?? x.code, x]));

function merged(goal) {
  const base = read(path.join(BASE, `${goal}.json`)) ?? {};
  const add = read(path.join(ADD, `${goal}.json`)) ?? {};
  const join = key => {
    const out = byId(base[key]);
    for (const x of add[key] ?? []) if (!out.has(x.id ?? x.code)) out.set(x.id ?? x.code, x);
    return [...out.values()];
  };
  return {
    goal, display_name_vi: base.display_name_vi ?? add.display_name_vi ?? goal,
    survey: base.survey?.length ? base.survey : (add.survey ?? []),
    topicOf: base.policy?.dialogueTopics ?? {},
    topics: join('topics'), vocabulary: join('vocabulary'), phrases: join('phrases'), dialogues: join('dialogues'),
  };
}

export function compileGoal(goal) {
  const m = merged(goal);
  const topicCode = d => d.topic_code ?? m.topicOf[d.id] ?? null;
  const topics = [...m.topics].sort((a, b) => (LEVEL_RANK[a.level] ?? 9) - (LEVEL_RANK[b.level] ?? 9));
  const problems = [];
  const out = topics.map((t, i) => {
    const situations = m.dialogues.filter(d => topicCode(d) === t.code).map((d, k) => ({
      id: d.id, order: k + 1, title_ja: d.title_ja ?? null, title_vi: d.title_vi ?? null,
      situation_vi: d.situation_vi ?? null, level: d.level ?? t.level ?? null,
      lines: (d.lines ?? []).map(l => ({ order: l.order, speaker: l.speaker, ja: l.ja, vi: l.vi ?? null })),
      furigana: d.dialogue_aids?.furigana ?? null,
      challenge: d.dialogue_aids?.challenge ?? null,
      phrase_ids: m.phrases.filter(p => p.dialogue_id === d.id).map(p => p.id),
    }));
    const words = m.vocabulary.filter(v => v.topic === t.code);
    const phrases = m.phrases.filter(p => situations.some(s => s.id === p.dialogue_id));
    for (const [what, n, min] of [['words', words.length, MIN.words], ['phrases', phrases.length, MIN.phrases],
      ['situations', situations.length, MIN.situations]]) if (n < min) problems.push({ goal, topic: t.code, what, n, min });
    return { code: t.code, order: i + 1, level: t.level ?? null, name_ja: t.name_ja ?? null, name_vi: t.name_vi ?? null,
      situations, words: words.map(v => ({ id: v.id, word: v.word, reading: v.reading, meaning_vi: v.meaning_vi })),
      phrases: phrases.map(p => ({ id: p.id, ja: p.ja, reading: p.reading, vi: p.vi, note: p.note ?? null, dialogue_id: p.dialogue_id })) };
  });
  const orphans = m.dialogues.filter(d => !topics.some(t => t.code === topicCode(d))).map(d => d.id);
  if (orphans.length) problems.push({ goal, what: 'dialogues without topic', ids: orphans });
  return { compiled: { goal, display_name_vi: m.display_name_vi, survey: m.survey, topics: out,
    generated_by: 'tools/comm-path/build_comm_source.mjs' }, problems };
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'))) {
  const check = process.argv.includes('--check');
  mkdirSync(OUT, { recursive: true });
  let all = [];
  for (const g of GOALS) {
    const { compiled, problems } = compileGoal(g);
    const text = JSON.stringify(compiled, null, 1) + '\n';
    const file = path.join(OUT, `${g}.json`);
    if (check) { if (!existsSync(file) || readFileSync(file, 'utf8') !== text) problems.push({ goal: g, what: 'stale' }); }
    else writeFileSync(file, text);
    const t = compiled.topics;
    console.log(g, 'topics', t.length, 'situations', t.reduce((s, x) => s + x.situations.length, 0),
      'words', t.reduce((s, x) => s + x.words.length, 0), 'phrases', t.reduce((s, x) => s + x.phrases.length, 0));
    all = all.concat(problems);
  }
  if (all.length) { console.log(JSON.stringify(all, null, 1)); process.exitCode = 1; } else console.log('OK');
}
