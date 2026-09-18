package com.jlas.dictionary;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.dto.DialogueLineResponse;
import com.jlas.dictionary.dto.ExampleSentenceResponse;
import com.jlas.dictionary.dto.KanjiResponse;
import com.jlas.dictionary.dto.LessonContentResponse;
import com.jlas.dictionary.dto.LessonVocabularyResponse;
import com.jlas.dictionary.dto.TopicLessonResponse;
import com.jlas.dictionary.dto.TopicLessonsResponse;
import com.jlas.dictionary.dto.TopicResponse;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.repository.ContentAudioRepository;
import com.jlas.dictionary.repository.ContentItemRepository;
import com.jlas.dictionary.repository.DialogueLineChunkRepository;
import com.jlas.dictionary.repository.KanjiReadingRepository;
import com.jlas.dictionary.repository.KanjiRepository;
import com.jlas.dictionary.repository.LessonRepository;
import com.jlas.dictionary.repository.VocabularyEntryRepository;
import com.jlas.dictionary.service.DictionaryService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test [JLAS-W0070 / W0080 / JLAS2-17] chạy lên MySQL local đã seed bằng seed_data_jlas.sql
 * (cấu hình datasource lấy từ application-local.yaml như {@code JlasApplicationTests}).
 * <p>
 * Mặc định BỊ BỎ QUA để máy chưa seed không đỏ build. Bật bằng system property:
 * <pre>
 *   .\mvnw.cmd test -Djlas.seeded=true
 *   .\mvnw.cmd test -Djlas.seeded=true -Dtest=LessonContentSeedTests
 * </pre>
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@EnabledIfSystemProperty(named = "jlas.seeded", matches = "true")
class LessonContentSeedTests {

    private static final long LESSON_1 = 1L;
    /** Seed hiện chỉ có giọng chuan-nu; backend vẫn đa giọng, seed thêm giọng thì tăng số này. */
    private static final int VOICE_COUNT = 1;

    @Autowired private VocabularyEntryRepository vocabularyEntryRepository;
    @Autowired private ContentAudioRepository contentAudioRepository;
    @Autowired private ContentItemRepository contentItemRepository;
    @Autowired private DialogueLineChunkRepository dialogueLineChunkRepository;
    @Autowired private KanjiRepository kanjiRepository;
    @Autowired private KanjiReadingRepository kanjiReadingRepository;
    @Autowired private DictionaryService dictionaryService;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private LessonRepository lessonRepository;
    @Autowired private MockMvc mockMvc;

    @Test
    void seedRowCountsMatchDigitizedContent() {
        assertEquals(1759L, vocabularyEntryRepository.count(), "vocabulary_entry");
        assertEquals(5057L, contentAudioRepository.count(), "content_audio");
        assertEquals(3237L, dialogueLineChunkRepository.count(), "dialogue_line_chunk");
        assertEquals(3556L, contentItemRepository.count(), "content_item (3348 + 208 chữ kana)");
        assertEquals(533L, kanjiRepository.count(), "kanji");
        assertEquals(2056L, kanjiReadingRepository.count(), "kanji_reading");
        assertEquals(136L, countRadicals(), "radical");
    }

    @Test
    void lesson1HasSixtyOneKanjiWithReadingsAndWords() {
        LessonContentResponse content = dictionaryService.getLessonContent(LESSON_1);

        assertEquals(61, content.getKanjis().size(), "Kanji xuất hiện trong từ vựng bài 1");
        for (KanjiResponse k : content.getKanjis()) {
            assertNotNull(k.getHanViet(), "Kanji " + k.getCharacter() + " phải có âm Hán Việt");
            assertFalse(k.getReadings().isEmpty(), "Kanji " + k.getCharacter() + " phải có cách đọc");
            assertFalse(k.getWords().isEmpty(), "Kanji " + k.getCharacter() + " phải thuộc ít nhất một từ của bài");
        }
    }

    @Test
    void lesson1EveryVocabularyHasOneAudioPerVoice() {
        LessonContentResponse content = dictionaryService.getLessonContent(LESSON_1);

        assertFalse(content.getVocabularies().isEmpty(), "bài 1 phải có từ vựng");
        for (LessonVocabularyResponse v : content.getVocabularies()) {
            assertEquals(VOICE_COUNT, v.getAudios().size(), "từ vựng id=" + v.getId() + " phải có " + VOICE_COUNT + " audio");
        }
    }

