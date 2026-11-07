package com.jlas.common.util;

import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;

/** Ngày sinh và tuổi, tính theo giờ Việt Nam để ngày sinh nhật không lệch một ngày quanh nửa đêm. */
public final class BirthDates {
    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    /** Tuổi được chấp nhận khi nhập ngày sinh; static/js/components/date-wheel.js giữ cùng hai con số. */
    public static final int MIN_AGE = 6;
    public static final int MAX_AGE = 120;
    public static final String OUT_OF_RANGE = "Tuổi phải từ " + MIN_AGE + " đến " + MAX_AGE + ".";

    private BirthDates() {
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    /** Ngày sinh nhập tay hợp lệ: tuổi tròn vào {@code today} từ MIN_AGE đến MAX_AGE. */
    public static boolean isPlausible(LocalDate dateOfBirth, LocalDate today) {
        if (dateOfBirth == null || dateOfBirth.isAfter(today)) return false;
        int age = ageOn(dateOfBirth, today);
        return age >= MIN_AGE && age <= MAX_AGE;
    }

    /** Số tuổi tròn vào ngày {@code today}: chưa tới sinh nhật năm nay thì chưa cộng tuổi. */
    public static int ageOn(LocalDate dateOfBirth, LocalDate today) {
        return Period.between(dateOfBirth, today).getYears();
    }
}
