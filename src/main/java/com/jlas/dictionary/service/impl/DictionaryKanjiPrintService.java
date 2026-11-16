package com.jlas.dictionary.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.Kanji;
import com.jlas.dictionary.entity.KanjiReading;
import com.jlas.dictionary.entity.VocabularyEntry;
import com.jlas.dictionary.repository.KanjiReadingRepository;
import com.jlas.dictionary.repository.KanjiRepository;
import com.jlas.dictionary.repository.VocabularyEntryRepository;
import com.jlas.dictionary.repository.VocabularyKanjiRepository;
import com.jlas.dictionary.util.DictionaryUtil;
import com.jlas.jlptpath.dto.JpathPrintRow;
import com.jlas.jlptpath.dto.JpathPrintSheet;
import com.jlas.jlptpath.util.JpathPrintLayout;
import com.jlas.jlptpath.util.JpathStrokeSvg;
import com.jlas.jlptpath.util.JpathStrokeSvgIndex;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DictionaryKanjiPrintService {
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE_VI = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final KanjiRepository kanjis;
    private final KanjiReadingRepository readings;
    private final VocabularyKanjiRepository vocabulary;
    private final JpathStrokeSvgIndex svgIndex;

    @Transactional(readOnly = true)
    public JpathPrintSheet sheet(String character) {
        if (character == null || character.codePointCount(0, character.length()) != 1
                || !DictionaryUtil.isKanjiCodepoint(character.codePointAt(0))) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy Kanji");
        }
        Kanji kanji = kanjis.findByCharacter(character)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy Kanji"));
        String reading = readings.findByKanji_IdOrderByReadingTypeAscReadingTextAsc(kanji.getId()).stream()
                .filter(item -> "ON".equals(item.getReadingType()) || "KUN".equals(item.getReadingType()))
                .sorted(Comparator.comparingInt(item -> "ON".equals(item.getReadingType()) ? 0 : 1))
                .map(KanjiReading::getReadingText).filter(text -> text != null && !text.isBlank())
                .distinct().collect(java.util.stream.Collectors.joining("・"));
        int cp = character.codePointAt(0);
        String svg = svgIndex.exists(cp) ? JpathStrokeSvg.url(cp) : kanji.getStrokeSvgUri();
        String hanViet = kanji.getHanViet() == null ? null : kanji.getHanViet().toUpperCase(Locale.forLanguageTag("vi"));
        Integer strokes = kanji.getStrokeCount() == null ? null : kanji.getStrokeCount().intValue();
        JpathPrintRow kanjiRow = new JpathPrintRow("KANJI", character, reading.isEmpty() ? null : reading,
                hanViet, kanji.getMeaningVi(), svg, strokes, JpathPrintLayout.cells(1));
        List<JpathPrintRow> rows = new ArrayList<>(6);
        for (int i = 0; i < 3; i++) rows.add(kanjiRow);
        Set<String> expressions = new HashSet<>();
        vocabulary.findPrintableVocabularyByKanjiCharacter(character, PageRequest.of(0, 6)).stream()
                .filter(entry -> "PUBLISHED".equals(entry.getDictionaryStatus())
                        && !"TEXTBOOK".equals(entry.getUsageScope())
                        && hasVietnameseMeaning(entry.getMeaning()))
                .filter(entry -> !character.equals(entry.getExpression()) && expressions.add(entry.getExpression()))
                .limit(3).map(DictionaryKanjiPrintService::vocabRow).forEach(rows::add);
        return new JpathPrintSheet("Luyện viết " + character, LocalDate.now(VIETNAM).format(DATE_VI),
                null, false, JpathPrintLayout.pages(rows), null);
    }

    private static JpathPrintRow vocabRow(VocabularyEntry entry) {
        String expression = entry.getExpression();
        int length = (int) expression.codePoints().filter(cp -> !Character.isWhitespace(cp)).count();
        String meaning = java.util.Arrays.stream(entry.getMeaning().strip().split("[\\r\\n;；]"))
                .map(String::strip).filter(DictionaryKanjiPrintService::hasVietnameseMeaning)
                .findFirst().orElse(entry.getMeaning());
        return new JpathPrintRow("VOCAB", expression, entry.getReading(), null, meaning, null, null,
                JpathPrintLayout.cells(Math.max(1, length)));
    }

    private static boolean hasVietnameseMeaning(String meaning) {
        if (meaning == null) return false;
        String lower = meaning.toLowerCase(Locale.ROOT);
        return lower.chars().anyMatch(cp -> VocabularyEntryRepository.VIETNAMESE_MEANING_LETTERS.indexOf(cp) >= 0);
    }
}
