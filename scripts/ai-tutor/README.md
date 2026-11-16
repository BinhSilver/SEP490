# Phase 1 — Chạy AI Call bằng Ollama cục bộ

Mục tiêu: AI Call (module `kaiwa`) trả lời bằng model chạy trên máy chủ AI của nhóm (RTX 3070 Ti 8 GB), không cần API key trả phí. Spring Boot vẫn là cổng duy nhất; trình duyệt không gọi Ollama trực tiếp.

## 1. Thay đổi trong code

| File | Thay đổi |
|---|---|
| `src/main/java/com/jlas/kaiwa/config/OpenRouterConfig.java` | Thêm `kaiwa.llm.provider` = `openrouter` (mặc định, hành vi cũ) hoặc `ollama`. Khi `ollama`: URL, model lấy từ `kaiwa.llm.ollama.*`, không gửi key OpenRouter. Timeout cấu hình được. |
| `src/main/java/com/jlas/kaiwa/service/ai/OpenRouterClient.java`, `OllamaNativeChat.java` | Khi chạy cục bộ: chat gọi API gốc `/api/chat` của Ollama với `think:false`, `keep_alive`, `num_ctx`, `format` (JSON schema cho lượt hội thoại có kịch bản). Không gửi header `Authorization`, `HTTP-Referer`, `X-Title` và field `reasoning` (riêng OpenRouter). Thêm `probe()` đo độ trễ một lượt ngắn. |
| `src/main/java/com/jlas/kaiwa/service/ai/OllamaWarmUp.java` | Khi app khởi động, gọi thử model ở luồng nền để nạp sẵn vào VRAM (tắt bằng `OLLAMA_WARM_UP=false`). |
| `src/main/java/com/jlas/kaiwa/memory/MemoryAiClient.java` | Bộ nhớ người học dùng chung nhà cung cấp; chat cục bộ cũng qua `/api/chat` (tắt thinking); embedding cục bộ dùng `kaiwa.llm.ollama.embedding-model` (mặc định `bge-m3`) qua `/v1/embeddings`. Vector cũ của model khác tự được tạo lại nhờ cơ chế `embedding_model` có sẵn. |
| `src/main/java/com/jlas/kaiwa/controller/api/AiProviderAdminController.java` | `GET /api/admin/system/ai/health`, chỉ ADMIN: gọi thử model, trả provider, model, độ trễ, loại lỗi (không trả nội dung lỗi của nhà cung cấp). |
| `src/main/resources/application.yaml` | Khối `kaiwa.llm.*` và `aicall.turn.max-concurrent-turns` (mặc định giữ 20). |
| `scripts/ai-tutor/benchmark-ollama.ps1`, `phase1-prompts.json` | Script đo trên máy thật. |

Không thêm bảng, không đổi schema, không đổi quy tắc tính phí. Khi Ollama tắt hoặc quá thời gian, lượt chat báo lỗi và `AiCallTurnService` hoàn lại hạn mức/CP như trước; không có chuyển ngầm sang API cloud.

## 2. Cài đặt trên máy chủ AI (Windows)

1. Cài Ollama cho Windows từ trang chính thức. Đã đo với Ollama 0.40.5.
2. Đặt biến môi trường hệ thống (System Properties → Environment Variables), rồi khởi động lại Ollama:

   ```
   OLLAMA_HOST=127.0.0.1:11434      # chỉ máy này gọi được Ollama; Spring Boot chạy cùng máy
   OLLAMA_NUM_PARALLEL=2            # số request GPU xử lý song song
   OLLAMA_MAX_LOADED_MODELS=2       # chat + embedding cùng nằm trong VRAM
   OLLAMA_KEEP_ALIVE=30m            # tránh nạp lại model sau mỗi lần nghỉ
   OLLAMA_FLASH_ATTENTION=1
   ```

