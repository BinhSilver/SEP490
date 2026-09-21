package com.jlas.flashcard.service;

import com.jlas.flashcard.entity.FlashcardSet;
import com.jlas.flashcard.repository.FlashcardSetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Shared visibility/draft policy for every flashcard study mode. */
@Service @RequiredArgsConstructor
public class FlashcardStudyAccess {
    private final FlashcardSetRepository sets;
    public FlashcardSet readable(Long id, Long userId) {
        FlashcardSet set = sets.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bộ thẻ"));
        if (set.getDeletedAt() != null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bộ thẻ");
        if ((userId != null && userId.equals(set.getOwner().getId())) || (!set.isDraft() && "PUBLIC".equals(set.getVisibility()))) return set;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bộ thẻ là riêng tư");
    }
    public FlashcardSet studyable(Long id, Long userId) {
        FlashcardSet set = readable(id, userId);
        if (set.isDraft()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bản nháp chưa thể học hoặc kiểm tra");
        return set;
    }
}
