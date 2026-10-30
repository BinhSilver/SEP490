package com.jlas.kaiwa.config;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Keeps the small, feature-essential AI Call dictionary/background dataset
 * available even when an existing development database is not re-imported.
 */
@Component
public class AiCallReferenceDataInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AiCallReferenceDataInitializer.class);

    private static final List<Phrase> PHRASES = List.of(
            p("いらっしゃいませ", "いらっしゃいませ", "Xin chào quý khách", "PHRASE", "N5",
                    "いらっしゃいませ。何名様ですか。", "Xin chào quý khách. Quý khách có mấy người ạ?"),
            p("何名様", "なんめいさま", "quý khách có mấy người", "PHRASE", "N4",
                    "何名様ですか。", "Quý khách có mấy người ạ?"),
            p("はじめまして", "はじめまして", "rất vui được gặp lần đầu", "PHRASE", "N5",
                    "はじめまして。ミンです。", "Rất vui được gặp bạn. Tôi là Minh."),
            p("お名前は何ですか", "おなまえはなんですか", "bạn tên là gì", "PHRASE", "N5",
                    "お名前は何ですか。", "Bạn tên là gì?"),
            p("どうされましたか", "どうされましたか", "bạn cần hỗ trợ gì / bạn thấy thế nào", "PHRASE", "N4",
                    "はい、どうされましたか。", "Vâng, bạn cần hỗ trợ gì ạ?"),
            p("渋谷駅", "しぶやえき", "ga Shibuya", "PROPER_NOUN", "N5",
                    "渋谷駅に行きたいです。", "Tôi muốn đến ga Shibuya."),
            p("山手線", "やまのてせん", "tuyến Yamanote", "PROPER_NOUN", "N4",
                    "山手線に乗ります。", "Tôi đi tuyến Yamanote."),
            p("何番線", "なんばんせん", "sân ga số mấy", "PHRASE", "N4",
                    "何番線ですか。", "Sân ga số mấy ạ?"),
            p("温めますか", "あたためますか", "có hâm nóng không", "PHRASE", "N4",
                    "お弁当、温めますか。", "Bạn có muốn hâm nóng cơm hộp không?"),
            p("レジ袋", "レジぶくろ", "túi đựng hàng tại quầy", "NOUN", "N4",
                    "レジ袋はご利用ですか。", "Bạn có dùng túi đựng hàng không?"),
            p("何をお探しですか", "なにをおさがしですか", "bạn đang tìm gì", "PHRASE", "N4",
                    "何をお探しですか。", "Bạn đang tìm gì ạ?"),
            p("試着してもいいですか", "しちゃくしてもいいですか", "tôi thử đồ được không", "PHRASE", "N4",
                    "このジャケットを試着してもいいですか。", "Tôi thử chiếc áo khoác này được không?"),
            p("今日はどうされましたか", "きょうはどうされましたか", "hôm nay bạn gặp vấn đề gì", "PHRASE", "N4",
                    "今日はどうされましたか。", "Hôm nay bạn gặp vấn đề gì?"),
            p("喉が痛い", "のどがいたい", "đau họng", "PHRASE", "N4",
                    "昨日から喉が痛いです。", "Tôi đau họng từ hôm qua."),
            p("昨日から", "きのうから", "từ hôm qua", "PHRASE", "N5",
                    "昨日からせきが出ます。", "Tôi bị ho từ hôm qua."),
            p("どのような症状ですか", "どのようなしょうじょうですか", "bạn có triệu chứng như thế nào", "PHRASE", "N3",
                    "どのような症状ですか。", "Bạn có triệu chứng như thế nào?"),
            p("一日三回", "いちにちさんかい", "ba lần một ngày", "PHRASE", "N4",
                    "一日三回、食後に飲んでください。", "Hãy uống ba lần một ngày sau bữa ăn."),
            p("明日の会議", "あしたのかいぎ", "cuộc họp ngày mai", "PHRASE", "N4",
                    "明日の会議は二時からです。", "Cuộc họp ngày mai bắt đầu lúc hai giờ."),
            p("午後三時", "ごごさんじ", "ba giờ chiều", "TIME_PHRASE", "N5",
                    "午後三時はいかがですか。", "Ba giờ chiều được không?"),
            p("大丈夫ですか", "だいじょうぶですか", "có ổn không", "PHRASE", "N5",
                    "二時からで大丈夫ですか。", "Bắt đầu từ hai giờ có ổn không?"),
            p("体調が悪い", "たいちょうがわるい", "tình trạng sức khỏe không tốt", "PHRASE", "N4",
                    "今日は体調が悪いです。", "Hôm nay tôi không khỏe."),
            p("宿題を教えてください", "しゅくだいをおしえてください", "hãy cho tôi biết bài tập", "PHRASE", "N5",
                    "宿題を教えてください。", "Hãy cho tôi biết bài tập."),
            p("どちらさまですか", "どちらさまですか", "xin hỏi ai đang gọi", "PHRASE", "N4",
                    "はい、どちらさまですか。", "Vâng, xin hỏi ai đang gọi?"),
            p("ご予約はありますか", "ごよやくはありますか", "bạn có đặt trước không", "PHRASE", "N4",
                    "ご予約はありますか。", "Bạn có đặt phòng trước không?"),
            p("二泊", "にはく", "hai đêm lưu trú", "COUNTER_PHRASE", "N4",
                    "二泊です。", "Tôi ở hai đêm."),
            p("朝食は何時ですか", "ちょうしょくはなんじですか", "bữa sáng lúc mấy giờ", "PHRASE", "N4",
                    "朝食は何時ですか。", "Bữa sáng lúc mấy giờ?")
    );

    private final JdbcTemplate jdbcTemplate;

    public AiCallReferenceDataInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            seedScenarioBackgrounds();
            seedDictionaryPhrases();
            log.info("AI Call reference data ready: {} dictionary phrases", PHRASES.size());
        } catch (Exception e) {
            // Reference data should not prevent the whole application from starting.
            log.warn("AI Call reference data initialization skipped: {}", e.getMessage());
        }
    }

    private void seedScenarioBackgrounds() {
        jdbcTemplate.update("""
                UPDATE scenario
                SET thumbnail_url = CONCAT('/images/scenarios/', slug, '.png')
                WHERE slug IN (
                    'restaurant-order-and-pay', 'first-meeting-introduction',
                    'station-directions-and-transfer', 'convenience-store-checkout',
                    'clothes-shopping-size-fitting', 'clinic-appointment',
                    'pharmacy-cold-medicine', 'office-reschedule-meeting',
                    'school-absence-and-homework', 'hotel-check-in'
                )
                  AND (thumbnail_url IS NULL OR thumbnail_url = '')
                """);
    }

    private void seedDictionaryPhrases() {
        String sql = """
                INSERT INTO vocabulary_entry
                    (expression, normalized_expression, reading, normalized_reading, meaning,
                     word_type, jlpt_level, example_ja, example_vi, usage_scope, dictionary_status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'BOTH', 'PUBLISHED')
                ON DUPLICATE KEY UPDATE
                    reading = VALUES(reading), normalized_reading = VALUES(normalized_reading),
                    meaning = VALUES(meaning), word_type = VALUES(word_type),
                    jlpt_level = VALUES(jlpt_level), example_ja = VALUES(example_ja),
                    example_vi = VALUES(example_vi), usage_scope = 'BOTH',
                    dictionary_status = 'PUBLISHED'
                """;
        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                Phrase phrase = PHRASES.get(index);
                statement.setString(1, phrase.expression());
                statement.setString(2, phrase.expression());
                statement.setString(3, phrase.reading());
                statement.setString(4, phrase.reading());
                statement.setString(5, phrase.meaning());
                statement.setString(6, phrase.wordType());
                statement.setString(7, phrase.jlptLevel());
                statement.setString(8, phrase.exampleJa());
                statement.setString(9, phrase.exampleVi());
            }

            @Override
            public int getBatchSize() {
                return PHRASES.size();
            }
        });
    }

    private static Phrase p(String expression, String reading, String meaning, String wordType,
                            String jlptLevel, String exampleJa, String exampleVi) {
        return new Phrase(expression, reading, meaning, wordType, jlptLevel, exampleJa, exampleVi);
    }

    private record Phrase(String expression, String reading, String meaning, String wordType,
                          String jlptLevel, String exampleJa, String exampleVi) {
    }
}
