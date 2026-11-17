# RAG từ nội dung giáo trình J-LAS

Mục tiêu: gia sư AI trả lời dựa trên dữ liệu J-LAS thay vì kiến thức tự thân của model 4B (Phase 1 đo được model bịa âm Hán Việt và sai ngữ pháp N3). MySQL vẫn là nguồn gốc; bảng chỉ mục `rag_chunk` dựng lại được bất cứ lúc nào. Cấu hình khi deploy: [README.md](README.md).

## 1. Cách hoạt động

Hai đường lấy nguồn cho mỗi câu hỏi:

| Đường | Dùng cho | Cách làm |
|---|---|---|
| Tra cứu chính xác trong MySQL | Kanji (âm Hán Việt, On, Kun, nghĩa), từ vựng (cách đọc, nghĩa) | Tách cụm tiếng Nhật trong câu hỏi (勉強する → 勉強する, 勉強), tìm đúng `vocabulary_entry.expression` và `kanji.character`. Không qua vector vì phải đúng từng chữ. |
| Tìm ngữ nghĩa trong chỉ mục `rag_chunk` | Mẫu ngữ pháp, câu ví dụ, hội thoại, bài đọc | Câu hỏi được embed bằng `baai/bge-m3` qua OpenRouter, lấy `top-k` đoạn có điểm cosine ≥ `min-score`. App nạp cả chỉ mục vào bộ nhớ (khoảng 4.200 đoạn ≈ 17 MB) và tự tính, mỗi máy chủ kiểm lại bảng tối đa 30 giây một lần. |
| Tìm đúng tên mẫu | Mẫu ngắn toàn hư từ (にとって) mà tìm ngữ nghĩa bỏ sót | Từng cụm chữ Nhật trong câu hỏi được so với tên mẫu (`pattern_key`), mẫu khớp đứng trước. |

Nguồn được đánh mã `[D1]` (từ điển) và `[S1]` (giáo trình) trong prompt; model được yêu cầu chỉ lấy âm Hán Việt, cách đọc, nghĩa và nội dung giáo trình từ nguồn, và nói rõ khi tài liệu J-LAS chưa có.

### Đoạn được index

| Loại | Một đoạn gồm | Trích dẫn trả về |
|---|---|---|
| `GRAMMAR` | 1 `grammar_point` (mẫu, nghĩa, lưu ý, ví dụ) + tối đa 6 câu ví dụ `content_item` của nó | giáo trình, unit, bài, mẫu, từng câu ví dụ kèm `audioUrl`, `startMs`, `endMs` |
| `DIALOGUE` | tối đa 10 dòng `LISTENING` liên tiếp của một phần bài, có tên người nói từ `dialogue_role` | như trên, từng dòng có người nói và mốc audio |
| `READING` | tối đa 10 mục `READING` của một phần bài | như trên |
| `JLPT_GRAMMAR` | 1 mẫu `jpath_grammar` + tối đa 6 câu `jpath_example` hợp lệ | cấp JLPT, mẫu, mã và câu ví dụ; không có audio |
| `CONFUSABLE` | 1 cặp dễ nhầm `jpath_confusable_pair` với hai mục còn hợp lệ | cấp JLPT, hai mã và ví dụ; không có audio |
| `COMM_DIALOGUE` | tối đa 10 dòng `cpath_line` của một tình huống; đoạn đầu kèm mẫu câu `cpath_item` | tên tình huống, người nói và câu thoại; không có audio |

### Nội dung nào được index

- Chỉ bài `lesson.status = PUBLISHED` thuộc giáo trình `textbook.status = PUBLISHED`.
- **Không** index thứ gì có dòng `content_source.source_code = VNJPCLUB` ở cấp giáo trình, unit, bài, mẫu ngữ pháp hoặc mục nội dung (chưa có giấy phép phân phối lại). Bật bằng `kaiwa.rag.include-vnjpclub` chỉ khi đã có giấy phép.
- **Không** index nội dung `AI_GENERATED` chưa có `review_status = DA_DUYET` (giáo trình N3–N1 do AI viết). Bật bằng `kaiwa.rag.include-unreviewed`.
- Dữ liệu J-Path chỉ lấy `AI_CHECKED` hoặc `REVIEWED`, bỏ `report_hidden = 1` và `DRAFT`. Khi bật `kaiwa.rag.include-unreviewed`, lấy thêm `HOLD` (cả mẫu và ví dụ); cặp dễ nhầm chỉ lấy khi cả hai mục còn hợp lệ. Dữ liệu J-Path và Comm-path chỉ vào lần reindex toàn bộ.
- Tra cứu từ vựng bỏ các từ do seed vnjpclub thêm mới (`content_source.note = 'Tu moi tu vnjpclub'`); từ có sẵn mà vnjpclub dùng lại thì vẫn tra.

