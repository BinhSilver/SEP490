package com.jlas.learning.controller;

import com.jlas.dictionary.entity.GrammarPoint;
import com.jlas.learning.dto.GrammarManifest;
import com.jlas.learning.repository.LearningGrammarPointRepository;
import com.jlas.learning.service.impl.GrammarManifestService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * [JLAS-W0108] Màn xem item ngữ pháp trọng tâm của warm-up GRAMMAR. Warm-up item trỏ tới đúng
 * mẫu ngữ pháp đã sai trong Smart Test, nên mở đúng mẫu đó (pattern + nghĩa + ví dụ đúng mẫu từ
 * content_item) thay vì dẫn về cả bài trong giáo trình. Mẫu không ánh xạ được hiển thị trạng thái
 * "Chưa có đủ học liệu" (BLOCKED_CONTENT) thay vì lỗi 404 mù.
 */
@Controller
@RequiredArgsConstructor
public class LearningGrammarPageController {
    private final GrammarManifestService manifests;
    private final LearningGrammarPointRepository grammarPoints;

    @GetMapping("/learning/grammar/{grammarPointId}")
    public String grammar(@PathVariable Long grammarPointId, Model model) {
        GrammarManifest manifest = manifests.resolve(grammarPointId);
        model.addAttribute("layoutMode", "app");
        if (!manifest.ready()) {
            model.addAttribute("contentBlocked", true);
            model.addAttribute("blockReason", manifest.blockReason());
            return "learning/grammar";
        }
        GrammarPoint point = grammarPoints.findWithLesson(grammarPointId)
                .orElseThrow(() -> new com.jlas.common.exception.ApiException(
                        com.jlas.common.exception.ErrorCode.NOT_FOUND, "Grammar point not found."));
        model.addAttribute("grammarPoint", point);
        model.addAttribute("examples", manifest.examples());
        return "learning/grammar";
    }
}
