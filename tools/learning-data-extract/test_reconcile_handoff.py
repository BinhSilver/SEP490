import unittest
from reconcile_handoff import sql_values, records, identity_columns, select_learning_workbook


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


if __name__ == '__main__': unittest.main()
