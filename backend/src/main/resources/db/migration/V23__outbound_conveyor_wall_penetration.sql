-- Let the outbound conveyors leave the building.
--
-- V13 cut a penetration in the west wall for the conveyor generation that existed
-- then. V21 retired that conveyor, correctly closed the penetration ("the wall
-- penetration opened onto nothing"), re-planned the outbound line as two westbound
-- lanes at z = 13.4 and z = 15.4 -- and never cut a new opening for them.
--
-- So the finished line ran west and stopped dead: both lanes ended at x = -17.6,
-- 5.7 m short of a continuous wall, and cartons were conveyed to the shipping dock
-- only to face blank concrete. The scene compounded it by drawing two shutters on
-- the wall at the dock centre +/- 1.5 m (z = 12.4 and z = 15.4), so one shutter
-- lined up with a lane and the other lined up with nothing.
--
-- A shipping conveyor discharges through the wall into the trailer. This migration
-- makes the geometry say that:
--
--   OUT-DCK-01     | WALL-W  |  CONV-OUT-01/02 (westbound)  <-- ROBOT-01 <-- OUTBOUND-01
--   x -29.2..-25.6 | opening |  x -25.2..-8.0                   x -7.8..-0.4  x 0.1..5.1
--                    z 12.4
--                      ..16.4
--
-- Footprints may touch but never intersect (LayoutValidator.overlappingFootprints),
-- and the dock keeps 0.4 m of clearance from the belt overhang.

-- 1. Cut the opening. WALL-W keeps its identity and covers everything south of the
--    lanes; a short return closes the corner north of them. The opening spans both
--    lane envelopes (12.7..14.1 and 14.7..16.1) with ~0.3 m of reveal each side.
UPDATE warehouse_obstacle SET z = -2.55, depth = 29.9
WHERE warehouse_id = 'linz' AND id = 'WALL-W';

INSERT INTO warehouse_obstacle(id, warehouse_id, type, x, z, width, depth, rotation_y, height)
VALUES ('WALL-W-OUT-N', 'linz', 'WALL', -23.45, 16.95, 0.18, 1.1, 0, 1.1)
ON CONFLICT (id) DO UPDATE SET
  x = excluded.x, z = excluded.z, width = excluded.width, depth = excluded.depth,
  rotation_y = excluded.rotation_y, height = excluded.height;

-- 2. Run both lanes through the opening with a 1.66 m overhang past the outer wall
--    face, which is what makes the discharge read as leaving the building rather
--    than ending at it. The east end does not move, so the robot-cell infeed and
--    the CONVEYOR_INFEED_INSET the renderer applies to it are untouched.
UPDATE location SET x = -16.6, operating_width = 17.2
WHERE warehouse_id = 'linz' AND id IN ('CONV-OUT-01', 'CONV-OUT-02');
UPDATE map_node SET x = -16.6 WHERE warehouse_id = 'linz' AND id IN ('CONV-OUT-01', 'CONV-OUT-02');

-- 3. The truck bay follows the discharge outside. It was against the inside face of
--    the west wall, which is exactly where the lanes now run; leaving it there
--    would have overlapped both of them and tripped the footprint invariant.
--    Centred on the opening rather than on the old dock line, so the trailer sits
--    square to the two belts.
UPDATE location SET x = -27.4, z = 14.4, operating_width = 3.6, operating_depth = 6,
    rotation_y = 3.141592653589793
WHERE warehouse_id = 'linz' AND id = 'OUT-DCK-01';
UPDATE map_node SET x = -27.4, z = 14.4 WHERE warehouse_id = 'linz' AND id = 'OUT-DCK-01';

-- 4. CONV-DCK-01 now crosses the wall line. It clears it: the segment passes the
--    plane at z ~ 14.0, and RoutePlanner inflates the two wall segments by
--    FORKLIFT_CLEARANCE (0.72 m), leaving a passable band of z 13.12..15.68.
--    LayoutValidator checks every edge at boot, so a mistake here is loud.
UPDATE map_edge SET cost = 11.2
WHERE warehouse_id = 'linz' AND id = 'CONV-DCK-01';
