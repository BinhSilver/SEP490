# Thiết kế Learning Path tương tác V2

> Ngày chốt: 2026-09-28
>
> Phạm vi: Learning Path JLPT — warm-up, target package, tiến độ, điểm, hoàn thành và chuyển sang thi thử
>
> Trạng thái: Thiết kế đã được người dùng duyệt; chờ lập implementation plan sau khi người dùng rà file
>
> Thay thế cho: mô hình target bê nguyên trang giáo trình, lesson quiz/daily quiz và warm-up gate quiz trên các lộ trình V2

## 1. Mục tiêu sản phẩm

Learning Path phải giúp người học thao tác trực tiếp với đúng kiến thức cần học, ghi nhớ chủ động và biết rõ mình đang yếu ở đâu. Một ngày học không còn là một lesson sách có nhiều tab và một bài quiz tổng hợp ở cuối. Một ngày học là một `learning package` do lộ trình tổng hợp từ dữ liệu canonical đã được duyệt.

Mỗi target package có bốn phần tuần tự:

1. Từ vựng: học toàn bộ flashcard rồi làm micro-check.
2. Kanji: học toàn bộ flashcard rồi làm micro-check.
3. Ngữ pháp: học và thao tác trực tiếp với từng mẫu rồi làm micro-check.
4. Hội thoại: học trên trang riêng thuộc Learning Path, đủ thời gian hoạt động và cuộn đến cuối.

Người học hoàn thành từng kỹ năng ngay tại chỗ. Khi đủ bốn phần, hệ thống tự hoàn thành package và mở popup kết thúc ngày. Không còn nút hoàn thành bài thủ công, lesson quiz tổng hợp hoặc daily quiz tổng hợp trên luồng V2.

Warm-up dùng cùng ngôn ngữ tương tác nhưng chỉ vá đúng điểm yếu Smart Test đã chứng minh. Warm-up không giao lại nguyên bài hay nguyên giáo trình.

## 2. Nguyên tắc nghiệp vụ

### 2.1 Không sao chép cấu trúc giáo trình vào lộ trình

- Giáo trình là nguồn nội dung canonical và vẫn có trang riêng của module sách.
- Learning Path tham chiếu đúng vocabulary entry, kanji/reading, grammar point, dialogue và nguồn đọc/nghe đã được duyệt.
- Target page của Learning Path có route, template, DTO và trải nghiệm riêng; không nhúng hoặc điều hướng sang trang lesson của sách.
- Giao diện hội thoại được triển khai lại trong Learning Path. Dữ liệu hội thoại vẫn lấy qua contract nội bộ từ nguồn canonical để tránh tạo nguồn sự thật thứ hai.
- Snapshot phục vụ lịch sử attempt được phép lưu nội dung đã phát; snapshot không trở thành dữ liệu canonical mới.

### 2.2 Package đủ bốn phần

Một target package bình thường chỉ được chuyển sang `READY` khi có đủ:

- Một bộ từ vựng hợp lệ.
- Một bộ Kanji hợp lệ.
- Một đến ba mẫu ngữ pháp hợp lệ.
- Một hội thoại hợp lệ.

Scheduler chỉ xếp package `READY`. Nếu thiếu một phần, hệ thống ghi coverage gap và không lấp bằng nội dung tùy ý hoặc AI runtime.

Ngoại lệ duy nhất là package nền tảng bảng chữ cái dành cho người bắt đầu từ số 0. Package này thuộc warm-up/foundation, không giả làm target package bốn phần.

Do target package đã đủ bốn phần, điểm target package được tính cố định:

```text
targetScore = round((vocabularyLatestScore + kanjiLatestScore + grammarLatestScore) / 3)
```

Nếu Vocabulary hoặc Kanji có nhiều batch, điểm kỹ năng được tính từ tổng số câu đúng trên tổng số câu của latest passing attempt ở từng batch; không lấy trung bình các phần trăm batch vì sẽ làm batch nhỏ có trọng số quá lớn.

Hội thoại là điều kiện hoàn thành, không tham gia điểm. Không gán 0 điểm cho phần dữ liệu bị thiếu; package thiếu dữ liệu không được phát hành.

### 2.3 Hoàn thành dựa trên bằng chứng

