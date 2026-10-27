package com.jlas.shadowing.util;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Common filter and duration ordering rules for videos and series. */
public final class LibraryFilters {
    private LibraryFilters() { }

    public static Set<String> values(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public static <T> Comparator<T> duration(Function<T, Double> getter, boolean descending) {
        return Comparator.<T, Boolean>comparing(item -> !knownDuration(getter.apply(item)))
                .thenComparingDouble(item -> {
                    Double seconds = getter.apply(item);
                    return knownDuration(seconds) ? (descending ? -seconds : seconds) : 0;
                });
    }

    private static boolean knownDuration(Double seconds) {
        return seconds != null && Double.isFinite(seconds) && seconds > 0;
    }
}
