package com.jlas.cpath;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.flashcard.dto.FlashcardDtos.CardInput;
import com.jlas.flashcard.dto.FlashcardDtos.FolderDto;
import com.jlas.flashcard.dto.FlashcardDtos.FolderRequest;
import com.jlas.flashcard.dto.FlashcardDtos.SetRequest;
import com.jlas.flashcard.dto.FlashcardDtos.StudySummaryDto;
import com.jlas.flashcard.service.FlashcardService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] Owner 18/11: a communication situation's phrases and words are studied with the team flashcard.
 * One private set per learner per situation (like the JLPT parts, so the team's access rule stays as it is), in the
 * folder "Lộ trình giao tiếp · goal"; only learners with a path of that goal get one.
 *
 * <p>Owner 24/11 Q1: the flashcard counts in the path. Once every card of the set has a grade (✓/✗ or 0–5), the
 * situation's step 1 (Từ và mẫu câu) is done, like finishing it on the situation page; the later steps and the
 * checks stay as they are.
 */
@Service
public class CpathFlashcardService {

    private static final String TABLE_DDL = "CREATE TABLE IF NOT EXISTS learning_path_flashcard_set ("
            + " id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, path_kind VARCHAR(8) NOT NULL,"
            + " tab VARCHAR(16) NOT NULL, level VARCHAR(8) NULL, codes_hash CHAR(64) NOT NULL,"
            + " set_id BIGINT NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
            + " CONSTRAINT uq_lp_fc_set UNIQUE (user_id, path_kind, codes_hash))";

    private final JdbcTemplate db;
    private final FlashcardService flashcards;
    private volatile boolean tableReady;
    private CpathService paths;
    private org.springframework.transaction.PlatformTransactionManager txManager;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTxManager(org.springframework.transaction.PlatformTransactionManager txManager) {
        this.txManager = txManager;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setPaths(@org.springframework.context.annotation.Lazy CpathService paths) {
        this.paths = paths;
    }

    public CpathFlashcardService(JdbcTemplate db, FlashcardService flashcards) {
        this.db = db;
        this.flashcards = flashcards;
    }

    /** The situation's set and "Đã nhớ x/y"; {@code create} makes it when missing. */
    @Transactional
    public Map<String, Object> set(Long userId, String situation, boolean create) {
        List<Map<String, Object>> rows = db.queryForList("SELECT s.code, s.title_vi, s.topic_code, t.goal_code, g.name_vi goal_name"
                + " FROM cpath_situation s JOIN cpath_topic t ON t.code = s.topic_code LEFT JOIN cpath_goal g ON g.code = t.goal_code"
                + " WHERE s.code = ?", situation);
        if (rows.isEmpty()) throw new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy tình huống.");
        Map<String, Object> s = rows.get(0);
        Integer paths = db.queryForObject("SELECT COUNT(*) FROM cpath_user_path WHERE user_id = ? AND goal_code = ?",
                Integer.class, userId, s.get("goal_code"));
        if (paths == null || paths == 0) throw new ApiException(ErrorCode.NOT_FOUND, "Bạn chưa có lộ trình giao tiếp này.");
        ready();
        String key = "COMM:" + situation;
        List<Long> ids = db.queryForList("SELECT m.set_id FROM learning_path_flashcard_set m JOIN flashcard_set f ON f.id = m.set_id"
                + " WHERE m.user_id = ? AND m.path_kind = 'COMM' AND m.codes_hash = ? AND f.deleted_at IS NULL", Long.class, userId, key);
        Long setId = ids.isEmpty() ? null : ids.get(0);
        if (setId == null && create) setId = make(userId, s, key);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("situation", situation);
        out.put("setId", setId);
        if (setId != null) {
            out.put("wordsStepDone", syncWordsStep(userId, situation, setId));
            StudySummaryDto sum = flashcards.summary(setId, userId);
            out.put("remembered", sum.getRemembered());
            out.put("total", sum.getTotal());
            out.put("url", "/flashcard/study.html?setId=" + setId + "&from=comm&studyMode=RECALL&learningPath=1");
        }
        return out;
    }

    /** Q1: every card graded → step 1 done (once); a situation the learner may not start yet is left alone. */
    boolean syncWordsStep(Long userId, String situation, Long setId) {
        List<String> steps = db.queryForList("SELECT us.steps_done FROM cpath_user_situation us JOIN cpath_user_path p ON p.id = us.path_id"
                + " WHERE p.user_id = ? AND us.situation_code = ?", String.class, userId, situation);
        if (steps.stream().anyMatch(CpathFlashcardService::hasWords)) return true;
        Integer open = db.queryForObject("SELECT COUNT(*) FROM flashcard_card c LEFT JOIN flashcard_card_progress p"
                + " ON p.card_id = c.id AND p.user_id = ? WHERE c.set_id = ? AND (p.last_reviewed_at IS NULL OR p.last_quality IS NULL)",
                Integer.class, userId, setId);
        Integer cards = db.queryForObject("SELECT COUNT(*) FROM flashcard_card WHERE set_id = ?", Integer.class, setId);
        if (paths == null || txManager == null || open == null || open > 0 || cards == null || cards == 0) return false;
        try {
            // own transaction: a refused step (situation locked for now) must not roll back this read
            org.springframework.transaction.support.TransactionTemplate tx =
                    new org.springframework.transaction.support.TransactionTemplate(txManager);
            tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx.executeWithoutResult(st -> paths.step(userId, situation, "WORDS", null));
            return true;
        } catch (ApiException e) {
            return false; // not a situation the learner can start now: the flashcard stays free study
        }
    }

    static boolean hasWords(String stepsDone) {
        if (stepsDone == null) return false;
        for (String x : stepsDone.split(",")) if ("WORDS".equals(x.trim())) return true;
        return false;
    }

    private Long make(Long userId, Map<String, Object> s, String key) {
        List<Map<String, Object>> items = new ArrayList<>(db.queryForList(
                "SELECT ja, reading, vi FROM cpath_item WHERE kind = 'PHRASE' AND situation_code = ? ORDER BY sort_order", s.get("code")));
        items.addAll(db.queryForList(
                "SELECT ja, reading, vi FROM cpath_item WHERE kind = 'WORD' AND topic_code = ? ORDER BY sort_order", s.get("topic_code")));
        List<CardInput> cards = new ArrayList<>();
        int pos = 0;
        for (Map<String, Object> i : items) {
            String ja = str(i.get("ja")), vi = str(i.get("vi"));
            String reading = str(i.get("reading")).replaceAll("\\{([^|{}]+)\\|([^{}]+)\\}", "$2");
            if (ja.isBlank() || vi.isBlank()) continue;
            CardInput c = new CardInput();
            c.setFront(cut(ja, 500));
            c.setReading(reading.isBlank() || reading.equals(ja) ? null : cut(reading, 255));
            c.setMeaning(cut(vi, 500));
            c.setPosition(pos++);
            cards.add(c);
        }
        if (cards.isEmpty()) throw new ApiException(ErrorCode.VALIDATION_FAILED, "Tình huống này chưa có câu để làm thẻ.");
        String goal = str(s.get("goal_name")).isBlank() ? str(s.get("goal_code")) : str(s.get("goal_name"));
        SetRequest req = new SetRequest();
        // bug 28: the learner sees the situation title only, never the internal code (TRV-01)
        req.setTitle(cut(str(s.get("title_vi")).isBlank() ? "Tình huống giao tiếp" : str(s.get("title_vi")), 100));
        req.setDescription("Bộ thẻ của lộ trình giao tiếp, tạo tự động theo tình huống.");
        req.setVisibility("PRIVATE");
        req.setFolderId(folder(userId, cut("Lộ trình giao tiếp · " + goal, 100)));
        req.setCards(cards);
        Long setId = flashcards.create(req, userId).getId();
        db.update("DELETE FROM learning_path_flashcard_set WHERE user_id = ? AND path_kind = 'COMM' AND codes_hash = ?", userId, key);
        db.update("INSERT INTO learning_path_flashcard_set (user_id, path_kind, tab, level, codes_hash, set_id) VALUES (?,?,?,?,?,?)",
                userId, "COMM", "SITUATION", null, key, setId);
        return setId;
    }

    private Long folder(Long userId, String name) {
        for (FolderDto f : flashcards.folders(userId)) {
            if (name.equals(f.getName())) return f.getId();
        }
        return flashcards.createFolder(new FolderRequest(name), userId).getId();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private void ready() {
        if (!tableReady) {
            db.execute(TABLE_DDL);
            tableReady = true;
        }
    }
}
