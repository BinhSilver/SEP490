-- JLAS-W0250: Moderation, Denylist, Appeals, and Dictionary Contribution Review.
-- Keep jlas_schema.sql unchanged; apply this migration through the deployment process.

-- 1) Roles used by the admin area (code already checks these names).
INSERT IGNORE INTO roles (name, description) VALUES
    ('MODERATOR',   'Kiem duyet noi dung cong dong, tu khoa cam, khang nghi, dong gop tu dien'),
    ('ADMIN',       'Quan tri: toan bo quyen kiem duyet va che tai nguoi dung'),
    ('SUPER_ADMIN', 'Quan tri cao nhat');

-- 2) Append-only audit trail: every moderation state transition writes one row
--    in the same transaction as the change itself.
CREATE TABLE IF NOT EXISTS moderation_audit_log (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    actor_user_id BIGINT UNSIGNED NULL,              -- NULL khi tai khoan da bi xoa.
    actor_role    VARCHAR(50) NOT NULL,
    action        VARCHAR(40) NOT NULL,              -- REPORT_VALID, APPEAL_APPROVE, DENYLIST_CREATE, ...
    target_type   VARCHAR(40) NOT NULL,
    target_id     BIGINT UNSIGNED NULL,
    from_state    VARCHAR(40) NULL,
    to_state      VARCHAR(40) NULL,
    reason        VARCHAR(1000) NOT NULL,
    case_id       BIGINT UNSIGNED NULL,              -- review_case lien quan (neu co).
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_mod_audit_created (created_at),
    KEY idx_mod_audit_target (target_type, target_id, created_at),
    KEY idx_mod_audit_actor (actor_user_id, created_at),
    KEY idx_mod_audit_action (action, created_at),
    CONSTRAINT fk_mod_audit_actor FOREIGN KEY (actor_user_id)
        REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3) Denylist moves from code into moderation_policy (policy_type = TERM).
--    action: BLOCK (chan) | REVIEW (dua vao hang doi xem lai).
--    config_json.accentSensitive = true: so khop giu nguyen dau tieng Viet.
--    policy_key dung collation nhi phan: unicode_ci coi "ぽるの" = "ポルノ" va
--    "thằng chó" = "thang cho", khien unique key nuot mat bien the.
ALTER TABLE moderation_policy
    MODIFY policy_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;

