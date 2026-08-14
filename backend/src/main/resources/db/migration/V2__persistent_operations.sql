ALTER TABLE location ADD COLUMN rack_id varchar(40) REFERENCES rack(id);
ALTER TABLE location ADD COLUMN bay_index integer;
ALTER TABLE location ADD COLUMN level_index integer;

UPDATE location
SET rack_id = regexp_replace(id, '-01$', ''), bay_index = 0, level_index = 0
WHERE type = 'STORAGE';

INSERT INTO location(id, warehouse_id, name, type, capacity, occupied, reserved, x, z, map_node_id, rack_id, bay_index, level_index)
SELECT r.id || '-B' || lpad((b + 1)::text, 2, '0') || '-L' || lpad((l + 1)::text, 2, '0'),
       r.warehouse_id, r.name || ' / Bay ' || (b + 1) || ' / Level ' || (l + 1),
       'STORAGE', 1, 0, 0, r.x, r.z,
       CASE r.id
         WHEN 'L-A1' THEN 'S-A1' WHEN 'L-A2' THEN 'S-A2' WHEN 'L-A3' THEN 'S-A3'
         WHEN 'L-B1' THEN 'S-B1' WHEN 'L-B2' THEN 'S-B2' WHEN 'L-B3' THEN 'S-B3'
       END,
       r.id, b, l
FROM rack r
CROSS JOIN LATERAL generate_series(0, r.bays - 1) b
CROSS JOIN generate_series(0, 2) l
WHERE r.id IN ('L-A1','L-A2','L-A3','L-B1','L-B2','L-B3')
  AND NOT (b = 0 AND l = 0);

CREATE UNIQUE INDEX location_physical_slot_idx ON location(rack_id, bay_index, level_index)
WHERE rack_id IS NOT NULL;

ALTER TABLE putaway_request RENAME TO warehouse_request;
ALTER TABLE warehouse_request ADD COLUMN request_type varchar(20) NOT NULL DEFAULT 'PUTAWAY';
ALTER TABLE warehouse_request ADD COLUMN completed_at timestamptz;

CREATE TABLE request_load (
  request_id uuid NOT NULL REFERENCES warehouse_request(id) ON DELETE CASCADE,
  load_id varchar(60) NOT NULL REFERENCES load(id),
  sequence_no integer NOT NULL,
  PRIMARY KEY(request_id, load_id),
  UNIQUE(request_id, sequence_no)
);

ALTER TABLE load ADD COLUMN received_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE load ADD COLUMN shipped_at timestamptz;
CREATE SEQUENCE load_display_id_seq START 1000;

CREATE TABLE conveyor_transfer (
  id uuid PRIMARY KEY,
  load_id varchar(60) NOT NULL UNIQUE REFERENCES load(id),
  status varchar(20) NOT NULL,
  entered_at timestamptz NOT NULL,
  exit_due_at timestamptz NOT NULL,
  completed_at timestamptz
);
CREATE INDEX conveyor_due_idx ON conveyor_transfer(status, exit_due_at);

CREATE TABLE warehouse_runtime (
  warehouse_id varchar(40) PRIMARY KEY REFERENCES warehouse(id),
  operation_state varchar(20) NOT NULL,
  simulation_epoch bigint NOT NULL,
  changed_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO warehouse_runtime VALUES ('linz', 'RUNNING', 1, now());

ALTER TABLE job ADD COLUMN simulation_epoch bigint NOT NULL DEFAULT 1;
