# Phase 2: RAG từ nội dung giáo trình J-LAS

Mục tiêu: gia sư AI trả lời dựa trên dữ liệu J-LAS thay vì kiến thức tự thân của model 4B (Phase 1 đo được model bịa âm Hán Việt và sai ngữ pháp N3). MySQL vẫn là nguồn gốc; Qdrant chỉ là chỉ mục có thể dựng lại bất cứ lúc nào.

## 1. Cách hoạt động

Hai đường lấy nguồn cho mỗi câu hỏi:

| Đường | Dùng cho | Cách làm |
|---|---|---|
| Tra cứu chính xác trong MySQL | Kanji (âm Hán Việt, On, Kun, nghĩa), từ vựng (cách đọc, nghĩa) | Tách cụm tiếng Nhật trong câu hỏi (勉強する → 勉強する, 勉強), tìm đúng `vocabulary_entry.expression` và `kanji.character`. Không qua vector vì phải đúng từng chữ. |
| Tìm ngữ nghĩa trong Qdrant | Mẫu ngữ pháp, câu ví dụ, hội thoại, bài đọc | Câu hỏi được embed bằng `bge-m3` (Ollama cục bộ), lấy `top-k` đoạn có điểm cosine ≥ `min-score`. |

Nguồn được đánh mã `[D1]` (từ điển) và `[S1]` (giáo trình) trong prompt; model được yêu cầu chỉ lấy âm Hán Việt, cách đọc, nghĩa và nội dung giáo trình từ nguồn, và nói rõ khi tài liệu J-LAS chưa có.

### Đoạn được index

| Loại | Một đoạn gồm | Trích dẫn trả về |
|---|---|---|
| `GRAMMAR` | 1 `grammar_point` (mẫu, nghĩa, lưu ý, ví dụ) + tối đa 6 câu ví dụ `content_item` của nó | giáo trình, unit, bài, mẫu, từng câu ví dụ kèm `audioUrl`, `startMs`, `endMs` |
| `DIALOGUE` | tối đa 10 dòng `LISTENING` liên tiếp của một phần bài, có tên người nói từ `dialogue_role` | như trên, từng dòng có người nói và mốc audio |
| `READING` | tối đa 10 mục `READING` của một phần bài | như trên |

### Nội dung nào được index

- Chỉ bài `lesson.status = PUBLISHED` thuộc giáo trình `textbook.status = PUBLISHED`.
- **Không** index thứ gì có dòng `content_source.source_code = VNJPCLUB` ở cấp giáo trình, unit, bài, mẫu ngữ pháp hoặc mục nội dung (chưa có giấy phép phân phối lại). Bật bằng `kaiwa.rag.include-vnjpclub` chỉ khi đã có giấy phép.
- **Không** index nội dung `AI_GENERATED` chưa có `review_status = DA_DUYET` (giáo trình N3–N1 do AI viết). Bật bằng `kaiwa.rag.include-unreviewed`.
- Tra cứu từ vựng bỏ các từ do seed vnjpclub thêm mới (`content_source.note = 'Tu moi tu vnjpclub'`); từ có sẵn mà vnjpclub dùng lại thì vẫn tra.

Trên DB thử (seed của `develop`): 234 đoạn (133 ngữ pháp, 50 hội thoại, 51 bài đọc) từ Sekai N5 và Bảng chữ cái; 1317 dòng, 1084 dòng có mốc audio.

### Đồng bộ (reindex)

- Id điểm trong Qdrant sinh cố định từ bản ghi MySQL (`grammar_point:12`, `section:500:DIALOGUE:0`), nên chạy lại không tạo bản trùng.
- Mỗi điểm lưu `content_hash`. Reindex so hash: chỉ embed đoạn mới hoặc đổi, xoá điểm không còn trong MySQL (bài bị chuyển về DRAFT, mục bị xoá), giữ nguyên phần còn lại.
- `?lessonId=` chỉ đồng bộ một bài sau khi sửa bài đó, không đụng bài khác.
- Collection theo model embedding (`jlas_content_bge_m3`); đổi model thì sang collection mới, không trộn vector khác model.
- Không thêm bảng MySQL nào.

