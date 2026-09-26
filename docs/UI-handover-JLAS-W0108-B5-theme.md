# Bàn giao UI — JLAS-W0108 (B5: tone tím–xanh lá + dark/light + "giật dây")

> **Cập nhật khôi phục UI 2026-09-25:** xem mục 8 và 9 cuối tài liệu. Bảng màu và danh sách phạm vi bên dưới ghi lại điểm xuất phát. **Mục 9 thay thế mô tả nút theme trong header:** dùng dây SVG gốc treo riêng, không dùng nút mặt trăng trong nav.

- **Ticket:** JLAS-W0108 — JLPT full learning journey (survey → Smart Test → learning path WARM_UP + GOAL_LEARNING)
- **Nhánh:** `feat/JLAS-W0108-jlpt-path-full-coverage`
- **Ngày bàn giao:** 2026-09-25
- **Phạm vi:** chỉ frontend (HTML/CSS/JS). Không đụng backend, không migration, không seed.
- **App chạy được:** `mvn spring-boot:run` (profile mặc định `local`, port 8080, MySQL `jlas` root/root). DB đã đồng bộ tới migration 013.

---

## 1. Mục tiêu (yêu cầu gốc B5)

> "Làm lại UI tone **tím + xanh lá** + **dark/light** + **giật dây** thống nhất suốt survey → bài học → lộ trình."

Trong đó **"giật dây" = nút bật/tắt light/dark mode** (toggle switch hình mặt trời / mặt trăng) đặt trên header, hoạt động **thống nhất trên cả 3 trang** của hành trình học, và **nhớ lựa chọn** của người dùng (persist qua reload).

Cảm giác mong muốn: một tone tím (brand) làm màu chủ đạo, xanh lá làm màu thành công/tiến độ, sạch sẽ và nhất quán — không còn sót màu **xanh dương** (blue) cũ của template cũ.

---

## 2. Phạm vi — 3 "surface" của hành trình

| # | Màn hình | Route | Template | CSS |
|---|---|---|---|---|
| 1 | Khảo sát Smart Test (survey) | `/survey` | `templates/smart-test-survey.html` | `static/css/pages/smart-test.css` |
| 2 | Lộ trình học (learning path) | `/learning/path` | `templates/learning/path.html` | `static/css/pages/learning-path.css` |
| 3 | Bài kiểm tra gate + lesson quiz | `/learning/path/gate-quiz` | `templates/learning/lesson-quiz.html` | `static/css/pages/lesson-quiz.css` |
| 3b | Warm-up flashcard (kanji/từ vựng sai) | `/learning/.../flashcard` | `templates/learning/warm-up-flashcard.html` | (dùng chung shell + inline) |

**Ngoài scope (đừng đụng, đang bị bỏ dần):** các template "Onyomi" cũ (`templates/learning/lesson.html`, `index.html`, `quiz.html`, `flashcards.html`, `grammar.html`, `rules.html`, `final-quiz.html`, `quiz-detail.html`, `fragments/rule-conversion.html`) + `static/css/pages/mau-lesson.css`. Trang login (`/login`, `login.css`) cũng ngoài scope.

---

## 3. Hiện trạng design system

Ứng dụng đã có một design system chung, dùng ở hầu hết các trang. AI làm tiếp **bắt buộc phải đọc** các file này trước khi sửa:

| File | Vai trò |
|---|---|
| `static/css/tokens.css` | **Nguồn chân lý** của design tokens ("Komorebi" — tím + xanh lá), có cả block `:root` (light) và `html[data-theme="dark"]` (dark). |
| `static/css/theme-bridge.css` | Map các biến CSS cũ → token mới (để module cũ không vỡ). |
| `static/css/base.css`, `layout.css`, `sakura-background.css` | Nền, layout, hiệu ứng sakura. |
| `static/css/components/*.css` | `header.css`, `brand.css`, `platform-nav.css`, `footer.css`, `user-menu.css`, `buttons.css`, `cards.css`, `inputs.css`, `forms.css`, `dropdowns.css`, `toast.css`, `kitsu-guide.css`, `retention-live.css`. |
| `static/css/jlab-base-shell.css` | Style của shell điều hướng chung. |
| `static/js/jlab-base-shell.js` | **Shell JS chung** — dựng markup nav vào `#jlabBaseShellMount`, có map `ids`, hàm `shellMarkup()/bindEvents()/mount()`, và **nút toggle theme**. |
| `templates/fragments/head.html` | Fragment `<head>` (2 fragment: `standard` dùng cho survey/path/quiz, `feature` cho module aicall/learning cũ). Load tokens + components + shell. |

