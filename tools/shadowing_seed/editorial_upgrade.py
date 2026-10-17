"""SQL fragments for a fingerprint-guarded v4 editorial upgrade in the seed transaction.

Never adopts arbitrary edited captions. Learner history and question IDs are retained;
retirement of a historical cue fails the entire import rather than cascading history.
"""

DOUBLE_START = "IF(s.start_time=FLOOR(s.start_time),CONCAT(CAST(FLOOR(s.start_time) AS CHAR),'.0'),TRIM(TRAILING '0' FROM CAST(s.start_time AS CHAR)))"
DOUBLE_END = DOUBLE_START.replace('start_time','end_time')
ROW_HASH = "SHA2(CONCAT(s.text,CHAR(31),COALESCE(s.furigana,'null'),CHAR(31),COALESCE(s.translation,'null'),CHAR(31),"+DOUBLE_START+",CHAR(31),"+DOUBLE_END+"),256)"

PREPARE = r"""
    -- [JLAS-SHADOWING-EDITORIAL-20261012] Only the accepted v4 fingerprints may
    -- be replaced. A fresh import or an already upgraded transcript needs no patch.
    CREATE TEMPORARY TABLE ep_video AS
    SELECT d.source_key,d.video_id,j.old_title,j.accepted_titles,d.title new_title,0 patch
    FROM cs_video d JOIN JSON_TABLE(@shadowing_editorial_baseline,'$[*]' COLUMNS (
        source_key VARCHAR(11) PATH '$.source_key',old_title VARCHAR(255) PATH '$.old_title',
        accepted_titles JSON PATH '$.accepted_titles' NULL ON EMPTY)) j USING(source_key);
    CREATE TEMPORARY TABLE ep_old AS
    SELECT j.* FROM JSON_TABLE(@shadowing_editorial_baseline,'$[*]' COLUMNS (
        source_key VARCHAR(11) PATH '$.source_key', NESTED PATH '$.old_cues[*]' COLUMNS (
            sort_order INT PATH '$.order',row_hash CHAR(64) PATH '$.sha256'))) j WHERE j.sort_order IS NOT NULL;
    CREATE TEMPORARY TABLE ep_new AS
    SELECT j.* FROM JSON_TABLE(@shadowing_seed_subtitles_json,'$[*]' COLUMNS (
        source_key VARCHAR(11) PATH '$.source_key', sort_order INT PATH '$.order',
        original_order INT PATH '$.original_order' NULL ON EMPTY)) j;
    UPDATE ep_new SET original_order=sort_order WHERE original_order IS NULL
        AND source_key NOT IN (SELECT source_key FROM JSON_TABLE(@shadowing_editorial_sources,'$[*]'
            COLUMNS(source_key VARCHAR(11) PATH '$')) j);
    CREATE TEMPORARY TABLE ep_new_count AS SELECT source_key,COUNT(*) c FROM cs_input GROUP BY source_key;
    CREATE TEMPORARY TABLE ep_old_count AS SELECT source_key,COUNT(*) c FROM ep_old GROUP BY source_key;
    CREATE TEMPORARY TABLE ep_state AS
    SELECT d.source_key,d.video_id,
        (SELECT COUNT(*) FROM subtitle s WHERE s.video_id=d.video_id) live_count,
        COALESCE(n.c,0) new_count,
        COALESCE(o.c,0) old_count,
        (SELECT COUNT(*) FROM subtitle s JOIN cs_input i ON i.source_key=d.source_key AND i.sort_order=s.sort_order
            WHERE s.video_id=d.video_id AND BINARY s.text=BINARY i.text
            AND BINARY COALESCE(s.furigana,'null')=BINARY COALESCE(i.furigana,'null')
            AND BINARY COALESCE(s.translation,'null')=BINARY COALESCE(i.translation,'null')
            AND s.start_time=i.start_time AND s.end_time=i.end_time) new_matches,
        (SELECT COUNT(*) FROM subtitle s JOIN ep_old o ON o.source_key=d.source_key AND o.sort_order=s.sort_order
            WHERE s.video_id=d.video_id AND BINARY {{ROW_HASH}}=BINARY o.row_hash) old_matches
    FROM cs_video d LEFT JOIN ep_new_count n USING(source_key) LEFT JOIN ep_old_count o USING(source_key);
    IF EXISTS (SELECT 1 FROM ep_state WHERE live_count>0
        AND NOT (live_count=new_count AND new_matches=new_count)
        AND NOT (live_count=old_count AND old_matches=old_count)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Editorial upgrade rejected: captions differ from accepted or reviewed transcript';
    END IF;
    UPDATE ep_video d JOIN ep_state s USING(source_key) SET d.patch=1
        WHERE s.live_count>0 AND s.live_count=s.old_count AND s.old_matches=s.old_count
        AND NOT (s.live_count=s.new_count AND s.new_matches=s.new_count);
    IF EXISTS (SELECT 1 FROM ep_video d JOIN video v ON v.id=d.video_id
        WHERE BINARY v.title<>BINARY d.new_title
        AND JSON_CONTAINS(COALESCE(d.accepted_titles,JSON_ARRAY(d.old_title)),JSON_QUOTE(v.title))=0) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Editorial upgrade rejected: a source title was independently edited';
    END IF;
    CREATE TEMPORARY TABLE ep_old_question AS
    SELECT j.*,d.video_id FROM JSON_TABLE(@shadowing_editorial_baseline,'$[*]' COLUMNS (
        source_key VARCHAR(11) PATH '$.source_key', NESTED PATH '$.questions[*]' COLUMNS (
            question_key VARCHAR(40) PATH '$.key',kind VARCHAR(40) PATH '$.kind',level_code VARCHAR(10) PATH '$.level',
            prompt TEXT PATH '$.prompt',answer TEXT PATH '$.answer',choices JSON PATH '$.choices',
            explanation TEXT PATH '$.explanation',first_order INT PATH '$.first_order',last_order INT PATH '$.last_order',
            start_time DECIMAL(10,3) PATH '$.start',end_time DECIMAL(10,3) PATH '$.end'))) j
        JOIN ep_video d USING(source_key) WHERE d.patch=1;
    CREATE TEMPORARY TABLE ep_qmap AS
    SELECT q.question_key,b.id question_id FROM ep_old_question q JOIN shadowing_question_bank b
        ON b.video_id=q.video_id AND b.kind=q.kind AND b.level_code=q.level_code
        AND BINARY b.prompt_text=BINARY q.prompt AND BINARY b.answer_text=BINARY q.answer
        AND CAST(b.choices_json AS JSON)=q.choices AND BINARY COALESCE(b.explanation,'')=BINARY q.explanation
        AND b.start_time=q.start_time AND b.end_time=q.end_time;
    SELECT COUNT(*) INTO input_count FROM ep_old_question;
    IF (SELECT COUNT(*) FROM ep_qmap)<>input_count THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Editorial upgrade rejected: existing question identities changed';
    END IF;
    IF EXISTS (SELECT question_key FROM ep_qmap GROUP BY question_key HAVING COUNT(*)<>1) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Editorial upgrade rejected: ambiguous question key';
    END IF;
    IF EXISTS (SELECT question_id FROM ep_qmap GROUP BY question_id HAVING COUNT(*)<>1) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Editorial upgrade rejected: ambiguous question row';
    END IF;
    IF (SELECT COUNT(*) FROM shadowing_question_bank b JOIN ep_video d ON d.video_id=b.video_id WHERE d.patch=1)<>input_count THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Editorial upgrade rejected: additional questions require reconciliation';
    END IF;
    CREATE TEMPORARY TABLE ep_ids AS
    SELECT n.source_key,n.sort_order,n.original_order,s.id subtitle_id
    FROM ep_new n JOIN ep_video d USING(source_key)
    LEFT JOIN subtitle s ON s.video_id=d.video_id AND s.sort_order=n.original_order WHERE d.patch=1;
    CREATE TEMPORARY TABLE ep_retired AS
    SELECT s.id FROM subtitle s JOIN ep_video d ON d.video_id=s.video_id
    LEFT JOIN ep_ids k ON k.subtitle_id=s.id WHERE d.patch=1 AND k.subtitle_id IS NULL;
    -- Discover every installed historical FK, including optional review tables.
    SELECT GROUP_CONCAT(CONCAT('EXISTS(SELECT 1 FROM `',REPLACE(table_name,'`','``'),
        '` h WHERE h.`',REPLACE(column_name,'`','``'),'`=r.id)') SEPARATOR ' OR ')
        INTO @shadowing_editorial_history_predicate FROM information_schema.key_column_usage
        WHERE referenced_table_schema=DATABASE() AND referenced_table_name='subtitle'
        AND table_name<>'shadowing_question_bank';
    SET @shadowing_editorial_history_sql=CONCAT('SELECT COUNT(*) INTO @shadowing_editorial_history_count FROM ep_retired r WHERE ',
        COALESCE(@shadowing_editorial_history_predicate,'FALSE'));
    PREPARE shadowing_editorial_history_stmt FROM @shadowing_editorial_history_sql;
    EXECUTE shadowing_editorial_history_stmt;
    DEALLOCATE PREPARE shadowing_editorial_history_stmt;
    IF @shadowing_editorial_history_count>0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Editorial upgrade rejected: retiring a cue would affect learner history';
    END IF;
    IF EXISTS (SELECT 1 FROM subtitle s JOIN ep_video d ON d.video_id=s.video_id WHERE d.patch=1 AND s.sort_order>=1000000) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Editorial upgrade rejected: temporary order range is occupied';
    END IF;
    -- Move within the unsigned order range to avoid unique-order collisions.
    UPDATE subtitle s JOIN ep_video d ON d.video_id=s.video_id SET s.sort_order=s.sort_order+1000000 WHERE d.patch=1;
    UPDATE subtitle s JOIN ep_ids k ON k.subtitle_id=s.id JOIN cs_input i ON i.source_key=k.source_key AND i.sort_order=k.sort_order
        SET s.sort_order=i.sort_order,s.text=i.text,s.furigana=i.furigana,s.translation=i.translation,
            s.start_time=i.start_time,s.end_time=i.end_time,s.ai_status='COMPLETED';
    INSERT INTO subtitle(video_id,start_time,end_time,text,furigana,translation,sort_order,ai_status)
        SELECT d.video_id,i.start_time,i.end_time,i.text,i.furigana,i.translation,i.sort_order,'COMPLETED'
        FROM ep_ids k JOIN ep_video d USING(source_key) JOIN cs_input i USING(source_key,sort_order)
        WHERE k.subtitle_id IS NULL ORDER BY d.source_key,i.sort_order;
    -- Keep question IDs while retiring unused old cues. Final evidence and hashes
    -- are resolved later in this SAME transaction; failure rolls everything back.
    UPDATE shadowing_question_bank b JOIN ep_qmap m ON m.question_id=b.id
        JOIN subtitle s ON s.video_id=b.video_id AND s.sort_order=1 SET b.subtitle_id=s.id;
    DELETE s FROM subtitle s JOIN ep_retired r ON r.id=s.id;
    UPDATE video v JOIN ep_video d ON d.video_id=v.id SET v.title=d.new_title WHERE BINARY v.title<>BINARY d.new_title;
    -- Archive the exact unplayable demo fixture while retaining its comments/audits.
    UPDATE video SET status='DRAFT',visibility='PRIVATE'
        WHERE source_url='https://example.com/demo-ramen' AND title='Luyện nghe gọi món ramen';
""".replace('{{ROW_HASH}}',ROW_HASH)

