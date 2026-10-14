package com.jlas.common.security;

import com.jlas.auth.security.UserPrincipal;
import java.util.Set;
import org.springframework.security.core.context.SecurityContextHolder;

/** Content reviewers (Onyomi, AI Call): content creators and admins; no client-provided admin flag. */
public final class ContentReviewAccess {
    private static final Set<String> ROLES=Set.of("ROLE_CONTENT_CREATOR","ROLE_ADMIN");
    private ContentReviewAccess() {}
    public static boolean allowed(Long userId) {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        return authentication!=null&&authentication.isAuthenticated()
                &&authentication.getPrincipal() instanceof UserPrincipal principal
                &&java.util.Objects.equals(principal.userId(),userId)
                &&authentication.getAuthorities().stream().anyMatch(a->ROLES.contains(a.getAuthority()));
    }
}
