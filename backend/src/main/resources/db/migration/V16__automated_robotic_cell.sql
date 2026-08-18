-- Canonical station vocabulary and the first executable robotic outbound cell.
-- Legacy IDs remain stored as stable aliases while API projections expose the
-- canonical IDs. This keeps existing tasks and retained MQTT messages readable
-- during the migration window.

ALTER TABLE rack ADD COLUMN IF NOT EXISTS canonical_id varchar(40);
UPDATE rack SET canonical_id = CASE id
  WHEN 'L-A1' THEN 'R01' WHEN 'L-A2' THEN 'R02' WHEN 'L-A3' THEN 'R03' WHEN 'L-A4' THEN 'R04'
  WHEN 'L-B1' THEN 'R05' WHEN 'L-B2' THEN 'R06' WHEN 'L-B3' THEN 'R07' WHEN 'L-B4' THEN 'R08'
  WHEN 'L-C1' THEN 'R09' WHEN 'L-C2' THEN 'R10' WHEN 'L-C3' THEN 'R11' WHEN 'L-C4' THEN 'R12'
  ELSE id END
WHERE warehouse_id = 'linz';
CREATE UNIQUE INDEX IF NOT EXISTS rack_canonical_id_idx ON rack(warehouse_id, canonical_id);

ALTER TABLE location ADD COLUMN IF NOT EXISTS canonical_id varchar(60);
UPDATE location SET canonical_id = CASE
  WHEN id = 'INBOUND-01' THEN 'REC-STG-01'
  WHEN id = 'OUTBOUND-01' THEN 'OUT-STG-01'
  WHEN id = 'PARK-01' THEN 'CHARGE-01-POS-01'
  WHEN id = 'PARK-02' THEN 'CHARGE-01-POS-02'
  WHEN id = 'PARK-03' THEN 'CHARGE-01-POS-03'
  WHEN rack_id IS NOT NULL THEN (
    SELECT r.canonical_id || '-B' || lpad((location.bay_index + 1)::text, 2, '0') || '-L' || lpad((location.level_index + 1)::text, 2, '0')
    FROM rack r WHERE r.id = location.rack_id
  )
  ELSE id END
WHERE warehouse_id = 'linz';
CREATE UNIQUE INDEX IF NOT EXISTS location_canonical_id_idx ON location(warehouse_id, canonical_id);

ALTER TABLE map_node ADD COLUMN IF NOT EXISTS canonical_id varchar(60);
UPDATE map_node SET canonical_id = CASE
  WHEN id = 'INBOUND' THEN 'REC-STG-01'
  WHEN id = 'OUTBOUND' THEN 'OUT-STG-01'
  WHEN id = 'PARK-01' THEN 'CHARGE-01-POS-01'
  WHEN id = 'PARK-02' THEN 'CHARGE-01-POS-02'
  WHEN id = 'PARK-03' THEN 'CHARGE-01-POS-03'
  ELSE id END
WHERE warehouse_id = 'linz';
CREATE UNIQUE INDEX IF NOT EXISTS map_node_canonical_id_idx ON map_node(warehouse_id, canonical_id);

INSERT INTO map_node(id, warehouse_id, x, z, canonical_id) VALUES
  ('REC-DCK-01','linz',20,-12,'REC-DCK-01'),
  ('OUT-DCK-01','linz',-21,13,'OUT-DCK-01'),
  ('ROBOT-01','linz',-12,13,'ROBOT-01'),
  ('CONV-OUT-01','linz',-8,12.2,'CONV-OUT-01'),
  ('CONV-OUT-02','linz',-8,13.8,'CONV-OUT-02'),
  ('QA-01','linz',12,8,'QA-01'),
  ('MAINT-01','linz',12,4,'MAINT-01'),
  ('CHARGE-01','linz',11,2,'CHARGE-01')
ON CONFLICT (id) DO UPDATE SET x = excluded.x, z = excluded.z, canonical_id = excluded.canonical_id;

