package com.example.warehouse.routing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Immutable routing graph independent of persistence and warehouse domain records. */
public final class Graph {
  public record Node(String id, double x, double z) {}
  public record Edge(String to, double cost) {}

  private final Map<String, Node> nodes;
  private final Map<String, List<Edge>> adjacency;

  public Graph(List<Node> nodes) {
    Map<String, Node> indexed = new HashMap<>();
    nodes.forEach(node -> indexed.put(node.id(), node));
    this.nodes = Map.copyOf(indexed);
    this.adjacency = new HashMap<>();
  }

  public boolean contains(String id) {
    return nodes.containsKey(id);
  }

  public Node node(String id) {
    return nodes.get(id);
  }

  public List<Edge> edgesFrom(String id) {
    return adjacency.getOrDefault(id, List.of());
  }

  public void connect(String from, String to, double cost) {
    adjacency.computeIfAbsent(from, ignored -> new ArrayList<>()).add(new Edge(to, cost));
  }
}
