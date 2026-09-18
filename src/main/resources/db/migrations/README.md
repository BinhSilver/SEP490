# Khảo sát học tập trước Smart Test

Ứng dụng đang cấu hình Hibernate `ddl-auto: validate`. Với database đã tồn tại,
chạy `20260917_user_learning_survey.sql` trước khi khởi động code mới. Với database
mới, bảng này đã nằm trong `jlas_schema.sql`.

Migration tạo bảng `user_learning_survey` theo `user_id`, rồi chuyển survey từ
Smart Test session mới nhất của mỗi tài khoản sang profile. Dữ liệu bài test cũ
được giữ nguyên. Chạy lại migration không ghi đè profile đã tồn tại.

## Flow mới

1. Người dùng đăng nhập và mở trang ứng dụng. Nếu chưa có survey profile, frontend
   chuyển đến `/survey`. Khách chưa đăng nhập, trang auth và quản trị không bị chặn.
2. Hoàn thành khảo sát: POST `/api/assessment/survey` lưu profile; chưa tạo attempt.
3. Hiển thị lựa chọn `Làm Smart Test` / `Về Home` / `Chỉnh khảo sát`.
4. `Về Home`: chuyển về `/`, profile vẫn được lưu và không hỏi lại khi vào web.
5. `Làm Smart Test`: POST `/api/assessment/smart-tests`, tạo bài bằng survey đã lưu.
6. Mở `/survey` sau này: tải profile hiện có và hiển thị lời mời làm test. Người dùng
   có thể chọn chỉnh lại khảo sát.

GET `/api/assessment/survey` và POST cùng route lấy user ID từ principal đăng nhập,
không nhận user ID từ trình duyệt. Bảng survey không lưu kết quả chấm; kết quả và
snapshot khảo sát của mỗi bài test vẫn nằm trong các bảng Smart Test hiện có.

Level tự khai `UNKNOWN` được giữ trong profile. Khi tạo bài Smart Test, rule hiện
tại quy đổi level này thành N5. Flow mới không cập nhật `users.japanese_level`.