- Việc mở trang, bấm nút, lật một thẻ hoặc tự nhận “đã học” không đủ để hoàn thành kỹ năng.
- Vocabulary, Kanji và Grammar hoàn thành khi micro-check tương ứng đạt ít nhất 70%.
- Dialogue hoàn thành khi thỏa đồng thời thời gian hoạt động và đã cuộn tới cuối.
- Package hoàn thành khi cả bốn activity hoàn thành.
- Package đã hoàn thành không được nhận thưởng, streak hoặc completion event lần hai.
- Hoàn thành riêng một activity chỉ lưu tiến độ và mở activity kế tiếp; streak/Credit của ngày được ghi đúng một lần khi package hoàn thành.

## 3. Mô hình hành trình target

### 3.1 Trình tự

```text
Mở target package
  → Vocabulary study
  → Vocabulary micro-check đạt
  → Kanji study
  → Kanji micro-check đạt
  → Grammar practice
  → Grammar micro-check đạt
  → Dialogue practice đủ điều kiện
  → Package COMPLETED
  → Popup kết thúc ngày
```

Mặc định mở tuần tự để hướng dẫn người học. Activity đã mở khóa có thể được truy cập lại. Activity đã đạt vẫn cho phép luyện thêm nhưng lần luyện thêm không tạo thưởng hoàn thành mới và không làm mất trạng thái đã đạt.

### 3.2 Vocabulary study

- Hiển thị một bộ flashcard chứa toàn bộ vocabulary target của package.
- Khử trùng theo canonical vocabulary/sense/reading, không chỉ theo chuỗi hiển thị.
- Mặt trước/sau thể hiện rõ từ, cách đọc, nghĩa và audio khi dữ liệu đã được duyệt.
- Người học phải lật từng thẻ trước khi chọn `Đã nhớ` hoặc `Chưa nhớ`.
- Thẻ `Chưa nhớ` quay lại hàng ôn trong cùng phiên.
- Nút micro-check chỉ mở khi mọi thẻ trong membership version hiện tại đã được lưu `Đã nhớ` ít nhất một lần.
- Reload tiếp tục từ trạng thái server; không tạo bộ mới hoặc mất các thẻ chưa nhớ.

Vocabulary micro-check phối hợp các kiểu:

- Hiển thị từ, chọn nghĩa.
- Hiển thị nghĩa, chọn từ.
- Hiển thị cách đọc, chọn từ.
- Ghép từ với nghĩa.

Mỗi target trong bộ phải xuất hiện ít nhất một lần trong micro-check. Bộ lớn hơn 10 target được chia thành các batch tối đa 10; phải đạt từng batch mới hoàn thành activity. Evidence lưu riêng cho dimension `meaning` và `reading` khi loại câu cho phép kết luận.

### 3.3 Kanji study

- Kanji có bộ flashcard và micro-check riêng để không trộn evidence với vocabulary.
- Mỗi thẻ thể hiện chữ, cách đọc áp dụng trong target, nghĩa và từ ví dụ đã được duyệt.
- Quy tắc lật, nhớ/chưa nhớ, resume và membership version giống Vocabulary.
- Không suy ra đã biết mọi âm On/Kun chỉ vì người học đạt một từ chứa Kanji.

Kanji micro-check phối hợp:

- Kanji → cách đọc.
- Cách đọc → Kanji.
- Kanji → nghĩa.
- Ghép Kanji với cách đọc.

Mỗi Kanji target phải được kiểm tra ít nhất một lần. Bộ lớn hơn 10 target được chia batch tối đa 10 và phải đạt từng batch.

### 3.4 Grammar practice

Mỗi package có từ một đến ba grammar point. Mỗi grammar point hiển thị tuần tự:

1. Ý nghĩa ngắn và phạm vi dùng.
2. Cấu trúc.
3. Một hoặc hai ví dụ đã được duyệt.
4. Xếp từ thành câu hoàn chỉnh.
5. Điền phần còn thiếu hoặc chọn cách dùng đúng theo ngữ cảnh.
6. Phản hồi giải thích ngay sau thao tác.

Người học phải hoàn thành hoạt động practice của mọi grammar point trước khi mở Grammar micro-check. Micro-check dùng câu mới cùng grammar point, không dùng nguyên câu vừa luyện và không gọi AI để sinh câu ở runtime.

Mỗi grammar point có ít nhất hai câu micro-check hợp lệ để tránh một lần đo duy nhất quyết định cả mẫu. Điểm Grammar là `correct / total` của toàn bộ câu micro-check trong package.

