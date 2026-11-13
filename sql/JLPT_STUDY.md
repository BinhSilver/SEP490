# Related JLPT vocabulary and grammar

For an existing MySQL 8 database, select the JLAS database and run:

1. sql/migrations/075_jlpt_exam_study_lists.sql
2. sql/seeds/jlpt_exam_study_lists.sql

For a fresh database, jlas_schema.sql already includes the two tables. Import the JLPT exams before running the study seed. The seed covers 75 N1–N3 exams; only existing exam codes receive lists. Re-running it preserves existing lists and subsequent manual edits.

The backend reads jlpt_exam_study_list and jlpt_exam_study_item first, on each request. An empty saved list stays empty. Before import, packaged lists remain available; exams without saved or packaged lists use extraction from their questions and the published dictionary/grammar catalog. UNMATCHED_GRAMMAR rows are retained for review and excluded from the grammar list shown to learners.

Deploy the backend and UI from the same revision. Push/merge does not execute SQL against the deployed database.

Validation: mvnw.cmd -Dtest=JlptCuratedStudyTest,JlptStudyServiceTest test
