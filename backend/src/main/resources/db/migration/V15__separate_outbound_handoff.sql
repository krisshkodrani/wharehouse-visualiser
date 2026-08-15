-- Keep the conveyor anchored at OUTBOUND-01 (-17, 13), but place the route
-- endpoint where the vehicle body can stop clear of the loading head. With a
-- west-facing truck, its 1.72 m pallet reach lands on the conveyor at x=-14.8.
UPDATE map_node
SET x = -13.1,
    z = 13.0
WHERE warehouse_id = 'linz' AND id = 'OUTBOUND';

UPDATE map_edge
SET cost = 5.745432968
WHERE warehouse_id = 'linz' AND id = 'W-C-OUT';

UPDATE location
SET handling_x = -13.1,
    handling_z = 13.0,
    handling_theta = 3.141592653589793,
    handling_height = 0.84
WHERE warehouse_id = 'linz' AND id = 'OUTBOUND-01';

-- Widen the wall penetration to the full guarded conveyor envelope. This
-- removes the short wall returns from the loading sightline and safety rails.
UPDATE warehouse_obstacle
SET z = -2.975,
    depth = 28.95
WHERE warehouse_id = 'linz' AND id = 'WALL-W';

UPDATE warehouse_obstacle
SET z = 15.975,
    depth = 2.95
WHERE warehouse_id = 'linz' AND id = 'WALL-W-OUT-N';
