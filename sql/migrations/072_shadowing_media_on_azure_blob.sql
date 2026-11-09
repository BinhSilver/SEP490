-- Tro video seed Shadowing toi ban sao tren Azure Blob: co file thi phat file, khong co moi nhung YouTube.
-- Duong dan dang blob:<ten>; ShadowingBlobStore ky link tam khi phat. Chay lai an toan: chi dien vao o con trong,
-- khong dung den video nguoi dung tu dang hay may da co duong dan file rieng.
SET NAMES utf8mb4;

DROP TEMPORARY TABLE IF EXISTS tmp_blob_media;
CREATE TEMPORARY TABLE tmp_blob_media (
    source_url VARCHAR(255) NOT NULL PRIMARY KEY,
    source_key VARCHAR(11) NOT NULL,
    has_thumbnail TINYINT NOT NULL
) ENGINE=MEMORY;

INSERT INTO tmp_blob_media (source_url, source_key, has_thumbnail) VALUES
('https://www.youtube.com/watch?v=wbQgGx27AkQ','wbQgGx27AkQ',1),
('https://www.youtube.com/watch?v=z6O3dfDSHmA','z6O3dfDSHmA',1),
('https://www.youtube.com/watch?v=-dUOzVp6-Y0','-dUOzVp6-Y0',1),
('https://www.youtube.com/watch?v=XtyYeTm1diw','XtyYeTm1diw',1),
('https://www.youtube.com/watch?v=XV6g5Y7gNCs','XV6g5Y7gNCs',1),
('https://www.youtube.com/watch?v=rnCXcKc8y0g','rnCXcKc8y0g',1),
('https://www.youtube.com/watch?v=Pj2eo2MnOUQ','Pj2eo2MnOUQ',1),
('https://www.youtube.com/watch?v=P04pWou9oE4','P04pWou9oE4',1),
('https://www.youtube.com/watch?v=EcbuczlzeBA','EcbuczlzeBA',1),
('https://www.youtube.com/watch?v=Hxh6VtpPGYU','Hxh6VtpPGYU',1),
('https://www.youtube.com/watch?v=xtrwVGlT1-g','xtrwVGlT1-g',1),
('https://www.youtube.com/watch?v=3I1okHFvBgE','3I1okHFvBgE',1),
('https://www.youtube.com/watch?v=AzZEEfy6YNw','AzZEEfy6YNw',1),
('urn:jlas:shadowing:sha256:233ec7a5d89f769da22a7b6bfddbebbb8190ee9dc781794062468532889629e8','L233ec7a5d8',1),
('urn:jlas:shadowing:sha256:f0b36e62bbe36974f675be5778e5f7385ae2aa9d2feff805b649ac65c4401108','Lf0b36e62bb',1),
('urn:jlas:shadowing:sha256:23406970dbf650c86ecbf69eec2337da687f67f3811df43971d5f610efe5d1e4','L23406970db',1),
('https://www.youtube.com/watch?v=0P1Tg2ovSAM','0P1Tg2ovSAM',1),
('https://www.youtube.com/watch?v=lnMkgPBt5HM','lnMkgPBt5HM',1),
('https://www.youtube.com/watch?v=Hi16mimGe0I','Hi16mimGe0I',1),
('https://www.youtube.com/watch?v=7VCvA0DjkLc','7VCvA0DjkLc',1),
('https://www.youtube.com/watch?v=A2bw8P1LIR0','A2bw8P1LIR0',1),
('https://www.youtube.com/watch?v=DwWBufEMxdk','DwWBufEMxdk',1),
('https://www.youtube.com/watch?v=jsYr7GonVhg','jsYr7GonVhg',1),
('https://www.youtube.com/watch?v=CEqNH6KZ4yQ','CEqNH6KZ4yQ',1),
('https://www.youtube.com/watch?v=lx4CQ-4hEVM','lx4CQ-4hEVM',1),
('https://www.youtube.com/watch?v=X3Y9esqDspY','X3Y9esqDspY',1),
('https://www.youtube.com/watch?v=venWKTSh3cs','venWKTSh3cs',1),
('https://www.youtube.com/watch?v=M3lb8-qaaTk','M3lb8-qaaTk',1),
('https://www.youtube.com/watch?v=miiLadou1cw','miiLadou1cw',1),
('https://www.youtube.com/watch?v=l7H11I5I_v8','l7H11I5I_v8',1),
('https://www.youtube.com/watch?v=s8dnPMjiBfs','s8dnPMjiBfs',1),
('https://www.youtube.com/watch?v=YKnowHCkAA8','YKnowHCkAA8',1),
('https://www.youtube.com/watch?v=-EAmvqBZu10','-EAmvqBZu10',1),
('https://www.youtube.com/watch?v=RyGRBBtdAkc','RyGRBBtdAkc',1),
('https://www.youtube.com/watch?v=TppDzkejwzU','TppDzkejwzU',1),
('https://www.youtube.com/watch?v=hoj0Yv987cg','hoj0Yv987cg',1),
('https://www.youtube.com/watch?v=MZ_cL3PqzsQ','MZ_cL3PqzsQ',1),
('https://www.youtube.com/watch?v=TA1pnf_CdhU','TA1pnf_CdhU',1),
('https://www.youtube.com/watch?v=X2MR4N6MT68','X2MR4N6MT68',1),
('https://www.youtube.com/watch?v=n42YvoFwTrk','n42YvoFwTrk',1),
('https://www.youtube.com/watch?v=5gFk7l66UIs','5gFk7l66UIs',1),
('https://www.youtube.com/watch?v=eJqHJ-GuFdM','eJqHJ-GuFdM',1),
('https://www.youtube.com/watch?v=XhMO3G6dBD0','XhMO3G6dBD0',1),
('https://www.youtube.com/watch?v=dJxxqDV_ESM','dJxxqDV_ESM',1),
('https://www.youtube.com/watch?v=zKe_-3rUFj4','zKe_-3rUFj4',1),
('https://www.youtube.com/watch?v=2Y7I9lSgIlc','2Y7I9lSgIlc',1),
('https://www.youtube.com/watch?v=HYX0VT7IDw4','HYX0VT7IDw4',1),
('https://www.youtube.com/watch?v=Wu4Q4VPQ7wQ','Wu4Q4VPQ7wQ',1),
('https://www.youtube.com/watch?v=9nVWgO8C8fs','9nVWgO8C8fs',1),
('https://www.youtube.com/watch?v=m4LCsajU0h0','m4LCsajU0h0',1),
('https://www.youtube.com/watch?v=iP6wS_RTSa4','iP6wS_RTSa4',1),
('https://www.youtube.com/watch?v=ROLAaszgTx0','ROLAaszgTx0',1),
('https://www.youtube.com/watch?v=8_Klmwrd3_8','8_Klmwrd3_8',1),
('https://www.youtube.com/watch?v=wZMTF3AaVzg','wZMTF3AaVzg',1),
('https://www.youtube.com/watch?v=GmPhtZmqTNk','GmPhtZmqTNk',1),
('https://www.youtube.com/watch?v=eCoVNjkZ0t0','eCoVNjkZ0t0',1),
('https://www.youtube.com/watch?v=xoPGClXM4JM','xoPGClXM4JM',1),
('https://www.youtube.com/watch?v=kofLx2CcgGw','kofLx2CcgGw',1),
('https://www.youtube.com/watch?v=gu9QiAsJRdM','gu9QiAsJRdM',1),
('https://www.youtube.com/watch?v=sv7vO0cKwXc','sv7vO0cKwXc',1),
('https://www.youtube.com/watch?v=kKZMqllvWIg','kKZMqllvWIg',1),
('https://www.youtube.com/watch?v=t-hR8QV0Udg','t-hR8QV0Udg',1),
('https://www.youtube.com/watch?v=Qb0HZ-Suwio','Qb0HZ-Suwio',1),
('https://www.youtube.com/watch?v=gvKXfBOShMk','gvKXfBOShMk',1),
('https://www.youtube.com/watch?v=hTN3GClBp48','hTN3GClBp48',1),
('https://www.youtube.com/watch?v=eIA4rUNl9Q8','eIA4rUNl9Q8',1),
('https://www.youtube.com/watch?v=Fwg7PmPu0Xo','Fwg7PmPu0Xo',1),
('https://www.youtube.com/watch?v=pXtPEBKKgUo','pXtPEBKKgUo',1),
('https://www.youtube.com/watch?v=1pV48p396fw','1pV48p396fw',1),
('https://www.youtube.com/watch?v=H_7X9gbB4TE','H_7X9gbB4TE',1),
('https://www.youtube.com/watch?v=RCLyPKAHkFs','RCLyPKAHkFs',1),
('https://www.youtube.com/watch?v=xA9PmpJWOOY','xA9PmpJWOOY',1),
('https://www.youtube.com/watch?v=5CIEWpYjHxs','5CIEWpYjHxs',1),
('https://www.youtube.com/watch?v=fChzc0KWLd0','fChzc0KWLd0',1),
('https://www.youtube.com/watch?v=Yii2N6fNhL0','Yii2N6fNhL0',1),
('https://www.youtube.com/watch?v=9MNSaURmkqQ','9MNSaURmkqQ',1),
('https://www.youtube.com/watch?v=OYcjSKH1NlM','OYcjSKH1NlM',1),
('https://www.youtube.com/watch?v=paDxfWuOqu0','paDxfWuOqu0',1),
('https://www.youtube.com/watch?v=X0ATMWOHoHI','X0ATMWOHoHI',1),
('https://www.youtube.com/watch?v=Wit6XpPIYS8','Wit6XpPIYS8',1),
('https://www.youtube.com/watch?v=KtvdnQLJaCw','KtvdnQLJaCw',1);

START TRANSACTION;

UPDATE video v
JOIN tmp_blob_media t ON CAST(v.source_url AS BINARY) IN (
    CAST(t.source_url AS BINARY),
    CAST(CONCAT('https://youtu.be/', t.source_key) AS BINARY),
    CAST(CONCAT('https://youtube.com/shorts/', t.source_key) AS BINARY),
    CAST(CONCAT('https://www.youtube.com/shorts/', t.source_key) AS BINARY))
SET v.thumbnail_url = IF(t.has_thumbnail = 1 AND (v.thumbnail_url IS NULL OR v.thumbnail_url = ''),
                         CONCAT('blob:shadowing-curated/', t.source_key, '.jpg'), v.thumbnail_url),
    v.video_path = CONCAT('blob:shadowing-curated/', t.source_key, '.mp4')
WHERE v.video_path IS NULL OR v.video_path = '';

COMMIT;

DROP TEMPORARY TABLE IF EXISTS tmp_blob_media;
