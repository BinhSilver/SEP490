import java.sql.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Companion to apply_local_catalog.py. Credentials are supplied only through the environment. */
class ApplyLocalCatalog {
    public static void main(String[] args) throws Exception {
        Path input=Path.of("target/ai-call-catalog-update");
        var slugs=Files.readAllLines(input.resolve("slugs.txt"));
        int expectedSlugs = System.getenv("AICALL_EXPECTED_SLUGS") != null ? Integer.parseInt(System.getenv("AICALL_EXPECTED_SLUGS")) : slugs.size();
        int expectedRoots = System.getenv("AICALL_EXPECTED_ROOTS") != null ? Integer.parseInt(System.getenv("AICALL_EXPECTED_ROOTS")) : -1;
        if(slugs.isEmpty() || slugs.size()!=expectedSlugs || new HashSet<>(slugs).size()!=slugs.size())throw new IllegalStateException("Expected "+expectedSlugs+" unique slugs");
        var columns=List.of("title","variant_title","description","background","flow_json","learning_content_json","thumbnail_url");
        Path backup=Path.of("target/ai-call-before-"+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        try(var db=DriverManager.getConnection(System.getenv("AICALL_DB_URL"),System.getenv("AICALL_DB_USER"),System.getenv("AICALL_DB_PASSWORD"))) {
            try(var s=db.createStatement();var r=s.executeQuery("SELECT DATABASE()")) {
                r.next();if(!"jlas_aicall".equals(r.getString(1)))throw new IllegalStateException("Only jlas_aicall development database is accepted");
            }
            db.setAutoCommit(false);
            try {
                var assignments=columns.stream().map(c->c+"=?").toList();
                try(var select=db.prepareStatement("SELECT id,parent_scenario_id,"+String.join(",",columns)+" FROM scenario WHERE slug=? FOR UPDATE");
                    var update=db.prepareStatement("UPDATE scenario SET "+String.join(",",assignments)+" WHERE id=?")) {
                    Files.createDirectories(backup);var manifest=new ArrayList<String>();int roots=0;
                    for(String slug:slugs) {
                        select.setString(1,slug);
                        long id;
                        try(var r=select.executeQuery()) {
                            if(!r.next())throw new IllegalStateException("Existing scenario missing: "+slug);
                            id=r.getLong("id");if(r.getObject("parent_scenario_id")==null)roots++;
                            Path row=backup.resolve(slug);Files.createDirectories(row);
                            for(String c:columns) {
                                String value=r.getString(c);
                                if(value!=null)Files.writeString(row.resolve(c+".txt"),value);
                            }
                            manifest.add(id+"\t"+slug);
                            if(r.next())throw new IllegalStateException("Duplicate slug: "+slug);
                        }
                        for(int i=0;i<columns.size();i++)update.setString(i+1,Files.readString(input.resolve(slug).resolve(columns.get(i)+".txt")));
                        update.setLong(columns.size()+1,id);
                        if(update.executeUpdate()!=1)throw new IllegalStateException("Expected one row: "+slug);
                    }
                    if(expectedRoots != -1 && roots != expectedRoots)throw new IllegalStateException("Expected "+expectedRoots+" existing roots");
                    Files.write(backup.resolve("ids.tsv"),manifest);
                    Files.write(input.resolve("ids.tsv"),manifest);
                }
                db.commit();System.out.println("Updated content of "+slugs.size()+" existing scenarios ("+roots+" roots); IDs and sessions preserved. Backup: "+backup);
            } catch(Exception e){db.rollback();throw e;}
        }
    }
}
