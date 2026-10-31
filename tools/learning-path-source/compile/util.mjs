// Shared helpers for the JLPT path source compiler: stable JSON, hashing and write-or-check output.
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
export const LEVELS = ['N5', 'N4', 'N3', 'N2', 'N1'];

export const sha256 = value => createHash('sha256').update(value).digest('hex');
export const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
export const json = value => JSON.stringify(stable(value), null, 1) + '\n';
export const readJson = file => JSON.parse(readFileSync(file, 'utf8'));

export function slugVi(text) {
  return String(text ?? '').normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[đĐ]/g, 'd')
    .toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
}

// Writes `value` as stable JSON, or with check=true compares bytes and returns false on any difference.
export function writeOrCheck(file, value, check) {
  const text = json(value);
  if (check) return existsSync(file) && readFileSync(file, 'utf8') === text;
  mkdirSync(path.dirname(file), { recursive: true });
  writeFileSync(file, text);
  return true;
}

export function argValue(argv, name, fallback = null) {
  const i = argv.indexOf(name);
  return i >= 0 && i + 1 < argv.length ? argv[i + 1] : fallback;
}
