import java.sql.*;
import java.util.*;

class ApplyProgressionSchema {
    public static void main(String[] args)throws Exception {
        var columns=new LinkedHashMap<String,String>();
        columns.put("stage_code","VARCHAR(100) NULL");
        columns.put("stage_definition_json","JSON NULL");
        columns.put("stage_cursor","INT NOT NULL DEFAULT 0");
        columns.put("stage_passed","BOOLEAN NOT NULL DEFAULT FALSE");
        columns.put("content_review","BOOLEAN NOT NULL DEFAULT FALSE");
        columns.put("current_state_code","VARCHAR(80) NULL");
        columns.put("completed_objectives","JSON NULL");
        columns.put("last_activity_at","DATETIME(6) NULL");
        try(var db=DriverManager.getConnection(System.getenv("AICALL_DB_URL"),System.getenv("AICALL_DB_USER"),System.getenv("AICALL_DB_PASSWORD"))) {
            try(var s=db.createStatement();var r=s.executeQuery("SELECT DATABASE()")) {
                r.next();if(!"jlas_aicall".equals(r.getString(1)))throw new IllegalStateException("Only jlas_aicall is accepted");
            }
            for(var column:columns.entrySet()) {
                try(var q=db.prepareStatement("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='conversation_session' AND column_name=?")) {
                    q.setString(1,column.getKey());try(var r=q.executeQuery()){r.next();if(r.getInt(1)>0)continue;}
                }
                try(var s=db.createStatement()){s.execute("ALTER TABLE conversation_session ADD COLUMN "+column.getKey()+" "+column.getValue());}
            }
            try(var s=db.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='conversation_session' AND index_name='idx_session_stage_progress'")) {
                r.next();if(r.getInt(1)==0)try(var ddl=db.createStatement()){ddl.execute("ALTER TABLE conversation_session ADD KEY idx_session_stage_progress (user_id,scenario_id,stage_code,stage_passed)");}
            }
            System.out.println("Local AI Call stage columns ready; existing sessions preserved.");
        }
    }
}
