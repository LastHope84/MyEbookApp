-- ISOLATED EXPERIMENT ONLY. Never load this schema into the app database.
-- A valid lease is an EXTERNAL assertion of a coherent, current provider graph and
-- a complete root/ownership inventory. Android SAF does not generally provide it.
-- Consequently this experiment is NOT permission to hide anything in the app.
PRAGMA foreign_keys = ON;
CREATE TABLE lab_lease (id INTEGER PRIMARY KEY CHECK(id = 1), valid INTEGER NOT NULL CHECK(valid IN (0,1)));
INSERT INTO lab_lease VALUES (1,0);
CREATE TABLE lab_roots (root TEXT PRIMARY KEY, complete INTEGER NOT NULL CHECK(complete IN (0,1)));
CREATE TABLE lab_books (id INTEGER PRIMARY KEY, uri TEXT NOT NULL, annotation TEXT NOT NULL, favorite INTEGER NOT NULL);
CREATE TABLE lab_evidence (
  book_id INTEGER PRIMARY KEY REFERENCES lab_books(id) ON DELETE CASCADE,
  exact_identity INTEGER NOT NULL CHECK(exact_identity IN (0,1)),
  scanner_origin INTEGER NOT NULL CHECK(scanner_origin IN (0,1)),
  independent_owner INTEGER NOT NULL CHECK(independent_owner IN (0,1)),
  unknown_owner INTEGER NOT NULL CHECK(unknown_owner IN (0,1)),
  dedup_uncertain INTEGER NOT NULL CHECK(dedup_uncertain IN (0,1))
);
CREATE TABLE lab_routes (id INTEGER PRIMARY KEY, book_id INTEGER NOT NULL REFERENCES lab_books(id) ON DELETE CASCADE,
  root TEXT NOT NULL REFERENCES lab_roots(root) ON DELETE CASCADE);
CREATE TABLE lab_route_dirs (route_id INTEGER NOT NULL REFERENCES lab_routes(id) ON DELETE CASCADE,
  directory TEXT NOT NULL, PRIMARY KEY(route_id,directory));
CREATE TABLE lab_exclusions (root TEXT NOT NULL REFERENCES lab_roots(root) ON DELETE CASCADE,
  directory TEXT NOT NULL, PRIMARY KEY(root,directory));

-- No mutation/deletion of books: visibility is derived, not stored as a destructive flag.
-- A path is excluded only when that exact root AND an observed directory match.
-- This query is meaningful only under the impossible-to-infer external lease above.
CREATE VIEW lab_hidden AS
SELECT b.id FROM lab_books b JOIN lab_evidence e ON e.book_id=b.id
WHERE (SELECT valid FROM lab_lease WHERE id=1)=1
  AND EXISTS (SELECT 1 FROM lab_roots)
  AND NOT EXISTS (SELECT 1 FROM lab_roots WHERE complete=0)
  AND e.exact_identity=1 AND e.scanner_origin=1 AND e.independent_owner=0
  AND e.unknown_owner=0 AND e.dedup_uncertain=0
  AND EXISTS (SELECT 1 FROM lab_routes r WHERE r.book_id=b.id)
  AND NOT EXISTS (
    SELECT 1 FROM lab_routes r WHERE r.book_id=b.id AND NOT EXISTS (
      SELECT 1 FROM lab_route_dirs d JOIN lab_exclusions x
        ON x.root=r.root AND x.directory=d.directory WHERE d.route_id=r.id
    )
  );
CREATE VIEW lab_visible AS SELECT b.* FROM lab_books b WHERE NOT EXISTS
  (SELECT 1 FROM lab_hidden h WHERE h.id=b.id);

-- Any local ownership/routing/configuration mutation fails open until an external
-- collector re-attests. Does not detect changes in an external provider.
CREATE TRIGGER lab_roots_i AFTER INSERT ON lab_roots BEGIN UPDATE lab_lease SET valid=0; END;
CREATE TRIGGER lab_roots_u AFTER UPDATE ON lab_roots BEGIN UPDATE lab_lease SET valid=0; END;
CREATE TRIGGER lab_roots_d AFTER DELETE ON lab_roots BEGIN UPDATE lab_lease SET valid=0; END;
CREATE TRIGGER lab_books_i AFTER INSERT ON lab_books BEGIN UPDATE lab_lease SET valid=0; END;
CREATE TRIGGER lab_books_u AFTER UPDATE OF uri ON lab_books BEGIN UPDATE lab_lease SET valid=0; END;
CREATE TRIGGER lab_books_d AFTER DELETE ON lab_books BEGIN UPDATE lab_lease SET valid=0; END;
CREATE TRIGGER lab_evidence_i AFTER INSERT ON lab_evidence BEGIN UPDATE lab_lease SET valid=0; END;
CREATE TRIGGER lab_evidence_u AFTER UPDATE ON lab_evidence BEGIN UPDATE lab_lease SET valid=0; END;
CREATE TRIGGER lab_evidence_d AFTER DELETE ON lab_evidence BEGIN UPDATE lab_lease SET valid=0; END;
CREATE TRIGGER lab_routes_i AFTER INSERT ON lab_routes BEGIN UPDATE lab_lease SET valid=0; END;
CREATE TRIGGER lab_routes_u AFTER UPDATE ON lab_routes BEGIN UPDATE lab_lease SET valid=0; END;
CREATE TRIGGER lab_routes_d AFTER DELETE ON lab_routes BEGIN UPDATE lab_lease SET valid=0; END;
CREATE TRIGGER lab_dirs_i AFTER INSERT ON lab_route_dirs BEGIN UPDATE lab_lease SET valid=0; END;
CREATE TRIGGER lab_dirs_u AFTER UPDATE ON lab_route_dirs BEGIN UPDATE lab_lease SET valid=0; END;
CREATE TRIGGER lab_dirs_d AFTER DELETE ON lab_route_dirs BEGIN UPDATE lab_lease SET valid=0; END;
CREATE TRIGGER lab_exclusions_i AFTER INSERT ON lab_exclusions BEGIN UPDATE lab_lease SET valid=0; END;
CREATE TRIGGER lab_exclusions_u AFTER UPDATE ON lab_exclusions BEGIN UPDATE lab_lease SET valid=0; END;
CREATE TRIGGER lab_exclusions_d AFTER DELETE ON lab_exclusions BEGIN UPDATE lab_lease SET valid=0; END;
