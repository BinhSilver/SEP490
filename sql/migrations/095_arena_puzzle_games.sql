-- [JLAS-1095] Arena games with a JLPT content bank and unscored drawing sessions.
SET @arena_type_check = (SELECT CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'chk_game_match_type');
SET @arena_type_sql = CASE
    WHEN @arena_type_check LIKE '%DRAW_GUESS%' THEN 'DO 0'
    WHEN @arena_type_check IS NULL THEN 'ALTER TABLE game_match ADD CONSTRAINT chk_game_match_type CHECK (game_type IN (''CANNON_BATTLE'',''MEMORY_MATCH'',''AIR_DEFENSE'',''NEKO_CATCH'',''SPHINX_RIDDLE'',''PICTURE_REBUS'',''DRAW_GUESS''))'
    ELSE 'ALTER TABLE game_match DROP CHECK chk_game_match_type, ADD CONSTRAINT chk_game_match_type CHECK (game_type IN (''CANNON_BATTLE'',''MEMORY_MATCH'',''AIR_DEFENSE'',''NEKO_CATCH'',''SPHINX_RIDDLE'',''PICTURE_REBUS'',''DRAW_GUESS''))'
END;
PREPARE arena_type_stmt FROM @arena_type_sql;
EXECUTE arena_type_stmt;
DEALLOCATE PREPARE arena_type_stmt;

SET @arena_set_nullable = (SELECT IS_NULLABLE FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'game_match' AND COLUMN_NAME = 'flashcard_set_id');
SET @arena_set_sql = IF(@arena_set_nullable = 'NO',
    'ALTER TABLE game_match MODIFY flashcard_set_id BIGINT UNSIGNED NULL', 'DO 0');
PREPARE arena_set_stmt FROM @arena_set_sql;
EXECUTE arena_set_stmt;
DEALLOCATE PREPARE arena_set_stmt;

SET @arena_result_check = (SELECT CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'chk_game_match_player_result');
SET @arena_result_sql = CASE
    WHEN @arena_result_check LIKE '%COMPLETED%' THEN 'DO 0'
    WHEN @arena_result_check IS NULL THEN 'ALTER TABLE game_match_player ADD CONSTRAINT chk_game_match_player_result CHECK (result IN (''PENDING'',''WIN'',''LOSE'',''DRAW'',''FORFEIT'',''COMPLETED''))'
    ELSE 'ALTER TABLE game_match_player DROP CHECK chk_game_match_player_result, ADD CONSTRAINT chk_game_match_player_result CHECK (result IN (''PENDING'',''WIN'',''LOSE'',''DRAW'',''FORFEIT'',''COMPLETED''))'
END;
PREPARE arena_result_stmt FROM @arena_result_sql;
EXECUTE arena_result_stmt;
DEALLOCATE PREPARE arena_result_stmt;