### 3.5 Dialogue practice riêng của Learning Path

- Learning Path có route và template hội thoại riêng.
- Không điều hướng người học sang trang sách.
- Nội dung, audio và metadata được lấy bằng DTO/service contract từ nguồn canonical.
- Có thể sao chép trải nghiệm trình bày phù hợp từ trang hội thoại hiện tại nhưng không sao chép dữ liệu thành bảng nội dung thứ hai.
- Dialogue không có micro-check trong phạm vi V2 này.

Dialogue hoàn thành khi thỏa đồng thời:

1. Tích lũy ít nhất 180 giây hoạt động hợp lệ.
2. Đã cuộn đến cuối vùng hội thoại.

Thời gian hợp lệ:

- Chỉ tính khi tab đang visible.
- Client gửi heartbeat mỗi 15 giây khi tab visible; server chỉ chấp nhận tối đa 20 giây cho một heartbeat hợp lệ và không tin tổng số giây do client tự khai.
- Heartbeat đến sau khoảng gián đoạn quá 30 giây bắt đầu lại mốc đo, không cộng toàn bộ khoảng trống.
- Thời gian tab nền, máy ngủ hoặc khoảng heartbeat bất thường không được cộng.
- Tương tác cuộn/phát audio/chuyển lượt cập nhật dấu hiệu hoạt động; không bắt người dùng thao tác liên tục mỗi giây.
- Reload hoặc rời trang giữ lại thời gian hợp lệ đã tích lũy.

Nếu chưa đủ điều kiện, hiển thị thời gian còn lại bằng thông điệp thân thiện, ví dụ:

> Bạn hãy nán lại thêm 1 phút 42 giây để luyện hết đoạn hội thoại nhé.

Cuộn đến cuối trước 180 giây không hoàn thành ngay. Đủ 180 giây nhưng chưa cuộn đến cuối cũng chưa hoàn thành.

### 3.6 Hoàn thành package và popup kết thúc ngày

Khi Vocabulary, Kanji, Grammar đều `PASSED` và Dialogue `COMPLETED`, server chuyển package sang `COMPLETED` trong transaction và phát completion event đúng một lần.

Frontend nhận trạng thái đã lưu thành công rồi hiển thị popup:

> Bạn đã hoàn thành phần bài học hôm nay. Bạn muốn tiếp tục như thế nào?

Ba lựa chọn:

- **Trở về lộ trình:** mở roadmap và làm nổi bật package vừa hoàn thành.
- **Học vượt:** mở package chưa hoàn thành kế tiếp theo thứ tự path. Day number gốc không bị đổi; hệ thống ghi ngày hoàn thành thực tế riêng.
- **Nghỉ ngơi:** về trang Learning Path ở trạng thái completion summary, không tự mở bài khác.

Nếu không còn package kế tiếp, nút `Học vượt` đổi thành `Xem tổng kết chặng`.

Refresh sau khi hoàn thành không cấp thưởng lại. Popup có thể hiện lại nếu completion acknowledgement chưa được ghi nhưng hành động nghiệp vụ vẫn idempotent.

## 4. Micro-check, lượt làm và cooldown

### 4.1 Ngưỡng đạt

- Ngưỡng đạt mọi micro-check: `scorePercent >= 70`.
- Score tính trên toàn bộ câu đã phát; câu bỏ trống tính sai.
- Đáp án được chấm theo answer key snapshot phía server.
- Một submission hợp lệ chỉ được xử lý một lần. Retry cùng idempotency key trả lại kết quả đã lưu.

### 4.2 Ba lượt trong một chu kỳ

Mỗi activity/batch có tối đa ba lần nộp hợp lệ trong một attempt cycle:

- Đạt ở bất kỳ lượt nào: activity hoặc batch chuyển `PASSED`.
- Trượt lượt 1 hoặc 2: hiển thị target/dimension cần ôn và cho quay lại practice ngay.
- Trượt lượt 3: activity chuyển `COOLDOWN` trong 60 phút.

Lỗi kỹ thuật, session hết hạn trước khi người dùng nhận đề hợp lệ hoặc submission trùng không tiêu tốn lượt.

### 4.3 Điều kiện thử lại sau cooldown

Sau lần trượt thứ ba, người học phải thỏa cả hai điều kiện:

