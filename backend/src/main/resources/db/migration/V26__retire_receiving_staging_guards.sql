-- Remove the guard rails from the superseded receiving-staging footprint.
--
-- V5 placed these rails around the old staging area centred at x=17, z=-12.
-- V21 separated REC-DCK-01 from INBOUND-01 and moved the staging footprint, but
-- left both physical obstacles behind. The handling pose intentionally remains
-- at x=17, z=-12, where the vehicle serves the staged pallet rows; consequently
-- the old north and west rails intersect the forklift's docking envelope even
-- though the current station footprints no longer overlap.
--
-- The receiving dock already supplies its own bumpers, scanner arch and
-- bollards. These two route-affecting rails therefore protect no current asset
-- and visually cut through the vehicle at the valid handoff pose.

DELETE FROM warehouse_obstacle
WHERE warehouse_id = 'linz' AND id IN ('REC-GUARD-W', 'REC-GUARD-N');
