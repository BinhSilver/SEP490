// Multi-voice Azure TTS for JLPT path Listening sets (Plan 3 Task 5, spec 4.11).
// One mp3 per listening set (one SSML request, one <voice> per script line, a pause between lines), one mp3 per
// dictation row (<setId>-D<n>, voice of the speaker of the line it comes from) and one per Listening strategy lesson's
// worked example. Files go under data/content-audio/learning-path/<L>/skills/ (not in Git); the manifest is
// data/learning-path/audio/skills-<L>.json.
// CLI: node tools/learning-path-source/skills/skill-tts.mjs --level N5 [--limit 20] [--dry-run] [--rpm 20]
// The Azure key/region come only from env (AZURE_SPEECH_KEY/REGION or AZURE_SPEECH_PRONUNCIATION_*).
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { escapeXml, mergeManifest, spokenText } from '../compile/tts.mjs';
import { LEVELS, ROOT, argValue, readJson, sha256, writeOrCheck } from '../compile/util.mjs';

export const VOICES = Object.freeze({ M: 'ja-JP-KeitaNeural', F: 'ja-JP-MayuNeural', N: 'ja-JP-NanamiNeural' });
export const OUTPUT_FORMAT = 'audio-24khz-48kbitrate-mono-mp3';
const BITS_PER_SECOND = 48000;
const SPEAK_OPEN = '<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="ja-JP">';

/** Seconds of a 48 kbps mp3, rounded up. */
export const audioSeconds = bytes => Math.ceil((Number(bytes) * 8) / BITS_PER_SECOND);

/** One SSML document for a script [{speaker: M|F|N, ja}]: a <voice> per spoken line, a break before every line but the first. */
export function dialogueSsml(script, voices = VOICES, { breakMs = 600, rate = '0%' } = {}) {
  const parts = [];
  for (const line of script ?? []) {
    const voice = voices[line?.speaker];
    if (!voice) throw new Error(`Unknown speaker ${JSON.stringify(line?.speaker)}`);
    const text = spokenText(line.ja);
    if (!text) continue;
    const pause = parts.length ? `<break time="${Number(breakMs)}ms"/>` : '';
    parts.push(`<voice name="${escapeXml(voice)}">${pause}<prosody rate="${escapeXml(rate)}">${escapeXml(text)}</prosody></voice>`);
  }
  if (!parts.length) throw new Error('Script has no spoken line');
  return `${SPEAK_OPEN}${parts.join('')}</speak>`;
}

const LABELS = { 質問: 'N', 女: 'F', 男: 'M' };
/** A strategy lesson's script_ja ("女：…" per line; 質問 and unlabelled lines are the narrator) as a script. */
export function lessonScript(scriptJa) {
  return String(scriptJa ?? '').split(/\r?\n/).map(s => s.trim()).filter(Boolean).map(line => {
    const m = line.match(/^(質問|女|男)[：:]\s*(.*)$/);
    return m ? { speaker: LABELS[m[1]], ja: m[2] } : { speaker: 'N', ja: line };
  }).filter(l => l.ja);
}

const spoken = script => script.map(l => spokenText(l.ja)).join('');
const item = (id, kind, file, script, opts) => {
  const ssml = dialogueSsml(script, opts.voices, opts);
  return { id, kind, file, ssml, text: spoken(script), sha_text: sha256(ssml) };
};

/**
 * Audio plan of a level: each accepted listening set (<setId>.mp3) followed by its dictation rows (<setId>-d<n>.mp3,
 * from skill-dictation rows — lines without a blank have no row and no audio), then the Listening lessons' worked
 * examples (lesson-<type>.mp3). sha_text = sha256 of the SSML, so a voice/pause change also remakes the file.
 */
