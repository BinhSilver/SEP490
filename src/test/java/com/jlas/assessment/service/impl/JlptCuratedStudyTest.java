package com.jlas.assessment.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JlptCuratedStudyTest {
    static JlptCuratedStudy withoutSavedLists() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<ResultSetExtractor<Optional<JsonNode>>>any(),
                anyString())).thenReturn(Optional.empty());
        return new JlptCuratedStudy(jdbc);
    }

    @Test void savedListTakesPrecedenceAndKeepsOnlyStudyItemsInOrder() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:")) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("CREATE TABLE jlpt_exam (id BIGINT, exam_code VARCHAR(30))");
            jdbc.execute("CREATE TABLE jlpt_exam_study_list (exam_id BIGINT)");
            jdbc.execute("""
                    CREATE TABLE jlpt_exam_study_item (
                        exam_id BIGINT, item_kind VARCHAR(30), sort_order INT, payload VARCHAR(500)
                    )
                    """);
            jdbc.update("INSERT INTO jlpt_exam VALUES (1, 'N2_2024_07')");
            jdbc.update("INSERT INTO jlpt_exam_study_list VALUES (1)");
            String insert = "INSERT INTO jlpt_exam_study_item VALUES (1, ?, ?, ?)";
            jdbc.update(insert, "VOCABULARY", 2, "{\"word\":\"second\"}");
            jdbc.update(insert, "VOCABULARY", 1, "{\"word\":\"first\"}");
            jdbc.update(insert, "GRAMMAR", 1, "{\"pattern\":\"matched\"}");
            jdbc.update(insert, "UNMATCHED_GRAMMAR", 1, "{\"pattern\":\"unmatched\"}");

            var data = new JlptCuratedStudy(jdbc).find("N2_2024_07").orElseThrow();
            assertThat(data.path("vocabulary")).extracting(word -> word.path("word").asText())
                    .containsExactly("first", "second");
            assertThat(data.path("grammar")).extracting(item -> item.path("pattern").asText())
                    .containsExactly("matched");
        }
    }

    @Test void n1AndN2ExamListsResolveAndKeepUnmatchedExpressionsSeparate() throws Exception {
        var loader = withoutSavedLists();
        for (String level : new String[]{"N1", "N2"}) {
            try (var files = Files.list(Path.of("src/main/resources/jlpt-study/" + level))) {
                var resources = files.filter(p -> p.toString().endsWith(".json")).toList();
                assertThat(resources).hasSize(level.equals("N1") ? 22 : 27);
                for (var resource : resources) {
                    String code = resource.getFileName().toString().replace(".json", "");
                    var data = loader.find(code).orElseThrow();
                    assertThat(data.path("vocabulary").size()).isPositive();
                    assertThat(data.path("unmatched_grammar").isArray()).isTrue();
                    for (var grammar : data.path("grammar")) {
                        assertThat(grammar.path("lesson").isObject()).isTrue();
                        assertThat(grammar.path("link").asText()).isNotBlank();
                        assertThat(grammar.path("is_answer").isBoolean()).isTrue();
                    }
                }
            }
        }
    }
    @Test void allBundledN3ListsHaveExamIdentityAndPreserveSourceFields() throws Exception {
        var loader = withoutSavedLists();
        try (var files = Files.list(Path.of("src/main/resources/jlpt-study/N3"))) {
            var resources = files.filter(p -> p.toString().endsWith(".json")).toList();
            assertThat(resources).hasSize(26);
            for (var resource : resources) {
                String code = resource.getFileName().toString().replace(".json", "");
                var data = loader.find(code).orElseThrow();
                assertThat(data.path("code").asText()).isEqualTo(code);
                assertThat(data.path("vocabulary").size()).isGreaterThan(70);
                for (var word : data.path("vocabulary")) {
                    assertThat(word.path("word").asText()).isNotBlank();
                    assertThat(word.path("tested").isBoolean()).isTrue();
                }
                assertThat(data.path("grammar").size()).isGreaterThan(0);
                for (var grammar : data.path("grammar")) {
                    assertThat(grammar.path("pattern").asText()).isNotBlank();
                    assertThat(grammar.path("is_answer").isBoolean()).isTrue();
                }
            }
        }
        assertThat(loader.find("N2_2023_12")).isPresent();
        assertThat(loader.find("N1_2023_12")).isPresent();
        assertThat(loader.find("N3_2020_12")).isEmpty();
        assertThat(loader.find("../../secrets")).isEmpty();
    }
}
