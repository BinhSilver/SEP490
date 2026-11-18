package com.jlas.tutor;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Trang "Duyệt câu trả lời AI" trong khung quản trị dùng chung, cùng khuôn với trang "Báo sai lộ trình".
 * Quyền kiểm ở SecurityConfig (/admin/tutor-review → CONTENT_CREATOR, ADMIN); dữ liệu do JS gọi /api/admin/tutor-review.
 */
@Controller
public class TutorReviewPageController {

    @GetMapping({"/admin/tutor-review", "/admin/tutor-review/"})
    public String review() {
        return "admin/tutor-review";
    }
}