INSERT IGNORE INTO moderation_policy (policy_type, policy_key, language, action, config_json, is_active) VALUES
-- BLOCK, English
('TERM','porn','ALL','BLOCK',NULL,1),('TERM','xxx','ALL','BLOCK',NULL,1),('TERM','onlyfans','ALL','BLOCK',NULL,1),
('TERM','nsfw','ALL','BLOCK',NULL,1),('TERM','rape','ALL','BLOCK',NULL,1),('TERM','pedophil','ALL','BLOCK',NULL,1),
('TERM','child porn','ALL','BLOCK',NULL,1),('TERM','go fuck','ALL','BLOCK',NULL,1),('TERM','fuck you','ALL','BLOCK',NULL,1),
('TERM','motherfucker','ALL','BLOCK',NULL,1),
-- BLOCK, Vietnamese
('TERM','dit me','ALL','BLOCK',NULL,1),('TERM','deo ma','ALL','BLOCK',NULL,1),('TERM','thằng chó','ALL','BLOCK',NULL,1),
('TERM','thang cho','ALL','BLOCK',NULL,1),('TERM','óc chó','ALL','BLOCK',NULL,1),('TERM','oc cho','ALL','BLOCK',NULL,1),
('TERM','giết hết','ALL','BLOCK',NULL,1),('TERM','giet het','ALL','BLOCK',NULL,1),('TERM','terrorist bomb','ALL','BLOCK',NULL,1),
-- BLOCK, Japanese NSFW
('TERM','ポルノ','ALL','BLOCK',NULL,1),('TERM','ぽるの','ALL','BLOCK',NULL,1),('TERM','エロ動画','ALL','BLOCK',NULL,1),
('TERM','エロ画像','ALL','BLOCK',NULL,1),('TERM','エロサイト','ALL','BLOCK',NULL,1),('TERM','アダルトビデオ','ALL','BLOCK',NULL,1),
('TERM','アダルト動画','ALL','BLOCK',NULL,1),('TERM','セックス','ALL','BLOCK',NULL,1),('TERM','せっくす','ALL','BLOCK',NULL,1),
('TERM','フェラチオ','ALL','BLOCK',NULL,1),('TERM','フェラ','ALL','BLOCK',NULL,1),('TERM','まんこ','ALL','BLOCK',NULL,1),
('TERM','おまんこ','ALL','BLOCK',NULL,1),('TERM','ちんこ','ALL','BLOCK',NULL,1),('TERM','ちんぽ','ALL','BLOCK',NULL,1),
('TERM','チンポ','ALL','BLOCK',NULL,1),('TERM','おっぱい揉','ALL','BLOCK',NULL,1),('TERM','中出し','ALL','BLOCK',NULL,1),
('TERM','なかだし','ALL','BLOCK',NULL,1),('TERM','顔射','ALL','BLOCK',NULL,1),('TERM','ぶっかけ','ALL','BLOCK',NULL,1),
('TERM','レイプ','ALL','BLOCK',NULL,1),('TERM','れいぷ','ALL','BLOCK',NULL,1),('TERM','強姦','ALL','BLOCK',NULL,1),
('TERM','ごうかん','ALL','BLOCK',NULL,1),('TERM','児童ポルノ','ALL','BLOCK',NULL,1),('TERM','じどうぽるの','ALL','BLOCK',NULL,1),
('TERM','ロリコン動画','ALL','BLOCK',NULL,1),('TERM','ショタコン','ALL','BLOCK',NULL,1),
-- BLOCK, Japanese abuse / hate
('TERM','死ねボケ','ALL','BLOCK',NULL,1),('TERM','くたばれ','ALL','BLOCK',NULL,1),('TERM','クソガキ','ALL','BLOCK',NULL,1),
('TERM','くそがき','ALL','BLOCK',NULL,1),('TERM','殺しに行く','ALL','BLOCK',NULL,1),('TERM','ころしにいく','ALL','BLOCK',NULL,1),
('TERM','皆殺し','ALL','BLOCK',NULL,1),('TERM','みなごろし','ALL','BLOCK',NULL,1),('TERM','在日叩き','ALL','BLOCK',NULL,1),
('TERM','朝鮮人は出てけ','ALL','BLOCK',NULL,1),
-- BLOCK, Vietnamese short words that must keep their accents
('TERM','địt','ALL','BLOCK','{"accentSensitive": true}',1),('TERM','đụ','ALL','BLOCK','{"accentSensitive": true}',1),
('TERM','lồn','ALL','BLOCK','{"accentSensitive": true}',1),('TERM','cặc','ALL','BLOCK','{"accentSensitive": true}',1),
('TERM','buồi','ALL','BLOCK','{"accentSensitive": true}',1),('TERM','đéo','ALL','BLOCK','{"accentSensitive": true}',1),
-- REVIEW
('TERM','nude','ALL','REVIEW',NULL,1),('TERM','naked','ALL','REVIEW',NULL,1),('TERM','sex video','ALL','REVIEW',NULL,1),
('TERM','only fans','ALL','REVIEW',NULL,1),('TERM','giết người','ALL','REVIEW',NULL,1),('TERM','giet nguoi','ALL','REVIEW',NULL,1),
('TERM','tự tử','ALL','REVIEW',NULL,1),('TERM','tu tu huong dan','ALL','REVIEW',NULL,1),('TERM','hate speech','ALL','REVIEW',NULL,1),
('TERM','kill yourself','ALL','REVIEW',NULL,1),('TERM','エロ','ALL','REVIEW',NULL,1),('TERM','えろい','ALL','REVIEW',NULL,1),
('TERM','えっち','ALL','REVIEW',NULL,1),('TERM','エッチ','ALL','REVIEW',NULL,1),('TERM','ヌード','ALL','REVIEW',NULL,1),
('TERM','ぬーど','ALL','REVIEW',NULL,1),('TERM','全裸','ALL','REVIEW',NULL,1),('TERM','ぜんら','ALL','REVIEW',NULL,1),
('TERM','裸体','ALL','REVIEW',NULL,1),('TERM','らたい','ALL','REVIEW',NULL,1),('TERM','下着姿','ALL','REVIEW',NULL,1),
('TERM','自殺','ALL','REVIEW',NULL,1),('TERM','じさつ','ALL','REVIEW',NULL,1),('TERM','自殺方法','ALL','REVIEW',NULL,1),
('TERM','自殺サイト','ALL','REVIEW',NULL,1),('TERM','他殺','ALL','REVIEW',NULL,1),('TERM','テロリズム','ALL','REVIEW',NULL,1),
('TERM','爆破予告','ALL','REVIEW',NULL,1),('TERM','死ね','ALL','REVIEW',NULL,1),('TERM','しね!','ALL','REVIEW',NULL,1),
('TERM','しね！','ALL','REVIEW',NULL,1),('TERM','殺すぞ','ALL','REVIEW',NULL,1),('TERM','ころすぞ','ALL','REVIEW',NULL,1),
('TERM','ぶっ殺','ALL','REVIEW',NULL,1),('TERM','ぶっころ','ALL','REVIEW',NULL,1),('TERM','障害者は消えろ','ALL','REVIEW',NULL,1),
('TERM','部落','ALL','REVIEW',NULL,1),('TERM','在日朝鮮人','ALL','REVIEW',NULL,1);
