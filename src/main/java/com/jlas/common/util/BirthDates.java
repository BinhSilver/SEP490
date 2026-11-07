package com.jlas.common.util;

import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;

/** Ngày sinh và tuổi, tính theo giờ Việt Nam để ngày sinh nhật không lệch một ngày quanh nửa đêm. */
public final class BirthDates {
    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate EARLIEST = LocalDate.of(1900, 1, 1);

    private BirthDates() {
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    /** Ngày sinh nhập tay hợp lệ: không ở tương lai và không trước năm 1900. */
    public static boolean isPlausible(LocalDate dateOfBirth, LocalDate today) {
        return dateOfBirth != null && !dateOfBirth.isBefore(EARLIEST) && !dateOfBirth.isAfter(today);
    }

    /** Số tuổi tròn vào ngày {@code today}: chưa tới sinh nhật năm nay thì chưa cộng tuổi. */
    public static int ageOn(LocalDate dateOfBirth, LocalDate today) {
        return Period.between(dateOfBirth, today).getYears();
    }
}
