package com.jlas.auth.security;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/** Shared accessor for the authenticated user. */
public final class CurrentUser {
    private CurrentUser() { }

    public static Optional<UserPrincipal> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) return Optional.empty();
        return authentication.getPrincipal() instanceof UserPrincipal principal
                ? Optional.of(principal) : Optional.empty();
    }

    public static Optional<Long> currentUserId() {
        return current().map(UserPrincipal::userId);
    }

    public static Long requireUserId() {
        return currentUserId().orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "Please login"));
    }

    public static boolean hasRole(String role) {
        return current().map(UserPrincipal::getAuthorities)
                .map(authorities -> authorities.stream().map(GrantedAuthority::getAuthority)
                        .anyMatch(role::equals))
                .orElse(false);
    }
}
