package com.jlas.kaiwa.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jlas.kaiwa.service.ai.TutorLlmClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Câu hỏi gõ romaji được đổi sang kana; giáo trình không có nội dung thì trả lời cố định, không để model tự giải thích. */
class RagTutorServiceTest {

    private static final RagLexiconLookup.Result NO_WORDS = new RagLexiconLookup.Result(List.of(), List.of());

    private final RagRetriever retriever = mock(RagRetriever.class);
    private final TutorLlmClient llm = mock(TutorLlmClient.class);
    private final TutorAntonymStore antonyms = mock(TutorAntonymStore.class);
    private final TutorAiAnswerStore aiAnswers = mock(TutorAiAnswerStore.class);
    private final RagTutorService tutor = new RagTutorService(retriever, llm, antonyms, aiAnswers);

    private static RagRetriever.Context passage(String title, String text) {
        return new RagRetriever.Context(List.of(new RagRetriever.Passage("id", 0.6, "GRAMMAR", title, text,
                Map.of("textbook", "Sekai N5", "lesson", "Bài 1"))), NO_WORDS);
    }

    @Test
    void romajiWordsAreReadAsHiragana() {
        assertThat(RomajiKana.toHiragana("nittote")).isEqualTo("にっとて");
        assertThat(RomajiKana.toHiragana("nitotte")).isEqualTo("にとって");
        assertThat(RomajiKana.toHiragana("sensei")).isEqualTo("せんせい");
        assertThat(RomajiKana.toHiragana("kanji")).isEqualTo("かんじ");
        assertThat(RomajiKana.toHiragana("watashi")).isEqualTo("わたし");
        assertThat(RomajiKana.toHiragana("thich")).isNull();

        assertThat(RomajiKana.hints("ngữ pháp nittote")).containsExactly(new RomajiKana.Hint("nittote", "にっとて"));
        assertThat(RomajiKana.hints("cach dung desu")).containsExactly(new RomajiKana.Hint("desu", "です"));
        // Tiếng Việt không dấu một âm tiết không bị coi là romaji; câu đã có chữ Nhật thì không đoán.
        assertThat(RomajiKana.hints("giai thich giup minh, muon hieu them")).isEmpty();
        assertThat(RomajiKana.hints("ngữ pháp にとって dùng thế nào, nitotte")).isEmpty();
    }

    @Test
    void onlyJapaneseQuestionsJLasCannotAnswerMayUseTheAiTier() {
        when(retriever.retrieve(anyString(), isNull())).thenReturn(new RagRetriever.Context(List.of(), NO_WORDS));
        assertThat(tutor.ask("誕生日 hay đi với từ nào?").fallbackEligible()).isTrue();
        assertThat(tutor.ask("Hôm nay thời tiết thế nào?").fallbackEligible()).isFalse();
        // Cách đọc, âm Hán Việt, cấp JLPT chỉ lấy từ dữ liệu J-LAS.
        assertThat(tutor.ask("誕生日 đọc là gì?").fallbackEligible()).isFalse();
        assertThat(tutor.ask("Âm Hán Việt của 誕生 là gì?").fallbackEligible()).isFalse();

        when(retriever.retrieve(anyString(), isNull())).thenReturn(passage("〜に", "Mẫu ngữ pháp: 〜に"));
        when(llm.chat(anyString(), anyList(), anyString(), anyInt())).thenReturn("Tài liệu J-LAS chưa có thông tin này cho từ này.");
        assertThat(tutor.ask("誕生日 hay đi với từ nào?").fallbackEligible()).isTrue();
        when(llm.chat(anyString(), anyList(), anyString(), anyInt())).thenReturn("〜に chỉ thời điểm [S1].");
        assertThat(tutor.ask("誕生日 hay đi với từ nào?").fallbackEligible()).isFalse();
    }

