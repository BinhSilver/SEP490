package com.jlas.contentreport;

import com.jlas.common.exception.ApiException;
import com.jlas.conjugation.VerbCatalog;
import com.jlas.conjugation.VerbSessionRepository;
import com.jlas.dictionary.kanjimap.KanjiMapContentService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static com.jlas.contentreport.ContentReportDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real source lookup plus report persistence in an isolated DB; never connects to the application DB. */
class ContentReportJlptVisibilityTest {
    SingleConnectionDataSource source;
    JdbcTemplate jdbc;
    ContentReportTargets targets;
    ContentReportRepository reports;

    @BeforeEach void setup() throws Exception {
        var h2=new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:reportVisibility"+System.nanoTime()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        source=new SingleConnectionDataSource(h2.getConnection(),true);
        jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE jlpt_exam(id BIGINT PRIMARY KEY,exam_code VARCHAR(30),title VARCHAR(255),jlpt_level VARCHAR(10),status VARCHAR(20))");
        jdbc.execute("CREATE TABLE jlpt_part(id BIGINT PRIMARY KEY,exam_id BIGINT)");
        jdbc.execute("CREATE TABLE jlpt_mondai(id BIGINT PRIMARY KEY,part_id BIGINT)");
        jdbc.execute("CREATE TABLE jlpt_question(id BIGINT PRIMARY KEY,mondai_id BIGINT,question_number INT,question_text VARCHAR(1000),correct_option INT)");
        jdbc.execute("CREATE TABLE jlpt_option(question_id BIGINT,option_number INT,option_text VARCHAR(1000))");
        jdbc.update("INSERT INTO jlpt_exam VALUES(1,'N3_2024_12','JLPT N3','N3','DRAFT')");
        jdbc.update("INSERT INTO jlpt_part VALUES(2,1)");
        jdbc.update("INSERT INTO jlpt_mondai VALUES(3,2)");
        jdbc.update("INSERT INTO jlpt_question VALUES(12,3,23,'問題',2)");
        jdbc.update("INSERT INTO jlpt_option VALUES(12,2,'答え')");
        String migration=Files.readString(Path.of("sql/migrations/090_learning_content_reports.sql"))
                .replace(" COLLATE utf8mb4_bin","").replace(" STORED","")
                .replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci","");
        jdbc.execute(migration);
        targets=new ContentReportTargets(jdbc,mock(VerbSessionRepository.class),mock(VerbCatalog.class),mock(KanjiMapContentService.class));
        reports=new ContentReportRepository(jdbc);
    }
    @AfterEach void close(){source.destroy();}

    @Test void accessibleDraftQuestionsAndTheirAiExplanationsCanBeReported() {
        for(var page:new Page[]{Page.JLPT_EXAM,Page.JLPT_PRACTICE}) {
            for(var type:new Target[]{Target.JLPT_QUESTION,Target.AI_EXPLANATION}) {
                var request=new Request(type,page,"12",null,null,type==Target.AI_EXPLANATION?"Giải thích đang hiển thị":null,Reason.CONTENT,null);
                var target=targets.resolve(7L,request);
                assertEquals("N3_2024_12",target.context().path("exam_code").asText());
                assertEquals(23,target.context().path("question_number").asInt());
                assertEquals("答え",target.context().path("options").get(0).path("option_text").asText());
                // Different users keep each case independent of the per-user duplicate constraint.
                long userId=7L+page.ordinal()*10+type.ordinal();
                long id=reports.insert(userId,request,target,null);
                var saved=reports.find(id).orElseThrow();
                assertEquals(page,saved.page());assertEquals(type,saved.targetType());
                if(type==Target.AI_EXPLANATION)assertEquals(request.explanation(),saved.context().path("reportedExplanation").asText());
            }
        }
    }
    @Test void publishedQuestionsStillResolve() {
        jdbc.update("UPDATE jlpt_exam SET status='PUBLISHED'");
        assertEquals("12",targets.resolve(7L,question("12")).code());
    }
    @Test void archivedAndMissingQuestionsRemainUnavailable() {
        jdbc.update("UPDATE jlpt_exam SET status='ARCHIVED'");
        assertThrows(ApiException.class,()->targets.resolve(7L,question("12")));
        assertThrows(ApiException.class,()->targets.resolve(7L,question("999")));
    }
    private Request question(String id){return new Request(Target.JLPT_QUESTION,Page.JLPT_EXAM,id,null,null,null,Reason.CONTENT,null);}
}