INSERT INTO location(id, warehouse_id, name, type, capacity, occupied, reserved, x, z, map_node_id,
    rotation_y, operating_width, operating_depth, canonical_id) VALUES
  ('REC-DCK-01','linz','Receiving dock','RECEIVING_DOCK',2,0,0,20,-12,'REC-DCK-01',0,6,5,'REC-DCK-01'),
  ('OUT-DCK-01','linz','Outbound shipping dock','OUTBOUND_DOCK',2,0,0,-21,13,'OUT-DCK-01',3.141592653589793,6,5,'OUT-DCK-01'),
  ('ROBOT-01','linz','Robotic picking cell','ROBOT_CELL',1,0,0,-12,13,'ROBOT-01',0,5,5,'ROBOT-01'),
  ('CONV-OUT-01','linz','Outbound conveyor 1','CONVEYOR',12,0,0,-8,12.2,'CONV-OUT-01',0,10,1.4,'CONV-OUT-01'),
  ('CONV-OUT-02','linz','Outbound conveyor 2','CONVEYOR',12,0,0,-8,13.8,'CONV-OUT-02',0,10,1.4,'CONV-OUT-02'),
  ('CHARGE-01','linz','AGV charging area','CHARGING_AREA',3,0,0,11,2,'CHARGE-01',0,8,5,'CHARGE-01'),
  ('QA-01','linz','Quality inspection','QUALITY_CONTROL',2,0,0,12,8,'QA-01',0,4,3,'QA-01'),
  ('MAINT-01','linz','Maintenance and service','MAINTENANCE',1,0,0,12,4,'MAINT-01',0,4,3,'MAINT-01')
ON CONFLICT (id) DO UPDATE SET name = excluded.name, type = excluded.type, x = excluded.x, z = excluded.z,
  map_node_id = excluded.map_node_id, canonical_id = excluded.canonical_id;

UPDATE location SET canonical_id = 'REC-STG-01', name = 'Receiving staging', type = 'RECEIVING_STAGING'
WHERE id = 'INBOUND-01';
UPDATE location SET canonical_id = 'OUT-STG-01', name = 'Outbound consolidation staging', type = 'OUTBOUND_STAGING'
WHERE id = 'OUTBOUND-01';
UPDATE location SET canonical_id = 'CHARGE-01-POS-' || substring(id from 6), name = 'Charging position ' || substring(id from 6), type = 'PARKING_CHARGING'
WHERE id IN ('PARK-01','PARK-02','PARK-03');

ALTER TABLE agv ADD COLUMN IF NOT EXISTS capabilities jsonb NOT NULL DEFAULT '{"forklift":true,"pallet":true}'::jsonb;
INSERT INTO agv(id, warehouse_id, x, z, theta, battery, status, task_id, capabilities)
VALUES
  ('FL-02','linz',11,2,0,88,'PARKED',NULL,'{"forklift":true,"pallet":true}'::jsonb),
  ('FL-03','linz',11,10,0,94,'PARKED',NULL,'{"forklift":true,"pallet":true}'::jsonb)
ON CONFLICT (id) DO UPDATE SET capabilities = excluded.capabilities;

