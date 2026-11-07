package com.jlas.shadowing.service;

import com.jlas.auth.repository.UserRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.common.util.BirthDates;
import com.jlas.shadowing.model.Video;
import com.jlas.shadowing.util.VideoOwnership;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * Nhãn độ tuổi của video (video.min_age). Chỉ kiểm tra ở chỗ trả nội dung để xem/học: thông tin phát, luồng phát,
 * phụ đề, phiên luyện tập, chuẩn bị câu hỏi, chấm phát âm. Ảnh bìa, tiêu đề và thẻ thư viện không bị chặn,
 * để người học vẫn thấy video và nhãn của nó. Chủ video và người kiểm duyệt luôn xem được.
 */
@Component
public class ShadowingAgeGate {
    private final UserRepository users;

    public ShadowingAgeGate(UserRepository users) {
        this.users = users;
    }

    public void requireAllowed(Video video, Long viewerId) {
        Integer minAge = video.getMinAge();
        if (minAge == null || VideoOwnership.isOwner(video, viewerId) || VideoService.isModerator()) return;
        if (viewerId == null) {
            // 401: auth-session.js đưa khách tới trang đăng nhập rồi quay lại đúng video này.
            throw new ApiException(ErrorCode.UNAUTHORIZED,
                    "Vui lòng đăng nhập để xem video dành cho người từ " + minAge + " tuổi trở lên.");
        }
        LocalDate dateOfBirth = users.findDateOfBirthById(viewerId).orElse(null);
        if (dateOfBirth == null) throw new ApiException(ErrorCode.BIRTH_DATE_REQUIRED);
        if (BirthDates.ageOn(dateOfBirth, BirthDates.today()) < minAge) {
            throw new ApiException(ErrorCode.AGE_RESTRICTED, "Video này dành cho người từ " + minAge + " tuổi trở lên.");
        }
    }
}