    @Test
    void lesson1HasFiftyFourExampleSentencesEachWithAudio() {
        LessonContentResponse content = dictionaryService.getLessonContent(LESSON_1);

        assertEquals(54, content.getExampleSentences().size(), "content_item GRAMMAR của bài 1");
        for (ExampleSentenceResponse s : content.getExampleSentences()) {
            assertEquals(VOICE_COUNT, s.getAudios().size(), "câu ví dụ id=" + s.getContentItemId() + " phải có " + VOICE_COUNT + " audio");
        }
    }


    /**
     * [JLAS-W0070] content_item.grammar_point_id: tab Ngữ pháp gom câu ví dụ theo mẫu.
     * Seed: 2493/2498 câu gắn mẫu; 5 câu không gắn đều ở bài 1 → khối "Câu ví dụ khác".
     */
    @Test
    void lesson1ExampleSentencesAreLinkedToItsGrammarPoints() {
        LessonContentResponse content = dictionaryService.getLessonContent(LESSON_1);
        Set<Long> pointIds = content.getGrammarPoints().stream().map(g -> g.getId()).collect(Collectors.toSet());
        assertEquals(8, pointIds.size(), "bài 1 có 8 mẫu ngữ pháp");

        long linked = content.getExampleSentences().stream().filter(s -> s.getGrammarPointId() != null).count();
        assertEquals(49, linked, "49/54 câu bài 1 gắn mẫu ngữ pháp");
        for (ExampleSentenceResponse s : content.getExampleSentences()) {
            if (s.getGrammarPointId() != null) {
                assertTrue(pointIds.contains(s.getGrammarPointId()),
                        "câu id=" + s.getContentItemId() + " gắn mẫu " + s.getGrammarPointId() + " không thuộc bài 1");
            }
        }
        // Mỗi mẫu của bài 1 có ít nhất 1 câu (3–12 câu) → không khối nào phải rơi về grammar_point.example.
        for (Long id : pointIds) {
            assertTrue(content.getExampleSentences().stream().anyMatch(s -> id.equals(s.getGrammarPointId())),
                    "mẫu ngữ pháp id=" + id + " không có câu ví dụ nào gắn");
        }
    }