    @Test
    void aiTierIsLabelledSavedReusedAndShowsDictionaryReadings() {
        var birthday = new RagLexiconLookup.WordEntry(5, "誕生日", "たんじょうび", "sinh nhật", null, "N5");
        when(retriever.words(anyString())).thenReturn(List.of(birthday));
        when(llm.chat(eq(RagTutorService.FALLBACK_PROMPT), anyList(), anyString(), anyInt()))
                .thenReturn("- 誕生日を祝う (tanjoubi wo iwau) — chúc mừng sinh nhật");

        RagTutorService.Answer fresh = tutor.aiFallback("誕生日 hay đi với từ nào?", true, null, () -> true);
        assertThat(fresh.tier()).isEqualTo(RagTutorService.TIER_AI);
        assertThat(fresh.answer()).startsWith(RagTutorService.NOT_IN_JLAS + RagTutorService.AI_PART)
                .contains("誕生日を祝う — chúc mừng sinh nhật", "誕生日 (たんじょうび): sinh nhật").doesNotContain("tanjoubi");
        verify(aiAnswers).save(eq("誕生日 hay đi với từ nào?"), anyString(), any());

        // Nối sau câu trả lời tầng 1: không lặp câu "chưa có".
        assertThat(tutor.aiFallback("誕生日 hay đi với từ nào?", false, null, () -> true).answer()).startsWith(RagTutorService.AI_PART);

        // Đã lưu: không gọi lại AI và không tốn hạn mức; đã duyệt thì đổi nhãn; bị từ chối thì không có tầng 2.
        org.mockito.Mockito.clearInvocations(llm);
        when(aiAnswers.find("q")).thenReturn(java.util.Optional.of(new TutorAiAnswerStore.Saved("APPROVED", "đã sửa")));
        RagTutorService.Answer reviewed = tutor.aiFallback("q", true, null, () -> { throw new AssertionError("quota used"); });
        assertThat(reviewed.tier()).isEqualTo(RagTutorService.TIER_AI_REVIEWED);
        assertThat(reviewed.answer()).contains(RagTutorService.AI_REVIEWED_PART, "đã sửa");
        when(aiAnswers.find("q")).thenReturn(java.util.Optional.of(new TutorAiAnswerStore.Saved("REJECTED", "sai")));
        assertThat(tutor.aiFallback("q", true, null, () -> true)).isNull();
        verifyNoInteractions(llm);

        // Hết hạn mức, hoặc AI nói đây không phải câu hỏi tiếng Nhật: giữ câu trả lời tầng 1.
        assertThat(tutor.aiFallback("mới", true, null, () -> false)).isNull();
        when(llm.chat(eq(RagTutorService.FALLBACK_PROMPT), anyList(), anyString(), anyInt())).thenReturn("なし");
        assertThat(tutor.aiFallback("mới", true, null, () -> true)).isNull();
    }

    @Test
    void translationIsLabelledShowsDictionaryWordsAndIsNeverSaved() {
        assertThat(RagTutorService.isTranslationRequest("Dịch giúp mình câu này: 明日は雨です")).isTrue();
        assertThat(RagTutorService.isTranslationRequest("Dịch sang tiếng Nhật: Hôm nay trời mưa")).isTrue();
        assertThat(RagTutorService.isTranslationRequest("雨 nghĩa là gì")).isFalse();

        var rain = new RagLexiconLookup.WordEntry(9, "雨", "あめ", "mưa", null, "N5");
        when(retriever.words(anyString())).thenReturn(List.of(rain));
        when(llm.chat(eq(RagTutorService.TRANSLATE_PROMPT), anyList(), anyString(), anyInt())).thenReturn("Ngày mai trời mưa. (ashita)");

        RagTutorService.Answer answer = tutor.aiTranslate("Dịch giúp: 明日は雨です", null, () -> true);
        assertThat(answer.tier()).isEqualTo(RagTutorService.TIER_AI_TRANSLATION);
        assertThat(answer.answer()).startsWith(RagTutorService.TRANSLATION_PART).contains("Ngày mai trời mưa.", "雨 (あめ): mưa");
        verifyNoInteractions(aiAnswers);

        assertThat(tutor.aiTranslate("Dịch giúp: 明日は雨です", null, () -> false)).isNull();
        when(llm.chat(eq(RagTutorService.TRANSLATE_PROMPT), anyList(), anyString(), anyInt())).thenReturn("なし");
        assertThat(tutor.aiTranslate("Dịch sang tiếng Pháp: 雨", null, () -> true)).isNull();
    }

