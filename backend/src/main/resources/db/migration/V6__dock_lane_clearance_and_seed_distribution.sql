-- Keep the receiving partition outside the 0.72 m forklift clearance envelope
-- of the INBOUND -> I-CROSS route at z=-12.
UPDATE warehouse_obstacle SET z=-15,depth=4 WHERE id='REC-GUARD-W';

-- Distribute the deterministic demo inventory across every rack rather than
-- filling complete racks from one side of the building.
WITH distributed_slots AS (
  SELECT id,row_number() OVER (ORDER BY bay_index,level_index,rack_id) AS rn
  FROM location WHERE type='STORAGE'
), seeded_loads AS (
  SELECT id,row_number() OVER (ORDER BY id) AS rn
  FROM load WHERE id LIKE 'SEED-%'
)
UPDATE load item SET location_id=slot.id
FROM seeded_loads seed JOIN distributed_slots slot ON slot.rn=seed.rn
WHERE item.id=seed.id;

UPDATE location l SET occupied=(SELECT count(*) FROM load item WHERE item.location_id=l.id AND item.status<>'SHIPPED')
WHERE l.type='STORAGE';
