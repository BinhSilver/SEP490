package com.jlas.flashcard.controller;

import com.jlas.flashcard.service.FlashcardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Controller API flashcard. Base path: /api/flashcard */
@RestController
@RequestMapping("/api/flashcard")
@RequiredArgsConstructor
public class FlashcardController {
    private final FlashcardService flashcardService;
}
