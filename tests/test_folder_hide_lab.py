"""Executable SQLite experiment, NOT the Android database or an ownership proof.

The lease stands in for a capability SAF does not generally offer. In particular,
external provider changes are NOT caught by SQLite triggers. These tests document
both the proposed fail-open behaviour and its crucial limitation.
"""
from pathlib import Path
import sqlite3
import tempfile
import unittest

SCHEMA = (Path(__file__).with_name('folder_hide_lab.sql')).read_text()

class ReversibleVisibilityLab(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.executescript(SCHEMA)
        self.db.execute("INSERT INTO lab_roots VALUES ('rootA',1)")
        self.db.execute("INSERT INTO lab_books VALUES (1,'content://A/doc','my notes',1)")
        self.db.execute("INSERT INTO lab_evidence VALUES (1,1,1,0,0,0)")
        self.db.execute("INSERT INTO lab_routes VALUES (11,1,'rootA')")
        self.db.executemany('INSERT INTO lab_route_dirs VALUES (11,?)', [('root',),('selected',)])
        self.db.execute("INSERT INTO lab_exclusions VALUES ('rootA','selected')")
        self.db.commit()

    def attest_simulated_only(self):
        self.db.execute('UPDATE lab_lease SET valid=1 WHERE id=1')

    def visible(self):
        return self.db.execute('SELECT id FROM lab_visible ORDER BY id').fetchall()

    def hidden(self):
        return self.db.execute('SELECT id FROM lab_hidden ORDER BY id').fetchall()

    def test_default_is_visible_and_simulated_hide_is_reversible_without_deleting_data(self):
        self.assertEqual(self.visible(), [(1,)])
        self.attest_simulated_only()
        self.assertEqual(self.hidden(), [(1,)])
        self.assertEqual(self.db.execute('SELECT uri,annotation,favorite FROM lab_books').fetchone(),
                         ('content://A/doc','my notes',1))
        self.db.execute("DELETE FROM lab_exclusions WHERE root='rootA' AND directory='selected'")
        self.assertEqual(self.visible(), [(1,)])
        self.assertEqual(self.db.execute('SELECT uri,annotation,favorite FROM lab_books').fetchone(),
                         ('content://A/doc','my notes',1))

    def test_independent_root_or_second_parent_remains_visible(self):
        self.db.execute("INSERT INTO lab_roots VALUES ('rootB',1)")
        self.db.execute("INSERT INTO lab_routes VALUES (12,1,'rootB')")
        self.db.execute("INSERT INTO lab_route_dirs VALUES (12,'selected')")
        self.attest_simulated_only()
        self.assertEqual(self.visible(), [(1,)])  # same ID, different grant
        self.db.execute("DELETE FROM lab_routes WHERE id=12")
        self.db.execute("INSERT INTO lab_routes VALUES (13,1,'rootA')")
        self.db.execute("INSERT INTO lab_route_dirs VALUES (13,'outside')")
        self.attest_simulated_only()
        self.assertEqual(self.visible(), [(1,)])

    def test_all_observed_routes_must_be_excluded(self):
        self.db.execute("INSERT INTO lab_roots VALUES ('rootB',1)")
        self.db.execute("INSERT INTO lab_routes VALUES (12,1,'rootB')")
        self.db.execute("INSERT INTO lab_route_dirs VALUES (12,'same-id')")
        self.db.execute("INSERT INTO lab_exclusions VALUES ('rootB','same-id')")
        self.attest_simulated_only()
        self.assertEqual(self.hidden(), [(1,)])
        self.db.execute("DELETE FROM lab_exclusions WHERE root='rootB'")
        self.attest_simulated_only()
        self.assertEqual(self.visible(), [(1,)])

    def test_uncertain_manual_legacy_or_dedup_rows_are_retained(self):
        for col, value in [('exact_identity',0),('scanner_origin',0),('independent_owner',1),
                           ('unknown_owner',1),('dedup_uncertain',1)]:
            with self.subTest(col=col):
                self.db.execute('SAVEPOINT evidence_test')
                self.db.execute(f'UPDATE lab_evidence SET {col}=? WHERE book_id=1',(value,))
                self.attest_simulated_only()
                self.assertEqual(self.visible(), [(1,)])
                self.db.execute('ROLLBACK TO evidence_test')
                self.db.execute('RELEASE evidence_test')

    def test_missing_evidence_no_route_or_incomplete_root_retains(self):
        for mutation in ["DELETE FROM lab_evidence", "DELETE FROM lab_routes", "UPDATE lab_roots SET complete=0"]:
            with self.subTest(mutation=mutation):
                self.db.execute('SAVEPOINT evidence_test')
                self.db.execute(mutation)
                self.attest_simulated_only()
                self.assertEqual(self.visible(), [(1,)])
                self.db.execute('ROLLBACK TO evidence_test')
                self.db.execute('RELEASE evidence_test')

    def test_any_local_graph_change_invalidates_lease(self):
        changes = ["UPDATE lab_roots SET complete=0", "UPDATE lab_books SET uri='other' WHERE id=1",
                   "UPDATE lab_evidence SET unknown_owner=1", "INSERT INTO lab_routes VALUES (12,1,'rootA')",
                   "INSERT INTO lab_route_dirs VALUES (11,'new')", "DELETE FROM lab_exclusions"]
        for change in changes:
            with self.subTest(change=change):
                self.db.execute('SAVEPOINT change_test')
                self.attest_simulated_only()
                self.assertEqual(self.hidden(), [(1,)])
                self.db.execute(change)
                self.assertEqual(self.db.execute('SELECT valid FROM lab_lease').fetchone(), (0,))
                self.assertEqual(self.visible(), [(1,)])
                self.db.execute('ROLLBACK TO change_test')
                self.db.execute('RELEASE change_test')

    def test_rollback_restores_previous_visibility_and_data(self):
        self.attest_simulated_only()
        self.db.commit()
        self.db.execute('BEGIN')
        self.db.execute('DELETE FROM lab_exclusions')
        self.assertEqual(self.visible(), [(1,)])
        self.db.rollback()
        self.assertEqual(self.hidden(), [(1,)])
        self.assertEqual(self.db.execute('SELECT annotation,favorite FROM lab_books').fetchone(), ('my notes',1))

    def test_provider_changes_cannot_invalidate_local_lease(self):
        # A new external alternate route, a provider move, or a lost grant changes no
        # rows in this local database. This is a counterexample, not an acceptance test.
        self.attest_simulated_only()
        external_provider_now_has_unobserved_route = True
        self.assertTrue(external_provider_now_has_unobserved_route)
        self.assertEqual(self.hidden(), [(1,)])  # stale and potentially WRONG

    def test_stored_mock_lease_survives_restart_without_explicit_invalidation(self):
        # A crash/restart can preserve a stale valid lease. This explicitly shows
        # why the model is not a recoverable production implementation.
        with tempfile.TemporaryDirectory() as directory:
            path = str(Path(directory) / 'lab.db')
            self.attest_simulated_only()
            self.db.commit()
            disk = sqlite3.connect(path)
            self.db.backup(disk)
            disk.close()
            reopened = sqlite3.connect(path)
            self.assertEqual(reopened.execute('SELECT valid FROM lab_lease').fetchone(), (1,))
            self.assertEqual(reopened.execute('SELECT id FROM lab_hidden').fetchall(), [(1,)])
            reopened.execute('UPDATE lab_lease SET valid=0')
            self.assertEqual(reopened.execute('SELECT id FROM lab_visible').fetchall(), [(1,)])
            reopened.close()

    def test_same_prefix_is_not_an_ancestry_or_identity_match(self):
        self.db.execute("DELETE FROM lab_exclusions")
        self.db.execute("INSERT INTO lab_exclusions VALUES ('rootA','select')")
        self.attest_simulated_only()
        self.assertEqual(self.visible(), [(1,)])

if __name__ == '__main__':
    unittest.main()
