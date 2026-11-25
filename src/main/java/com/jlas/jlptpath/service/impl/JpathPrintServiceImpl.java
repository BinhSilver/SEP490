package com.jlas.jlptpath.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathItemRef;
import com.jlas.jlptpath.dto.JpathKanjiFilter;
import com.jlas.jlptpath.dto.JpathKanjiRowView;
import com.jlas.jlptpath.dto.JpathPage;
import com.jlas.jlptpath.dto.JpathPrintRequest;
import com.jlas.jlptpath.dto.JpathPrintRow;
import com.jlas.jlptpath.dto.JpathPrintSheet;
import com.jlas.jlptpath.dto.JpathVocabFilter;
import com.jlas.jlptpath.dto.JpathVocabRowView;
import com.jlas.jlptpath.entity.JpathKana;
import com.jlas.jlptpath.entity.JpathKanji;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.entity.JpathVocabulary;
import com.jlas.jlptpath.enums.JpathPathStatus;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import com.jlas.jlptpath.service.JpathClock;
import com.jlas.jlptpath.service.JpathDailyPlanService;
import com.jlas.jlptpath.service.JpathListService;
import com.jlas.jlptpath.service.JpathPrintService;
import com.jlas.jlptpath.util.JpathPrintLayout;
import com.jlas.jlptpath.util.JpathStrokeSvg;
import com.jlas.jlptpath.util.JpathStrokeSvgIndex;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] Builds the A4 writing sheet. LIST asks {@link JpathListService} for the codes only (page by page, up to
 * {@link #MAX_ITEMS}); TODAY takes {@link JpathDailyPlanService#plannedNewItems}; the rows are then read through
 * {@link JpathContentReader}, so hidden items never print. KANJI rows get the stroke-order SVG when
 * {@link JpathStrokeSvgIndex} has it.
 */
@Service
public class JpathPrintServiceImpl implements JpathPrintService {

    static final String MSG_TRUNCATED = "Chỉ in 120 mục đầu của danh sách";
    static final String MSG_EMPTY = "Chưa có mục nào để in.";
    private static final String TITLE = "Bảng luyện viết";
    private static final Set<String> LEVELS = Set.of("N1", "N2", "N3", "N4", "N5");
    private static final Set<String> SCRIPTS = Set.of("HIRAGANA", "KATAKANA");
    private static final Locale VI = Locale.forLanguageTag("vi");
    private static final DateTimeFormatter DATE_VI = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_LIST_PAGES = 10;

    private final JpathListService lists;
    private final JpathDailyPlanService dailyPlan;
    private final JpathContentReader content;
    private final JpathUserPathRepository paths;
    private final JpathClock clock;
    private final JpathStrokeSvgIndex svgIndex;
    private final org.springframework.beans.factory.ObjectProvider<com.jlas.jlptpath.service.JpathHistoryService> history;
    private final org.springframework.beans.factory.ObjectProvider<JpathPartsService> partsService;

    public JpathPrintServiceImpl(JpathListService lists, JpathDailyPlanService dailyPlan, JpathContentReader content,
                                 JpathUserPathRepository paths, JpathClock clock, JpathStrokeSvgIndex svgIndex) {
        this(lists, dailyPlan, content, paths, clock, svgIndex, null);
    }

    public JpathPrintServiceImpl(JpathListService lists, JpathDailyPlanService dailyPlan, JpathContentReader content,
                                 JpathUserPathRepository paths, JpathClock clock, JpathStrokeSvgIndex svgIndex,
                                 org.springframework.beans.factory.ObjectProvider<com.jlas.jlptpath.service.JpathHistoryService> history) {
        this(lists, dailyPlan, content, paths, clock, svgIndex, history, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public JpathPrintServiceImpl(JpathListService lists, JpathDailyPlanService dailyPlan, JpathContentReader content,
                                 JpathUserPathRepository paths, JpathClock clock, JpathStrokeSvgIndex svgIndex,
                                 org.springframework.beans.factory.ObjectProvider<com.jlas.jlptpath.service.JpathHistoryService> history,
                                 org.springframework.beans.factory.ObjectProvider<JpathPartsService> partsService) {
        this.partsService = partsService;
        this.lists = lists;
        this.dailyPlan = dailyPlan;
        this.content = content;
        this.paths = paths;
        this.clock = clock;
        this.svgIndex = svgIndex;
        this.history = history;
    }

    /** Owner 05/11: the new items of one study day on one tab (first touched first); empty when none. */
    private List<String> dayCodes(Long userId, java.time.LocalDate day, JpathTab tab) {
        com.jlas.jlptpath.service.JpathHistoryService h = history == null ? null : history.getIfAvailable();
        if (h == null) return List.of();
        return h.day(userId, day).items().stream()
                .filter(i -> i.itemType() != null && i.itemType().name().equals(tab.name()))
                .filter(i -> !"REVIEW_RATE".equals(i.lastAction()))
                .map(com.jlas.jlptpath.dto.JpathDayItemView::code).distinct().toList();
    }

    @Override
    @Transactional(readOnly = true)
    public JpathPrintSheet sheet(Long userId, JpathPrintRequest request) {
        String scope = upper(request == null ? null : request.scope());
        if (scope == null) {
            throw invalid("Phạm vi in không hợp lệ.");
        }
        String date = clock.today(userId).format(DATE_VI);
        return switch (scope) {
            case "KANA" -> kanaSheet(request, date);
            case "TODAY", "LIST", "DAY" -> itemSheet(userId, scope, request, date);
            case "PART" -> partSheet(userId, request, date);
            default -> throw invalid("Phạm vi in không hợp lệ.");
        };
    }

    private JpathPrintSheet kanaSheet(JpathPrintRequest request, String date) {
        String script = upper(request.script());
        if (script == null || !SCRIPTS.contains(script)) {
            throw invalid("Bảng chữ không hợp lệ.");
        }
        List<JpathPrintRow> rows = content.kana(script).stream().map(JpathPrintServiceImpl::kanaRow).toList();
        String name = script.charAt(0) + script.substring(1).toLowerCase(Locale.ROOT);
        return sheet(TITLE + " · " + name, date, "KANA", false, rows);
    }

    private JpathPrintSheet itemSheet(Long userId, String scope, JpathPrintRequest request, String date) {
        String tabName = upper(request.tab());
        if (!"VOCAB".equals(tabName) && !"KANJI".equals(tabName)) {
            throw invalid("Tab không hợp lệ.");
        }
        JpathTab tab = JpathTab.valueOf(tabName);
        String level = level(userId, request.level());
        Integer radical = radical(request.radical());

        List<String> codes;
        boolean truncated = false;
        java.time.LocalDate printedDay = null;
        if ("TODAY".equals(scope) || "DAY".equals(scope)) {
            printedDay = clock.today(userId);
            if ("DAY".equals(scope)) {
                try {
                    printedDay = java.time.LocalDate.parse(String.valueOf(request.day()).trim());
                } catch (RuntimeException e) {
                    throw invalid("Ngày in không hợp lệ.");
                }
            }
            // items learned that day, then (today only) the ones still planned
            List<String> learned = new ArrayList<>(dayCodes(userId, printedDay, tab));
            if ("TODAY".equals(scope)) {
                dailyPlan.plannedNewItems(userId, tab).stream().map(JpathItemRef::code)
                        .filter(c -> !learned.contains(c)).forEach(learned::add);
            }
            codes = learned;
        } else {
            Function<Integer, JpathPage<String>> page = tab == JpathTab.VOCAB
                    ? p -> codesOf(lists.vocabulary(userId, new JpathVocabFilter(level, request.topic(),
                            request.state(), request.q(), p)), JpathVocabRowView::code)
                    : p -> codesOf(lists.kanji(userId, new JpathKanjiFilter(level, request.q(), radical,
                            request.sort(), null, p)), JpathKanjiRowView::code);
            codes = new ArrayList<>();
            long total = 0;
            for (int p = 0; p < MAX_LIST_PAGES && codes.size() < MAX_ITEMS; p++) {
                JpathPage<String> got = page.apply(p);
                total = got.total();
                codes.addAll(got.items());
                if (got.items().isEmpty() || codes.size() >= total) {
                    break;
                }
            }
            truncated = total > MAX_ITEMS || codes.size() > MAX_ITEMS;
            if (codes.size() > MAX_ITEMS) {
                codes = List.copyOf(codes.subList(0, MAX_ITEMS));
            }
        }
        List<JpathPrintRow> rows = tab == JpathTab.VOCAB
                ? content.vocabs(codes).stream().map(JpathPrintServiceImpl::vocabRow).toList()
                : content.kanjis(codes).stream().map(this::kanjiRow).toList();
        String title = TITLE + " · " + (tab == JpathTab.VOCAB ? "Từ vựng" : "Kanji")
                + (level == null ? "" : " " + level)
                + " · " + ("TODAY".equals(scope) ? "Hôm nay" : "DAY".equals(scope)
                        ? "Ngày " + printedDay.format(DATE_VI) : "Danh sách đang chọn");
        return sheet(title, date, scope, truncated, rows);
    }

    /**
     * F226 (owner 22/11): "In cả phần" — every item of one study part on the map (Từ vựng, Kanji) or of one part of the
     * kana stage, in the part's own order.
     */
    private JpathPrintSheet partSheet(Long userId, JpathPrintRequest request, String date) {
        String tabName = upper(request.tab());
        int index;
        try {
            index = Integer.parseInt(String.valueOf(request.part()).trim());
        } catch (RuntimeException e) {
            throw invalid("Phần in không hợp lệ.");
        }
        JpathPartsService service = partsService == null ? null : partsService.getIfAvailable();
        if (index < 1 || service == null) {
            throw invalid("Phần in không hợp lệ.");
        }
        if ("KANA".equals(tabName)) {
            JpathPartsService.KanaPart part = service.kanaStage(userId).parts().stream()
                    .filter(p -> p.index() == index).findFirst()
                    .orElseThrow(() -> invalid("Phần in không hợp lệ."));
            Set<String> labels = part.items().stream().map(JpathPartsService.Item::label)
                    .collect(java.util.stream.Collectors.toSet());
            String script = upper(part.script());
            List<JpathPrintRow> rows = script == null || !SCRIPTS.contains(script) ? List.of()
                    : content.kana(script).stream().filter(k -> labels.contains(k.getKana()))
                            .map(JpathPrintServiceImpl::kanaRow).toList();
            String name = script == null ? "Chữ cái" : script.charAt(0) + script.substring(1).toLowerCase(Locale.ROOT);
            return sheet(TITLE + " · " + name + " · Phần " + index, date, "PART", false, rows);
        }
        if (!"VOCAB".equals(tabName) && !"KANJI".equals(tabName)) {
            throw invalid("Tab không hợp lệ.");
        }
        JpathTab tab = JpathTab.valueOf(tabName);
        JpathPartsService.Parts parts = service.parts(userId, tab);
        JpathPartsService.Part part = parts.parts().stream().filter(p -> p.index() == index).findFirst()
                .orElseThrow(() -> invalid("Phần in không hợp lệ."));
        List<String> codes = part.items().stream().map(JpathPartsService.Item::code).distinct().toList();
        List<JpathPrintRow> rows = tab == JpathTab.VOCAB
                ? content.vocabs(codes).stream().map(JpathPrintServiceImpl::vocabRow).toList()
                : content.kanjis(codes).stream().map(this::kanjiRow).toList();
        String title = TITLE + " · " + (tab == JpathTab.VOCAB ? "Từ vựng" : "Kanji")
                + (parts.level() == null ? "" : " " + parts.level()) + " · Phần " + index;
        return sheet(title, date, "PART", false, rows);
    }

    private static JpathPrintSheet sheet(String title, String date, String scope, boolean truncated,
                                         List<JpathPrintRow> rows) {
        String notice = rows.isEmpty() ? MSG_EMPTY : truncated ? MSG_TRUNCATED : null;
        return new JpathPrintSheet(title, date, scope, truncated, JpathPrintLayout.pages(rows), notice);
    }

    private static <T> JpathPage<String> codesOf(JpathPage<T> page, Function<T, String> code) {
        return new JpathPage<>(page.items().stream().map(code).toList(), page.page(), page.size(), page.total(),
                page.facets());
    }

    private static JpathPrintRow vocabRow(JpathVocabulary v) {
        return new JpathPrintRow("VOCAB", v.getExpression(), v.getReading(), null, v.getMeaningVi(), null, null,
                JpathPrintLayout.cells(length(v.getExpression())));
    }

    private JpathPrintRow kanjiRow(JpathKanji k) {
        int cp = k.getCharacter().codePointAt(0);
        String svg = svgIndex.exists(cp) ? JpathStrokeSvg.url(cp) : null;
        String hanViet = k.getHanViet() == null ? null : k.getHanViet().toUpperCase(VI);
        return new JpathPrintRow("KANJI", k.getCharacter(), String.join("・", readings(k.getLevelReadingsJson())),
                hanViet, k.getMeaningsVi(), svg, k.getStrokeCount(), JpathPrintLayout.cells(length(k.getCharacter())));
    }

    private static JpathPrintRow kanaRow(JpathKana k) {
        return new JpathPrintRow("KANA", k.getKana(), k.getRomaji(), null, null, null, null,
                JpathPrintLayout.cells(length(k.getKana())));
    }

    private static int length(String text) {
        return text == null ? 1
                : Math.max(1, (int) text.codePoints().filter(cp -> !Character.isWhitespace(cp)).count());
    }

    private static List<String> readings(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    /** The requested level (validated), else the open level of the learner's ACTIVE path. */
    private String level(Long userId, String requested) {
        String level = upper(requested);
        if (level != null) {
            if (!LEVELS.contains(level)) {
                throw invalid("Cấp độ không hợp lệ.");
            }
            return level;
        }
        return paths.findFirstByUserIdAndStatusOrderByIdDesc(userId, JpathPathStatus.ACTIVE)
                .map(JpathUserPath::getOpenLevel).orElse(null);
    }

    private static Integer radical(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw invalid("Bộ thủ không hợp lệ.");
        }
    }

    private static String upper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }
}
