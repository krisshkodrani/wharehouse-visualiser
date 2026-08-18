-- Re-plan the outbound zone as a single coherent west-flowing line and retire
-- the three superseded generations of layout that were still on the floor.
--
-- History of the problem: V13 built a westbound conveyor with SHIP-GUARD rails
-- and cut a penetration in the west wall for it. V15 moved the AGV handoff.
-- V16 added a robot cell and two conveyors centred at x=-8. V19 moved the
-- conveyors east to x=-3.2. Each pass added geometry; none removed the previous
-- pass's, so OUT-DCK-01, OUTBOUND-01 and ROBOT-01 all overlapped, two waist-high
-- guard rails stood inside the outbound staging area guarding a conveyor that no
-- longer existed there, and the wall penetration opened onto nothing.
--
-- Worse, the conveyors ran *east*, away from the shipping dock 23 m to the west,
-- so cartons were conveyed into the middle of the storage hall.
--
-- The zone is now one flow, west to east:
--   OUT-DCK-01  <-- CONV-OUT-01/02 (westbound)  <-- ROBOT-01  <-- OUTBOUND-01
--   x -23.3..-17.9   x -17.6..-8.0                 x -7.8..-0.4   x 0.1..5.1
--
-- Hard constraint encoded by LayoutGeometryTest: the C-row travel aisle runs at
-- z=10 and RoutePlanner inflates every obstacle by FORKLIFT_CLEARANCE (0.72 m),
-- so no route-affecting obstacle here may come within 0.72 m of z=10. The
-- original ROBOT-CELL-N sat at z=10.1 and severed the C row from the outbound
-- handoff, which is why V17 had to delete three barriers and V18 trim a fourth.
-- Every cell barrier below is at z >= 11.5, leaving >= 0.7 m of margin.

-- 1. Retire the stale V13 conveyor guards and close the wall penetration.
DELETE FROM warehouse_obstacle WHERE warehouse_id = 'linz' AND id IN ('SHIP-GUARD-N', 'SHIP-GUARD-S');
DELETE FROM warehouse_obstacle WHERE warehouse_id = 'linz' AND id = 'WALL-W-OUT-N';
UPDATE warehouse_obstacle SET z = 0, depth = 35 WHERE warehouse_id = 'linz' AND id = 'WALL-W';

-- 2. Shipping dock against the west wall.
UPDATE location SET x = -20.6, z = 13.9, operating_width = 5.4, operating_depth = 5,
    rotation_y = 3.141592653589793
WHERE warehouse_id = 'linz' AND id = 'OUT-DCK-01';
UPDATE map_node SET x = -20.6, z = 13.9 WHERE warehouse_id = 'linz' AND id = 'OUT-DCK-01';

-- 3. Two westbound conveyor lanes between the cell and the dock. rotation_y = pi
--    is what makes them flow west: the renderer derives cargo travel from the
--    station rotation instead of hard-coding a direction.
UPDATE location SET x = -12.8, z = 13.4, rotation_y = 3.141592653589793,
    operating_width = 9.6, operating_depth = 1.4
WHERE warehouse_id = 'linz' AND id = 'CONV-OUT-01';
UPDATE location SET x = -12.8, z = 15.4, rotation_y = 3.141592653589793,
    operating_width = 9.6, operating_depth = 1.4
WHERE warehouse_id = 'linz' AND id = 'CONV-OUT-02';
UPDATE map_node SET x = -12.8, z = 13.4 WHERE warehouse_id = 'linz' AND id = 'CONV-OUT-01';
UPDATE map_node SET x = -12.8, z = 15.4 WHERE warehouse_id = 'linz' AND id = 'CONV-OUT-02';

-- 4. Robot cell east of the conveyor infeed. operating_width/depth now match the
--    rendered 7.4 x 5.8 guarded footprint; previously the data claimed 5 x 5
--    while the scene drew 7.4 x 5.8, so overlap checks passed on paper and failed
--    on screen. The map node is the arm pedestal, not the cell centre.
UPDATE location SET x = -4.1, z = 14.4, rotation_y = 0,
    operating_width = 7.4, operating_depth = 5.8
WHERE warehouse_id = 'linz' AND id = 'ROBOT-01';
UPDATE map_node SET x = -5.8, z = 14.4 WHERE warehouse_id = 'linz' AND id = 'ROBOT-01';

-- 5. Outbound staging apron east of the cell, and the AGV handoff pose. The
--    vehicle stops at the cell's east gate facing west and reaches its forks
--    1.7 m over the handoff pad at x=-3.6.
UPDATE location SET x = 2.6, z = 14.4, rotation_y = 3.141592653589793,
    operating_width = 5, operating_depth = 5,
    handling_x = -1.9, handling_z = 14.4,
    handling_theta = 3.141592653589793, handling_height = 0.84
