package com.jlas.auth.service;

import java.util.Collection;
import java.util.Map;

/** Read-only user information exposed to other feature modules. */
public interface UserDirectoryPort {
    String displayName(Long userId);

    /** Ảnh đại diện của nhiều người (một truy vấn); người không có ảnh không nằm trong map. */
    default Map<Long, String> avatarUrls(Collection<Long> userIds) {
        return Map.of();
    }
}
