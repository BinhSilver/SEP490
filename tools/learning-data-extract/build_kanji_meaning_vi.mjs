import fs from 'node:fs/promises';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { readLearningContent } from './learning_content_store.mjs';
import { lessonMeaning } from './build_jlas_compiled_books_sql.mjs';

// [JLAS-W0108] Owner 2026-10-24 (D3-03): the Kanji card shows a Vietnamese meaning (VNJPClub, from the compiled
// books) next to the Hán Việt, and no English. The shared `kanji.meaning_vi` belongs to Dictionary and is empty for
// these characters, so Learning keeps its own copy: a small classpath file generated from the compiled-book sections.
// Characters VNJPClub gives no meaning for are written here by Learning and listed under `written`.
//   node tools/learning-data-extract/build_kanji_meaning_vi.mjs . [--check]
const OUTPUT = 'src/main/resources/learning/kanji-meaning-vi.json';
const LEVELS = ['n5', 'n4', 'n3'];

export const WRITTEN_MEANINGS = {
  誰: 'ai (hỏi người)', 韓: 'Hàn Quốc', 椅: 'ghế', 鍵: 'chìa khóa; ổ khóa', 阪: 'dốc; Osaka (大阪)',
  鵜: 'chim cốc', 貼: 'dán', 鍋: 'nồi', 戚: 'họ hàng (親戚)', 嘘: 'lời nói dối', 叩: 'gõ; đập', 或: 'nào đó; hoặc',
  枕: 'cái gối', 餌: 'mồi; thức ăn cho vật nuôi', 妬: 'ghen tị', 嫉: 'ghen ghét', 妖: 'yêu quái; quyến rũ',
  醤: 'tương (醤油: nước tương)', 爪: 'móng (tay, chân)', 鹸: 'xà phòng (石鹸)',
};

// D3-08: obvious source errors corrected in Learning's copy only, each with its reason; the source stays as it is.
export const CORRECTED_MEANINGS = {
  族: ['gia tộc; dòng họ', 'lỗi gõ "gia dộc"'],
  日: ['ngày; mặt trời', 'nghĩa đầu là cách dùng làm từ đếm, không phải nghĩa chữ'],
  本: ['sách; gốc', '"hiện tại" là nghĩa của từ ghép 本日, không phải của chữ'],
  校: ['trường học', '"in ấn" là nghĩa hiếm (校正), dễ hiểu sai ở N5'],
  外: ['bên ngoài', 'lỗi gõ "bên ngoại"'],
  八: ['tám', '"bộ tám (số 12)" là tên bộ thủ'],
  土: ['đất; thứ Bảy (土曜日)', '"Thổ Nhĩ Kỳ" là cách viết tắt hiếm'],
  短: ['ngắn', '"lỗi" là nghĩa từ ghép 短所'],
  漢: ['Hán (Trung Quốc)', 'thiếu chữ Hán'],
  然: ['như vậy; tự nhiên (自然)', '"loại" không phải nghĩa chữ'],
  公: ['công cộng; công', '"hoàng tử" là nghĩa từ ghép 公子'],
  卵: ['trứng', '"trứng cá; noãn" là nghĩa chuyên ngành'],
  葉: ['lá', '"lưỡi" là cách dịch sai của 葉 (phiến)'],
  当: ['đúng; trúng', 'nghĩa "đánh" ít dùng, đưa ra sau'],
  介: ['giới thiệu (紹介); trung gian', '"kẹt; vỏ sò" là nghĩa cổ, không khớp bài'],
  予: ['trước; dự (予定)', '"tôi" là nghĩa cổ'],
  申: ['nói (khiêm nhường); trình bày', '"dấu hiệu con khỉ" là can chi'],
  準: ['chuẩn; chuẩn bị (準備)', '"bán" sai nghĩa'],
  末: ['cuối', '"đầu" ngược nghĩa'],
  弟: ['em trai', 'nghĩa thứ hai là chú giải dài của từ khác'],
  眠: ['ngủ; buồn ngủ', '"chết" là nghĩa văn chương'],
  張: ['căng; kéo căng', 'nghĩa đầu là cách dùng làm từ đếm'],
  丁: ['cẩn thận (丁寧); đinh', '"can thứ tư" là can chi'],
  壁: ['tường', '"niêm mạc (dạ dày)" là nghĩa chuyên ngành'],
  片: ['mảnh; một phía', '"lá" sai nghĩa'],
  卒: ['tốt nghiệp', '"lính" là nghĩa cổ'],
  召: ['gọi; dùng (kính ngữ 召し上がる)', '"mặc" thiếu ngữ cảnh kính ngữ'],
  押: ['đẩy; ấn', '"dừng" sai nghĩa'],
  決: ['quyết định', '"sửa chữa" sai nghĩa'],
  定: ['xác định; cố định', '"sửa" sai nghĩa'],
  考: ['suy xét; suy nghĩ', 'lỗi gõ "suy sét"'],
  配: ['phân phối; lo lắng (心配)', '"vợ chồng" là nghĩa từ ghép 配偶'],
  実: ['thực tế; quả', '"hạt" chưa sát'],
  故: ['sự cố (事故); cố', '"tình cờ; đặc biệt" sai nghĩa'],
  込: ['đông đúc; cho vào', '"hỗn hợp" sai nghĩa'],
  留: ['giữ lại; vắng nhà (留守)', '"giam giữ" quá nặng nghĩa'],
  在: ['tồn tại; ở', '"ngoại ô" là nghĩa hiếm'],
  解: ['giải; hiểu (理解)', '"ghi chú" sai nghĩa'],
  流: ['dòng chảy; chảy', '"bồn rửa" sai nghĩa'],
  経: ['trải qua; kinh (経済)', '"kinh độ" là nghĩa chuyên ngành'],
  望: ['mong muốn; hy vọng', '"trăng tròn" là nghĩa cổ'],
  告: ['báo; nói', '"mặc khải" sai ngữ cảnh'],
  判: ['phán đoán; con dấu', '"chữ ký" chưa sát'],
  更: ['thay đổi; hơn nữa', '"trở nên muộn; canh đêm" là nghĩa cổ'],
  首: ['cổ; đầu', '"bài hát" là cách dùng làm từ đếm thơ'],
  才: ['tài năng; tuổi', '"thiên tài" quá nghĩa'],
  殺: ['giết', '"giảm" là nghĩa từ ghép'],
  適: ['phù hợp', '"thỉnh thoảng" sai nghĩa'],
  偶: ['ngẫu nhiên; số chẵn', '"tình cờ" giữ, sửa thứ tự nghĩa'],
  背: ['lưng; chiều cao', 'nghĩa chính là lưng'],
  程: ['mức độ; trình độ', '"luật" sai nghĩa'],
  髪: ['tóc', 'lỗi lặp chữ "tóc đầu"'],
  側: ['bên; phía', '"nghiêng" sai nghĩa'],
};

