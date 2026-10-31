# Nguồn dữ liệu lộ trình học J-LAS

Bộ xuất ở giai đoạn 1 (28/10/2026), chưa bổ sung nội dung và chưa chuyển pipeline. Chạy `node tools/learning-path-source/build_learning_path_source.mjs` để sinh lại; thêm `--check` để so byte, không ghi file. Không sửa tay các JSON đầu ra.

## Cấu trúc

- `jlpt/kana.json`: bảng Kana nguồn và quy tắc âm đặc biệt.
- `jlpt/N5.json` … `N1.json`: mỗi cấp gồm chương (danh sách ID theo kỹ năng), mục từ vựng/Kanji/ngữ pháp, bài Đọc/Nghe với nội dung và đáp án nguồn, hội thoại, kho Luyện thêm, nguồn gốc và `gaps[]` do script tính.
- `gaps-report.md`: báo cáo thiếu hụt theo cấp/chương/chủ đề và tổng đề xuất Giai đoạn 3/4.
- `communication/BA.json`, `TRAVEL.json`, `DAILY_LIFE.json`: khảo sát, chủ đề, từ/Kanji, mẫu câu, hội thoại, luyện tập, giọng đọc và ghi công.
- `source-sections/`: bản lưu nguyên giá trị của 42 section, 5 tài nguyên runtime, manifest audio và bảng Kana nguồn. `index.json` giữ metadata cấp gốc và thứ tự bài của section lớn bị chia. Có thể dựng lại dữ liệu cũ từ đây.
- `manifest.json`: SHA-256 của từng file sinh ra và số mục trong file.

## Schema và trạng thái

Mỗi mục JLPT giữ nguyên ID, trường gốc, nghĩa, ví dụ, đáp án, trạng thái, `books` và nhãn nguồn. Các trường thêm: `normalized_expression`, `normalized_reading` = katakana→hiragana(NFKC) (giữ `ー`, `・`), `labels` từ `jlas_unit_labels`, `jlas_example` và `application_questions` cho nội dung N3. `RELEASED`/`NOT_RELEASED` là trạng thái lộ trình; `original_status`, review/publication ở từng nguồn vẫn được giữ. Dữ liệu nguồn chưa duyệt không được tự đổi thành đã duyệt.

Âm thanh chỉ lưu đường dẫn và SHA-256 từ `learning-audio-manifest.json`; không sao chép MP3. BA/TRAVEL/DAILY_LIFE dùng giọng tổng hợp trên trình duyệt. Nguồn gốc JMdict/KANJIDIC2 giữ trong `attribution` và bản lưu nguồn.

Cấp độ chủ đề giao tiếp được suy ra từ hội thoại đang có; `DAILY_LIFE.TIME` chưa có hội thoại nên `level: null`. BA có hội thoại nguồn mức N2; giai đoạn 1 giữ nguyên cấp nguồn, chưa viết lại xuống N3.

Trong 5.583 ví dụ từ vựng J-LAS mang nhãn N3, 5 ID thuộc danh mục từ N4 sau khi sách tổng hợp khử trùng. N4 gắn ví dụ vào đúng từ; N3 giữ toàn bộ map `jlas_n3_vocab_examples` để không mất mục và giữ số đếm nguồn.

Có 2 câu ngữ pháp N4 đã duyệt (`LP-GRAM-N4-26-noi-01`, `LP-GRAM-N4-26-noi-02`) dùng nhãn mẫu `～んです` không khớp chuỗi pattern sách tổng hợp; chúng nằm trong `unlinked_reviewed_application_questions` để người duyệt nối chính xác sau.

## Đối chiếu số lượng

| Cấp | Từ | Kanji | Ngữ pháp | Đọc | Câu đọc | Nghe | Dòng hội thoại |
|---|---:|---:|---:|---:|---:|---:|---:|
| N5 | 1465 | 311 | 314 | 62 | 119 | 100 | 400 |
| N4 | 2016 | 247 | 434 | 79 | 142 | 104 | 400 |
| N3 | 5582 | 194 | 720 | 54 | 129 | 54 | 137 |
| N2 | 5058 | 310 | 821 | 0 | 0 | 0 | 0 |
| N1 | 4157 | 1089 | 483 | 0 | 0 | 0 | 0 |

