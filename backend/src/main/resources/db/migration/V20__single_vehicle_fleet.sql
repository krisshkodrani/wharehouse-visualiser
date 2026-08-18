-- Reduce the reference fleet to the single vehicle the control plane actually
-- orchestrates.
--
-- V16 introduced FL-02 and FL-03 and made them claimable for transport tasks,
-- but every parking and charging path remained bound to FL-01:
-- WarehouseStore.parkingTargets() gates on agv("FL-01"), enqueueParking()
-- updates `where id='FL-01'` and publishes to the literal FL-01 order topic,
-- and DispatchService.parkIfIdle() builds its movement order for "FL-01".
-- In the simulator `charging` is only ever set by handleDock(), which requires a
-- dock action the companions never receive, so FL-02 and FL-03 drained
-- monotonically and could never recover. Once below the 25% claim threshold
-- they were unusable for the rest of the session: the fleet silently decayed
-- from three vehicles to one.
--
-- Rather than widen the control plane, the reference workload is now explicitly
-- single-vehicle. PARK-02 and PARK-03 are retained as unoccupied spare bays so
-- the charging area keeps its three-position geometry.

DELETE FROM zone_reservation WHERE agv_id IN ('FL-02', 'FL-03');
DELETE FROM agv WHERE warehouse_id = 'linz' AND id IN ('FL-02', 'FL-03');

-- Leave exactly one bay occupied by FL-01 so parkingTargets() always has a
-- reachable candidate. Previously reset marked all three bays occupied, and
-- since capacity is 1 the `occupied + reserved < capacity` filter matched
-- nothing until a dispatch happened to free a bay.
UPDATE location SET occupied = 0, reserved = 0
WHERE warehouse_id = 'linz' AND id IN ('PARK-02', 'PARK-03');
UPDATE location SET occupied = 1, reserved = 0
WHERE warehouse_id = 'linz' AND id = 'PARK-01';

UPDATE agv
SET x = 11, z = -6, theta = 0, battery = 82, status = 'CHARGING', charging = true,
    current_station_id = 'PARK-01', handling_phase = 'CHARGING',
    fork_height = 0, fork_extension = 0, carried_load_id = null, task_id = null
WHERE warehouse_id = 'linz' AND id = 'FL-01';
