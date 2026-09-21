# Automated Test Report - Flash Card & Shadowing

## Summary

This report documents the automated test execution for the Flash Card and Shadowing modules in the repository.

Executed date: 2026-09-21
Branch: `test/integration`

## Test command used

```powershell
cd "d:\test\SEP490"; .\mvnw.cmd test "-Dtest=FlashcardCardRepositoryTest,FlashcardSetRepositoryTest,FlashcardStudySessionRepositoryTest,PronunciationAttemptRepositoryTest,ShadowingPracticeSessionRepositoryTest,ShadowingSeriesRepositoryTest,VideoRepositoryTest"
```

## Results

### Flash Card

| Test class | Tests run | Failures | Errors | Status |
| --- | ---: | ---: | ---: | --- |
| FlashcardCardRepositoryTest | 7 | 0 | 0 | PASS |
| FlashcardSetRepositoryTest | 7 | 0 | 0 | PASS |
| FlashcardStudySessionRepositoryTest | 7 | 0 | 0 | PASS |

Total Flash Card tests: 21 passed, 0 failed, 0 errors.

### Shadowing

| Test class | Tests run | Failures | Errors | Status |
| --- | ---: | ---: | ---: | --- |
| PronunciationAttemptRepositoryTest | 5 | 0 | 0 | PASS |
| ShadowingPracticeSessionRepositoryTest | 5 | 0 | 0 | PASS |
| ShadowingSeriesRepositoryTest | 6 | 0 | 0 | PASS |
| VideoRepositoryTest | 9 | 0 | 0 | PASS |

Total Shadowing tests: 25 passed, 0 failed, 0 errors.

### Combined result

- Total tests run: 46
- Failures: 0
- Errors: 0
- Skipped: 0

## Conclusion

The automated repository-level tests for Flash Card and Shadowing are currently passing. There are no failures or errors in the selected test suite for these features.

## Related test artifacts

The generated Surefire reports are stored under:

- `target/surefire-reports/com.jlas.flashcard.repository.FlashcardCardRepositoryTest.txt`
- `target/surefire-reports/com.jlas.flashcard.repository.FlashcardSetRepositoryTest.txt`
- `target/surefire-reports/com.jlas.flashcard.repository.FlashcardStudySessionRepositoryTest.txt`
- `target/surefire-reports/com.jlas.shadowing.repository.PronunciationAttemptRepositoryTest.txt`
- `target/surefire-reports/com.jlas.shadowing.repository.ShadowingPracticeSessionRepositoryTest.txt`
- `target/surefire-reports/com.jlas.shadowing.repository.ShadowingSeriesRepositoryTest.txt`
- `target/surefire-reports/com.jlas.shadowing.repository.VideoRepositoryTest.txt`

This report is prepared for integration branch validation.