| Giao tiếp | Chủ đề | Từ | Kanji | Hội thoại |
|---|---:|---:|---:|---:|
| BA | 7 | 140 | 210 | 14 |
| TRAVEL | 6 | 94 | 0 | 8 |
| DAILY_LIFE | 7 | 100 | 0 | 8 |

N3: 1404 câu ngữ pháp J-LAS, 5583 ví dụ J-LAS. Kho Luyện thêm: N3 7 đoạn/13 câu, N4 12 bài/17 câu.

## Đối chiếu toàn bộ 41 section

Không bỏ section nào; bản nháp và lịch sử chỉ được lưu để tra cứu, chưa gộp vào nội dung phát hành.

| Section | Bản lưu | Cách dùng |
|---|---|---|
| `jlpt_sekai_n5_n4` | `source-sections/jlpt_sekai_n5_n4.json` | Giữ nguyên nguồn; trường phù hợp đã gộp vào lộ trình |
| `jlpt_comprehension_n5_n3` | `source-sections/jlpt_comprehension_n5_n3_n5.json`, `source-sections/jlpt_comprehension_n5_n3_n4.json`, `source-sections/jlpt_comprehension_n5_n3_n3.json` | Giữ nguyên nguồn; trường phù hợp đã gộp vào lộ trình |
| `jlpt_n4_kanji` | `source-sections/jlpt_n4_kanji.json` | Giữ nguyên nguồn; trường phù hợp đã gộp vào lộ trình |
| `n4_kanji_selection` | `source-sections/n4_kanji_selection.json` | Giữ bản duyệt/đề xuất để đối chiếu, chưa tự phát hành |
| `supplement_selection` | `source-sections/supplement_selection.json` | Giữ bản duyệt/đề xuất để đối chiếu, chưa tự phát hành |
| `reviewed_vocabulary` | `source-sections/reviewed_vocabulary.json` | Giữ bản duyệt/đề xuất để đối chiếu, chưa tự phát hành |
| `kana_special_sounds` | `source-sections/kana_special_sounds.json` | Giữ nguyên nguồn; trường phù hợp đã gộp vào lộ trình |
| `n1_grammar_correction` | `source-sections/n1_grammar_correction.json` | Giữ bản duyệt/đề xuất để đối chiếu, chưa tự phát hành |
| `n2_grammar_selection` | `source-sections/n2_grammar_selection.json` | Giữ bản duyệt/đề xuất để đối chiếu, chưa tự phát hành |
| `vocabulary_n5_n1` | `source-sections/vocabulary_n5_n1.json` | Giữ nguyên nguồn; trường phù hợp đã gộp vào lộ trình |
| `grammar_n3_n1` | `source-sections/grammar_n3_n1.json` | Giữ nguyên nguồn; trường phù hợp đã gộp vào lộ trình |
| `goal_source_snapshots` | `source-sections/goal_source_snapshots.json` | Giữ nguyên nguồn; trường phù hợp đã gộp vào lộ trình |
| `kanji_n5_n1` | `source-sections/kanji_n5_n1.json` | Giữ nguyên nguồn; trường phù hợp đã gộp vào lộ trình |
| `sekai_n5_comprehension` | `source-sections/sekai_n5_comprehension.json` | Giữ nguyên nguồn; trường phù hợp đã gộp vào lộ trình |
| `jlpt_minna_n5_n4_listening` | `source-sections/jlpt_minna_n5_n4_listening.json` | Giữ nguyên nguồn; trường phù hợp đã gộp vào lộ trình |
| `jlpt_minna_n5_n4_reading` | `source-sections/jlpt_minna_n5_n4_reading.json` | Giữ nguyên nguồn; trường phù hợp đã gộp vào lộ trình |
| `vocabulary_review_batches` | `source-sections/vocabulary_review_batches.json` | Giữ bản duyệt/đề xuất để đối chiếu, chưa tự phát hành |
| `grammar_question_candidates` | `source-sections/grammar_question_candidates.json` | Giữ bản duyệt/đề xuất để đối chiếu, chưa tự phát hành |
| `n1_grammar_review_draft` | `source-sections/n1_grammar_review_draft.json` | Giữ bản duyệt/đề xuất để đối chiếu, chưa tự phát hành |
| `n2_grammar_review_draft` | `source-sections/n2_grammar_review_draft.json` | Giữ bản duyệt/đề xuất để đối chiếu, chưa tự phát hành |
| `historical_context_candidates` | `source-sections/historical_context_candidates.json` | Lưu lịch sử, không dùng làm lộ trình hiện hành |
| `owned_draft_source_manifest` | `source-sections/owned_draft_source_manifest.json` | Lưu lịch sử, không dùng làm lộ trình hiện hành |
| `source_aligned_pilot_archive` | `source-sections/source_aligned_pilot_archive.json` | Lưu lịch sử, không dùng làm lộ trình hiện hành |
| `historical_supplement_v3` | `source-sections/historical_supplement_v3.json` | Lưu lịch sử, không dùng làm lộ trình hiện hành |
| `communication_path_release` | `source-sections/communication_path_release.json` | Giữ nguyên nguồn; trường phù hợp đã gộp vào lộ trình |
| `jlpt_speed_n3_comprehension` | `source-sections/jlpt_speed_n3_comprehension.json` | Giữ nguyên nguồn; trường phù hợp đã gộp vào lộ trình |
| `jlpt_speed_n5_n4_reading` | `source-sections/jlpt_speed_n5_n4_reading.json` | Giữ nguyên nguồn; trường phù hợp đã gộp vào lộ trình |
| `jlas_n3_backbone_snapshot` | `source-sections/jlas_n3_backbone_snapshot.json` | Giữ nguyên nguồn; trường phù hợp đã gộp vào lộ trình |
| `canonical_catalog_n5_n3` | `source-sections/canonical_catalog_n5_n3.json` | Giữ nguyên nguồn; trường phù hợp đã gộp vào lộ trình |
| `jlas_n2_backbone_snapshot` | `source-sections/jlas_n2_backbone_snapshot.json` | Giữ nguyên nguồn; trường phù hợp đã gộp vào lộ trình |
| `jlas_n1_backbone_snapshot` | `source-sections/jlas_n1_backbone_snapshot.json` | Giữ nguyên nguồn; trường phù hợp đã gộp vào lộ trình |
| `jlas_compiled_book_n5` | `source-sections/jlas_compiled_book_n5.json` | Giữ nguyên nguồn; trường phù hợp đã gộp vào lộ trình |
| `jlas_compiled_book_n4` | `source-sections/jlas_compiled_book_n4.json` | Giữ nguyên nguồn; trường phù hợp đã gộp vào lộ trình |
| `jlas_compiled_book_n3` | `source-sections/jlas_compiled_book_n3.json` | Giữ nguyên nguồn; trường phù hợp đã gộp vào lộ trình |
| `jlas_compiled_book_n2` | `source-sections/jlas_compiled_book_n2.json` | Giữ nguyên nguồn; trường phù hợp đã gộp vào lộ trình |
| `jlas_compiled_book_n1` | `source-sections/jlas_compiled_book_n1.json` | Giữ nguyên nguồn; trường phù hợp đã gộp vào lộ trình |
| `jlas_book_grammar_snapshot` | `source-sections/jlas_book_grammar_snapshot.json` | Giữ nguyên nguồn; trường phù hợp đã gộp vào lộ trình |
| `jlas_store_pool` | `source-sections/jlas_store_pool.json` | Giữ nguyên nguồn; trường phù hợp đã gộp vào lộ trình |
| `jlas_unit_labels` | `source-sections/jlas_unit_labels.json` | Giữ nguyên nguồn; trường phù hợp đã gộp vào lộ trình |
| `jlas_n3_grammar_application` | `source-sections/jlas_n3_grammar_application.json` | Giữ nguyên nguồn; trường phù hợp đã gộp vào lộ trình |
| `jlas_n3_vocab_examples` | `source-sections/jlas_n3_vocab_examples.json` | Giữ nguyên nguồn; trường phù hợp đã gộp vào lộ trình |
| `jlas_grammar_example_rewrites` | `source-sections/jlas_grammar_example_rewrites.json` | Giữ nguyên nguồn; trường phù hợp đã gộp vào lộ trình |
