package com.jlas.flashcard;
import com.jlas.auth.entity.User;
import com.jlas.flashcard.entity.FlashcardSet;
import com.jlas.flashcard.repository.FlashcardSetRepository;
import com.jlas.flashcard.service.FlashcardStudyAccess;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class FlashcardStudyAccessTest {
    @Test void preservesExistingVisibilityDraftAndDeletionPolicy() {
        var repo = mock(FlashcardSetRepository.class);
        var access = new FlashcardStudyAccess(repo);
        var set = FlashcardSet.builder().id(1L).owner(User.builder().id(2L).build()).build();
        when(repo.findById(1L)).thenReturn(Optional.of(set));
        assertSame(set, access.studyable(1L,2L));
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> access.studyable(1L,3L)).getStatusCode().value());
        set.setVisibility("PUBLIC"); assertSame(set, access.studyable(1L,3L));
        set.setDraft(true);
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> access.studyable(1L,2L)).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> access.studyable(1L,3L)).getStatusCode().value());
        set.setDraft(false); set.setVisibility("PRIVATE"); set.setModerationStatus("UNSAFE");
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> access.studyable(1L,3L)).getStatusCode().value());
        set.setDeletedAt(LocalDateTime.now());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> access.studyable(1L,2L)).getStatusCode().value());
    }
}
