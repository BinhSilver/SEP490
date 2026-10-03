import unittest
from reconcile_handoff import sql_values, records, identity_columns


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


if __name__ == '__main__': unittest.main()
