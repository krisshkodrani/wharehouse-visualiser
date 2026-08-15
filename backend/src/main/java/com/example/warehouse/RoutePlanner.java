package com.example.warehouse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
class RoutePlanner {
  private static final double FORKLIFT_CLEARANCE = .72;
  private static final double COLLISION_SAMPLE_STEP = .2;
  private final WarehouseStore store;
  RoutePlanner(WarehouseStore store) { this.store = store; }

  List<String> route(String sourceLocation, String destinationLocation) {
    return routeNodes(store.nodeForLocation(sourceLocation), store.nodeForLocation(destinationLocation));
  }

  List<String> routeFromAgv(String destinationLocation) {
    return routeNodes(store.nearestNodeToAgv().id(), store.nodeForLocation(destinationLocation));
  }

  List<String> routeFromAgvToNode(String destinationNode) {
    return routeNodes(store.nearestNodeToAgv().id(), destinationNode);
  }

  private List<String> routeNodes(String start, String goal) {
    Map<String, WarehouseStore.NodeRow> nodes = new HashMap<>();
    store.nodes().forEach(node -> nodes.put(node.id(), node));
    if (!nodes.containsKey(start) || !nodes.containsKey(goal))
      throw new IllegalStateException("Route endpoint is missing from the warehouse map");
    List<WarehouseStore.PhysicalObstacle> loadedObstacles = store.physicalObstacles();
    List<WarehouseStore.PhysicalObstacle> obstacles = loadedObstacles == null ? List.of() : loadedObstacles;
    Map<String, List<Step>> graph = new HashMap<>();
    store.edges().forEach(edge -> {
      WarehouseStore.NodeRow from = nodes.get(edge.from());
      WarehouseStore.NodeRow to = nodes.get(edge.to());
      if (from == null || to == null || !segmentPassable(from, to, obstacles)) return;
      // Keeping traversal cost at least as large as physical distance makes the
      // Euclidean A* heuristic admissible while preserving any configured penalty.
      double traversalCost = Math.max(edge.cost(), heuristic(from, to));
      graph.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(new Step(edge.to(), traversalCost));
      if (edge.bidirectional()) graph.computeIfAbsent(edge.to(), ignored -> new ArrayList<>()).add(new Step(edge.from(), traversalCost));
    });
    Map<String, Double> score = new HashMap<>();
    Map<String, String> previous = new HashMap<>();
    PriorityQueue<Visit> open = new PriorityQueue<>(Comparator.comparingDouble(Visit::estimate).thenComparing(Visit::id));
    Set<String> closed = new HashSet<>();
    score.put(start, 0d);
    open.add(new Visit(start, heuristic(nodes.get(start), nodes.get(goal))));
    while (!open.isEmpty()) {
      String current = open.remove().id();
      if (current.equals(goal)) return reconstruct(previous, current);
      if (!closed.add(current)) continue;
      for (Step step : graph.getOrDefault(current, List.of())) {
        double tentative = score.get(current) + step.cost();
        if (tentative < score.getOrDefault(step.to(), Double.POSITIVE_INFINITY)) {
          score.put(step.to(), tentative);
          previous.put(step.to(), current);
          open.add(new Visit(step.to(), tentative + heuristic(nodes.get(step.to()), nodes.get(goal))));
        }
      }
    }
    throw new IllegalStateException("No route from node " + start + " to node " + goal);
  }

  private static double heuristic(WarehouseStore.NodeRow a, WarehouseStore.NodeRow b) {
    return Math.hypot(a.x() - b.x(), a.z() - b.z());
  }

  private static boolean segmentPassable(WarehouseStore.NodeRow from, WarehouseStore.NodeRow to,
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

  private static List<String> reconstruct(Map<String, String> previous, String current) {
    ArrayList<String> route = new ArrayList<>();
    route.add(current);
    while (previous.containsKey(current)) { current = previous.get(current); route.add(current); }
    java.util.Collections.reverse(route);
    return List.copyOf(route);
  }

  private record Step(String to, double cost) {}
  private record Visit(String id, double estimate) {}
}
