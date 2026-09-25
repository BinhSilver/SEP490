-- Removes the original 20-question ceiling. The available eligible cards are the natural maximum.
-- MySQL 8+. Apply after 001_flashcard_speed_recall.sql on an existing database.
ALTER TABLE flashcard_speed_recall_session
    DROP CHECK chk_sr_total,
    ADD CONSTRAINT chk_sr_total CHECK (total_questions >= 1);
