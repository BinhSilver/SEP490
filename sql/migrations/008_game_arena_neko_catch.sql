-- Game Arena: Neko Catch becomes a third game type (matches read their cards from flashcard_set).
-- jlas_schema.sql already declares the new list; this brings existing databases in line. MySQL 8+. Run once.
ALTER TABLE game_match
    DROP CHECK chk_game_match_type,
    ADD CONSTRAINT chk_game_match_type
        CHECK (game_type IN ('CANNON_BATTLE','MEMORY_MATCH','AIR_DEFENSE','NEKO_CATCH'));
