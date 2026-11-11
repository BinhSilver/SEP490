package com.jlas.jlptpath.service;

import com.jlas.auth.service.PremiumAccess;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.common.security.ContentReviewAccess;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.enums.JpathPathStatus;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] F5: N4–N1 of the JLPT path need Premium (owner 11/11; was N3+ on 10/11). Only N5 stays free. A learner whose open
 * level is N3+ without an active Premium keeps the path page (with an upgrade box) but cannot start a study or
 * skill session. Content reviewers pass, like the other paywalls.
 */
@Component
public class JpathPremiumGate {

    public static final Set<String> PREMIUM_LEVELS = Set.of("N4", "N3", "N2", "N1");

    private final JpathUserPathRepository paths;
    private final PremiumAccess premium;

    public JpathPremiumGate(JpathUserPathRepository paths, PremiumAccess premium) {
        this.paths = paths;
        this.premium = premium;
    }

    /** The open level when it needs Premium the learner does not have; otherwise null. */
    @Transactional(readOnly = true)
    public String lockedLevel(Long userId) {
        if (userId == null || ContentReviewAccess.allowed(userId)) return null;
        String level = paths.findFirstByUserIdAndStatusOrderByIdDesc(userId, JpathPathStatus.ACTIVE)
                .map(JpathUserPath::getOpenLevel).orElse(null);
        if (level == null || !PREMIUM_LEVELS.contains(level) || premium.isActive(userId)) return null;
        return level;
    }

    public Map<String, Object> status(Long userId) {
        String level = lockedLevel(userId);
        return level == null ? Map.of("locked", false) : Map.of("locked", true, "level", level);
    }

    /** Throws JPATH_PREMIUM_REQUIRED when the learner's open level needs Premium. */
    public void require(Long userId) {
        if (lockedLevel(userId) != null) throw new ApiException(ErrorCode.JPATH_PREMIUM_REQUIRED);
    }
}