1. Đã hoàn thành một review cycle sau thời điểm bị khóa.
2. Đã đủ 60 phút tính theo server clock.

Review cycle:

- Vocabulary/Kanji: ôn lại các thẻ liên quan câu sai; nếu không ánh xạ an toàn được thì ôn lại toàn batch.
- Grammar: làm lại practice của các grammar point có câu sai.
- Reading/Listening warm-up: xem lại nguồn và phản hồi, sau đó hoàn thành lại lượt practice.

Hết cooldown tạo attempt cycle mới với ba lượt. Cooldown chỉ khóa micro-check cụ thể, không khóa activity khác hoặc toàn lộ trình.

API trả `cooldownUntil`, `attemptsUsed`, `attemptsRemaining` và `reviewRequired`; frontend không tự tính quyền thử lại.

### 4.4 Ba loại điểm

Lưu mọi attempt bất biến và duy trì summary:

- `firstScore`: điểm lần nộp hợp lệ đầu tiên, dùng phân tích điểm yếu ban đầu.
- `latestScore`: điểm lần nộp hợp lệ gần nhất, dùng tính điểm package và phản hồi hiện tại.
- `bestScore`: điểm cao nhất, dùng tạo động lực trên giao diện.

Không ghi đè `firstScore`. Không dùng `bestScore` để che một `latestScore` thấp trong phân tích hiện trạng. Attempt có hỗ trợ/hint phải lưu assistance state và không được diễn giải giống independent attempt.

Sau khi activity đã `PASSED`, chế độ luyện thêm không phát một scored attempt thuộc completion cycle cũ. Nếu sau này cần tái đánh giá retention, hệ thống phải mở review cycle mới có mục đích và version rõ ràng; không làm điểm package đã hoàn thành thay đổi âm thầm.

### 4.5 Thông điệp động viên

Thông điệp dựa trên kết quả thật, không bịa tiến bộ:

- Chưa đạt lần đầu: “Bạn vừa tìm ra những điểm cần ôn thêm. Biết chính xác chỗ sai là một bước tiến.”
- Có cải thiện: “Bạn đã cải thiện {delta}% so với lần trước. Tiếp tục thêm một lượt ôn chủ động nhé.”
- Lần ba chưa đạt: “Não cần thời gian để củng cố. Hãy ôn lại phần vừa sai và quay lại sau 60 phút.”
- Đạt sau khi từng chưa đạt: “Bạn vừa biến một điểm yếu thành kiến thức đã nắm được.”
- Đạt lần đầu: “Khởi đầu tốt! Bạn đã sẵn sàng chuyển sang phần tiếp theo.”

Chỉ hiển thị `{delta}` khi hai attempt tương đương đủ để so sánh.

## 5. Warm-up B — chặng vá lỗ hổng tương tác

### 5.1 Mục đích và phạm vi

Warm-up chỉ nhận target người học đã được kiểm tra, thuộc phạm vi đã học và có evidence yếu hợp lệ. Không dùng tổng điểm cao để xóa điểm yếu riêng kỹ năng. Không tự giao toàn bộ giáo trình vì một score thấp.

Mỗi warm-up activity hiển thị lý do cụ thể, ví dụ:

> Bạn gặp khó khăn với cách đọc của 6 từ này trong Smart Test. Hãy ôn lại và kiểm tra ngay sau đó.

Không đủ evidence tạo trạng thái `NEEDS_VERIFICATION`, không tự coi là 0% hoặc yếu.

### 5.2 Vocabulary và Kanji

- Lấy toàn bộ vocabulary/Kanji cụ thể làm sai, không lấy cả lesson nguồn.
- Tách Vocabulary và Kanji thành activity riêng để giữ evidence kỹ năng rõ ràng.
- Khử trùng theo canonical identity và dimension.
- Chia batch tối đa 10 target.
- Học hết flashcard, ghi nhớ hết membership rồi mới mở micro-check.
- Dùng cùng loại tương tác, ngưỡng 70%, ba lượt và cooldown 60 phút của target.

### 5.3 Grammar

- Chỉ giao đúng grammar point/cách dùng đã sai.
- Mỗi grammar point có explanation, example và practice tương tác.
- Micro-check dùng câu mới đã duyệt.
- Tối đa ba grammar point trong một ngày warm-up.

### 5.4 Reading và Listening

