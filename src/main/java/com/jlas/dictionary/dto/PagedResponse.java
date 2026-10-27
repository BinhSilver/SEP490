package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * Kết quả phân trang cho các API tra cứu của module dictionary.
 * <p>
 * Bảng vocabulary_entry đang có hơn 9.000 dòng nên mọi tìm kiếm đều phải phân trang; trả hết một lần
 * vừa nặng vừa vô dụng với người dùng. Không dùng thẳng {@link Page} trong response để khỏi lộ cấu trúc
 * của Spring Data ra API công khai.
 */
@Data
@Builder
public class PagedResponse<T> {
    private List<T> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private boolean first;
    private boolean last;

    /** Đổi một {@link Page} của entity thành PagedResponse của DTO. */
    public static <E, T> PagedResponse<T> of(Page<E> source, Function<E, T> mapper) {
        return PagedResponse.<T>builder()
                .content(source.getContent().stream().map(mapper).toList())
                .page(source.getNumber())
                .size(source.getSize())
                .totalElements(source.getTotalElements())
                .totalPages(source.getTotalPages())
                .first(source.isFirst())
                .last(source.isLast())
                .build();
    }
}
