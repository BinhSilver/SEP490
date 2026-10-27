package com.jlas.assessment.service.impl;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JlptWeaknessNotebookService {
    private final JdbcTemplate jdbc;

    public JlptWeaknessNotebookService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Server-checks the question and selected option; clients cannot choose an arbitrary saved word. */
    @Transactional
    public boolean recordWrongAnswer(Long userId, long questionId, int selectedOption) {
        var rows = jdbc.query("""
                SELECT q.correct_option,q.target_vocabulary_id,p.part_code
                FROM jlpt_question q
                JOIN jlpt_mondai m ON m.id=q.mondai_id
                JOIN jlpt_part p ON p.id=m.part_id
                WHERE q.id=?
                """, (rs, row) -> new Question(rs.getObject("correct_option") == null ? null : rs.getInt("correct_option"),
                rs.getObject("target_vocabulary_id") == null ? null : rs.getLong("target_vocabulary_id"),
                rs.getString("part_code")), questionId);
        if (rows.isEmpty()) return false;
        Question question = rows.getFirst();
        if (!"vocabulary".equals(question.partCode()) || question.vocabularyId() == null
                || question.correctOption() == null || question.correctOption() == selectedOption) return false;

        jdbc.update("""
                INSERT INTO user_notebook(user_id,name,notebook_type)
                SELECT ?, 'Sổ tay điểm yếu', 'WEAKNESS'
                WHERE NOT EXISTS (SELECT 1 FROM user_notebook WHERE user_id=? AND notebook_type='WEAKNESS')
                """, userId, userId);
        jdbc.update("""
                INSERT IGNORE INTO user_notebook_entry(notebook_id,vocabulary_id)
                SELECT id,? FROM user_notebook WHERE user_id=? AND notebook_type='WEAKNESS' ORDER BY id LIMIT 1
                """, question.vocabularyId(), userId);
        return true;
    }

    private record Question(Integer correctOption, Long vocabularyId, String partCode) { }
}
