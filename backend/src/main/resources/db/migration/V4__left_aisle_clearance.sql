-- Keep the west connector outside the footprint of racks L-A1 and L-B1.
UPDATE map_node
SET x = -10.5
WHERE warehouse_id = 'linz' AND id IN ('N-D','N-E');
