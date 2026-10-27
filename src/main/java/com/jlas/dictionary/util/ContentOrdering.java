package com.jlas.dictionary.util;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Sắp xếp lại order_index cho các bảng con của cây nội dung số hoá.
 * <p>
 * Mọi bảng trong cây đều có UNIQUE (cha, order_index) — uk_unit_order, uk_lesson_unit_order,
 * uk_section_order, uk_content_item_order, uk_grammar_order, uk_dialogue_chunk_order — nên cùng vướng
 * một vấn đề và cùng dùng được hai hàm ở đây. Tách ra khỏi ContentAdminServiceImpl để các service nội
 * dung (ngữ pháp, hội thoại…) dùng lại mà không chép logic.
 * <p>
 * Lớp tiện ích thuần, không phải bean: không phụ thuộc repository, chỉ nhận vào hàm lưu.
 */
public final class ContentOrdering {

    private ContentOrdering() {
    }

    /**
     * Đánh số lại order_index thành 1..n theo đúng thứ tự danh sách truyền vào.
     * <p>
     * Phải làm HAI PHA vì order_index có UNIQUE trong phạm vi cha: gán thẳng số mới sẽ đụng số mà bản ghi
     * khác đang giữ và vi phạm ràng buộc ngay giữa chừng, dù kết quả cuối cùng hợp lệ.
     * <ul>
     *   <li>Pha 1: đẩy tất cả lên dải tạm bắt đầu từ {@code max + 1}. Mọi giá trị mới đều lớn hơn mọi
     *       giá trị đang tồn tại nên không thể trùng ai, và chúng cũng khác nhau từng đôi một — nhờ vậy
     *       Hibernate flush các UPDATE theo thứ tự nào cũng an toàn.</li>
     *   <li>Pha 2: gán số thật 1..n, lúc này không còn bản ghi nào nằm trong dải đó.</li>
     * </ul>
     * Không dùng số âm làm giá trị tạm: các cột này là INT UNSIGNED nên DB sẽ từ chối.
     * Đánh số từ 1 để khớp dữ liệu seed đang có (1-based), tránh chỗ hiển thị theo order_index ra "Bài 0".
     * Cả hai pha phải nằm trong transaction của lời gọi.
     */
    public static <T> void renumber(List<T> ordered,
                                    Function<T, Integer> getOrderIndex,
                                    BiConsumer<T, Integer> setOrderIndex,
                                    Function<List<T>, List<T>> saveAndFlush) {
        int max = 0;
        for (T item : ordered) {
            Integer current = getOrderIndex.apply(item);
            if (current != null && current > max) max = current;
        }
        int temp = max + 1;
        for (T item : ordered) setOrderIndex.accept(item, temp++);
        saveAndFlush.apply(ordered);

        int position = 1;
        for (T item : ordered) setOrderIndex.accept(item, position++);
        saveAndFlush.apply(ordered);
    }

    /**
     * Sắp xếp danh sách hiện có theo đúng thứ tự id client gửi lên, sau khi kiểm hai tập id khớp nhau
     * TUYỆT ĐỐI. Thiếu, thừa, trùng hay id lạ đều là 400 — nếu bỏ qua thì bản ghi không có trong danh
     * sách sẽ giữ order_index cũ và đụng số vừa gán cho bản ghi khác.
     *
     * @param what tên loại mục trong thông báo lỗi, ví dụ "phần", "bài học", "mục nội dung"
     */
    public static <T> List<T> sortByRequestedIds(List<T> existing, Function<T, Long> getId,
                                                 List<Long> requestedIds, String what) {
        Map<Long, T> byId = existing.stream().collect(Collectors.toMap(getId, Function.identity(),
                (a, b) -> a, LinkedHashMap::new));
        Set<Long> requested = new LinkedHashSet<>(requestedIds);
        if (requested.size() != requestedIds.size()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Danh sách thứ tự " + what + " có id bị lặp.");
        }
        if (!requested.equals(byId.keySet())) {
            throw new ApiException(ErrorCode.BAD_REQUEST,
                    "Danh sách thứ tự phải gồm đúng " + byId.size() + " " + what + " hiện có, không thiếu không thừa.");
        }
        return requestedIds.stream().map(byId::get).toList();
    }
}