## 2. Cài Qdrant trên máy chủ AI (Windows)

1. Tải `qdrant-x86_64-pc-windows-msvc.zip` từ trang Releases của `qdrant/qdrant` trên GitHub (đã thử bản 1.15.4 trên Linux), giải nén vào ví dụ `C:\qdrant`.
2. Chạy chỉ trên loopback (không mở ra mạng LAN hay Internet):

   ```powershell
   cd C:\qdrant
   $env:QDRANT__SERVICE__HOST = "127.0.0.1"
   $env:QDRANT__TELEMETRY_DISABLED = "true"
   .\qdrant.exe
   ```

   Kiểm tra: `curl.exe http://127.0.0.1:6333/` trả về phiên bản. Dữ liệu nằm trong `C:\qdrant\storage`; xoá thư mục này chỉ mất chỉ mục, chạy reindex là có lại.
3. Model embedding: `ollama pull bge-m3` (đã có từ Phase 1).

Nếu sau này Qdrant phải nghe trên LAN, đặt `QDRANT__SERVICE__API_KEY` và cấu hình `kaiwa.rag.qdrant-api-key` cùng giá trị (để trong biến môi trường, không ghi vào file trong repo).

## 3. Bật RAG trong J-LAS

Biến môi trường (hoặc `application-local.yaml`):

```
KAIWA_RAG_ENABLED=true
QDRANT_URL=http://127.0.0.1:6333
KAIWA_RAG_TOP_K=5
KAIWA_RAG_MIN_SCORE=0.50
```

Embedding của RAG luôn gọi Ollama cục bộ (`/api/embed`), kể cả khi chat vẫn dùng OpenRouter: nội dung giáo trình và câu hỏi không bị gửi ra ngoài để embed.

API (chỉ ADMIN, cần `Authorization: Bearer <token-admin>`):

| Lệnh | Việc |
|---|---|
| `GET /api/admin/system/ai/rag/status` | Bật/tắt, Qdrant có phản hồi không, tên collection, số điểm |
| `POST /api/admin/system/ai/rag/reindex[?lessonId=]` | Đồng bộ; trả số đoạn, số embed, số giữ, số xoá, thời gian |
| `GET /api/admin/system/ai/rag/search?q=...&limit=&minScore=` | Xem nguồn mà gia sư sẽ nhận, không gọi model chat |
| `POST /api/admin/system/ai/rag/ask` `{"question": "..."}` | Hỏi gia sư có nguồn để đánh giá; chưa mở cho người học (Phase 4) |

## 4. Hiệu chỉnh và đánh giá trên máy thật