Trên DB thử (seed của `develop`): 234 đoạn (133 ngữ pháp, 50 hội thoại, 51 bài đọc) từ Sekai N5 và Bảng chữ cái; 1317 dòng, 1084 dòng có mốc audio.

### Đồng bộ (reindex)

- Id của đoạn sinh cố định từ bản ghi MySQL (`grammar_point:12`, `section:500:DIALOGUE:0`), nên chạy lại không tạo bản trùng.
- Mỗi điểm lưu `content_hash`. Reindex so hash: chỉ embed đoạn mới hoặc đổi, xoá điểm không còn trong MySQL (bài bị chuyển về DRAFT, mục bị xoá), giữ nguyên phần còn lại.
- `?lessonId=` chỉ đồng bộ một bài sau khi sửa bài đó, không đụng bài khác.
- Bộ chỉ mục theo model embedding (`collection` = `jlas_content_baai_bge_m3`); đổi model thì sang bộ mới, không trộn vector khác model.
- Lần đầu embed khoảng 4.200 đoạn qua OpenRouter (vài phút, dưới 0,02 USD, ghi vào trang "Chi phí AI" với tên "Gia sư AI: tìm nội dung giáo trình").

## 2. Chỉ mục trong MySQL

Bảng `rag_chunk` (migration `084_rag_chunk.sql`): mỗi dòng là một đoạn với vector float32 (`MEDIUMBLOB`), `content_hash`,
`lesson_id`, `pattern_key` và payload JSON (chữ của đoạn, nguồn trích dẫn). Không có kho vector riêng: với vài nghìn đoạn,
duyệt cosine trong bộ nhớ mất dưới 10 ms. Khi chỉ mục vượt cỡ 100.000 đoạn mới cần một chỉ mục vector thật.

Dung lượng không phình theo người dùng: câu hỏi, câu trả lời AI và gợi ý từ trái nghĩa lưu ở các bảng khác; chỉ mục chỉ
tăng khi giáo trình có thêm nội dung, và reindex ghi đè đúng chỗ.

## 3. Bật RAG trong J-LAS

Biến môi trường (hoặc `application-local.yaml`):

```
KAIWA_RAG_ENABLED=true
KAIWA_RAG_EMBEDDING_MODEL=baai/bge-m3
KAIWA_RAG_TOP_K=5
KAIWA_RAG_MIN_SCORE=0.50
```

Embedding gọi OpenRouter `/embeddings` bằng key OpenRouter chung, hoặc key riêng `OPENROUTER_FEATURE_KEYS_TUTOR_SEARCH` nếu muốn tách chi phí.

API (chỉ ADMIN, cần `Authorization: Bearer <token-admin>`):

| Lệnh | Việc |
|---|---|
| `GET /api/admin/system/ai/rag/status` | Bật/tắt, đọc được chỉ mục không (`storeReachable`), tên bộ chỉ mục, số đoạn |
| `POST /api/admin/system/ai/rag/reindex[?lessonId=]` | Đồng bộ; trả số đoạn, số embed, số giữ, số xoá, thời gian |
| `GET /api/admin/system/ai/rag/search?q=...&limit=&minScore=` | Xem nguồn mà gia sư sẽ nhận, không gọi model chat |
| `POST /api/admin/system/ai/rag/ask` `{"question": "..."}` | Hỏi gia sư có nguồn để đánh giá; chưa mở cho người học (Phase 4) |

## 4. Hiệu chỉnh và đánh giá trên máy thật

