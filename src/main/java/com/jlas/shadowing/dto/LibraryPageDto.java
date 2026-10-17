package com.jlas.shadowing.dto;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import java.util.List;
import java.util.function.Function;

/** One-based pages. Out-of-range pages clamp to the last page after filtering/deletion. */
public record LibraryPageDto<T>(List<T> items, int page, int size, long totalItems, int totalPages) {
    public static <T> LibraryPageDto<T> from(List<T> sorted, int requestedPage, int size) {
        validate(requestedPage, size);
        int pages = (int) ((sorted.size() + (long) size - 1) / size);
        int page = Math.min(requestedPage, Math.max(1, pages));
        int start = (page - 1) * size;
        return new LibraryPageDto<>(List.copyOf(sorted.subList(start, Math.min(start + size, sorted.size()))),
                page, size, sorted.size(), pages);
    }

    public static void validate(int page, int size) {
        if (page < 1 || size < 1 || size > 48) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Trang phải từ 1; mỗi trang từ 1 đến 48 mục.");
        }
    }

    public <R> LibraryPageDto<R> map(Function<T, R> mapper) {
        return new LibraryPageDto<>(items.stream().map(mapper).toList(), page, size, totalItems, totalPages);
    }
}
