CREATE TABLE warehouse_obstacle (
  id varchar(60) PRIMARY KEY,
  warehouse_id varchar(40) NOT NULL REFERENCES warehouse(id),
  type varchar(20) NOT NULL,
  x double precision NOT NULL,
  z double precision NOT NULL,
  width double precision NOT NULL,
  depth double precision NOT NULL,
  rotation_y double precision NOT NULL DEFAULT 0,
  height double precision NOT NULL,
  CONSTRAINT warehouse_obstacle_type CHECK (type IN ('WALL','BARRIER'))
);

ALTER TABLE location ADD COLUMN rotation_y double precision NOT NULL DEFAULT 0;
ALTER TABLE location ADD COLUMN operating_width double precision;
ALTER TABLE location ADD COLUMN operating_depth double precision;

DELETE FROM map_edge WHERE warehouse_id = 'linz';

INSERT INTO map_node(id,warehouse_id,x,z) VALUES
 ('INBOUND','linz',17,-12),('I-CROSS','linz',8,-12),
 ('W-A','linz',-18,-6),('S-A1','linz',-14,-6),('S-A2','linz',-8,-6),('S-A3','linz',-2,-6),('S-A4','linz',4,-6),('E-A','linz',8,-6),
 ('W-B','linz',-18,2),('S-B1','linz',-14,2),('S-B2','linz',-8,2),('S-B3','linz',-2,2),('S-B4','linz',4,2),('E-B','linz',8,2),
 ('W-C','linz',-18,10),('S-C1','linz',-14,10),('S-C2','linz',-8,10),('S-C3','linz',-2,10),('S-C4','linz',4,10),('E-C','linz',8,10),
 ('OUTBOUND','linz',-17,13)
ON CONFLICT (id) DO UPDATE SET x=excluded.x,z=excluded.z;

UPDATE rack SET x=-14,z=-8,bays=4,name='Rack A1' WHERE id='L-A1';
UPDATE rack SET x=-8,z=-8,bays=4,name='Rack A2' WHERE id='L-A2';
UPDATE rack SET x=-2,z=-8,bays=4,name='Rack A3' WHERE id='L-A3';
UPDATE rack SET x=-14,z=0,bays=4,name='Rack B1' WHERE id='L-B1';
UPDATE rack SET x=-8,z=0,bays=4,name='Rack B2' WHERE id='L-B2';
UPDATE rack SET x=-2,z=0,bays=4,name='Rack B3' WHERE id='L-B3';
DELETE FROM rack WHERE id='L-D1';
INSERT INTO rack(id,warehouse_id,name,x,z,rotation_y,bays) VALUES
 ('L-A4','linz','Rack A4',4,-8,0,4),
 ('L-B4','linz','Rack B4',4,0,0,4),
 ('L-C1','linz','Rack C1',-14,8,0,4),
 ('L-C2','linz','Rack C2',-8,8,0,4),
 ('L-C3','linz','Rack C3',-2,8,0,4),
 ('L-C4','linz','Rack C4',4,8,0,4);

UPDATE location l SET x=r.x,z=r.z,map_node_id=
  CASE r.id
    WHEN 'L-A1' THEN 'S-A1' WHEN 'L-A2' THEN 'S-A2' WHEN 'L-A3' THEN 'S-A3' WHEN 'L-A4' THEN 'S-A4'
    WHEN 'L-B1' THEN 'S-B1' WHEN 'L-B2' THEN 'S-B2' WHEN 'L-B3' THEN 'S-B3' WHEN 'L-B4' THEN 'S-B4'
    WHEN 'L-C1' THEN 'S-C1' WHEN 'L-C2' THEN 'S-C2' WHEN 'L-C3' THEN 'S-C3' WHEN 'L-C4' THEN 'S-C4'
  END
FROM rack r WHERE l.rack_id=r.id AND l.type='STORAGE';

INSERT INTO location(id,warehouse_id,name,type,capacity,occupied,reserved,x,z,map_node_id,rack_id,bay_index,level_index)
SELECT r.id || '-B' || lpad((b+1)::text,2,'0') || '-L' || lpad((level+1)::text,2,'0'),
 r.warehouse_id,r.name || ' / Bay ' || (b+1) || ' / Level ' || (level+1),'STORAGE',1,0,0,r.x,r.z,
 'S-' || substring(r.id from 3),r.id,b,level
