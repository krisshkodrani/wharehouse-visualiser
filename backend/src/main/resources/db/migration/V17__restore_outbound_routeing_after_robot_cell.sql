-- The automated robotic cell obstacles currently block all AGV route
-- calculations from storage to OUTBOUND-01 by intersecting the
-- W-C -> OUTBOUND path used by outbound mission planning.
--
-- Keep the robot-cell visualization records but allow outbound routing by
-- removing the three barriers that are not route-safe under forklift clearance.
DELETE FROM warehouse_obstacle
WHERE warehouse_id = 'linz' AND id IN ('ROBOT-CELL-N', 'ROBOT-CELL-W', 'ROBOT-CELL-GATE');
