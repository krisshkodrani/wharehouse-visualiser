-- The conveyor records introduced with the robotic cell were centred at x=-8,
-- placing almost half of each 10 m lane inside the guarded robot-cell footprint.
-- Move both lanes east so their infeed begins at the cell's east-side handoff.
UPDATE location
SET x = -3.2
WHERE warehouse_id = 'linz' AND id IN ('CONV-OUT-01', 'CONV-OUT-02');

UPDATE map_node
SET x = -3.2
WHERE warehouse_id = 'linz' AND id IN ('CONV-OUT-01', 'CONV-OUT-02');
