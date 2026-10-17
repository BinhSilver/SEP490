package com.jlas.dictionary.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.dictionary.dto.ExampleSentenceResponse;
import com.jlas.dictionary.dto.LessonContentResponse;
import com.jlas.dictionary.dto.LessonVocabularyResponse;
import com.jlas.dictionary.entity.ContentItem;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.LessonSection;
import com.jlas.dictionary.entity.Textbook;
import com.jlas.dictionary.entity.Unit;
import com.jlas.dictionary.entity.VocabularyEntry;
import com.jlas.dictionary.mapper.DictionaryMapper;
import com.jlas.dictionary.repository.ContentAudioRepository;
import com.jlas.dictionary.repository.ContentItemRepository;
import com.jlas.dictionary.repository.ContentVocabularyRepository;
import com.jlas.dictionary.repository.DialogueLineChunkRepository;
import com.jlas.dictionary.repository.DialogueLineDetailRepository;
import com.jlas.dictionary.repository.DialogueRoleRepository;
import com.jlas.dictionary.repository.GrammarPointRepository;
import com.jlas.dictionary.repository.KanjiReadingRepository;
import com.jlas.dictionary.repository.LessonKanjiRepository;
import com.jlas.dictionary.repository.LessonRepository;
import com.jlas.dictionary.repository.VocabularyEntryRepository;
import com.jlas.dictionary.repository.VocabularyKanjiRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** Nội dung bài học: câu ví dụ từ vựng theo bài (lessonExamples) và đoạn văn/ảnh của câu luyện tập. */
@ExtendWith(MockitoExtension.class)
class DictionaryServiceImplLessonContentTest {

    private static final long LESSON_ID = 7L;

    @Mock LessonRepository lessonRepository;
    @Mock ContentVocabularyRepository contentVocabularyRepository;
    @Mock ContentItemRepository contentItemRepository;
    // Các repository khác getLessonContent có gọi: để mặc định (danh sách rỗng).
    @Mock LessonKanjiRepository lessonKanjiRepository;
    @Mock VocabularyKanjiRepository vocabularyKanjiRepository;
    @Mock KanjiReadingRepository kanjiReadingRepository;
    @Mock ContentAudioRepository contentAudioRepository;
    @Mock GrammarPointRepository grammarPointRepository;
    @Mock DialogueRoleRepository dialogueRoleRepository;
    @Mock DialogueLineDetailRepository dialogueLineDetailRepository;
    @Mock DialogueLineChunkRepository dialogueLineChunkRepository;
    @Mock VocabularyEntryRepository vocabularyEntryRepository;
    @Spy DictionaryMapper mapper = new DictionaryMapper();
    @Spy ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks DictionaryServiceImpl service;

    private LessonSection grammarSection;
    private LessonSection vocabExampleSection;

    @BeforeEach
    void publishedLesson() {
        Textbook textbook = Textbook.builder().id(1L).title("Giáo trình").status("PUBLISHED").build();
        Unit unit = Unit.builder().id(2L).title("Unit").textbook(textbook).build();
        Lesson lesson = Lesson.builder().id(LESSON_ID).title("Bài").unit(unit).status("PUBLISHED").build();
        when(lessonRepository.findWithUnitById(LESSON_ID)).thenReturn(Optional.of(lesson));
        grammarSection = LessonSection.builder().id(10L).lesson(lesson).title("Câu ví dụ").orderIndex(3).build();
        vocabExampleSection = LessonSection.builder().id(11L).lesson(lesson).title("Câu ví dụ từ vựng").orderIndex(2).build();
    }

    private static VocabularyEntry word(long id, String expression) {
        return VocabularyEntry.builder().id(id).expression(expression).reading(expression).meaning("nghĩa")
                .exampleJa("ví dụ chung " + id).build();
    }

    private ContentItem example(long id, LessonSection section, Long grammarPointId, String ja) {
        return ContentItem.builder().id(id).section(section).contentType("GRAMMAR").grammarPointId(grammarPointId)
                .title("Ví dụ").content(ja).translatedText("dịch " + id).orderIndex((int) id).build();
    }

