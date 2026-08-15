-- The first horizontal-discharge layout left only 0.67 m between the diagonal
-- W-C -> OUTBOUND route and the south guard. Keep the guard anchored at the
-- west wall, but stop it before the AGV's 0.72 m clearance envelope.
UPDATE warehouse_obstacle
SET x = -21.15,
    width = 4.5
WHERE warehouse_id = 'linz' AND id = 'SHIP-GUARD-S';
