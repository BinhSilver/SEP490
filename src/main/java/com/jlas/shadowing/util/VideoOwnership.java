package com.jlas.shadowing.util;

import com.jlas.auth.security.CurrentUser;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.shadowing.model.Video;

/** Shared ownership policy for video and subtitle editing. */
public final class VideoOwnership {
    private VideoOwnership() { }

    public static boolean isOwner(Video video, Long userId) {
        return video != null && userId != null && userId.equals(video.getOwnerId());
    }

    public static boolean canEdit(Video video) {
        return isOwner(video, CurrentUser.currentUserId().orElse(null));
    }

    public static void requireOwner(Video video) {
        Long userId = CurrentUser.requireUserId();
        // Unassigned legacy content has no owner; it is never editable by default.
        if (!isOwner(video, userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Chỉ chủ sở hữu mới được sửa hoặc xóa video này");
        }
    }
}
