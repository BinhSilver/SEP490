package com.jlas.shadowing.model;

import java.util.List;

public final class CategoryConstants {
    public static final String DEFAULT = "Daily";

    public static final List<String> ALL = List.of(
            "Podcast", "Anime", "Drama", "News", "Speech", "Daily", "Story", "JLPT"
    );

    private CategoryConstants() {}

    public static boolean isValid(String name) {
        return name != null && ALL.stream().anyMatch(c -> c.equalsIgnoreCase(name.trim()));
    }

    public static String normalize(String name) {
        if (name == null || name.isBlank()) return DEFAULT;
        return ALL.stream()
                .filter(c -> c.equalsIgnoreCase(name.trim()))
                .findFirst()
                .orElse(DEFAULT);
    }
}