Khối lượng tính riêng từng kỹ năng từ Smart Test, không làm tròn trước khi chọn nhánh:

| Tỷ lệ đúng | Số bài warm-up của kỹ năng |
|---|---:|
| `p > 70%` | 0 |
| `50% < p <= 70%` | 4 |
| `40% < p <= 50%` | 6 |
| `0% <= p <= 40%` | 8 |
| Không đủ evidence | 0 bài tự động; yêu cầu xác minh bổ sung |

- Tối đa hai bài Reading/Listening tổng cộng mỗi ngày và vẫn phải nằm trong ngân sách phút của người dùng.
- Nguồn đọc/nghe phải giữ nguyên passage/audio và provenance.
- Media failure là lỗi kỹ thuật, không tính người học trả lời sai.
- Mỗi bài có practice và micro-check tại chỗ; không có gate quiz tổng hợp.
- Transcript/hint được ghi assistance state; attempt có hỗ trợ không được dùng như evidence độc lập.

### 5.5 Người dùng rất yếu

Nếu overall dưới 40% và ít nhất ba trong năm kỹ năng dưới 40%, hệ thống đề nghị một foundation verification package ngắn. Chỉ target được xác minh là yếu mới đi vào warm-up. Không tự động giao lại 70–100% sách N5 hoặc cấp hiện tại.

### 5.6 Kết thúc warm-up

- Mỗi activity warm-up hoàn thành bằng micro-check riêng.
- Không phát warm-up gate quiz.
- Warm-up phase hoàn thành khi mọi activity bắt buộc đã `PASSED` hoặc được xử lý theo trạng thái dữ liệu được duyệt.
- Không có warm-up thì vào target đầu tiên.
- Khi hoàn thành workload warm-up của ngày, dùng popup ba lựa chọn giống target.
- Khi hoàn thành toàn bộ warm-up, lần `Học tiếp` kế tiếp mở target package đầu tiên vào ngày mới; không gộp warm-up và target vào cùng ngày tự động.

## 6. Tổng kết chặng, chứng nhận và thi thử

### 6.1 Tổng kết

Cuối chặng hiển thị:

- `first/latest/best` theo Vocabulary, Kanji và Grammar.
- Target/dimension còn yếu và số lần review.
- Tiến độ Dialogue dưới dạng completion, không đổi thành điểm.
- Evidence Reading/Listening từ Smart Test và warm-up nếu có.
- Confidence và phạm vi dữ liệu dùng để tính readiness.

Nếu Reading/Listening không được đánh giá lại trong Learning Path, UI phải ghi rõ “chưa được đánh giá lại trong lộ trình”, không suy ra từ Dialogue hoặc từ điểm Vocabulary/Grammar.

### 6.2 Readiness estimate

Readiness là ước tính nội bộ, không phải xác nhận đỗ JLPT:

- Dùng evidence gần nhất theo từng skill và dimension.
- Không để điểm cao của kỹ năng này bù cho kỹ năng khác không có evidence.
- Hiển thị confidence thấp khi dữ liệu thiếu hoặc chỉ có attempt có hỗ trợ.
- Link rõ sang thi thử JLPT để có đánh giá tổng hợp độc lập.

Luồng V2 không có lesson quiz, daily quiz hoặc warm-up gate quiz. Stage-final quiz hiện tại không phải điều kiện hoàn thành path V2; endpoint/record cũ được giữ cho lộ trình legacy trong thời gian tương thích nhưng không được liên kết từ UI V2.

### 6.3 Chứng nhận

- Chứng nhận được cấp khi mọi package bắt buộc của toàn lộ trình đã hoàn thành.
- Nội dung ghi rõ: **Chứng nhận hoàn thành lộ trình J-LAS**.
- Không dùng từ ngữ cho rằng người học đã đỗ hoặc được cấp chứng chỉ JLPT.
- Chứng nhận và completion event phải kiểm tra ownership.
- Trang tổng kết có nút sang chức năng thi thử của module do nhóm khác sở hữu; Learning Path chỉ dùng route/contract đã được owner công bố.

## 7. Mô hình trạng thái

### 7.1 Package

```text
LOCKED → AVAILABLE → IN_PROGRESS → COMPLETED
```

