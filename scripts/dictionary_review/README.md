# Danh sách duyệt mục từ là dạng chia / câu

Tạo `data/dictionary-review/muc-dang-chia-va-cau.csv`: các mục **không có từ loại** mà chỉ là
dạng chia của động từ/tính từ đã có trong từ điển (貸します → 貸す), hoặc là câu/cụm lịch sự
(いくらですか). Công cụ chỉ đọc dữ liệu, không sửa DB.

```powershell
# 1. Xuất toàn bộ mục từ (khoảng 11 giây)
Get-Content scripts/dictionary_review/export_entries.sql |
  mysql --default-character-set=utf8mb4 -B -N --raw -u root -p jlas_dictionary_test > entries.tsv

# 2. Biên dịch để có VerbDeinflector trong target/classes, rồi chạy (khoảng 2 giây)
mvn -o -q compile
java -cp target/classes scripts/dictionary_review/FindInflectedEntries.java entries.tsv data/dictionary-review/muc-dang-chia-va-cau.csv
```

Kết quả với seed 20261016 sau migration 061 là `inflected=219 sentences=95`, tổng cộng 314 dòng.

Các mục không đưa vào danh sách, vì không phải lỗi:

- Mục có từ loại (ví dụ 行き là danh từ `n`).
- Mục `〜する` (休学する): bản thân đã là thể từ điển.
- Danh từ tạo từ dạng liên dụng (積み込み, 押し切り).
- Mục chỉ viết bằng kana mà khớp theo cách đọc (うんでい bị nhận nhầm thành dạng chia của 生む).
