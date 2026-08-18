-- Give the storage aisles an identity.
--
-- The layout has had three aisles since V5 in everything but name: racks in three
-- rows (L-A1..L-A4, L-B*, L-C*), a travel lane per row (W-A -> S-A1..S-A4 -> E-A),
-- and edges named A-1-2, B-2-3, C-3-4. But an aisle was never a row anywhere, so
-- "put this pallet in aisle B" arrived at PlacementAdvisor as free text next to a
-- flat list of slots carrying only id, name and x/z. The mock advisor takes
-- candidates in order and the prompt is not consulted at all, so the instruction
-- was silently dropped -- the pallet went wherever the ordering happened to land.
--
-- Letters rather than numbers: the racks are already A/B/C and so are the route
-- nodes, so "aisle B" needs no translation to reach L-B1..L-B4.
--
--   Aisle A   racks L-A1..L-A4 (z = -8)   lane z = -6
--   Aisle B   racks L-B1..L-B4 (z =  0)   lane z =  2
--   Aisle C   racks L-C1..L-C4 (z =  8)   lane z = 10
--
-- Extent comes from the existing node rows: W-* at x = -18 to E-* at x = 8, so the
-- lane is 26 m long centred at x = -5. Geometry lives here rather than in the
-- renderer for the same reason station footprints do -- the scene needs somewhere
-- to put the floor label, and a layout change should be a migration.

CREATE TABLE aisle (
  id varchar(40) PRIMARY KEY,
  warehouse_id varchar(40) NOT NULL REFERENCES warehouse(id),
  name varchar(120) NOT NULL,
  x double precision NOT NULL,
  z double precision NOT NULL,
  rotation_y double precision NOT NULL DEFAULT 0,
  length double precision NOT NULL,
  width double precision NOT NULL
);

INSERT INTO aisle(id, warehouse_id, name, x, z, rotation_y, length, width) VALUES
  ('A', 'linz', 'Aisle A', -5, -6, 0, 26, 3),
  ('B', 'linz', 'Aisle B', -5,  2, 0, 26, 3),
  ('C', 'linz', 'Aisle C', -5, 10, 0, 26, 3);

-- Backfill from the rack ids themselves rather than restating the mapping by hand.
-- L-A3 -> A. Racks that do not follow the pattern keep a NULL aisle and simply do
-- not participate in aisle-directed putaway.
ALTER TABLE rack ADD COLUMN aisle_id varchar(40) REFERENCES aisle(id);

UPDATE rack SET aisle_id = substring(id from 3 for 1)
WHERE warehouse_id = 'linz'
  AND substring(id from 3 for 1) IN (SELECT id FROM aisle WHERE warehouse_id = 'linz');

CREATE INDEX idx_rack_aisle ON rack(aisle_id);
