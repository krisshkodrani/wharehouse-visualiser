CREATE TABLE warehouse (
  id varchar(40) PRIMARY KEY, name varchar(120) NOT NULL, width double precision NOT NULL, depth double precision NOT NULL
);
CREATE TABLE rack (
  id varchar(40) PRIMARY KEY, warehouse_id varchar(40) NOT NULL REFERENCES warehouse(id), name varchar(120) NOT NULL,
  x double precision NOT NULL, z double precision NOT NULL, rotation_y double precision NOT NULL DEFAULT 0, bays integer NOT NULL
);
CREATE TABLE map_node (
  id varchar(60) PRIMARY KEY, warehouse_id varchar(40) NOT NULL REFERENCES warehouse(id), x double precision NOT NULL, z double precision NOT NULL
);
CREATE TABLE map_edge (
  id varchar(100) PRIMARY KEY, warehouse_id varchar(40) NOT NULL REFERENCES warehouse(id), from_node varchar(60) NOT NULL REFERENCES map_node(id),
  to_node varchar(60) NOT NULL REFERENCES map_node(id), cost double precision NOT NULL, bidirectional boolean NOT NULL DEFAULT true
);
CREATE TABLE location (
  id varchar(60) PRIMARY KEY, warehouse_id varchar(40) NOT NULL REFERENCES warehouse(id), name varchar(120) NOT NULL,
  type varchar(30) NOT NULL, capacity integer NOT NULL, occupied integer NOT NULL DEFAULT 0, reserved integer NOT NULL DEFAULT 0,
  x double precision NOT NULL, z double precision NOT NULL, map_node_id varchar(60) NOT NULL REFERENCES map_node(id),
  CONSTRAINT location_capacity CHECK (occupied >= 0 AND reserved >= 0 AND occupied + reserved <= capacity)
);
CREATE TABLE load (
  id varchar(60) PRIMARY KEY, item varchar(120) NOT NULL, status varchar(30) NOT NULL,
  location_id varchar(60) NOT NULL REFERENCES location(id)
);
CREATE TABLE putaway_request (
  id uuid PRIMARY KEY, status varchar(30) NOT NULL, prompt text, error text, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE job (
  id uuid PRIMARY KEY, request_id uuid NOT NULL REFERENCES putaway_request(id), sequence_no integer NOT NULL,
  load_id varchar(60) NOT NULL REFERENCES load(id), source_location varchar(60) NOT NULL REFERENCES location(id),
  destination_location varchar(60) NOT NULL REFERENCES location(id), status varchar(30) NOT NULL,
  route_json text NOT NULL DEFAULT '[]', created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(request_id, sequence_no)
);
CREATE TABLE agv (
  id varchar(60) PRIMARY KEY, warehouse_id varchar(40) NOT NULL REFERENCES warehouse(id), x double precision NOT NULL,
  z double precision NOT NULL, theta double precision NOT NULL DEFAULT 0, battery double precision NOT NULL,
  status varchar(30) NOT NULL, job_id uuid REFERENCES job(id)
);
CREATE TABLE mqtt_outbox (
  id bigserial PRIMARY KEY, topic varchar(200) NOT NULL, payload text NOT NULL, qos integer NOT NULL,
  status varchar(20) NOT NULL DEFAULT 'PENDING', attempts integer NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX job_status_idx ON job(status);
CREATE INDEX outbox_status_idx ON mqtt_outbox(status, id);

INSERT INTO warehouse VALUES ('linz', 'Linz Central Warehouse', 48, 36);
INSERT INTO rack(id,warehouse_id,name,x,z,rotation_y,bays) VALUES
 ('L-A1','linz','Rack L-A1',-7,-5.7,0,4),('L-A2','linz','Rack L-A2',-1.8,-5.7,0,4),
 ('L-A3','linz','Rack L-A3',3.4,-5.7,0,3),('L-B1','linz','Rack L-B1',-7,1.8,0,4),
 ('L-B2','linz','Rack L-B2',-1.8,1.8,0,4),('L-B3','linz','Rack L-B3',3.4,1.8,0,3),
 ('L-D1','linz','Dispatch Rack L-D1',8.2,-6.9,0,2);

INSERT INTO map_node(id,warehouse_id,x,z) VALUES
 ('INBOUND','linz',8.2,-4.7),('N-A','linz',6,-4.7),('N-B','linz',1,-4.7),('N-C','linz',-4,-4.7),
 ('N-D','linz',-9,-4.7),('N-E','linz',-9,4),('N-F','linz',-4,4),('N-G','linz',1,4),('N-H','linz',6,4),
 ('S-A1','linz',-7,-4.7),('S-A2','linz',-1.8,-4.7),('S-A3','linz',3.4,-4.7),
 ('S-B1','linz',-7,4),('S-B2','linz',-1.8,4),('S-B3','linz',3.4,4),('OUTBOUND','linz',8.2,5.5);

INSERT INTO map_edge(id,warehouse_id,from_node,to_node,cost) VALUES
 ('IN-A','linz','INBOUND','N-A',2.2),('A-B','linz','N-A','N-B',5),('B-C','linz','N-B','N-C',5),('C-D','linz','N-C','N-D',5),
 ('D-E','linz','N-D','N-E',8.7),('E-F','linz','N-E','N-F',5),('F-G','linz','N-F','N-G',5),('G-H','linz','N-G','N-H',5),
 ('H-OUT','linz','N-H','OUTBOUND',2.8),('C-SA1','linz','N-C','S-A1',3),('B-SA2','linz','N-B','S-A2',2.8),
 ('A-SA3','linz','N-A','S-A3',2.6),('F-SB1','linz','N-F','S-B1',3),('G-SB2','linz','N-G','S-B2',2.8),('H-SB3','linz','N-H','S-B3',2.6);

INSERT INTO location(id,warehouse_id,name,type,capacity,occupied,reserved,x,z,map_node_id) VALUES
 ('INBOUND-01','linz','Inbound 01','INBOUND',20,3,0,8.2,-4.7,'INBOUND'),
 ('OUTBOUND-01','linz','Outbound 01','OUTBOUND',20,0,0,8.2,5.5,'OUTBOUND'),
 ('L-A1-01','linz','L-A1 Slot 01','STORAGE',1,0,0,-7,-5.7,'S-A1'),
 ('L-A2-01','linz','L-A2 Slot 01','STORAGE',1,0,0,-1.8,-5.7,'S-A2'),
 ('L-A3-01','linz','L-A3 Slot 01','STORAGE',1,0,0,3.4,-5.7,'S-A3'),
 ('L-B1-01','linz','L-B1 Slot 01','STORAGE',1,0,0,-7,1.8,'S-B1'),
 ('L-B2-01','linz','L-B2 Slot 01','STORAGE',1,0,0,-1.8,1.8,'S-B2'),
 ('L-B3-01','linz','L-B3 Slot 01','STORAGE',1,0,0,3.4,1.8,'S-B3');

INSERT INTO load VALUES ('PALLET-A-001','PALLET-A','INBOUND','INBOUND-01'),('PALLET-A-002','PALLET-A','INBOUND','INBOUND-01'),('PALLET-A-003','PALLET-A','INBOUND','INBOUND-01');
INSERT INTO agv VALUES ('FL-01','linz',8.2,-4.7,0,82,'IDLE',NULL);
