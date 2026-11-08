-- [JLAS-W0210] Remove retired AI Call preparation metadata.
-- Safe to rerun. Conversation samples, learning vocabulary/grammar, IDs and sessions stay intact.
SET NAMES utf8mb4;

UPDATE scenario
SET flow_json = JSON_REMOVE(flow_json, '$.hints', '$.skillTransfers')
WHERE JSON_CONTAINS_PATH(flow_json, 'one', '$.hints', '$.skillTransfers');

UPDATE prompt_template
SET system_prompt = REPLACE(REPLACE(REPLACE(REPLACE(system_prompt,
    ' Lưu ý văn hóa: {{culture_note}}.', ''),
    '- Culture note: {{culture_note}}', ''),
    '{{culture_note}}', ''), '{{hints}}', '')
WHERE system_prompt LIKE '%{{culture_note}}%' OR system_prompt LIKE '%{{hints}}%';

-- The compact schema stores the retired resources in flow_json and a scalar column.
SET @drop_culture_note = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'scenario' AND column_name = 'culture_note'),
    'ALTER TABLE scenario DROP COLUMN culture_note', 'SELECT 1');
PREPARE cleanup_column FROM @drop_culture_note;
EXECUTE cleanup_column;
DEALLOCATE PREPARE cleanup_column;
