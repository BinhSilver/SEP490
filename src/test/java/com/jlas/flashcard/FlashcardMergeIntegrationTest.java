package com.jlas.flashcard;

import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.auth.repository.RoleRepository;
import com.jlas.auth.repository.UserRepository;
import com.jlas.flashcard.dto.FlashcardDtos.*;
import com.jlas.flashcard.repository.FlashcardCardRepository;
import com.jlas.flashcard.repository.FlashcardStudySessionRepository;
import com.jlas.flashcard.service.FlashcardFileCodec;
import com.jlas.flashcard.service.FlashcardService;
import com.jlas.game.service.GameDeckService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:credit_merge_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@Transactional
class FlashcardMergeIntegrationTest {
    @Autowired FlashcardService flashcards;
    @Autowired FlashcardStudySessionRepository sessions;
    @Autowired FlashcardCardRepository cards;
    @Autowired FlashcardFileCodec codec;
    @Autowired GameDeckService games;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    private User owner;
    private SetDetailDto deck;

    @BeforeEach
    void createDeckWithKanjiAndOptionalKanaReading() {
        Role role = roles.findByName("LEARNER").orElseGet(() -> roles.save(Role.builder().name("LEARNER").build()));
        owner = users.saveAndFlush(User.builder().email("flashcard-merge@example.com").username("flashcard-merge")
                .passwordHash("not-a-login-hash").role(role).loginType("LOCAL").creditBalance(0).isVerified(true).build());
        deck = flashcards.create(new SetRequest("Integration deck", null, null, "PRIVATE", null, List.of(
                card("猫", "ねこ", "mèo", "Miêu"), card("犬", "いぬ", "chó", "Khuyển"),
                card("山", "やま", "núi", "Sơn"), card("高", "たかい", "cao", "Cao"),
                card("ネコ", null, "mèo", null))), owner.getId());
    }

    @Test
    void defaultQuizPreservesKanaEligibilityAndDeduplicatesAnswers() {
        Long id = flashcards.startQuiz(new StartQuizRequest(deck.getId(), null, false, null, null), owner.getId());
        QuizQuestionDto question = flashcards.nextQuiz(id, owner.getId());
        assertThat(question.getTotalQuestions()).isEqualTo(4);
        assertThat(question.getOptions()).extracting(OptionDto::getText).doesNotHaveDuplicates().hasSize(4);
        assertThat(question.getQuestion()).isNotEqualTo("ネコ");
        assertThat(deck.getCards()).hasSize(5);
    }

    @Test
    void readsPersistedQuizModesFromBothBranches() {
        Long id = flashcards.startQuiz(new StartQuizRequest(deck.getId(), null, false, "MEANING", "HAN_VIET"), owner.getId());
        for (String mode : List.of("QUIZ_MEANING_TO_HAN_VIET", "MC:MEANING>HAN_VIET")) {
            sessions.findById(id).orElseThrow().setMode(mode);
            QuizQuestionDto question = flashcards.nextQuiz(id, owner.getId());
            var card = cards.findById(question.getCardId()).orElseThrow();
            assertThat(question.getQuestion()).isEqualTo(card.getMeaning());
            assertThat(question.getOptions()).extracting(OptionDto::getText).contains(card.getHanViet());
        }
        sessions.findById(id).orElseThrow().setMode("MULTIPLE_CHOICE");
        QuizQuestionDto legacy = flashcards.nextQuiz(id, owner.getId());
        assertThat(legacy.getQuestion()).isEqualTo(cards.findById(legacy.getCardId()).orElseThrow().getFront());
    }

    @Test
    void selectedSubsetStillRequiresFourEligibleCards() {
        List<Long> selected = deck.getCards().stream().limit(3).map(CardDto::getId).toList();
        assertThatThrownBy(() -> flashcards.startQuiz(
                new StartQuizRequest(deck.getId(), selected, false, "KANJI", "MEANING"), owner.getId()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("4");
    }

    @Test
    void importExportAndArenaUseTheSamePrivateDeck() {
        for (String format : List.of("csv", "txt", "xlsx")) {
            ExportFileDto file = flashcards.export(deck.getId(), format, true, owner.getId());
            String imported = codec.toImportText(file.getFilename(), file.getContent());
            assertThat(imported).contains("猫", "mèo", "ネコ");
        }
        assertThat(games.cards(owner.getId(), deck.getId())).hasSize(5);
        assertThatThrownBy(() -> games.cards(owner.getId() + 10000, deck.getId()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
    }

    private static CardInput card(String front, String reading, String meaning, String hanViet) {
        CardInput card = new CardInput();
        card.setFront(front);
        card.setReading(reading);
        card.setMeaning(meaning);
        card.setHanViet(hanViet);
        return card;
    }
}