export function buildKanjiMeaningVi(books, sourceSha256) {
  const kanji = {};
  const written = [];
  const corrected = {};
  for (const book of books) {
    for (const entry of book.kanji ?? []) {
      if (entry.character in kanji) continue;
      const meaning = lessonMeaning((entry.meanings_vi ?? []).map((m) => String(m).trim()).filter(Boolean));
      if (meaning && CORRECTED_MEANINGS[entry.character]) {
        const [fixed, reason] = CORRECTED_MEANINGS[entry.character];
        kanji[entry.character] = fixed;
        corrected[entry.character] = { source: meaning, reason };
      } else if (meaning) kanji[entry.character] = meaning;
      else if (WRITTEN_MEANINGS[entry.character]) {
        kanji[entry.character] = WRITTEN_MEANINGS[entry.character];
        written.push(entry.character);
      } else throw new Error(`Kanji ${entry.character} has no Vietnamese meaning; add it to WRITTEN_MEANINGS.`);
    }
  }
  return { source: 'VNJPClub (sách tổng hợp JLAS)', sourceSha256, written, corrected, kanji };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = path.resolve(process.argv[2] ?? '.');
  const data = await readLearningContent(root);
  const names = LEVELS.map((level) => `jlas_compiled_book_${level}`);
  const sha = Object.fromEntries(names.map((name) => [name, data.section_sha256[name]]));
  const text = JSON.stringify(buildKanjiMeaningVi(names.map((name) => data.sections[name]), sha)) + '\n';
  const target = path.join(root, OUTPUT);
  if (process.argv.includes('--check')) {
    if ((await fs.readFile(target, 'utf8')) !== text) throw new Error(`${OUTPUT} is stale`);
  } else {
    await fs.mkdir(path.dirname(target), { recursive: true });
    await fs.writeFile(target, text, 'utf8');
  }
  const out = JSON.parse(text);
  console.log(JSON.stringify({ kanji: Object.keys(out.kanji).length, written: out.written.length, corrected: Object.keys(out.corrected).length,
    bytes: Buffer.byteLength(text), sha256: createHash('sha256').update(text).digest('hex').slice(0, 12) }));
}
