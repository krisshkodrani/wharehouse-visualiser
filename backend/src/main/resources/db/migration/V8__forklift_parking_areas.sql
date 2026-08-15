INSERT INTO map_node(id,warehouse_id,x,z) VALUES
 ('PARK-01','linz',11,-6),('PARK-02','linz',11,2),('PARK-03','linz',11,10);

INSERT INTO map_edge(id,warehouse_id,from_node,to_node,cost) VALUES
 ('PARK-01-EA','linz','PARK-01','E-A',3),
 ('PARK-02-EB','linz','PARK-02','E-B',3),
 ('PARK-03-EC','linz','PARK-03','E-C',3);

INSERT INTO location(id,warehouse_id,name,type,capacity,occupied,reserved,x,z,map_node_id,rotation_y,operating_width,operating_depth) VALUES
 ('PARK-01','linz','Forklift rest bay 1','PARKING',1,0,0,11,-6,'PARK-01',0,2.5,3.2),
 ('PARK-02','linz','Forklift rest bay 2','PARKING',1,0,0,11,2,'PARK-02',0,2.5,3.2),
 ('PARK-03','linz','Forklift rest bay 3','PARKING',1,0,0,11,10,'PARK-03',0,2.5,3.2);
