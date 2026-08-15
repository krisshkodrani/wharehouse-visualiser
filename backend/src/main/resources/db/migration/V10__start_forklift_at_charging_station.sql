UPDATE location SET occupied=0,reserved=0 WHERE type='PARKING_CHARGING';
UPDATE location SET occupied=1 WHERE id='PARK-01';

UPDATE agv
SET x=11,
    z=-6,
    theta=0,
    velocity=0,
    status='CHARGING',
    charging=true,
    current_station_id='PARK-01',
    handling_phase='CHARGING',
    fork_height=0,
    fork_extension=0,
    carried_load_id=null,
    job_id=null
WHERE id='FL-01';
