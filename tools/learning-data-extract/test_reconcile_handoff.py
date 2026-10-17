import unittest
from reconcile_handoff import (
    sql_values,
    records,
    identity_columns,
    select_learning_workbook,
    reconcile_vocabulary,
    learning_readiness_issues,
    build_issue_register_rows,
)


class ReconciliationTest(unittest.TestCase):
    def test_readiness_identity_is_composite(self):
        self.assertEqual(identity_columns('READINESS', ['entity_type', 'external_key']), ['entity_type', 'external_key'])

    def test_sql_literals_without_execution(self):
        self.assertEqual(sql_values("(1, 'it''s', 'a,b', NULL, -2, 0.5, 'a\\nb', 'x\\\\y');"),
                         [1, "it's", 'a,b', None, -2, 0.5, 'a\nb', 'x\\y'])

    def test_rejects_expressions(self):
        with self.assertRaises(ValueError): sql_values('(1, NOW());')

    def test_contract_and_auxiliary_header_positions(self):
        self.assertEqual(records([[1, ['title']], [4, ['code*', 'value']], [5, ['A', False]]]),
                         [{'_row': 5, 'code': 'A', 'value': False}])
        self.assertEqual(records([[1, ['code']], [2, ['A']], [3, ['B']], [4, ['C']]]),
                         [{'_row': 2, 'code': 'A'}, {'_row': 3, 'code': 'B'}, {'_row': 4, 'code': 'C'}])

    def test_selects_renamed_learning_workbook_by_contract_sheets(self):
        learning = {'LESSON': [], 'VOCABULARY': [], 'AUDIO': []}
        tables = {'assessment-v2.xlsx': {'QUESTION': []}, 'learning-v3.xlsx': learning}
        self.assertIs(select_learning_workbook(tables), learning)

    def test_accepts_identical_duplicate_learning_workbooks_but_rejects_conflicting_versions(self):
        first = {'LESSON': [[4, ['lesson_code*']]], 'VOCABULARY': [], 'AUDIO': []}
        duplicate = {'LESSON': [[4, ['lesson_code*']]], 'VOCABULARY': [], 'AUDIO': []}
        self.assertIn(select_learning_workbook({'learning.xlsx': first, 'copy.xlsx': duplicate}), (first, duplicate))
        conflicting = {'LESSON': [[4, ['different*']]], 'VOCABULARY': [], 'AUDIO': []}
        with self.assertRaisesRegex(ValueError, 'Conflicting learning workbooks'):
            select_learning_workbook({'learning.xlsx': first, 'new.xlsx': conflicting})

    def test_ambiguous_vocabulary_ids_are_not_auto_assigned(self):
        rows = [{'vocab_code': 'VOCAB-undefined', 'expression': 'スグ', 'reading': 'スグ',
                 'source_locator': 'book:p1', 'source_version': 'v1'}]
        canonical = [
            {'id': 1, 'expression': 'すぐ', 'reading': 'すぐ', 'source_locator': 'book:p1', 'source_version': 'v1'},
            {'id': 2, 'expression': 'すぐ', 'reading': 'すぐ', 'source_locator': 'book:p1', 'source_version': 'v1'},
        ]
        result = reconcile_vocabulary(rows, canonical)[0]
        self.assertEqual(result['resolution'], 'MANUAL_CANONICAL_DECISION_REQUIRED')
        self.assertFalse(result['ready_for_import'])

    def test_unique_vocabulary_match_requires_matching_provenance(self):
        source = {'vocab_code': 'VOCAB-undefined', 'expression': 'スグ', 'reading': 'スグ',
                  'source_locator': 'book:p1', 'source_version': 'v1'}
        matching = {'id': 7, 'expression': 'すぐ', 'reading': 'すぐ',
                    'source_locator': 'book:p1', 'source_version': 'v1'}
        accepted = reconcile_vocabulary([source], [matching])[0]
        self.assertEqual(accepted['resolution'], 'UNIQUE_CANONICAL_MATCH_REVIEW_REQUIRED')
        self.assertEqual(accepted['canonical_id'], 7)
        self.assertFalse(accepted['ready_for_import'])

        wrong_source = dict(matching, source_locator='other:p9')
        blocked = reconcile_vocabulary([source], [wrong_source])[0]
        self.assertEqual(blocked['resolution'], 'MANUAL_CANONICAL_DECISION_REQUIRED')
        self.assertIsNone(blocked['canonical_id'])

    def test_published_lesson_without_complete_review_is_blocked(self):
        issues = learning_readiness_issues([
            {'lesson_code': 'N5-L01', 'lifecycle_status': 'PUBLISHED', 'review_status': 'APPROVED',
             'reviewer': '', 'reviewed_at': '', 'source_locator': 'book:p1'},
            {'lesson_code': 'N5-L02', 'lifecycle_status': 'DRAFT', 'review_status': 'NOT_REVIEWED'},
        ])
        self.assertEqual(issues, [{
            'entity_type': 'LESSON', 'external_key': 'N5-L01',
            'issue_code': 'PUBLISHED_WITHOUT_COMPLETE_REVIEW',
            'recommended_status': 'NEEDS_REVIEW',
        }])

    def test_issue_register_combines_identity_and_readiness_blockers(self):
        vocabulary = [{
            'source_row': 9, 'source_code': 'VOCAB-undefined', 'expression': 'すぐ',
            'reading': 'すぐ', 'canonical_id': None, 'candidate_ids': [],
            'resolution': 'MANUAL_CANONICAL_DECISION_REQUIRED', 'ready_for_import': False,
        }]
        lessons = [{'lesson_code': 'N5-L01', 'lifecycle_status': 'PUBLISHED',
                    'review_status': 'NOT_REVIEWED'}]
        rows = build_issue_register_rows(vocabulary, lessons)
        self.assertEqual([row['issue_code'] for row in rows], [
            'MANUAL_CANONICAL_DECISION_REQUIRED', 'PUBLISHED_WITHOUT_COMPLETE_REVIEW'])
        self.assertTrue(all(row['ready_for_import'] is False for row in rows))


if __name__ == '__main__': unittest.main()
