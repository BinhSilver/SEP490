import fs from 'node:fs/promises';
import path from 'node:path';
import { createHash } from 'node:crypto';

const filename = (root) => path.join(root, 'data/learning-owned/learning-content.json');
const digest = (value) => createHash('sha256').update(JSON.stringify(value)).digest('hex');

export async function readLearningContent(root) {
  const data = JSON.parse(await fs.readFile(filename(root), 'utf8'));
  if (data.schema !== 'JLAS_LEARNING_OWNED_CONTENT_V1') throw new Error('Unknown Learning data schema');
  for (const [name, section] of Object.entries(data.sections)) {
    if (data.section_sha256[name] !== digest(section)) throw new Error(`Learning section checksum mismatch: ${name}`);
  }
  for (const [name, record] of Object.entries(data.review_audit_archive ?? {})) {
    const actual = createHash('sha256').update(record.text).digest('hex');
    if (actual !== record.sha256) throw new Error(`Learning review-audit checksum mismatch: ${name}`);
  }
  return data;
}

export async function readLearningSection(root, name) {
  const data = await readLearningContent(root);
  if (!(name in data.sections)) throw new Error(`Missing Learning section: ${name}`);
  return data.sections[name];
}

export async function readReviewedVocabularyBatchTexts(root, document) {
  const manifest = document.schema === 'JLAS_LEARNING_OWNED_CONTENT_V1'
    ? document.sections.reviewed_vocabulary : document;
  const embedded = document.sections?.vocabulary_review_batches?.batches;
  return Promise.all(manifest.review_batch_files.map(async (name) => {
    if (embedded) {
      const record = embedded[name];
      if (!record) throw new Error(`Missing embedded review batch: ${name}`);
      if (createHash('sha256').update(record.text).digest('hex') !== record.sha256)
        throw new Error(`Review batch checksum mismatch: ${name}`);
      return record.text;
    }
    const file = path.resolve(root, name);
    if (!file.startsWith(path.resolve(root) + path.sep)) throw new Error(`Review batch outside repository: ${name}`);
    return fs.readFile(file, 'utf8');
  }));
}

export async function writeLearningSection(root, name, section) {
  return writeLearningSections(root, { [name]: section });
}

/** Writes several sections with one rewrite of the (large) canonical file. */
export async function writeLearningSections(root, sections) {
  const data = await readLearningContent(root);
  for (const [name, section] of Object.entries(sections)) {
    if (typeof name !== 'string' || !/^[a-z][a-z0-9_]*$/.test(name)) {
      throw new Error(`Invalid Learning section name: ${name}`);
    }
    data.sections[name] = section;
    data.section_sha256[name] = digest(section);
  }
  const target = filename(root);
  const temporary = `${target}.tmp`;
  await fs.writeFile(temporary, JSON.stringify(data), 'utf8');
  // On Windows an editor or indexer may hold the just-written file for a moment (copyfile UNKNOWN / EBUSY / EPERM).
  for (let attempt = 1; ; attempt++) {
    try {
      await fs.copyFile(temporary, target);
      break;
    } catch (error) {
      if (attempt >= 8 || !['UNKNOWN', 'EBUSY', 'EPERM', 'EACCES'].includes(error.code)) throw error;
      await new Promise((resolve) => setTimeout(resolve, 750 * attempt));
    }
  }
  await fs.unlink(temporary);
}
