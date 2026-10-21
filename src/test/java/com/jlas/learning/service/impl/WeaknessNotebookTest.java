package com.jlas.learning.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.dictionary.entity.Outcome;
import com.jlas.learning.dto.WeaknessEvidence;
import com.jlas.learning.dto.WeaknessEvidence.OutcomeBreakdown;
import com.jlas.learning.repository.UserKnowledgeWeaknessRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

/**
 * [JLAS-W0108][MP01 follow-up] Sổ tay điểm yếu phải ghi riêng từng outcome đọc/nghe đã sai (khớp
 * {@code WeaknessEvidence.outcomeBreakdown()}), không chỉ ghi outcome dominant như trước MP01.
 */
@DataJpaTest(properties = {
        "spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:weakness-notebook-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@ContextConfiguration(classes = WeaknessNotebookTest.NotebookConfiguration.class)
class WeaknessNotebookTest {

    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = {"com.jlas.learning.repository", "com.jlas.auth.repository"})
    @Import(WeaknessNotebook.class)
    static class NotebookConfiguration { }

    @Autowired EntityManager em;
    @Autowired WeaknessNotebook notebook;
    @Autowired UserKnowledgeWeaknessRepository weaknesses;

    @Test
    void recordsOneRowPerOutcomeInsteadOfOnlyTheDominantOne() {
        User user = seedUser();
        var outcomes = seedOutcomes();
        var evidence = comprehensionEvidenceFor(outcomes);

        notebook.record(user, 91L, List.of(evidence));

        var rows = weaknesses.findByUserId(user.getId());
        assertEquals(2, rows.size(), "Hai outcome sai bằng nhau đều phải có dòng riêng trong notebook");
        assertTrue(rows.stream().anyMatch(r -> r.getOutcome() != null
                && outcomes.mainIdea().getId().equals(r.getOutcome().getId()) && r.getWrongCount() == 1));
        assertTrue(rows.stream().anyMatch(r -> r.getOutcome() != null
                && outcomes.detail().getId().equals(r.getOutcome().getId()) && r.getWrongCount() == 1));
    }

    @Test
    void recordingTwiceForTheSameOutcomesUpdatesInPlaceInsteadOfDuplicating() {
        User user = seedUser();
        var outcomes = seedOutcomes();
        var evidence = comprehensionEvidenceFor(outcomes);

        notebook.record(user, 91L, List.of(evidence));
        notebook.record(user, 92L, List.of(evidence));

        assertEquals(2, weaknesses.findByUserId(user.getId()).size(),
                "Ghi lại đúng 2 outcome cũ không được tạo thêm dòng trùng");
    }

    private WeaknessEvidence comprehensionEvidenceFor(Outcomes outcomes) {
        return WeaknessEvidence.comprehension(WeaknessEvidence.SKILL_READING, 2, 2,
                "JLPT", null,
                List.of(new OutcomeBreakdown("READ_MAIN_IDEA", outcomes.mainIdea().getId(), 1, 1),
                        new OutcomeBreakdown("READ_DETAIL", outcomes.detail().getId(), 1, 1)),
                null, null, "N4");
    }

    @Test
    void repeatedSyncPreservesReviewButNewEvidenceReopensTheItem() {
        User user = seedUser();
        var evidence = WeaknessEvidence.item("KANJI", "KANJI:食", 1, 1, "N5");
        notebook.record(user, 100L, List.of(evidence));
        var row = weaknesses.findByUserId(user.getId()).getFirst();
        row.setStatus("REVIEWED");
        notebook.record(user, 100L, List.of(evidence));
        assertEquals("REVIEWED", row.getStatus());
        notebook.record(user, 99L, List.of(evidence));
        assertEquals("REVIEWED", row.getStatus());
        notebook.record(user, 101L, List.of(evidence));
        assertEquals("OPEN", row.getStatus());
        assertEquals(1, weaknesses.findByUserId(user.getId()).size());
    }

    private Outcomes seedOutcomes() {
        var mainIdea = Outcome.builder().code("READ_MAIN_IDEA").nameVi("Ý chính").skill("READING").build();
        var detail = Outcome.builder().code("READ_DETAIL").nameVi("Chi tiết").skill("READING").build();
        em.persist(mainIdea);
        em.persist(detail);
        em.flush();
        return new Outcomes(mainIdea, detail);
    }

    private record Outcomes(Outcome mainIdea, Outcome detail) { }

    private int userSeq = 0;

    private User seedUser() {
        Role role = em.createQuery("select r from Role r where r.name = 'LEARNER'", Role.class)
                .getResultStream().findFirst()
                .orElseGet(() -> {
                    Role created = Role.builder().name("LEARNER").build();
                    em.persist(created);
                    return created;
                });
        User user = User.builder().role(role)
                .email("weakness-notebook" + (++userSeq) + "@test.local")
                .loginType("LOCAL").build();
        em.persist(user);
        em.flush();
        return user;
    }
}