    private LessonContentResponse loadWithExamples() {
        when(contentVocabularyRepository.findVocabularyByLessonId(LESSON_ID))
                .thenReturn(List.of(word(100L, "学生"), word(101L, "先生"), word(102L, "本")));
        when(contentItemRepository.findByLessonIdAndContentType(eq(LESSON_ID), eq("GRAMMAR"))).thenReturn(List.of(
                example(500L, grammarSection, 9L, "わたしは学生です。"),       // câu ví dụ ngữ pháp (gắn mẫu)
                example(501L, grammarSection, null, "これは本です。"),         // câu ví dụ khác (không gắn mẫu, không nối từ)
                example(502L, vocabExampleSection, null, "学生が来ます。"),     // ví dụ từ vựng của 学生
                example(503L, vocabExampleSection, null, "先生に聞きます。"),   // ví dụ từ vựng của 先生
                example(504L, vocabExampleSection, null, "学生の本。")));      // ví dụ thứ hai của 学生 (chỉ nối với 学生)
        when(contentVocabularyRepository.findVocabularyExampleLinksByLessonId(LESSON_ID)).thenReturn(List.of(
                new Object[]{502L, 100L}, new Object[]{503L, 101L}, new Object[]{504L, 100L}));
        return service.getLessonContent(LESSON_ID);
    }

    private static Map<Long, LessonVocabularyResponse> byId(LessonContentResponse response) {
        return response.getVocabularies().stream().collect(Collectors.toMap(LessonVocabularyResponse::getId, v -> v));
    }

    @Test
    void vocabularyLinkedExamplesGoToTheirWordAndNotToExampleSentences() {
        LessonContentResponse response = loadWithExamples();
        Map<Long, LessonVocabularyResponse> vocab = byId(response);

        assertEquals(List.of(502L, 504L), vocab.get(100L).getLessonExamples().stream()
                .map(ExampleSentenceResponse::getContentItemId).toList());
        assertEquals(List.of(503L), vocab.get(101L).getLessonExamples().stream()
                .map(ExampleSentenceResponse::getContentItemId).toList());
        assertEquals("学生が来ます。", vocab.get(100L).getLessonExamples().get(0).getTextJa());
        assertTrue(response.getExampleSentences().stream()
                .noneMatch(s -> List.of(502L, 503L, 504L).contains(s.getContentItemId())));
    }

    @Test
    void grammarExamplesWithoutVocabularyLinkStayInExampleSentences() {
        LessonContentResponse response = loadWithExamples();

        assertEquals(List.of(500L, 501L), response.getExampleSentences().stream()
                .map(ExampleSentenceResponse::getContentItemId).toList());
        assertEquals(9L, response.getExampleSentences().get(0).getGrammarPointId());
        assertNull(response.getExampleSentences().get(1).getGrammarPointId());
    }

    @Test
    void wordWithoutLessonExampleHasEmptyListAndKeepsDictionaryExample() {
        LessonVocabularyResponse book = byId(loadWithExamples()).get(102L);

        assertTrue(book.getLessonExamples().isEmpty());
        assertEquals("ví dụ chung 102", book.getExampleJa());
    }

    @Test
    void practiceQuestionCarriesPassageImageAndPassageCode() {
        Object[] row = new Object[13];
        row[0] = 900L;
        row[1] = 1;
        row[2] = "本文の内容と合うものはどれか。";
        row[3] = "一";
        row[4] = "二";
        row[7] = 1;
        row[9] = "{\"parent_code\":\"SOMATOME-N3-L001-RDG-001\",\"passage_code\":\"SOMATOME-N3-L001-RDG-001\"}";
        row[11] = "<p>先月、旅行に行きました。<br>楽しかったです。</p>";
        row[12] = "vnjpclub/N3/somatome-n3-doc-hieu/N3_Dokkai_Tuan1Ngay1_2.png";
        when(lessonRepository.findPracticeQuestionRows(eq(LESSON_ID), anyBoolean())).thenReturn(List.<Object[]>of(row));

        LessonContentResponse.PracticeQuestion q = service.getLessonContent(LESSON_ID).getPracticeQuestions().get(0);

        assertEquals("<p>先月、旅行に行きました。<br>楽しかったです。</p>", q.getPassageHtml());
        assertEquals("vnjpclub/N3/somatome-n3-doc-hieu/N3_Dokkai_Tuan1Ngay1_2.png", q.getImageUrl());
        assertEquals("SOMATOME-N3-L001-RDG-001", q.getPassageCode());
        assertEquals("SOMATOME-N3-L001-RDG-001", q.getParentCode());
    }

