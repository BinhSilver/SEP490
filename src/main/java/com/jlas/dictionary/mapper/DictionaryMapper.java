package com.jlas.dictionary.mapper;

import com.jlas.dictionary.dto.*;
import com.jlas.dictionary.entity.*;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Mapper chuyển đổi giữa Dictionary entities và DTO.
 * Chỉ map entity đơn lẻ -> DTO; việc gom nhóm sense/gloss/kanji-composition theo lô
 * (để tránh N+1) thuộc trách nhiệm của DictionaryServiceImpl.
 */
@Component
public class DictionaryMapper {

    public KanjiResponse toKanjiResponse(Kanji kanji, List<KanjiReadingResponse> readings) {
        return KanjiResponse.builder()
                .id(kanji.getId())
                .character(kanji.getCharacter())
                .unicodeCode(kanji.getUnicodeCode())
                .strokeCount(kanji.getStrokeCount() == null ? null : kanji.getStrokeCount().intValue())
                .hanViet(kanji.getHanViet())
                .meaningVi(kanji.getMeaningVi())
                .jlptLevel(kanji.getJlptLevel())
                .strokeSvgUri(kanji.getStrokeSvgUri())
                .radical(toRadicalResponse(kanji.getRadical()))
                .readings(readings == null ? List.of() : readings)
                .build();
    }

    public RadicalResponse toRadicalResponse(Radical radical) {
        if (radical == null) return null;
        return RadicalResponse.builder()
                .radicalNumber(radical.getRadicalNumber() == null ? null : radical.getRadicalNumber().intValue())
                .character(radical.getCharacter())
                .nameJa(radical.getNameJa())
                .nameVi(radical.getNameVi())
                .meaningVi(radical.getMeaningVi())
                .strokeCount(radical.getStrokeCount() == null ? null : radical.getStrokeCount().intValue())
                .build();
    }

    public KanjiReadingResponse toKanjiReadingResponse(KanjiReading reading) {
        return KanjiReadingResponse.builder()
                .readingType(reading.getReadingType())
                .readingText(reading.getReadingText())
                .common(Boolean.TRUE.equals(reading.getIsCommon()))
                .build();
    }

    public VocabularyResponse toVocabularyResponse(VocabularyEntry entry,
                                                     List<SenseResponse> senses,
                                                     List<KanjiCompositionResponse> composition) {
        return VocabularyResponse.builder()
                .id(entry.getId())
                .expression(entry.getExpression())
                .reading(entry.getReading())
                .readingRomaji(entry.getReadingRomaji())
                .hanViet(entry.getHanViet())
                .meaning(entry.getMeaning())
                .wordType(entry.getWordType())
                .jlptLevel(entry.getJlptLevel())
                .exampleJa(entry.getExampleJa())
                .exampleVi(entry.getExampleVi())
                .usageScope(entry.getUsageScope())
                .senses(senses == null ? List.of() : senses)
                .kanjiComposition(composition == null ? List.of() : composition)
                .build();
    }

    public SenseResponse toSenseResponse(VocabularySense sense, List<String> partsOfSpeech,
                                          List<String> domains, List<GlossResponse> glosses) {
        return SenseResponse.builder()
                .senseOrder(sense.getSenseOrder() == null ? null : sense.getSenseOrder().intValue())
                .usageNote(sense.getUsageNote())
                .partsOfSpeech(partsOfSpeech == null ? List.of() : partsOfSpeech)
                .domains(domains == null ? List.of() : domains)
                .glosses(glosses == null ? List.of() : glosses)
                .build();
    }

    public GlossResponse toGlossResponse(VocabularyGloss gloss) {
        return GlossResponse.builder()
                .languageCode(gloss.getLanguageCode())
                .text(gloss.getGlossText())
                .build();
    }

    public KanjiCompositionResponse toKanjiCompositionResponse(VocabularyKanji vocabularyKanji) {
        return KanjiCompositionResponse.builder()
                .position(vocabularyKanji.getId().getCharacterPosition())
                .character(vocabularyKanji.getKanji().getCharacter())
                .jlptLevel(vocabularyKanji.getKanji().getJlptLevel())
                .build();
    }

    public SearchHistoryResponse toHistoryResponse(DictionarySearchHistory history) {
        return SearchHistoryResponse.builder()
                .id(history.getId())
                .queryText(history.getQueryText())
                .searchMode(history.getSearchMode())
                .resultCount(history.getResultCount())
                .searchedAt(history.getSearchedAt())
                .selectedVocabularyId(history.getSelectedVocabulary() == null
                        ? null : history.getSelectedVocabulary().getId())
                .build();
    }
}
