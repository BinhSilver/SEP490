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
import com.jlas.dictionary.entity.GrammarPoint;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.LessonSection;
import com.jlas.dictionary.repository.ContentAudioRepository;
import com.jlas.dictionary.repository.ContentItemRepository;
import com.jlas.dictionary.repository.ContentVocabularyRepository;
import com.jlas.dictionary.repository.DialogueRoleRepository;
import com.jlas.dictionary.repository.DialogueLineChunkRepository;
import com.jlas.dictionary.repository.KanjiReadingRepository;
import com.jlas.dictionary.repository.KanjiRepository;
import com.jlas.dictionary.repository.GrammarPointRepository;
import com.jlas.dictionary.repository.LessonRepository;
import com.jlas.dictionary.repository.LessonSectionRepository;
import com.jlas.dictionary.repository.UnitRepository;
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
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test [JLAS-W0070 / W0080 / JLAS2-17] chạy lên MySQL local đã seed bằng sql/seeds/seed_data_jlas.sql
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
    @Autowired private UnitRepository unitRepository;
    @Autowired private LessonSectionRepository lessonSectionRepository;
    @Autowired private GrammarPointRepository grammarPointRepository;
    @Autowired private ContentVocabularyRepository contentVocabularyRepository;
    @Autowired private DialogueRoleRepository dialogueRoleRepository;
    @Autowired private MockMvc mockMvc;

    @Test
    void seedRowCountsMatchDigitizedContent() {
        assertEquals(9031L, vocabularyEntryRepository.count(), "vocabulary_entry");
        assertEquals(5057L, contentAudioRepository.count(), "content_audio");
        assertEquals(5636L, dialogueLineChunkRepository.count(), "dialogue_line_chunk");
        assertEquals(5106L, contentItemRepository.count(), "content_item (3284 câu ngữ pháp + 1435 lời hội thoại + 387 mục đọc = 5106)");
        assertEquals(2540L, kanjiRepository.count(), "kanji");
        assertEquals(8068L, kanjiReadingRepository.count(), "kanji_reading");
        assertEquals(200L, countRadicals(), "radical");
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
            assertEquals(3284L, total);
            assertEquals(3279L, linked, "5 câu không gắn mẫu (đều ở bài 1)");
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
     * lesson_tag qua tag SITUATION 119 dòng (tổng lesson_tag 564, phần còn lại là tag KNOWLEDGE).
     */
    @Test
    void topicsComeFromTagTableWithLearnerVisibleCounts() {
        assertEquals(119L, countSituationLessonTags(), "lesson_tag qua tag SITUATION");

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

    /**
     * Learner không sửa được nội dung — quét MỌI NHÓM endpoint ghi, mỗi nhóm một đại diện, thay vì chỉ
     * PATCH status. Mục đích: ai thêm endpoint ghi mà quên @PreAuthorize thì test này đỏ.
     * Dùng 403 (không phải 401) vì @WithMockUser đã đăng nhập, chỉ thiếu vai.
     */
    @Test
    @Transactional
    @WithMockUser(roles = "LEARNER")
    void learnerIsForbiddenOnEveryKindOfWriteEndpoint() throws Exception {
        long unitId = lessonRepository.findById(LESSON_1).orElseThrow().getUnit().getId();
        long textbookId = lessonRepository.findById(LESSON_1).orElseThrow().getUnit().getTextbook().getId();
        String lessonBody = "{\"knowledgeTagId\":1,\"title\":\"x\",\"lessonType\":\"CURRICULUM\",\"skill\":\"VOCABULARY\"}";
        String titleBody = "{\"title\":\"x\"}";
        String orderBody = "{\"orderedIds\":[1]}";

        // POST — tạo
        mockMvc.perform(post("/api/dictionary/textbooks").contentType(MediaType.APPLICATION_JSON).content(titleBody))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/dictionary/textbooks/" + textbookId + "/units")
                        .contentType(MediaType.APPLICATION_JSON).content(titleBody))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/dictionary/units/" + unitId + "/lessons")
                        .contentType(MediaType.APPLICATION_JSON).content(lessonBody))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/dictionary/manage/tags").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"X\",\"name\":\"X\",\"tagType\":\"KNOWLEDGE\"}"))
                .andExpect(status().isForbidden());

        // PUT — sửa
        mockMvc.perform(put("/api/dictionary/textbooks/" + textbookId)
                        .contentType(MediaType.APPLICATION_JSON).content(titleBody))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/dictionary/units/" + unitId)
                        .contentType(MediaType.APPLICATION_JSON).content(titleBody))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/dictionary/lessons/" + LESSON_1)
                        .contentType(MediaType.APPLICATION_JSON).content(lessonBody))
                .andExpect(status().isForbidden());

        // PUT — sắp xếp
        mockMvc.perform(put("/api/dictionary/textbooks/" + textbookId + "/units/order")
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/dictionary/units/" + unitId + "/lessons/order")
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody))
                .andExpect(status().isForbidden());

        // DELETE — xoá cứng
        mockMvc.perform(delete("/api/dictionary/units/" + unitId)).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/dictionary/lessons/" + LESSON_1)).andExpect(status().isForbidden());

        // Phần nội dung trong bài (lesson_section) — cùng một lưới quyền
        mockMvc.perform(get("/api/dictionary/manage/lessons/" + LESSON_1 + "/sections"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/dictionary/lessons/" + LESSON_1 + "/sections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"sectionType\":\"EXPLANATION\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/dictionary/lessons/" + LESSON_1 + "/sections/order")
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody))
                .andExpect(status().isForbidden());

        // GET của màn hình quản lý cũng phải chặn: learner không được thấy mục đã lưu trữ.
        mockMvc.perform(get("/api/dictionary/manage/textbooks")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/dictionary/manage/tags").param("type", "KNOWLEDGE"))
                .andExpect(status().isForbidden());

        assertTrue(unitRepository.findById(unitId).isPresent(), "không có gì bị xoá");
        assertEquals("PUBLISHED", lessonRepository.findById(LESSON_1).orElseThrow().getStatus(), "không có gì bị đổi");
    }

    /** System Admin không có quyền biên soạn (chỉ CONTENT_CREATOR) — 403 như learner, không được đổi gì. */
    @Test
    @Transactional
    @WithMockUser(roles = "ADMIN")
    void systemAdminCannotChangeStatus() throws Exception {
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

    /**
     * Xoá mềm: giáo trình ARCHIVED phải biến mất khỏi MỌI đường đọc của learner, kể cả khi bài bên trong
     * vẫn còn PUBLISHED (lưu trữ giáo trình cố ý không cascade xuống bài).
     * <p>
     * @WithMockUser không phải UserPrincipal nên CurrentUser coi người gọi là learner khi gọi thẳng service —
     * đúng góc nhìn cần kiểm; riêng PATCH status đi qua @PreAuthorize nên vẫn dùng được vai CONTENT_CREATOR.
     */
    @Test
    @Transactional
    @WithMockUser(roles = "CONTENT_CREATOR")
    void archivedTextbookDisappearsFromEveryLearnerReadPath() throws Exception {
        mockMvc.perform(patch("/api/dictionary/textbooks/1/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PUBLISHED\"}")).andExpect(status().isOk());
        mockMvc.perform(patch("/api/dictionary/lessons/21/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PUBLISHED\"}")).andExpect(status().isOk());
        assertEquals(21L, dictionaryService.getLessonContent(21L).getId(), "tiền đề: learner đang xem được bài 21");

        mockMvc.perform(patch("/api/dictionary/textbooks/1/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ARCHIVED\"}")).andExpect(status().isOk());

        assertTrue(dictionaryService.listTextbooks().stream().noneMatch(t -> t.getId().equals(1L)),
                "giáo trình đã lưu trữ không được nằm trong danh sách");
        assertEquals(ErrorCode.NOT_FOUND,
                assertThrows(ApiException.class, () -> dictionaryService.getTextbookUnits(1L)).getErrorCode(),
                "mở giáo trình đã lưu trữ phải là 404");

        // Điểm mấu chốt: bài vẫn PUBLISHED trong DB, nhưng gõ thẳng URL cũng không xem được.
        assertEquals("PUBLISHED", lessonRepository.findById(21L).orElseThrow().getStatus());
        assertEquals(ErrorCode.NOT_FOUND,
                assertThrows(ApiException.class, () -> dictionaryService.getLessonContent(21L)).getErrorCode(),
                "bài PUBLISHED trong giáo trình đã lưu trữ vẫn phải 404 với learner");
    }

    /** Không entity mồ côi: không thêm được phần vào giáo trình đã lưu trữ, không thêm được bài vào phần không tồn tại. */
    @Test
    @Transactional
    @WithMockUser(roles = "CONTENT_CREATOR")
    void cannotAttachContentToArchivedOrMissingParent() throws Exception {
        mockMvc.perform(patch("/api/dictionary/textbooks/1/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ARCHIVED\"}")).andExpect(status().isOk());

        mockMvc.perform(post("/api/dictionary/textbooks/1/units").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Phần thêm vào giáo trình đã lưu trữ\"}"))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/dictionary/units/99999999/lessons").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"knowledgeTagId\":1,\"title\":\"Bài mồ côi\","
                                + "\"lessonType\":\"CURRICULUM\",\"skill\":\"VOCABULARY\"}"))
                .andExpect(status().isNotFound());
    }

    /**
     * Giáo trình đã lưu trữ thì khoá MỌI đường sửa nội dung, không chỉ đường tạo mới: sửa giáo trình,
     * sửa bài, và cả hai đường đổi thứ tự. Giao diện đã tắt nút, nhưng gọi thẳng API cũng phải bị chặn.
     * Đường khôi phục (PATCH status) vẫn phải chạy được — kiểm ở cuối test.
     */
    @Test
    @Transactional
    @WithMockUser(roles = "CONTENT_CREATOR")
    void archivedTextbookLocksEveryEditPathButNotTheRestorePath() throws Exception {
        long unitId = lessonRepository.findById(LESSON_1).orElseThrow().getUnit().getId();
        long textbookId = lessonRepository.findById(LESSON_1).orElseThrow().getUnit().getTextbook().getId();
        mockMvc.perform(patch("/api/dictionary/textbooks/" + textbookId + "/status")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ARCHIVED\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/dictionary/textbooks/" + textbookId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Tên mới\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(put("/api/dictionary/lessons/" + LESSON_1).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"knowledgeTagId\":1,\"title\":\"x\","
                                + "\"lessonType\":\"CURRICULUM\",\"skill\":\"VOCABULARY\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(put("/api/dictionary/textbooks/" + textbookId + "/units/order")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"orderedIds\":[" + unitId + "]}"))
                .andExpect(status().isConflict());
        mockMvc.perform(put("/api/dictionary/units/" + unitId + "/lessons/order")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"orderedIds\":[" + LESSON_1 + "]}"))
                .andExpect(status().isConflict());

        // Lối ra vẫn mở: khôi phục đi qua PATCH status, không qua updateTextbook.
        mockMvc.perform(patch("/api/dictionary/textbooks/" + textbookId + "/status")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DRAFT\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/dictionary/textbooks/" + textbookId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Tên mới\"}"))
                .andExpect(status().isOk());
    }

    /**
     * Xoá cứng phần còn bài phải bị chặn, và thông báo phải nói rõ phải làm gì tiếp — giao diện hiện
     * nguyên văn message này ở dải lỗi cố định nên nội dung của nó là một phần hợp đồng, không chỉ là chữ.
     */
    @Test
    @Transactional
    @WithMockUser(roles = "CONTENT_CREATOR")
    void deletingUnitThatStillHasLessonsIsRejectedWithAnActionableMessage() throws Exception {
        long unitId = lessonRepository.findById(1L).orElseThrow().getUnit().getId();

        mockMvc.perform(delete("/api/dictionary/units/" + unitId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("bài học")))
                .andExpect(jsonPath("$.message", containsString("lưu trữ")));

        assertTrue(unitRepository.findById(unitId).isPresent(), "phần không được xoá");
    }

    /**
     * Xoá cứng bài học chỉ dành cho bài tạo nhầm. Bài 1 đã công bố và đầy nội dung nên phải bị chặn —
     * mọi FK trỏ tới lesson đều CASCADE, xoá nhầm là mất cả tiến độ học viên lẫn nội dung biên soạn.
     */
    @Test
    @Transactional
    @WithMockUser(roles = "CONTENT_CREATOR")
    void deletingALessonThatIsPublishedOrHasContentIsRejected() throws Exception {
        mockMvc.perform(delete("/api/dictionary/lessons/" + LESSON_1))
                .andExpect(status().isConflict());
        assertTrue(lessonRepository.findById(LESSON_1).isPresent(), "bài không được xoá");

        // Rút về nháp rồi thử lại: vẫn phải chặn, lần này vì bài đã có nội dung biên soạn.
        mockMvc.perform(patch("/api/dictionary/lessons/" + LESSON_1 + "/status")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DRAFT\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/dictionary/lessons/" + LESSON_1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("nội dung")));
        assertTrue(lessonRepository.findById(LESSON_1).isPresent(), "bài vẫn không được xoá");
    }

    /**
     * Đảo thứ tự bài trong một phần. Đây là chỗ dễ vỡ nhất của tính năng: uk_lesson_unit_order là UNIQUE
     * (unit_id, order_index) nên gán thẳng số mới sẽ đụng số bản ghi khác đang giữ; service phải đi hai pha
     * (đẩy lên dải tạm từ max+1 rồi mới đánh số thật). Test đảo ngược CẢ danh sách để chắc chắn mọi bản ghi
     * đều đổi chỗ, không phải trường hợp may mắn không va nhau.
     */
    @Test
    @Transactional
    @WithMockUser(roles = "CONTENT_CREATOR")
    void reorderingLessonsRewritesOrderIndexFromOneWithoutGaps() throws Exception {
        long unitId = lessonRepository.findById(LESSON_1).orElseThrow().getUnit().getId();
        List<Long> before = lessonRepository.findByUnitIdOrderByOrderIndexAscIdAsc(unitId)
                .stream().map(Lesson::getId).toList();
        assertTrue(before.size() >= 2, "cần ít nhất 2 bài để đảo thứ tự");

        List<Long> reversed = new java.util.ArrayList<>(before);
        java.util.Collections.reverse(reversed);
        mockMvc.perform(put("/api/dictionary/units/" + unitId + "/lessons/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderedIds\":" + reversed + "}"))
                .andExpect(status().isOk());

        List<Lesson> after = lessonRepository.findByUnitIdOrderByOrderIndexAscIdAsc(unitId);
        assertEquals(reversed, after.stream().map(Lesson::getId).toList(), "thứ tự đọc lại phải đúng như gửi lên");
        // order_index bắt đầu từ 1 và liên tục: khớp dữ liệu seed (1-based), không để lại khoảng trống.
        for (int i = 0; i < after.size(); i++) {
            assertEquals(i + 1, after.get(i).getOrderIndex(), "order_index phải là " + (i + 1));
        }
    }

    /** Danh sách id gửi lên phải khớp TUYỆT ĐỐI tập id hiện có; thiếu/thừa/trùng/lạ đều là 400. */
    @Test
    @Transactional
    @WithMockUser(roles = "CONTENT_CREATOR")
    void reorderingWithAMismatchedIdListIsBadRequest() throws Exception {
        long unitId = lessonRepository.findById(LESSON_1).orElseThrow().getUnit().getId();
        List<Long> ids = lessonRepository.findByUnitIdOrderByOrderIndexAscIdAsc(unitId)
                .stream().map(Lesson::getId).toList();
        Integer firstOrder = lessonRepository.findById(ids.get(0)).orElseThrow().getOrderIndex();

        List<Long> missing = ids.subList(0, ids.size() - 1);                 // thiếu một id
        List<Long> duplicated = List.of(ids.get(0), ids.get(0));             // id lặp
        List<Long> unknown = List.of(ids.get(0), 99999999L);                 // id lạ
        List<Long> extra = new java.util.ArrayList<>(ids);
        extra.add(99999999L);                                                // thừa id

        for (List<Long> bad : List.of(missing, duplicated, unknown, extra)) {
            mockMvc.perform(put("/api/dictionary/units/" + unitId + "/lessons/order")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orderedIds\":" + bad + "}"))
                    .andExpect(status().isBadRequest());
        }
        assertEquals(firstOrder, lessonRepository.findById(ids.get(0)).orElseThrow().getOrderIndex(),
                "danh sách sai thì không được đổi gì");
    }

    /**
     * [GĐ0] Vòng đời phần nội dung trong bài: tạo vào cuối, sửa, đổi thứ tự bằng ContentOrdering dùng chung,
     * và chỉ xoá cứng được khi đã rỗng mục nội dung (fk_content_item_section là ON DELETE CASCADE).
     */
    @Test
    @Transactional
    @WithMockUser(roles = "CONTENT_CREATOR")
    void lessonSectionCrudAndOrderingWorkEndToEnd() throws Exception {
        // Bài 1 đã có sẵn phần nội dung; thêm hai phần mới vào cuối.
        String first = mockMvc.perform(post("/api/dictionary/lessons/" + LESSON_1 + "/sections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Phần A\",\"sectionType\":\"EXPLANATION\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.contentItemCount").value(0))
                .andReturn().getResponse().getContentAsString();
        long sectionA = Long.parseLong(first.replaceAll("(?s).*\"id\"\\s*:\\s*(\\d+).*", "$1"));

        String second = mockMvc.perform(post("/api/dictionary/lessons/" + LESSON_1 + "/sections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Phần B\",\"sectionType\":\"EXERCISE\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long sectionB = Long.parseLong(second.replaceAll("(?s).*\"id\"\\s*:\\s*(\\d+).*", "$1"));

        mockMvc.perform(put("/api/dictionary/sections/" + sectionA).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Phần A đã sửa\",\"sectionType\":\"EXAMPLE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Phần A đã sửa"))
                .andExpect(jsonPath("$.data.sectionType").value("EXAMPLE"));

        // Đảo ngược toàn bộ thứ tự rồi kiểm order_index chạy 1..n liên tục.
        List<Long> ids = lessonSectionRepository.findByLessonIdOrderByOrderIndexAscIdAsc(LESSON_1)
                .stream().map(LessonSection::getId).toList();
        List<Long> reversed = new java.util.ArrayList<>(ids);
        java.util.Collections.reverse(reversed);
        mockMvc.perform(put("/api/dictionary/lessons/" + LESSON_1 + "/sections/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderedIds\":" + reversed + "}"))
                .andExpect(status().isOk());

        List<LessonSection> after = lessonSectionRepository.findByLessonIdOrderByOrderIndexAscIdAsc(LESSON_1);
        assertEquals(reversed, after.stream().map(LessonSection::getId).toList(), "thứ tự phải đúng như gửi lên");
        for (int i = 0; i < after.size(); i++) {
            assertEquals(i + 1, after.get(i).getOrderIndex(), "order_index phải là " + (i + 1));
        }

        // Phần rỗng xoá được; phần đã có mục nội dung thì bị chặn.
        mockMvc.perform(delete("/api/dictionary/sections/" + sectionB)).andExpect(status().isOk());
        long sectionWithContent = lessonSectionRepository.findByLessonIdOrderByOrderIndexAscIdAsc(LESSON_1).stream()
                .filter(s -> lessonSectionRepository.countContentItemsBySectionId(s.getId()) > 0)
                .findFirst().orElseThrow(() -> new AssertionError("bài 1 phải có ít nhất một phần còn nội dung"))
                .getId();
        mockMvc.perform(delete("/api/dictionary/sections/" + sectionWithContent))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("mục")));
        assertTrue(lessonSectionRepository.findById(sectionWithContent).isPresent(), "phần còn nội dung không bị xoá");
        assertTrue(lessonSectionRepository.findById(sectionA).isPresent(), "phần A vẫn còn");
    }

    /**
     * [GĐ1] Vòng đời mẫu ngữ pháp và câu ví dụ. Bài 1 đã có sẵn 8 mẫu nên test thêm mẫu mới vào cuối,
     * gắn ví dụ, rồi kiểm chốt chặn xoá.
     */
    @Test
    @Transactional
    @WithMockUser(roles = "CONTENT_CREATOR")
    void grammarPointAndExampleCrudWorkEndToEnd() throws Exception {
        String created = mockMvc.perform(post("/api/dictionary/lessons/" + LESSON_1 + "/grammar-points")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pattern\":\"N です\",\"meaning\":\"Là N\",\"note\":\"ghi chú\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.exampleCount").value(0))
                .andReturn().getResponse().getContentAsString();
        long pointId = Long.parseLong(created.replaceAll("(?s).*\"id\"\\s*:\\s*(\\d+).*", "$1"));

        mockMvc.perform(put("/api/dictionary/grammar-points/" + pointId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pattern\":\"N じゃ ありません\",\"meaning\":\"Không phải N\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pattern").value("N じゃ ありません"));

        // Không chỉ định sectionId: service phải tự chọn/tạo phần loại EXAMPLE của bài.
        String example = mockMvc.perform(post("/api/dictionary/grammar-points/" + pointId + "/examples")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Ví dụ 1\",\"textJa\":\"わたしは 学生です。\",\"textVi\":\"Tôi là sinh viên.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.grammarPointId").value(pointId))
                .andExpect(jsonPath("$.data.sectionId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        long exampleId = Long.parseLong(example.replaceAll("(?s).*\"contentItemId\"\\s*:\\s*(\\d+).*", "$1"));
        long sectionId = Long.parseLong(example.replaceAll("(?s).*\"sectionId\"\\s*:\\s*(\\d+).*", "$1"));
        assertEquals("EXAMPLE", lessonSectionRepository.findById(sectionId).orElseThrow().getSectionType(),
                "phần chứa câu ví dụ phải là loại EXAMPLE");

        mockMvc.perform(get("/api/dictionary/manage/grammar-points/" + pointId + "/examples"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        // Mẫu còn câu ví dụ thì không xoá được — fk_content_item_grammar là SET NULL nên xoá thẳng sẽ
        // khiến câu ví dụ mất liên kết trong im lặng.
        mockMvc.perform(delete("/api/dictionary/grammar-points/" + pointId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("câu ví dụ")));
        assertTrue(grammarPointRepository.findById(pointId).isPresent(), "mẫu không bị xoá");

        // Xoá ví dụ trước rồi mới xoá được mẫu.
        mockMvc.perform(delete("/api/dictionary/examples/" + exampleId)).andExpect(status().isOk());
        mockMvc.perform(delete("/api/dictionary/grammar-points/" + pointId)).andExpect(status().isOk());
        assertTrue(grammarPointRepository.findById(pointId).isEmpty(), "mẫu đã xoá được sau khi hết ví dụ");
    }

    /** [GĐ1] Câu ví dụ không được gắn vào phần nội dung của bài khác — đó là dữ liệu mồ côi. */
    @Test
    @Transactional
    @WithMockUser(roles = "CONTENT_CREATOR")
    void exampleCannotBeAttachedToASectionOfAnotherLesson() throws Exception {
        long pointId = grammarPointRepository.findByLessonIdOrderByOrderIndexAscIdAsc(LESSON_1)
                .stream().findFirst().orElseThrow().getId();
        long foreignSectionId = lessonSectionRepository.findAll().stream()
                .filter(s -> !s.getLesson().getId().equals(LESSON_1))
                .findFirst().orElseThrow().getId();

        mockMvc.perform(post("/api/dictionary/grammar-points/" + pointId + "/examples")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"textJa\":\"テスト\",\"sectionId\":" + foreignSectionId + "}"))
                .andExpect(status().isBadRequest());
    }

    /** [GĐ1] Thứ tự mẫu ngữ pháp dùng chung ContentOrdering: đảo xong order_index phải là 1..n liên tục. */
    @Test
    @Transactional
    @WithMockUser(roles = "CONTENT_CREATOR")
    void reorderingGrammarPointsRewritesOrderIndexFromOne() throws Exception {
        List<Long> ids = grammarPointRepository.findByLessonIdOrderByOrderIndexAscIdAsc(LESSON_1)
                .stream().map(GrammarPoint::getId).toList();
        assertTrue(ids.size() >= 2, "bài 1 phải có ít nhất 2 mẫu ngữ pháp");
        List<Long> reversed = new java.util.ArrayList<>(ids);
        java.util.Collections.reverse(reversed);

        mockMvc.perform(put("/api/dictionary/lessons/" + LESSON_1 + "/grammar-points/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderedIds\":" + reversed + "}"))
                .andExpect(status().isOk());

        List<GrammarPoint> after = grammarPointRepository.findByLessonIdOrderByOrderIndexAscIdAsc(LESSON_1);
        assertEquals(reversed, after.stream().map(GrammarPoint::getId).toList(), "thứ tự đúng như gửi lên");
        for (int i = 0; i < after.size(); i++) {
            assertEquals(i + 1, after.get(i).getOrderIndex(), "order_index phải là " + (i + 1));
        }
    }

    /**
     * [GĐ2] Tìm từ có phân trang. 9.031 từ nên không bao giờ trả hết một lần; và tìm trên cột thường
     * nên gõ thiếu dấu đục vẫn ra (collation utf8mb4_unicode_ci bỏ qua dakuten).
     */
    @Test
    @WithMockUser(roles = "CONTENT_CREATOR")
    void vocabularySearchIsPagedAndToleratesMissingDakuten() throws Exception {
        mockMvc.perform(get("/api/dictionary/manage/vocabulary/search").param("q", "").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(5))
                .andExpect(jsonPath("$.data.totalElements").value(9031))
                .andExpect(jsonPath("$.data.first").value(true));

        // size vượt trần bị kẹp lại, không cho kéo cả bảng về bằng một request.
        mockMvc.perform(get("/api/dictionary/manage/vocabulary/search").param("q", "").param("size", "99999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100));

        // "ハス" (thiếu dakuten) vẫn phải tìm ra "バス" nhờ collation của cột thường.
        String lenient = mockMvc.perform(get("/api/dictionary/manage/vocabulary/search").param("q", "ハス"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(lenient.contains("\"totalElements\""), "phải trả về dạng phân trang");
    }

    /**
     * [GĐ2] Gắn và gỡ từ khỏi bài. Điểm quan trọng nhất: gỡ từ CHỈ xoá dòng nối, vocabulary_entry phải
     * còn nguyên — nó dùng chung cho mọi bài và cho tra cứu từ điển.
     */
    @Test
    @Transactional
    @WithMockUser(roles = "CONTENT_CREATOR")
    void attachingAndDetachingVocabularyNeverTouchesTheSharedEntry() throws Exception {
        long vocabularyCountBefore = vocabularyEntryRepository.count();
        // Chọn một từ chưa nằm trong bài 1.
        List<Long> inLesson = contentVocabularyRepository.findVocabularyIdsByLessonId(LESSON_1);
        long freeVocabId = vocabularyEntryRepository.findAll().stream()
                .map(v -> v.getId())
                .filter(id -> !inLesson.contains(id))
                .findFirst().orElseThrow();

        mockMvc.perform(post("/api/dictionary/lessons/" + LESSON_1 + "/vocabulary")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vocabularyId\":" + freeVocabId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.attachedToLesson").value(true));

        // Gắn lại lần nữa phải bị chặn.
        mockMvc.perform(post("/api/dictionary/lessons/" + LESSON_1 + "/vocabulary")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vocabularyId\":" + freeVocabId + "}"))
                .andExpect(status().isConflict());

        assertTrue(contentVocabularyRepository.findVocabularyIdsByLessonId(LESSON_1).contains(freeVocabId),
                "từ phải nằm trong bài sau khi gắn");

        mockMvc.perform(delete("/api/dictionary/lessons/" + LESSON_1 + "/vocabulary/" + freeVocabId))
                .andExpect(status().isOk());

        assertFalse(contentVocabularyRepository.findVocabularyIdsByLessonId(LESSON_1).contains(freeVocabId),
                "từ đã được gỡ khỏi bài");
        assertTrue(vocabularyEntryRepository.findById(freeVocabId).isPresent(),
                "từ vẫn còn trong từ điển — gỡ khỏi bài không được xoá vocabulary_entry");
        assertEquals(vocabularyCountBefore, vocabularyEntryRepository.count(),
                "tổng số từ trong từ điển không đổi");
    }

    /** [GĐ2] Gỡ một từ không nằm trong bài là 404, không im lặng bỏ qua. */
    @Test
    @Transactional
    @WithMockUser(roles = "CONTENT_CREATOR")
    void detachingAVocabularyNotInTheLessonIsNotFound() throws Exception {
        List<Long> inLesson = contentVocabularyRepository.findVocabularyIdsByLessonId(LESSON_1);
        long freeVocabId = vocabularyEntryRepository.findAll().stream()
                .map(v -> v.getId())
                .filter(id -> !inLesson.contains(id))
                .findFirst().orElseThrow();

        mockMvc.perform(delete("/api/dictionary/lessons/" + LESSON_1 + "/vocabulary/" + freeVocabId))
                .andExpect(status().isNotFound());
    }

    /**
     * [GĐ4] BẤT BIẾN của hội thoại, quét TOÀN BỘ dữ liệu hiện có: ghép các cụm của mỗi lời phải ra đúng
     * nguyên câu trong content_item.content.
     * <p>
     * Vì sao đây là bất biến chứ không phải chuyện làm đẹp: textbook-lesson.js dựng lại lời thoại bằng
     * {@code chunks.map(chunkSpan).join('')} và chỉ dùng content khi KHÔNG có cụm nào. Lệch một ký tự là
     * học viên đọc một câu còn audio đọc câu khác, không có lỗi nào hiện ra. Test này khoá cả dữ liệu
     * seed lẫn mọi dữ liệu do người biên soạn nhập sau này.
     */
    @Test
    void everyDialogueLineIsExactlyRebuiltFromItsChunks() {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createQuery("""
                    select ci.id, ci.content, c.surface
                    from DialogueLineChunk c join c.contentItem ci
                    order by ci.id, c.orderIndex
                    """).getResultList();
            assertFalse(rows.isEmpty(), "phải có dữ liệu cụm để kiểm");

            Map<Long, StringBuilder> joined = new java.util.LinkedHashMap<>();
            Map<Long, String> expected = new java.util.LinkedHashMap<>();
            for (Object[] row : rows) {
                Long itemId = (Long) row[0];
                expected.putIfAbsent(itemId, (String) row[1]);
                joined.computeIfAbsent(itemId, k -> new StringBuilder()).append((String) row[2]);
            }

            List<String> mismatches = new java.util.ArrayList<>();
            expected.forEach((itemId, content) -> {
                String rebuilt = joined.get(itemId).toString();
                if (!rebuilt.equals(content)) {
                    mismatches.add("content_item " + itemId + ": lưu [" + content + "] ghép ra [" + rebuilt + "]");
                }
            });
            assertTrue(mismatches.isEmpty(),
                    mismatches.size() + "/" + expected.size() + " lời thoại ghép cụm không khớp nguyên câu:\n"
                            + String.join("\n", mismatches.subList(0, Math.min(5, mismatches.size()))));
        } finally {
            em.close();
        }
    }

    /** [GĐ4] Lớp chặn ở service: gửi cụm ghép không ra đúng câu phải bị 400, kèm cả hai chuỗi. */
    @Test
    @Transactional
    @WithMockUser(roles = "CONTENT_CREATOR")
    void dialogueLineWithChunksThatDoNotRebuildTheSentenceIsRejected() throws Exception {
        String scene = mockMvc.perform(post("/api/dictionary/lessons/" + LESSON_1 + "/dialogue-scenes")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Cảnh thử\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long sectionId = Long.parseLong(scene.replaceAll("(?s).*\"sectionId\"\\s*:\\s*(\\d+).*", "$1"));

        // Thiếu một ký tự so với câu gốc → phải bị chặn.
        mockMvc.perform(post("/api/dictionary/dialogue-scenes/" + sectionId + "/lines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Nguyễn\",\"textJa\":\"はじめまして。\","
                                + "\"chunks\":[{\"surface\":\"はじめまして\",\"meaningVi\":\"rất vui\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("không ra đúng lời thoại")));

        // Thêm cụm dấu câu cho khớp → qua. meaning_vi để trống vì là dấu câu.
        String created = mockMvc.perform(post("/api/dictionary/dialogue-scenes/" + sectionId + "/lines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Nguyễn\",\"textJa\":\"はじめまして。\",\"bubbleSide\":\"trai\","
                                + "\"chunks\":[{\"surface\":\"はじめまして\",\"meaningVi\":\"rất vui được gặp\"},"
                                + "{\"surface\":\"。\"}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.chunks.length()").value(2))
                .andExpect(jsonPath("$.data.bubbleSide").value("trai"))
                .andReturn().getResponse().getContentAsString();
        long lineId = Long.parseLong(created.replaceAll("(?s).*\"contentItemId\"\\s*:\\s*(\\d+).*", "$1"));

        // Lời không có cụm nào cũng hợp lệ: giao diện học sẽ hiện nguyên câu.
        mockMvc.perform(post("/api/dictionary/dialogue-scenes/" + sectionId + "/lines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Tanaka\",\"textJa\":\"よろしく。\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/dictionary/dialogue-lines/" + lineId)).andExpect(status().isOk());
    }

    /** [GĐ4] Vai trùng mã trong cùng bài là 409; vai còn lời thoại thì không xoá được. */
    @Test
    @Transactional
    @WithMockUser(roles = "CONTENT_CREATOR")
    void dialogueRoleCodeIsUniquePerLessonAndRoleInUseCannotBeDeleted() throws Exception {
        String role = mockMvc.perform(post("/api/dictionary/lessons/" + LESSON_1 + "/dialogue-roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"vai-thu\",\"nameVi\":\"Vai thử\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long roleId = Long.parseLong(role.replaceAll("(?s).*\"id\"\\s*:\\s*(\\d+).*", "$1"));

        mockMvc.perform(post("/api/dictionary/lessons/" + LESSON_1 + "/dialogue-roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"vai-thu\",\"nameVi\":\"Trùng mã\"}"))
                .andExpect(status().isConflict());

        // Vai chưa dùng thì xoá được.
        mockMvc.perform(delete("/api/dictionary/dialogue-roles/" + roleId)).andExpect(status().isOk());

        // Vai đang có lời thoại trong dữ liệu seed thì bị chặn.
        long usedRoleId = dialogueRoleRepository.findByLessonIdOrderByCodeAsc(LESSON_1).stream()
                .filter(r -> dialogueRoleRepository.countLinesByRoleId(r.getId()) > 0)
                .findFirst().orElseThrow(() -> new AssertionError("bài 1 phải có vai đang được dùng"))
                .getId();
        mockMvc.perform(delete("/api/dictionary/dialogue-roles/" + usedRoleId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("lời thoại")));
    }

    /** Bài nháp vừa tạo, chưa có gì bên trong thì xoá được — đây là lối dọn bài tạo nhầm. */
    @Test
    @Transactional
    @WithMockUser(roles = "CONTENT_CREATOR")
    void deletingAnEmptyDraftLessonSucceeds() throws Exception {
        long unitId = lessonRepository.findById(LESSON_1).orElseThrow().getUnit().getId();
        long tagId = lessonRepository.findById(LESSON_1).orElseThrow().getKnowledgeTag().getId();

        String created = mockMvc.perform(post("/api/dictionary/units/" + unitId + "/lessons")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"knowledgeTagId\":" + tagId + ",\"title\":\"Bài tạo nhầm\","
                                + "\"lessonType\":\"CURRICULUM\",\"skill\":\"VOCABULARY\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long newId = Long.parseLong(created.replaceAll("(?s).*\"id\"\\s*:\\s*(\\d+).*", "$1"));

        mockMvc.perform(delete("/api/dictionary/lessons/" + newId)).andExpect(status().isOk());
        assertTrue(lessonRepository.findById(newId).isEmpty(), "bài nháp rỗng phải xoá được");
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

    /**
     * Id giáo trình bảng chữ cái lấy theo dữ liệu, không hardcode id 3.
     * <p>
     * Tiêu chí: KHÔNG có cấp độ JLPT và KHÔNG có bài nào gắn tag SITUATION — đúng tiêu chí mà
     * DictionaryServiceImpl.isTopicBased() dùng để tách nhóm "Theo chủ đề", nên test không thể lệch
     * khỏi code thật. Chỉ mỗi "jlpt_level IS NULL" là không đủ: giáo trình chủ đề (IT, du lịch,
     * đời sống) cũng không có cấp độ, thêm "đã công bố" cũng không cứu được vì chúng có thể được
     * công bố bất cứ lúc nào.
     */
    private long kanaTextbookId() {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            return em.createQuery("""
                    select t.id from Textbook t
                    where t.jlptLevel is null
                      and not exists (
                        select 1 from LessonTag lt
                          join lt.lesson l
                          join lt.tag tg
                        where l.unit.textbook.id = t.id and tg.tagType = 'SITUATION')
                    """, Long.class).getSingleResult();
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

    /** Chỉ đếm lesson_tag trỏ tới tag SITUATION (chủ đề học); tag KNOWLEDGE không tính. */
    private long countSituationLessonTags() {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            return em.createQuery("select count(lt) from LessonTag lt join lt.tag tg where tg.tagType = 'SITUATION'", Long.class).getSingleResult();
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