    @Test
    void practiceQuestionWithoutPassageHasNullFields() {
        Object[] row = new Object[13];
        row[0] = 901L;
        row[2] = "（　）に入るものはどれか。";
        row[3] = "は";
        row[4] = "が";
        row[7] = 2;
        row[9] = "{\"parent_code\":\"X\"}";
        row[11] = "  ";
        when(lessonRepository.findPracticeQuestionRows(eq(LESSON_ID), anyBoolean())).thenReturn(List.<Object[]>of(row));

        LessonContentResponse.PracticeQuestion q = service.getLessonContent(LESSON_ID).getPracticeQuestions().get(0);

        assertNull(q.getPassageHtml());
        assertNull(q.getImageUrl());
        assertNull(q.getPassageCode());
        assertEquals(List.of(), q.getPassageImages());
        assertEquals(List.of(), q.getQuestionImages());
    }

    @Test
    void practiceQuestionListsPassageAndQuestionImagesWithoutDuplicates() {
        Object[] row = new Object[13];
        row[0] = 902L;
        row[2] = "正しい絵はどれですか。";
        row[7] = 3;
        row[9] = "{\"passage_images\":[\"a.png\",\" \",\"b.png\",\"a.png\",7],"
                + "\"question_images\":[\"q1.png\",\"q2.png\"],\"question_image\":\"q2.png\"}";
        row[12] = "a.png";
        when(lessonRepository.findPracticeQuestionRows(eq(LESSON_ID), anyBoolean())).thenReturn(List.<Object[]>of(row));

        LessonContentResponse.PracticeQuestion q = service.getLessonContent(LESSON_ID).getPracticeQuestions().get(0);

        assertEquals("a.png", q.getImageUrl());
        assertEquals(List.of("a.png", "b.png"), q.getPassageImages());
        assertEquals(List.of("q1.png", "q2.png"), q.getQuestionImages());
    }

    @Test
    void singleQuestionImageFieldIsRead() {
        Object[] row = new Object[13];
        row[0] = 903L;
        row[2] = "どれですか。";
        row[9] = "{\"question_image\":\"vnjpclub/N5/x/q.png\"}";
        when(lessonRepository.findPracticeQuestionRows(eq(LESSON_ID), anyBoolean())).thenReturn(List.<Object[]>of(row));

        LessonContentResponse.PracticeQuestion q = service.getLessonContent(LESSON_ID).getPracticeQuestions().get(0);

        assertEquals(List.of("vnjpclub/N5/x/q.png"), q.getQuestionImages());
        assertEquals(List.of(), q.getPassageImages());
    }

    @Test
    void imageUrlThatIsTheQuestionImageIsNotAPassageImageAndCommonImagesAreRead() {
        Object[] row = new Object[13];
        row[0] = 904L;
        row[2] = "正しい絵はどれですか。";
        row[9] = "{\"question_images\":[\"vnjpclub/N5/t/q1.png\",\"vnjpclub/N5/t/q2.png\"],"
                + "\"common_images\":[\"vnjpclub/N5/t/c1.png\",\"vnjpclub/N5/t/c2.png\"]}";
        row[12] = "vnjpclub/N5/t/q1.png";
        when(lessonRepository.findPracticeQuestionRows(eq(LESSON_ID), anyBoolean())).thenReturn(List.<Object[]>of(row));
        when(lessonRepository.findLessonSourceCode(LESSON_ID)).thenReturn(java.util.Optional.of("VNJPCLUB"));

        LessonContentResponse response = service.getLessonContent(LESSON_ID);
        LessonContentResponse.PracticeQuestion q = response.getPracticeQuestions().get(0);

        assertEquals("vnjpclub/N5/t/q1.png", q.getImageUrl());
        assertEquals(List.of(), q.getPassageImages());
        assertEquals(List.of("vnjpclub/N5/t/q1.png", "vnjpclub/N5/t/q2.png"), q.getQuestionImages());
        assertEquals(List.of("vnjpclub/N5/t/c1.png", "vnjpclub/N5/t/c2.png"), q.getCommonImages());
        assertEquals("VNJPCLUB", response.getContentSource());
    }
}
