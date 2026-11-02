// Data report for one compiled level: a Markdown summary plus CSV detail files the owner can open in Excel.
// Reports go to the main checkout's docs/learning (git-ignored) so they open in the owner's file pane.
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';

export const REPORT_DIR = process.env.REPORT_DIR ?? 'D:/FALL26_FPT/SEP490/docs/learning';

const cell = v => { const s = v == null ? '' : String(v); return /[",\n;]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s; };
const show = v => v == null ? '' : typeof v === 'string' || typeof v === 'number' ? v
  : Array.isArray(v) && v.every(x => x && typeof x === 'object' && 'ja' in x) ? v.map(x => x.ja).join(' / ') : JSON.stringify(v);
const csv = (header, rows) => '\uFEFF' + [header, ...rows].map(r => r.map(cell).join(',')).join('\n') + '\n';

export function writeReport({ level, compiled, result, date }, outDir = REPORT_DIR) {
  const csvDir = path.join(outDir, 'du-lieu-csv');
  mkdirSync(csvDir, { recursive: true });
  const s = result.stats;
  const blocking = result.errors.filter(e => !e.warning);
  const byStatus = list => list.reduce((a, x) => (a[x.status] = (a[x.status] ?? 0) + 1, a), {});
  const files = {
    [`${level}-loi.csv`]: csv(['code', 'id', 'warning', 'detail'], result.errors.map(e => [e.code, e.id, e.warning ? 'x' : '', e.detail])),
    [`${level}-xung-dot.csv`]: csv(['kind', 'id', 'chosen', 'values'], compiled.conflicts.filter(c => c.kind !== 'REVIEW_FIX').map(c => [c.kind, c.id, c.chosen,
      c.values.map(v => `${v.source}: ${v.value}`).join(' | ')])),
    // Every value a review replaced (old source and value, new value) and every sentence a review dropped.
    [`${level}-ghi-de.csv`]: csv(['id', 'field', 'old_source', 'old_value', 'new_value'], compiled.conflicts.filter(c => c.kind === 'REVIEW_FIX')
      .map(c => [c.id, c.field, c.values[0].source, show(c.values[0].value), show(c.values[1].value)])),
    [`${level}-cau-bi-bo.csv`]: csv(['id', 'source', 'ja', 'vi'], (compiled.dropped_examples ?? []).map(d => [d.id, d.source, d.ja, d.vi])),
    [`${level}-chuyen-cap.csv`]: csv(['id', 'from', 'to'], compiled.level_moves.map(m => [m.id, m.from, m.to])),
    [`${level}-giong-mirai.csv`]: csv(['id', 'detail'], result.errors.filter(e => e.code === 'MIRAI_SIMILAR').map(e => [e.id, e.detail])),
    [`${level}-de-xuat-bo-sung-tu-dien.csv`]: csv(['expression', 'reading', 'meaning_vi', 'word_type', 'level'],
      compiled.vocabulary.filter(v => v.dictionary_entry_id == null).map(v => [v.expression, v.reading, v.meaning_vi, v.word_type_label, level]))
  };
  for (const [name, text] of Object.entries(files)) writeFileSync(path.join(csvDir, name), text);
  const codes = Object.entries(s.errors_by_code).sort((a, b) => b[1] - a[1]).map(([c, n]) => `| ${c} | ${n} |`).join('\n');
  const md = `# Báo cáo dữ liệu lộ trình JLPT ${level} (${date})

| Mục | Số lượng | Trạng thái |
|---|---:|---|
| Từ vựng | ${s.vocabulary} | ${JSON.stringify(byStatus(compiled.vocabulary))} |
| Kanji | ${s.kanji} | ${JSON.stringify(byStatus(compiled.kanji))} |
| Ngữ pháp | ${s.grammar} | ${JSON.stringify(byStatus(compiled.grammar))} |

- Từ theo khung Mirai: ${s.from_frame}; có trong nguồn J-LAS: ${s.jlas_matched}; mục có phần AI soạn: ${s.ai_authored}.
- Chuyển cấp (về ${level}): ${s.level_moves}; xung đột nghĩa: ${compiled.conflicts.filter(c => c.kind !== 'REVIEW_FIX').length}; ghi đè khi duyệt: ${compiled.conflicts.filter(c => c.kind === 'REVIEW_FIX').length}; câu bị bỏ: ${(compiled.dropped_examples ?? []).length}; từ chưa có trong từ điển: ${compiled.vocabulary.filter(v => v.dictionary_entry_id == null).length}.
- Lỗi chặn: ${blocking.length}; cảnh báo: ${result.errors.length - blocking.length}.

| Mã | Số |
|---|---:|
${codes || '| (không có) | 0 |'}

Chi tiết: \`du-lieu-csv/${level}-*.csv\` (lỗi, xung đột, ghi đè, câu bị bỏ, chuyển cấp, giống Mirai, đề xuất bổ sung từ điển).
`;
  writeFileSync(path.join(outDir, `bao-cao-du-lieu-${level}.md`), md);
  return path.join(outDir, `bao-cao-du-lieu-${level}.md`);
}
