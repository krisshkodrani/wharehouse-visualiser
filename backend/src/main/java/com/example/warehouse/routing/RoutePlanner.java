package com.example.warehouse.routing;

import com.example.warehouse.WarehouseStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class RoutePlanner {
  private static final double FORKLIFT_CLEARANCE = .72;
  private static final double COLLISION_SAMPLE_STEP = .2;
  private final WarehouseStore store;
  private final AStarRoutePlanner search = new AStarRoutePlanner();
  public RoutePlanner(WarehouseStore store) { this.store = store; }

  public List<String> route(String sourceLocation, String destinationLocation) {
    return routeNodes(store.nodeForLocation(sourceLocation), store.nodeForLocation(destinationLocation));
  }

  public List<String> routeFromAgv(String destinationLocation) {
    return routeNodes(store.nearestNodeToAgv().id(), store.nodeForLocation(destinationLocation));
  }

  public List<String> routeFromAgv(String destinationLocation, String agvId) {
    return routeNodes(store.nearestNodeToAgv(agvId).id(), store.nodeForLocation(destinationLocation));
  }

  public List<String> routeFromAgvToNode(String destinationNode) {
    return routeNodes(store.nearestNodeToAgv().id(), destinationNode);
  }

  private List<String> routeNodes(String start, String goal) {
    Map<String, WarehouseStore.NodeRow> nodes = new HashMap<>();
    store.nodes().forEach(node -> nodes.put(node.id(), node));
    List<WarehouseStore.PhysicalObstacle> loadedObstacles = store.physicalObstacles();
    List<WarehouseStore.PhysicalObstacle> obstacles = loadedObstacles == null ? List.of() : loadedObstacles;
    Graph graph = new Graph(nodes.values().stream()
        .map(node -> new Graph.Node(node.id(), node.x(), node.z())).toList());
    store.edges().forEach(edge -> {
      WarehouseStore.NodeRow from = nodes.get(edge.from());
      WarehouseStore.NodeRow to = nodes.get(edge.to());
      if (from == null || to == null || !segmentPassable(from, to, obstacles)) return;
      // Keeping traversal cost at least as large as physical distance makes the
      // Euclidean A* heuristic admissible while preserving any configured penalty.
      double traversalCost = Math.max(edge.cost(), Math.hypot(from.x() - to.x(), from.z() - to.z()));
      graph.connect(edge.from(), edge.to(), traversalCost);
      if (edge.bidirectional()) graph.connect(edge.to(), edge.from(), traversalCost);
    });
    return search.route(graph, start, goal);
  }

  /** Package-private so {@link LayoutValidator} can assert every declared map edge
   * survives the same clearance check the planner applies. */
  static boolean segmentPassable(WarehouseStore.NodeRow from, WarehouseStore.NodeRow to,
      List<WarehouseStore.PhysicalObstacle> obstacles) {
    double distance = Math.hypot(to.x() - from.x(), to.z() - from.z());
    int samples = Math.max(1, (int) Math.ceil(distance / COLLISION_SAMPLE_STEP));
    for (int sample = 0; sample <= samples; sample++) {
      double progress = (double) sample / samples;
      double x = from.x() + (to.x() - from.x()) * progress;
      double z = from.z() + (to.z() - from.z()) * progress;
      for (WarehouseStore.PhysicalObstacle obstacle : obstacles) {
        double cos = Math.cos(obstacle.rotationY());
        double sin = Math.sin(obstacle.rotationY());
        double dx = x - obstacle.x();
        double dz = z - obstacle.z();
        double localX = dx * cos - dz * sin;
        double localZ = dx * sin + dz * cos;
        if (Math.abs(localX) < obstacle.halfWidth() + FORKLIFT_CLEARANCE
            && Math.abs(localZ) < obstacle.halfDepth() + FORKLIFT_CLEARANCE) return false;
      }
    }
    return true;
  }

}