- `LOCKED`: chưa đến thứ tự hoặc prerequisite chưa đạt.
- `AVAILABLE`: có thể bắt đầu.
- `IN_PROGRESS`: ít nhất một activity đã bắt đầu.
- `COMPLETED`: đủ mọi activity bắt buộc; trạng thái cuối.

### 7.2 Scored activity/batch

```text
LOCKED → STUDYING → READY_FOR_CHECK → CHECKING
  → PASSED
  → NEEDS_REVIEW → READY_FOR_CHECK
  → COOLDOWN → REVIEWED_WAITING → READY_FOR_CHECK
```

- `PASSED` là trạng thái hoàn thành kỹ năng.
- Lần trượt 1–2 đi qua `NEEDS_REVIEW`.
- Lần trượt 3 đi `COOLDOWN`; sau review thành `REVIEWED_WAITING`; đủ giờ mới trở lại `READY_FOR_CHECK`.
- Client không được tự gửi trạng thái đích; server tính transition.

### 7.3 Dialogue

```text
NOT_STARTED → IN_PROGRESS → COMPLETED
```

Server hoàn thành khi `activeSeconds >= 180` và `reachedBottom = true`.

## 8. Mô hình dữ liệu và ranh giới module

Thiết kế implementation phải ưu tiên mở rộng cấu trúc Learning hiện có thay vì dựng hệ thống song song.

### 8.1 Package và activity

- Bổ sung khái niệm package/day vào Learning Path để nhóm bốn activity cùng một ngày.
- `UserLearningPathItem` tiếp tục là activity tiến độ của người dùng, nhưng cần loại activity rõ: `VOCABULARY`, `KANJI`, `GRAMMAR`, `DIALOGUE`, `READING`, `LISTENING`, `FOUNDATION`.
- Activity phải thuộc đúng path, stage và package; mọi lookup kiểm tra quan hệ cha–con.
- Membership target cần cấu trúc có thứ tự và version. Không lấy tùy ý item đầu làm danh tính activity.
- Content reference giữ canonical type/id/dimension; snapshot câu hỏi và answer key chỉ nằm ở attempt.

### 8.2 Attempt và score summary

- Tái sử dụng hợp đồng immutable `LearningQuizSession`: câu hỏi và answer key được snapshot khi phát.
- Mở rộng quiz type cho micro-check thay vì tạo engine chấm điểm thứ hai.
- Attempt gắn `activityId`, `batchId`, `cycleNumber`, assistance state và idempotency key.
- Lưu mọi attempt; summary first/latest/best được cập nhật transactionally trên progress/activity hoặc bảng summary chuyên trách.
- Quota ba lượt và cooldown được tính từ submission hợp lệ phía server.

### 8.3 Flashcard

- Tái sử dụng Flashcard service và bảng tiến độ hiện có qua adapter/contract; không sửa ownership module nếu chưa phối hợp.
- Learning lưu membership version và liên kết activity; Flashcard giữ card/session/progress.
- `Đã nhớ` là study evidence, không phải mastery; chỉ micro-check mới chuyển activity sang `PASSED`.

### 8.4 Dialogue progress

Lưu tối thiểu:

- `userId`, `activityId`.
- `activeSeconds` đã được server chấp nhận.
- `reachedBottomAt`.
- `lastHeartbeatAt`.
- `completedAt`.
- Version để chống lost update khi heartbeat đồng thời.

Heartbeat nhận idempotency/request token và không nhận `userId` từ client làm nguồn ownership.

### 8.5 Legacy compatibility

- Path mới mang experience/policy version V2 và dùng package/activity flow mới.
- Path legacy vẫn đọc được trong giai đoạn chuyển đổi; không tự migrate completion hoặc sửa lịch sử attempt.
- Không cho một path trộn lesson-completion legacy với package-completion V2.
- Replan của path V2 bảo toàn package/activity đã hoàn thành và membership đã kiểm tra.
- Các endpoint lesson quiz/gate/stage-final cũ không được gọi từ UI V2; chỉ xóa sau khi audit không còn consumer.

## 9. API contract cấp cao

API cụ thể sẽ được khóa trong implementation plan, nhưng phải có các nhóm hành vi sau:

- Lấy package và activity theo authenticated principal.
- Bắt đầu/resume activity.
- Lấy flashcard membership và ghi nhớ/chưa nhớ qua adapter.
- Phát micro-check immutable.
- Nộp micro-check idempotent.
- Lấy trạng thái attempts/cooldown/review requirement.
- Ghi review cycle hoàn thành.
- Gửi Dialogue heartbeat và reached-bottom event.
- Lấy package completion và next action.
- Acknowledge popup completion idempotently.
- Lấy stage/path summary và mock-test destination từ contract cấu hình.

