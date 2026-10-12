-- [JLAS-W0252] Live Shadowing counters, derived from real interactions.
-- Re-runnable; does not manufacture votes, views or user activity.
-- Run after 026_shadowing_popularity.sql on an older database.
CREATE OR REPLACE SQL SECURITY INVOKER VIEW shadowing_video_statistics AS
SELECT v.id AS video_id,
       v.view_count,
       COALESCE(e.views_last_7_days,0) AS views_last_7_days,
       r.avg_rating,
       COALESCE(r.rating_count,0) AS rating_count,
       COALESCE(r.rating_1,0) AS rating_1,
       COALESCE(r.rating_2,0) AS rating_2,
       COALESCE(r.rating_3,0) AS rating_3,
       COALESCE(r.rating_4,0) AS rating_4,
       COALESCE(r.rating_5,0) AS rating_5,
       COALESCE(r.favorite_count,0) AS favorite_count,
       COALESCE(c.comment_count,0) AS comment_count,
       COALESCE(s.subtitle_count,0) AS subtitle_count,
       COALESCE(q.ready_question_count,0) AS ready_question_count,
       COALESCE(l.series_count,0) AS series_count
FROM video v
LEFT JOIN (SELECT video_id, AVG(rating) AS avg_rating, COUNT(rating) AS rating_count,
                  SUM(rating=1) AS rating_1,SUM(rating=2) AS rating_2,SUM(rating=3) AS rating_3,
                  SUM(rating=4) AS rating_4,SUM(rating=5) AS rating_5,
                  SUM(is_favorite) AS favorite_count
           FROM video_user_state GROUP BY video_id) r ON r.video_id=v.id
LEFT JOIN (SELECT video_id,COUNT(*) AS views_last_7_days FROM shadowing_view_event
           WHERE counted_at >= CURRENT_TIMESTAMP(6)-INTERVAL 7 DAY
             AND counted_at <= CURRENT_TIMESTAMP(6) GROUP BY video_id) e ON e.video_id=v.id
LEFT JOIN (SELECT target_id,COUNT(*) AS comment_count FROM content_comment
           WHERE target_type='VIDEO' AND is_hidden=0 GROUP BY target_id) c ON c.target_id=v.id
LEFT JOIN (SELECT video_id,COUNT(*) AS subtitle_count FROM subtitle GROUP BY video_id) s ON s.video_id=v.id
LEFT JOIN (SELECT video_id,COUNT(*) AS ready_question_count FROM shadowing_question_bank
           WHERE status='READY' GROUP BY video_id) q ON q.video_id=v.id
LEFT JOIN (SELECT video_id,COUNT(*) AS series_count FROM shadowing_series_video GROUP BY video_id) l ON l.video_id=v.id;
