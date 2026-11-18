package com.jlas.jlptpath.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.flashcard.dto.FlashcardDtos.CardInput;
import com.jlas.flashcard.dto.FlashcardDtos.FolderDto;
import com.jlas.flashcard.dto.FlashcardDtos.FolderRequest;
import com.jlas.flashcard.dto.FlashcardDtos.SetRequest;
import com.jlas.flashcard.dto.FlashcardDtos.StudySummaryDto;
import com.jlas.flashcard.service.FlashcardService;
import com.jlas.jlptpath.entity.JpathKanji;
import com.jlas.jlptpath.entity.JpathVocabulary;
import com.jlas.jlptpath.enums.JpathTab;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] Owner 18/11: path words are studied with the team's flashcard feature.
 *
 * <p>JLPT parts are per learner, so each learner gets one PRIVATE set per part, in the folder
 * "Lộ trình JLPT · N5". A part is found again by the hash of its item codes, so a part studied ahead
 * (button) keeps the same set when it becomes today's part. Today's set is made without a button
 * when the path or the flashcard page opens. The flashcard progress is only shown ("Đã nhớ x/y");
 * the path's own check still decides when the part is done.
 */
@Service
public class JpathFlashcardService {

    public record PartSet(int index, String status, Long setId, int remembered, int total, String url) {
    }

    public record PartSets(String tab, String level, List<PartSet> parts) {
    }

    static final String TABLE_DDL = "CREATE TABLE IF NOT EXISTS learning_path_flashcard_set ("
            + " id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, path_kind VARCHAR(8) NOT NULL,"
            + " tab VARCHAR(16) NOT NULL, level VARCHAR(8) NULL, codes_hash CHAR(64) NOT NULL,"
            + " set_id BIGINT NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
            + " CONSTRAINT uq_lp_fc_set UNIQUE (user_id, path_kind, codes_hash))";

    private final JdbcTemplate db;
    private final FlashcardService flashcards;
    private final JpathPartsService parts;
    private final JpathContentReader content;
    private volatile boolean tableReady;

    public JpathFlashcardService(JdbcTemplate db, FlashcardService flashcards, JpathPartsService parts,
            JpathContentReader content) {
        this.db = db;
        this.flashcards = flashcards;
        this.parts = parts;
        this.content = content;
    }

    /** Sets that already exist for the parts of one tab, with their flashcard progress; makes today's set. */
    @Transactional
    public PartSets sets(Long userId, JpathTab tab) {
        JpathPartsService.Parts p = parts.parts(userId, requireTab(tab));
        List<PartSet> out = new ArrayList<>();
        for (JpathPartsService.Part part : p.parts()) {
            if (part.items().isEmpty()) continue;
            boolean today = part.status().startsWith("TODAY");
            Long setId = today ? ensure(userId, tab, p.level(), part) : existing(userId, hash(part));
            out.add(view(userId, part, setId));
        }
        return new PartSets(tab.name(), p.level(), out);
    }