`min-score: 0.50` được chọn từ lần đo bge-m3 thật ngày 2026-10-26 (cùng model, khi còn chạy cục bộ): câu ngoài phạm vi cao nhất 0,485, câu có nguồn đúng thấp nhất 0,518. Khoảng cách này mỏng, nên đo lại mỗi khi index thêm nhiều nội dung. Ngưỡng không chặn được câu hỏi về mẫu chưa có trong giáo trình nhưng giống chữ (ようになる khớp なります 0,674); việc đó do prompt xử lý. Đo lại bằng:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\ai-tutor\rag-eval.ps1 -Token <token-admin> -Reindex -Ask
```

Script gọi `/search` với ngưỡng 0 để thấy điểm thật của từng câu hỏi trong `rag-eval-questions.json` (gồm câu ngoài phạm vi), rồi `/ask` để lấy câu trả lời. Chọn `min-score` nằm giữa điểm của các câu có nguồn đúng và các câu ngoài phạm vi. Kết quả: `target\ai-tutor-rag-eval\<thời gian>\summary.md`.

## 5. Test tự động

```powershell
.\mvnw.cmd -B test -Dtest="RagIndexServiceTest,RagContentSourceTest,RagEmbedderAndPromptTest,MysqlVectorStoreTest,RagAdminControllerTest,RagTutorServiceTest,TutorLlmClientTest"
```

| Test | Kiểm tra |
|---|---|
| `RagIndexServiceTest` | Chạy lại không embed lại; id không đổi theo thứ tự; chỉ embed đoạn đổi, xoá đoạn mất; reindex một bài không đụng bài khác; RAG tắt thì từ chối; hai lượt reindex cùng lúc thì lượt sau bị từ chối |
| `RagContentSourceTest` (H2) | Chỉ lấy bài PUBLISHED; bỏ vnjpclub và AI chưa duyệt ở mọi cấp; hội thoại chia 10 dòng; J-Path, cặp dễ nhầm, Comm-path theo luật trạng thái; tra cứu bỏ từ vnjpclub thêm mới |
| `RagEmbedderAndPromptTest` | Embedding qua OpenRouter `/embeddings` theo lô; số vector sai thì không ghi; lỗi mạng không lộ địa chỉ; prompt đánh mã nguồn |
| `MysqlVectorStoreTest` (H2) | Ghi đè cùng id, xoá theo lô, tìm cosine, tìm theo tên mẫu, lọc theo bài, tách bộ chỉ mục |
| `RagAdminControllerTest` | Khách 401; LEARNER/MODERATOR 403 cho reindex, search, ask; ADMIN 200 |
| `TutorLlmClientTest` | Gọi API trả lời có key và `reasoning_effort`; trả lời chữ dần (SSE); lỗi không lộ key; thiếu key báo rõ |

## 6. Xử lý sự cố

| Triệu chứng | Nguyên nhân thường gặp |
|---|---|
| `503 RAG chưa được bật` | Thiếu `KAIWA_RAG_ENABLED=true` |
| `status` báo `storeReachable: false` | Chưa chạy migration 084 (thiếu bảng `rag_chunk`) |
| Reindex lỗi "Không gọi được model tìm kiếm" | Key OpenRouter sai hoặc hết tiền |
| `409` khi reindex | Đang có một lượt reindex khác chạy |
| Tìm không ra đoạn nào | Chưa reindex, hoặc `min-score` quá cao với bge-m3; xem điểm bằng `search?minScore=0` |

## 7. Chatbot gia sư cho học viên

Nút "Hỏi gia sư" ở góc phải dưới các trang học (trang dùng `fragments/head :: standard`). Đây là tính năng riêng, không liên quan AI Call.

- Hiện khi đã đăng nhập và `KAIWA_RAG_ENABLED=true`. Không hiện với khách, trang quản trị, `/smart-test`, `/jlpt/exam` và các trang quiz (để gia sư không làm bài hộ), và không hiện ở AI Call.
- API: `GET /api/tutor/status`, `POST /api/tutor/chat` `{"question": "...", "history": [{"role": "user|assistant", "content": "..."}]}`. Trả `answer` và `sources` (mã `[D1]`/`[S1]` khớp với câu trả lời).
- Hiện chữ dần: `POST /api/tutor/chat/stream` (cùng body) trả từng dòng JSON: nhiều dòng `{"delta": "..."}`, rồi một dòng cuối `{"done": true, "answer", "sources"}` (câu trả lời đã lọc romaji, thay cho phần đã hiện) hoặc `{"error": "..."}`. Câu trả lời cố định (chào hỏi, ngoài phạm vi, âm Hán Việt) chỉ có dòng cuối. Nút nổi dùng đường này.
- Model trả lời: API kiểu OpenAI theo `tutor.llm.*` (mặc định DeepSeek V4.1 Flash qua Deploy, `reasoning_effort=none`). Thiếu key thì gia sư báo chưa được cấu hình. Model lỗi thì báo lỗi, không chuyển sang nhà cung cấp khác. AI Call không đổi (OpenRouter).
- Giới hạn: `TUTOR_MAX_CONCURRENT` câu cùng lúc trên một máy chủ (mặc định 10), chờ tối đa `TUTOR_WAIT_SECONDS` (30), mỗi người `TUTOR_QUESTIONS_PER_HOUR` câu mỗi giờ (60) và một câu một lúc, câu hỏi tối đa 500 ký tự.
- Chỉ trả lời theo nguồn J-LAS. Không tìm được nguồn nào thì trả lời cố định, không gọi model. Hỏi mẫu ngữ pháp bằng romaji (ví dụ `nittote`) thì đổi sang kana (`にっとて`) để tìm; giáo trình không có đúng chữ đó thì báo chưa có mẫu này và nhờ người học viết bằng chữ Nhật, không để model đoán sang mẫu khác. `sources` chỉ gồm các nguồn câu trả lời có trích.
- Hỏi âm Hán Việt thì chép thẳng từ từ điển J-LAS, không gọi model. Câu hỏi khác đã tìm được từ thì bỏ các dòng kanji riêng lẻ, để model không suy nghĩa của từ từ nghĩa từng chữ.
- Ba tầng trả lời. Tầng 1: có nguồn J-LAS (mã [D]/[S]). Tầng 2 (`TUTOR_AI_FALLBACK=true`): câu hỏi có chữ Nhật mà J-LAS không có nội dung (không tìm được nguồn, hoặc tầng 1 trả lời "Tài liệu J-LAS chưa có...") thì AI tổng hợp câu trả lời, gắn nhãn "AI tổng hợp, chưa được người duyệt" (khung viền cam, nhãn ở đầu), không có mục Nguồn; từ tiếng Nhật trong câu trả lời có trong từ điển được liệt kê kèm cách đọc và nghĩa của từ điển. Không dùng tầng 2 cho câu hỏi cách đọc, âm Hán Việt, cấp JLPT. Câu trả lời được lưu ở `tutor_ai_answer` (migration 082, khóa là câu hỏi đã chuẩn hóa): người hỏi sau nhận đúng bản đó, không gọi lại AI và không tính hạn mức. Người duyệt sửa cột `answer` và đổi `status` sang `APPROVED` (đổi nhãn thành "đã được người duyệt") hoặc `REJECTED` (quay về câu "chưa có"). Mỗi người tối đa `TUTOR_AI_FALLBACK_PER_DAY` (20) lượt gọi AI tầng 2 trong 24 giờ. Tầng 3: câu ngoài phạm vi tiếng Nhật bị từ chối như cũ.
- Duyệt: trang `/admin/tutor-review` ("Duyệt câu trả lời AI" trong menu Quản lý nội dung), cho CONTENT_CREATOR và ADMIN, cùng khuôn với "Báo sai lộ trình". Hai loại: câu trả lời tầng 2 (sửa nội dung, "Lưu và duyệt", "Chỉ lưu sửa", "Từ chối") và gợi ý từ trái nghĩa (duyệt, từ chối, hoặc đổi sang từ khác có trong từ điển). Danh sách sắp theo số lần được hỏi, có ô tìm theo từ. API: `GET /api/admin/tutor-review/items?kind=ANSWER|ANTONYM&status=&q=`, `PUT /answers/{id}`, `PUT /antonyms/{id}`.
- Dịch câu ("dịch", "translate"): khi bật tầng 2, đi thẳng tới AI, Nhật → Việt hoặc Việt → Nhật, gắn nhãn "AI dịch · chưa duyệt", kèm cách đọc và nghĩa của các từ có trong từ điển. KHÔNG lưu vào `tutor_ai_answer` (câu cần dịch hầu như không lặp lại và có thể là nội dung riêng tư), vẫn tính vào hạn mức tầng 2. Ngôn ngữ khác bị từ chối. Tầng 2 tắt thì yêu cầu dịch đi đường tầng 1 (prompt cho phép dịch, nguồn không đủ thì nói chưa có).
- Câu chào, cảm ơn và câu hỏi về chính gia sư ("bạn là ai", "dùng model gì") được đáp ngắn cố định, không gọi model. Câu hỏi sức khỏe không có nguồn thì nhắc hỏi bác sĩ hoặc dược sĩ.
- Câu hỏi nối tiếp ngắn (dưới 40 ký tự, ví dụ "cho thêm ví dụ") được tìm nguồn kèm câu hỏi trước của người học.
- Không lưu cuộc trò chuyện ở máy chủ: lịch sử nằm trong tab trình duyệt (sessionStorage), gửi kèm 6 lượt cuối. Log chỉ ghi id người dùng, số nguồn và thời gian, không ghi nội dung câu hỏi.
