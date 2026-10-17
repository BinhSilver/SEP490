-- [JLAS-W0205] Approved AI Call voice diversity. Apply after catalog migration 015.
-- Data only: scenario.flow_json already exists; no table/column/schema alteration.
-- Guards check role and prior voice at every JSON path before updating each scenario.
-- Does not modify sessions, messages, learning content, or prerecorded sample clips.
SET NAMES utf8mb4;
START TRANSACTION;

-- station-directions-and-transfer
UPDATE scenario SET flow_json = JSON_SET(flow_json,
    '$.stages[0].speakers[0].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.stages[1].speakers[0].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.stages[2].speakers[0].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.stages[3].speakers[0].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.stages[4].speakers[0].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.stages[5].speakers[0].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.speakers[0].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59')
WHERE slug = 'station-directions-and-transfer'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[0].speakers[0].voiceId')) = '835eddd64e474271a042b175425afc39' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[0].speakers[0].code')) = 'AI08'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[1].speakers[0].voiceId')) = '835eddd64e474271a042b175425afc39' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[1].speakers[0].code')) = 'AI08'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[2].speakers[0].voiceId')) = '835eddd64e474271a042b175425afc39' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[2].speakers[0].code')) = 'AI08'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[3].speakers[0].voiceId')) = '835eddd64e474271a042b175425afc39' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[3].speakers[0].code')) = 'AI08'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[4].speakers[0].voiceId')) = '835eddd64e474271a042b175425afc39' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[4].speakers[0].code')) = 'AI08'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[5].speakers[0].voiceId')) = '835eddd64e474271a042b175425afc39' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[5].speakers[0].code')) = 'AI08'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[0].voiceId')) = '835eddd64e474271a042b175425afc39' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[0].code')) = 'AI08';

-- station-directions-and-transfer--missed-train
UPDATE scenario SET flow_json = JSON_SET(flow_json,
    '$.speakers[0].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59')
WHERE slug = 'station-directions-and-transfer--missed-train'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[0].voiceId')) = '835eddd64e474271a042b175425afc39' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[0].code')) = 'AI08';

-- station-directions-and-transfer--wrong-line
UPDATE scenario SET flow_json = JSON_SET(flow_json,
    '$.speakers[0].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59')
WHERE slug = 'station-directions-and-transfer--wrong-line'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[0].voiceId')) = '835eddd64e474271a042b175425afc39' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[0].code')) = 'AI08';

-- office-reschedule-meeting
UPDATE scenario SET flow_json = JSON_SET(flow_json,
    '$.stages[0].speakers[1].voiceId', '45c5d3723c9c42f598e4776dcfd5f02d',
    '$.stages[1].speakers[1].voiceId', '45c5d3723c9c42f598e4776dcfd5f02d',
    '$.stages[2].speakers[1].voiceId', '45c5d3723c9c42f598e4776dcfd5f02d',
    '$.stages[3].speakers[1].voiceId', '45c5d3723c9c42f598e4776dcfd5f02d',
    '$.stages[4].speakers[1].voiceId', '45c5d3723c9c42f598e4776dcfd5f02d',
    '$.stages[5].speakers[1].voiceId', '45c5d3723c9c42f598e4776dcfd5f02d',
    '$.speakers[1].voiceId', '45c5d3723c9c42f598e4776dcfd5f02d')
WHERE slug = 'office-reschedule-meeting'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[0].speakers[1].voiceId')) = 'cc0b286e294a43d7bdfbe1f26446b984' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[0].speakers[1].code')) = 'AI19'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[1].speakers[1].voiceId')) = 'cc0b286e294a43d7bdfbe1f26446b984' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[1].speakers[1].code')) = 'AI19'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[2].speakers[1].voiceId')) = 'cc0b286e294a43d7bdfbe1f26446b984' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[2].speakers[1].code')) = 'AI19'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[3].speakers[1].voiceId')) = 'cc0b286e294a43d7bdfbe1f26446b984' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[3].speakers[1].code')) = 'AI19'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[4].speakers[1].voiceId')) = 'cc0b286e294a43d7bdfbe1f26446b984' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[4].speakers[1].code')) = 'AI19'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[5].speakers[1].voiceId')) = 'cc0b286e294a43d7bdfbe1f26446b984' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[5].speakers[1].code')) = 'AI19'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[1].voiceId')) = 'cc0b286e294a43d7bdfbe1f26446b984' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[1].code')) = 'AI19';

-- office-reschedule-meeting--time-rejected
UPDATE scenario SET flow_json = JSON_SET(flow_json,
    '$.speakers[1].voiceId', '45c5d3723c9c42f598e4776dcfd5f02d')