    @Test
    void questionKeyIgnoresCaseSpacesAndTrailingPunctuation() {
        assertThat(TutorAiAnswerStore.hash("誕生日  hay đi với từ nào？")).isEqualTo(TutorAiAnswerStore.hash(" 誕生日 Hay đi với từ nào"));
        assertThat(TutorAiAnswerStore.hash("誕生日")).isNotEqualTo(TutorAiAnswerStore.hash("誕生"));
    }

    @Test
    void antonymSuggestedByTheModelIsShownOnlyWhenTheDictionaryHasThatWord() {
        var fun = new RagLexiconLookup.WordEntry(1, "楽しい", "たのしい", "vui", null, "N5");
        var boring = new RagLexiconLookup.WordEntry(2, "つまらない", "つまらない", "chán", null, "N5");
        when(retriever.retrieve(anyString(), isNull()))
                .thenReturn(new RagRetriever.Context(List.of(), new RagLexiconLookup.Result(List.of(fun), List.of())));
        when(llm.chat(anyString(), any(), anyString(), anyInt())).thenReturn("つまらない、ほげほげ、楽しい");
        when(retriever.words("つまらない")).thenReturn(List.of(boring));

        RagTutorService.Answer answer = tutor.ask("Từ trái nghĩa của 「楽しい」 là gì?");

        // Nghĩa và cách đọc lấy từ từ điển; từ không có trong từ điển và chính từ đang hỏi bị bỏ.
        assertThat(answer.answer()).contains("つまらない (つまらない): chán [D2]", "chưa được người duyệt").doesNotContain("ほげほげ");
        assertThat(answer.citations()).extracting(RagPromptComposer.Citation::code).containsExactly("D1", "D2");

        when(llm.chat(anyString(), any(), anyString(), anyInt())).thenReturn("なし");
        RagTutorService.Answer none = tutor.ask("Từ trái nghĩa của 「楽しい」 là gì?");
        assertThat(none.answer()).contains("chưa tìm được gợi ý nào");
        assertThat(none.citations()).isEmpty();

        // Từ điển đã ghi từ trái nghĩa thì đi đường thường, không qua bước đề xuất.
        var tall = new RagLexiconLookup.WordEntry(3, "高い", "たかい", "cao", null, "N5", List.of("Trái nghĩa: 低い (ひくい)"));
        assertThat(RagTutorService.antonymTarget("Từ trái nghĩa của 高い", new RagLexiconLookup.Result(List.of(tall), List.of()))).isNull();
        assertThat(RagTutorService.antonymTarget("楽しい nghĩa là gì", new RagLexiconLookup.Result(List.of(fun), List.of()))).isNull();
    }

    @Test
    void savedAntonymSuggestionIsReusedWithoutCallingTheModelAgain() {
        var fun = new RagLexiconLookup.WordEntry(1, "楽しい", "たのしい", "vui", null, "N5");
        var boring = new RagLexiconLookup.WordEntry(2, "つまらない", "つまらない", "chán", null, "N5");
        when(retriever.retrieve(anyString(), isNull()))
                .thenReturn(new RagRetriever.Context(List.of(), new RagLexiconLookup.Result(List.of(fun), List.of())));
        when(llm.chat(anyString(), any(), anyString(), anyInt())).thenReturn("つまらない");
        when(retriever.words("つまらない")).thenReturn(List.of(boring));

        tutor.ask("Từ trái nghĩa của 「楽しい」 là gì?");
        verify(antonyms).save(eq(1L), eq(2L), any());

        // Lần sau: gợi ý chờ duyệt vẫn kèm cảnh báo, đã duyệt thì không, bị từ chối thì báo chưa có; model không được gọi lại.
        when(antonyms.find(1L)).thenReturn(java.util.Optional.of(new TutorAntonymStore.Saved("PENDING", boring)));
        assertThat(tutor.ask("Từ trái nghĩa của 「楽しい」 là gì?").answer()).contains("つまらない", "chưa được người duyệt");
        when(antonyms.find(1L)).thenReturn(java.util.Optional.of(new TutorAntonymStore.Saved("APPROVED", boring)));
        assertThat(tutor.ask("Từ trái nghĩa của 「楽しい」 là gì?").answer())
                .contains("つまらない (つまらない): chán [D2]").doesNotContain("chưa được người duyệt");
        when(antonyms.find(1L)).thenReturn(java.util.Optional.of(new TutorAntonymStore.Saved("REJECTED", boring)));
        assertThat(tutor.ask("Từ trái nghĩa của 「楽しい」 là gì?").answer()).contains("chưa tìm được gợi ý nào");
        verify(llm, org.mockito.Mockito.times(1)).chat(anyString(), any(), anyString(), anyInt());
    }