3. Tải model và đo:

   ```powershell
   ollama pull qwen3.5:9b
   ollama pull bge-m3
   cd <thư mục repo>
   powershell -ExecutionPolicy Bypass -File scripts\ai-tutor\benchmark-ollama.ps1
   # so sánh thêm model khác:
   powershell -ExecutionPolicy Bypass -File scripts\ai-tutor\benchmark-ollama.ps1 -Models qwen3.5:4b,gemma3:4b
   ```

   Kết quả nằm ở `target\ai-tutor-benchmark\<thời gian>\results.json` và `summary.md`. Gửi lại hai file này để chốt model.

   Model mặc định là `qwen3.5:9b` (6,6 GB; đo ngày 2026-11-16 trên RTX 3070 Ti 8 GB: bám nguồn tốt hơn `qwen3.5:4b`, không bịa quy tắc chia, nhưng chậm gần gấp 3 — trung bình 7,5 giây mỗi câu so với 2,6 giây — và dùng 7,9/8,2 GB VRAM cùng bge-m3). Máy yếu hơn thì đặt `OLLAMA_CHAT_MODEL=qwen3.5:4b`. Muốn kho model nằm ngoài ổ C thì chạy `ollama serve` với `OLLAMA_MODELS=<thư mục>` và trỏ `OLLAMA_BASE_URL` tới cổng của nó. Model này mặc định bật thinking: khi bật, nó dùng hết 2048 token mà không ra câu trả lời. J-LAS và script luôn gửi `think:false`; nếu tự gọi Ollama bằng tay cũng phải tắt. Kiểm tra giấy phép model trước khi dùng ngoài phạm vi đồ án.

## 3. Bật Ollama trong J-LAS

Thêm vào `src/main/resources/application-local.yaml` (file đã bị gitignore) hoặc đặt biến môi trường:

```yaml
kaiwa:
  llm:
    provider: ollama
    read-timeout-ms: 90000          # lần nạp model đầu tiên có thể chậm
    ollama:
      base-url: http://127.0.0.1:11434/v1
      model: qwen3.5:9b
      keep-alive: 30m               # giữ model trong VRAM; nạp lạnh mất ~20 s
      think: false
      num-ctx: 4096
      embedding-model: bge-m3
aicall:
  turn:
    max-concurrent-turns: 2         # bằng OLLAMA_NUM_PARALLEL; lượt thừa xếp hàng rồi báo quá tải, không bị trừ phí
```

Biến môi trường tương đương: `KAIWA_LLM_PROVIDER=ollama`, `OLLAMA_CHAT_MODEL`, `OLLAMA_KEEP_ALIVE_APP`, `OLLAMA_THINK`, `OLLAMA_NUM_CTX`, `OLLAMA_WARM_UP`, `OLLAMA_EMBEDDING_MODEL`, `OLLAMA_BASE_URL`, `KAIWA_LLM_READ_TIMEOUT_MS`, `AICALL_MAX_CONCURRENT_TURNS`.

Kiểm tra qua Spring Boot (cần token của tài khoản ADMIN):

```powershell
curl.exe -H "Authorization: Bearer <token-admin>" http://localhost:8080/api/admin/system/ai/health
```

Kết quả mong đợi: `"ok": true`, `"provider": "ollama"`, `latencyMs` là độ trễ thật. Sau đó mở `/ai-call` → Free chat và nói chuyện thử.

## 4. Test tự động

```powershell
.\mvnw.cmd -B test -Dtest="LocalLlmProviderTest,AiProviderAdminControllerTest"
```

`LocalLlmProviderTest` dựng một HTTP server thật trên 127.0.0.1 đóng vai Ollama để kiểm tra URL, không gửi key, giữ UTF-8 tiếng Nhật, timeout, lỗi model chưa tải, server tắt, embedding và gọi đồng thời. Các test này **không** đo chất lượng hay tốc độ model; việc đó do script benchmark làm trên máy thật.

## 5. Xử lý sự cố

| Triệu chứng | Nguyên nhân thường gặp |
|---|---|
| `/health` trả `UNREACHABLE_OR_TIMEOUT` | Ollama chưa chạy, sai `base-url`, hoặc model đang nạp lâu hơn `read-timeout-ms` |
| `/health` trả `HTTP_404` | Chưa `ollama pull` đúng tên model trong `kaiwa.llm.ollama.model` |
| Chat báo "message content empty" | Thinking đang bật (`think: true`) và model hết token khi đang nghĩ; để `think: false` |
| Lượt chat đầu tiên sau khi nghỉ lâu chậm ~30 s | Model đã bị gỡ khỏi VRAM; tăng `keep-alive` hoặc `OLLAMA_KEEP_ALIVE` |
| Chậm dần khi nhiều người dùng | GPU bão hoà: giảm `max-concurrent-turns`, giữ `OLLAMA_NUM_PARALLEL` bằng giá trị đó |
| VRAM đầy, model chạy một phần trên CPU | Xem `ollama ps` (cột PROCESSOR); dùng model nhỏ hơn hoặc giảm `num_ctx` |