WHERE slug = 'office-reschedule-meeting--time-rejected'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[1].voiceId')) = 'cc0b286e294a43d7bdfbe1f26446b984' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[1].code')) = 'AI19';

-- office-reschedule-meeting--online-meeting
UPDATE scenario SET flow_json = JSON_SET(flow_json,
    '$.speakers[1].voiceId', '45c5d3723c9c42f598e4776dcfd5f02d')
WHERE slug = 'office-reschedule-meeting--online-meeting'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[1].voiceId')) = 'cc0b286e294a43d7bdfbe1f26446b984' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[1].code')) = 'AI19';

-- school-absence-and-homework
UPDATE scenario SET flow_json = JSON_SET(flow_json,
    '$.stages[0].speakers[1].voiceId', '6a33cc6c58994fa1af41f578075e8357',
    '$.stages[1].speakers[1].voiceId', '6a33cc6c58994fa1af41f578075e8357',
    '$.stages[2].speakers[1].voiceId', '6a33cc6c58994fa1af41f578075e8357',
    '$.stages[3].speakers[1].voiceId', '6a33cc6c58994fa1af41f578075e8357',
    '$.stages[4].speakers[1].voiceId', '6a33cc6c58994fa1af41f578075e8357',
    '$.speakers[1].voiceId', '6a33cc6c58994fa1af41f578075e8357')
WHERE slug = 'school-absence-and-homework'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[0].speakers[1].voiceId')) = 'da9c1d6ff8664d26926112ac79a1d32b' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[0].speakers[1].code')) = 'AI22'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[1].speakers[1].voiceId')) = 'da9c1d6ff8664d26926112ac79a1d32b' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[1].speakers[1].code')) = 'AI22'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[2].speakers[1].voiceId')) = 'da9c1d6ff8664d26926112ac79a1d32b' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[2].speakers[1].code')) = 'AI22'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[3].speakers[1].voiceId')) = 'da9c1d6ff8664d26926112ac79a1d32b' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[3].speakers[1].code')) = 'AI22'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[4].speakers[1].voiceId')) = 'da9c1d6ff8664d26926112ac79a1d32b' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[4].speakers[1].code')) = 'AI22'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[1].voiceId')) = 'da9c1d6ff8664d26926112ac79a1d32b' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[1].code')) = 'AI22';

-- school-absence-and-homework--homework-deadline
UPDATE scenario SET flow_json = JSON_SET(flow_json,
    '$.speakers[1].voiceId', '6a33cc6c58994fa1af41f578075e8357')
WHERE slug = 'school-absence-and-homework--homework-deadline'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[1].voiceId')) = 'da9c1d6ff8664d26926112ac79a1d32b' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[1].code')) = 'AI22';

-- school-absence-and-homework--return-to-school
UPDATE scenario SET flow_json = JSON_SET(flow_json,
    '$.speakers[1].voiceId', '6a33cc6c58994fa1af41f578075e8357')
WHERE slug = 'school-absence-and-homework--return-to-school'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[1].voiceId')) = 'da9c1d6ff8664d26926112ac79a1d32b' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[1].code')) = 'AI22';

-- hotel-check-in
UPDATE scenario SET flow_json = JSON_SET(flow_json,
    '$.stages[0].speakers[2].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.stages[1].speakers[2].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.stages[2].speakers[2].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.stages[3].speakers[2].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.stages[4].speakers[2].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.stages[5].speakers[2].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.stages[6].speakers[2].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.speakers[2].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59')
WHERE slug = 'hotel-check-in'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[0].speakers[2].voiceId')) = '3155b947de204555be8fff36a2bed0da' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[0].speakers[2].code')) = 'AI25'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[1].speakers[2].voiceId')) = '3155b947de204555be8fff36a2bed0da' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[1].speakers[2].code')) = 'AI25'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[2].speakers[2].voiceId')) = '3155b947de204555be8fff36a2bed0da' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[2].speakers[2].code')) = 'AI25'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[3].speakers[2].voiceId')) = '3155b947de204555be8fff36a2bed0da' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[3].speakers[2].code')) = 'AI25'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[4].speakers[2].voiceId')) = '3155b947de204555be8fff36a2bed0da' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[4].speakers[2].code')) = 'AI25'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[5].speakers[2].voiceId')) = '3155b947de204555be8fff36a2bed0da' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[5].speakers[2].code')) = 'AI25'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[6].speakers[2].voiceId')) = '3155b947de204555be8fff36a2bed0da' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[6].speakers[2].code')) = 'AI25'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[2].voiceId')) = '3155b947de204555be8fff36a2bed0da' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[2].code')) = 'AI25';

