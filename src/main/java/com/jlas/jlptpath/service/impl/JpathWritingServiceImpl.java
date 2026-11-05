package com.jlas.jlptpath.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathItemRef;
import com.jlas.jlptpath.dto.JpathWritingChar;
import com.jlas.jlptpath.dto.JpathWritingView;
import com.jlas.jlptpath.entity.JpathKana;
import com.jlas.jlptpath.entity.JpathKanji;
import com.jlas.jlptpath.entity.JpathVocabulary;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.service.JpathDailyPlanService;
import com.jlas.jlptpath.service.JpathWritingService;
import com.jlas.jlptpath.util.JpathStrokeSvg;
import com.jlas.jlptpath.util.JpathStrokeSvgIndex;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] Writing practice: every non-blank character of a word / kanji / kana becomes a cell; the stroke-order
 * SVG is offered only when {@link JpathStrokeSvgIndex} finds the file. Content is read through
 * {@link JpathContentReader}, so hidden or unreleased items read as absent.
 */
@Service
public class JpathWritingServiceImpl implements JpathWritingService {

    static final int TODAY_MAX = 30;
    private static final Locale VI = Locale.forLanguageTag("vi");

    private final JpathContentReader content;
    private final JpathStrokeSvgIndex svgIndex;
    private final JpathDailyPlanService dailyPlan;
    private final com.jlas.jlptpath.repository.JpathUserItemRepository userItems;
    private final com.jlas.jlptpath.service.JpathClock clock;

    public JpathWritingServiceImpl(JpathContentReader content, JpathStrokeSvgIndex svgIndex,
                                   JpathDailyPlanService dailyPlan) {
        this(content, svgIndex, dailyPlan, (com.jlas.jlptpath.repository.JpathUserItemRepository) null,
                (com.jlas.jlptpath.service.JpathClock) null);
    }

    /** Optional beans (absent in slim test contexts): without them "today" lists the planned items only. */
    @org.springframework.beans.factory.annotation.Autowired
    public JpathWritingServiceImpl(JpathContentReader content, JpathStrokeSvgIndex svgIndex,
                                   JpathDailyPlanService dailyPlan,
                                   org.springframework.beans.factory.ObjectProvider<com.jlas.jlptpath.repository.JpathUserItemRepository> userItems,
                                   org.springframework.beans.factory.ObjectProvider<com.jlas.jlptpath.service.JpathClock> clock) {
        this(content, svgIndex, dailyPlan, userItems.getIfAvailable(), clock.getIfAvailable());
    }