    @Test
    void noSourceGetsAFixedAnswerWithoutCallingTheModel() {
        when(retriever.retrieve(anyString(), isNull())).thenReturn(new RagRetriever.Context(List.of(), NO_WORDS));

        RagTutorService.Answer answer = tutor.ask("Hôm nay thời tiết thế nào?");

        assertThat(answer.answer()).isEqualTo(RagTutorService.NO_SOURCE);
        assertThat(answer.citations()).isEmpty();
        assertThat(tutor.ask("Mình bị đau đầu thì nên uống thuốc gì?").answer())
                .isEqualTo(RagTutorService.NO_SOURCE + RagTutorService.SEE_A_DOCTOR);
        verifyNoInteractions(llm);
    }

    @Test
    void romajiGrammarPatternMissingFromTheTextbookIsNotExplained() {
        when(retriever.retrieve(anyString(), isNull())).thenReturn(passage("N1 は N2 です", "Mẫu ngữ pháp: N1 は N2 です"));

        RagTutorService.Answer answer = tutor.ask("ngữ pháp nittote");

        assertThat(answer.answer()).startsWith("Tài liệu J-LAS chưa có mẫu này.").contains("\"nittote\" là 「にっとて」");
        assertThat(answer.citations()).isEmpty();
        verify(retriever).retrieve("ngữ pháp nittote\nにっとて", null);
        verifyNoInteractions(llm);
    }

    @Test
    void romajiFoundInTheTextbookIsAnsweredWithTheKanaHint() {
        when(retriever.retrieve(anyString(), isNull())).thenReturn(passage("～にとって", "Mẫu ～にとって: đối với ..."));
        when(llm.chat(anyString(), anyList(), anyString(), anyInt())).thenReturn("～にとって nghĩa là \"đối với\" [S1].");
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);

        RagTutorService.Answer answer = tutor.ask("ngu phap nitotte");

