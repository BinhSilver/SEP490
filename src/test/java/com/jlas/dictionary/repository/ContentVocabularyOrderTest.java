package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.ContentVocabulary;
import com.jlas.dictionary.entity.VocabularyEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Từ của bài: giữ thứ tự truy vấn (display_order rồi id), một từ gắn nhiều mục chỉ hiện một lần ở vị trí sớm nhất. */
class ContentVocabularyOrderTest {

    private static ContentVocabulary link(long vocabularyId, Integer order) {
        VocabularyEntry entry = new VocabularyEntry();
        entry.setId(vocabularyId);
        return ContentVocabulary.builder().vocabularyEntry(entry).displayOrder(order).build();
    }

    @Test
    void keepsQueryOrderAndDropsDuplicates() {
        ContentVocabularyRepository repository = mock(ContentVocabularyRepository.class, CALLS_REAL_METHODS);
        // Sau gộp (088): mục đúng 20740 nằm ở vị trí cũ của mục sai (display_order 2), trước 30796 dù id nhỏ hơn.
        when(repository.findLinksByLessonIdOrdered(1L)).thenReturn(List.of(
                link(30001L, 1), link(20740L, 2), link(30796L, 3), link(20740L, 5), link(40000L, null)));
        assertEquals(List.of(30001L, 20740L, 30796L, 40000L),
                repository.findVocabularyByLessonId(1L).stream().map(VocabularyEntry::getId).toList());
    }
}
