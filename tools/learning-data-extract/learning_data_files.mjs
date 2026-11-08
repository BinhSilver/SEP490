// [JLAS-W0108] Owner 2026-11-01: the Learning data is one common file plus one file per JLPT level (each under 50 MB),
// applied as sql/learning/learning_schema.sql -> learning_data_common.sql -> learning_data_N5.sql -> N4 -> N3 -> N2 -> N1.
// Builders use these helpers to find the file that owns their block.
import path from 'node:path';

export const LEVELS = ['N5', 'N4', 'N3', 'N2', 'N1'];
export const JPATH_BEGIN = '-- BEGIN GENERATED jpath';

/** The data file of a JLPT level, or the common file when no level is given. */
export function learningDataFile(root, level = null) {
  if (level && !LEVELS.includes(level)) throw new Error(`Unknown JLPT level ${level}`);
  return path.join(root, 'sql/learning/parts', level ? `learning_data_${level}.sql` : 'learning_data_common.sql');
}

/** Marker of the part of a multi-level section that belongs to one level. */
export const levelMarker = (marker, level) => (level ? `${marker}@${level}` : marker);

/**
 * Replaces the block whose first line is `-- BEGIN <marker> SHA256 ...` and last line `-- END <marker>`.
 * A block the file does not have yet is inserted before the file's GENERATED jpath region (or appended).
 */
export function replaceDataBlock(sql, marker, block) {
  const newline = sql.includes('\r\n') ? '\r\n' : '\n';
  const formatted = block.replace(/\r?\n/g, newline);
  const begin = `-- BEGIN ${marker} SHA256 `;
  const end = `-- END ${marker}`;
  const start = sql.indexOf(begin);
  if (start < 0) {
    const at = sql.indexOf(JPATH_BEGIN);
    if (at < 0) return `${sql.trimEnd()}${newline}${newline}${formatted}`;
    return `${sql.slice(0, at)}${formatted}${newline}${sql.slice(at)}`;
  }
  const endStart = sql.indexOf(`${end}${newline}`, start) >= 0 ? sql.indexOf(`${end}${newline}`, start) : sql.indexOf(end, start);
  if (endStart < 0) throw new Error(`Unclosed SQL section: ${marker}`);
  const afterEnd = sql.indexOf('\n', endStart);
  return `${sql.slice(0, start)}${formatted}${afterEnd < 0 ? '' : sql.slice(afterEnd + 1)}`;
}
