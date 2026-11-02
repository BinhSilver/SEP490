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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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

    public JpathWritingServiceImpl(JpathContentReader content, JpathStrokeSvgIndex svgIndex,
                                   JpathDailyPlanService dailyPlan) {
        this.content = content;
        this.svgIndex = svgIndex;
        this.dailyPlan = dailyPlan;
    }

    @Override
    @Transactional(readOnly = true)
    public JpathWritingView view(Long userId, JpathItemType type, String code) {
        return find(type, code).orElseThrow(() -> new ApiException(ErrorCode.JPATH_ITEM_NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public List<JpathWritingView> today(Long userId, JpathTab tab) {
        List<JpathWritingView> out = new ArrayList<>();
        for (JpathItemRef ref : dailyPlan.plannedNewItems(userId, tab)) {
            if (out.size() >= TODAY_MAX) {
                break;
            }
            find(ref.type(), ref.code()).ifPresent(out::add);
        }
        return out;
    }

    private Optional<JpathWritingView> find(JpathItemType type, String code) {
        if (type == null) {
            return Optional.empty();
        }
        return switch (type) {
            case VOCAB -> content.vocab(code).map(this::vocabView);
            case KANJI -> content.kanji(code).map(this::kanjiView);
            case KANA -> content.kanaItem(code).map(this::kanaView);
            case GRAMMAR -> Optional.empty();
        };
    }

    private JpathWritingView vocabView(JpathVocabulary v) {
        return new JpathWritingView(JpathItemType.VOCAB.name(), v.getCode(), v.getExpression(), v.getReading(),
                v.getMeaningVi(), null, characters(v.getExpression()));
    }

    private JpathWritingView kanjiView(JpathKanji k) {
        String hanViet = k.getHanViet() == null ? null : k.getHanViet().toUpperCase(VI);
        return new JpathWritingView(JpathItemType.KANJI.name(), k.getCode(), k.getCharacter(), null,
                k.getMeaningsVi(), hanViet, characters(k.getCharacter()));
    }

    private JpathWritingView kanaView(JpathKana k) {
        return new JpathWritingView(JpathItemType.KANA.name(), k.getCode(), k.getKana(), k.getRomaji(), null, null,
                characters(k.getKana()));
    }

    private List<JpathWritingChar> characters(String text) {
        if (text == null) {
            return List.of();
        }
        List<JpathWritingChar> out = new ArrayList<>();
        text.codePoints().filter(cp -> !Character.isWhitespace(cp) && !Character.isSpaceChar(cp)).forEach(cp -> {
            String ch = new String(Character.toChars(cp));
            boolean svg = svgIndex.exists(cp);
            Integer strokes = isHan(cp)
                    ? content.kanjiByCharacter(ch).map(JpathKanji::getStrokeCount).orElse(null) : null;
            out.add(new JpathWritingChar(ch, cp, svg ? JpathStrokeSvg.url(cp) : null, strokes, svg));
        });
        return out;
    }

    private static boolean isHan(int cp) {
        return Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN;
    }
}