FROM rack r CROSS JOIN generate_series(0,3) b CROSS JOIN generate_series(0,2) level
WHERE r.warehouse_id='linz' AND NOT EXISTS (
  SELECT 1 FROM location existing WHERE existing.rack_id=r.id AND existing.bay_index=b AND existing.level_index=level
);

UPDATE location SET x=17,z=-12,map_node_id='INBOUND',rotation_y=0,operating_width=7,operating_depth=7 WHERE id='INBOUND-01';
UPDATE location SET x=-17,z=13,map_node_id='OUTBOUND',rotation_y=3.141592653589793,operating_width=7,operating_depth=6 WHERE id='OUTBOUND-01';
UPDATE agv SET x=17,z=-12,theta=0 WHERE warehouse_id='linz' AND job_id IS NULL;

WITH seed_slots AS (
  SELECT id,row_number() OVER (ORDER BY rack_id,bay_index,level_index) AS rn
  FROM location WHERE type='STORAGE' ORDER BY rack_id,bay_index,level_index LIMIT 40
)
INSERT INTO load(id,item,status,location_id,received_at)
SELECT 'SEED-' || lpad(rn::text,3,'0'),
  CASE ((rn-1)/8) WHEN 0 THEN 'ELECTRONICS' WHEN 1 THEN 'AUTOMOTIVE' WHEN 2 THEN 'MEDICAL' WHEN 3 THEN 'FOOD-DRY' ELSE 'TOOLS' END,
  'STORED',id,now()
FROM seed_slots
WHERE NOT EXISTS (SELECT 1 FROM load WHERE status IN ('STORED','OUTBOUND_QUEUED','IN_TRANSIT','ON_CONVEYOR'));

UPDATE location l SET occupied=(SELECT count(*) FROM load item WHERE item.location_id=l.id AND item.status<>'SHIPPED')
WHERE l.type='STORAGE';

DELETE FROM map_node WHERE warehouse_id='linz' AND id NOT IN (
 'INBOUND','I-CROSS','W-A','S-A1','S-A2','S-A3','S-A4','E-A','W-B','S-B1','S-B2','S-B3','S-B4','E-B',
 'W-C','S-C1','S-C2','S-C3','S-C4','E-C','OUTBOUND'
);

INSERT INTO map_edge(id,warehouse_id,from_node,to_node,cost) VALUES
 ('IN-I','linz','INBOUND','I-CROSS',9),('I-EA','linz','I-CROSS','E-A',6),
 ('A-W-1','linz','W-A','S-A1',4),('A-1-2','linz','S-A1','S-A2',6),('A-2-3','linz','S-A2','S-A3',6),('A-3-4','linz','S-A3','S-A4',6),('A-4-E','linz','S-A4','E-A',4),
 ('B-W-1','linz','W-B','S-B1',4),('B-1-2','linz','S-B1','S-B2',6),('B-2-3','linz','S-B2','S-B3',6),('B-3-4','linz','S-B3','S-B4',6),('B-4-E','linz','S-B4','E-B',4),
 ('C-W-1','linz','W-C','S-C1',4),('C-1-2','linz','S-C1','S-C2',6),('C-2-3','linz','S-C2','S-C3',6),('C-3-4','linz','S-C3','S-C4',6),('C-4-E','linz','S-C4','E-C',4),
 ('E-A-B','linz','E-A','E-B',8),('E-B-C','linz','E-B','E-C',8),
 ('W-A-B','linz','W-A','W-B',8),('W-B-C','linz','W-B','W-C',8),('W-C-OUT','linz','W-C','OUTBOUND',3.1622776602);

INSERT INTO warehouse_obstacle(id,warehouse_id,type,x,z,width,depth,rotation_y,height) VALUES
 ('WALL-NW','linz','WALL',-22,17.45,4,0.18,0,1.1),('WALL-N','linz','WALL',6,17.45,36,0.18,0,1.1),
 ('WALL-S','linz','WALL',-6, -17.45,36,0.18,0,1.1),('WALL-SE','linz','WALL',22,-17.45,4,0.18,0,1.1),
 ('WALL-W','linz','WALL',-23.45,0,0.18,35,0,1.1),('WALL-E','linz','WALL',23.45,0,0.18,35,0,1.1),
 ('REC-GUARD-W','linz','BARRIER',13,-14.5,0.16,5,0,0.9),('REC-GUARD-N','linz','BARRIER',15,-10,4,0.16,0,0.9),
 ('SHIP-GUARD-E','linz','BARRIER',-13,14.5,0.16,5,0,0.9),('SHIP-GUARD-S','linz','BARRIER',-15,10,4,0.16,0,0.9);
