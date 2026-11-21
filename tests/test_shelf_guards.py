#!/usr/bin/env python3
"""Host-side smoke tests of the actual SQL and WebView scripts (Node required)."""
import json
import re
import sqlite3
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/example/myebookapp'
READER = (SRC / 'ReaderActivity.kt').read_text()
DAO = (SRC / 'BookDatabase.kt').read_text()


def script(name):
    return re.search(r'private const val ' + name + r' = """(.*?)"""', READER, re.S).group(1)


def eval_js(name, document):
    program = 'const document = ' + json.dumps(document) + '; console.log(' + script(name) + ');'
    return subprocess.check_output(['node', '-e', program], text=True).strip() == 'true'


def doc(top=500, height=1000, width=400):
    return {'readyState': 'complete', 'documentElement': {'clientWidth': 400, 'clientHeight': 500},
            'scrollingElement': {'clientWidth': 400, 'clientHeight': 500, 'scrollWidth': width,
                                 'scrollHeight': height, 'scrollTop': top},
            'fonts': {'status': 'loaded'}, 'images': []}


class Guards(unittest.TestCase):
    def test_scroll_end_not_approximate_progress(self):
        self.assertFalse(eval_js('END_SCROLL_SCRIPT', doc(top=499)))
        self.assertTrue(eval_js('END_SCROLL_SCRIPT', doc(top=500)))
        self.assertTrue(eval_js('END_SCROLL_SCRIPT', doc(top=0, height=400)))
        self.assertFalse(eval_js('END_SCROLL_SCRIPT', doc(top=500, width=800)))

    def test_readiness(self):
        d = doc()
        self.assertTrue(eval_js('READY_SCRIPT', d))
        d['images'] = [{'complete': False, 'naturalWidth': 0}]
        self.assertFalse(eval_js('READY_SCRIPT', d))
        self.assertFalse(eval_js('END_SCROLL_SCRIPT', d))
        d['images'] = []
        d['fonts']['status'] = 'loading'
        self.assertFalse(eval_js('READY_SCRIPT', d))
        d['fonts']['status'] = 'loaded'
        d['readyState'] = 'loading'
        self.assertFalse(eval_js('END_SCROLL_SCRIPT', d))

    def test_actual_dao_queries_are_guarded_and_reversible(self):
        queries = re.findall(r'@Query\("(UPDATE books SET readingStatus = \'(?:READING|FINISHED)\'.*?)"\)', DAO)
        self.assertEqual(len(queries), 2)
        db = sqlite3.connect(':memory:')
        db.execute('CREATE TABLE books (uriString TEXT, readingStatus TEXT, title TEXT)')
        for status in ('NEW', 'TBR', 'READING', 'FINISHED'):
            db.execute('INSERT INTO books VALUES (?, ?, ?)', (status, status, 'original'))
        for status in ('NEW', 'TBR', 'READING', 'FINISHED'):
            db.execute(queries[0], {'uri': status})
        self.assertEqual(db.execute('SELECT readingStatus FROM books ORDER BY rowid').fetchall(),
                         [('READING',), ('READING',), ('READING',), ('FINISHED',)])
        db.execute("UPDATE books SET readingStatus='TBR' WHERE uriString='TBR'")
        for status in ('NEW', 'TBR', 'READING', 'FINISHED'):
            db.execute(queries[1], {'uri': status})
        self.assertEqual(db.execute('SELECT readingStatus FROM books ORDER BY rowid').fetchall(),
                         [('FINISHED',), ('TBR',), ('FINISHED',), ('FINISHED',)])
        self.assertEqual(db.execute("SELECT COUNT(*) FROM books WHERE title != 'original'").fetchone()[0], 0)
        db.execute("UPDATE books SET readingStatus='READING' WHERE uriString='NEW'")
        self.assertEqual(db.execute("SELECT readingStatus FROM books WHERE uriString='NEW'").fetchone()[0], 'READING')

if __name__ == '__main__':
    unittest.main()