-- hotel-check-in--booking-not-found
UPDATE scenario SET flow_json = JSON_SET(flow_json,
    '$.speakers[2].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59')
WHERE slug = 'hotel-check-in--booking-not-found'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[2].voiceId')) = '3155b947de204555be8fff36a2bed0da' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[2].code')) = 'AI25';

-- hotel-check-in--room-problem
UPDATE scenario SET flow_json = JSON_SET(flow_json,
    '$.speakers[2].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59')
WHERE slug = 'hotel-check-in--room-problem'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[2].voiceId')) = '3155b947de204555be8fff36a2bed0da' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[2].code')) = 'AI25';

-- part-time-job-interview
UPDATE scenario SET flow_json = JSON_SET(flow_json,
    '$.stages[0].speakers[0].voiceId', '45c5d3723c9c42f598e4776dcfd5f02d',
    '$.stages[1].speakers[0].voiceId', '45c5d3723c9c42f598e4776dcfd5f02d',
    '$.stages[2].speakers[0].voiceId', '45c5d3723c9c42f598e4776dcfd5f02d',
    '$.stages[3].speakers[0].voiceId', '45c5d3723c9c42f598e4776dcfd5f02d',
    '$.stages[4].speakers[0].voiceId', '45c5d3723c9c42f598e4776dcfd5f02d',
    '$.speakers[0].voiceId', '45c5d3723c9c42f598e4776dcfd5f02d')
WHERE slug = 'part-time-job-interview'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[0].speakers[0].voiceId')) = 'cc0b286e294a43d7bdfbe1f26446b984' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[0].speakers[0].code')) = 'AI28'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[1].speakers[0].voiceId')) = 'cc0b286e294a43d7bdfbe1f26446b984' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[1].speakers[0].code')) = 'AI28'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[2].speakers[0].voiceId')) = 'cc0b286e294a43d7bdfbe1f26446b984' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[2].speakers[0].code')) = 'AI28'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[3].speakers[0].voiceId')) = 'cc0b286e294a43d7bdfbe1f26446b984' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[3].speakers[0].code')) = 'AI28'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[4].speakers[0].voiceId')) = 'cc0b286e294a43d7bdfbe1f26446b984' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[4].speakers[0].code')) = 'AI28'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[0].voiceId')) = 'cc0b286e294a43d7bdfbe1f26446b984' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[0].code')) = 'AI28';

-- part-time-job-interview--no-experience
UPDATE scenario SET flow_json = JSON_SET(flow_json,
    '$.speakers[0].voiceId', '45c5d3723c9c42f598e4776dcfd5f02d')
WHERE slug = 'part-time-job-interview--no-experience'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[0].voiceId')) = 'cc0b286e294a43d7bdfbe1f26446b984' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[0].code')) = 'AI28';

-- part-time-job-interview--schedule-conflict
UPDATE scenario SET flow_json = JSON_SET(flow_json,
    '$.speakers[0].voiceId', '45c5d3723c9c42f598e4776dcfd5f02d')
WHERE slug = 'part-time-job-interview--schedule-conflict'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[0].voiceId')) = 'cc0b286e294a43d7bdfbe1f26446b984' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[0].code')) = 'AI28';

-- service-complaint-return
UPDATE scenario SET flow_json = JSON_SET(flow_json,
    '$.stages[0].speakers[1].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.stages[1].speakers[1].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.stages[2].speakers[1].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.stages[3].speakers[1].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.stages[4].speakers[1].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.speakers[1].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59')
WHERE slug = 'service-complaint-return'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[0].speakers[1].voiceId')) = '3155b947de204555be8fff36a2bed0da' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[0].speakers[1].code')) = 'AI31'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[1].speakers[1].voiceId')) = '3155b947de204555be8fff36a2bed0da' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[1].speakers[1].code')) = 'AI31'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[2].speakers[1].voiceId')) = '3155b947de204555be8fff36a2bed0da' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[2].speakers[1].code')) = 'AI31'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[3].speakers[1].voiceId')) = '3155b947de204555be8fff36a2bed0da' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[3].speakers[1].code')) = 'AI31'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[4].speakers[1].voiceId')) = '3155b947de204555be8fff36a2bed0da' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[4].speakers[1].code')) = 'AI31'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[1].voiceId')) = '3155b947de204555be8fff36a2bed0da' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[1].code')) = 'AI31';

-- service-complaint-return--missing-receipt
UPDATE scenario SET flow_json = JSON_SET(flow_json,
    '$.speakers[1].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59')
WHERE slug = 'service-complaint-return--missing-receipt'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[1].voiceId')) = '3155b947de204555be8fff36a2bed0da' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[1].code')) = 'AI31';