        verify(llm).chat(system.capture(), eq(List.of()), eq("ngu phap nitotte"), anyInt());
        assertThat(system.getValue()).contains("\"nitotte\" = にとって", "[S1] Sekai N5 › Bài 1");
        assertThat(answer.citations()).extracting(RagPromptComposer.Citation::code).containsExactly("S1");
    }

    @Test
    void kanjiLinesAreDroppedOnceAWordIsFoundUnlessTheLearnerAsksAboutKanji() {
        var gaku = new RagLexiconLookup.KanjiEntry(4, "学", "Học", null, "N5", List.of("ガク"), List.of());
        var kou = new RagLexiconLookup.KanjiEntry(88, "校", "Hiệu", "kiểm tra, sửa chữa", "N5", List.of("コウ"), List.of());
        var iku = new RagLexiconLookup.KanjiEntry(9, "行", "Hành", null, "N5", List.of("コウ"), List.of("い.く"));
        var gakkou = new RagLexiconLookup.WordEntry(99, "学校", "がっこう", "trường học", null, "N5");
        var context = new RagRetriever.Context(List.of(), new RagLexiconLookup.Result(List.of(gakkou), List.of(gaku, kou, iku)));

        assertThat(RagTutorService.kanjiOnlyWhenNeeded(context, "Câu 学校を行きました sai ở đâu?").lexicon().kanji()).isEmpty();
        assertThat(RagTutorService.kanjiOnlyWhenNeeded(context, "Âm Hán Việt của 学校 là gì?").lexicon().kanji()).hasSize(3);
        assertThat(RagTutorService.kanjiOnlyWhenNeeded(context, "学校 đọc On/Kun thế nào?").lexicon().kanji()).hasSize(3);
        var kanjiOnly = new RagRetriever.Context(List.of(), new RagLexiconLookup.Result(List.of(), List.of(iku)));
        assertThat(RagTutorService.kanjiOnlyWhenNeeded(kanjiOnly, "行 nghĩa là gì?").lexicon().kanji()).hasSize(1);
    }

    @Test
    void hanVietIsCopiedFromTheDictionaryWithoutCallingTheModel() {
        var ben = new RagLexiconLookup.KanjiEntry(1, "勉", "Miễn, Mẫn", null, "N5", List.of("ベン"), List.of());
        var kyou = new RagLexiconLookup.KanjiEntry(2, "強", "Cường, Cưỡng", null, "N4", List.of("キョウ"), List.of("つよ.い"));
        var benkyou = new RagLexiconLookup.WordEntry(3, "勉強", "べんきょう", "việc học", null, "N5");
        when(retriever.retrieve(anyString(), isNull())).thenReturn(new RagRetriever.Context(List.of(),
                new RagLexiconLookup.Result(List.of(benkyou), List.of(ben, kyou))));

        RagTutorService.Answer answer = tutor.ask("Âm Hán Việt của 勉強 là gì?");

        assertThat(answer.answer()).isEqualTo("""
                Âm Hán Việt theo từ điển J-LAS:
                - 勉: Miễn, Mẫn [D1]
                - 強: Cường, Cưỡng [D2]

                勉強 (べんきょう) nghĩa là việc học [D3]""");
        assertThat(answer.citations()).extracting(RagPromptComposer.Citation::code).containsExactly("D1", "D2", "D3");
        verifyNoInteractions(llm);
    }

    @Test
    void shortFollowUpIsSearchedTogetherWithThePreviousQuestion() {
        List<Map<String, String>> history = List.of(
                Map.of("role", "user", "content", "～てもいい là gì?"),
                Map.of("role", "assistant", "content", "～てもいい là được phép làm gì [S1]."));

        assertThat(RagTutorService.retrievalQuery("cho thêm ví dụ", List.of(), history)).isEqualTo("～てもいい là gì?\ncho thêm ví dụ");
        assertThat(RagTutorService.retrievalQuery("Câu 学校を行きました sai ở đâu, sửa giúp mình với nhé bạn ơi?", List.of(), history))
                .isEqualTo("Câu 学校を行きました sai ở đâu, sửa giúp mình với nhé bạn ơi?");
        assertThat(RagTutorService.isGrammarQuestion("Cấu trúc này dùng sao")).isTrue();
        assertThat(RagTutorService.isGrammarQuestion("nittote là gì")).isFalse();
    }

    @Test
    void romajiInParenthesesIsRemovedButVietnameseAndJapaneseStay() {
        assertThat(RagTutorService.stripRomaji("- 暇すらなかった。 (Hiragana: *hima sura nakatta*): Không có thời gian (rất bận) [S1]"))
                .isEqualTo("- 暇すらなかった。: Không có thời gian (rất bận) [S1]");
        assertThat(RagTutorService.stripRomaji("駅(えき)まで (N + まで) [S1]")).isEqualTo("駅(えき)まで (N + まで) [S1]");
        assertThat(RagTutorService.stripRomaji("Dòng không có chữ Nhật (hello world)")).isEqualTo("Dòng không có chữ Nhật (hello world)");
        // Romaji ngắn ngay sau chữ Nhật (model 9B); nhãn viết hoa và nghĩa tiếng Việt được giữ.
        assertThat(RagTutorService.stripRomaji("Từ trái nghĩa của 高い (takai) là 低い [D1].")).isEqualTo("Từ trái nghĩa của 高い là 低い [D1].");
        assertThat(RagTutorService.stripRomaji("**高い** (takai) nghĩa là đắt")).isEqualTo("**高い** nghĩa là đắt");
        assertThat(RagTutorService.stripRomaji("ブリッジSE (BrSE) và 高い (đắt) và 文法 (JLPT N2)"))
                .isEqualTo("ブリッジSE (BrSE) và 高い (đắt) và 文法 (JLPT N2)");
    }

    @Test
    void patternKeyKeepsOnlyTheJapaneseOfAPatternName() {
        assertThat(RagChunk.patternKey("N + にとって")).isEqualTo("にとって");
        assertThat(RagChunk.patternKey("～にとって")).isEqualTo("にとって");
        assertThat(RagChunk.patternKey("が早(はや)いか")).isEqualTo("が早いか");
        assertThat(RagChunk.patternKey(null)).isEmpty();
    }
}
