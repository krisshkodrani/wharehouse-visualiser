-- Move the front rack service lane far enough from the shelving for the AGV body and forks.
UPDATE map_node
SET z = -3.9
WHERE warehouse_id = 'linz' AND id IN ('N-A','N-B','N-C','N-D','S-A1','S-A2','S-A3');