Mọi response dùng `ApiResponse<T>`. Business errors dùng `ApiException(ErrorCode)` với thông điệp tiếng Việt. Không nhận quyền sở hữu từ `userId` trong path/query/body.

## 10. UI/UX

### 10.1 Bố cục

- Containerless, borderless: nội dung chính nằm trực tiếp trên nền trang.
- Dùng khoảng trắng, typography, divider và nền chuyển nhẹ để phân đoạn.
- Card chỉ dùng cho flashcard, vùng tương tác, feedback hoặc summary dày thông tin.
- Không quay lại bố cục năm tab của trang sách.
- Có progress rail rõ bốn phần và trạng thái `Đang học / Sẵn sàng kiểm tra / Cần ôn / Chờ / Hoàn thành`.
- Mobile-first; điều khiển không bị che, target chạm đủ lớn, focus keyboard rõ, hỗ trợ reduced motion.

### 10.2 Flashcard hoàn chỉnh

- Vùng thẻ lớn, mặt trước/sau rõ và hỗ trợ tiếng Nhật.
- Nhãn nút đầy đủ: `Chưa nhớ`, `Lật thẻ`, `Đã nhớ`; không chỉ dùng icon.
- Hiển thị `Đã xử lý`, `Còn cần ôn`, tổng membership và progress bar.
- Không cho đánh dấu trước khi lật.
- Không mở kiểm tra khi còn thẻ chưa nhớ.
- Khi hoàn tất tự chuyển sang completion state có nút `Bắt đầu kiểm tra` nổi bật.
- Reload giữ đúng membership, vị trí hợp lý và trạng thái server.

### 10.3 Feedback và cooldown

- Sau submit, feedback gắn với target/dimension vừa sai, không chỉ báo điểm tổng.
- Cooldown dùng server timestamp và hiển thị countdown tham khảo; khi hết giờ phải hỏi lại server.
- Trong cooldown, CTA chính là `Ôn lại phần vừa sai`.
- Không dùng màu đỏ hoặc câu chữ mang tính trách phạt cho việc chưa đạt.

## 11. Security, integrity và phân quyền

- JWT stateless theo `SecurityConfig`; route V2 mặc định authenticated.
- User lấy từ principal. Repository/service query luôn scope theo owner và quan hệ path → package → activity → attempt.
- Chặn IDOR cho cả read, write, heartbeat, certificate và attempt retry.
- Answer key không xuất hiện trong payload phát đề hoặc HTML/JS.
- Server tự tính score, state transition, attempt count, cooldown và active seconds.
- Dùng transaction/locking phù hợp để hai tab không cấp thưởng, hoàn thành hoặc tiêu lượt trùng.
- Idempotency cho issue/submit/complete/heartbeat acknowledgement.
- Rate limit heartbeat và quiz issue để tránh spam.
- Không log JWT, answer key, API key hoặc nội dung nhạy cảm.
- AI key chỉ ở backend/env; AI không chấm điểm, quyết định pass/fail hoặc sinh quiz runtime.
- Thay đổi module Flashcard, Assessment, Textbook, Kaiwa hoặc Mock Test phải theo owner contract; không sửa ngầm code người khác.

## 12. Data readiness

Một target package chỉ `READY` khi:

- Tất cả canonical reference tồn tại, published/approved và đúng level/applicability.
- Vocabulary sense/reading không mơ hồ hoặc bị merge sai.
- Kanji reading dùng đúng ngữ cảnh target.
- Grammar example và câu micro-check có primary grammar point rõ.
- Dialogue script/audio có provenance và đã kiểm nội dung.
- Mỗi micro-check đủ câu để phủ mọi target theo quy tắc tương ứng.
- Practice item và assessment item không trùng nguyên văn.
- Không có duplicate source family làm pool trông lớn giả.

AI có thể tạo bản nháp offline cho example, distractor và biến thể. Nội dung AI phải qua schema validation, deduplication, review tiếng Nhật và trạng thái `DRAFT → VALIDATED → APPROVED → PUBLISHED`. Thiếu dữ liệu tạo coverage gap; không mass-publish dữ liệu chưa duyệt để mở luồng.

