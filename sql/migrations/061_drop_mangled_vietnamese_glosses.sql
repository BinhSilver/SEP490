-- JLAS2-34: xoa 22 nghia Mazii bi luu sai ma hoa (chu co dau thanh "?", vd. "l? xo" ben canh
-- "lò xo") cua 17 muc tu. Moi sense chi co dung 1 gloss do, khong co tu loai/linh vuc.
-- Id lay tu seed jlas-dictionary-test-seed-20261016 (giong nhau tren moi may nap seed). Importer
-- da loc cac nghia nay (yomitan.drop_mangled_meanings) nen import lai khong sinh ra nua.
-- Chay lai an toan: lan sau khong con dong nao khop.

START TRANSACTION;

DELETE FROM vocabulary_gloss
WHERE sense_id IN (2008676, 2008678, 2008684, 2013512, 2013514, 2013634, 2013636, 2013689, 2013691, 2013721, 2013723, 2019563, 2035550, 2041306, 2074964, 2095975, 2095978, 2128876, 2128878, 2324940, 2324948, 2325049)
  AND gloss_text LIKE '%?%';

DELETE s FROM vocabulary_sense s
WHERE s.id IN (2008676, 2008678, 2008684, 2013512, 2013514, 2013634, 2013636, 2013689, 2013691, 2013721, 2013723, 2019563, 2035550, 2041306, 2074964, 2095975, 2095978, 2128876, 2128878, 2324940, 2324948, 2325049)
  AND NOT EXISTS (SELECT 1 FROM vocabulary_gloss g WHERE g.sense_id = s.id);

-- Danh so lai sense_order lien tuc (1, 2, 3...); cong 1000 truoc de khong dung uk_vocabulary_sense_order.
UPDATE vocabulary_sense SET sense_order = sense_order + 1000
WHERE vocabulary_id IN (632376, 632380, 634922, 635001, 635038, 635060, 638890, 650733, 655311, 681358, 699010, 699011, 727428, 727429, 890338, 890341, 890411);

UPDATE vocabulary_sense s
JOIN (SELECT id, ROW_NUMBER() OVER (PARTITION BY vocabulary_id ORDER BY sense_order) AS rn
      FROM vocabulary_sense WHERE vocabulary_id IN (632376, 632380, 634922, 635001, 635038, 635060, 638890, 650733, 655311, 681358, 699010, 699011, 727428, 727429, 890338, 890341, 890411)) r ON r.id = s.id
SET s.sense_order = r.rn;

-- Tom tat meaning = 3 nghia dau noi bang "; " (nhu importer, toi da 500 ky tu).
UPDATE vocabulary_entry e
JOIN (SELECT s.vocabulary_id,
             GROUP_CONCAT(g.gloss_text ORDER BY s.sense_order, g.id SEPARATOR '; ') AS summary
      FROM vocabulary_sense s JOIN vocabulary_gloss g ON g.sense_id = s.id
      WHERE s.vocabulary_id IN (632376, 632380, 634922, 635001, 635038, 635060, 638890, 650733, 655311, 681358, 699010, 699011, 727428, 727429, 890338, 890341, 890411) AND s.sense_order <= 3
      GROUP BY s.vocabulary_id) m ON m.vocabulary_id = e.id
SET e.meaning = IF(CHAR_LENGTH(m.summary) > 500, CONCAT(LEFT(m.summary, 499), '…'), m.summary);

COMMIT;
