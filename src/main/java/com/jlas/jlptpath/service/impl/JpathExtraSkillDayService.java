package com.jlas.jlptpath.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] Q9 (owner 24/11): a learner weak in Reading or Listening in the Smart Test gets a third skill day each
 * week from the study stage (3 instead of 2); the study mode (Gộp/Tách) does not change. The path is created with it
 * on; the learner turns it off or on again in Settings. In skill practice the third day runs anyway, as before.
 */
@Service
public class JpathExtraSkillDayService {

    private final JpathUserPathRepository paths;

    public JpathExtraSkillDayService(JpathUserPathRepository paths) {
        this.paths = paths;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(Long userId) {
        return view(active(userId));
    }

    @Transactional
    public Map<String, Object> set(Long userId, boolean on) {
        JpathUserPath p = paths.findActiveForUpdate(userId).orElseThrow(() -> new ApiException(ErrorCode.JPATH_NO_ACTIVE_PATH));
        p.setExtraSkillDay(on);
        paths.save(p);
        return view(p);
    }

    private JpathUserPath active(Long userId) {
        return paths.findFirstByUserIdAndStatusOrderByIdDesc(userId, com.jlas.jlptpath.enums.JpathPathStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(ErrorCode.JPATH_NO_ACTIVE_PATH));
    }

    private static Map<String, Object> view(JpathUserPath p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("on", p.isExtraSkillDay());
        return m;
    }
}
