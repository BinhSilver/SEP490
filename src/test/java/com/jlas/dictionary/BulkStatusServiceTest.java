package com.jlas.dictionary;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.dto.BulkStatusResponse;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.Textbook;
import com.jlas.dictionary.entity.Unit;
import com.jlas.dictionary.repository.LessonRepository;
import com.jlas.dictionary.repository.TextbookRepository;
import com.jlas.dictionary.service.impl.DictionaryServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [JLAS2-17] Công bố / ẩn hàng loạt: cùng luật với API từng mục, và có lỗi ở bất kỳ id nào thì KHÔNG mục nào bị đổi
 * (kiểm tra trước khi ghi; trên DB thật còn có @Transactional của service rollback cả lô).
 */
@ExtendWith(MockitoExtension.class)
class BulkStatusServiceTest {

    @Mock TextbookRepository textbookRepository;
    @Mock LessonRepository lessonRepository;
    @Mock com.jlas.dictionary.mapper.DictionaryMapper mapper;
    @InjectMocks DictionaryServiceImpl service;

    private static Textbook textbook(long id, String status) {
        Textbook t = new Textbook();
        t.setId(id);
        t.setTitle("Giáo trình " + id);
        t.setStatus(status);
        return t;
    }

    private static Lesson lesson(long id, String status, Textbook book) {
        Unit unit = new Unit();
        unit.setId(id * 10);
        unit.setTextbook(book);
        Lesson l = new Lesson();
        l.setId(id);
        l.setTitle("Bài " + id);
        l.setStatus(status);
        l.setUnit(unit);
        return l;
    }

    @Test
    void publishTextbooksCountsUpdatedAndUnchangedAndMergesDuplicates() {
        Textbook a = textbook(1, "DRAFT");
        Textbook b = textbook(2, "PUBLISHED");
        when(textbookRepository.findAllById(any())).thenReturn(List.of(a, b));

        BulkStatusResponse r = service.bulkUpdateStatus("TEXTBOOK", "PUBLISH", List.of(1L, 2L, 1L), 9L);

        assertEquals("PUBLISHED", a.getStatus());
        assertEquals(2, r.getRequested());
        assertEquals(1, r.getUpdated());
        assertEquals(1, r.getUnchanged());
        assertEquals(List.of(1L, 2L), r.getIds());
        verify(textbookRepository).saveAll(anyIterable());
    }

    @Test
    void publishLessonsAlsoPublishesTheirPracticeQuizzes() {
        Textbook book = textbook(1, "PUBLISHED");
        Lesson l1 = lesson(11, "DRAFT", book);
        Lesson l2 = lesson(12, "PUBLISHED", book);
        when(lessonRepository.findWithUnitByIdIn(anyCollection())).thenReturn(List.of(l1, l2));

        service.bulkUpdateStatus("LESSON", "PUBLISH", List.of(11L, 12L), 9L);

        // Cả bài vốn đã công bố cũng được đồng bộ (sửa dữ liệu lệch cũ).
        verify(lessonRepository).activatePracticeQuestions(List.of(11L, 12L));
        verify(lessonRepository).updatePracticeQuizStatus(List.of(11L, 12L), "PUBLISHED");
    }

    @Test
    void unpublishLessonsHidesPracticeQuizzesWithoutTouchingQuestions() {
        Textbook book = textbook(1, "PUBLISHED");
        when(lessonRepository.findWithUnitByIdIn(anyCollection())).thenReturn(List.of(lesson(11, "PUBLISHED", book)));

        service.bulkUpdateStatus("LESSON", "UNPUBLISH", List.of(11L), 9L);

        verify(lessonRepository).updatePracticeQuizStatus(List.of(11L), "DRAFT");
        verify(lessonRepository, never()).activatePracticeQuestions(anyCollection());
    }

    @Test
    void singleLessonStatusSyncsPracticeQuiz() {
        Textbook book = textbook(1, "PUBLISHED");
        Lesson l = lesson(11, "DRAFT", book);
        when(lessonRepository.findWithUnitById(11L)).thenReturn(java.util.Optional.of(l));
        when(lessonRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateLessonStatus(11L, "PUBLISHED");
        verify(lessonRepository).activatePracticeQuestions(List.of(11L));
        verify(lessonRepository).updatePracticeQuizStatus(List.of(11L), "PUBLISHED");

        service.updateLessonStatus(11L, "ARCHIVED");
        verify(lessonRepository).updatePracticeQuizStatus(List.of(11L), "DRAFT");
    }

    @Test
    void unpublishLessonsMovesToDraftEvenWhenTextbookIsDraft() {
        Textbook book = textbook(1, "DRAFT");
        Lesson l1 = lesson(11, "PUBLISHED", book);
        Lesson l2 = lesson(12, "PUBLISHED", book);
        when(lessonRepository.findWithUnitByIdIn(anyCollection())).thenReturn(List.of(l1, l2));

        BulkStatusResponse r = service.bulkUpdateStatus("LESSON", "UNPUBLISH", List.of(11L, 12L), 9L);

        assertEquals("DRAFT", l1.getStatus());
        assertEquals("DRAFT", l2.getStatus());
        assertEquals("DRAFT", r.getStatus());
        assertEquals(2, r.getUpdated());
    }

    @Test
    void publishLessonInDraftTextbookChangesNothing() {
        Textbook published = textbook(1, "PUBLISHED");
        Textbook draft = textbook(2, "DRAFT");
        Lesson ok = lesson(11, "DRAFT", published);
        Lesson orphan = lesson(12, "DRAFT", draft);
        when(lessonRepository.findWithUnitByIdIn(anyCollection())).thenReturn(List.of(ok, orphan));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.bulkUpdateStatus("LESSON", "PUBLISH", List.of(11L, 12L), 9L));

        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("Giáo trình 2"), ex.getMessage());
        assertEquals("DRAFT", ok.getStatus());   // bài hợp lệ đứng trước cũng KHÔNG bị đổi
        verify(lessonRepository, never()).saveAll(anyIterable());
    }

    @Test
    void unknownIdChangesNothing() {
        Textbook a = textbook(1, "DRAFT");
        when(textbookRepository.findAllById(any())).thenReturn(List.of(a));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.bulkUpdateStatus("TEXTBOOK", "PUBLISH", List.of(1L, 999L), 9L));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("999"), ex.getMessage());
        assertEquals("DRAFT", a.getStatus());
        verify(textbookRepository, never()).saveAll(anyIterable());
    }

    @Test
    void archivedItemIsRejectedAndNothingChanges() {
        Textbook book = textbook(1, "PUBLISHED");
        Lesson draft = lesson(11, "DRAFT", book);
        Lesson archived = lesson(12, "ARCHIVED", book);
        when(lessonRepository.findWithUnitByIdIn(anyCollection())).thenReturn(List.of(draft, archived));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.bulkUpdateStatus("LESSON", "PUBLISH", List.of(11L, 12L), 9L));

        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        assertEquals("DRAFT", draft.getStatus());
        assertEquals("ARCHIVED", archived.getStatus());
        verify(lessonRepository, never()).saveAll(anyIterable());
    }
}
