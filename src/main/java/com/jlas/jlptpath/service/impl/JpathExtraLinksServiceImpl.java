package com.jlas.jlptpath.service.impl;

import com.jlas.jlptpath.dto.JpathExtraLink;
import com.jlas.jlptpath.dto.JpathExtraLinksView;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.enums.JpathPathStatus;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import com.jlas.jlptpath.service.JpathExtraLinksService;
import com.jlas.learning.dto.MockTestDestination;
import com.jlas.learning.service.MockTestDestinationProvider;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] {@link JpathExtraLinksService}. Fixed tools of the app (flashcards, Speed Recall, mini-games, shadowing),
 * the mock test of the open level through {@link MockTestDestinationProvider}, confusable pairs, and the coming-soon
 * Đọc · Nghe entry; no AI conversation link.
 */
@Service
public class JpathExtraLinksServiceImpl implements JpathExtraLinksService {

    static final String NOT_OPEN = "Chưa mở";
    static final String COMING_SOON = "Sắp mở";
    private static final String FLASHCARDS = "/flashcard/library.html";

    private final JpathUserPathRepository paths;
    private final MockTestDestinationProvider mockTests;

    public JpathExtraLinksServiceImpl(JpathUserPathRepository paths, MockTestDestinationProvider mockTests) {
        this.paths = paths;
        this.mockTests = mockTests;
    }

    @Override
    @Transactional(readOnly = true)
    public JpathExtraLinksView links(Long userId) {
        String level = paths.findFirstByUserIdAndStatusOrderByIdDesc(userId, JpathPathStatus.ACTIVE)
                .map(JpathUserPath::getOpenLevel).orElse(null);
        MockTestDestination mock = level == null ? null : mockTests.resolveDestination(level);
        boolean mockOpen = mock != null && mock.available() && mock.url() != null;
        List<JpathExtraLink> links = List.of(
                open("FLASHCARD", "Flashcard", FLASHCARDS, null),
                open("SPEED_RECALL", "Speed Recall", FLASHCARDS, "Chọn bộ thẻ rồi bấm Speed Recall"),
                open("GAME", "Mini-game", "/games", null),
                open("SHADOWING", "Shadowing", "/shadowing", null),
                mockOpen ? open("MOCK", "Thi thử " + level, mock.url(), null)
                        : new JpathExtraLink("MOCK", level == null ? "Thi thử" : "Thi thử " + level, null, false,
                                NOT_OPEN),
                open("CONFUSABLES", "Phân biệt chữ dễ nhầm", "/learning/jlpt/confusables", null),
                new JpathExtraLink("READ_LISTEN", "Đọc · Nghe", null, false, COMING_SOON));
        List<JpathExtraLink> pages = List.of(
                open("HISTORY", "Lịch sử học", "/learning/jlpt/history", null),
                open("STATS", "Thống kê", "/learning/jlpt/stats", null),
                open("KNOWN", "Kho đã biết", "/learning/jlpt/known", null),
                open("NOTEBOOK", "Sổ tay", "/learning/jlpt/notebook", null));
        return new JpathExtraLinksView(level, links, pages);
    }

    private static JpathExtraLink open(String key, String label, String url, String note) {
        return new JpathExtraLink(key, label, url, true, note);
    }
}
