// Azure Speech REST TTS planner and synthesizer for JLPT learning-path cards and examples (spec 4.11, Task 9).
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { ROOT, argValue, readJson, sha256, writeOrCheck } from './util.mjs';

const DEFAULT_VOICE = 'ja-JP-NanamiNeural';
const DEFAULT_RATE = '0%';
const OUTPUT_FORMAT = 'audio-24khz-48kbitrate-mono-mp3';
const SKIPPED_STATUSES = new Set(['DRAFT', 'HOLD']);

const XML_ESCAPES = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&apos;'
};

export function escapeXml(value) {
  return String(value ?? '').replace(/[&<>"']/g, ch => XML_ESCAPES[ch]);
}

export function buildSsml(textOrOpts, opts = {}) {
  const isObj = textOrOpts !== null && typeof textOrOpts === 'object';
  const text = isObj ? textOrOpts.text : textOrOpts;
  const { voice = DEFAULT_VOICE, rate = DEFAULT_RATE } = isObj ? textOrOpts : opts;
  return `<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="ja-JP"><voice name="${escapeXml(voice)}"><prosody rate="${escapeXml(rate)}">${escapeXml(text)}</prosody></voice></speak>`;
}

// What is spoken for a card text: the first of a/b alternatives, an optional [お]/（お） kept, no 〜, and no notation
// brackets — ＜する＞/（する）/（な） and any other (…) or ＜…＞ annotation are dropped (decision: the word card says the
// word itself; する is grammar notation, the examples carry the verb forms).
export function spokenText(text) {
  let t = String(text ?? '').trim();
  if (/[/／]/.test(t) && !/[。、！？]/.test(t)) t = t.split(/[/／]/)[0];
  t = t.replace(/[[［]([おご御])[\]］]/g, '$1').replace(/[（(]([おご御])[）)]/g, '$1')
    .replace(/[＜<][^＞>]*[＞>]/g, '').replace(/[（(][^）)]*[）)]/g, '').replace(/[[［][^\]］]*[\]］]/g, '')
    .replace(/[〜～~]/g, '');
  return t.replace(/\s+/g, ' ').trim();
}

const entry = (id, kind, raw, file) => { const text = spokenText(raw); return { id, kind, text, file, sha_text: sha256(text) }; };

export function planAudio(compiled) {
  const level = compiled?.level ?? 'N5';
  const baseDir = `data/content-audio/learning-path/${level}`;
  const plan = [];

  for (const v of compiled?.vocabulary ?? []) {
    if (SKIPPED_STATUSES.has(v.status)) continue;
    if (v.reading) plan.push(entry(v.id, 'WORD', v.reading, `${baseDir}/${v.id}.mp3`));
    const examples = v.examples ?? [];
    for (let i = 0; i < examples.length; i++) {
      const ja = examples[i]?.ja;
      if (ja) plan.push(entry(v.id, 'EXAMPLE', ja, `${baseDir}/${v.id}-ex${i + 1}.mp3`));
    }
  }

  for (const g of compiled?.grammar ?? []) {
    if (SKIPPED_STATUSES.has(g.status)) continue;
    const examples = (g.examples ?? []).slice(0, 3);
    for (let i = 0; i < examples.length; i++) {
      const ja = examples[i]?.ja;
      if (ja) plan.push(entry(g.id, 'EXAMPLE', ja, `${baseDir}/${g.id}-ex${i + 1}.mp3`));
    }
  }

  return plan;
}

const defaultSleep = ms => new Promise(resolve => setTimeout(resolve, ms));

