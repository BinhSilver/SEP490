package com.jlas.learning.service.impl;

import com.jlas.dictionary.entity.ContentItem;
import com.jlas.dictionary.entity.GrammarPoint;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.repository.ContentItemRepository;
import com.jlas.learning.dto.GrammarManifest;
import com.jlas.learning.repository.LearningGrammarPointRepository;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] P2 — dựng manifest cho một activity ngữ pháp theo §7.4/§7.8 của hướng dẫn tách kiến
 * thức: resolve đúng {@code grammarPointId}, lấy pattern/meaning/note và các ví dụ content_item gắn
 * đúng mẫu TRONG ĐÚNG bài nguồn. Không kéo toàn bộ content_item của bài; mẫu không ánh xạ được
 * (thiếu point, nguồn chưa published, thiếu pattern/meaning) trả {@code BLOCKED_CONTENT}.
 */
@Service
@RequiredArgsConstructor
public class GrammarManifestService {

    private final LearningGrammarPointRepository grammarPoints;
    private final ContentItemRepository contentItems;

    @Transactional(readOnly = true)
    public GrammarManifest resolve(Long grammarPointId) {
        if (grammarPointId == null || grammarPointId <= 0) {
            return GrammarManifest.blocked(grammarPointId, "Không có mẫu ngữ pháp được chọn.");
        }
        GrammarPoint point = grammarPoints.findWithLesson(grammarPointId).orElse(null);
        if (point == null) {
            return GrammarManifest.blocked(grammarPointId, "Mẫu ngữ pháp không tồn tại hoặc chưa được ánh xạ.");
        }
        Lesson lesson = point.getLesson();
        if (lesson == null || !"PUBLISHED".equals(lesson.getStatus())) {
            return GrammarManifest.blocked(grammarPointId, "Bài nguồn của mẫu chưa được phát hành.");
        }
        var textbook = lesson.getUnit() == null ? null : lesson.getUnit().getTextbook();
        if (textbook == null || !"PUBLISHED".equals(textbook.getStatus())) {
            return GrammarManifest.blocked(grammarPointId, "Sách nguồn của mẫu chưa được phát hành.");
        }
        if (isBlank(point.getPattern()) || isBlank(point.getMeaning())) {
            return GrammarManifest.blocked(grammarPointId, "Mẫu ngữ pháp thiếu pattern hoặc meaning.");
        }

        List<ContentItem> examples = contentItems.findGrammarExamples(point.getId(), lesson.getId());
        List<GrammarManifest.ExampleBlock> blocks = examples.stream()
                .map(item -> new GrammarManifest.ExampleBlock(item.getId(), item.getTitle(), item.getContent(),
                        item.getTranslatedText(), item.getOrderIndex()))
                .toList();
        String version = "gp:" + point.getId() + ":lesson:" + lesson.getId() + ":items:"
                + examples.stream().map(item -> item.getId().toString()).collect(Collectors.joining(","));

        Long textbookId = textbook.getId();
        Long unitId = lesson.getUnit() == null ? null : lesson.getUnit().getId();
        return new GrammarManifest(point.getId(), point.getPattern(), point.getMeaning(),
                point.getExample(), point.getNote(), lesson.getId(), textbookId, unitId,
                textbook.getJlptLevel(), version, GrammarManifest.READY, null, blocks);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