export function planSkillAudio(level, acceptedSets, lessons, dictationRows = [], opts = {}) {
  const o = { voices: VOICES, breakMs: 600, rate: '0%', ...opts };
  const dir = `data/content-audio/learning-path/${level}/skills`;
  const listening = (acceptedSets ?? []).filter(s => Array.isArray(s.script) && s.script.length);
  const byId = new Map(listening.map(s => [s.id, s]));
  const rowsBySet = new Map();
  for (const row of dictationRows ?? []) {
    const set = byId.get(row.set);
    if (!set) throw new Error(`Dictation row ${row.code} has no accepted listening set`);
    if (!rowsBySet.has(row.set)) rowsBySet.set(row.set, []);
    rowsBySet.get(row.set).push(row);
  }
  const plan = [];
  for (const set of listening) {
    plan.push(item(set.id, 'SET', `${dir}/${set.id}.mp3`, set.script, o));
    for (const row of (rowsBySet.get(set.id) ?? []).sort((a, b) => a.sort_order - b.sort_order)) {
      const speaker = set.script.find(l => String(l.ja ?? '').includes(row.text))?.speaker ?? 'N';
      plan.push(item(row.code, 'DICTATION', `${dir}/${set.id}-d${row.sort_order}.mp3`, [{ speaker, ja: row.text }], o));
    }
  }
  for (const lesson of lessons ?? []) {
    const code = String(lesson?.type_code ?? '');
    if (!code.startsWith(`${level}-L-`)) continue;
    const script = lessonScript(lesson?.worked_example?.script_ja);
    if (script.length) plan.push(item(code, 'LESSON', `${dir}/lesson-${code}.mp3`, script, o));
  }
  return plan;
}

const defaultSleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const entryOf = (it, buf) => ({ id: it.id, file: it.file, sha256: sha256(buf), bytes: buf.length,
  text_sha256: it.sha_text, audio_seconds: audioSeconds(buf.length) });

/**
 * synthesizeAll of compile/tts.mjs (same options and reuse rule: a file is kept when the previous manifest says it was
 * made from this exact sha_text), but posts item.ssml, records audio_seconds and can checkpoint every N new entries.
 */
export async function synthesizeSsmlAll(plan, {
  limit = null,
  dryRun = false,
  fetchImpl = globalThis.fetch,
  rootDir = ROOT,
  manifest: previous = [],
  key = process.env.AZURE_SPEECH_KEY ?? process.env.AZURE_SPEECH_PRONUNCIATION_KEY ?? null,
  region = process.env.AZURE_SPEECH_REGION ?? process.env.AZURE_SPEECH_PRONUNCIATION_REGION ?? null,
  rateLimitPerMinute = 20,
  minIntervalMs = fetchImpl === globalThis.fetch ? Math.ceil(60000 / rateLimitPerMinute) : 0,
  sleepImpl = defaultSleep,
  retries = 3,
  retryDelayMs = 15000,
  checkpointEvery = 20,
  onCheckpoint = null,
  logger = console.log
} = {}) {
  const selected = limit != null ? plan.slice(0, Math.max(0, Number(limit))) : plan;
  const chars = selected.reduce((sum, it) => sum + String(it.text ?? '').length, 0);
  const ssmlChars = selected.reduce((sum, it) => sum + String(it.ssml ?? '').length, 0);
  const made = new Map((previous ?? []).map(e => [e.file, e]));
  const reusable = it => existsSync(path.resolve(rootDir, it.file)) && made.get(it.file)?.text_sha256 === it.sha_text;

  if (dryRun) {
    const toMake = selected.filter(it => !reusable(it)).length;
    logger(`Skill TTS dry-run: ${selected.length} files (${toMake} to synthesize), ${chars} spoken chars, ${ssmlChars} SSML chars`);
    return Object.assign([], { files: selected.length, toMake, chars, ssmlChars });
  }

  const out = [];
  let requestCount = 0;
  for (const it of selected) {
    const absFile = path.resolve(rootDir, it.file);
    if (reusable(it)) {
      out.push(entryOf(it, readFileSync(absFile)));
      continue;
    }
    if (!key || !region) throw new Error('Missing AZURE_SPEECH_KEY or AZURE_SPEECH_REGION environment variable');
    if (typeof fetchImpl !== 'function') throw new Error('Missing fetch implementation');
    if (requestCount > 0 && minIntervalMs > 0) await sleepImpl(minIntervalMs);

    const post = () => fetchImpl(`https://${region}.tts.speech.microsoft.com/cognitiveservices/v1`, {
      method: 'POST',
      headers: {
        'Ocp-Apim-Subscription-Key': key,
        'Content-Type': 'application/ssml+xml',
        'X-Microsoft-OutputFormat': OUTPUT_FORMAT,
        'User-Agent': 'jlas-learning-path-tts'
      },
      body: it.ssml
    });
    let response = await post();
    // 429 / 5xx (quota shared with another TTS job): wait (Retry-After or retryDelayMs × attempt) and try again.
    for (let attempt = 1; attempt <= retries && (response.status === 429 || response.status >= 500); attempt++) {
      const after = Number(response.headers?.get?.('retry-after'));
      const wait = Number.isFinite(after) && after > 0 ? after * 1000 : retryDelayMs * attempt;
      logger(`Skill TTS: status ${response.status} for ${it.file}, retry ${attempt}/${retries} in ${wait} ms`);
      await sleepImpl(wait);
      response = await post();
    }
    requestCount++;
    if (!response.ok) throw new Error(`Azure TTS failed for ${it.file} with status ${response.status}`);

    const buf = Buffer.from(await response.arrayBuffer());
    if (!buf.length) throw new Error(`Azure TTS returned an empty file for ${it.file}`);
    mkdirSync(path.dirname(absFile), { recursive: true });
    writeFileSync(absFile, buf);
    out.push(entryOf(it, buf));
    if (onCheckpoint && checkpointEvery > 0 && requestCount % checkpointEvery === 0) onCheckpoint([...out]);
    if (requestCount % 50 === 0) logger(`Skill TTS: ${requestCount} files synthesized`);
  }
  return Object.assign(out, { files: out.length, chars, ssmlChars, requested: requestCount });
}

