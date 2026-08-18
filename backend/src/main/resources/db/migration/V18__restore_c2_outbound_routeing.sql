-- Leave a south-east access opening in the robotic-cell perimeter. The former
-- east guard started at z=10.1, intersecting the C-row travel lane at z=10
-- once the forklift clearance envelope was applied. Keeping the guard from
-- z=12.1 through z=15.9 preserves the visible cell boundary while keeping all
-- storage nodes connected to the outbound handoff.
UPDATE warehouse_obstacle
SET z = 14.0,
    depth = 3.8
WHERE warehouse_id = 'linz' AND id = 'ROBOT-CELL-E';
