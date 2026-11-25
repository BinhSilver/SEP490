package com.jlas.contentreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import static com.jlas.contentreport.ContentReportDtos.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the migration and the repository in an isolated H2 MySQL-mode database, never the app DB. */
class ContentReportRepositoryTest {
    JdbcTemplate jdbc;
    ContentReportRepository reports;
    Request request=new Request(Target.AI_EXPLANATION,Page.JLPT_EXAM,"12",null,null,"AI text",Reason.AI,"mô tả");
    TargetSnapshot target=new TargetSnapshot(Target.AI_EXPLANATION,"12:response","AI câu 12","/jlpt/exam?code=N3",new ObjectMapper().createObjectNode().put("reportedExplanation","<script>untrusted</script>"));
    @BeforeEach void setup()throws Exception{
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:report"+System.nanoTime()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc=new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(source.getConnection(),true));reports=new ContentReportRepository(jdbc);
        String sql=Files.readString(Path.of("sql/migrations/090_learning_content_reports.sql"))
                .replaceAll("(?m)^--[^\\n]*","").replace(" COLLATE utf8mb4_bin","").replace(" STORED","")
                .replaceAll("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci","");
        jdbc.execute(sql);
    }
    @Test void storesFullSnapshotAndEnforcesOneOpenReportPerUserAndTarget(){
        Long id=reports.insert(7L,request,target,"mô tả");
        assertTrue(id>0);assertTrue(reports.hasOpen(7L,Target.AI_EXPLANATION,"12:response"));
        assertThrows(DuplicateKeyException.class,()->reports.insert(7L,request,target,"duplicate"));
        var saved=reports.find(id).orElseThrow();assertEquals("<script>untrusted</script>",saved.context().path("reportedExplanation").asText());
        assertEquals(Status.OPEN,saved.status());assertEquals(1L,reports.countToday(7L));
    }
    @Test void resolvedReportsKeepHistoryAndAllowLaterReports(){
        Long id=reports.insert(7L,request,target,null);
        assertEquals(1,reports.resolve(id,9L,new Resolution(Status.RESOLVED,"Đã kiểm tra")));
        var row=reports.find(id).orElseThrow();assertEquals(9L,row.resolvedBy());assertNotNull(row.resolvedAt());
        assertEquals(0,reports.resolve(id,10L,new Resolution(Status.REJECTED,"stale")));
        assertNotEquals(id,reports.insert(7L,request,target,null));
        assertEquals(1,reports.list(Status.OPEN,null,0).totalElements());
        assertEquals(1,reports.list(Status.RESOLVED,Target.AI_EXPLANATION,0).totalElements());
        assertEquals(0,reports.list(Status.OPEN,Target.KANJI_MAP,0).totalElements());
    }
}
