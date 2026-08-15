-- Open the west wall around the conveyor centreline. The retained WALL-W row
-- covers the lower section so existing obstacle identities remain stable.
UPDATE warehouse_obstacle
SET z = -2.65,
    depth = 29.7
WHERE warehouse_id = 'linz' AND id = 'WALL-W';

INSERT INTO warehouse_obstacle(id,warehouse_id,type,x,z,width,depth,rotation_y,height)
VALUES ('WALL-W-OUT-N','linz','WALL',-23.45,15.7,0.18,3.5,0,1.1)
ON CONFLICT (id) DO UPDATE SET
  x=excluded.x,z=excluded.z,width=excluded.width,depth=excluded.depth,
  rotation_y=excluded.rotation_y,height=excluded.height;

-- Guard the long sides of the westbound conveyor. The shorter south guard
-- stops before the loading head so W-C -> OUTBOUND remains an open AGV route.
UPDATE warehouse_obstacle
SET id = 'SHIP-GUARD-N', x = -19.2, z = 14.0, width = 8.4, depth = 0.16,
    rotation_y = 0, height = 0.9
WHERE warehouse_id = 'linz' AND id = 'SHIP-GUARD-E';

UPDATE warehouse_obstacle
SET x = -20.7, z = 12.0, width = 5.4, depth = 0.16,
    rotation_y = 0, height = 0.9
WHERE warehouse_id = 'linz' AND id = 'SHIP-GUARD-S';