QUESTIONS = r"""
    SELECT COUNT(*) INTO input_count FROM ep_qmap;
    IF (SELECT COUNT(*) FROM cs_resolved r JOIN ep_qmap m USING(question_key))<>input_count THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Editorial question replacement did not resolve every accepted identity';
    END IF;
    UPDATE shadowing_question_bank b JOIN ep_qmap m ON m.question_id=b.id JOIN cs_resolved r USING(question_key)
        SET b.subtitle_id=r.subtitle_id,b.source_ids_json=r.source_ids_json,b.kind=r.kind,b.level_code=r.level_code,
            b.prompt_text=r.prompt_text,b.answer_text=r.answer_text,b.choices_json=r.choices_json,b.explanation=r.explanation,
            b.start_time=r.start_time,b.end_time=r.end_time,b.source_hash=r.source_hash,b.content_hash=r.content_hash;
"""

JOBS = r"""
    UPDATE shadowing_preparation_job j JOIN cs_video d ON d.video_id=j.video_id
        JOIN ep_video e ON e.video_id=d.video_id AND e.patch=1
        JOIN cs_analysis a ON a.video_id=d.video_id JOIN cs_plans p ON p.video_id=d.video_id
        SET j.levels_csv=d.job_levels,j.kinds_csv=d.job_kinds,j.status='READY',j.progress=100,
            j.targets_csv=d.job_targets,j.error_message=NULL,j.suggestions_csv=d.job_suggestions,
            j.suggestion_hash=a.full_hash,j.capacities_csv=d.job_targets,j.analysis_version='evidence-plans-v1',
            j.evidence_plans_json=p.plans_json;
"""