-- service-complaint-return--exchange-unavailable
UPDATE scenario SET flow_json = JSON_SET(flow_json,
    '$.speakers[1].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59')
WHERE slug = 'service-complaint-return--exchange-unavailable'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[1].voiceId')) = '3155b947de204555be8fff36a2bed0da' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[1].code')) = 'AI31';

-- project-deadline-negotiation
UPDATE scenario SET flow_json = JSON_SET(flow_json,
    '$.stages[0].speakers[0].voiceId', '45c5d3723c9c42f598e4776dcfd5f02d',
    '$.stages[0].speakers[1].voiceId', '6a33cc6c58994fa1af41f578075e8357',
    '$.stages[1].speakers[0].voiceId', '45c5d3723c9c42f598e4776dcfd5f02d',
    '$.stages[1].speakers[1].voiceId', '6a33cc6c58994fa1af41f578075e8357',
    '$.stages[2].speakers[0].voiceId', '45c5d3723c9c42f598e4776dcfd5f02d',
    '$.stages[2].speakers[1].voiceId', '6a33cc6c58994fa1af41f578075e8357',
    '$.stages[3].speakers[0].voiceId', '45c5d3723c9c42f598e4776dcfd5f02d',
    '$.stages[3].speakers[1].voiceId', '6a33cc6c58994fa1af41f578075e8357',
    '$.stages[4].speakers[0].voiceId', '45c5d3723c9c42f598e4776dcfd5f02d',
    '$.stages[4].speakers[1].voiceId', '6a33cc6c58994fa1af41f578075e8357',
    '$.speakers[0].voiceId', '45c5d3723c9c42f598e4776dcfd5f02d',
    '$.speakers[1].voiceId', '6a33cc6c58994fa1af41f578075e8357')
WHERE slug = 'project-deadline-negotiation'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[0].speakers[0].voiceId')) = 'cc0b286e294a43d7bdfbe1f26446b984' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[0].speakers[0].code')) = 'AI33'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[0].speakers[1].voiceId')) = 'da9c1d6ff8664d26926112ac79a1d32b' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[0].speakers[1].code')) = 'AI34'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[1].speakers[0].voiceId')) = 'cc0b286e294a43d7bdfbe1f26446b984' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[1].speakers[0].code')) = 'AI33'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[1].speakers[1].voiceId')) = 'da9c1d6ff8664d26926112ac79a1d32b' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[1].speakers[1].code')) = 'AI34'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[2].speakers[0].voiceId')) = 'cc0b286e294a43d7bdfbe1f26446b984' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[2].speakers[0].code')) = 'AI33'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[2].speakers[1].voiceId')) = 'da9c1d6ff8664d26926112ac79a1d32b' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[2].speakers[1].code')) = 'AI34'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[3].speakers[0].voiceId')) = 'cc0b286e294a43d7bdfbe1f26446b984' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[3].speakers[0].code')) = 'AI33'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[3].speakers[1].voiceId')) = 'da9c1d6ff8664d26926112ac79a1d32b' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[3].speakers[1].code')) = 'AI34'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[4].speakers[0].voiceId')) = 'cc0b286e294a43d7bdfbe1f26446b984' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[4].speakers[0].code')) = 'AI33'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[4].speakers[1].voiceId')) = 'da9c1d6ff8664d26926112ac79a1d32b' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[4].speakers[1].code')) = 'AI34'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[0].voiceId')) = 'cc0b286e294a43d7bdfbe1f26446b984' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[0].code')) = 'AI33'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[1].voiceId')) = 'da9c1d6ff8664d26926112ac79a1d32b' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[1].code')) = 'AI34';

-- project-deadline-negotiation--delay
UPDATE scenario SET flow_json = JSON_SET(flow_json,
    '$.speakers[0].voiceId', '45c5d3723c9c42f598e4776dcfd5f02d',
    '$.speakers[1].voiceId', '6a33cc6c58994fa1af41f578075e8357')
WHERE slug = 'project-deadline-negotiation--delay'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[0].voiceId')) = 'cc0b286e294a43d7bdfbe1f26446b984' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[0].code')) = 'AI33'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[1].voiceId')) = 'da9c1d6ff8664d26926112ac79a1d32b' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[1].code')) = 'AI34';

-- project-deadline-negotiation--scope-change
UPDATE scenario SET flow_json = JSON_SET(flow_json,
    '$.speakers[0].voiceId', '45c5d3723c9c42f598e4776dcfd5f02d',
    '$.speakers[1].voiceId', '6a33cc6c58994fa1af41f578075e8357')
