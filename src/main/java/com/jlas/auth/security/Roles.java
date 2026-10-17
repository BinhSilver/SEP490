package com.jlas.auth.security;

import java.util.Locale;

/**
 * The four account roles. SUPER_ADMIN was merged into ADMIN and CONTENT_ADMIN into CONTENT_CREATOR
 * (sql/migrations/033_merge_roles.sql); rows that still carry an old name are folded here, so
 * authorities and the role sent to the browser are always one of these four.
 */
public final class Roles {
    public static final String LEARNER = "LEARNER";
    public static final String MODERATOR = "MODERATOR";
    public static final String ADMIN = "ADMIN";
    public static final String CONTENT_CREATOR = "CONTENT_CREATOR";

    private Roles() {}

    public static String canonical(String name) {
        if (name == null || name.isBlank()) return LEARNER;
        String role = name.trim().toUpperCase(Locale.ROOT);
        return switch (role) {
            case "SUPER_ADMIN" -> ADMIN;
            case "CONTENT_ADMIN" -> CONTENT_CREATOR;
            default -> role;
        };
    }
}
