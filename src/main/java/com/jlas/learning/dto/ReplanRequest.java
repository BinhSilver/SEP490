package com.jlas.learning.dto;

/**
 * [JLAS-W0122] Yêu cầu áp dụng replan. {@code confirmed} phải là {@code true} — replan chỉ chạy
 * sau khi learner xác nhận (đặc biệt trường hợp ACCELERATE không được tự ý đổi lịch).
 */
public record ReplanRequest(String mode, boolean confirmed) { }
