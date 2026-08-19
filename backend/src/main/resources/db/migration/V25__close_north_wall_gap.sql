-- Close the 8 m hole in the north wall.
--
-- V5 seeded the north elevation as two segments, WALL-NW covering x -24..-20 and
-- WALL-N covering x -12..24, leaving x -20..-12 open. Nothing has ever passed
-- through it. The outbound line runs *west*: CONV-OUT-01/02 occupy z 12.7..16.1 and
-- discharge through the west-wall penetration V23 cut at z 12.4..16.4, so they stop
-- 1.35 m short of the north wall line and turn nowhere near it. The receiving
-- traffic is on the opposite elevation entirely, through the south gap at x 12..20
-- where REC-DCK-01 sits.
--
-- So this is the same defect V21 described when it closed the previous one: an
-- opening onto nothing. From inside the shipping hall you could see straight out of
-- the building, past the ends of both belts.
--
--   WALL-NW    |        gap        |  WALL-N
--   x -24..-20 |    x -20..-12     |  x -12..24          all at z = 17.45
--                 nothing crosses
--
-- Closing it is safe against both boot-time invariants:
--
--   * Routing. There is no map_node anywhere above z = 15.5, so no edge crosses the
--     wall line here. RoutePlanner inflates walls by FORKLIFT_CLEARANCE (0.72 m),
--     giving a blocked band of z 16.64..18.26 that still contains no node.
--   * Footprints. The nearest is CONV-OUT-02 at z 14.7..16.1, which clears the new
--     segment's z 17.36..17.54 by 1.26 m, so overlappingFootprints stays satisfied.
--
-- Dimensions match the segments either side exactly -- 0.18 deep, 1.1 high -- so the
-- finished elevation reads as one continuous wall rather than a patch.

INSERT INTO warehouse_obstacle(id, warehouse_id, type, x, z, width, depth, rotation_y, height)
VALUES ('WALL-N-MID', 'linz', 'WALL', -16, 17.45, 8, 0.18, 0, 1.1)
ON CONFLICT (id) DO UPDATE SET
  x = excluded.x, z = excluded.z, width = excluded.width, depth = excluded.depth,
  rotation_y = excluded.rotation_y, height = excluded.height;
