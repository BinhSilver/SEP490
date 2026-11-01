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

export function planAudio(compiled) {
  const level = compiled?.level ?? 'N5';
  const baseDir = `data/content-audio/learning-path/${level}`;
  const plan = [];

  for (const v of compiled?.vocabulary ?? []) {
    if (SKIPPED_STATUSES.has(v.status)) continue;
    if (v.reading) {
      const text = String(v.reading);
      plan.push({
        id: v.id,
        kind: 'WORD',
        text,
        file: `${baseDir}/${v.id}.mp3`,
        sha_text: sha256(text)
      });
    }
    const examples = v.examples ?? [];
    for (let i = 0; i < examples.length; i++) {
      const ja = examples[i]?.ja;
      if (!ja) continue;
      const text = String(ja);
      plan.push({
        id: v.id,
        kind: 'EXAMPLE',
        text,
        file: `${baseDir}/${v.id}-ex${i + 1}.mp3`,
        sha_text: sha256(text)
      });
    }
  }

  for (const g of compiled?.grammar ?? []) {
    if (SKIPPED_STATUSES.has(g.status)) continue;
    const examples = (g.examples ?? []).slice(0, 3);
    for (let i = 0; i < examples.length; i++) {
      const ja = examples[i]?.ja;
      if (!ja) continue;
      const text = String(ja);
      plan.push({
        id: g.id,
        kind: 'EXAMPLE',
        text,
        file: `${baseDir}/${g.id}-ex${i + 1}.mp3`,
        sha_text: sha256(text)
      });
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

  for (const item of selected) {
    const absFile = path.resolve(rootDir, item.file);
    if (existsSync(absFile)) {
      const existing = readFileSync(absFile);
      manifest.push({
        id: item.id,
        file: item.file,
        sha256: sha256(existing),
        bytes: existing.length
      });
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
    manifest.push({
      id: item.id,
      file: item.file,
      sha256: sha256(buf),
      bytes: buf.length
    });
  }

  return Object.assign(manifest, { files: manifest.length, chars: totalChars });
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
  const manifest = await synthesizeAll(plan, { limit, dryRun });
  if (!dryRun) {
    writeOrCheck(audioManifestFile(level), { level, items: manifest }, false);
    console.log(`${level}: wrote ${manifest.length} audio entries to data/learning-path/audio/${level}.json`);
  }
}