CREATE TABLE IF NOT EXISTS carton (
  id varchar(80) PRIMARY KEY,
  pallet_id varchar(60) NOT NULL REFERENCES load(id),
  sku varchar(120) NOT NULL,
  quantity integer NOT NULL DEFAULT 1,
  status varchar(30) NOT NULL DEFAULT 'ON_PALLET',
  location_id varchar(60) REFERENCES location(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  picked_at timestamptz,
  shipped_at timestamptz,
  CONSTRAINT carton_quantity CHECK (quantity > 0)
);
CREATE INDEX IF NOT EXISTS carton_pallet_status_idx ON carton(pallet_id, status);

INSERT INTO carton(id, pallet_id, sku, quantity, status, location_id)
SELECT l.id || '-C' || lpad((n)::text, 2, '0'), l.id, l.item, 1, 'ON_PALLET', l.location_id
FROM load l CROSS JOIN generate_series(1, 4) n
WHERE NOT EXISTS (SELECT 1 FROM carton c WHERE c.pallet_id = l.id);

ALTER TABLE conveyor_transfer ADD COLUMN IF NOT EXISTS conveyor_id varchar(60) REFERENCES location(id);
ALTER TABLE conveyor_transfer ADD COLUMN IF NOT EXISTS carton_id varchar(80) REFERENCES carton(id);
ALTER TABLE conveyor_transfer DROP CONSTRAINT IF EXISTS conveyor_transfer_load_id_key;
UPDATE conveyor_transfer SET conveyor_id = 'CONV-OUT-01' WHERE conveyor_id IS NULL;

CREATE TABLE IF NOT EXISTS robot_pick_job (
  id uuid PRIMARY KEY,
  transport_task_id uuid NOT NULL REFERENCES transport_task(id) ON DELETE CASCADE,
  carton_id varchar(80) NOT NULL REFERENCES carton(id),
  robot_id varchar(60) NOT NULL REFERENCES location(id),
  conveyor_id varchar(60) REFERENCES location(id),
  status varchar(30) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  started_at timestamptz,
  completed_at timestamptz,
  error text
);
CREATE INDEX IF NOT EXISTS robot_pick_status_idx ON robot_pick_job(status, created_at);

CREATE TABLE IF NOT EXISTS zone_reservation (
  id uuid PRIMARY KEY,
  zone_id varchar(60) NOT NULL,
  agv_id varchar(60) NOT NULL REFERENCES agv(id),
  task_id uuid REFERENCES transport_task(id),
  status varchar(20) NOT NULL DEFAULT 'ACTIVE',
  reserved_at timestamptz NOT NULL DEFAULT now(),
  released_at timestamptz
);
CREATE UNIQUE INDEX IF NOT EXISTS active_zone_reservation_idx ON zone_reservation(zone_id) WHERE status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS robot_cell_state (
  robot_id varchar(60) PRIMARY KEY REFERENCES location(id),
  phase varchar(30) NOT NULL DEFAULT 'IDLE',
  active_pick_job_id uuid REFERENCES robot_pick_job(id),
  updated_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO robot_cell_state(robot_id) VALUES ('ROBOT-01') ON CONFLICT (robot_id) DO NOTHING;

INSERT INTO map_edge(id, warehouse_id, from_node, to_node, cost) VALUES
  ('REC-DCK-STG','linz','REC-DCK-01','INBOUND',3),
  ('STG-ROBOT','linz','OUTBOUND','ROBOT-01',5),
  ('ROBOT-CONV-01','linz','ROBOT-01','CONV-OUT-01',4),
  ('ROBOT-CONV-02','linz','ROBOT-01','CONV-OUT-02',4),
  ('STG-DCK','linz','OUTBOUND','OUT-DCK-01',4),
  ('E-B-QA','linz','E-B','QA-01',4),
  ('E-B-MAINT','linz','E-B','MAINT-01',4),
  ('PARK-02-CHARGE','linz','PARK-02','CHARGE-01',3)
ON CONFLICT (id) DO NOTHING;

INSERT INTO warehouse_obstacle(id, warehouse_id, type, x, z, width, depth, rotation_y, height) VALUES
  ('ROBOT-CELL-N','linz','BARRIER',-12,10.1,7.5,0.16,0,1.4),
  ('ROBOT-CELL-S','linz','BARRIER',-12,15.9,7.5,0.16,0,1.4),
  ('ROBOT-CELL-W','linz','BARRIER',-15.7,13,0.16,5.8,0,1.4),
  ('ROBOT-CELL-E','linz','BARRIER',-8.3,13,0.16,5.8,0,1.4),
  ('ROBOT-CELL-GATE','linz','BARRIER',-15.7,11.4,0.16,1.2,0,0.9)
ON CONFLICT (id) DO NOTHING;
