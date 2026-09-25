package com.jlas.shadowing.util;

import org.springframework.lang.NonNull;

import java.util.Objects;

public final class NullSafe {

    private NullSafe() { }

    @NonNull
    public static Long longId(Long value) {
        return Objects.requireNonNull(value, "id");
    }

    @NonNull
    public static <T> T ref(T value) {
        return Objects.requireNonNull(value);
    }

    @NonNull
    public static String str(String value) {
        return Objects.requireNonNull(value);
    }
}
