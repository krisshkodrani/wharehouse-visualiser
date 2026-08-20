package com.example.warehouse.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** Deterministic A* search over a domain-neutral graph. */
public final class AStarRoutePlanner {
  public List<String> route(Graph graph, String start, String goal) {
    if (!graph.contains(start) || !graph.contains(goal))
      throw new IllegalStateException("Route endpoint is missing from the warehouse map");
    Map<String, Double> score = new HashMap<>();
    Map<String, String> previous = new HashMap<>();
    PriorityQueue<Visit> open =
        new PriorityQueue<>(Comparator.comparingDouble(Visit::estimate).thenComparing(Visit::id));
    Set<String> closed = new HashSet<>();
    score.put(start, 0d);
    open.add(new Visit(start, heuristic(graph.node(start), graph.node(goal))));
    while (!open.isEmpty()) {
      String current = open.remove().id();
      if (current.equals(goal)) return reconstruct(previous, current);
      if (!closed.add(current)) continue;
      for (Graph.Edge edge : graph.edgesFrom(current)) {
        double tentative = score.get(current) + edge.cost();
        if (tentative < score.getOrDefault(edge.to(), Double.POSITIVE_INFINITY)) {
          score.put(edge.to(), tentative);
          previous.put(edge.to(), current);
          open.add(new Visit(edge.to(),
              tentative + heuristic(graph.node(edge.to()), graph.node(goal))));
        }
      }
    }
    throw new IllegalStateException("No route from node " + start + " to node " + goal);
  }

  public static double heuristic(Graph.Node a, Graph.Node b) {
    return Math.hypot(a.x() - b.x(), a.z() - b.z());
  }

  private static List<String> reconstruct(Map<String, String> previous, String current) {
    ArrayList<String> route = new ArrayList<>();
    route.add(current);
    while (previous.containsKey(current)) {
      current = previous.get(current);
      route.add(current);
    }
    java.util.Collections.reverse(route);
    return List.copyOf(route);
  }

  private record Visit(String id, double estimate) {}
}