WHERE warehouse_id = 'linz' AND id = 'OUTBOUND-01';
UPDATE map_node SET x = -1.9, z = 14.4 WHERE warehouse_id = 'linz' AND id = 'OUTBOUND';

-- 6. Approach node so the vehicle enters through the east gate on a straight
--    z = 14.4 run. Routing directly from S-C4 to the gate clipped the cell's
--    north-east guard once the clearance envelope was applied.
INSERT INTO map_node(id, warehouse_id, x, z, canonical_id)
VALUES ('OUT-APR-01', 'linz', 2.6, 14.4, 'OUT-APR-01')
ON CONFLICT (id) DO UPDATE SET x = excluded.x, z = excluded.z;

DELETE FROM map_edge WHERE warehouse_id = 'linz' AND id IN ('W-C-OUT', 'STG-DCK');
INSERT INTO map_edge(id, warehouse_id, from_node, to_node, cost) VALUES
  ('C4-OUT-APR', 'linz', 'S-C4', 'OUT-APR-01', 4.63),
  ('OUT-APR-STG', 'linz', 'OUT-APR-01', 'OUTBOUND', 4.5),
  ('CONV-DCK-01', 'linz', 'CONV-OUT-01', 'OUT-DCK-01', 7.82)
ON CONFLICT (id) DO UPDATE SET from_node = excluded.from_node, to_node = excluded.to_node, cost = excluded.cost;

-- 7. Cell perimeter: a conveyor opening in the west face and an AGV gate in the
--    east face. V17 deleted the north, west and gate barriers outright to unblock
--    routing, leaving the cell visually open on three sides; these are placed
--    clear of the aisle instead so the cell can be both guarded and passable.
DELETE FROM warehouse_obstacle WHERE warehouse_id = 'linz' AND id LIKE 'ROBOT-CELL-%';
INSERT INTO warehouse_obstacle(id, warehouse_id, type, x, z, width, depth, rotation_y, height) VALUES
  ('ROBOT-CELL-N',   'linz', 'BARRIER', -4.1, 11.50, 7.40, 0.16, 0, 1.4),
  ('ROBOT-CELL-S',   'linz', 'BARRIER', -4.1, 17.30, 7.40, 0.16, 0, 1.4),
  ('ROBOT-CELL-W-N', 'linz', 'BARRIER', -7.8, 12.10, 0.16, 1.20, 0, 1.4),
  ('ROBOT-CELL-W-S', 'linz', 'BARRIER', -7.8, 16.70, 0.16, 1.20, 0, 1.4),
  ('ROBOT-CELL-E-N', 'linz', 'BARRIER', -0.4, 12.25, 0.16, 1.50, 0, 1.4),
  ('ROBOT-CELL-E-S', 'linz', 'BARRIER', -0.4, 16.55, 0.16, 1.50, 0, 1.4);

-- 8. Separate the receiving dock from receiving staging. These overlapped by
--    2.5 m from V16 onwards.
UPDATE location SET x = 15.5, z = -12, operating_width = 6, operating_depth = 7
WHERE warehouse_id = 'linz' AND id = 'INBOUND-01';
UPDATE location SET x = 21, z = -12, operating_width = 4, operating_depth = 5
WHERE warehouse_id = 'linz' AND id = 'REC-DCK-01';
UPDATE map_node SET x = 21, z = -12 WHERE warehouse_id = 'linz' AND id = 'REC-DCK-01';

-- 9. Move quality control and maintenance clear of the charging bays. QA-01
--    overlapped PARK-03 and MAINT-01 overlapped the CHARGE-01 zone.
UPDATE location SET x = 17, z = 8 WHERE warehouse_id = 'linz' AND id = 'QA-01';
UPDATE location SET x = 17, z = 4 WHERE warehouse_id = 'linz' AND id = 'MAINT-01';
UPDATE map_node SET x = 17, z = 8 WHERE warehouse_id = 'linz' AND id = 'QA-01';
UPDATE map_node SET x = 17, z = 4 WHERE warehouse_id = 'linz' AND id = 'MAINT-01';

-- 10. Drop the CHARGE-01 zone. Its 8 x 5 footprint completely contained PARK-02
--     and both were drawn as full parking bays with charger hardware and a floor
--     label on the identical centre point, so the decals z-fought. Nothing reads
--     CHARGING_AREA: the three PARKING_CHARGING bays already carry the charger
--     geometry and the charging indicator.
DELETE FROM map_edge WHERE warehouse_id = 'linz' AND id = 'PARK-02-CHARGE';
DELETE FROM location WHERE warehouse_id = 'linz' AND id = 'CHARGE-01';
DELETE FROM map_node WHERE warehouse_id = 'linz' AND id = 'CHARGE-01';
