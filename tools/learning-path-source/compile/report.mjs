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
  // A capped level (N3): the words kept out of the study queue, in rank order (Luyện thêm / Look up candidates).
  if (compiled.study_cap) files[`${level}-ngoai-hang-hoc.csv`] = csv(['order', 'id', 'expression', 'reading', 'tier', 'meaning_vi'],
    compiled.vocabulary.filter(v => v.queue === 'EXTRA').map(v => [v.order, v.id, v.expression, v.reading, v.tier, v.meaning_vi]));
  // A split level (N3): every SECONDARY grammar pattern and the MAIN pattern it is attached to (empty: waits for a packet).
  if (compiled.grammar_split) {
    const patternOf = new Map(compiled.grammar.map(g => [g.id, g.pattern]));
    files[`${level}-ngu-phap-phu.csv`] = csv(['id', 'pattern', 'meaning_vi', 'parent_id', 'parent_pattern', 'parent_source'],
      compiled.grammar.filter(g => g.grammar_role === 'SECONDARY').map(g => [g.id, g.pattern, g.meaning_vi, g.parent_id,
        patternOf.get(g.parent_id) ?? null, g.provenance?.parent_source ?? null]));
  }
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
${compiled.study_cap ? `- Giới hạn hàng học: ${compiled.study_cap.vocabulary} từ; trong hàng học ${compiled.study_cap.in_queue} (khung Mirai ${compiled.study_cap.from_frame}); ngoài hàng học ${compiled.study_cap.out_of_queue} (HOLD, để Luyện thêm/Look up; danh sách \`du-lieu-csv/${level}-ngoai-hang-hoc.csv\`). Cách chọn: ${compiled.study_cap.rule}
` : ''}${compiled.grammar_split ? `- Ngữ pháp chính/phụ: chính ${s.grammar_main} (hàng học); phụ ${s.grammar_secondary} (học cuối bài mẫu chính, nội dung nhẹ); mẫu trùng đã gộp ${compiled.grammar_split.merged_duplicates}; phụ chưa có mẫu chính ${s.grammar_pending_parent} (gói GRAMMAR_PARENT; danh sách \`du-lieu-csv/${level}-ngu-phap-phu.csv\`). Cách chọn: ${compiled.grammar_split.rule}
` : ''}- Lỗi chặn: ${blocking.length}; cảnh báo: ${result.errors.length - blocking.length}.

| Mã | Số |
|---|---:|
${codes || '| (không có) | 0 |'}

Chi tiết: \`du-lieu-csv/${level}-*.csv\` (lỗi, xung đột, ghi đè, câu bị bỏ, chuyển cấp, giống Mirai, đề xuất bổ sung từ điển).
`;
  writeFileSync(path.join(outDir, `bao-cao-du-lieu-${level}.md`), md);
  return path.join(outDir, `bao-cao-du-lieu-${level}.md`);
}