WHERE slug = 'project-deadline-negotiation--scope-change'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[0].voiceId')) = 'cc0b286e294a43d7bdfbe1f26446b984' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[0].code')) = 'AI33'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[1].voiceId')) = 'da9c1d6ff8664d26926112ac79a1d32b' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[1].code')) = 'AI34';

-- rental-contract-negotiation
UPDATE scenario SET flow_json = JSON_SET(flow_json,
    '$.stages[0].speakers[0].voiceId', '45c5d3723c9c42f598e4776dcfd5f02d',
    '$.stages[0].speakers[2].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.stages[1].speakers[0].voiceId', '45c5d3723c9c42f598e4776dcfd5f02d',
    '$.stages[1].speakers[2].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.stages[2].speakers[0].voiceId', '45c5d3723c9c42f598e4776dcfd5f02d',
    '$.stages[2].speakers[2].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.stages[3].speakers[0].voiceId', '45c5d3723c9c42f598e4776dcfd5f02d',
    '$.stages[3].speakers[2].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.stages[4].speakers[0].voiceId', '45c5d3723c9c42f598e4776dcfd5f02d',
    '$.stages[4].speakers[2].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.stages[5].speakers[0].voiceId', '45c5d3723c9c42f598e4776dcfd5f02d',
    '$.stages[5].speakers[2].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.speakers[0].voiceId', '45c5d3723c9c42f598e4776dcfd5f02d',
    '$.speakers[2].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59')
WHERE slug = 'rental-contract-negotiation'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[0].speakers[0].voiceId')) = 'cc0b286e294a43d7bdfbe1f26446b984' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[0].speakers[0].code')) = 'AI37'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[0].speakers[2].voiceId')) = '3155b947de204555be8fff36a2bed0da' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[0].speakers[2].code')) = 'AI38'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[1].speakers[0].voiceId')) = 'cc0b286e294a43d7bdfbe1f26446b984' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[1].speakers[0].code')) = 'AI37'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[1].speakers[2].voiceId')) = '3155b947de204555be8fff36a2bed0da' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[1].speakers[2].code')) = 'AI38'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[2].speakers[0].voiceId')) = 'cc0b286e294a43d7bdfbe1f26446b984' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[2].speakers[0].code')) = 'AI37'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[2].speakers[2].voiceId')) = '3155b947de204555be8fff36a2bed0da' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[2].speakers[2].code')) = 'AI38'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[3].speakers[0].voiceId')) = 'cc0b286e294a43d7bdfbe1f26446b984' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[3].speakers[0].code')) = 'AI37'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[3].speakers[2].voiceId')) = '3155b947de204555be8fff36a2bed0da' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[3].speakers[2].code')) = 'AI38'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[4].speakers[0].voiceId')) = 'cc0b286e294a43d7bdfbe1f26446b984' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[4].speakers[0].code')) = 'AI37'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[4].speakers[2].voiceId')) = '3155b947de204555be8fff36a2bed0da' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[4].speakers[2].code')) = 'AI38'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[5].speakers[0].voiceId')) = 'cc0b286e294a43d7bdfbe1f26446b984' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[5].speakers[0].code')) = 'AI37'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[5].speakers[2].voiceId')) = '3155b947de204555be8fff36a2bed0da' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[5].speakers[2].code')) = 'AI38'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[0].voiceId')) = 'cc0b286e294a43d7bdfbe1f26446b984' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[0].code')) = 'AI37'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[2].voiceId')) = '3155b947de204555be8fff36a2bed0da' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[2].code')) = 'AI38';

-- rental-contract-negotiation--repair
UPDATE scenario SET flow_json = JSON_SET(flow_json,
    '$.speakers[0].voiceId', '45c5d3723c9c42f598e4776dcfd5f02d',
    '$.speakers[2].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59')
WHERE slug = 'rental-contract-negotiation--repair'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[0].voiceId')) = 'cc0b286e294a43d7bdfbe1f26446b984' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[0].code')) = 'AI37'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[2].voiceId')) = '3155b947de204555be8fff36a2bed0da' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[2].code')) = 'AI38';

-- rental-contract-negotiation--move-out
UPDATE scenario SET flow_json = JSON_SET(flow_json,
    '$.speakers[0].voiceId', '45c5d3723c9c42f598e4776dcfd5f02d',
    '$.speakers[2].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59')
WHERE slug = 'rental-contract-negotiation--move-out'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[0].voiceId')) = 'cc0b286e294a43d7bdfbe1f26446b984' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[0].code')) = 'AI37'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[2].voiceId')) = '3155b947de204555be8fff36a2bed0da' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[2].code')) = 'AI38';

COMMIT;
