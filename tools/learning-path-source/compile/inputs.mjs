// Loads every compiler input once: Mirai frames (all levels), the J-LAS path source, the dictionary export and VNJPClub.
import { readFileSync } from 'node:fs';
import { gunzipSync } from 'node:zlib';
import path from 'node:path';
import { LEVELS, ROOT, readJson } from './util.mjs';
import { loadMiraiFrame, loadMiraiReference } from './mirai-frame.mjs';
import { loadDictionaryIndex } from './dictionary-export.mjs';
import { compileLevel } from './merge.mjs';

export const MIRAI_PACK = process.env.MIRAI_PACK ?? 'D:/rea/Mirai_Full_Project';
const gz = name => JSON.parse(gunzipSync(readFileSync(path.join(ROOT, 'data/vnjpclub', `${name}.json.gz`))).toString('utf8'));

export function loadCompileInputs() {
  return {
    frames: Object.fromEntries(LEVELS.map(l => [l, loadMiraiFrame(MIRAI_PACK, l)])),
    jlasLevels: Object.fromEntries(LEVELS.map(l => [l, readJson(path.join(ROOT, 'data/learning-path/jlpt', `${l}.json`))])),
    dictionary: loadDictionaryIndex(),
    vnjpclub: { vocabulary: gz('tu_vung'), examples: gz('vi_du') }
  };
}

export const compileFromInputs = (inputs, level) => compileLevel({ level, ...inputs });
export const loadReference = level => loadMiraiReference(MIRAI_PACK, level);