/** {level, voices, items}: a partial run keeps the entries it did not touch; replace=true lists exactly `fresh`. */
export function skillsManifest(level, oldItems, fresh, { replace = false, voices = VOICES } = {}) {
  return { level, voices: { ...voices }, items: replace ? [...fresh] : mergeManifest(oldItems, fresh) };
}

export const skillsManifestFile = level => path.join(ROOT, 'data/learning-path/audio', `skills-${level}.json`);

/** Plan of a level from the bank: accepted sets (skill-check) + dictation rows (skill-dictation) + lessons. */
export async function levelPlan(level) {
  const { checkLevel, loadLevelInputs } = await import('./skill-check.mjs');
  const { dictationBlanks, levelVocab } = await import('./skill-dictation.mjs');
  const inputs = loadLevelInputs(level);
  const { accepted } = checkLevel(level, inputs);
  const { rows } = dictationBlanks(accepted.filter(s => Array.isArray(s.dictation)), levelVocab(level));
  return planSkillAudio(level, accepted, inputs.lessons, rows);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const argv = process.argv.slice(2);
  const level = argValue(argv, '--level', 'N5');
  if (!LEVELS.includes(level)) {
    console.error('usage: skill-tts.mjs --level N5 [--limit 20] [--dry-run] [--rpm 20]');
    process.exit(2);
  }
  const dryRun = argv.includes('--dry-run');
  const limitRaw = argValue(argv, '--limit', null);
  const limit = limitRaw != null ? Number(limitRaw) : null;
  const rpm = Number(argValue(argv, '--rpm', 20));
  const plan = await levelPlan(level);
  const kinds = plan.reduce((m, p) => ({ ...m, [p.kind]: (m[p.kind] ?? 0) + 1 }), {});
  console.log(`${level}: plan ${plan.length} files ${JSON.stringify(kinds)}`);
  const file = skillsManifestFile(level);
  const previous = existsSync(file) ? readJson(file).items ?? [] : [];
  const save = (fresh, replace) => {
    const m = skillsManifest(level, previous, fresh, { replace });
    writeOrCheck(file, m, false);
    return m;
  };
  const result = await synthesizeSsmlAll(plan, {
    limit, dryRun, manifest: previous, rateLimitPerMinute: rpm, onCheckpoint: fresh => save(fresh, false)
  });
  if (dryRun) {
    const intervalMs = Math.ceil(60000 / rpm);
    console.log(`${level}: estimated ${Math.ceil((Math.max(0, result.toMake - 1) * intervalMs) / 60000)} min at ${intervalMs} ms between requests`);
  } else {
    const m = save([...result], limit == null);
    console.log(`${level}: ${result.requested} synthesized, ${result.files - result.requested} reused; wrote ${m.items.length} entries to data/learning-path/audio/skills-${level}.json`);
  }
}
