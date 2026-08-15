package com.example.warehouse;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class WarehouseStore {
  record NodeRow(String id, double x, double z) {}
  record EdgeRow(String id, String from, String to, double cost, boolean bidirectional) {}
  record PhysicalObstacle(String id, String type, double x, double z, double halfWidth, double halfDepth, double rotationY, double height) {}
  record ParkingRow(String id, String nodeId, double x, double z, double theta) {}
  record HandlingRow(String locationId, double x, double z, double theta, double height) {}
  record TaskRow(UUID id, UUID transportOrderId, int sequence, String loadId, String source, String destination, String status,
      List<String> route, String assignedAgvId) {}
  record OutboxRow(long id, String topic, String payload, int qos) {}
  record DispatchPayload(long orderUpdateId, String payload) {}

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  WarehouseStore(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  ApiModels.WarehouseSnapshot snapshot() {
    Map<String, Object> warehouse = jdbc.queryForMap("select * from warehouse where id='linz'");
    var racks = jdbc.query("select * from rack where warehouse_id='linz' order by id", (rs, n) ->
        new ApiModels.RackView(rs.getString("id"), rs.getString("name"), rs.getDouble("x"), rs.getDouble("z"), rs.getDouble("rotation_y"), rs.getInt("bays")));
    var locations = jdbc.query("select * from location where warehouse_id='linz' order by id", (rs, n) -> location(rs));
    var loads = jdbc.query("select * from load order by received_at,id", (rs, n) -> loadView(rs));
    var agvs = jdbc.query("select * from agv where warehouse_id='linz' order by id", (rs, n) -> agvView(rs));
    var tasks = jdbc.query("select * from transport_task order by created_at", (rs, n) -> taskView(rs));
    var jobs = tasks.stream().map(WarehouseStore::legacyJobView).toList();
    var orders = transportOrders();
    var runtime = runtime();
    var transfers = jdbc.query("select * from conveyor_transfer order by entered_at", (rs, n) ->
        new ApiModels.ConveyorTransferView(rs.getObject("id", UUID.class), rs.getString("load_id"), rs.getString("status"),
            instant(rs, "entered_at"), instant(rs, "exit_due_at"), instant(rs, "completed_at")));
    var obstacles = jdbc.query("select * from warehouse_obstacle where warehouse_id='linz' order by id", (rs, n) ->
        new ApiModels.ObstacleView(rs.getString("id"), rs.getString("type"), rs.getDouble("x"), rs.getDouble("z"),
            rs.getDouble("width"), rs.getDouble("depth"), rs.getDouble("rotation_y"), rs.getDouble("height")));
    return new ApiModels.WarehouseSnapshot("linz", String.valueOf(warehouse.get("name")),
        ((Number) warehouse.get("width")).doubleValue(), ((Number) warehouse.get("depth")).doubleValue(), racks, locations, loads, agvs, jobs,
        orders, tasks, scenario(runtime), runtime, transfers, obstacles);
  }

  ApiModels.RuntimeView runtime() {
    return jdbc.queryForObject("select * from warehouse_runtime where warehouse_id='linz'", (rs, n) ->
        new ApiModels.RuntimeView(rs.getString("operation_state"), rs.getLong("simulation_epoch"), rs.getInt("time_scale"),
            rs.getString("scenario_id"), rs.getBoolean("scenario_configured"), instant(rs, "changed_at")));
  }

  ApiModels.AgvView agv() {
    return jdbc.queryForObject("select * from agv where id='FL-01'", (rs, n) -> agvView(rs));
  }

  HandlingRow handling(String locationId) {
    return jdbc.queryForObject("select id,coalesce(handling_x,x) handling_x,coalesce(handling_z,z) handling_z,coalesce(handling_theta,rotation_y) handling_theta,coalesce(handling_height,0) handling_height from location where id=?",
        (rs, n) -> new HandlingRow(rs.getString("id"), rs.getDouble("handling_x"), rs.getDouble("handling_z"),
            rs.getDouble("handling_theta"), rs.getDouble("handling_height")), locationId);
  }

  NodeRow locationPosition(String locationId) {
    return jdbc.queryForObject("select map_node_id,x,z from location where id=?", (rs, n) ->
        new NodeRow(rs.getString("map_node_id"), rs.getDouble("x"), rs.getDouble("z")), locationId);
  }

  NodeRow nearestNodeToAgv() {
    ApiModels.AgvView agv = agv();
    return nodes().stream().min(java.util.Comparator
        .comparingDouble((NodeRow node) -> Math.hypot(node.x() - agv.x(), node.z() - agv.z()))
        .thenComparing(NodeRow::id)).orElseThrow();
  }

  boolean isRunning() { return "RUNNING".equals(runtime().operationState()); }

  void requireRunning() {
    if (!isRunning()) throw new OperationsPausedException();
  }

  List<ApiModels.IncomingLoad> incomingLoads(List<String> ids) {
    if (ids.isEmpty()) return List.of();
    String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
    return jdbc.query("select id,item,location_id from load where status='INBOUND' and id in (" + placeholders + ") order by id",
        (rs, n) -> new ApiModels.IncomingLoad(rs.getString(1), rs.getString(2), rs.getString(3)), ids.toArray());
  }

  List<ApiModels.CandidateSlot> candidates() {
    return jdbc.query("select * from location where warehouse_id='linz' and type='STORAGE' and occupied+reserved < capacity order by id",
        (rs, n) -> new ApiModels.CandidateSlot(rs.getString("id"), rs.getString("name"),
            rs.getInt("capacity") - rs.getInt("occupied") - rs.getInt("reserved"), rs.getDouble("x"), rs.getDouble("z")));
  }

  void createRequest(UUID id, String type, String prompt, List<String> loadIds) {
    createRequest(id, type, "NORMAL", prompt, runtime().scenarioId(), loadIds);
  }

  void createRequest(UUID id, String type, String priority, String objective, String scenarioId, List<String> loadIds) {
    jdbc.update("insert into transport_order(id,status,objective,order_type,priority,scenario_id) values (?,?,?,?,?,?)",
        id, "PLANNING", objective, type, priority, scenarioId);
    for (int index = 0; index < loadIds.size(); index++)
      jdbc.update("insert into transport_order_load(request_id,load_id,sequence_no) values (?,?,?)", id, loadIds.get(index), index + 1);
  }

  ApiModels.PutawayStatus request(UUID id) {
    return jdbc.queryForObject("select * from transport_order where id=?", (rs, n) ->
        new ApiModels.PutawayStatus(id, rs.getString("status"), rs.getString("objective"), rs.getString("error"),
            rs.getTimestamp("created_at").toInstant(), jobsForRequest(id)), id);
  }

  void rejectRequest(UUID id, String error) {
    jdbc.update("update transport_order set status='FAILED', error=?,completed_at=now(),updated_at=now() where id=?", error, id);
  }

  void createPlannedJobs(UUID requestId, List<ApiModels.IncomingLoad> loads, ApiModels.PlacementPlan plan, RoutePlanner routes) {
    for (int index = 0; index < plan.placements().size(); index++) {
      ApiModels.Placement placement = plan.placements().get(index);
      ApiModels.IncomingLoad load = loads.stream().filter(item -> item.id().equals(placement.loadId())).findFirst().orElseThrow();
      int reserved = jdbc.update("update location set reserved=reserved+1 where id=? and occupied+reserved < capacity", placement.slotId());
      if (reserved != 1) throw new IllegalStateException("Slot is no longer available: " + placement.slotId());
      List<String> route = routes.route(load.locationId(), placement.slotId());
      UUID jobId = UUID.randomUUID();
      jdbc.update("insert into transport_task(id,request_id,sequence_no,load_id,source_location,destination_location,status,route_json,simulation_epoch) values (?,?,?,?,?,?,?,?,?)",
          jobId, requestId, index + 1, load.id(), load.locationId(), placement.slotId(), "QUEUED", write(route), runtime().simulationEpoch());
    }
    jdbc.update("update transport_order set status='READY',updated_at=now() where id=?", requestId);
  }

  List<ApiModels.LoadView> receive(String sku, int quantity) {
    requireRunning();
    Integer free = jdbc.queryForObject("select capacity-occupied-reserved from location where id='INBOUND-01' for update", Integer.class);
    if (free == null || free < quantity) throw new IllegalStateException("Inbound staging does not have enough free positions");
    var result = new java.util.ArrayList<ApiModels.LoadView>();
    for (int index = 0; index < quantity; index++) {
      Long number = jdbc.queryForObject("select nextval('load_display_id_seq')", Long.class);
      String id = "BOX-%06d".formatted(number);
      jdbc.update("insert into load(id,item,status,location_id,received_at) values (?,?, 'INBOUND','INBOUND-01',now())", id, sku.trim());
      jdbc.update("update location set occupied=occupied+1 where id='INBOUND-01'");
      result.add(jdbc.queryForObject("select * from load where id=?", (rs, n) -> loadView(rs), id));
    }
    return result;
  }

  UUID createOutbound(List<String> loadIds, RoutePlanner routes) {
    return createOutbound(loadIds, "NORMAL", "Operator outbound shipment", runtime().scenarioId(), routes);
  }

  UUID createOutbound(List<String> loadIds, String priority, String objective, String scenarioId, RoutePlanner routes) {
    requireRunning();
    List<String> distinct = loadIds.stream().distinct().toList();
    if (distinct.size() != loadIds.size()) throw new IllegalArgumentException("Each outbound box may only be selected once");
    String placeholders = String.join(",", distinct.stream().map(id -> "?").toList());
    var loads = jdbc.query("select id,location_id from load where status='STORED' and id in (" + placeholders + ") order by id",
        (rs, n) -> Map.entry(rs.getString(1), rs.getString(2)), distinct.toArray());
    if (loads.size() != distinct.size()) throw new IllegalArgumentException("Every outbound load must be stored and available");
    int reserved = jdbc.update("update location set reserved=reserved+? where id='OUTBOUND-01' and occupied+reserved+? <= capacity", distinct.size(), distinct.size());
    if (reserved != 1) throw new IllegalStateException("Outbound conveyor staging is full");
    UUID requestId = UUID.randomUUID();
    createRequest(requestId, "OUTBOUND", priority, objective, scenarioId, distinct);
    long epoch = runtime().simulationEpoch();
    for (int index = 0; index < loads.size(); index++) {
      var load = loads.get(index);
      UUID jobId = UUID.randomUUID();
      jdbc.update("insert into transport_task(id,request_id,sequence_no,load_id,source_location,destination_location,status,route_json,simulation_epoch) values (?,?,?,?,?,?, 'QUEUED',?,?)",
          jobId, requestId, index + 1, load.getKey(), load.getValue(), "OUTBOUND-01", write(routes.route(load.getValue(), "OUTBOUND-01")), epoch);
      jdbc.update("update load set status='OUTBOUND_QUEUED' where id=?", load.getKey());
    }
    jdbc.update("update transport_order set status='READY',updated_at=now() where id=?", requestId);
    return requestId;
  }

  List<ApiModels.ScenarioPreset> scenarioPresets() {
    return List.of(
        new ApiModels.ScenarioPreset("balanced-shift", "Balanced shift", "Inbound and outbound work sharing one vehicle.", 32, 4, "MIXED", 4, "NORMAL", 82),
        new ApiModels.ScenarioPreset("inbound-surge", "Inbound surge", "Clear a busy staging lane with auto-planned put-away.", 24, 12, "PUTAWAY", 6, "HIGH", 78),
        new ApiModels.ScenarioPreset("outbound-wave", "Outbound wave", "Fulfil a priority shipment from storage to outbound.", 40, 0, "OUTBOUND", 6, "URGENT", 90));
  }

  ApiModels.WarehouseSnapshot seedScenario(String presetId, RoutePlanner routes) {
    ApiModels.ScenarioPreset preset = scenarioPresets().stream().filter(item -> item.id().equals(presetId)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown scenario preset: " + presetId));
    reset();
    jdbc.update("update agv set battery=? where id='FL-01'", preset.agvBattery());
    String[] skus = {"ELECTRONICS", "AUTOMOTIVE", "MEDICAL", "FOOD-DRY", "TOOLS"};
    List<String> slots = jdbc.queryForList("select id from location where type='STORAGE' order by bay_index,level_index,rack_id limit ?", String.class, preset.storedLoads());
    for (int index = 0; index < slots.size(); index++) {
      String loadId = "SEED-%03d".formatted(index + 1);
      jdbc.update("insert into load(id,item,status,location_id,received_at) values (?,?, 'STORED',?,now())", loadId, skus[index % skus.length], slots.get(index));
      jdbc.update("update location set occupied=1 where id=?", slots.get(index));
    }
    var inbound = new java.util.ArrayList<String>();
    for (int index = 0; index < preset.inboundLoads(); index++) {
      String loadId = "IN-%03d".formatted(index + 1);
      inbound.add(loadId);
      jdbc.update("insert into load(id,item,status,location_id,received_at) values (?,?, 'INBOUND','INBOUND-01',now())", loadId, skus[index % skus.length]);
    }
    jdbc.update("update location set occupied=? where id='INBOUND-01'", preset.inboundLoads());
    jdbc.update("update warehouse_runtime set scenario_id=?,scenario_configured=true,changed_at=now() where warehouse_id='linz'", preset.id());

    if ("balanced-shift".equals(preset.id())) {
      createSeedPutaway(inbound.subList(0, 2), "NORMAL", "Clear inbound staging", preset.id(), routes);
      List<String> outbound = jdbc.queryForList("select id from load where status='STORED' order by id limit 2", String.class);
      createOutbound(outbound, "NORMAL", "Prepare the next outbound shipment", preset.id(), routes);
    } else if ("inbound-surge".equals(preset.id())) {
      createSeedPutaway(inbound.subList(0, 6), "HIGH", "Clear inbound staging", preset.id(), routes);
    } else {
      List<String> outbound = jdbc.queryForList("select id from load where status='STORED' order by id limit 6", String.class);
      createOutbound(outbound, "URGENT", "Fulfil the priority outbound wave", preset.id(), routes);
    }
    return snapshot();
  }

  private UUID createSeedPutaway(List<String> loadIds, String priority, String objective, String scenarioId, RoutePlanner routes) {
    UUID orderId = UUID.randomUUID();
    createRequest(orderId, "PUTAWAY", priority, objective, scenarioId, loadIds);
    List<String> slots = jdbc.queryForList("select id from location where type='STORAGE' and occupied+reserved<capacity order by bay_index,level_index,rack_id limit ?", String.class, loadIds.size());
    if (slots.size() != loadIds.size()) throw new IllegalStateException("Not enough storage slots for scenario");
    long epoch = runtime().simulationEpoch();
    for (int index = 0; index < loadIds.size(); index++) {
      String destination = slots.get(index);
      jdbc.update("update location set reserved=reserved+1 where id=?", destination);
      jdbc.update("insert into transport_task(id,request_id,sequence_no,load_id,source_location,destination_location,status,route_json,simulation_epoch) values (?,?,?,?,?,?, 'QUEUED',?,?)",
          UUID.randomUUID(), orderId, index + 1, loadIds.get(index), "INBOUND-01", destination,
          write(routes.route("INBOUND-01", destination)), epoch);
    }
    jdbc.update("update transport_order set status='READY',updated_at=now() where id=?", orderId);
    return orderId;
  }

  List<ApiModels.TransportOrderView> transportOrders() {
    return jdbc.query("select * from transport_order order by case status when 'IN_PROGRESS' then 0 when 'READY' then 1 when 'PLANNING' then 2 else 3 end,case priority when 'URGENT' then 0 when 'HIGH' then 1 else 2 end,created_at desc",
        (rs, n) -> transportOrder(rs));
  }

  Optional<ApiModels.TransportOrderView> transportOrder(UUID id) {
    return jdbc.query("select * from transport_order where id=?", (rs, n) -> transportOrder(rs), id).stream().findFirst();
  }

  private ApiModels.TransportOrderView transportOrder(ResultSet rs) throws SQLException {
    UUID id = rs.getObject("id", UUID.class);
    List<ApiModels.TransportTaskView> tasks = jdbc.query("select * from transport_task where request_id=? order by sequence_no", (taskRs, n) -> taskView(taskRs), id);
    List<ApiModels.VdaDispatchView> dispatches = jdbc.query("select * from vda_dispatch where task_id in (select id from transport_task where request_id=?) order by created_at desc",
        (dispatchRs, n) -> dispatchView(dispatchRs), id);
    return new ApiModels.TransportOrderView(id, rs.getString("order_type"), rs.getString("priority"), rs.getString("status"),
        rs.getString("objective"), rs.getString("scenario_id"), rs.getString("error"), instant(rs, "created_at"),
        instant(rs, "completed_at"), tasks, dispatches);
  }

  private ApiModels.VdaDispatchView dispatchView(ResultSet rs) throws SQLException {
    String validationError = rs.getString("validation_error");
    return new ApiModels.VdaDispatchView(rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class),
        rs.getString("manufacturer"), rs.getString("serial_number"), rs.getString("order_id"), rs.getLong("order_update_id"),
        rs.getString("status"), validationError == null, validationError, rs.getString("rejection_error"), instant(rs, "created_at"),
        instant(rs, "published_at"), instant(rs, "accepted_at"), instant(rs, "finished_at"), rs.getString("payload_json"));
  }

  void recordDispatch(UUID taskId, String orderId, long updateId, String payload) {
    jdbc.update("insert into vda_dispatch(id,task_id,manufacturer,serial_number,order_id,order_update_id,status,payload_json,published_at) values (?,?,?,?,?,?, 'PUBLISHED',?,now())",
        UUID.randomUUID(), taskId, "demo", "FL-01", orderId, updateId, payload);
  }

  Optional<DispatchPayload> latestDispatch(UUID taskId) {
    return jdbc.query("select order_update_id,payload_json from vda_dispatch where task_id=? order by order_update_id desc limit 1",
        (rs, n) -> new DispatchPayload(rs.getLong(1), rs.getString(2)), taskId).stream().findFirst();
  }

  void enqueueOrderUpdate(UUID taskId, String orderId, long updateId, String payload) {
    recordDispatch(taskId, orderId, updateId, payload);
    jdbc.update("insert into mqtt_outbox(topic,payload,qos) values (?,?,1)", "vda5050/v3/demo/FL-01/order", payload);
  }

  void acceptDispatch(UUID taskId) {
    jdbc.update("update transport_task set status='ACCEPTED',accepted_at=coalesce(accepted_at,now()),updated_at=now() where id=? and status='DISPATCHED'", taskId);
    jdbc.update("update vda_dispatch set status='ACCEPTED',accepted_at=coalesce(accepted_at,now()) where task_id=? and status='PUBLISHED'", taskId);
  }

  void finishDispatch(UUID taskId) {
    jdbc.update("update vda_dispatch set status='FINISHED',finished_at=coalesce(finished_at,now()) where task_id=? and status in ('PUBLISHED','ACCEPTED','ACTIVE')", taskId);
  }

  ApiModels.TransportOrderView cancelOrder(UUID orderId) {
    List<TaskRow> queued = jdbc.query("select * from transport_task where request_id=? and status in ('QUEUED','ASSIGNED')", (rs, n) -> task(rs), orderId);
    queued.forEach(this::releaseCancelledTask);
    jdbc.update("update transport_task set status='CANCELLING',updated_at=now() where request_id=? and status in ('DISPATCHED','ACCEPTED','EXECUTING')", orderId);
    jdbc.update("update transport_order set status='CANCELLED',completed_at=now(),updated_at=now() where id=?", orderId);
    return transportOrder(orderId).orElseThrow(() -> new IllegalArgumentException("Unknown transport order"));
  }

  void completeCancellation(UUID taskId) {
    job(taskId).ifPresent(task -> {
      releaseCancelledTask(task);
      jdbc.update("update vda_dispatch set status='CANCELLED',finished_at=coalesce(finished_at,now()) where task_id=? and status<>'FINISHED'", taskId);
      jdbc.update("update agv set status='IDLE',task_id=null,carried_load_id=null,handling_phase='IDLE',fork_height=0,fork_extension=0 where task_id=?", taskId);
    });
  }

  private void releaseCancelledTask(TaskRow task) {
    if ("COMPLETED".equals(task.status()) || "CANCELLED".equals(task.status())) return;
    jdbc.update("update location set reserved=greatest(0,reserved-1) where id=?", task.destination());
    String type = jdbc.queryForObject("select order_type from transport_order where id=?", String.class, task.transportOrderId());
    jdbc.update("update load set status=?,location_id=? where id=?", "OUTBOUND".equals(type) ? "STORED" : "INBOUND", task.source(), task.loadId());
    jdbc.update("update transport_task set status='CANCELLED',completed_at=now(),updated_at=now() where id=?", task.id());
  }

  private ApiModels.ScenarioView scenario(ApiModels.RuntimeView runtime) {
    if (!runtime.scenarioConfigured() || runtime.scenarioId() == null) return new ApiModels.ScenarioView(null, null, false);
    String name = scenarioPresets().stream().filter(item -> item.id().equals(runtime.scenarioId())).map(ApiModels.ScenarioPreset::name).findFirst().orElse(runtime.scenarioId());
    return new ApiModels.ScenarioView(runtime.scenarioId(), name, true);
  }

  Optional<TaskRow> nextQueuedJob() {
    if (!isRunning()) return Optional.empty();
    return jdbc.query("select t.* from transport_task t join transport_order o on o.id=t.request_id join warehouse_runtime r on r.warehouse_id='linz' where t.status='QUEUED' and t.simulation_epoch=r.simulation_epoch and r.operation_state='RUNNING' and exists (select 1 from agv where id='FL-01' and status in ('IDLE','PARKED','CHARGING') and battery>=25) and not exists (select 1 from transport_task where status in ('DISPATCHED','ACCEPTED','EXECUTING')) order by case o.priority when 'URGENT' then 0 when 'HIGH' then 1 else 2 end,o.created_at,t.sequence_no limit 1",
        (rs, n) -> task(rs)).stream().findFirst();
  }

  List<ParkingRow> parkingTargets() {
    if (!isRunning()) return List.of();
    ApiModels.AgvView agv = agv();
    if (!"IDLE".equals(agv.status())) return List.of();
    Integer executing = jdbc.queryForObject("select count(*) from transport_task where status in ('DISPATCHED','ACCEPTED','EXECUTING')", Integer.class);
    if (executing != null && executing > 0) return List.of();
    Integer queued = jdbc.queryForObject("select count(*) from transport_task t join warehouse_runtime r on r.warehouse_id='linz' where t.simulation_epoch=r.simulation_epoch and t.status='QUEUED'", Integer.class);
    if (queued != null && queued > 0 && agv.battery() >= 25) return List.of();
    return jdbc.query("select id,map_node_id,x,z,rotation_y from location where type='PARKING_CHARGING' and occupied+reserved<capacity order by id",
        (rs, n) -> new ParkingRow(rs.getString("id"), rs.getString("map_node_id"), rs.getDouble("x"), rs.getDouble("z"), rs.getDouble("rotation_y")));
  }

  boolean enqueueParking(String parkingId, String orderJson) {
    int reserved = jdbc.update("update location set reserved=reserved+1 where id=? and type='PARKING_CHARGING' and occupied+reserved<capacity", parkingId);
    if (reserved != 1) return false;
    int claimed = jdbc.update("update agv set status='PARKING',task_id=null,current_station_id=?,charging=false where id='FL-01' and status='IDLE' and not exists (select 1 from transport_task where status in ('DISPATCHED','ACCEPTED','EXECUTING'))", parkingId);
    if (claimed != 1) {
      jdbc.update("update location set reserved=greatest(0,reserved-1) where id=?", parkingId);
      return false;
    }
    jdbc.update("insert into mqtt_outbox(topic,payload,qos) values (?,?,1)", "vda5050/v3/demo/FL-01/order", orderJson);
    return true;
  }

  Optional<TaskRow> job(UUID id) {
    return jdbc.query("select * from transport_task where id=?", (rs, n) -> task(rs), id).stream().findFirst();
  }

  Optional<TaskRow> activeTaskForOrder(UUID orderId) {
    return jdbc.query("select * from transport_task where request_id=? and status in ('DISPATCHED','ACCEPTED','EXECUTING') order by sequence_no limit 1",
        (rs, n) -> task(rs), orderId).stream().findFirst();
  }

  void markDispatched(UUID jobId, String orderJson) {
    releaseStation();
    jdbc.update("update transport_task set status='DISPATCHED',assigned_agv_id='FL-01',updated_at=now() where id=?", jobId);
    jdbc.update("update transport_order set status='IN_PROGRESS',updated_at=now() where id=(select request_id from transport_task where id=?)", jobId);
    jdbc.update("update agv set status='DISPATCHED',task_id=?,charging=false,current_station_id=null,handling_phase='IDLE' where id='FL-01'", jobId);
    jdbc.update("insert into mqtt_outbox(topic,payload,qos) values (?,?,1)", "vda5050/v3/demo/FL-01/order", orderJson);
  }

  private void releaseStation() {
    String stationId = jdbc.queryForObject("select current_station_id from agv where id='FL-01'", String.class);
    if (stationId != null)
      jdbc.update("update location set occupied=greatest(0,occupied-1),reserved=greatest(0,reserved-1) where id=? and type='PARKING_CHARGING'", stationId);
  }

  void markPicked(UUID jobId) {
    jdbc.update("update load set status='IN_TRANSIT' where id=(select load_id from transport_task where id=?) and status in ('INBOUND','STORED','OUTBOUND_QUEUED')", jobId);
    jdbc.update("update agv set carried_load_id=(select load_id from transport_task where id=?) where id='FL-01'", jobId);
  }

  void markExecuting(UUID jobId) {
    jdbc.update("update transport_task set status='EXECUTING',started_at=coalesce(started_at,now()),updated_at=now() where id=? and status in ('DISPATCHED','ACCEPTED')", jobId);
    jdbc.update("update agv set status='MOVING',task_id=? where id='FL-01'", jobId);
  }

  void updateAgvMotion(double x, double z, double theta, double velocity, String status, UUID jobId) {
    jdbc.update("update agv set x=?,z=?,theta=?,velocity=?,status=?,task_id=? where id='FL-01'", x, z, theta, velocity, status, jobId);
  }

  void updatePower(double battery, boolean charging) {
    jdbc.update("update agv set battery=?,charging=?,status=case when ? then 'CHARGING' when status='CHARGING' then 'PARKED' else status end where id='FL-01'",
        battery, charging, charging);
  }

  void updateHandling(String phase, double forkHeight, double forkExtension, String loadId, String stationId) {
    jdbc.update("update agv set handling_phase=?,fork_height=?,fork_extension=?,carried_load_id=?,current_station_id=coalesce(?,current_station_id),charging=(?='CHARGING'),status=case when ?='CHARGING' then 'CHARGING' when ?='PARKED' then 'PARKED' when ?='DOCKING' then 'DOCKING' else status end where id='FL-01'",
        phase, forkHeight, forkExtension, loadId, stationId, phase, phase, phase, phase);
    if (stationId != null && "CHARGING".equals(phase))
      jdbc.update("update location set reserved=0,occupied=1 where id=? and type='PARKING_CHARGING'", stationId);
  }

  void complete(TaskRow job) {
    int changed = jdbc.update("update transport_task set status='COMPLETED',completed_at=now(),updated_at=now() where id=? and status in ('DISPATCHED','ACCEPTED','EXECUTING')", job.id());
    if (changed == 0) return;
    jdbc.update("update location set occupied=occupied-1 where id=?", job.source());
    jdbc.update("update location set reserved=reserved-1,occupied=occupied+1 where id=?", job.destination());
    String requestType = jdbc.queryForObject("select order_type from transport_order where id=?", String.class, job.transportOrderId());
    if ("OUTBOUND".equals(requestType)) {
      jdbc.update("update load set location_id=?,status='ON_CONVEYOR' where id=?", job.destination(), job.loadId());
      jdbc.update("insert into conveyor_transfer(id,load_id,status,entered_at,exit_due_at) values (?,?, 'MOVING',now(),now()+interval '6 seconds')",
          UUID.randomUUID(), job.loadId());
    } else {
      jdbc.update("update load set location_id=?,status='STORED' where id=?", job.destination(), job.loadId());
    }
    jdbc.update("update agv set status='IDLE',task_id=null,carried_load_id=null,handling_phase='IDLE',fork_height=0,fork_extension=0 where id='FL-01'");
    jdbc.update("update transport_order set status='COMPLETED',completed_at=now(),updated_at=now() where id=? and not exists (select 1 from transport_task where request_id=? and status<>'COMPLETED')", job.transportOrderId(), job.transportOrderId());
  }

  List<String> completeDueTransfers() {
    var due = jdbc.query("select load_id from conveyor_transfer where status='MOVING' and exit_due_at<=now()", (rs, n) -> rs.getString(1));
    for (String loadId : due) {
      jdbc.update("update conveyor_transfer set status='COMPLETED',completed_at=now() where load_id=? and status='MOVING'", loadId);
      jdbc.update("update load set status='SHIPPED',shipped_at=now() where id=?", loadId);
      jdbc.update("update location set occupied=occupied-1 where id='OUTBOUND-01'");
    }
    return due;
  }

  ApiModels.RuntimeView setRuntime(String state) {
    jdbc.update("update warehouse_runtime set operation_state=?,changed_at=now() where warehouse_id='linz'", state);
    jdbc.update("update agv set status=case when ?='PAUSED' then 'PAUSED' when task_id is null then 'IDLE' else 'MOVING' end where id='FL-01'", state);
    return runtime();
  }

  ApiModels.RuntimeView setTimeScale(int multiplier) {
    if (multiplier != 1 && multiplier != 2 && multiplier != 4) throw new IllegalArgumentException("Simulation speed must be 1x, 2x, or 4x");
    jdbc.update("update warehouse_runtime set time_scale=?,changed_at=now() where warehouse_id='linz'", multiplier);
    return runtime();
  }

  ApiModels.RuntimeView reset() {
    jdbc.update("delete from mqtt_outbox");
    jdbc.update("delete from vda_dispatch");
    jdbc.update("update agv set task_id=null,status='CHARGING',x=11,z=-6,theta=0,velocity=0,battery=82,charging=true,current_station_id='PARK-01',handling_phase='CHARGING',fork_height=0,fork_extension=0,carried_load_id=null where id='FL-01'");
    jdbc.update("delete from conveyor_transfer");
    jdbc.update("delete from transport_task");
    jdbc.update("delete from transport_order");
    jdbc.update("delete from load");
    jdbc.update("update location set occupied=0,reserved=0");
    jdbc.update("update location set occupied=1 where id='PARK-01'");
    jdbc.update("update warehouse_runtime set operation_state='RUNNING',simulation_epoch=simulation_epoch+1,scenario_id=null,scenario_configured=false,changed_at=now() where warehouse_id='linz'");
    return runtime();
  }

  List<TaskRow> jobsForRequestRows(UUID requestId) {
    return jdbc.query("select * from transport_task where request_id=? order by sequence_no", (rs, n) -> task(rs), requestId);
  }

  List<ApiModels.JobView> jobsForRequest(UUID requestId) {
    return jdbc.query("select * from transport_task where request_id=? order by sequence_no", (rs, n) -> legacyJobView(task(rs)), requestId);
  }

  List<NodeRow> nodes() {
    return jdbc.query("select * from map_node where warehouse_id='linz'", (rs, n) -> new NodeRow(rs.getString("id"), rs.getDouble("x"), rs.getDouble("z")));
  }

  List<EdgeRow> edges() {
    return jdbc.query("select * from map_edge where warehouse_id='linz'", (rs, n) ->
        new EdgeRow(rs.getString("id"), rs.getString("from_node"), rs.getString("to_node"), rs.getDouble("cost"), rs.getBoolean("bidirectional")));
  }

  List<PhysicalObstacle> physicalObstacles() {
    var obstacles = new java.util.ArrayList<PhysicalObstacle>();
    obstacles.addAll(jdbc.query("select id,x,z,rotation_y,bays from rack where warehouse_id='linz'", (rs, n) ->
        new PhysicalObstacle(rs.getString("id"), "SHELF", rs.getDouble("x"), rs.getDouble("z"),
            rs.getInt("bays") * 1.05 / 2.0, .46, rs.getDouble("rotation_y"), 4.05)));
    obstacles.addAll(jdbc.query("select * from warehouse_obstacle where warehouse_id='linz'", (rs, n) ->
        new PhysicalObstacle(rs.getString("id"), rs.getString("type"), rs.getDouble("x"), rs.getDouble("z"),
            rs.getDouble("width") / 2.0, rs.getDouble("depth") / 2.0, rs.getDouble("rotation_y"), rs.getDouble("height"))));
    return List.copyOf(obstacles);
  }

  ApiModels.PlanningMap planningMap() {
    double tileSize = 1;
    double originX = -24;
    double originZ = -18;
    int columns = 48;
    int rows = 36;
    var blocked = new java.util.ArrayList<ApiModels.BlockedTile>();
    for (int row = 0; row < rows; row++) {
      for (int column = 0; column < columns; column++) {
        double centerX = originX + column + .5;
        double centerZ = originZ + row + .5;
        for (PhysicalObstacle obstacle : physicalObstacles()) {
          if (tileIntersects(centerX, centerZ, tileSize, obstacle)) {
            blocked.add(new ApiModels.BlockedTile(column, row, centerX, centerZ, obstacle.type(), obstacle.id()));
            break;
          }
        }
      }
    }
    var stations = jdbc.query("select id,type,x,z,rotation_y,operating_width,operating_depth from location where warehouse_id='linz' and type in ('INBOUND','OUTBOUND','PARKING_CHARGING') order by id",
        (rs, n) -> new ApiModels.MapStation(rs.getString("id"), rs.getString("type"), rs.getDouble("x"), rs.getDouble("z"),
            rs.getDouble("rotation_y"), (Double) rs.getObject("operating_width"), (Double) rs.getObject("operating_depth")));
    var routeNodes = nodes().stream().map(node -> Map.<String, Object>of("id", node.id(), "x", node.x(), "z", node.z())).toList();
    var routeEdges = edges().stream().map(edge -> Map.<String, Object>of("id", edge.id(), "from", edge.from(), "to", edge.to(),
        "bidirectional", edge.bidirectional())).toList();
    return new ApiModels.PlanningMap(tileSize, originX, originZ, columns, rows, true, blocked, stations, routeNodes, routeEdges);
  }

  private static boolean tileIntersects(double x, double z, double tileSize, PhysicalObstacle obstacle) {
    double cos = Math.cos(obstacle.rotationY());
    double sin = Math.sin(obstacle.rotationY());
    double dx = x - obstacle.x();
    double dz = z - obstacle.z();
    double localX = dx * cos - dz * sin;
    double localZ = dx * sin + dz * cos;
    double tileProjection = tileSize * (Math.abs(cos) + Math.abs(sin)) / 2.0;
    return Math.abs(localX) <= obstacle.halfWidth() + tileProjection && Math.abs(localZ) <= obstacle.halfDepth() + tileProjection;
  }

  String nodeForLocation(String locationId) {
    return jdbc.queryForObject("select map_node_id from location where id=?", String.class, locationId);
  }

  List<OutboxRow> pendingOutbox() {
    return jdbc.query("select * from mqtt_outbox where status='PENDING' order by id limit 20", (rs, n) ->
        new OutboxRow(rs.getLong("id"), rs.getString("topic"), rs.getString("payload"), rs.getInt("qos")));
  }

  void sent(long id) { jdbc.update("update mqtt_outbox set status='SENT',attempts=attempts+1 where id=?", id); }
  void failed(long id) { jdbc.update("update mqtt_outbox set attempts=attempts+1 where id=?", id); }

  private ApiModels.LocationView location(ResultSet rs) throws SQLException {
    return new ApiModels.LocationView(rs.getString("id"), rs.getString("name"), rs.getString("type"), rs.getInt("capacity"),
        rs.getInt("occupied"), rs.getInt("reserved"), rs.getDouble("x"), rs.getDouble("z"), rs.getString("rack_id"),
        (Integer) rs.getObject("bay_index"), (Integer) rs.getObject("level_index"), rs.getDouble("rotation_y"),
        (Double) rs.getObject("operating_width"), (Double) rs.getObject("operating_depth"),
        (Double) rs.getObject("handling_x"), (Double) rs.getObject("handling_z"),
        (Double) rs.getObject("handling_theta"), (Double) rs.getObject("handling_height"));
  }

  private ApiModels.AgvView agvView(ResultSet rs) throws SQLException {
    return new ApiModels.AgvView(rs.getString("id"), rs.getDouble("x"), rs.getDouble("z"), rs.getDouble("theta"),
        rs.getDouble("velocity"), rs.getDouble("battery"), rs.getString("status"), rs.getObject("task_id", UUID.class),
        rs.getBoolean("charging"), rs.getString("current_station_id"), rs.getString("handling_phase"),
        rs.getDouble("fork_height"), rs.getDouble("fork_extension"), rs.getString("carried_load_id"));
  }

  private ApiModels.LoadView loadView(ResultSet rs) throws SQLException {
    return new ApiModels.LoadView(rs.getString("id"), rs.getString("item"), rs.getString("status"), rs.getString("location_id"),
        instant(rs, "received_at"), instant(rs, "shipped_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    var timestamp = rs.getTimestamp(column);
    return timestamp == null ? null : timestamp.toInstant();
  }

  private TaskRow task(ResultSet rs) throws SQLException {
    return new TaskRow(rs.getObject("id", UUID.class), rs.getObject("request_id", UUID.class), rs.getInt("sequence_no"),
        rs.getString("load_id"), rs.getString("source_location"), rs.getString("destination_location"), rs.getString("status"),
        readRoute(rs.getString("route_json")), rs.getString("assigned_agv_id"));
  }

  private ApiModels.TransportTaskView taskView(ResultSet rs) throws SQLException {
    TaskRow task = task(rs);
    return new ApiModels.TransportTaskView(task.id(), task.transportOrderId(), task.sequence(), task.loadId(), task.source(),
        task.destination(), task.status(), task.route(), task.assignedAgvId(), instant(rs, "accepted_at"), instant(rs, "started_at"),
        instant(rs, "completed_at"), rs.getString("error"));
  }

  private static ApiModels.JobView legacyJobView(ApiModels.TransportTaskView task) {
    return new ApiModels.JobView(task.id(), task.transportOrderId(), task.sequence(), task.loadId(), task.source(), task.destination(), task.status(), task.route());
  }

  private static ApiModels.JobView legacyJobView(TaskRow task) {
    return new ApiModels.JobView(task.id(), task.transportOrderId(), task.sequence(), task.loadId(), task.source(), task.destination(), task.status(), task.route());
  }

  private String write(Object value) {
    try { return mapper.writeValueAsString(value); } catch (Exception exception) { throw new IllegalStateException(exception); }
  }

  private List<String> readRoute(String json) {
    try { return mapper.readValue(json, new TypeReference<>() {}); } catch (Exception exception) { throw new IllegalStateException(exception); }
  }
}
