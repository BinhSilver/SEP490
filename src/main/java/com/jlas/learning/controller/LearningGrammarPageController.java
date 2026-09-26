package com.jlas.learning.controller;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.GrammarPoint;
import com.jlas.learning.repository.LearningGrammarPointRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * [JLAS-W0108] Màn xem item ngữ pháp trọng tâm của warm-up GRAMMAR. Warm-up item trỏ tới đúng
 * mẫu ngữ pháp đã sai trong Smart Test, nên mở đúng mẫu đó (pattern + nghĩa + ví dụ) thay vì
 * dẫn về cả bài trong giáo trình.
 */
@Controller
@RequiredArgsConstructor
public class LearningGrammarPageController {
    private final LearningGrammarPointRepository grammarPoints;

    @GetMapping("/learning/grammar/{grammarPointId}")
    public String grammar(@PathVariable Long grammarPointId, Model model) {
        GrammarPoint point = grammarPoints.findWithLesson(grammarPointId)
                .filter(p -> "PUBLISHED".equals(p.getLesson().getStatus()))
                .filter(p -> "PUBLISHED".equals(p.getLesson().getUnit().getTextbook().getStatus()))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Grammar point not found."));
        model.addAttribute("grammarPoint", point);
        model.addAttribute("layoutMode", "app");
        return "learning/grammar";
    }
}
