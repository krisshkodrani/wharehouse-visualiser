ALTER TABLE warehouse_runtime
  ADD COLUMN time_scale integer NOT NULL DEFAULT 2,
  ADD CONSTRAINT warehouse_runtime_time_scale CHECK (time_scale IN (1,2,4));

ALTER TABLE location
  ADD COLUMN handling_x double precision,
  ADD COLUMN handling_z double precision,
  ADD COLUMN handling_theta double precision,
  ADD COLUMN handling_height double precision;

UPDATE location l
SET handling_x = r.x - (r.bays * 1.05) / 2.0 + 0.52 + l.bay_index * 1.05,
    handling_z = r.z + 2.0,
    handling_theta = -1.5707963267948966,
    handling_height = 0.15 + l.level_index * 1.10
FROM rack r
WHERE l.rack_id = r.id AND l.type = 'STORAGE';

UPDATE location SET handling_x=x,handling_z=z,handling_theta=rotation_y,handling_height=0.08
WHERE type='INBOUND';
UPDATE location SET handling_x=x,handling_z=z,handling_theta=rotation_y,handling_height=0.84
WHERE type='OUTBOUND';
UPDATE location SET type='PARKING_CHARGING',handling_x=x,handling_z=z,handling_theta=rotation_y,handling_height=0
WHERE type='PARKING';

ALTER TABLE agv
  ADD COLUMN velocity double precision NOT NULL DEFAULT 0,
  ADD COLUMN charging boolean NOT NULL DEFAULT false,
  ADD COLUMN current_station_id varchar(60) REFERENCES location(id),
  ADD COLUMN handling_phase varchar(30) NOT NULL DEFAULT 'IDLE',
  ADD COLUMN fork_height double precision NOT NULL DEFAULT 0,
  ADD COLUMN fork_extension double precision NOT NULL DEFAULT 0,
  ADD COLUMN carried_load_id varchar(60) REFERENCES load(id);
