-- [JLAS-W0108] 27/11: wrong Smart Test grammar goes into the path notebook (commit 37777896),
-- but this check still forbade GRAMMAR rows in state NOTEBOOK, so creating a path from a Smart Test
-- result failed with "Check constraint 'chk_jpath_item_grammar_notebook' is violated" (deploy log 27/11 19:48).
-- Re-runnable: drops the check only when it still exists.
SET @has := (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
             WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'jpath_user_item'
               AND CONSTRAINT_NAME = 'chk_jpath_item_grammar_notebook');
SET @sql := IF(@has > 0, 'ALTER TABLE jpath_user_item DROP CHECK chk_jpath_item_grammar_notebook', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
