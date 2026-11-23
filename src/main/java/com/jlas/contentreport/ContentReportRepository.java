package com.jlas.contentreport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import static com.jlas.contentreport.ContentReportDtos.*;

@Repository @RequiredArgsConstructor
public class ContentReportRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    public long countToday(Long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM learning_content_report WHERE reporter_user_id=? AND created_at>=CURRENT_DATE()",Long.class,userId);
    }
    public boolean hasOpen(Long userId, Target type, String code) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM learning_content_report WHERE reporter_user_id=? AND target_type=? AND target_code=? AND status='OPEN'",Long.class,userId,type.name(),code)>0;
    }
    public Long insert(Long userId, Request request, TargetSnapshot target, String note) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps=connection.prepareStatement("""
                    INSERT INTO learning_content_report(reporter_user_id,target_type,target_code,page_context,
                    target_label,reason,note,context_json,source_url,status) VALUES(?,?,?,?,?,?,?,?,?,'OPEN')
                    """,new String[]{"id"});
            ps.setLong(1,userId); ps.setString(2,target.type().name()); ps.setString(3,target.code());
            ps.setString(4,request.page().name()); ps.setString(5,target.label()); ps.setString(6,request.reason().name());
            ps.setString(7,note); ps.setString(8,target.context().toString()); ps.setString(9,target.url());
            return ps;
        },key);
        return key.getKey().longValue();
    }
    public ResultPage list(Status status, Target type, int page) {
        List<Object> args=new ArrayList<>(); args.add(status.name());
        String where=" WHERE status=?";
        if(type!=null){where+=" AND target_type=?";args.add(type.name());}
        long count=jdbc.queryForObject("SELECT COUNT(*) FROM learning_content_report"+where,Long.class,args.toArray());
        args.add(20);args.add(page*20);
        var rows=jdbc.query("SELECT * FROM learning_content_report"+where+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",rowMapper(),args.toArray());
        return new ResultPage(rows,page,20,count,(count+19)/20);
    }
    public java.util.Optional<Row> find(Long id) {
        return jdbc.query("SELECT * FROM learning_content_report WHERE id=?",rowMapper(),id).stream().findFirst();
    }
    public int resolve(Long id, Long reviewerId, Resolution request) {
        return jdbc.update("UPDATE learning_content_report SET status=?,review_note=?,resolved_by=?,resolved_at=CURRENT_TIMESTAMP WHERE id=? AND status='OPEN'",
                request.status().name(),request.note(),reviewerId,id);
    }
    private RowMapper<Row> rowMapper() {
        return (rs,n) -> {
            try {
                var resolved=rs.getTimestamp("resolved_at");
                return new Row(rs.getLong("id"),rs.getLong("reporter_user_id"),Target.valueOf(rs.getString("target_type")),rs.getString("target_code"),
                    Page.valueOf(rs.getString("page_context")),rs.getString("target_label"),Reason.valueOf(rs.getString("reason")),rs.getString("note"),
                    mapper.readTree(rs.getString("context_json")),rs.getString("source_url"),Status.valueOf(rs.getString("status")),rs.getString("review_note"),
                    (Long)rs.getObject("resolved_by"),rs.getTimestamp("created_at").toLocalDateTime(),resolved==null?null:resolved.toLocalDateTime());
            } catch(JsonProcessingException error){throw new java.sql.SQLException("Invalid report context",error);}
        };
    }
}
