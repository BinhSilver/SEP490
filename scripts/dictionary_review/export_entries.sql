SELECT e.id, e.expression, e.normalized_expression, e.reading, e.normalized_reading,
       REPLACE(REPLACE(IFNULL(e.meaning,''), '\t', ' '), '\n', ' '),
       IFNULL((SELECT GROUP_CONCAT(DISTINCT p.code) FROM vocabulary_sense s JOIN sense_part_of_speech sp ON sp.sense_id = s.id
               JOIN dictionary_part_of_speech p ON p.id = sp.part_of_speech_id WHERE s.vocabulary_id = e.id), '')
FROM vocabulary_entry e WHERE e.dictionary_status = 'PUBLISHED' ORDER BY e.id;
