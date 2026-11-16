package com.jlas.kaiwa.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jlas.kaiwa.service.ai.LocalLlmClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Câu hỏi gõ romaji được đổi sang kana; giáo trình không có nội dung thì trả lời cố định, không để model tự giải thích. */
class RagTutorServiceTest {

    private static final RagLexiconLookup.Result NO_WORDS = new RagLexiconLookup.Result(List.of(), List.of());

    private final RagRetriever retriever = mock(RagRetriever.class);
    private final LocalLlmClient llm = mock(LocalLlmClient.class);
    private final RagTutorService tutor = new RagTutorService(retriever, llm);

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
    }
}
