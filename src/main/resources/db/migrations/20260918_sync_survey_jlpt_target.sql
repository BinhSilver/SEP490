-- Sync previously saved JLPT surveys into the existing profile goal field.
UPDATE users u
JOIN user_learning_survey s ON s.user_id = u.id
SET u.japanese_level = s.target_level
WHERE s.goal = 'JLPT' AND s.target_level IN ('N5','N4','N3','N2','N1')
  AND NOT (u.japanese_level <=> s.target_level);
