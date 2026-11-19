# JLPT mock audio: adult voices and pauses after answer numbers

The 15 `N1_MOCK_001`–`N5_MOCK_003` exams use 444 new MP3 files with calmer male/female voices. Each of the 783 spoken answer labels has a measured 1.25-second quiet interval before the answer text. The 3,914 Japanese/Vietnamese caption pairs are unchanged; their timestamps match the new audio.

The new Azure files use `/audio/JLPT/mock-adult-v2-20261115/{level}/{exam}/Mondai{n}/{question}.mp3`. All 444 files were uploaded and verified for byte length, MD5 and SHA-256. The previous audio remains available for rollback.

## Release order

1. Build and deploy this branch so all 15 files in `src/main/resources/static/JLPT/timed-transcripts/` are available in the running application.
2. Verify the deployed JSON resources under `/JLPT/timed-transcripts/{exam}.json`: their `audioUrl` values must use `/audio/JLPT/mock-adult-v2-20261115/`.
3. Run `sql/patches/jlpt_mock_adult_audio_20261115.sql` against the production `jlas` database, or have the maintainer run the guarded DB updater after the resources have been verified. The SQL checks all 444 clip identities and original Japanese/Vietnamese source hashes. If any source differs, it changes zero URLs.
4. Confirm the final SQL output reports 444 matched sources and 444 new URLs. Open listening practice, especially N3 mock 001 Mondai 4/5, and check audio playback, the pause after each answer number, caption seeking, duration and translation.

This release changes audio URLs and timing resources only. It does not modify answers, scores, translations or exam visibility. The 69 previously archived exams remain archived.

## Rollback

Restore the previous 15 timing JSON resources and run `sql/patches/jlpt_mock_adult_audio_20261115_rollback.sql`. The rollback only resets these 444 URLs when they currently point at this new audio version.

## Validation

- `JlptTimedTranscriptsTest`: 5 tests passed.
- Production `JlptLocalAudio` resolver: 444 new URLs and durations, 3,914 aligned bilingual sentences verified.
- All 444 MP3 files are readable; the approved voice PCM is preserved when inserting the additional silence.
- Actual MP3 quiet intervals were also checked for the N3 Mondai 4/5 examples.
