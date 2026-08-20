package com.example.warehouse;

import com.example.warehouse.routing.RoutePlanner;
import com.example.warehouse.scenario.ScenarioPreset;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WarehouseStore {
  public record NodeRow(String id, double x, double z) {}
  public record EdgeRow(String id, String from, String to, double cost, boolean bidirectional) {}
  public record PhysicalObstacle(String id, String type, double x, double z, double halfWidth, double halfDepth, double rotationY, double height) {}
  public record ParkingRow(String id, String nodeId, double x, double z, double theta) {}
  public record StationFootprint(String id, String type, double x, double z, double width, double depth) {}
  public record HandlingRow(String locationId, double x, double z, double theta, double height) {}
  public record TaskRow(UUID id, UUID transportOrderId, int sequence, String loadId, String source, String destination, String status,
      List<String> route, String assignedAgvId) {}
  public record OutboxRow(long id, String topic, String payload, int qos) {}
  public record DispatchPayload(long orderUpdateId, String payload) {}
  private static final java.util.Map<String, Set<String>> TASK_TRANSITIONS = new java.util.HashMap<>();
  static {
    TASK_TRANSITIONS.put("PLANNING", Set.of("READY"));
    TASK_TRANSITIONS.put("READY", Set.of("QUEUED"));
    TASK_TRANSITIONS.put("QUEUED", Set.of("ASSIGNED", "DISPATCHED", "CANCELLED"));
    TASK_TRANSITIONS.put("ASSIGNED", Set.of("DISPATCHED", "CANCELLED"));
    TASK_TRANSITIONS.put("DISPATCHED", Set.of("ACCEPTED", "EXECUTING", "CANCELLING", "CANCELLED"));
    TASK_TRANSITIONS.put("ACCEPTED", Set.of("EXECUTING", "CANCELLING", "CANCELLED"));
    TASK_TRANSITIONS.put("EXECUTING", Set.of("COMPLETED", "CANCELLING", "CANCELLED"));
    TASK_TRANSITIONS.put("CANCELLING", Set.of("CANCELLED"));
    TASK_TRANSITIONS.put("REJECTED", Set.of("REJECTED"));
    TASK_TRANSITIONS.put("COMPLETED", Set.of("COMPLETED"));
    TASK_TRANSITIONS.put("CANCELLED", Set.of("CANCELLED"));
    TASK_TRANSITIONS.put("FAILED", Set.of("FAILED"));
  }

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  WarehouseStore(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public ApiModels.WarehouseSnapshot snapshot() {
    Map<String, Object> warehouse = jdbc.queryForMap("select * from warehouse where id='linz'");
    var racks = jdbc.query("select * from rack where warehouse_id='linz' order by id", (rs, n) ->
        new ApiModels.RackView(rs.getString("id"), rs.getString("name"), rs.getDouble("x"), rs.getDouble("z"), rs.getDouble("rotation_y"), rs.getInt("bays"), rs.getString("canonical_id")));
    var locations = jdbc.query("select * from location where warehouse_id='linz' order by id", (rs, n) -> location(rs));
    var loads = jdbc.query("select * from load order by received_at,id", (rs, n) -> loadView(rs));
    var agvs = jdbc.query("select * from agv where warehouse_id='linz' order by id", (rs, n) -> agvView(rs));
    var tasks = jdbc.query("select * from transport_task order by created_at", (rs, n) -> taskView(rs));
    var jobs = tasks.stream().map(WarehouseStore::legacyJobView).toList();
    var orders = transportOrders();
    var runtime = runtime();
    var transfers = jdbc.query("select * from conveyor_transfer order by entered_at", (rs, n) ->
        new ApiModels.ConveyorTransferView(rs.getObject("id", UUID.class), rs.getString("load_id"), rs.getString("carton_id"), rs.getString("conveyor_id"), rs.getString("status"),
            instant(rs, "entered_at"), instant(rs, "exit_due_at"), instant(rs, "completed_at")));
    var obstacles = jdbc.query("select * from warehouse_obstacle where warehouse_id='linz' order by id", (rs, n) ->
        new ApiModels.ObstacleView(rs.getString("id"), rs.getString("type"), rs.getDouble("x"), rs.getDouble("z"),
            rs.getDouble("width"), rs.getDouble("depth"), rs.getDouble("rotation_y"), rs.getDouble("height")));
    var cartons = jdbc.query("select * from carton order by id", (rs, n) -> new ApiModels.CartonView(rs.getString("id"), rs.getString("pallet_id"),
        rs.getString("sku"), rs.getInt("quantity"), rs.getString("status"), rs.getString("location_id"), instant(rs, "picked_at"), instant(rs, "shipped_at")));
    var robotCells = jdbc.query("select * from robot_cell_state order by robot_id", (rs, n) -> new ApiModels.RobotCellView(rs.getString("robot_id"),
        rs.getString("phase"), rs.getObject("active_pick_job_id", UUID.class), instant(rs, "updated_at")));
    return new ApiModels.WarehouseSnapshot("linz", String.valueOf(warehouse.get("name")),
        ((Number) warehouse.get("width")).doubleValue(), ((Number) warehouse.get("depth")).doubleValue(), racks, locations, loads, agvs, jobs,
        orders, tasks, scenario(runtime), runtime, transfers, obstacles, cartons, robotCells, aisles());
  }

  public ApiModels.RuntimeView runtime() {
    return jdbc.queryForObject("select * from warehouse_runtime where warehouse_id='linz'", (rs, n) ->
        new ApiModels.RuntimeView(rs.getString("operation_state"), rs.getLong("simulation_epoch"), rs.getInt("time_scale"),
            rs.getString("scenario_id"), rs.getBoolean("scenario_configured"), instant(rs, "changed_at")));
  }

  public ApiModels.AgvView agv() {
    return agv("FL-01");
  }

  public ApiModels.AgvView agv(String agvId) {
    return jdbc.queryForObject("select * from agv where id=?", (rs, n) -> agvView(rs), agvId);
  }

  public List<String> agvIds() {
    return jdbc.queryForList("select id from agv where warehouse_id='linz' order by id", String.class);
  }

  public Optional<String> agvIdForTask(UUID taskId) {
    return jdbc.query("select assigned_agv_id from transport_task where id=? and assigned_agv_id is not null",
        (rs, n) -> rs.getString(1), taskId).stream().findFirst();
  }

  public HandlingRow handling(String locationId) {
    return jdbc.queryForObject("select id,coalesce(handling_x,x) handling_x,coalesce(handling_z,z) handling_z,coalesce(handling_theta,rotation_y) handling_theta,coalesce(handling_height,0) handling_height from location where id=?",
        (rs, n) -> new HandlingRow(rs.getString("id"), rs.getDouble("handling_x"), rs.getDouble("handling_z"),
            rs.getDouble("handling_theta"), rs.getDouble("handling_height")), locationId);
  }

  public NodeRow locationPosition(String locationId) {
    return jdbc.queryForObject("select map_node_id,x,z from location where id=?", (rs, n) ->
        new NodeRow(rs.getString("map_node_id"), rs.getDouble("x"), rs.getDouble("z")), locationId);
  }

  public NodeRow nearestNodeToAgv() {
    return nearestNodeToAgv("FL-01");
  }

  public NodeRow nearestNodeToAgv(String agvId) {
    ApiModels.AgvView agv = agv(agvId);
    return nodes().stream().min(java.util.Comparator
        .comparingDouble((NodeRow node) -> Math.hypot(node.x() - agv.x(), node.z() - agv.z()))
        .thenComparing(NodeRow::id)).orElseThrow();
  }

  public boolean isRunning() { return "RUNNING".equals(runtime().operationState()); }

  public void requireRunning() {
    if (!isRunning()) throw new OperationsPausedException();
  }

  public List<ApiModels.IncomingLoad> incomingLoads(List<String> ids) {
    if (ids.isEmpty()) return List.of();
    String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
    return jdbc.query("select id,item,location_id from load where status='INBOUND' and id in (" + placeholders + ") order by id",
        (rs, n) -> new ApiModels.IncomingLoad(rs.getString(1), rs.getString(2), rs.getString(3)), ids.toArray());
  }

  /** Eligible storage slots, each carrying the aisle that serves it. The aisle comes
   * through the slot's rack rather than being stored on the location, so a rack can
   * be moved between aisles without rewriting every slot beneath it. */
  public List<ApiModels.CandidateSlot> candidates() {
    return jdbc.query("select l.*, a.id as aisle_id, a.name as aisle_name from location l "
        + "left join rack r on l.rack_id = r.id left join aisle a on r.aisle_id = a.id "
        + "where l.warehouse_id='linz' and l.type='STORAGE' and l.occupied+l.reserved < l.capacity order by l.id",
        (rs, n) -> new ApiModels.CandidateSlot(rs.getString("id"), rs.getString("name"),
            rs.getInt("capacity") - rs.getInt("occupied") - rs.getInt("reserved"), rs.getDouble("x"), rs.getDouble("z"),
            rs.getString("aisle_id"), rs.getString("aisle_name")));
  }

  public List<ApiModels.AisleView> aisles() {
    return jdbc.query("select id,name,x,z,rotation_y,length,width from aisle where warehouse_id='linz' order by id",
        (rs, n) -> new ApiModels.AisleView(rs.getString("id"), rs.getString("name"), rs.getDouble("x"),
            rs.getDouble("z"), rs.getDouble("rotation_y"), rs.getDouble("length"), rs.getDouble("width")));
  }

  void createRequest(UUID id, String type, String prompt, List<String> loadIds) {
    createRequest(id, type, "NORMAL", prompt, runtime().scenarioId(), loadIds);
  }

  public void createRequest(UUID id, String type, String priority, String objective, String scenarioId, List<String> loadIds) {
    jdbc.update("insert into transport_order(id,status,objective,order_type,priority,scenario_id) values (?,?,?,?,?,?)",
        id, "PLANNING", objective, type, priority, scenarioId);
    for (int index = 0; index < loadIds.size(); index++)
      jdbc.update("insert into transport_order_load(request_id,load_id,sequence_no) values (?,?,?)", id, loadIds.get(index), index + 1);
  }

  public ApiModels.PutawayStatus request(UUID id) {
    return jdbc.queryForObject("select * from transport_order where id=?", (rs, n) ->
        new ApiModels.PutawayStatus(id, rs.getString("status"), rs.getString("objective"), rs.getString("error"),
            rs.getTimestamp("created_at").toInstant(), jobsForRequest(id)), id);
  }

  public void rejectRequest(UUID id, String error) {
    jdbc.update("update transport_order set status='FAILED', error=?,completed_at=now(),updated_at=now() where id=?", error, id);
  }

  public void createPlannedJobs(UUID requestId, List<ApiModels.IncomingLoad> loads, ApiModels.PlacementPlan plan, RoutePlanner routes) {
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

  public List<ApiModels.LoadView> receive(String sku, int quantity) {
    requireRunning();
    Integer free = jdbc.queryForObject("select capacity-occupied-reserved from location where id='INBOUND-01' for update", Integer.class);
    if (free == null || free < quantity) throw new IllegalStateException("Inbound staging does not have enough free positions");
    var result = new java.util.ArrayList<ApiModels.LoadView>();
    for (int index = 0; index < quantity; index++) {
      Long number = jdbc.queryForObject("select nextval('load_display_id_seq')", Long.class);
      String id = "BOX-%06d".formatted(number);
      jdbc.update("insert into load(id,item,status,location_id,received_at) values (?,?, 'INBOUND','INBOUND-01',now())", id, sku.trim());
      createCartons(id, sku.trim(), "ON_PALLET", "INBOUND-01");
      jdbc.update("update location set occupied=occupied+1 where id='INBOUND-01'");
      result.add(jdbc.queryForObject("select * from load where id=?", (rs, n) -> loadView(rs), id));
    }
    return result;
  }

  public UUID createOutbound(List<String> loadIds, RoutePlanner routes) {
    return createOutbound(loadIds, "NORMAL", "Operator outbound shipment", runtime().scenarioId(), routes);
  }

  public UUID createOutbound(List<String> loadIds, String priority, String objective, String scenarioId, RoutePlanner routes) {
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
    return ScenarioPreset.all();
  }

  public ApiModels.WarehouseSnapshot seedScenario(String presetId, RoutePlanner routes) {
    ApiModels.ScenarioPreset preset = scenarioPresets().stream().filter(item -> item.id().equals(presetId)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown scenario preset: " + presetId));
    reset();
    jdbc.update("update agv set battery=? where id='FL-01'", preset.agvBattery());
    String[] skus = {"ELECTRONICS", "AUTOMOTIVE", "MEDICAL", "FOOD-DRY", "TOOLS"};
    List<String> slots = jdbc.queryForList("select id from location where type='STORAGE' order by bay_index,level_index,rack_id limit ?", String.class, preset.storedLoads());
    for (int index = 0; index < slots.size(); index++) {
      String loadId = "SEED-%03d".formatted(index + 1);
      String sku = skus[index % skus.length];
      jdbc.update("insert into load(id,item,status,location_id,received_at) values (?,?, 'STORED',?,now())", loadId, sku, slots.get(index));
      createCartons(loadId, sku, "STORED", slots.get(index));
      jdbc.update("update location set occupied=1 where id=?", slots.get(index));
    }
    var inbound = new java.util.ArrayList<String>();
    for (int index = 0; index < preset.inboundLoads(); index++) {
      String loadId = "IN-%03d".formatted(index + 1);
      inbound.add(loadId);
      String sku = skus[index % skus.length];
      jdbc.update("insert into load(id,item,status,location_id,received_at) values (?,?, 'INBOUND','INBOUND-01',now())", loadId, sku);
      createCartons(loadId, sku, "ON_PALLET", "INBOUND-01");
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

  public List<ApiModels.TransportOrderView> transportOrders() {
    return jdbc.query("select * from transport_order order by case status when 'IN_PROGRESS' then 0 when 'READY' then 1 when 'PLANNING' then 2 else 3 end,case priority when 'URGENT' then 0 when 'HIGH' then 1 else 2 end,created_at desc",
        (rs, n) -> transportOrder(rs));
  }

  public Optional<ApiModels.TransportOrderView> transportOrder(UUID id) {
    return jdbc.query("select * from transport_order where id=?", (rs, n) -> transportOrder(rs), id).stream().findFirst();
  }

  private ApiModels.TransportOrderView transportOrder(ResultSet rs) throws SQLException {
    UUID id = rs.getObject("id", UUID.class);
    List<ApiModels.TransportTaskView> tasks = jdbc.query("select * from transport_task where request_id=? order by sequence_no", (taskRs, n) -> taskView(taskRs), id);
    List<ApiModels.VdaDispatchView> dispatches = jdbc.query("select * from vda_dispatch where task_id in (select id from transport_task where request_id=?) order by created_at desc",
        (dispatchRs, n) -> dispatchView(dispatchRs), id);
    List<ApiModels.ExecutionEventView> executionEvents = executionEvents(id);
    return new ApiModels.TransportOrderView(id, rs.getString("order_type"), rs.getString("priority"), rs.getString("status"),
        rs.getString("objective"), rs.getString("scenario_id"), rs.getString("error"), instant(rs, "created_at"),
        instant(rs, "completed_at"), tasks, dispatches, executionEvents);
  }

  private ApiModels.VdaDispatchView dispatchView(ResultSet rs) throws SQLException {
    String validationError = rs.getString("validation_error");
    return new ApiModels.VdaDispatchView(rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class),
        rs.getString("manufacturer"), rs.getString("serial_number"), rs.getString("order_id"), rs.getLong("order_update_id"),
        rs.getString("status"), validationError == null, validationError, rs.getString("rejection_error"), instant(rs, "created_at"),
        instant(rs, "published_at"), instant(rs, "accepted_at"), instant(rs, "finished_at"), rs.getString("payload_json"));
  }

  private List<ApiModels.ExecutionEventView> executionEvents(UUID orderId) {
    return jdbc.query("select * from execution_event where transport_order_id = ? order by occurred_at, id", (rs, n) ->
        new ApiModels.ExecutionEventView(rs.getObject("id", UUID.class), rs.getObject("transport_order_id", UUID.class),
            rs.getObject("transport_task_id", UUID.class), rs.getString("vehicle_id"), rs.getString("event_type"),
            rs.getString("correlation_id"), rs.getString("vda_order_id"), rs.getLong("order_update_id"),
            instant(rs, "occurred_at"), rs.getString("description")),
        orderId);
  }

  void recordDispatch(UUID taskId, String orderId, long updateId, String payload) {
    String agvId = jdbc.queryForObject("select coalesce(assigned_agv_id,'FL-01') from transport_task where id=?", String.class, taskId);
    recordDispatch(taskId, agvId, orderId, updateId, payload);
  }

  public void recordDispatch(UUID taskId, String agvId, String orderId, long updateId, String payload) {
    jdbc.update("insert into vda_dispatch(id,task_id,manufacturer,serial_number,order_id,order_update_id,status,payload_json,published_at) values (?,?,?,?,?,?, 'PUBLISHED',?,now())",
        UUID.randomUUID(), taskId, "demo", agvId, orderId, updateId, payload);
  }

  public Optional<DispatchPayload> latestDispatch(UUID taskId) {
    return jdbc.query("select order_update_id,payload_json from vda_dispatch where task_id=? order by order_update_id desc limit 1",
        (rs, n) -> new DispatchPayload(rs.getLong(1), rs.getString(2)), taskId).stream().findFirst();
  }

  public void enqueueOrderUpdate(UUID taskId, String orderId, long updateId, String payload) {
    String agvId = jdbc.queryForObject("select coalesce(assigned_agv_id,'FL-01') from transport_task where id=?", String.class, taskId);
    recordDispatch(taskId, agvId, orderId, updateId, payload);
    jdbc.update("insert into mqtt_outbox(topic,payload,qos) values (?,?,1)", com.example.warehouse.mqtt.TopicFactory.order(agvId), payload);
  }

  void appendExecutionEvent(UUID taskId, String eventType, String description, String correlationId, String vdaOrderId, long orderUpdateId) {
    TaskRow task = job(taskId).orElseThrow(() -> new IllegalArgumentException("Unknown task: " + taskId));
    jdbc.update("insert into execution_event(id,transport_order_id,transport_task_id,vehicle_id,event_type,correlation_id,vda_order_id,order_update_id,occurred_at,description) "
        + "values (?,?,?,?,?,?,?,?,now(),?)", UUID.randomUUID(), task.transportOrderId(), task.id(), task.assignedAgvId(), eventType,
        correlationId, vdaOrderId, orderUpdateId, description);
  }

  private String taskStatus(UUID taskId) {
    return jdbc.query("select status from transport_task where id=?", (rs, n) -> rs.getString(1), taskId).stream().findFirst().orElse(null);
  }

  private boolean transitionTaskStatus(UUID taskId, String targetStatus) {
    String current = taskStatus(taskId);
    if (current == null) throw new IllegalArgumentException("Unknown task: " + taskId);
    if (current.equals(targetStatus)) return false;
    Set<String> allowed = TASK_TRANSITIONS.getOrDefault(current, Set.of());
    if (!allowed.contains(targetStatus)) throw new IllegalStateException("Invalid task transition " + current + " -> " + targetStatus);
    int changed = jdbc.update("update transport_task set status=?,updated_at=now() where id=? and status=?", targetStatus, taskId, current);
    if (changed == 0) return false;
    return true;
  }

  private boolean safeTransitionTaskStatus(UUID taskId, String targetStatus) {
    try {
      return transitionTaskStatus(taskId, targetStatus);
    } catch (IllegalStateException exception) {
      return false;
    }
  }

  public void acceptDispatch(UUID taskId) {
    if (safeTransitionTaskStatus(taskId, "ACCEPTED")) {
      jdbc.update("update transport_task set accepted_at=coalesce(accepted_at,now()) where id=?", taskId);
      appendExecutionEvent(taskId, "TASK_ACCEPTED", "Task accepted by AGV", null, null, 0);
    }
    jdbc.update("update vda_dispatch set status='ACCEPTED',accepted_at=coalesce(accepted_at,now()) where task_id=? and status='PUBLISHED'", taskId);
  }

  public void finishDispatch(UUID taskId) {
    jdbc.update("update vda_dispatch set status='FINISHED',finished_at=coalesce(finished_at,now()) where task_id=? and status in ('PUBLISHED','ACCEPTED','ACTIVE')", taskId);
  }

  public ApiModels.TransportOrderView cancelOrder(UUID orderId) {
    List<TaskRow> queued = jdbc.query("select * from transport_task where request_id=? and status in ('QUEUED','ASSIGNED','DISPATCHED','ACCEPTED','EXECUTING') order by sequence_no", (rs, n) -> task(rs), orderId);
    queued.forEach(task -> {
      if (!"COMPLETED".equals(task.status()) && !"CANCELLED".equals(task.status())) {
        if (task.status().equals("QUEUED") || task.status().equals("ASSIGNED")) {
          transitionTaskStatus(task.id(), "CANCELLED");
        } else {
          if (safeTransitionTaskStatus(task.id(), "CANCELLING")) appendExecutionEvent(task.id(), "TASK_CANCELLING", "Operator requested task cancellation", null, null, 0);
        }
        releaseCancelledTask(task);
      }
    });
    jdbc.update("update transport_order set status='CANCELLED',completed_at=now(),updated_at=now() where id=?", orderId);
    return transportOrder(orderId).orElseThrow(() -> new IllegalArgumentException("Unknown transport order"));
  }

  /** Tasks whose order is cancelled but whose pallet was never given back.
   *
   * <p>{@link #completeCancellation} is what returns the load to its source, and it runs
   * only when the vehicle echoes a finished cancelOrder instant action. If the vehicle was
   * not executing that task -- parked, mid-park, or holding an order it never started --
   * the echo never comes, the order is left CANCELLED, and the load sits in IN_TRANSIT:
   * not INBOUND so it cannot be put away, not STORED so it can never ship. Inventory
   * correctness must not depend on the vehicle answering, so this is swept up instead.
   *
   * <p>Deliberately grace-delayed rather than immediate: handling telemetry is
   * latest-value-wins, so releasing while the fork is still reporting the load would let
   * updateHandling write it straight back onto the vehicle. */
  public List<TaskRow> tasksAwaitingCancellation(int graceSeconds) {
    return jdbc.query("select t.* from transport_task t join transport_order o on o.id=t.request_id "
        + "where o.status='CANCELLED' and t.status in ('DISPATCHED','ACCEPTED','EXECUTING') "
        + "and t.updated_at < now() - make_interval(secs => ?)",
        (rs, n) -> task(rs), graceSeconds);
  }

  /** Tasks handed to a vehicle that never started them. A task in DISPATCHED with no
   * started_at has been published to the broker and forgotten: nothing retries it and
   * nothing times it out, so it is invisible until someone notices the pallet never moved. */
  public List<TaskRow> tasksStalledInDispatch(int graceSeconds) {
    return jdbc.query("select * from transport_task where status='DISPATCHED' and started_at is null "
        + "and updated_at < now() - make_interval(secs => ?)", (rs, n) -> task(rs), graceSeconds);
  }

  /** True while a vehicle is standing in, or reaching into, a guarded cell.
   *
   * <p>The AGV has to break the plane of the robot cell's guarding to serve it: the
   * handoff pad sits at x -3.60, 3.2 m inside a cell that spans x -7.80..-0.40, because
   * that is the only spot the arm can reach while still reaching the conveyor infeed on
   * the other side. So OUTBOUND-01's handling pose is at x -1.90 -- 1.5 m inside the cell,
   * 2.0 m outside the staging area it serves -- and the forks reach a further 1.72 m in.
   * That is legitimate; what was missing is the interlock every real cell pairs it with.
   *
   * <p>The margin covers the vehicle's own envelope, so the arm is held while the forks
   * are inside even though the vehicle origin is not. */
  public boolean guardedCellOccupied(String cellId) {
    Integer occupants = jdbc.queryForObject(
        "select count(*) from agv a, location c where c.id=? and c.operating_width is not null "
        + "and a.x between c.x - c.operating_width/2 - ? and c.x + c.operating_width/2 + ? "
        + "and a.z between c.z - c.operating_depth/2 - ? and c.z + c.operating_depth/2 + ?",
        Integer.class, cellId, VEHICLE_REACH_MARGIN, VEHICLE_REACH_MARGIN,
        VEHICLE_REACH_MARGIN, VEHICLE_REACH_MARGIN);
    return occupants != null && occupants > 0;
  }

  /** How far the forks reach ahead of the vehicle origin, from the carriage offset the
   * renderer parents carried cargo at. */
  private static final double VEHICLE_REACH_MARGIN = 1.8;

  public void completeCancellation(UUID taskId) {
    job(taskId).ifPresent(task -> {
      releaseCancelledTask(task);
      releaseTaskZone(taskId);
      jdbc.update("update vda_dispatch set status='CANCELLED',finished_at=coalesce(finished_at,now()) where task_id=? and status<>'FINISHED'", taskId);
      jdbc.update("update agv set status='IDLE',task_id=null,carried_load_id=null,handling_phase='IDLE',fork_height=0,fork_extension=0 where task_id=?", taskId);
    });
  }

  private void releaseCancelledTask(TaskRow task) {
    if ("COMPLETED".equals(task.status()) || "CANCELLED".equals(task.status())) return;
    if (!transitionTaskStatus(task.id(), "CANCELLED")) return;
    jdbc.update("update location set reserved=greatest(0,reserved-1) where id=?", task.destination());
    String type = jdbc.queryForObject("select order_type from transport_order where id=?", String.class, task.transportOrderId());
    jdbc.update("update load set status=?,location_id=? where id=?", "OUTBOUND".equals(type) ? "STORED" : "INBOUND", task.source(), task.loadId());
    jdbc.update("update transport_task set completed_at=now() where id=?", task.id());
    appendExecutionEvent(task.id(), "TASK_CANCELLED", "Task cancelled before execution", null, null, 0);
  }

  private ApiModels.ScenarioView scenario(ApiModels.RuntimeView runtime) {
    if (!runtime.scenarioConfigured() || runtime.scenarioId() == null) return new ApiModels.ScenarioView(null, null, false);
    String name = scenarioPresets().stream().filter(item -> item.id().equals(runtime.scenarioId())).map(ApiModels.ScenarioPreset::name).findFirst().orElse(runtime.scenarioId());
    return new ApiModels.ScenarioView(runtime.scenarioId(), name, true);
  }

  Optional<TaskRow> nextQueuedJob() {
    return queuedJobs(1).stream().findFirst();
  }

  /** Queued tasks in dispatch order. More than one is returned so the caller can
   * skip a task whose destination zone is already reserved instead of abandoning
   * the whole dispatch pass: every OUTBOUND task shares the OUTBOUND-01
   * destination zone, so returning only the head of the queue let one blocked
   * outbound task starve unrelated put-away work while the vehicle sat idle. */
  public List<TaskRow> queuedJobs(int limit) {
    if (!isRunning()) return List.of();
    return jdbc.query("select t.* from transport_task t join transport_order o on o.id=t.request_id join warehouse_runtime r on r.warehouse_id='linz' "
        + "where t.status='QUEUED' and t.simulation_epoch=r.simulation_epoch and r.operation_state='RUNNING' "
        + "and exists (select 1 from agv where status in (" + CLAIMABLE_STATUSES + ") and battery>=25 and task_id is null) "
        + "order by case o.priority when 'URGENT' then 0 when 'HIGH' then 1 else 2 end,o.created_at,t.sequence_no limit ?",
        (rs, n) -> task(rs), limit);
  }

  /** Statuses in which a vehicle holds no task and may be given one.
   *
   * <p>PARKING belongs here: it means the vehicle is driving to a charger on a
   * housekeeping order, not that it is busy. Excluding it deadlocked the fleet —
   * parkIfIdle would send the idle vehicle to a bay, and any task arriving during
   * that drive could neither claim it nor wait for it, because leaving PARKING
   * depends on the dock telemetry round trip. A new task simply preempts the park
   * move, which is what a fleet manager should do. */
  private static final String CLAIMABLE_STATUSES = "'IDLE','PARKED','CHARGING','PARKING'";

  public Optional<String> claimableAgvId() {
    return jdbc.query("select id from agv where status in (" + CLAIMABLE_STATUSES
        + ") and battery>=25 and task_id is null order by case id when 'FL-01' then 0 else 1 end,id limit 1",
        (rs, n) -> rs.getString(1)).stream().findFirst();
  }

  /** Single-vehicle by design: the reference fleet is FL-01 only (see V20), so
   * binding the parking lifecycle to agv() is deliberate rather than an omission.
   * Adding a second vehicle requires threading agvId through here,
   * {@link #enqueueParking} and DispatchService.parkIfIdle together. */
  public List<ParkingRow> parkingTargets() {
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

  /** True for an order this service published that has no task behind it -- a park or
   * charge move. Housekeeping orders are real VDA orders on the wire, so the vehicle
   * reports state against them, but they carry no transport task to transition. Without
   * this the lookup treated every one of those states as an unknown task and rejected it:
   * 5,704 rejections in three hours on the reference stack, each one logged at WARN and
   * published as AGV_MESSAGE_REJECTED, so the operator view showed a protocol failure
   * every time the vehicle drove to a charger. */
  public boolean isHousekeepingOrder(String orderId) {
    Integer known = jdbc.queryForObject(
        "select count(*) from vda_dispatch where order_id=? and task_id is null", Integer.class, orderId);
    return known != null && known > 0;
  }

  public boolean enqueueParking(String parkingId, String orderId, String orderJson) {
    int reserved = jdbc.update("update location set reserved=reserved+1 where id=? and type='PARKING_CHARGING' and occupied+reserved<capacity", parkingId);
    if (reserved != 1) return false;
    int claimed = jdbc.update("update agv set status='PARKING',task_id=null,current_station_id=?,charging=false where id='FL-01' and status='IDLE' and not exists (select 1 from transport_task where status in ('DISPATCHED','ACCEPTED','EXECUTING'))", parkingId);
    if (claimed != 1) {
      jdbc.update("update location set reserved=greatest(0,reserved-1) where id=?", parkingId);
      return false;
    }
    // Recorded in the same audit table as every other published order, with a null task.
    // A park move used to leave no trace at all, which is why its state messages could not
    // be told apart from a genuinely unknown order.
    recordDispatch(null, "FL-01", orderId, 0, orderJson);
    jdbc.update("insert into mqtt_outbox(topic,payload,qos) values (?,?,1)", com.example.warehouse.mqtt.TopicFactory.order("FL-01"), orderJson);
    return true;
  }

  public Optional<TaskRow> job(UUID id) {
    return jdbc.query("select * from transport_task where id=?", (rs, n) -> task(rs), id).stream().findFirst();
  }

  public Optional<TaskRow> activeTaskForOrder(UUID orderId) {
    return jdbc.query("select * from transport_task where request_id=? and status in ('DISPATCHED','ACCEPTED','EXECUTING') order by sequence_no limit 1",
        (rs, n) -> task(rs), orderId).stream().findFirst();
  }

  public void markDispatched(UUID jobId, String agvId, String orderJson) {
    releaseStation(agvId);
    if (!safeTransitionTaskStatus(jobId, "DISPATCHED")) return;
    jdbc.update("update transport_task set assigned_agv_id=?,updated_at=now() where id=?", agvId, jobId);
    appendExecutionEvent(jobId, "TASK_DISPATCHED", "Task dispatched to AGV", null, null, 0);
    jdbc.update("update transport_order set status='IN_PROGRESS',updated_at=now() where id=(select request_id from transport_task where id=?)", jobId);
    jdbc.update("update agv set status='DISPATCHED',task_id=?,charging=false,current_station_id=null,handling_phase='IDLE' where id=?", jobId, agvId);
    jdbc.update("insert into mqtt_outbox(topic,payload,qos) values (?,?,1)", com.example.warehouse.mqtt.TopicFactory.order(agvId), orderJson);
  }

  public boolean reserveTaskZone(UUID taskId, String agvId, String destination) {
    String zoneId = "STATION-" + canonicalLocation(destination);
    int claimed = jdbc.update("insert into zone_reservation(id,zone_id,agv_id,task_id,status) values (?,?,?,?, 'ACTIVE') on conflict (zone_id) where status='ACTIVE' do nothing",
        UUID.randomUUID(), zoneId, agvId, taskId);
    return claimed == 1;
  }

  void releaseTaskZone(UUID taskId) {
    jdbc.update("update zone_reservation set status='RELEASED',released_at=now() where task_id=? and status='ACTIVE'", taskId);
  }

  private void releaseStation(String agvId) {
    String stationId = jdbc.queryForObject("select current_station_id from agv where id=?", String.class, agvId);
    if (stationId != null)
      jdbc.update("update location set occupied=greatest(0,occupied-1),reserved=greatest(0,reserved-1) where id=? and type='PARKING_CHARGING'", stationId);
  }

  public void markPicked(UUID jobId, String agvId) {
    safeTransitionTaskStatus(jobId, "EXECUTING");
    jdbc.update("update transport_task set started_at=coalesce(started_at,now()),updated_at=now() where id=? and status in ('DISPATCHED','ACCEPTED','EXECUTING')", jobId);
    int updated = jdbc.update("update load set status='IN_TRANSIT' where id=(select load_id from transport_task where id=?) and status in ('INBOUND','STORED','OUTBOUND_QUEUED')", jobId);
    if (updated > 0) appendExecutionEvent(jobId, "TASK_PICKED", "Load picked by AGV", null, null, 0);
    jdbc.update("update agv set carried_load_id=(select load_id from transport_task where id=?) where id=?", jobId, agvId);
  }

  public void markExecuting(UUID jobId, String agvId) {
    boolean transitioned = safeTransitionTaskStatus(jobId, "EXECUTING");
    if (transitioned) {
      jdbc.update("update transport_task set started_at=coalesce(started_at,now()),updated_at=now() where id=?", jobId);
      appendExecutionEvent(jobId, "TASK_EXECUTING", "Task entered motion", null, null, 0);
    }
    jdbc.update("update agv set status='MOVING',task_id=? where id=?", jobId, agvId);
  }

  /** Persists the coalesced live pose. Telemetry is latest-value-wins and lags the
   * command path by up to one pose interval, so it must never write task_id: the
   * cached value can name a task that has already completed, and writing it back
   * left the vehicle PARKED holding a dead task id. Dispatch claims only vehicles
   * with task_id is null, so that row was permanently unclaimable and every queued
   * order stalled behind it with nothing in the logs.
   *
   * <p>Only task_id needed guarding. An earlier version gated the status write on
   * the cached task still matching the stored one, which was an over-correction:
   * whenever the cache lagged a dispatch the vehicle kept whatever status it had
   * before, so it sat reading PARKED while visibly driving across the floor. Pose
   * and status both always land; the jobId argument is deliberately unused. */
  public void updateAgvMotion(String agvId, double x, double z, double theta, double velocity, String status, UUID jobId) {
    jdbc.update("update agv set x=?,z=?,theta=?,velocity=?,status=? where id=?", x, z, theta, velocity, status, agvId);
  }

  public void updatePower(String agvId, double battery, boolean charging) {
    jdbc.update("update agv set battery=?,charging=?,status=case when ? then 'CHARGING' when status='CHARGING' then 'PARKED' else status end where id=?",
        battery, charging, charging, agvId);
  }

  public void updateHandling(String agvId, String phase, double forkHeight, double forkExtension, String loadId, String stationId) {
    jdbc.update("update agv set handling_phase=?,fork_height=?,fork_extension=?,carried_load_id=?,current_station_id=coalesce(?,current_station_id),charging=(?='CHARGING'),status=case when ?='CHARGING' then 'CHARGING' when ?='PARKED' then 'PARKED' when ?='DOCKING' then 'DOCKING' else status end where id=?",
        phase, forkHeight, forkExtension, loadId, stationId, phase, phase, phase, phase, agvId);
    if (stationId != null && "CHARGING".equals(phase))
      jdbc.update("update location set reserved=0,occupied=1 where id=? and type='PARKING_CHARGING'", stationId);
  }

  public void complete(TaskRow job) {
    boolean transitioned = safeTransitionTaskStatus(job.id(), "COMPLETED");
    if (!transitioned) return;
    jdbc.update("update transport_task set completed_at=now(),updated_at=now() where id=?", job.id());
    appendExecutionEvent(job.id(), "TASK_COMPLETED", "Task completed", null, null, 0);
    releaseTaskZone(job.id());
    jdbc.update("update location set occupied=occupied-1 where id=?", job.source());
    jdbc.update("update location set reserved=reserved-1,occupied=occupied+1 where id=?", job.destination());
    String requestType = jdbc.queryForObject("select order_type from transport_order where id=?", String.class, job.transportOrderId());
    if ("OUTBOUND".equals(requestType)) {
      jdbc.update("update load set location_id=?,status='AT_ROBOT_HANDOFF' where id=?", job.destination(), job.loadId());
      jdbc.update("update carton set status='AT_HANDOFF',location_id=? where pallet_id=? and status in ('ON_PALLET','STORED')", job.destination(), job.loadId());
      jdbc.update("insert into robot_pick_job(id,transport_task_id,carton_id,robot_id,status) "
          + "select gen_random_uuid(),?,id,'ROBOT-01','QUEUED' from carton where pallet_id=? and status='AT_HANDOFF'",
          job.id(), job.loadId());
      jdbc.update("update transport_order set status='ROBOT_PROCESSING' where id=?", job.transportOrderId());
    } else {
      jdbc.update("update load set location_id=?,status='STORED' where id=?", job.destination(), job.loadId());
      jdbc.update("update carton set location_id=?,status='STORED' where pallet_id=? and status='ON_PALLET'", job.destination(), job.loadId());
    }
    jdbc.update("update agv set status='IDLE',task_id=null,carried_load_id=null,handling_phase='IDLE',fork_height=0,fork_extension=0 where id=?", job.assignedAgvId());
    jdbc.update("update transport_order set status='COMPLETED',completed_at=now(),updated_at=now() where id=? and order_type<>'OUTBOUND' and not exists (select 1 from transport_task where request_id=? and status<>'COMPLETED')", job.transportOrderId(), job.transportOrderId());
  }

  private void createCartons(String loadId, String sku, String status, String locationId) {
    for (int index = 1; index <= 4; index++) {
      jdbc.update("insert into carton(id,pallet_id,sku,quantity,status,location_id) values (?,?,?,?,?,?)",
          "%s-C%02d".formatted(loadId, index), loadId, sku, 1, status, locationId);
    }
  }

  public List<String> completeDueTransfers() {
    var due = jdbc.query("select load_id,carton_id from conveyor_transfer where status='MOVING' and exit_due_at<=now()", (rs, n) ->
        Map.entry(rs.getString("load_id"), rs.getString("carton_id")));
    for (var transfer : due) {
      jdbc.update("update conveyor_transfer set status='COMPLETED',completed_at=now() where status='MOVING' and carton_id is not distinct from ? and load_id=?",
          transfer.getValue(), transfer.getKey());
      if (transfer.getValue() != null) {
        jdbc.update("update carton set status='SHIPPED',shipped_at=now() where id=?", transfer.getValue());
        int loadShipped = jdbc.update("update load set status='SHIPPED',shipped_at=now() where id=? and status<>'SHIPPED' and not exists (select 1 from carton where pallet_id=? and status<>'SHIPPED')",
            transfer.getKey(), transfer.getKey());
        if (loadShipped > 0) jdbc.update("update location set occupied=greatest(0,occupied-1) where canonical_id='OUT-STG-01' or id='OUTBOUND-01'");
      } else {
        int loadShipped = jdbc.update("update load set status='SHIPPED',shipped_at=now() where id=? and status<>'SHIPPED'", transfer.getKey());
        if (loadShipped > 0) jdbc.update("update location set occupied=greatest(0,occupied-1) where canonical_id='OUT-STG-01' or id='OUTBOUND-01'");
      }
      jdbc.update("update transport_order o set status='COMPLETED',completed_at=now(),updated_at=now() "
          + "where o.order_type='OUTBOUND' and o.status='ROBOT_PROCESSING' "
          + "and not exists (select 1 from transport_order_load tol join load l on l.id=tol.load_id "
          + "where tol.request_id=o.id and l.status<>'SHIPPED')");
    }
    return due.stream().map(Map.Entry::getKey).distinct().toList();
  }

  public Optional<Map<String, Object>> nextRobotPick() {
    return jdbc.query("select id,transport_task_id,carton_id,robot_id,status,coalesce(started_at,created_at) as phase_at "
        + "from robot_pick_job where status in ('QUEUED','AT_HANDOFF','PICKING','PLACING') "
        + "order by case when status='QUEUED' then 1 else 0 end,created_at,id limit 1",
        (rs, n) -> Map.<String, Object>of("id", rs.getObject("id", UUID.class), "taskId", rs.getObject("transport_task_id", UUID.class),
            "cartonId", rs.getString("carton_id"), "robotId", rs.getString("robot_id"), "status", rs.getString("status"), "createdAt", instant(rs, "phase_at"))).stream().findFirst();
  }

  public boolean robotCellAvailable() {
    Integer active = jdbc.queryForObject("select count(*) from robot_cell_state where phase <> 'IDLE'", Integer.class);
    return active == null || active == 0;
  }

  public void robotPhase(UUID pickId, String phase) {
    jdbc.update("update robot_pick_job set status=?,started_at=now() where id=?", phase, pickId);
    jdbc.update("update robot_cell_state set phase=?,active_pick_job_id=?,updated_at=now() where robot_id='ROBOT-01'", phase, pickId);
    String cartonStatus = switch (phase) {
      case "PICKING" -> "PICKING";
      case "PLACING" -> "PLACING";
      default -> "AT_HANDOFF";
    };
    jdbc.update("update carton set status=? where id=(select carton_id from robot_pick_job where id=?)", cartonStatus, pickId);
  }

  public void completeRobotPick(UUID pickId, UUID taskId, String cartonId) {
    String conveyorId = jdbc.queryForObject("select case when (select count(*) from conveyor_transfer where status='MOVING' and conveyor_id='CONV-OUT-01') <= "
        + "(select count(*) from conveyor_transfer where status='MOVING' and conveyor_id='CONV-OUT-02') then 'CONV-OUT-01' else 'CONV-OUT-02' end", String.class);
    String loadId = jdbc.queryForObject("select pallet_id from carton where id=?", String.class, cartonId);
    jdbc.update("update robot_pick_job set status='COMPLETE',conveyor_id=?,completed_at=now() where id=?", conveyorId, pickId);
    jdbc.update("update carton set status='ON_CONVEYOR',location_id=?,picked_at=coalesce(picked_at,now()) where id=?", conveyorId, cartonId);
    jdbc.update("insert into conveyor_transfer(id,load_id,carton_id,conveyor_id,status,entered_at,exit_due_at) values (gen_random_uuid(),?,?,?,'MOVING',now(),now()+interval '6 seconds')",
        loadId, cartonId, conveyorId);
    jdbc.update("update load set status='ON_CONVEYOR',location_id=? where id=? and status='AT_ROBOT_HANDOFF'", conveyorId, loadId);
    jdbc.update("update robot_cell_state set phase='IDLE',active_pick_job_id=null,updated_at=now() where robot_id='ROBOT-01'");
  }

  boolean finishOutboundOrder(UUID taskId) {
    Integer remaining = jdbc.queryForObject("select count(*) from robot_pick_job where transport_task_id=? and status<>'COMPLETE'", Integer.class, taskId);
    if (remaining != null && remaining > 0) return false;
    UUID orderId = jdbc.queryForObject("select request_id from transport_task where id=?", UUID.class, taskId);
    Integer active = jdbc.queryForObject("select count(*) from transport_task where request_id=? and status<>'COMPLETED'", Integer.class, orderId);
    if (active == null || active == 0) jdbc.update("update transport_order set status='ROBOT_PROCESSING',updated_at=now() where id=?", orderId);
    return true;
  }

  public ApiModels.RuntimeView setRuntime(String state) {
    jdbc.update("update warehouse_runtime set operation_state=?,changed_at=now() where warehouse_id='linz'", state);
    jdbc.update("update agv set status=case when ?='PAUSED' then 'PAUSED' when task_id is null then 'IDLE' else 'MOVING' end", state);
    return runtime();
  }

  public ApiModels.RuntimeView setTimeScale(int multiplier) {
    if (multiplier != 1 && multiplier != 2 && multiplier != 4) throw new IllegalArgumentException("Simulation speed must be 1x, 2x, or 4x");
    jdbc.update("update warehouse_runtime set time_scale=?,changed_at=now() where warehouse_id='linz'", multiplier);
    return runtime();
  }

  public ApiModels.RuntimeView reset() {
    jdbc.update("delete from api_idempotency_key");
    jdbc.update("delete from mqtt_outbox");
    jdbc.update("delete from vda_dispatch");
    jdbc.update("update agv set task_id=null,status='CHARGING',x=11,z=-6,theta=0,velocity=0,battery=82,charging=true,current_station_id='PARK-01',handling_phase='CHARGING',fork_height=0,fork_extension=0,carried_load_id=null where id='FL-01'");
    jdbc.update("update robot_cell_state set phase='IDLE',active_pick_job_id=null,updated_at=now()");
    jdbc.update("delete from conveyor_transfer");
    jdbc.update("delete from zone_reservation");
    jdbc.update("delete from transport_task");
    jdbc.update("delete from transport_order");
    jdbc.update("delete from carton");
    jdbc.update("delete from load");
    jdbc.update("update location set occupied=0,reserved=0");
    // Only FL-01's bay is occupied. Marking every bay occupied left
    // parkingTargets() with no candidate, because capacity is 1 per bay.
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

  public List<NodeRow> nodes() {
    return jdbc.query("select * from map_node where warehouse_id='linz'", (rs, n) -> new NodeRow(rs.getString("id"), rs.getDouble("x"), rs.getDouble("z")));
  }

  public List<EdgeRow> edges() {
    return jdbc.query("select * from map_edge where warehouse_id='linz'", (rs, n) ->
        new EdgeRow(rs.getString("id"), rs.getString("from_node"), rs.getString("to_node"), rs.getDouble("cost"), rs.getBoolean("bidirectional")));
  }

  public List<PhysicalObstacle> physicalObstacles() {
    var obstacles = new java.util.ArrayList<PhysicalObstacle>();
    obstacles.addAll(jdbc.query("select id,x,z,rotation_y,bays from rack where warehouse_id='linz'", (rs, n) ->
        new PhysicalObstacle(rs.getString("id"), "SHELF", rs.getDouble("x"), rs.getDouble("z"),
            rs.getInt("bays") * 1.05 / 2.0, .46, rs.getDouble("rotation_y"), 4.05)));
    obstacles.addAll(jdbc.query("select * from warehouse_obstacle where warehouse_id='linz'", (rs, n) ->
        new PhysicalObstacle(rs.getString("id"), rs.getString("type"), rs.getDouble("x"), rs.getDouble("z"),
            rs.getDouble("width") / 2.0, rs.getDouble("depth") / 2.0, rs.getDouble("rotation_y"), rs.getDouble("height"))));
    return List.copyOf(obstacles);
  }

  /** Operating footprints of every non-storage station, for layout validation and
   * rendering. Rows without an explicit footprint fall back to the renderer's
   * default so validation sees the same rectangle the operator sees. */
  public List<StationFootprint> stationFootprints() {
    return jdbc.query("select id,type,x,z,coalesce(operating_width,7) w,coalesce(operating_depth,7) d "
        + "from location where warehouse_id='linz' and type<>'STORAGE' order by id",
        (rs, n) -> new StationFootprint(rs.getString("id"), rs.getString("type"),
            rs.getDouble("x"), rs.getDouble("z"), rs.getDouble("w"), rs.getDouble("d")));
  }

  public ApiModels.PlanningMap planningMap() {
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
    var stations = jdbc.query("select id,type,x,z,rotation_y,operating_width,operating_depth,canonical_id from location where warehouse_id='linz' and type not in ('STORAGE') order by id",
        (rs, n) -> new ApiModels.MapStation(rs.getString("id"), rs.getString("type"), rs.getDouble("x"), rs.getDouble("z"),
            rs.getDouble("rotation_y"), (Double) rs.getObject("operating_width"), (Double) rs.getObject("operating_depth"), rs.getString("canonical_id")));
    var routeNodes = nodes().stream().map(node -> Map.<String, Object>of("id", node.id(), "x", node.x(), "z", node.z())).toList();
    var routeEdges = edges().stream().map(edge -> Map.<String, Object>of("id", edge.id(), "from", edge.from(), "to", edge.to(),
        "bidirectional", edge.bidirectional())).toList();
    return new ApiModels.PlanningMap(tileSize, originX, originZ, columns, rows, true, blocked, stations, routeNodes, routeEdges, aisles());
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

  public String nodeForLocation(String locationId) {
    return jdbc.queryForObject("select map_node_id from location where id=?", String.class, locationId);
  }

  public List<OutboxRow> pendingOutbox() {
    return jdbc.query("select * from mqtt_outbox where status='PENDING' order by id limit 20", (rs, n) ->
        new OutboxRow(rs.getLong("id"), rs.getString("topic"), rs.getString("payload"), rs.getInt("qos")));
  }

  public void sent(long id) { jdbc.update("update mqtt_outbox set status='SENT',attempts=attempts+1 where id=?", id); }
  public void failed(long id) { jdbc.update("update mqtt_outbox set attempts=attempts+1 where id=?", id); }

  private ApiModels.LocationView location(ResultSet rs) throws SQLException {
    return new ApiModels.LocationView(rs.getString("id"), rs.getString("name"), rs.getString("type"), rs.getInt("capacity"),
        rs.getInt("occupied"), rs.getInt("reserved"), rs.getDouble("x"), rs.getDouble("z"), rs.getString("rack_id"),
        (Integer) rs.getObject("bay_index"), (Integer) rs.getObject("level_index"), rs.getDouble("rotation_y"),
        (Double) rs.getObject("operating_width"), (Double) rs.getObject("operating_depth"),
        (Double) rs.getObject("handling_x"), (Double) rs.getObject("handling_z"),
        (Double) rs.getObject("handling_theta"), (Double) rs.getObject("handling_height"), rs.getString("canonical_id"));
  }

  private ApiModels.AgvView agvView(ResultSet rs) throws SQLException {
    return new ApiModels.AgvView(rs.getString("id"), rs.getDouble("x"), rs.getDouble("z"), rs.getDouble("theta"),
        rs.getDouble("velocity"), rs.getDouble("battery"), rs.getString("status"), rs.getObject("task_id", UUID.class),
        rs.getBoolean("charging"), rs.getString("current_station_id"), rs.getString("handling_phase"),
        rs.getDouble("fork_height"), rs.getDouble("fork_extension"), rs.getString("carried_load_id"));
  }

  private ApiModels.LoadView loadView(ResultSet rs) throws SQLException {
    String locationId = rs.getString("location_id");
    return new ApiModels.LoadView(rs.getString("id"), rs.getString("item"), rs.getString("status"), locationId, canonicalLocation(locationId),
        instant(rs, "received_at"), instant(rs, "shipped_at"));
  }

  private String canonicalLocation(String locationId) {
    if (locationId == null) return null;
    return jdbc.query("select coalesce(canonical_id,id) from location where id=?", (rs, n) -> rs.getString(1), locationId)
        .stream().findFirst().orElse(locationId);
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
    return new ApiModels.TransportTaskView(task.id(), task.transportOrderId(), task.sequence(), task.loadId(), canonicalLocation(task.source()),
        canonicalLocation(task.destination()), task.status(), task.route(), task.assignedAgvId(), instant(rs, "accepted_at"), instant(rs, "started_at"),
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
