-- Read-only diagnostic for questionbank.sql error 1452.
-- Run in jlas; does not change data, foreign keys or safe updates.
USE jlas;
SELECT DATABASE() AS selected_database;
SELECT COUNT(*) AS grammar_point_count, MIN(id) AS first_id, MAX(id) AS last_id
FROM grammar_point;

-- Expected: zero rows. Any returned row is a missing textbook-seed dependency.
SELECT m.form, m.expected_grammar_point_id
FROM (
    SELECT 'かたがた' AS form, 579 AS expected_grammar_point_id
    UNION ALL SELECT 'が早いか', 576
    UNION ALL SELECT 'くらい', 410
    UNION ALL SELECT 'ことから', 445
    UNION ALL SELECT 'ことなく', 649
    UNION ALL SELECT 'ことはない', 444
    UNION ALL SELECT 'たりとも', 600
    UNION ALL SELECT 'ておきます', 158
    UNION ALL SELECT 'てみます', 219
    UNION ALL SELECT 'でも', 401
    UNION ALL SELECT 'とあって', 597
    UNION ALL SELECT 'というものだ', 484
    UNION ALL SELECT 'といえども', 638
    UNION ALL SELECT 'として', 300
    UNION ALL SELECT 'とはいえ', 636
    UNION ALL SELECT 'どころか', 447
    UNION ALL SELECT 'ながら', 145
    UNION ALL SELECT 'なら', 326
    UNION ALL SELECT 'ならでは', 571
    UNION ALL SELECT 'なり', 578
    UNION ALL SELECT 'にすぎない', 481
    UNION ALL SELECT 'について', 298
    UNION ALL SELECT 'にとって', 502
    UNION ALL SELECT 'にもかかわらず', 464
    UNION ALL SELECT 'によって', 297
    UNION ALL SELECT 'によると', 330
    UNION ALL SELECT 'に伴って', 495
    UNION ALL SELECT 'に先立って', 426
    UNION ALL SELECT 'に加えて', 551
    UNION ALL SELECT 'に反して', 463
    UNION ALL SELECT 'に基づいて', 430
    UNION ALL SELECT 'に堪えない', 567
    UNION ALL SELECT 'に対して', 299
    UNION ALL SELECT 'に応じて', 532
    UNION ALL SELECT 'に比べて', 335
    UNION ALL SELECT 'に沿って', 431
    UNION ALL SELECT 'に越したことはない', 628
    UNION ALL SELECT 'に違いない', 314
    UNION ALL SELECT 'のに', 331
    UNION ALL SELECT 'ばかりか', 449
    UNION ALL SELECT 'ばかりでなく', 530
    UNION ALL SELECT 'べく', 595
    UNION ALL SELECT 'ほど', 409
    UNION ALL SELECT 'までもない', 627
    UNION ALL SELECT 'ものの', 442
    UNION ALL SELECT 'ものを', 604
    UNION ALL SELECT 'ゆえに', 596
    UNION ALL SELECT 'わけがない', 386
    UNION ALL SELECT 'わけにはいかない', 451
    UNION ALL SELECT 'をおいて', 559
    UNION ALL SELECT 'をめぐって', 427
    UNION ALL SELECT 'をものともせず', 561
    UNION ALL SELECT 'をよそに', 560
    UNION ALL SELECT 'を問わず', 491
    UNION ALL SELECT 'を皮切りに', 610
    UNION ALL SELECT 'を踏まえて', 611
    UNION ALL SELECT '一方で', 461
    UNION ALL SELECT '予定です', 747
    UNION ALL SELECT '次第', 710
    UNION ALL SELECT 'ことにしました', 317
    UNION ALL SELECT 'ことになりました', 318
    UNION ALL SELECT 'すぎました', 351
    UNION ALL SELECT 'てあげました', 125
    UNION ALL SELECT 'わけです', 285
    UNION ALL SELECT 'わけではありません', 512
    UNION ALL SELECT 'ようにします', 286
    UNION ALL SELECT '続けています', 349
    UNION ALL SELECT 'はずです', 284
    UNION ALL SELECT '前に', 101
    UNION ALL SELECT 'てもらいました', 127
    UNION ALL SELECT 'でくれました', 126
    UNION ALL SELECT 'ようとした', 384
    UNION ALL SELECT 'せいで', 329
    UNION ALL SELECT 'てください', 81
    UNION ALL SELECT 'てもいい', 85
    UNION ALL SELECT 'てはいけません', 86
    UNION ALL SELECT 'たい', 77
    UNION ALL SELECT 'たいです', 77
    UNION ALL SELECT 'たり', 105
    UNION ALL SELECT 'だけ', 69
    UNION ALL SELECT 'があります', 60
    UNION ALL SELECT 'がいます', 61
    UNION ALL SELECT 'ことができます', 98
    UNION ALL SELECT 'ですから', 56
    UNION ALL SELECT 'なければなりません', 94
    UNION ALL SELECT 'いちばん', 72
    UNION ALL SELECT 'より', 71
    UNION ALL SELECT 'もう', 45
) m
LEFT JOIN grammar_point gp ON gp.id = m.expected_grammar_point_id
WHERE gp.id IS NULL
ORDER BY m.expected_grammar_point_id, m.form;

-- Existing links must not be orphaned.
SELECT q.id AS question_id, q.grammar_point_id
FROM assessment_question q
LEFT JOIN grammar_point gp ON gp.id = q.grammar_point_id
WHERE q.grammar_point_id IS NOT NULL AND gp.id IS NULL;