### Cơ chế dark mode (đã có sẵn)

- Dark mode điều khiển bằng attribute `data-theme` trên thẻ `<html>`: `html[data-theme="dark"]`.
- `tokens.css` override toàn bộ token trong block `html[data-theme="dark"]`.
- Toggle + persist đã cài trong `jlab-base-shell.js`: nút `#jlabBaseThemeToggle` (class `icon-btn jlab-theme-toggle`, icon material `dark_mode`/`light_mode`), lưu vào `localStorage` key **`jlas-theme`** (giá trị `light`/`dark`), áp dụng lại lúc `mount()`.
- Chống flash trắng (FOUC): `head.html` đã nhúng 1 `<script>` inline set `data-theme` trước khi paint (đọc cùng key `jlas-theme`).

> Lưu ý: nếu AI khác đổi tên key localStorage hoặc cách set theme, phải sửa **đồng bộ** cả 2 chỗ: inline script trong `head.html` (cả `standard` + `feature`) và `jlab-base-shell.js`.

---

## 4. Bảng màu hiện tại (tokens chính)

### Light (`:root`)

| Token | Giá trị | Ý nghĩa |
|---|---|---|
| `--color-primary` | `#4f378a` | Tím brand (nút chính, link, accent) |
| `--color-primary-container` | `#6750a4` | Tím đậm (hover/viền/tab active) |
| `--color-on-primary` | `#ffffff` | Chữ trên nền tím |
| `--color-success` | `#006947` | Xanh lá (đúng/hoàn thành/tiến độ) |
| `--color-success-bg` | `#d6f5e7` | Nền xanh lá nhạt |
| `--color-tertiary` | `#765b00` | Vàng (accent phụ, không bắt buộc dùng) |
| `--color-on-surface` | `#161d1f` | Chữ chính |
| `--color-on-surface-variant` | `#494551` | Chữ phụ |
| `--color-surface-lowest` | `#ffffff` | Nền card |
| `--color-surface` / `--color-background` | `#f4fafd` | Nền trang |
| `--color-outline-variant` | `#cbc4d2` | Viền |
| `--color-error` | `#ba1a1a` | Đỏ (sai/lỗi) |

### Dark (`html[data-theme="dark"]`)

| Token | Giá trị | Ý nghĩa |
|---|---|---|
| `--color-primary` | `#d3bcff` | Tím nhạt (đóng vai "màu chính" trên nền tối) |
| `--color-primary-container` | `#4f378a` | Tím đậm (nền nút/viền) |
| `--color-on-primary` | `#33106d` | Chữ trên nền tím nhạt |
| `--color-on-primary-container` | `#ecdfff` | Chữ trên nền tím đậm |
| `--color-success` | `#7ad5a0` | Xanh lá nhạt |
| `--color-success-bg` | `#103a24` | Nền xanh lá tối |
| `--color-surface-lowest` | `#141218` | Nền card |
| `--color-surface` | `#1d1b20` | Nền trang |
| `--color-on-surface` | `#e6e1ea` | Chữ chính |
| `--color-on-surface-variant` | `#cbc6d2` | Chữ phụ |
| `--color-outline-variant` | `#48434f` | Viền |
| `--color-error` | `#ffb4ab` | Đỏ nhạt |

> **Điểm cần xem lại (khả năng người dùng chê "dở"):** các nền light hiện tại (`#f4fafd`, `#eef5f7`, `#e8eff1`, `#e2e9ec`) có **ngả xanh-cyan nhẹ** (do `fa/f7/f1` ở kênh blue-green), chưa "tím xanh lá" thuần. Nếu muốn tone tím–xanh lá rõ hơn, điều chỉnh nhóm `--color-surface*` về trắng/ngà ấm hoặc pha chút tím (`#f7f5fb`, `#f1eef7`, …) thay vì cyan.

---

## 5. Những gì đã làm (điểm xuất phát — AI mới có thể build tiếp hoặc làm lại)

Đã hoàn thành bước cơ bản của B5 (chưa commit — vẫn là working tree):

1. `tokens.css` — thêm đầy đủ block dark `html[data-theme="dark"]` (tím + xanh lá).
2. `theme-bridge.css` — sửa các giá trị blue còn sót → tím.
3. `jlab-base-shell.js` — thêm nút toggle theme ("giật dây") + `currentTheme/applyTheme/toggleTheme` + persist `localStorage` + khởi tạo theme sớm.
4. `user-menu.css` — thêm style kích thước icon toggle.
5. `lesson-quiz.css` — chuyển hardcode màu → token (`--lq-* = var(--color-*)`).
6. `learning-path.css` — chuyển hardcode màu → token; sửa `color: white/#fff` → `var(--color-on-primary)` để đúng cả 2 theme.
7. Legacy templates (`templates/learning/*.html`) + `mau-lesson.css` — quét cơ học blue → tím (`#0058be→#4f378a`, `#2170e4→#6750a4`, `#eff4ff→#e9ddff`, `rgba(0,88,190,…)→rgba(79,55,138,…)`).
8. `head.html` (cả `standard` + `feature`) — thêm inline script chống FOUC.

