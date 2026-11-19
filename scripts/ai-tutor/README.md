# Gia sư AI J-LAS: cấu hình và vận hành

Gia sư AI (nút "Hỏi gia sư" trên các trang học, các nút AI ở trang tra cứu) trả lời dựa trên dữ liệu J-LAS. Không cần máy
có GPU hay dịch vụ chạy riêng: chỉ cần App Service và MySQL như phần còn lại của web. Chi tiết cách tìm nguồn, các tầng
trả lời và trang duyệt: [RAG.md](RAG.md).

| Phần | Dùng gì | Cấu hình |
|---|---|---|
| Trả lời câu hỏi | API kiểu OpenAI, mặc định DeepSeek V4.1 Flash qua Deploy (deploy.com) | `tutor.llm.*` |
| Tìm nội dung giáo trình (embedding) | `baai/bge-m3` qua OpenRouter, dùng key OpenRouter chung hoặc key riêng `OPENROUTER_FEATURE_KEYS_TUTOR_SEARCH` | `kaiwa.rag.embedding-model` |
| Chỉ mục tìm kiếm | Bảng MySQL `rag_chunk` (migration 084); app nạp vào bộ nhớ và tự tìm | không cần cấu hình |
| AI Call và các tính năng AI khác | OpenRouter, không đổi | `openrouter.*` |

## Bật gia sư khi deploy

1. Chạy các migration của gia sư trên DB: `082_tutor_ai_answer.sql`, `083_tutor_antonym_suggestion.sql`, `084_rag_chunk.sql`
   (cùng `080`, `081` nếu chưa chạy). Đều chạy lại an toàn.
2. Đặt biến môi trường cho App Service (key không ghi vào file trong repo):

   ```
   KAIWA_RAG_ENABLED=true
   TUTOR_LLM_API_KEY=<Secret Key của Deploy>
   # mặc định đã là Deploy + DeepSeek V4.1 Flash; chỉ đặt khi đổi nhà cung cấp hoặc model:
   # TUTOR_LLM_BASE_URL=https://api-cdn.deploy.com/api/v1
   # TUTOR_LLM_MODEL=deepseek-ai/deepseek-v4.1-flash-1017
   # TUTOR_AI_FALLBACK=false       # tắt tầng 2 (AI tổng hợp có nhãn khi J-LAS chưa có nội dung); mặc định bật
   ```

   Key OpenRouter (`OPENROUTER_API_KEY`) đã có sẵn cho AI Call; phần embedding dùng chung key đó.
3. Đăng nhập ADMIN và dựng chỉ mục một lần: `POST /api/admin/system/ai/rag/reindex`. Lần đầu embed khoảng 4.200 đoạn,
   mất vài phút, chi phí OpenRouter dưới 0,02 USD. Các lần sau chỉ embed đoạn mới hoặc đã đổi. Chạy lại sau khi thêm hoặc
   sửa nội dung giáo trình (`?lessonId=` để chỉ đồng bộ một bài).

## Kiểm tra nhanh

- `GET /api/admin/system/ai/rag/status`: bật/tắt, số đoạn trong chỉ mục.
- `GET /api/admin/system/ai/rag/search?q=にとって&minScore=0`: xem nguồn gia sư sẽ nhận, không gọi model trả lời.
- `powershell -ExecutionPolicy Bypass -File scripts\ai-tutor\rag-eval.ps1 -Token <token-admin> -Ask`: chạy bộ câu hỏi
  trong `rag-eval-questions.json`.

## Xử lý sự cố

| Triệu chứng | Nguyên nhân thường gặp |
|---|---|
| Gia sư báo "chưa được cấu hình model" | Thiếu `TUTOR_LLM_API_KEY` |
| Gia sư báo "Máy chủ AI trả lỗi" | Key Deploy sai hoặc hết hạn mức; xem log `Tutor API error` |
| Reindex lỗi "Không gọi được model tìm kiếm" | Key OpenRouter sai hoặc hết tiền; xem trang admin "Chi phí AI" |
| Gia sư luôn trả lời "chưa có nội dung" | Chưa reindex (status báo 0 đoạn) |
