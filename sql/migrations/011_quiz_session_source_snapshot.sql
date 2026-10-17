-- [JLAS-W0108] Reading/Listening replay and review must show the passage/audio that was actually asked about,
-- so the source is frozen on the session. Existing sessions stay NULL and are reported as unavailable.

SET NAMES utf8mb4;

ALTER TABLE learning_quiz_session
    ADD COLUMN source_snapshot_json TEXT NULL AFTER answer_key_json;