    /** "Học bằng flashcard" on one part (learned or upcoming): its set, made now if needed. */
    @Transactional
    public PartSet open(Long userId, JpathTab tab, int index) {
        JpathPartsService.Parts p = parts.parts(userId, requireTab(tab));
        JpathPartsService.Part part = p.parts().stream().filter(x -> x.index() == index).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "Không tìm thấy phần học này."));
        if (part.items().isEmpty()) throw new ApiException(ErrorCode.VALIDATION_FAILED, "Phần này chưa có từ.");
        return view(userId, part, ensure(userId, tab, p.level(), part));
    }

    private static JpathTab requireTab(JpathTab tab) {
        if (tab != JpathTab.VOCAB && tab != JpathTab.KANJI)
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Chỉ từ vựng và Kanji có flashcard.");
        return tab;
    }

    private PartSet view(Long userId, JpathPartsService.Part part, Long setId) {
        if (setId == null) return new PartSet(part.index(), part.status(), null, 0, part.items().size(), null);
        StudySummaryDto s = flashcards.summary(setId, userId);
        return new PartSet(part.index(), part.status(), setId, s.getRemembered(), s.getTotal(),
                "/flashcard/study.html?setId=" + setId + "&from=jlpt");
    }

    private Long ensure(Long userId, JpathTab tab, String level, JpathPartsService.Part part) {
        String h = hash(part);
        Long id = existing(userId, h);
        if (id != null) return id;
        SetRequest req = new SetRequest();
        req.setTitle(title(tab, level, part));
        req.setDescription("Bộ thẻ của lộ trình JLPT, tạo tự động theo phần học.");
        req.setVisibility("PRIVATE");
        req.setFolderId(folder(userId, "Lộ trình JLPT · " + (level == null ? "" : level)));
        req.setCards(cards(tab, level, part));
        Long setId = flashcards.create(req, userId).getId();
        db.update("DELETE FROM learning_path_flashcard_set WHERE user_id = ? AND path_kind = 'JLPT' AND codes_hash = ?", userId, h);
        db.update("INSERT INTO learning_path_flashcard_set (user_id, path_kind, tab, level, codes_hash, set_id) VALUES (?,?,?,?,?,?)",
                userId, "JLPT", tab.name(), level, h, setId);
        return setId;
    }

    /** The set made for these codes, unless the learner deleted it in the flashcard library. */
    private Long existing(Long userId, String h) {
        ready();
        List<Long> ids = db.queryForList("SELECT m.set_id FROM learning_path_flashcard_set m JOIN flashcard_set s ON s.id = m.set_id"
                + " WHERE m.user_id = ? AND m.path_kind = 'JLPT' AND m.codes_hash = ? AND s.deleted_at IS NULL", Long.class, userId, h);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private Long folder(Long userId, String name) {
        for (FolderDto f : flashcards.folders(userId)) {
            if (name.equals(f.getName())) return f.getId();
        }
        return flashcards.createFolder(new FolderRequest(name), userId).getId();
    }

    static String title(JpathTab tab, String level, JpathPartsService.Part part) {
        String what = tab == JpathTab.KANJI ? "Kanji" : "Từ vựng";
        String when = part.day() != null ? " · " + String.format("%02d/%02d", part.day().getDayOfMonth(), part.day().getMonthValue()) : "";
        return what + " " + (level == null ? "" : level) + " · Phần " + part.index() + when;
    }

    /** Card faces by type (owner 18/11): word → reading, meaning, Hán Việt; kanji → Hán Việt, on/kun, meaning. */
    private List<CardInput> cards(JpathTab tab, String level, JpathPartsService.Part part) {
        List<String> codes = part.items().stream().map(JpathPartsService.Item::code).toList();
        List<CardInput> out = new ArrayList<>();
        int pos = 0;
        if (tab == JpathTab.KANJI) {
            Map<String, JpathKanji> byCode = new LinkedHashMap<>();
            content.kanjis(codes).forEach(k -> byCode.put(k.getCode(), k));
            for (String c : codes) {
                JpathKanji k = byCode.get(c);
                if (k == null || k.getCharacter() == null) continue;
                CardInput card = new CardInput();
                card.setFront(k.getCharacter());
                String on = list(k.getReadingsOnJson()), kun = list(k.getReadingsKunJson());
                card.setReading(cut((on.isEmpty() ? "" : "Âm On: " + on) + (on.isEmpty() || kun.isEmpty() ? "" : " · ")
                        + (kun.isEmpty() ? "" : "Âm Kun: " + kun), 255));
                card.setMeaning(cut(nonBlank(k.getMeaningsVi(), k.getHanViet(), k.getCharacter()), 500));
                card.setHanViet(k.getHanViet() == null ? null : cut(k.getHanViet().toUpperCase(java.util.Locale.ROOT), 255));
                card.setJlptLevel(level);
                card.setPosition(pos++);
                out.add(card);
            }
        } else {
            Map<String, JpathVocabulary> byCode = new LinkedHashMap<>();
            content.vocabs(codes).forEach(v -> byCode.put(v.getCode(), v));
            for (String c : codes) {
                JpathVocabulary v = byCode.get(c);
                if (v == null || v.getExpression() == null) continue;
                CardInput card = new CardInput();
                card.setFront(v.getExpression());
                card.setReading(v.getReading() == null || v.getReading().equals(v.getExpression()) ? null : cut(v.getReading(), 255));
                card.setMeaning(cut(nonBlank(v.getMeaningVi(), v.getExpression()), 500));
                card.setWordType(v.getWordTypeLabel());
                card.setJlptLevel(level);
                card.setPosition(pos++);
                out.add(card);
            }
        }
        if (out.isEmpty()) throw new ApiException(ErrorCode.VALIDATION_FAILED, "Phần này chưa có từ để làm thẻ.");
        return out;
    }

    static String hash(JpathPartsService.Part part) {
        TreeSet<String> sorted = new TreeSet<>();
        part.items().forEach(i -> sorted.add(i.code()));
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(String.join(",", sorted).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** ["ニチ","ジツ"] → ニチ、ジツ */
    static String list(String json) {
        if (json == null) return "";
        return json.replaceAll("[\\[\\]\"]", "").replace(",", "、").trim();
    }

    private static String nonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return "";
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