export async function synthesizeAll(plan, {
  limit = null,
  dryRun = false,
  fetchImpl = globalThis.fetch,
  rootDir = ROOT,
  manifest: previous = [],
  voice = DEFAULT_VOICE,
  rate = DEFAULT_RATE,
  key = process.env.AZURE_SPEECH_KEY ?? process.env.AZURE_SPEECH_PRONUNCIATION_KEY ?? null,
  region = process.env.AZURE_SPEECH_REGION ?? process.env.AZURE_SPEECH_PRONUNCIATION_REGION ?? null,
  rateLimitPerMinute = 20,
  minIntervalMs = fetchImpl === globalThis.fetch ? Math.ceil(60000 / rateLimitPerMinute) : 0,
  sleepImpl = defaultSleep,
  logger = console.log
} = {}) {
  const selected = limit != null ? plan.slice(0, Math.max(0, Number(limit))) : plan;
  const totalChars = selected.reduce((sum, item) => sum + String(item.text ?? '').length, 0);

  if (dryRun) {
    logger(`TTS dry-run: ${selected.length} files, ${totalChars} chars`);
    return Object.assign([], { files: selected.length, chars: totalChars });
  }

  const manifest = [];
  let requestCount = 0;
  const made = new Map((previous ?? []).map(e => [e.file, e]));

  for (const item of selected) {
    const absFile = path.resolve(rootDir, item.file);
    // A file is reused only when the manifest says it was made from this exact text.
    if (existsSync(absFile) && made.get(item.file)?.text_sha256 === item.sha_text) {
      const existing = readFileSync(absFile);
      manifest.push({ id: item.id, file: item.file, sha256: sha256(existing), bytes: existing.length, text_sha256: item.sha_text });
      continue;
    }

    if (!key || !region) {
      throw new Error('Missing AZURE_SPEECH_KEY or AZURE_SPEECH_REGION environment variable');
    }
    if (typeof fetchImpl !== 'function') {
      throw new Error('Missing fetch implementation');
    }

    if (requestCount > 0 && minIntervalMs > 0) {
      await sleepImpl(minIntervalMs);
    }

    const url = `https://${region}.tts.speech.microsoft.com/cognitiveservices/v1`;
    const body = buildSsml(item.text, { voice, rate });
    const response = await fetchImpl(url, {
      method: 'POST',
      headers: {
        'Ocp-Apim-Subscription-Key': key,
        'Content-Type': 'application/ssml+xml',
        'X-Microsoft-OutputFormat': OUTPUT_FORMAT,
        'User-Agent': 'jlas-learning-path-tts'
      },
      body
    });
    requestCount++;

    if (!response.ok) {
      throw new Error(`Azure TTS failed for ${item.file} with status ${response.status}`);
    }

    const buf = Buffer.from(await response.arrayBuffer());
    mkdirSync(path.dirname(absFile), { recursive: true });
    writeFileSync(absFile, buf);
    manifest.push({ id: item.id, file: item.file, sha256: sha256(buf), bytes: buf.length, text_sha256: item.sha_text });
    if (requestCount % 100 === 0) logger(`TTS: ${requestCount} files synthesized`);
  }

  return Object.assign(manifest, { files: manifest.length, chars: totalChars });
}

// A run over part of the plan (--limit) updates its own entries and keeps every other one.
export function mergeManifest(old, fresh) {
  const byFile = new Map((old ?? []).map(e => [e.file, e]));
  for (const e of fresh) byFile.set(e.file, e);
  return [...byFile.values()];
}

// Released items (not DRAFT/HOLD) need an audio entry made from their current spoken text.
export function audioErrors(compiled, manifest) {
  const byFile = new Map((manifest?.items ?? []).map(e => [e.file, e]));
  return planAudio(compiled).flatMap(p => {
    const e = byFile.get(p.file);
    if (!e) return [{ code: 'AUDIO_MISSING', id: p.id, detail: p.file }];
    return e.text_sha256 === p.sha_text ? [] : [{ code: 'AUDIO_SHA_MISMATCH', id: p.id, detail: p.file }];
  });
}

export const compiledLevelFile = level => path.join(ROOT, 'data/learning-path/compiled', `${level}.json`);
export const audioManifestFile = level => path.join(ROOT, 'data/learning-path/audio', `${level}.json`);

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'))) {
  const argv = process.argv.slice(2);
  const level = argValue(argv, '--level', 'N5');
  const dryRun = argv.includes('--dry-run');
  const limitRaw = argValue(argv, '--limit', null);
  const limit = limitRaw != null ? Number(limitRaw) : null;
  const compiled = readJson(compiledLevelFile(level));
  const plan = planAudio(compiled);
  const file = audioManifestFile(level);
  const previous = existsSync(file) ? readJson(file).items : [];
  const manifest = await synthesizeAll(plan, { limit, dryRun, manifest: previous });
  if (!dryRun) {
    // A full run lists exactly the plan; a limited one keeps the entries it did not touch.
    const items = limit != null ? mergeManifest(previous, manifest) : [...manifest];
    writeOrCheck(file, { level, items }, false);
    console.log(`${level}: wrote ${items.length} audio entries to data/learning-path/audio/${level}.json`);
  }
}