## 13. Đo lường

Theo dõi tối thiểu:

- Completion rate từng activity/package.
- First/latest/best score và số cycle cần để pass.
- Target/dimension sai lặp lại.
- Số lần vào cooldown và tỷ lệ quay lại sau cooldown.
- Thời gian active thực tế của Dialogue.
- Tỷ lệ chọn `Học vượt / Trở về lộ trình / Nghỉ ngơi`.
- Warm-up item count, pass rate và thời gian chuyển sang target.
- Coverage gap/data readiness theo level.

Không dùng số click, thời gian tab nền hoặc số thẻ tự nhận “đã nhớ” làm mastery.

## 14. Acceptance scenarios bắt buộc

1. Người dùng không thể mở Vocabulary quiz khi còn thẻ chưa lật hoặc chưa nhớ.
2. Thẻ `Chưa nhớ` quay lại; reload không làm mất tiến độ hoặc tạo bộ trùng.
3. Đạt đúng 70% hoàn thành micro-check; dưới 70% không hoàn thành.
4. Trượt hai lượt vẫn ôn và thử lại được; trượt lượt ba khóa đúng quiz 60 phút.
5. Cooldown không khóa activity khác; client đổi đồng hồ không mở khóa sớm.
6. Sau cooldown nhưng chưa review, quiz vẫn khóa; đủ review và đủ giờ mới mở.
7. Submission trùng không tốn lượt hoặc cấp thưởng hai lần.
8. Vocabulary/Kanji batch phủ mọi membership target.
9. Grammar micro-check không lặp nguyên câu practice và phủ mọi grammar point.
10. Dialogue cuộn cuối trước ba phút chưa hoàn thành.
11. Dialogue đủ ba phút nhưng chưa cuộn cuối chưa hoàn thành.
12. Tab nền không cộng active seconds; reload resume đúng số giây server đã nhận.
13. Đủ bốn activity tự hoàn thành package và hiện popup đúng một completion event.
14. `Học vượt` mở đúng package kế tiếp, không đổi day number gốc.
15. User A không đọc/ghi package, attempt, dialogue progress hoặc certificate của user B.
16. Package thiếu một trong bốn phần không được scheduler phát hành.
17. Warm-up chỉ chứa target yếu trong phạm vi đã học; không bê nguyên lesson.
18. Reading/Listening áp đúng các biên 40/50/70 và thiếu evidence không bị coi là 0%.
19. Path V2 không gọi lesson quiz, daily quiz, warm-up gate hoặc stage-final legacy.
20. Chứng nhận ghi rõ hoàn thành J-LAS và link thi thử dùng contract của owner khác.

## 15. Phạm vi không làm trong lát kiến trúc này

- Không sửa hoặc xây lại chức năng thi thử JLPT của nhóm khác.
- Không tuyên bố chứng nhận J-LAS tương đương chứng chỉ JLPT.
- Không thêm quiz Dialogue trong V2 đầu tiên.
- Không dùng AI runtime để sinh đề, chấm bài hoặc quyết định lộ trình.
- Không xóa endpoint/bảng legacy trước khi audit consumer và dữ liệu lịch sử.
- Không mass-publish nội dung N3–N1 chưa được review.
- Không triển khai W0127; W0193 chỉ tích hợp qua contract do owner cung cấp.

## 16. Chiến lược chuyển đổi

1. Đóng băng hành vi V2 trong contract và test, đồng thời giữ legacy đọc được.
2. Xây package/activity model và immutable micro-check trên backend.
3. Xây Vocabulary và Kanji interactive flow hoàn chỉnh.
4. Xây Grammar interactive flow.
5. Xây Dialogue page riêng với server-timed completion.
6. Ghép package completion, popup và next action.
7. Chuyển warm-up sang activity-level micro-check, bỏ gate cho path V2.
8. Xây summary, certificate wording và mock-test handoff.
9. Chạy data readiness audit; chỉ bật level/package đủ dữ liệu.
10. E2E trên tài khoản/môi trường kiểm thử được cho phép, sau đó mới cân nhắc retire legacy.

Implementation plan phải chia các bước trên thành lát nhỏ có test, migration tương thích, file ownership, focused verification và commit độc lập. Không trộn thay đổi seed chưa duyệt vào commit tính năng.