    @Test
    void almostAllExampleSentencesAreLinkedToAGrammarPoint() {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            long total = em.createQuery("select count(c) from ContentItem c where c.contentType = 'GRAMMAR'", Long.class).getSingleResult();
            long linked = em.createQuery("select count(c) from ContentItem c where c.contentType = 'GRAMMAR' and c.grammarPointId is not null", Long.class).getSingleResult();
            assertEquals(2498L, total);
            assertEquals(2493L, linked, "5 câu không gắn mẫu (đều ở bài 1)");
        } finally {
            em.close();
        }
    }
    @Test
    void lesson1DialogueLinesHaveRoleChunksAndAudio() {
        LessonContentResponse content = dictionaryService.getLessonContent(LESSON_1);

        assertFalse(content.getDialogueRoles().isEmpty(), "bài 1 phải có vai diễn");
        assertFalse(content.getDialogueLines().isEmpty(), "bài 1 phải có lời thoại");
        for (DialogueLineResponse line : content.getDialogueLines()) {
            assertNotNull(line.getRoleId(), "lời thoại id=" + line.getContentItemId() + " phải có vai");
            assertFalse(line.getChunks().isEmpty(), "lời thoại id=" + line.getContentItemId() + " phải có cụm tách nghĩa");
            assertEquals(VOICE_COUNT, line.getAudios().size(), "lời thoại id=" + line.getContentItemId() + " phải có " + VOICE_COUNT + " audio");
        }
    }

    /**
     * Chủ đề học (tag SITUATION). Test không có principal → hành vi LEARNER (chỉ bài PUBLISHED trong
     * textbook PUBLISHED). Trạng thái duyệt là DỮ LIỆU SỐNG (admin publish/unpublish được) nên số bài
     * được đối chiếu bằng SQL native độc lập với service, không hardcode.
     * Baseline seed để tham khảo: DAILY 50 bài (learner thấy 20), TRAVEL 19 (learner thấy 7), IT 0;
     * lesson_tag 119 dòng.
     */
    @Test
    void topicsComeFromTagTableWithLearnerVisibleCounts() {
        assertEquals(119L, countLessonTags(), "lesson_tag");

        List<TopicResponse> topics = dictionaryService.listTopics();
        assertEquals(3, topics.size(), "3 chủ đề SITUATION");
        Map<String, Long> countByCode = topics.stream()
                .collect(Collectors.toMap(TopicResponse::getCode, TopicResponse::getLessonCount));
        assertEquals(countLearnerVisibleLessonsByTopic("TOPIC-DAILY"), countByCode.get("TOPIC-DAILY"), "TOPIC-DAILY");
        assertEquals(countLearnerVisibleLessonsByTopic("TOPIC-TRAVEL"), countByCode.get("TOPIC-TRAVEL"), "TOPIC-TRAVEL");
        assertEquals(0L, countByCode.get("TOPIC-IT"), "TOPIC-IT chưa có bài nhưng vẫn có mặt");
        assertTrue(countByCode.get("TOPIC-DAILY") > 0, "phải có ít nhất một bài DAILY được công bố để test có ý nghĩa");
    }

    /** Số bài learner được xem của một chủ đề, đếm bằng SQL native (độc lập với JPQL của service). */
    private long countLearnerVisibleLessonsByTopic(String tagCode) {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            Number n = (Number) em.createNativeQuery("""
                    select count(*) from lesson_tag lt
                      join tag tg on tg.id = lt.tag_id
                      join lesson l on l.id = lt.lesson_id
                      join unit u on u.id = l.unit_id
                      join textbook t on t.id = u.textbook_id
                    where tg.code = ?1 and l.status = 'PUBLISHED' and t.status = 'PUBLISHED'
                    """).setParameter(1, tagCode).getSingleResult();
            return n.longValue();
        } finally {
            em.close();
        }
    }

    /** Chủ đề TỒN TẠI mà 0 bài (IT) phải trả về 200 với lessons rỗng — không được nhầm sang NOT_FOUND. */
    @Test
    void existingTopicWithoutLessonsReturnsEmptyListNotNotFound() {
        TopicLessonsResponse response = dictionaryService.getTopicLessons("TOPIC-IT");

        assertEquals("TOPIC-IT", response.getTopic().getCode());
        assertEquals(0L, response.getTopic().getLessonCount());
        assertTrue(response.getLessons().isEmpty(), "IT chưa có bài → danh sách rỗng, không ném lỗi");
    }

    /** Tag không tồn tại (hoặc không phải SITUATION) mới là NOT_FOUND. */
    @Test
    void unknownTopicIsNotFound() {
        ApiException ex = assertThrows(ApiException.class, () -> dictionaryService.getTopicLessons("TOPIC-KHONG-TON-TAI"));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());

        // Tag có thật nhưng không phải chủ đề (tag_type khác) cũng không được lộ qua endpoint chủ đề.
        ApiException ex2 = assertThrows(ApiException.class, () -> dictionaryService.getTopicLessons("N5-B01"));
        assertEquals(ErrorCode.NOT_FOUND, ex2.getErrorCode());
    }

    /**
     * Bài của chủ đề với vai learner: số bài khớp SQL native độc lập, tất cả PUBLISHED, kèm ngữ cảnh giáo trình.
     * Baseline seed: 7 bài (10 bài N5 gắn TRAVEL, 7 PUBLISHED; 9 bài N4 còn DRAFT).
     */
    @Test
    void travelTopicLessonsForLearnerArePublishedWithTextbookContext() {
        TopicLessonsResponse response = dictionaryService.getTopicLessons("TOPIC-TRAVEL");

        assertEquals(countLearnerVisibleLessonsByTopic("TOPIC-TRAVEL"), response.getLessons().size(),
                "TOPIC-TRAVEL learner visible (đối chiếu SQL native)");
        assertFalse(response.getLessons().isEmpty(), "phải có ít nhất một bài TRAVEL được công bố để test có ý nghĩa");
        for (TopicLessonResponse l : response.getLessons()) {
            assertEquals("PUBLISHED", l.getStatus());
            assertNotNull(l.getTextbookId(), "bài " + l.getId() + " phải có textbookId để dựng link");
            assertNotNull(l.getJlptLevel(), "bài " + l.getId() + " phải có cấp độ giáo trình");
            assertNotNull(l.getUnitTitle(), "bài " + l.getId() + " phải có tên unit");
        }
    }

    /* ---------- [JLAS2-17] Publish / unpublish — qua MockMvc để kiểm chính @PreAuthorize ----------
     * @Transactional: mọi thay đổi status trong test được rollback, kể cả khi assert fail giữa chừng. */

    @Test
    @Transactional
    @WithMockUser(roles = "CONTENT_CREATOR")
    void contentCreatorCanPublishDraftLessonAndLearnerThenSeesIt() throws Exception {
        // Tiền đề tự dựng (rollback sau test): giáo trình N5 PUBLISHED, bài 21 DRAFT.
        mockMvc.perform(patch("/api/dictionary/textbooks/1/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PUBLISHED\"}")).andExpect(status().isOk());
        mockMvc.perform(patch("/api/dictionary/lessons/21/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DRAFT\"}")).andExpect(status().isOk());
        ApiException hidden = assertThrows(ApiException.class, () -> dictionaryService.getLessonContent(21L));
        assertEquals(ErrorCode.NOT_FOUND, hidden.getErrorCode(), "learner chưa thấy bài 21 khi còn DRAFT");

        mockMvc.perform(patch("/api/dictionary/lessons/21/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

        assertEquals("PUBLISHED", lessonRepository.findById(21L).orElseThrow().getStatus());
        // @WithMockUser không phải UserPrincipal → CurrentUser coi là learner: bài 21 giờ phải xem được.
        assertEquals(21L, dictionaryService.getLessonContent(21L).getId());
    }

    @Test
    @Transactional
    @WithMockUser(roles = "LEARNER")
    void learnerCannotChangeStatus() throws Exception {
        String before = lessonRepository.findById(21L).orElseThrow().getStatus();
        mockMvc.perform(patch("/api/dictionary/lessons/21/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isForbidden());

        assertEquals(before, lessonRepository.findById(21L).orElseThrow().getStatus(), "không được đổi gì");
    }

    /** Quyết định team: ADMIN là role riêng, KHÔNG có quyền biên soạn — 403 như learner, không được đổi gì. */
    @Test
    @Transactional
    @WithMockUser(roles = "ADMIN")
    void adminCannotChangeStatus() throws Exception {
        String before = lessonRepository.findById(21L).orElseThrow().getStatus();
        mockMvc.perform(patch("/api/dictionary/lessons/21/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isForbidden());

        assertEquals(before, lessonRepository.findById(21L).orElseThrow().getStatus(), "không được đổi gì");
    }

    @Test
    @Transactional
    @WithMockUser(roles = "CONTENT_CREATOR")
    void publishingLessonInsideDraftTextbookIsRejectedWithReason() throws Exception {
        // Tự dựng tiền đề trong transaction (rollback sau test): giáo trình N4 về DRAFT, bài 26 về DRAFT.
        mockMvc.perform(patch("/api/dictionary/textbooks/2/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DRAFT\"}")).andExpect(status().isOk());
        mockMvc.perform(patch("/api/dictionary/lessons/26/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DRAFT\"}")).andExpect(status().isOk());

        // Bài 26 thuộc giáo trình N4 (DRAFT) → 409 CONFLICT, message nêu rõ giáo trình chưa công bố.
        mockMvc.perform(patch("/api/dictionary/lessons/26/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("công bố giáo trình trước")));

        assertEquals("DRAFT", lessonRepository.findById(26L).orElseThrow().getStatus());
    }

    @Test
    @Transactional
    @WithMockUser(roles = "CONTENT_CREATOR")
    void invalidStatusIsBadRequest() throws Exception {
        mockMvc.perform(patch("/api/dictionary/lessons/21/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"XYZ\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @Transactional
    @WithMockUser(roles = "CONTENT_CREATOR")
    void unpublishingTextbookDoesNotTouchItsLessons() throws Exception {
        mockMvc.perform(patch("/api/dictionary/textbooks/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DRAFT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"));

        // Bài 1 vẫn PUBLISHED trong DB (không cascade) nhưng learner không còn xem được vì giáo trình DRAFT.
        assertEquals("PUBLISHED", lessonRepository.findById(1L).orElseThrow().getStatus());
        ApiException ex = assertThrows(ApiException.class, () -> dictionaryService.getLessonContent(1L));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    /* ---------- [S2-07 DoD] Learning Path lấy được Lesson N5/N4 — đóng vai Learning Path ----------
     * Nhánh JLAS2-28/30 lọc bằng findByKnowledgeTagName(targetLevel) → 0 bài với dữ liệu thật (knowledge_tag là
     * điểm kiến thức từng bài, không phải cấp độ). Cầu nối: findPublishedByTextbookJlptLevel(level). */

    @Test
    void noKnowledgeTagIsNamedAfterAJlptLevelSoLevelLookupMustUseTextbook() {
        // Bằng chứng cho chủ nhánh 28/30: không tồn tại tag KNOWLEDGE tên "N5"/"N4".
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            long named = em.createQuery("select count(t) from Tag t where t.name in ('N5','N4')", Long.class).getSingleResult();
            assertEquals(0L, named, "knowledge_tag là điểm kiến thức từng bài, không có tag tên N5/N4");
        } finally {
            em.close();
        }
    }

    @Test
    void learningPathCanFetchPublishedLessonsByLevelAndOpenTheirContent() {
        // Trạng thái publish là dữ liệu sống (admin bấm/seed lại): đối chiếu từng cấp với query độc lập,
        // chỉ N5 bắt buộc có bài (baseline seed: N5 PUBLISHED, N4 DRAFT).
        for (String level : List.of("N5", "N4")) {
            List<Lesson> lessons = lessonRepository.findPublishedByTextbookJlptLevel(level);
            List<Long> expected = publishedLessonIdsOfPublishedTextbookByLevel(level);
            assertEquals(expected, lessons.stream().map(Lesson::getId).toList(),
                    level + ": đúng tập bài PUBLISHED của textbook PUBLISHED, đúng thứ tự unit → bài");
            if ("N5".equals(level)) {
                assertFalse(lessons.isEmpty(), "Learning Path phải lấy được bài N5 (seed baseline)");
            }

            for (Lesson lesson : lessons) {
                assertEquals("PUBLISHED", lesson.getStatus());
                assertEquals(level, lesson.getUnit().getTextbook().getJlptLevel());
                assertEquals("PUBLISHED", lesson.getUnit().getTextbook().getStatus());
                assertNotNull(lesson.getKnowledgeTag().getCode(), "knowledge tag đã fetch join, không N+1");
                assertTrue(lesson.getKnowledgeTag().getCode().startsWith(level + "-"),
                        "mã knowledge tag theo cấp độ, ví dụ N5-B01: " + lesson.getKnowledgeTag().getCode());
                assertNotNull(lesson.getEstimatedMinutes(), "Learning Path chia ngày theo estimated_minutes");

                // Mở nội dung như learner sẽ mở từ lộ trình: đủ 5 khối FR-73 + audio.
                assertLessonHasAllContentBlocks(dictionaryService.getLessonContent(lesson.getId()));
            }
        }
    }

    /** Query độc lập (JPQL, không qua LessonRepository) để đối chiếu với findPublishedByTextbookJlptLevel. */
    private List<Long> publishedLessonIdsOfPublishedTextbookByLevel(String level) {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            return em.createQuery(
                    "select l.id from Lesson l join l.unit u join u.textbook t"
                            + " where t.jlptLevel = :level and t.status = 'PUBLISHED' and l.status = 'PUBLISHED'"
                            + " order by t.id, u.orderIndex, u.id, l.orderIndex, l.id", Long.class)
                    .setParameter("level", level).getResultList();
        } finally {
            em.close();
        }
    }

    /** Cách nhánh personalized chọn bài: textbook + tập knowledge tag code (N5-B01...) → mở nội dung. */
    @Test
    void learningPathCanSelectLessonsByKnowledgeTagCodesWithinTextbook() {
        // Query có fetch join knowledgeTag (đọc code ngoài transaction không bị LazyInitialization).
        List<Lesson> published = lessonRepository.findPublishedByTextbookJlptLevel("N5");
        assertFalse(published.isEmpty());
        Set<String> wanted = published.stream().limit(3)
                .map(l -> l.getKnowledgeTag().getCode()).collect(Collectors.toSet());

        List<Lesson> selected = published.stream()
                .filter(l -> wanted.contains(l.getKnowledgeTag().getCode())).toList();
        assertEquals(3, selected.size(), "chọn đúng 3 bài theo mã knowledge tag");
        for (Lesson lesson : selected) {
            assertLessonHasAllContentBlocks(dictionaryService.getLessonContent(lesson.getId()));
        }
    }

    /** Giáo trình bảng chữ cái (jlpt_level NULL, skill READING): chỉ có mục đọc kana + romaji, không audio, không 5 khối kia. */
    @Test
    void kanaLessonExposesReadingItemsOnlyAndNoOtherBlocks() {
        List<Lesson> kanaLessons = lessonRepository.findByTextbookIdAndStatus(kanaTextbookId(), "PUBLISHED");
        assertEquals(10, kanaLessons.size(), "bảng chữ cái: 10 bài PUBLISHED");

        int totalKana = 0;
        for (Lesson l : kanaLessons) {
            LessonContentResponse c = dictionaryService.getLessonContent(l.getId());
            assertEquals("READING", c.getSkill());
            assertFalse(c.getReadingItems().isEmpty(), "bài " + l.getId() + " phải có mục đọc kana");
            for (ExampleSentenceResponse item : c.getReadingItems()) {
                assertNotNull(item.getTextJa(), "kana");
                assertNotNull(item.getTextVi(), "romaji");
                assertTrue(item.getAudios().isEmpty(), "kana không có audio thu sẵn (đọc bằng TTS)");
            }
            totalKana += c.getReadingItems().size();
            assertTrue(c.getKanjis().isEmpty() && c.getVocabularies().isEmpty() && c.getExampleSentences().isEmpty()
                    && c.getGrammarPoints().isEmpty() && c.getDialogueLines().isEmpty(), "bài kana không có 5 khối kia");
        }
        assertEquals(208, totalKana, "208 chữ kana");

        // Bài N5 vẫn có mục READING (1 đoạn chú thích) nhưng skill != READING → giao diện không hiện tab Bảng chữ.
        LessonContentResponse n5 = dictionaryService.getLessonContent(LESSON_1);
        assertEquals(1, n5.getReadingItems().size());
        assertEquals("GRAMMAR", n5.getSkill());
    }

    /** Id giáo trình bảng chữ cái lấy theo dữ liệu (jlpt_level NULL), không hardcode id 3. */
    private long kanaTextbookId() {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            return em.createQuery("select t.id from Textbook t where t.jlptLevel is null", Long.class).getSingleResult();
        } finally {
            em.close();
        }
    }

    private static void assertLessonHasAllContentBlocks(LessonContentResponse c) {
        String id = "bài " + c.getId();
        assertFalse(c.getKanjis().isEmpty(), id + " thiếu khối Kanji");
        assertFalse(c.getVocabularies().isEmpty(), id + " thiếu khối Từ vựng");
        assertFalse(c.getExampleSentences().isEmpty(), id + " thiếu khối Câu ví dụ");
        assertFalse(c.getGrammarPoints().isEmpty(), id + " thiếu khối Ngữ pháp");
        assertFalse(c.getDialogueLines().isEmpty(), id + " thiếu khối Hội thoại");
        assertTrue(c.getVocabularies().stream().allMatch(v -> !v.getAudios().isEmpty()), id + " có từ vựng thiếu audio");
    }

    private long countLessonTags() {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            return em.createQuery("select count(lt) from LessonTag lt", Long.class).getSingleResult();
        } finally {
            em.close();
        }
    }

    /** Đếm radical bằng JPQL để không phải tạo RadicalRepository chỉ cho một count. */
    private long countRadicals() {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            return em.createQuery("select count(r) from Radical r", Long.class).getSingleResult();
        } finally {
            em.close();
        }
    }

    @Test
    void getLessonContentRunsFixedNumberOfQueries() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        dictionaryService.getLessonContent(LESSON_1);

        // lesson, từ vựng, audio từ vựng, từ↔Kanji, cách đọc Kanji, ngữ pháp, vai, lời thoại, cụm tách nghĩa, câu ví dụ, mục đọc (READING), audio content_item
        assertEquals(12L, statistics.getPrepareStatementCount(), "số câu SQL cho một bài phải cố định (không N+1)");
    }
}
