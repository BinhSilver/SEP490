-- J-LAS curated Shadowing seed, MySQL 8.0.21+, UTF-8.
-- GENERATED: edit data/shadowing/*.json and run tools/shadowing_seed/build_seed.py.
-- {{VIDEO_COUNT}} sources / {{SERIES_COUNT}} curated lists / {{MEMBERSHIP_COUNT}} memberships / {{QUESTION_COUNT}} original questions.
-- Owner explicitly requested approved status: questions READY; moderation SAFE
-- records MANUAL approval, not a claimed automated scan. No paid API is called.
-- Optional development/demo engagement is a separate self-contained companion:
-- sql/seeds/seed_data_shadowing_interactions.sql (sqlrun/05_shadowing_interactions.sql).
-- Content import never silently creates fictional learner activity on production.
-- Run in a NEW connection, after the current jlas_schema.sql / deployed migrations.
-- Required session inputs, supplied by import_seed.py:
--   @shadowing_seed_owner_id: existing users.id (never creates an account).
-- Optional @shadowing_seed_subtitles_json: fingerprint-matched override of the {{CUE_COUNT}} embedded cues.
-- Optional @shadowing_seed_media_json: [{source_key,video_path,thumbnail_path}].
-- Optional @shadowing_seed_publish: 1 enables PUBLIC/READY only for new videos
-- with local media AND subtitles and no unresolved local audio review cues.
-- Otherwise approved videos stay PRIVATE/DRAFT.
-- Reviewed learning transcripts are included; physical media is supplied separately.
-- No USE, DROP TABLE, REPLACE, foreign-key bypass, or hard-coded row IDs.
-- The accepted editorial upgrade retires unused noisy cues inside the
-- same transaction. Unknown edits or any affected learner history abort it.

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET @shadowing_seed_catalog = {{CATALOG}};
SET @shadowing_seed_questions = {{QUESTIONS}};
SET @shadowing_seed_anchors = {{ANCHORS}};
SET @shadowing_seed_tags = {{TAGS}};
SET @shadowing_seed_tag_links = {{TAG_LINKS}};
-- Reviewed learning transcripts are embedded for local and YouTube sources.
-- Optional importer overrides must preserve the reviewed source fingerprints.
SET @shadowing_seed_local_cues = {{LOCAL_CUES}};
SET @shadowing_seed_subtitles_json=COALESCE(@shadowing_seed_subtitles_json,{{REVIEWED_CUES}});
SET @shadowing_editorial_baseline={{EDITORIAL_BASELINE}};
SET @shadowing_editorial_sources={{EDITORIAL_SOURCES}};

DROP PROCEDURE IF EXISTS jlas_import_curated_shadowing_20261010;
DELIMITER $$
CREATE PROCEDURE jlas_import_curated_shadowing_20261010()
SQL SECURITY INVOKER
BEGIN
    DECLARE locked INT DEFAULT 0;
    DECLARE started INT DEFAULT 0;
    DECLARE old_concat BIGINT UNSIGNED DEFAULT @@SESSION.group_concat_max_len;
    DECLARE lock_name VARCHAR(64);
    DECLARE input_count INT;
    DECLARE anchor_count INT;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        IF started = 1 THEN ROLLBACK; END IF;
        IF locked = 1 THEN DO RELEASE_LOCK(lock_name); END IF;
        SET SESSION group_concat_max_len = old_concat;
        RESIGNAL;
    END;
    IF DATABASE() IS NULL OR @@autocommit <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Select target database in a fresh autocommit connection';
    END IF;
    IF @shadowing_seed_owner_id IS NULL OR NOT EXISTS
        (SELECT 1 FROM users WHERE id = @shadowing_seed_owner_id) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Set shadowing_seed_owner_id to an existing content owner';
    END IF;
    IF (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
        AND table_name IN ('users','tag','video','subtitle','video_tag','shadowing_series',
            'shadowing_series_video','shadowing_question_bank','shadowing_preparation_job')
        AND engine='InnoDB') <> 9 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Current Shadowing schema with InnoDB tables is required';
    END IF;
    IF @shadowing_seed_subtitles_json IS NULL OR JSON_VALID(@shadowing_seed_subtitles_json) <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Timed local caption input required; use import_seed.py';
    END IF;
    SET lock_name = CONCAT('jlas-shadowing-', LEFT(SHA2(DATABASE(),256),40));
    SELECT GET_LOCK(lock_name,10) INTO locked;
    IF COALESCE(locked,0) <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Another Shadowing seed import is running';
    END IF;
    SET SESSION group_concat_max_len = 16777216;
    START TRANSACTION;
    SET started = 1;

    CREATE TEMPORARY TABLE cs_video (
        source_key VARCHAR(11) PRIMARY KEY, source_url VARCHAR(255), title VARCHAR(255),
        duration_seconds DECIMAL(10,3), estimated_jlpt VARCHAR(2), expected_cues INT,
        notes VARCHAR(500), job_levels VARCHAR(40), job_kinds VARCHAR(240), job_targets VARCHAR(300),
        job_suggestions VARCHAR(240),
        approved_on DATE,
        can_publish TINYINT NOT NULL,
        video_id BIGINT UNSIGNED NULL
    ) ENGINE=InnoDB;
    INSERT INTO cs_video SELECT j.*, NULL FROM JSON_TABLE(@shadowing_seed_catalog,'$.videos[*]' COLUMNS (
        source_key VARCHAR(11) PATH '$.source_key', source_url VARCHAR(255) PATH '$.source_url',
        title VARCHAR(255) PATH '$.title', duration_seconds DECIMAL(10,3) PATH '$.duration_seconds',
        estimated_jlpt VARCHAR(2) PATH '$.estimated_jlpt', expected_cues INT PATH '$.expected_cue_count',
        notes VARCHAR(500) PATH '$.notes', job_levels VARCHAR(40) PATH '$.job_levels',
        job_kinds VARCHAR(240) PATH '$.job_kinds', job_targets VARCHAR(300) PATH '$.job_targets',
        job_suggestions VARCHAR(240) PATH '$.job_suggestions',approved_on DATE PATH '$.approved_on',
        can_publish TINYINT PATH '$.can_publish'
    )) j;
    CREATE TEMPORARY TABLE cs_anchor (
        source_key VARCHAR(11), sort_order INT, start_time DECIMAL(10,3), end_time DECIMAL(10,3),
        text_sha256 CHAR(64), PRIMARY KEY(source_key,sort_order)
    ) ENGINE=InnoDB;
    INSERT INTO cs_anchor SELECT * FROM JSON_TABLE(@shadowing_seed_anchors,'$[*]' COLUMNS (
        source_key VARCHAR(11) PATH '$.source_key', sort_order INT PATH '$.order',
        start_time DECIMAL(10,3) PATH '$.start', end_time DECIMAL(10,3) PATH '$.end',
        text_sha256 CHAR(64) PATH '$.text_sha256'
    )) j;
    CREATE TEMPORARY TABLE cs_input (
        source_key VARCHAR(11) NOT NULL, sort_order INT NOT NULL,
        start_time DECIMAL(10,3) NOT NULL, end_time DECIMAL(10,3) NOT NULL,
        text TEXT NOT NULL, furigana TEXT NULL, translation TEXT NULL,
        PRIMARY KEY(source_key,sort_order)
    ) ENGINE=InnoDB;
    INSERT INTO cs_input SELECT * FROM JSON_TABLE(@shadowing_seed_subtitles_json,'$[*]' COLUMNS (
        source_key VARCHAR(11) PATH '$.source_key' ERROR ON EMPTY ERROR ON ERROR,
        sort_order INT PATH '$.order' ERROR ON EMPTY ERROR ON ERROR,
        start_time DECIMAL(10,3) PATH '$.start' ERROR ON EMPTY ERROR ON ERROR,
        end_time DECIMAL(10,3) PATH '$.end' ERROR ON EMPTY ERROR ON ERROR,
        text TEXT PATH '$.text' ERROR ON EMPTY ERROR ON ERROR,
        furigana TEXT PATH '$.furigana' NULL ON EMPTY, translation TEXT PATH '$.translation' NULL ON EMPTY
    )) j;
    CREATE TEMPORARY TABLE cs_missing_local AS
    SELECT j.* FROM JSON_TABLE(@shadowing_seed_local_cues,'$[*]' COLUMNS (
        source_key VARCHAR(11) PATH '$.source_key',sort_order INT PATH '$.order',
        start_time DECIMAL(10,3) PATH '$.start',end_time DECIMAL(10,3) PATH '$.end',
        text TEXT PATH '$.text',furigana TEXT PATH '$.furigana',translation TEXT PATH '$.translation'
    )) j LEFT JOIN cs_input i USING(source_key,sort_order) WHERE i.source_key IS NULL;
    INSERT INTO cs_input SELECT * FROM cs_missing_local;
    IF EXISTS (SELECT 1 FROM cs_input WHERE LENGTH(TRIM(COALESCE(furigana,'')))=0
        OR LENGTH(TRIM(COALESCE(translation,'')))=0) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Complete subtitle readings and Vietnamese translations are required';
    END IF;
    SELECT COUNT(*) INTO input_count FROM cs_input;
    SELECT COUNT(*) INTO anchor_count FROM cs_anchor;
    IF input_count <> anchor_count OR EXISTS (
        SELECT 1 FROM cs_anchor a LEFT JOIN cs_input i USING(source_key,sort_order)
        WHERE i.source_key IS NULL OR i.start_time <> a.start_time OR i.end_time <> a.end_time
            OR BINARY SHA2(i.text,256) <> BINARY a.text_sha256
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Caption fingerprint mismatch; review source text/timing before import';
    END IF;
    CREATE TEMPORARY TABLE cs_media (
        source_key VARCHAR(11) PRIMARY KEY, video_path TEXT NOT NULL, thumbnail_path TEXT NULL
    ) ENGINE=InnoDB;
    INSERT INTO cs_media SELECT * FROM JSON_TABLE(COALESCE(@shadowing_seed_media_json,'[]'),'$[*]' COLUMNS (
        source_key VARCHAR(11) PATH '$.source_key' ERROR ON EMPTY,
        video_path TEXT PATH '$.video_path' ERROR ON EMPTY,
        thumbnail_path TEXT PATH '$.thumbnail_path' NULL ON EMPTY
    )) j;
    IF EXISTS (SELECT 1 FROM cs_media m LEFT JOIN cs_video d USING(source_key)
        WHERE d.source_key IS NULL OR LENGTH(TRIM(m.video_path))=0) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Invalid local media mapping';
    END IF;
    -- Reject ambiguous ownership/business keys instead of adopting other users' content.
    IF EXISTS (SELECT 1 FROM cs_video d JOIN video v ON v.source_url IN (d.source_url,
        CONCAT('https://youtu.be/',d.source_key),CONCAT('https://youtube.com/shorts/',d.source_key),
        CONCAT('https://www.youtube.com/shorts/',d.source_key))
        GROUP BY d.source_key HAVING COUNT(*) > 1 OR MIN(COALESCE(v.owner_id,0)) <> @shadowing_seed_owner_id) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Existing source has ambiguous identity or another owner';
    END IF;
    UPDATE cs_video d JOIN video v ON v.source_url IN (d.source_url,
        CONCAT('https://youtu.be/',d.source_key),CONCAT('https://youtube.com/shorts/',d.source_key),
        CONCAT('https://www.youtube.com/shorts/',d.source_key)) SET d.video_id=v.id;
    IF EXISTS (SELECT 1 FROM cs_video d JOIN video v ON v.id=d.video_id
        LEFT JOIN shadowing_preparation_job j ON j.video_id=v.id
        WHERE v.status='PROCESSING' OR j.status IN ('QUEUED','RUNNING')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Source is being processed; finish processing before importing';
    END IF;
    INSERT INTO video (owner_id,title,source_url,video_path,thumbnail_url,duration_seconds,
        status,processing_step,progress_percent,target_language,generate_furigana,visibility,
        moderation_status,moderation_checked_at,moderation_summary)
    SELECT @shadowing_seed_owner_id,d.title,d.source_url,m.video_path,m.thumbnail_path,d.duration_seconds,
        IF(COALESCE(@shadowing_seed_publish,0)=1 AND m.video_path IS NOT NULL AND d.expected_cues>0 AND d.can_publish=1,'READY','DRAFT'),
        IF(d.expected_cues>0 AND m.video_path IS NOT NULL,'DONE','UPLOADED'),
        IF(d.expected_cues>0 AND m.video_path IS NOT NULL,100,0),'vi',IF(d.expected_cues>0,1,0),
        IF(COALESCE(@shadowing_seed_publish,0)=1 AND m.video_path IS NOT NULL AND d.expected_cues>0 AND d.can_publish=1,'PUBLIC','PRIVATE'),
        'SAFE',CURRENT_TIMESTAMP(6),LEFT(CONCAT('MANUAL_APPROVAL: content owner requested approved status on ',d.approved_on,'; no automated scan. ',d.notes),500)
    FROM cs_video d LEFT JOIN cs_media m USING(source_key) WHERE d.video_id IS NULL;
    UPDATE cs_video d JOIN video v ON v.owner_id=@shadowing_seed_owner_id AND v.source_url=d.source_url
        SET d.video_id=v.id WHERE d.video_id IS NULL;
    -- Existing video workflow/approval/media fields are deliberately never overwritten.
{{EDITORIAL_PREPARE}}
    IF EXISTS (SELECT 1 FROM cs_video d JOIN subtitle s ON s.video_id=d.video_id
        GROUP BY d.source_key,d.expected_cues HAVING COUNT(*) <> d.expected_cues) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Existing subtitle count differs; import will not overwrite editorial changes';
    END IF;
    IF EXISTS (SELECT 1 FROM cs_input i JOIN cs_video d USING(source_key)
        JOIN subtitle s ON s.video_id=d.video_id AND s.sort_order=i.sort_order
        WHERE BINARY s.text <> BINARY i.text OR s.start_time<>i.start_time OR s.end_time<>i.end_time) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Existing subtitle content/timing differs; import rolled back';
    END IF;
    IF EXISTS (SELECT 1 FROM cs_video d JOIN subtitle s ON s.video_id=d.video_id
        WHERE LENGTH(TRIM(COALESCE(s.furigana,'')))=0 OR LENGTH(TRIM(COALESCE(s.translation,'')))=0) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Existing captions need enrichment; importer preserves editorial content';
    END IF;
    INSERT INTO subtitle (video_id,start_time,end_time,text,furigana,translation,sort_order,ai_status)
    SELECT d.video_id,i.start_time,i.end_time,i.text,i.furigana,i.translation,i.sort_order,'COMPLETED'
    FROM cs_input i JOIN cs_video d USING(source_key)
    LEFT JOIN subtitle s ON s.video_id=d.video_id AND s.sort_order=i.sort_order
    WHERE s.id IS NULL ORDER BY d.source_key,i.sort_order;

    CREATE TEMPORARY TABLE cs_tag (name VARCHAR(255) PRIMARY KEY,code VARCHAR(100),tag_id BIGINT UNSIGNED NULL) ENGINE=InnoDB;
    INSERT INTO cs_tag SELECT j.*,NULL FROM JSON_TABLE(@shadowing_seed_tags,'$[*]' COLUMNS (
        name VARCHAR(255) PATH '$.name',code VARCHAR(100) PATH '$.code')) j;
    IF EXISTS (SELECT 1 FROM cs_tag d JOIN tag t ON t.name=d.name AND t.tag_type='VIDEO'
        GROUP BY d.name HAVING COUNT(*)>1) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Category tag has duplicate names';
    END IF;
    IF EXISTS (
        SELECT 1 FROM cs_tag d JOIN tag t ON t.code=d.code WHERE t.tag_type<>'VIDEO' OR t.name<>d.name
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Category tag is ambiguous or its business code collides';
    END IF;
    UPDATE cs_tag d JOIN tag t ON t.name=d.name AND t.tag_type='VIDEO' SET d.tag_id=t.id;
    INSERT INTO tag(code,name,tag_type) SELECT code,name,'VIDEO' FROM cs_tag WHERE tag_id IS NULL;
    UPDATE cs_tag d JOIN tag t ON t.name=d.name AND t.tag_type='VIDEO' SET d.tag_id=t.id;
    INSERT INTO video_tag(video_id,tag_id)
    SELECT d.video_id,t.tag_id FROM JSON_TABLE(@shadowing_seed_tag_links,'$[*]' COLUMNS (
        source_key VARCHAR(11) PATH '$.source_key',name VARCHAR(255) PATH '$.name')) j
    JOIN cs_video d USING(source_key) JOIN cs_tag t ON t.name=j.name
    LEFT JOIN video_tag vt ON vt.video_id=d.video_id AND vt.tag_id=t.tag_id WHERE vt.video_id IS NULL;

    CREATE TEMPORARY TABLE cs_question AS
    SELECT j.*,d.video_id FROM JSON_TABLE(@shadowing_seed_questions,'$[*]' COLUMNS (
        question_key VARCHAR(40) PATH '$.key',source_key VARCHAR(11) PATH '$.source_key',
        kind VARCHAR(40) PATH '$.kind',level_code VARCHAR(10) PATH '$.level',
        first_order INT PATH '$.first_order',last_order INT PATH '$.last_order',
        answer_order INT PATH '$.answer_order',answer_start INT PATH '$.answer_start',answer_length INT PATH '$.answer_length',
        prompt TEXT PATH '$.prompt',distractors JSON PATH '$.distractors',
        explanation TEXT PATH '$.explanation_vi',correct_position INT PATH '$.correct_position'
    )) j JOIN cs_video d USING(source_key);
    CREATE TEMPORARY TABLE cs_resolved AS
    SELECT q.question_key,q.video_id,a.id subtitle_id,q.kind,q.level_code,q.explanation,
        SUBSTRING(a.text,q.answer_start,q.answer_length) answer_text,
        CASE WHEN q.kind<>'GAP' THEN q.prompt
             WHEN q.level_code='HARD' THEN '音声の一文を日本語で書いてください。'
             ELSE CONCAT('音声を聞いて、空欄に入る言葉は何ですか。',CHAR(10),LEFT(a.text,q.answer_start-1),
                REPEAT('＿',GREATEST(3,LEAST(12,q.answer_length))),SUBSTRING(a.text,q.answer_start+q.answer_length)) END prompt_text,
        IF(q.kind='GAP',JSON_ARRAY(),JSON_ARRAY_INSERT(q.distractors,CONCAT('$[',q.correct_position-1,']'),
            SUBSTRING(a.text,q.answer_start,q.answer_length))) choices_json,
        CONCAT('[',GROUP_CONCAT(s.id ORDER BY s.sort_order SEPARATOR ','),']') source_ids_json,
        MIN(s.start_time) start_time,MAX(s.end_time) end_time,
        SHA2(CONCAT('listening-v1',GROUP_CONCAT(CONCAT(CHAR(31),s.id,CHAR(31),s.text,
            CHAR(31),COALESCE(s.furigana,'null'),CHAR(31),COALESCE(s.translation,'null'),CHAR(31),
            IF(s.start_time=FLOOR(s.start_time),CONCAT(CAST(FLOOR(s.start_time) AS CHAR),'.0'),
                TRIM(TRAILING '0' FROM CAST(s.start_time AS CHAR))),CHAR(31),
            IF(s.end_time=FLOOR(s.end_time),CONCAT(CAST(FLOOR(s.end_time) AS CHAR),'.0'),
                TRIM(TRAILING '0' FROM CAST(s.end_time AS CHAR)))) ORDER BY s.sort_order SEPARATOR '')),256) source_hash,
        CAST(NULL AS CHAR(64)) content_hash
    FROM cs_question q JOIN subtitle a ON a.video_id=q.video_id AND a.sort_order=q.answer_order
    JOIN subtitle s ON s.video_id=q.video_id AND s.sort_order BETWEEN q.first_order AND q.last_order
    GROUP BY q.question_key,q.video_id,a.id,a.text,q.kind,q.level_code,q.explanation,q.answer_start,
        q.answer_length,q.prompt,q.distractors,q.correct_position;
    UPDATE cs_resolved SET content_hash=SHA2(CONCAT('listening-v3-evidence|',kind,'|',level_code,'|',
        prompt_text,'|',answer_text,'|',source_hash),256);
    SELECT COUNT(*) INTO input_count FROM cs_resolved;
    IF input_count <> JSON_LENGTH(@shadowing_seed_questions) OR EXISTS (
        SELECT 1 FROM cs_resolved WHERE LENGTH(answer_text)=0 OR JSON_VALID(source_ids_json)<>1
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Question evidence resolution is incomplete';
    END IF;
{{EDITORIAL_QUESTIONS}}
    IF EXISTS (SELECT 1 FROM cs_resolved r JOIN shadowing_question_bank b
        ON b.video_id=r.video_id AND b.kind=r.kind AND b.content_hash<>r.content_hash
        WHERE JSON_OVERLAPS(b.source_ids_json,r.source_ids_json)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Existing questions overlap changed evidence; manual reconciliation required';
    END IF;
    INSERT INTO shadowing_question_bank (video_id,subtitle_id,source_ids_json,kind,level_code,prompt_text,
        answer_text,choices_json,explanation,start_time,end_time,source_hash,occurrence_index,content_hash,status)
    SELECT r.video_id,r.subtitle_id,r.source_ids_json,r.kind,r.level_code,r.prompt_text,r.answer_text,
        r.choices_json,r.explanation,r.start_time,r.end_time,r.source_hash,1,r.content_hash,'READY'
    FROM cs_resolved r LEFT JOIN shadowing_question_bank b ON b.video_id=r.video_id AND b.content_hash=r.content_hash
    WHERE b.id IS NULL ORDER BY r.question_key;
    -- The editor requests /suggestions automatically. Cache the authored evidence
    -- plans against the REAL full-transcript hash so opening it needs no AI call.
    -- This is offline authoring, not a claimed OpenRouter analysis response.
    CREATE TEMPORARY TABLE cs_analysis AS
    SELECT d.video_id,SHA2(CONCAT('listening-v1',GROUP_CONCAT(CONCAT(CHAR(31),s.id,CHAR(31),s.text,
        CHAR(31),COALESCE(s.furigana,'null'),CHAR(31),COALESCE(s.translation,'null'),CHAR(31),
        IF(s.start_time=FLOOR(s.start_time),CONCAT(CAST(FLOOR(s.start_time) AS CHAR),'.0'),
            TRIM(TRAILING '0' FROM CAST(s.start_time AS CHAR))),CHAR(31),
        IF(s.end_time=FLOOR(s.end_time),CONCAT(CAST(FLOOR(s.end_time) AS CHAR),'.0'),
            TRIM(TRAILING '0' FROM CAST(s.end_time AS CHAR)))) ORDER BY s.sort_order SEPARATOR '')),256) full_hash
    FROM cs_video d JOIN subtitle s ON s.video_id=d.video_id GROUP BY d.video_id;
    CREATE TEMPORARY TABLE cs_plans AS
    SELECT video_id,JSON_ARRAYAGG(JSON_OBJECT('kind',kind,'sourceIds',CAST(source_ids_json AS JSON),
        'focus',prompt_text,'answerQuote',answer_text,'contextIds',JSON_ARRAY(),
        'contextSummary',CONCAT('OFFLINE_AUTHORING: ',explanation))) plans_json
    FROM cs_resolved GROUP BY video_id;
    INSERT INTO shadowing_preparation_job (video_id,levels_csv,kinds_csv,status,progress,targets_csv,
        selected_jlpt_level,error_message,suggestions_csv,suggestion_hash,capacities_csv,
        analysis_version,evidence_plans_json,suggested_jlpt_level)
    SELECT d.video_id,d.job_levels,d.job_kinds,IF(d.expected_cues>0,'READY','IDLE'),
        IF(d.expected_cues>0,100,0),d.job_targets,d.estimated_jlpt,
        IF(d.expected_cues=0,'TRANSCRIPT_REQUIRED: no verified Japanese transcript; no questions fabricated.',NULL),
        d.job_suggestions,a.full_hash,d.job_targets,IF(d.expected_cues>0,'evidence-plans-v1',NULL),
        p.plans_json,IF(d.expected_cues>0,d.estimated_jlpt,NULL)
    FROM cs_video d LEFT JOIN shadowing_preparation_job j ON j.video_id=d.video_id
    LEFT JOIN cs_analysis a ON a.video_id=d.video_id LEFT JOIN cs_plans p ON p.video_id=d.video_id
    WHERE j.video_id IS NULL;
{{EDITORIAL_JOBS}}
    -- Additive question-bank expansion: refresh only our offline cache, with the
    -- same reviewed transcript, no running worker and no independently added bank rows.
    -- Never resurrect REJECTED questions or overwrite a creator's chosen JLPT level.
    UPDATE shadowing_preparation_job j JOIN cs_video d ON d.video_id=j.video_id
        JOIN cs_analysis a ON a.video_id=d.video_id JOIN cs_plans p ON p.video_id=d.video_id
        SET j.levels_csv=d.job_levels,j.kinds_csv=d.job_kinds,j.targets_csv=d.job_targets,
            j.suggestions_csv=d.job_suggestions,j.capacities_csv=d.job_targets,
            j.evidence_plans_json=p.plans_json
        WHERE j.status NOT IN ('RUNNING','QUEUED') AND j.suggestion_hash=a.full_hash
          AND j.analysis_version='evidence-plans-v1'
          AND JSON_LENGTH(j.evidence_plans_json)>0
          AND NOT EXISTS (SELECT 1 FROM JSON_TABLE(j.evidence_plans_json,'$[*]' COLUMNS (
              summary TEXT PATH '$.contextSummary')) old_plan
              WHERE old_plan.summary IS NULL OR old_plan.summary NOT LIKE 'OFFLINE_AUTHORING:%')
          AND NOT EXISTS (SELECT 1 FROM shadowing_question_bank b
              LEFT JOIN cs_resolved r ON r.video_id=b.video_id AND r.content_hash=b.content_hash
              WHERE b.video_id=d.video_id AND r.question_key IS NULL);

    CREATE TEMPORARY TABLE cs_series (
        series_key VARCHAR(20) PRIMARY KEY,title VARCHAR(255),description TEXT,
        estimated_level VARCHAR(16),level_source VARCHAR(16),previous_titles JSON,series_id BIGINT NULL
    ) ENGINE=InnoDB;
    INSERT INTO cs_series SELECT j.*,NULL FROM JSON_TABLE(@shadowing_seed_catalog,'$.series[*]' COLUMNS (
        series_key VARCHAR(20) PATH '$.key',title VARCHAR(255) PATH '$.title',description TEXT PATH '$.description',
        estimated_level VARCHAR(16) PATH '$.estimated_level',level_source VARCHAR(16) PATH '$.level_source',
        previous_titles JSON PATH '$.previous_titles' NULL ON EMPTY)) j;
    IF EXISTS (SELECT 1 FROM cs_series d JOIN shadowing_series s
        ON s.owner_id=@shadowing_seed_owner_id AND (s.title=d.title
            OR JSON_CONTAINS(COALESCE(d.previous_titles,JSON_ARRAY()),JSON_QUOTE(s.title))=1)
        GROUP BY d.series_key HAVING COUNT(*)>1) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Existing curated list has duplicate titles for this owner';
    END IF;
    UPDATE cs_series d JOIN shadowing_series s ON s.owner_id=@shadowing_seed_owner_id AND (s.title=d.title
        OR JSON_CONTAINS(COALESCE(d.previous_titles,JSON_ARRAY()),JSON_QUOTE(s.title))=1)
        SET d.series_id=s.id;
    -- Explicit prior titles permit renaming without duplicating lists or memberships.
    UPDATE shadowing_series s JOIN cs_series d ON d.series_id=s.id SET s.title=d.title
        WHERE BINARY s.title<>BINARY d.title;
    INSERT INTO shadowing_series(owner_id,title,description,estimated_level,level_source,visibility)
    SELECT @shadowing_seed_owner_id,title,description,estimated_level,level_source,'PRIVATE' FROM cs_series WHERE series_id IS NULL;
    -- Remember newly created lists; existing visibility/description decisions are preserved.
    CREATE TEMPORARY TABLE cs_new_series AS SELECT series_key FROM cs_series WHERE series_id IS NULL;
    UPDATE cs_series d JOIN shadowing_series s ON s.owner_id=@shadowing_seed_owner_id AND s.title=d.title
        SET d.series_id=s.id;
    CREATE TEMPORARY TABLE cs_members AS
    SELECT s.series_id,d.video_id,j.position FROM JSON_TABLE(@shadowing_seed_catalog,'$.series[*]' COLUMNS (
        series_key VARCHAR(20) PATH '$.key',NESTED PATH '$.members[*]' COLUMNS (
            position FOR ORDINALITY,source_key VARCHAR(11) PATH '$'))) j
    JOIN cs_series s USING(series_key) JOIN cs_video d USING(source_key);
    IF EXISTS (SELECT 1 FROM cs_members m JOIN shadowing_series_video sv ON sv.series_id=m.series_id
        WHERE (sv.video_id=m.video_id AND sv.position<>m.position)
           OR (sv.position=m.position AND sv.video_id<>m.video_id)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Existing list order differs; import will not overwrite it';
    END IF;
    INSERT INTO shadowing_series_video(series_id,video_id,position)
    SELECT m.* FROM cs_members m LEFT JOIN shadowing_series_video sv
        ON sv.series_id=m.series_id AND sv.video_id=m.video_id WHERE sv.video_id IS NULL;
    UPDATE shadowing_series s JOIN cs_series d ON d.series_id=s.id
    JOIN cs_new_series n ON n.series_key=d.series_key
    SET s.visibility='PUBLIC' WHERE COALESCE(@shadowing_seed_publish,0)=1 AND NOT EXISTS (
        SELECT 1 FROM shadowing_series_video sv JOIN video v ON v.id=sv.video_id
        WHERE sv.series_id=s.id AND (v.status<>'READY' OR v.visibility<>'PUBLIC' OR v.moderation_status<>'SAFE')
    );
    COMMIT;
    SET started=0;
    SET SESSION group_concat_max_len=old_concat;
    DO RELEASE_LOCK(lock_name);
    SET locked=0;
    SELECT JSON_UNQUOTE(JSON_EXTRACT(@shadowing_seed_catalog,'$.version')) seed_version,COUNT(*) videos,
        SUM(expected_cues=0) transcript_pending FROM cs_video;
    SELECT COUNT(*) authored_questions,'READY (owner approval)' initial_status FROM cs_resolved;
    SELECT COUNT(*) curated_lists FROM cs_series;
    SELECT COUNT(*) list_memberships FROM cs_members;
END$$
DELIMITER ;
CALL jlas_import_curated_shadowing_20261010();
DROP PROCEDURE jlas_import_curated_shadowing_20261010;
