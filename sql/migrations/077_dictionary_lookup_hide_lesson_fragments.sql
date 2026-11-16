-- [JLAS2-34] Keep published lesson vocabulary usable in learning paths while hiding
-- conjugated forms and sentence fragments from public dictionary lookup. The lookup
-- filters TEXTBOOK scope; dictionary_status must stay PUBLISHED for linked lessons.
-- Re-runnable: only BOTH fragments are demoted, and keep-list words are promoted
-- only when no published dictionary-visible entry with that spelling exists.
SET NAMES utf8mb4;
START TRANSACTION;

UPDATE vocabulary_entry v
SET v.usage_scope = 'TEXTBOOK'
WHERE v.dictionary_status = 'PUBLISHED'
  AND v.usage_scope = 'BOTH'
  AND v.expression NOT IN (
      'いつも', '何時も', 'いつでも', '何時でも', 'だれでも', 'どこでも', '何でも', 'なにも',
      '何より', '何と', 'なんとなく', '何気ない', '何しろ', 'さようなら', 'それでも',
      '其れでも', 'それなら', 'なぜなら', 'あくまでも', 'それにしても', 'だったら',
      'ともすれば', 'そうすれば', 'どうしても', '如何しても', 'もしかしたら',
      '若しかしたら', 'やたら', 'いつまでも', '何時までも', 'それなのに',
      '然しながら', 'しかしながら', 'ながら', 'とおりに', 'そのとき', 'でも', 'とても',
      '何とか', 'なんとか', '何とも', 'なんとも', 'どうにか', 'どうにも',
      'いつのまにか', 'いつの間にか', '何で', 'なんで', '何となく', '何と無く',
      '遠く', '近く', '多く', '早く', '全く', '間もなく', '宜しく'
  )
  AND (
      v.expression LIKE '% %'
      OR v.expression LIKE '%　%'
      OR v.expression REGEXP '(たら|ったら|ても|でも|なければ|させていただく|せていただく|のに|ながら|とおりに|のとき|の時|のために|ができる|ておく|でおく)$'
      OR v.expression REGEXP '^(だれ|誰|どこ|何処|なに|なん|何|どちら|どれ|いつ|どう)(が|と|に|を|は|で|へ|から|なら|まで|までに|の|ででも|じから|じまでに|したら|すると|しますか|でしたか|でしょうか|のために)$'
      OR (
          CHAR_LENGTH(v.normalized_expression) > 2
          AND RIGHT(v.normalized_expression, 1) = 'な'
          AND v.expression NOT IN (
              'どんな', 'こんな', 'そんな', 'あんな', 'いろんな', '色んな',
              'みんな', '皆', '大きな', '小さな', 'おかしな', '可笑しな'
          )
          AND EXISTS (
              SELECT 1 FROM (
                  SELECT DISTINCT normalized_expression FROM vocabulary_entry
                  WHERE dictionary_status = 'PUBLISHED'
              ) base_na
              WHERE base_na.normalized_expression = LEFT(v.normalized_expression, CHAR_LENGTH(v.normalized_expression) - 1)
          )
      )
      OR v.expression IN (
          '寒く', '短く', 'あたたかく', 'すずしく', '楽しく', '煙たく', '煩わしく',
          'ものすごく', '思いがけなく', '元気に', '静かに', '丁寧に', '無理に',
          '大げさに', '大ざっぱに', '無愛想に', '満足に', '下品な男',
          'かわいそうな犬', '利口な犬', 'かわいそうな', '青いシャツ',
          '短いコート', '長いコート', '緑色の車', '濃い緑色の服',
          '明るい色のシャツ', '暗い色のシャツ', '放っておく', '家族と',
          'かぞくと', 'ともだちと', 'どうりょうと', 'しょくじのまえに',
          'りょこうのまえに', 'それまでに', 'そのほうが', 'その映画',
          '来週まで', '最初から', '頭が', '仕事が', '弱い力', '腕の力',
          '重いけが', '軽いけが', 'がんの疑い', '背中がかゆい',
          'のどが痛い', '足がものすごく痛い', '痛みが取れる',
          '痛くて思わず叫んだ', '痛みに耐える', 'ひどい態度にあきれる',
          'わざわざお越しいただいて', 'わりあいに早く着いた',
          'さっき着いた', 'いっぱい食べる', 'ぜひ会いたい',
          'ぜいたくな生活', 'だいたいわかる', 'ほぼ完成した',
          'ほぼ読み終わる', 'いってく', '行ってく', 'できればできたら', 'ーッ',
          'だれのために', 'なんのために', 'だれがしますか',
          'だれがおくりましたか', 'なにがあって', '何をお探しですか'
      )
  );

-- Restore standard headwords from textbook scope when they have no published
-- DICTIONARY/BOTH twin. A derived table avoids MySQL error 1093 on self-updates.
UPDATE vocabulary_entry v
SET v.usage_scope = 'BOTH'
WHERE v.dictionary_status = 'PUBLISHED'
  AND v.usage_scope = 'TEXTBOOK'
  AND v.expression IN (
      'いつも', '何時も', 'いつでも', '何時でも', 'だれでも', 'どこでも', '何でも', 'なにも',
      '何より', '何と', 'なんとなく', '何気ない', '何しろ', 'さようなら', 'それでも',
      '其れでも', 'それなら', 'なぜなら', 'あくまでも', 'それにしても', 'だったら',
      'ともすれば', 'そうすれば', 'どうしても', '如何しても', 'もしかしたら',
      '若しかしたら', 'やたら', 'いつまでも', '何時までも', 'それなのに',
      '然しながら', 'しかしながら', 'ながら', 'とおりに', 'そのとき', 'でも', 'とても',
      '何とか', 'なんとか', '何とも', 'なんとも', 'どうにか', 'どうにも',
      'いつのまにか', 'いつの間にか', '何で', 'なんで', '何となく', '何と無く',
      '遠く', '近く', '多く', '早く', '全く', '間もなく', '宜しく'
  )
  AND NOT EXISTS (
      SELECT 1 FROM (
          SELECT DISTINCT normalized_expression FROM vocabulary_entry
          WHERE dictionary_status = 'PUBLISHED' AND usage_scope <> 'TEXTBOOK'
      ) visible
      WHERE visible.normalized_expression = v.normalized_expression
  );

UPDATE vocabulary_entry
SET meaning = 'cảnh báo tuyết lớn'
WHERE normalized_expression = '大雪注意報' AND meaning = 'Suối nước nóng';

UPDATE vocabulary_entry
SET jlpt_level = 'N5', han_viet = 'GIÁNG'
WHERE normalized_expression = '降る'
  AND normalized_reading = 'ふる'
  AND dictionary_status = 'PUBLISHED';

UPDATE vocabulary_entry
SET usage_scope = 'TEXTBOOK'
WHERE expression = 'ーッ' AND usage_scope <> 'DICTIONARY';

COMMIT;

-- Read-only operator audit: these published base forms should remain searchable.
SELECT expression, normalized_expression, normalized_reading, usage_scope, dictionary_status
FROM vocabulary_entry
WHERE normalized_expression IN ('短い', '降る', '放る', '下品', '誰', '元気', '寒い')
ORDER BY normalized_expression, id;
