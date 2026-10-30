package com.jlas.auth.service;

import java.util.Collection;
import java.util.Map;

/** Read-only user information exposed to other feature modules. */
public interface UserDirectoryPort {
    String displayName(Long userId);

    /** Họ tên của nhiều người (một truy vấn); người chưa đặt họ tên không nằm trong map. */
    default Map<Long, String> displayNames(Collection<Long> userIds) {
        return Map.of();
    }

    /** Ảnh đại diện của nhiều người (một truy vấn); người không có ảnh không nằm trong map. */
    default Map<Long, String> avatarUrls(Collection<Long> userIds) {
        return Map.of();
    }
}