`min-score: 0.50` được chọn từ lần đo bge-m3 thật ngày 2026-10-26 trên RTX 3070 Ti: câu ngoài phạm vi cao nhất 0,485, câu có nguồn đúng thấp nhất 0,518. Khoảng cách này mỏng, nên đo lại mỗi khi index thêm nhiều nội dung. Ngưỡng không chặn được câu hỏi về mẫu chưa có trong giáo trình nhưng giống chữ (ようになる khớp なります 0,674); việc đó do prompt xử lý. Đo lại bằng:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\ai-tutor\rag-eval.ps1 -Token <token-admin> -Reindex -Ask
```

Script gọi `/search` với ngưỡng 0 để thấy điểm thật của từng câu hỏi trong `rag-eval-questions.json` (gồm câu ngoài phạm vi), rồi `/ask` để lấy câu trả lời. Chọn `min-score` nằm giữa điểm của các câu có nguồn đúng và các câu ngoài phạm vi. Kết quả: `target\ai-tutor-rag-eval\<thời gian>\summary.md`.

## 5. Test tự động

```powershell
.\mvnw.cmd -B test -Dtest="RagIndexServiceTest,RagContentSourceTest,RagEmbedderAndPromptTest,AiProviderAdminControllerTest"
# thêm test với Qdrant thật (đang chạy trên 127.0.0.1:6333):
$env:JLAS_TEST_QDRANT_URL = "http://127.0.0.1:6333"; .\mvnw.cmd -B test -Dtest=QdrantVectorStoreTest
```

| Test | Kiểm tra |
|---|---|
| `RagIndexServiceTest` | Chạy lại không embed lại; id điểm không đổi theo thứ tự; chỉ embed đoạn đổi, xoá đoạn mất; reindex một bài không đụng bài khác; RAG tắt thì từ chối; hai lượt reindex cùng lúc thì lượt sau bị từ chối |
| `RagContentSourceTest` (H2) | Chỉ lấy bài PUBLISHED; bỏ vnjpclub và AI chưa duyệt ở mọi cấp; câu ví dụ bị gắn vnjpclub bị bỏ riêng; hội thoại chia 10 dòng kèm người nói và mốc audio; tra cứu bỏ từ vnjpclub thêm mới |
| `RagEmbedderAndPromptTest` | Embedding qua `/api/embed` có `keep_alive`; số vector sai thì không ghi; prompt đánh mã nguồn |
| `QdrantVectorStoreTest` | Với Qdrant thật: tạo collection, upsert 300 điểm, scroll nhiều trang, lọc theo bài, tìm, ghi đè cùng id, xoá |
| `AiProviderAdminControllerTest` | Khách 401; LEARNER/MODERATOR 403 cho reindex, search, ask; ADMIN 200 |

## 6. Xử lý sự cố

| Triệu chứng | Nguyên nhân thường gặp |
|---|---|
| `503 RAG chưa được bật` | Thiếu `KAIWA_RAG_ENABLED=true` |
| `status` báo `qdrantReachable: false` | Qdrant chưa chạy hoặc sai `QDRANT_URL` |
| Reindex lỗi `Ollama embedding failed` | Chưa `ollama pull bge-m3` hoặc Ollama tắt; lần đầu nạp bge-m3 mất ~15 s |
| `409` khi reindex | Đang có một lượt reindex khác chạy |
| Tìm không ra đoạn nào | Chưa reindex, hoặc `min-score` quá cao với bge-m3; xem điểm bằng `search?minScore=0` |

## 7. Chatbot gia sư cho học viên

Nút "Hỏi gia sư" ở góc phải dưới các trang học (trang dùng `fragments/head :: standard`). Đây là tính năng riêng, không liên quan AI Call.

- Hiện khi đã đăng nhập và `KAIWA_RAG_ENABLED=true`. Không hiện với khách, trang quản trị, `/smart-test`, `/jlpt/exam` và các trang quiz (để gia sư không làm bài hộ), và không hiện ở AI Call.
- API: `GET /api/tutor/status`, `POST /api/tutor/chat` `{"question": "...", "history": [{"role": "user|assistant", "content": "..."}]}`. Trả `answer` và `sources` (mã `[D1]`/`[S1]` khớp với câu trả lời).
- Luôn dùng model Ollama trên máy chủ AI (`OLLAMA_CHAT_MODEL`), kể cả khi AI Call dùng OpenRouter. Ollama lỗi thì báo lỗi, không chuyển sang API cloud.
- Giới hạn: `TUTOR_MAX_CONCURRENT` câu cùng lúc cho cả LAN (mặc định 2, Ollama trên một GPU chạy lần lượt), chờ tối đa `TUTOR_WAIT_SECONDS` (30), mỗi người `TUTOR_QUESTIONS_PER_HOUR` câu mỗi giờ (60) và một câu một lúc, câu hỏi tối đa 500 ký tự.
- Không lưu cuộc trò chuyện ở máy chủ: lịch sử nằm trong tab trình duyệt (sessionStorage), gửi kèm 6 lượt cuối. Log chỉ ghi id người dùng, số nguồn và thời gian, không ghi nội dung câu hỏi.