`smart-test.css` (survey) **đã** dùng token tím + xanh lá sẵn, không cần đổi.

---

## 6. Tiêu chí chấp nhận (checklist "done")

1. **3 trang** `/survey`, `/learning/path`, `/learning/path/gate-quiz` nhìn **đồng bộ** một tone (tím chủ đạo + xanh lá thành công).
2. **Nút "giật dây"** (mặt trời/mặt trăng) hiện trên header ở cả 3 trang (và trang chủ), bấm đổi light↔dark ngay lập tức, không cần reload.
3. **Nhớ lựa chọn**: reload / chuyển trang vẫn giữ đúng theme (không về mặc định).
4. **Không flash trắng** khi vào dark mode lúc load trang.
5. **Không còn màu xanh dương (blue) cũ** (#0058be, #2170e4, #eff4ff, rgba(0,88,190,…)) trong 3 surface + component chung.
6. **Đủ contrast** đọc được ở cả 2 theme (chữ trên nền, trạng thái đúng/sai, tab active, nút primary).
7. Responsive ổn ở mobile (không tràn ngang, gutter 16px).
8. Không phá các trang khác dùng chung shell/tokens (home, profile, …).

---

## 7. Ràng buộc & lưu ý

- **Frontend-only**: không sửa Java, không thêm migration/seed. Nếu thêm file CSS/JS phải đặt trong `static/` và link qua Thymeleaf `th:href`/`th:src`.
- **Dùng token, không hardcode màu** trong 3 surface: luôn tham chiếu `var(--color-*)`. Hardcode chỉ chấp nhận ở giá trị token trong `tokens.css`.
- **Theme qua `data-theme` + localStorage key `jlas-theme`** — đừng đổi sang cơ chế khác (nếu đổi phải đổi đồng bộ 2 chỗ như đã note ở mục 3).
- **Không push secret**: `application-local.yaml` là gitignored, đừng dính vào commit.
- Nếu cần xem các quyết định nghiệp vụ liên quan (warm-up, gate quiz, …): xem `docs/plan-2026-09-22-smart-test-learning-path-end-to-end.md`.

## 8. Khôi phục giao diện Stitch tím/lime sau khi ghép nhánh

### Phạm vi thực tế

- Survey, Smart Test, lộ trình, gate/lesson quiz, warm-up flashcard, trang ôn ngữ pháp và trang bài học số hóa.
- Route `/learning/lesson/{id}` hiện chuyển tới trang `textbooks/lesson.html`. Vì vậy template này và `textbook-lesson.js` nằm trong phạm vi. `learning/grammar.html` hiện phục vụ ôn mẫu ngữ pháp trong lộ trình, được áp dụng cùng theme.
- Thêm class `journey-ui` cho các trang trên. `css/pages/learning-journey.css` chứa bố cục và màu theo token; palette được giới hạn tại `html:has(body.journey-ui)` trong `tokens.css`.
- Light: nền `#f8fafc`, card trắng, tím `#7c3aed`, CTA lime `#a3e635`. Dark: nền `#0b0d13`, card `#171b26`, tím `#c084fc`, lime `#a3e635`.
- Lộ trình dùng hero, card tiến độ, danh sách ngày, cột mục tiêu, nền lưới mờ. Nội dung, số liệu và trạng thái vẫn lấy từ API hiện có.
- Bài học có khối công thức, ghi chú, ví dụ/âm thanh và cột mục lục trên desktop. Mục lục chỉ hiện phần có dữ liệu, đồng bộ với tab; mobile dùng tab. Có liên kết quay về lộ trình.
- Quiz tách các câu hỏi thành card, đáp án được chọn có viền lime; nút nộp luôn dễ tiếp cận. Không đổi quy tắc chấm bài.
- Warm-up có bố cục thẻ và hiệu ứng lật riêng, sửa trường hợp hai mặt hiển thị đồng thời do stylesheet cũ giới hạn theo class trang khác.

### Dây giật và theme — bản đầu, đã được thay thế ở mục 9

- `jlab-base-shell.js`: kéo xuống từ 28px rồi thả để đổi theme, có giới hạn độ kéo. Hủy kéo không đổi theme; bỏ qua click phát sinh sau một lần kéo để tránh đổi hai lần.
- Bấm, Enter/Space vẫn dùng được. Nút có tên truy cập, `aria-pressed`, trạng thái focus; hỗ trợ giảm chuyển động.
- Giữ key `jlas-theme`, đồng bộ khi tab khác đổi theme; storage bị chặn thì vẫn đổi được theme trong trang hiện tại.
- Warm-up bổ sung script áp dụng theme trước paint. Shell dùng cùng nút cho toàn ứng dụng.

### Kiểm tra đã thực hiện

- `node --test src/test/frontend/*.test.cjs`: **9/9 đạt** (5 kiểm tra lộ trình sẵn có và 4 kiểm tra dây giật/theme).
- Kiểm tra cú pháp JavaScript cho shell và trang bài học.
- Browser kiểm tra UI bằng template, CSS và JS thật với API mẫu biệt lập, ở desktop 1440px và mobile 390px: survey, Smart Test, lộ trình, quiz, bài học, ôn ngữ pháp, flashcard.
- Đã kiểm tra kéo dây, Enter, lựa chọn theme sau chuyển trang/reload; chọn radio; bước survey; mục lục ↔ tab bài học; mặt thẻ sau khi lật. Kiểm tra mobile không tràn ngang ở lộ trình, Smart Test, quiz và bài học.
- Có server preview tái lập: `node src/test/frontend/journey-preview.cjs`, chỉ lắng nghe `127.0.0.1:4179`. Các URL mẫu: `/survey`, `/smart-test`, `/learning/path`, `/learning/lesson/102`, `/learning/lesson/102/quiz`, `/grammar`, `/warmup`. Dữ liệu chỉ phục vụ UI, không ghi DB và không đóng gói vào ứng dụng.
- **Giới hạn:** app thật chạy được nhưng trang survey yêu cầu đăng nhập. Chưa xác minh end-to-end đăng nhập, lưu tiến độ, chấm bài hoặc audio server trên dữ liệu DB thật. Preview không thay thế các kiểm tra đó, không phải bằng chứng khớp từng pixel với ảnh mẫu.

### Khi ghép nhánh tiếp

Giữ stylesheet `learning-journey.css` sau CSS trang/shell trong head standard và head riêng của warm-up; giữ class `journey-ui` trên body. Không thay dữ liệu thật bằng số liệu mẫu trong ảnh. Lượt khôi phục này không thay Java, DB, seed hay hợp đồng API.

## 9. Sửa theo phản hồi: dây phải giống bản gốc, nav phải đồng bộ

- Tìm lại bản gốc tại commit `eb58bda`, gồm `static/js/theme.js` và `static/css/components/pullcord.css`. Khôi phục dây SVG 16 đoạn có vật lý Verlet, tay nắm tròn, kéo qua ngưỡng để đổi theme và đung đưa khi thả. Giữ màu tím phù hợp hai theme, vị trí desktop gốc (right 150px); mobile đặt sát mép phải.
- Xóa nút mặt trăng và dây giả bằng pseudo-element trong nav. Shell tự nạp `theme.js` một lần, dây được mount ngoài header. Không cần mỗi trang khai báo riêng. `theme.js` khởi tạo idempotent, xử lý pointer primary/cancel, Enter/Space, reduced motion và đồng bộ storage.
- Theme theo thứ tự lựa chọn đã lưu → hệ điều hành; init không tự lưu lựa chọn hệ thống. Các script chống flash dùng cùng quy tắc.
- Header sở hữu palette riêng và dùng thống nhất giữa trang học và các trang khác trong cùng theme. Bỏ override nav trong `home.css` (nền trong suốt, lề, màu riêng); giữ nội dung hero trang chủ. Dùng `scrollbar-gutter: stable` để header không dịch ngang khi chuyển giữa trang dài/ngắn.
- Kiểm tra browser trên dữ liệu fixture: dây SVG kéo đổi theme đúng một lần; Enter và menu Học tập hoạt động; không còn nút theme cũ. Đo header mobile trên survey/path/lesson/warm-up/home đều x=12, y=8, width=356, height=106 ở viewport 390px, không tràn ngang. Desktop 1440px cũng có hình học thống nhất. Light/dark dùng cùng palette nav; mỗi trang chỉ có một dây sau khi script nạp xong.
- Test cập nhật: `node --test src/test/frontend/*.test.cjs` **10/10 đạt**; đã kiểm tra cú pháp `theme.js` và `jlab-base-shell.js`. Các giới hạn về kiểm tra DB/auth thật tại mục 8 vẫn áp dụng.