    JpathWritingServiceImpl(JpathContentReader content, JpathStrokeSvgIndex svgIndex,
                            JpathDailyPlanService dailyPlan,
                            com.jlas.jlptpath.repository.JpathUserItemRepository userItems,
                            com.jlas.jlptpath.service.JpathClock clock) {
        this.content = content;
        this.svgIndex = svgIndex;
        this.dailyPlan = dailyPlan;
        this.userItems = userItems;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public JpathWritingView view(Long userId, JpathItemType type, String code) {
        Item item = find(type, code).orElseThrow(() -> new ApiException(ErrorCode.JPATH_ITEM_NOT_FOUND));
        return build(List.of(item)).get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public List<JpathWritingView> today(Long userId, JpathTab tab) {
        // Owner 05/11: after the day is done the planned list is empty, so the items learned today come first.
        Set<String> codes = new LinkedHashSet<>();
        List<Item> items = new ArrayList<>();
        JpathItemType type = tabType(tab);
        if (type != null && userItems != null && clock != null) {
            java.time.LocalDateTime local = clock.localNow(userId);
            java.time.LocalDateTime since = clock.now()
                    .minus(java.time.Duration.between(local.toLocalDate().atStartOfDay(), local));
            for (var learned : userItems.findByUserIdAndItemTypeAndFirstLearnedAtGreaterThanEqualOrderByFirstLearnedAtAsc(
                    userId, type, since)) {
                if (items.size() >= TODAY_MAX) break;
                if (codes.add(learned.getItemCode())) find(type, learned.getItemCode()).ifPresent(items::add);
            }
        }
        for (JpathItemRef ref : dailyPlan.plannedNewItems(userId, tab)) {
            if (items.size() >= TODAY_MAX) {
                break;
            }
            if (codes.add(ref.code())) find(ref.type(), ref.code()).ifPresent(items::add);
        }
        return build(items);
    }

    private static JpathItemType tabType(JpathTab tab) {
        if (tab == null) return null;
        return switch (tab) {
            case VOCAB -> JpathItemType.VOCAB;
            case KANJI -> JpathItemType.KANJI;
            case KANA -> JpathItemType.KANA;
            default -> null;
        };
    }

    /** A resolved item before its characters are built: the view without cells plus the text to split. */
    private record Item(String type, String code, String word, String reading, String meaning, String hanViet) {
    }

    private Optional<Item> find(JpathItemType type, String code) {
        if (type == null) {
            return Optional.empty();
        }
        return switch (type) {
            case VOCAB -> content.vocab(code).map(JpathWritingServiceImpl::vocabItem);
            case KANJI -> content.kanji(code).map(JpathWritingServiceImpl::kanjiItem);
            case KANA -> content.kanaItem(code).map(JpathWritingServiceImpl::kanaItem);
            case GRAMMAR -> Optional.empty();
        };
    }

    private static Item vocabItem(JpathVocabulary v) {
        return new Item(JpathItemType.VOCAB.name(), v.getCode(), v.getExpression(), v.getReading(), v.getMeaningVi(),
                null);
    }

    private static Item kanjiItem(JpathKanji k) {
        String hanViet = k.getHanViet() == null ? null : k.getHanViet().toUpperCase(VI);
        return new Item(JpathItemType.KANJI.name(), k.getCode(), k.getCharacter(), readings(k), k.getMeaningsVi(), hanViet);
    }

    /** Kun readings then on readings ("ひと.つ、イチ"), so Tập viết can practise the reading in hiragana; null when none. */
    static String readings(JpathKanji k) {
        java.util.List<String> all = new java.util.ArrayList<>();
        // Owner 05/11: 下 listed 15 readings; the level's readings are enough, else the first four.
        String[] sources = k.getLevelReadingsJson() != null && k.getLevelReadingsJson().matches(".*[^\\[\\]\\s\"].*")
                ? new String[] { k.getLevelReadingsJson() } : new String[] { k.getReadingsKunJson(), k.getReadingsOnJson() };
        for (String json : sources) {
            if (json == null || json.isBlank()) continue;
            for (String part : json.replaceAll("[\\[\\]\"]", "").split(",")) {
                String r = part.trim();
                if (!r.isEmpty() && !all.contains(r)) all.add(r);
            }
        }
        return all.isEmpty() ? null : String.join("、", all.subList(0, Math.min(4, all.size())));
    }

    private static Item kanaItem(JpathKana k) {
        return new Item(JpathItemType.KANA.name(), k.getCode(), k.getKana(), k.getRomaji(), null, null);
    }

    /** Builds the views; the stroke counts of every Han character across items come from one batch lookup. */
    private List<JpathWritingView> build(List<Item> items) {
        Set<String> han = new LinkedHashSet<>();
        items.forEach(item -> codePoints(item.word()).filter(JpathWritingServiceImpl::isHan)
                .forEach(cp -> han.add(new String(Character.toChars(cp)))));
        Map<String, JpathKanji> kanji = han.isEmpty() ? Map.of() : content.kanjisByCharacter(han);
        return items.stream().map(item -> new JpathWritingView(item.type(), item.code(), item.word(), item.reading(),
                item.meaning(), item.hanViet(), characters(item.word(), kanji))).toList();
    }

    private static IntStream codePoints(String text) {
        return text == null ? IntStream.empty()
                : text.codePoints().filter(cp -> !Character.isWhitespace(cp) && !Character.isSpaceChar(cp));
    }

    private List<JpathWritingChar> characters(String text, Map<String, JpathKanji> kanji) {
        List<JpathWritingChar> out = new ArrayList<>();
        codePoints(text).forEach(cp -> {
            String ch = new String(Character.toChars(cp));
            boolean svg = svgIndex.exists(cp);
            JpathKanji k = isHan(cp) ? kanji.get(ch) : null;
            Integer strokes = k == null ? null : k.getStrokeCount();
            out.add(new JpathWritingChar(ch, cp, svg ? JpathStrokeSvg.url(cp) : null, strokes, svg));
        });
        return out;
    }

    private static boolean isHan(int cp) {
        return Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN;
    }
}
