-- Keep the shipping partition behind the work area so the complete C service
-- aisle and the W-C -> OUTBOUND approach retain forklift clearance.
UPDATE warehouse_obstacle SET z=16 WHERE id='SHIP-GUARD-S';
