-- J-LAS Shadowing engagement fixtures, MySQL 8.0.21+, UTF-8.
-- GENERATED from data/shadowing/demo_engagement.json. Do not edit this output.
-- SYNTHETIC DEMO DATA: names/accounts, comments, votes, favorites and views are fictional.
-- These are development/demo fixtures, NOT genuine learner activity or public endorsements.
-- Production content import does not run this file. Do not use it to manufacture production metrics.
-- Run after seed_data_shadowing.sql and the current schema (incl. 026/027 on older databases).
-- Set @shadowing_seed_owner_id to the existing content owner in the SAME fresh connection.
-- No fixed database IDs, passwords, emails to real domains, credit grants, or notifications.
-- Existing interactions/edited comments are preserved; repeat imports add no duplicate activity.
-- {{DEMO_USER_COUNT}} fictional learners / {{DEMO_VIDEO_COUNT}} videos / {{DEMO_VIEW_COUNT}} accepted-view fixtures /
-- {{DEMO_VOTE_COUNT}} votes / {{DEMO_FAVORITE_COUNT}} favorites / {{DEMO_COMMENT_COUNT}} original sample comments.

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET @shadowing_demo_payload = {{DEMO_PAYLOAD}};

DROP PROCEDURE IF EXISTS jlas_seed_shadowing_demo_interactions_v1;
DELIMITER $$
CREATE PROCEDURE jlas_seed_shadowing_demo_interactions_v1()
SQL SECURITY INVOKER
BEGIN
    DECLARE locked INT DEFAULT 0;
    DECLARE started INT DEFAULT 0;
    DECLARE learner_role BIGINT UNSIGNED;
    DECLARE anchor_time DATETIME(6);
    DECLARE lock_name VARCHAR(64);
    DECLARE new_views INT DEFAULT 0;
    DECLARE new_comments INT DEFAULT 0;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        IF started = 1 THEN ROLLBACK; END IF;
        IF locked = 1 THEN DO RELEASE_LOCK(lock_name); END IF;
        RESIGNAL;
    END;
    IF DATABASE() IS NULL OR @@autocommit <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Select a DEMO database in a fresh autocommit connection';
    END IF;
    IF @shadowing_seed_owner_id IS NULL OR NOT EXISTS (SELECT 1 FROM users WHERE id=@shadowing_seed_owner_id) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Set shadowing_seed_owner_id to the existing content owner';
    END IF;
    IF (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
        AND table_name IN ('roles','users','video','video_user_state','video_view_tracker','shadowing_view_event',
                          'content_comment','content_comment_rating') AND engine='InnoDB') <> 8 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Current Shadowing interaction schema with InnoDB tables is required';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.views WHERE table_schema=DATABASE()
                   AND table_name='shadowing_video_statistics') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Apply 027_shadowing_statistics.sql before the demo interaction seed';
    END IF;
    SELECT id INTO learner_role FROM roles WHERE name='LEARNER';
    IF learner_role IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'The LEARNER role is required';
    END IF;
    -- Share the content import's advisory lock so IDs cannot be remapped mid-import.
    SET lock_name=CONCAT('jlas-shadowing-',LEFT(SHA2(DATABASE(),256),40));
    SELECT GET_LOCK(lock_name,10) INTO locked;
    IF COALESCE(locked,0) <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Another Shadowing seed import is running';
    END IF;
    START TRANSACTION;
    SET started=1;

    CREATE TEMPORARY TABLE sd_video (
        source_key VARCHAR(11) PRIMARY KEY, source_url VARCHAR(255) NOT NULL,
        video_id BIGINT UNSIGNED NULL
    ) ENGINE=InnoDB;
    INSERT INTO sd_video SELECT j.*,NULL FROM JSON_TABLE(@shadowing_demo_payload,'$.videos[*]' COLUMNS (
        source_key VARCHAR(11) PATH '$.source_key',source_url VARCHAR(255) PATH '$.source_url')) j;
    IF EXISTS (SELECT 1 FROM sd_video d LEFT JOIN video v ON v.owner_id=@shadowing_seed_owner_id AND v.source_url=d.source_url
               GROUP BY d.source_key HAVING COUNT(v.id)<>1) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Import all Shadowing sources for this owner first; missing or ambiguous video';
    END IF;
    UPDATE sd_video d JOIN video v ON v.owner_id=@shadowing_seed_owner_id AND v.source_url=d.source_url SET d.video_id=v.id;

    CREATE TEMPORARY TABLE sd_user (
        user_key VARCHAR(20) PRIMARY KEY, full_name VARCHAR(150) NOT NULL,
        email VARCHAR(255) NOT NULL UNIQUE, username VARCHAR(50) NOT NULL UNIQUE,
        level_code VARCHAR(10) NOT NULL,user_id BIGINT UNSIGNED NULL
    ) ENGINE=InnoDB;
    INSERT INTO sd_user SELECT j.*,NULL FROM JSON_TABLE(@shadowing_demo_payload,'$.learners[*]' COLUMNS (
        user_key VARCHAR(20) PATH '$.key',full_name VARCHAR(150) PATH '$.name',email VARCHAR(255) PATH '$.email',
        username VARCHAR(50) PATH '$.username',level_code VARCHAR(10) PATH '$.level')) j;
    -- Reserved .invalid identities must never be adopted from an actual login-capable account.
    IF EXISTS (SELECT 1 FROM sd_user d JOIN users u ON u.email=d.email OR u.username=d.username
               WHERE u.email<>d.email OR COALESCE(u.username,'')<>d.username OR u.role_id<>learner_role
                  OR u.password_hash IS NOT NULL OR u.google_id IS NOT NULL OR u.is_verified<>0 OR u.login_type<>'LOCAL') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Demo identity collision; existing account will not be modified';
    END IF;
    -- An immutable first-import anchor keeps every event/comment business key stable on rerun.
    SELECT DATE_ADD(MIN(u.created_at),INTERVAL 90 DAY) INTO anchor_time
        FROM users u JOIN sd_user d ON d.email=u.email;
    SET anchor_time=COALESCE(anchor_time,CURRENT_TIMESTAMP(6)-INTERVAL 1 MINUTE);
    IF anchor_time>CURRENT_TIMESTAMP(6) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Demo account timestamps are in the future; import aborted';
    END IF;
    INSERT INTO users(role_id,email,username,password_hash,login_type,full_name,japanese_level,is_verified,created_at,updated_at)
    SELECT learner_role,d.email,d.username,NULL,'LOCAL',d.full_name,d.level_code,0,
           anchor_time-INTERVAL 90 DAY,anchor_time-INTERVAL 90 DAY
    FROM sd_user d LEFT JOIN users u ON u.email=d.email WHERE u.id IS NULL;
    UPDATE sd_user d JOIN users u ON u.email=d.email SET d.user_id=u.id;

    CREATE TEMPORARY TABLE sd_event (
        source_key VARCHAR(11) NOT NULL,user_key VARCHAR(20) NOT NULL,minutes_ago INT UNSIGNED NOT NULL,
        PRIMARY KEY(source_key,user_key,minutes_ago)
    ) ENGINE=InnoDB;
    INSERT INTO sd_event SELECT * FROM JSON_TABLE(@shadowing_demo_payload,'$.events[*]' COLUMNS (
        source_key VARCHAR(11) PATH '$.source_key',user_key VARCHAR(20) PATH '$.user_key',
        minutes_ago INT UNSIGNED PATH '$.minutes_ago')) j;
    CREATE TEMPORARY TABLE sd_new_event AS
    SELECT u.user_id,v.video_id,TIMESTAMPADD(MINUTE,-CAST(d.minutes_ago AS SIGNED),anchor_time) counted_at
    FROM sd_event d JOIN sd_user u USING(user_key) JOIN sd_video v USING(source_key)
    LEFT JOIN shadowing_view_event e ON e.user_id=u.user_id AND e.video_id=v.video_id
        AND e.counted_at=TIMESTAMPADD(MINUTE,-CAST(d.minutes_ago AS SIGNED),anchor_time)
    WHERE e.user_id IS NULL;
    SELECT COUNT(*) INTO new_views FROM sd_new_event;
    INSERT INTO shadowing_view_event(user_id,video_id,counted_at) SELECT user_id,video_id,counted_at FROM sd_new_event;
    -- Add only newly inserted events; do not replace/clamp genuine pre-existing view totals.
    UPDATE video v JOIN (SELECT video_id,COUNT(*) amount FROM sd_new_event GROUP BY video_id) d ON d.video_id=v.id
        SET v.view_count=v.view_count+d.amount;

    CREATE TEMPORARY TABLE sd_state (
        source_key VARCHAR(11) NOT NULL,user_key VARCHAR(20) NOT NULL,rating TINYINT UNSIGNED NULL,
        favorite TINYINT NOT NULL,max_position DECIMAL(10,3) NOT NULL,last_view_minutes_ago INT UNSIGNED NOT NULL,
        PRIMARY KEY(source_key,user_key)
    ) ENGINE=InnoDB;
    INSERT INTO sd_state SELECT * FROM JSON_TABLE(@shadowing_demo_payload,'$.states[*]' COLUMNS (
        source_key VARCHAR(11) PATH '$.source_key',user_key VARCHAR(20) PATH '$.user_key',
        rating TINYINT UNSIGNED PATH '$.rating' NULL ON EMPTY,favorite TINYINT PATH '$.favorite',
        max_position DECIMAL(10,3) PATH '$.position',last_view_minutes_ago INT UNSIGNED PATH '$.last_view_minutes_ago')) j;
    INSERT INTO video_user_state(user_id,video_id,max_position_sec,counted_as_view,rating,is_favorite,last_watched_at,updated_at)
    SELECT u.user_id,v.video_id,d.max_position,1,d.rating,d.favorite,
           TIMESTAMPADD(MINUTE,-CAST(d.last_view_minutes_ago AS SIGNED),anchor_time),
           TIMESTAMPADD(MINUTE,-CAST(d.last_view_minutes_ago AS SIGNED),anchor_time)
    FROM sd_state d JOIN sd_user u USING(user_key) JOIN sd_video v USING(source_key)
    LEFT JOIN video_user_state s ON s.user_id=u.user_id AND s.video_id=v.video_id WHERE s.user_id IS NULL;
    INSERT INTO video_view_tracker(user_id,video_id,listened_sec,last_ping_at,last_counted_at)
    SELECT u.user_id,v.video_id,0,TIMESTAMPADD(MINUTE,-CAST(d.last_view_minutes_ago AS SIGNED),anchor_time),
           TIMESTAMPADD(MINUTE,-CAST(d.last_view_minutes_ago AS SIGNED),anchor_time)
    FROM sd_state d JOIN sd_user u USING(user_key) JOIN sd_video v USING(source_key)
    LEFT JOIN video_view_tracker t ON t.user_id=u.user_id AND t.video_id=v.video_id WHERE t.user_id IS NULL;

    CREATE TEMPORARY TABLE sd_comment (
        source_key VARCHAR(11) NOT NULL,comment_key VARCHAR(60) NOT NULL,user_key VARCHAR(20) NOT NULL,
        content TEXT NOT NULL,rating TINYINT UNSIGNED NULL,minutes_ago INT UNSIGNED NOT NULL,
        PRIMARY KEY(source_key,comment_key),UNIQUE KEY uk_sd_comment_identity(source_key,user_key,minutes_ago)
    ) ENGINE=InnoDB;
    INSERT INTO sd_comment SELECT * FROM JSON_TABLE(@shadowing_demo_payload,'$.comments[*]' COLUMNS (
        source_key VARCHAR(11) PATH '$.source_key',comment_key VARCHAR(60) PATH '$.key',user_key VARCHAR(20) PATH '$.user_key',
        content TEXT PATH '$.content',rating TINYINT UNSIGNED PATH '$.rating' NULL ON EMPTY,
        minutes_ago INT UNSIGNED PATH '$.minutes_ago')) j;
    INSERT INTO content_comment(target_type,target_id,user_id,content,is_hidden,created_at,updated_at)
    SELECT 'VIDEO',v.video_id,u.user_id,d.content,0,
           TIMESTAMPADD(MINUTE,-CAST(d.minutes_ago AS SIGNED),anchor_time),
           TIMESTAMPADD(MINUTE,-CAST(d.minutes_ago AS SIGNED),anchor_time)
    FROM sd_comment d JOIN sd_user u USING(user_key) JOIN sd_video v USING(source_key)
    WHERE NOT EXISTS (SELECT 1 FROM content_comment c WHERE c.target_type='VIDEO' AND c.target_id=v.video_id
        AND c.user_id=u.user_id AND c.created_at=TIMESTAMPADD(MINUTE,-CAST(d.minutes_ago AS SIGNED),anchor_time));
    SET new_comments=ROW_COUNT();
    INSERT INTO content_comment_rating(comment_id,rating,created_at)
    SELECT c.id,d.rating,c.created_at
    FROM sd_comment d JOIN sd_user u USING(user_key) JOIN sd_video v USING(source_key)
    JOIN content_comment c ON c.target_type='VIDEO' AND c.target_id=v.video_id AND c.user_id=u.user_id
        AND c.created_at=TIMESTAMPADD(MINUTE,-CAST(d.minutes_ago AS SIGNED),anchor_time)
    LEFT JOIN content_comment_rating r ON r.comment_id=c.id
    WHERE d.rating IS NOT NULL AND r.comment_id IS NULL AND c.is_hidden=0 AND BINARY c.content=BINARY d.content;

    COMMIT;
    SET started=0;
    DO RELEASE_LOCK(lock_name);
    SET locked=0;
    SELECT 'SYNTHETIC_DEMO' data_kind,anchor_time first_import_as_of,
           (SELECT COUNT(*) FROM sd_user) demo_learners,(SELECT COUNT(*) FROM sd_video) videos,
           new_views inserted_views,new_comments inserted_comments;
    SELECT v.id,v.title,s.view_count,s.views_last_7_days,s.avg_rating,s.rating_count,
           s.rating_1,s.rating_2,s.rating_3,s.rating_4,s.rating_5,s.favorite_count,s.comment_count
    FROM sd_video d JOIN video v ON v.id=d.video_id JOIN shadowing_video_statistics s ON s.video_id=v.id
    ORDER BY v.id;
END$$
DELIMITER ;
CALL jlas_seed_shadowing_demo_interactions_v1();
DROP PROCEDURE jlas_seed_shadowing_demo_interactions_v1;
