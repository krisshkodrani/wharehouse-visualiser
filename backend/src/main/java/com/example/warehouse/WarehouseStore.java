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
  record JobRow(UUID id, UUID requestId, int sequence, String loadId, String source, String destination, String status, List<String> route) {}
  record OutboxRow(long id, String topic, String payload, int qos) {}

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
    var agvs = jdbc.query("select * from agv where warehouse_id='linz' order by id", (rs, n) ->
        new ApiModels.AgvView(rs.getString("id"), rs.getDouble("x"), rs.getDouble("z"), rs.getDouble("theta"),
            rs.getDouble("battery"), rs.getString("status"), rs.getObject("job_id", UUID.class)));
    var jobs = jdbc.query("select * from job order by created_at", (rs, n) -> jobView(rs));
    var runtime = runtime();
    var transfers = jdbc.query("select * from conveyor_transfer order by entered_at", (rs, n) ->
        new ApiModels.ConveyorTransferView(rs.getObject("id", UUID.class), rs.getString("load_id"), rs.getString("status"),
            instant(rs, "entered_at"), instant(rs, "exit_due_at"), instant(rs, "completed_at")));
    var obstacles = jdbc.query("select * from warehouse_obstacle where warehouse_id='linz' order by id", (rs, n) ->
        new ApiModels.ObstacleView(rs.getString("id"), rs.getString("type"), rs.getDouble("x"), rs.getDouble("z"),
            rs.getDouble("width"), rs.getDouble("depth"), rs.getDouble("rotation_y"), rs.getDouble("height")));
    return new ApiModels.WarehouseSnapshot("linz", String.valueOf(warehouse.get("name")),
        ((Number) warehouse.get("width")).doubleValue(), ((Number) warehouse.get("depth")).doubleValue(), racks, locations, loads, agvs, jobs,
        runtime, transfers, obstacles);
  }

  ApiModels.RuntimeView runtime() {
    return jdbc.queryForObject("select * from warehouse_runtime where warehouse_id='linz'", (rs, n) ->
        new ApiModels.RuntimeView(rs.getString("operation_state"), rs.getLong("simulation_epoch"), instant(rs, "changed_at")));
  }

  ApiModels.AgvView agv() {
    return jdbc.queryForObject("select * from agv where id='FL-01'", (rs, n) ->
        new ApiModels.AgvView(rs.getString("id"), rs.getDouble("x"), rs.getDouble("z"), rs.getDouble("theta"),
            rs.getDouble("battery"), rs.getString("status"), rs.getObject("job_id", UUID.class)));
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
    jdbc.update("insert into warehouse_request(id,status,prompt,request_type) values (?,?,?,?)", id, "PLANNING", prompt, type);
    for (int index = 0; index < loadIds.size(); index++)
      jdbc.update("insert into request_load(request_id,load_id,sequence_no) values (?,?,?)", id, loadIds.get(index), index + 1);
  }

  ApiModels.PutawayStatus request(UUID id) {
    return jdbc.queryForObject("select * from warehouse_request where id=?", (rs, n) ->
        new ApiModels.PutawayStatus(id, rs.getString("status"), rs.getString("prompt"), rs.getString("error"),
            rs.getTimestamp("created_at").toInstant(), jobsForRequest(id)), id);
  }

  void rejectRequest(UUID id, String error) {
    jdbc.update("update warehouse_request set status='REJECTED', error=?,completed_at=now() where id=?", error, id);
  }

  void createPlannedJobs(UUID requestId, List<ApiModels.IncomingLoad> loads, ApiModels.PlacementPlan plan, RoutePlanner routes) {
    for (int index = 0; index < plan.placements().size(); index++) {
      ApiModels.Placement placement = plan.placements().get(index);
      ApiModels.IncomingLoad load = loads.stream().filter(item -> item.id().equals(placement.loadId())).findFirst().orElseThrow();
      int reserved = jdbc.update("update location set reserved=reserved+1 where id=? and occupied+reserved < capacity", placement.slotId());
      if (reserved != 1) throw new IllegalStateException("Slot is no longer available: " + placement.slotId());
      List<String> route = routes.route(load.locationId(), placement.slotId());
      UUID jobId = UUID.randomUUID();
      jdbc.update("insert into job(id,request_id,sequence_no,load_id,source_location,destination_location,status,route_json,simulation_epoch) values (?,?,?,?,?,?,?,?,?)",
          jobId, requestId, index + 1, load.id(), load.locationId(), placement.slotId(), "QUEUED", write(route), runtime().simulationEpoch());
    }
    jdbc.update("update warehouse_request set status='VALIDATED' where id=?", requestId);
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
    createRequest(requestId, "OUTBOUND", "Operator outbound shipment", distinct);
    long epoch = runtime().simulationEpoch();
    for (int index = 0; index < loads.size(); index++) {
      var load = loads.get(index);
      UUID jobId = UUID.randomUUID();
      jdbc.update("insert into job(id,request_id,sequence_no,load_id,source_location,destination_location,status,route_json,simulation_epoch) values (?,?,?,?,?,?, 'QUEUED',?,?)",
          jobId, requestId, index + 1, load.getKey(), load.getValue(), "OUTBOUND-01", write(routes.route(load.getValue(), "OUTBOUND-01")), epoch);
      jdbc.update("update load set status='OUTBOUND_QUEUED' where id=?", load.getKey());
    }
    jdbc.update("update warehouse_request set status='VALIDATED' where id=?", requestId);
    return requestId;
  }

  Optional<JobRow> nextQueuedJob() {
    if (!isRunning()) return Optional.empty();
    return jdbc.query("select j.* from job j join warehouse_runtime r on r.warehouse_id='linz' where j.status='QUEUED' and j.simulation_epoch=r.simulation_epoch and r.operation_state='RUNNING' and not exists (select 1 from job where status in ('DISPATCHED','EXECUTING')) order by j.created_at,j.sequence_no limit 1",
        (rs, n) -> job(rs)).stream().findFirst();
  }

  Optional<JobRow> job(UUID id) {
    return jdbc.query("select * from job where id=?", (rs, n) -> job(rs), id).stream().findFirst();
  }

  void markDispatched(UUID jobId, String orderJson) {
    jdbc.update("update job set status='DISPATCHED',updated_at=now() where id=?", jobId);
    jdbc.update("update agv set status='DISPATCHED',job_id=? where id='FL-01'", jobId);
    jdbc.update("insert into mqtt_outbox(topic,payload,qos) values (?,?,1)", "vda5050/v3/demo/FL-01/order", orderJson);
  }

  void markPicked(UUID jobId) {
    jdbc.update("update load set status='IN_TRANSIT' where id=(select load_id from job where id=?) and status in ('INBOUND','STORED','OUTBOUND_QUEUED')", jobId);
  }

  void markExecuting(UUID jobId) {
    jdbc.update("update job set status='EXECUTING',updated_at=now() where id=? and status='DISPATCHED'", jobId);
    jdbc.update("update agv set status='MOVING',job_id=? where id='FL-01'", jobId);
  }

  void updateAgv(double x, double z, double theta, double battery, String status, UUID jobId) {
    jdbc.update("update agv set x=?,z=?,theta=?,battery=?,status=?,job_id=? where id='FL-01'", x, z, theta, battery, status, jobId);
  }

  void complete(JobRow job) {
    int changed = jdbc.update("update job set status='COMPLETED',updated_at=now() where id=? and status in ('DISPATCHED','EXECUTING')", job.id());
    if (changed == 0) return;
    jdbc.update("update location set occupied=occupied-1 where id=?", job.source());
    jdbc.update("update location set reserved=reserved-1,occupied=occupied+1 where id=?", job.destination());
    String requestType = jdbc.queryForObject("select request_type from warehouse_request where id=?", String.class, job.requestId());
    if ("OUTBOUND".equals(requestType)) {
      jdbc.update("update load set location_id=?,status='ON_CONVEYOR' where id=?", job.destination(), job.loadId());
      jdbc.update("insert into conveyor_transfer(id,load_id,status,entered_at,exit_due_at) values (?,?, 'MOVING',now(),now()+interval '6 seconds')",
          UUID.randomUUID(), job.loadId());
    } else {
      jdbc.update("update load set location_id=?,status='STORED' where id=?", job.destination(), job.loadId());
    }
    jdbc.update("update agv set status='IDLE',job_id=null where id='FL-01'");
    jdbc.update("update warehouse_request set status='COMPLETED',completed_at=now() where id=? and not exists (select 1 from job where request_id=? and status<>'COMPLETED')", job.requestId(), job.requestId());
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
    jdbc.update("update agv set status=case when ?='PAUSED' then 'PAUSED' when job_id is null then 'IDLE' else 'MOVING' end where id='FL-01'", state);
    return runtime();
  }

  ApiModels.RuntimeView reset() {
    jdbc.update("delete from mqtt_outbox");
    jdbc.update("update agv set job_id=null,status='IDLE',x=17,z=-12,theta=0,battery=82 where id='FL-01'");
    jdbc.update("delete from conveyor_transfer");
    jdbc.update("delete from job");
    jdbc.update("delete from warehouse_request");
    jdbc.update("delete from load");
    jdbc.update("update location set occupied=0,reserved=0");
    jdbc.update("insert into load(id,item,status,location_id,received_at) values ('PALLET-A-001','PALLET-A','INBOUND','INBOUND-01',now()),('PALLET-A-002','PALLET-A','INBOUND','INBOUND-01',now()),('PALLET-A-003','PALLET-A','INBOUND','INBOUND-01',now())");
    jdbc.update("update location set occupied=3 where id='INBOUND-01'");
    String[] skus = {"ELECTRONICS", "AUTOMOTIVE", "MEDICAL", "FOOD-DRY", "TOOLS"};
    var slots = jdbc.queryForList("select id from location where type='STORAGE' order by bay_index,level_index,rack_id limit 40", String.class);
    for (int index = 0; index < slots.size(); index++) {
      String loadId = "SEED-%03d".formatted(index + 1);
      jdbc.update("insert into load(id,item,status,location_id,received_at) values (?,?, 'STORED',?,now())", loadId, skus[index / 8], slots.get(index));
      jdbc.update("update location set occupied=1 where id=?", slots.get(index));
    }
    jdbc.update("update warehouse_runtime set operation_state='RUNNING',simulation_epoch=simulation_epoch+1,changed_at=now() where warehouse_id='linz'");
    return runtime();
  }

  List<JobRow> jobsForRequestRows(UUID requestId) {
    return jdbc.query("select * from job where request_id=? order by sequence_no", (rs, n) -> job(rs), requestId);
  }

  List<ApiModels.JobView> jobsForRequest(UUID requestId) {
    return jdbc.query("select * from job where request_id=? order by sequence_no", (rs, n) -> jobView(rs), requestId);
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
    var stations = jdbc.query("select id,type,x,z,rotation_y,operating_width,operating_depth from location where warehouse_id='linz' and type in ('INBOUND','OUTBOUND') order by id",
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
        (Double) rs.getObject("operating_width"), (Double) rs.getObject("operating_depth"));
  }

  private ApiModels.LoadView loadView(ResultSet rs) throws SQLException {
    return new ApiModels.LoadView(rs.getString("id"), rs.getString("item"), rs.getString("status"), rs.getString("location_id"),
        instant(rs, "received_at"), instant(rs, "shipped_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    var timestamp = rs.getTimestamp(column);
    return timestamp == null ? null : timestamp.toInstant();
  }

  private JobRow job(ResultSet rs) throws SQLException {
    return new JobRow(rs.getObject("id", UUID.class), rs.getObject("request_id", UUID.class), rs.getInt("sequence_no"),
        rs.getString("load_id"), rs.getString("source_location"), rs.getString("destination_location"), rs.getString("status"), readRoute(rs.getString("route_json")));
  }

  private ApiModels.JobView jobView(ResultSet rs) throws SQLException {
    JobRow job = job(rs);
    return new ApiModels.JobView(job.id(), job.requestId(), job.sequence(), job.loadId(), job.source(), job.destination(), job.status(), job.route());
  }

  private String write(Object value) {
    try { return mapper.writeValueAsString(value); } catch (Exception exception) { throw new IllegalStateException(exception); }
  }

  private List<String> readRoute(String json) {
    try { return mapper.readValue(json, new TypeReference<>() {}); } catch (Exception exception) { throw new IllegalStateException(exception); }
  }
}
