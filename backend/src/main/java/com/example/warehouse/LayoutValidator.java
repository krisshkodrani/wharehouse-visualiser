package com.example.warehouse;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Guards the two layout invariants that migrations kept breaking silently.
 *
 * <p>Station footprints used to overlap because each layout migration added
 * geometry without retiring the previous generation's: OUT-DCK-01 overlapped
 * OUTBOUND-01 by 2.5 m, the robot cell overlapped it by another 2.2 m, and the
 * CHARGE-01 zone completely contained PARK-02 so their floor decals z-fought.
 *
 * <p>Separately, a barrier placed within the forklift clearance envelope of a
 * travel lane silently removes edges from the route graph — the planner just
 * stops returning routes through them, with no error anywhere. That is what
 * forced V17 to delete three robot-cell barriers and V18 to trim a fourth.
 *
 * <p>Both are checked here rather than only in tests so a bad migration is loud
 * at boot instead of appearing as "routing mysteriously stopped working". */
@Component
class LayoutValidator {
  private static final Logger log = LoggerFactory.getLogger(LayoutValidator.class);
  private final WarehouseStore store;

  LayoutValidator(WarehouseStore store) {
    this.store = store;
  }

  @EventListener(ApplicationReadyEvent.class)
  void reportOnStartup() {
    List<String> problems = validate();
    if (problems.isEmpty()) {
      log.info("Warehouse layout validated: no overlapping station footprints, all map edges passable");
      return;
    }
    for (String problem : problems) log.error("Warehouse layout invariant violated: {}", problem);
  }

  List<String> validate() {
    List<String> problems = new ArrayList<>(overlappingFootprints(store.stationFootprints()));
    problems.addAll(impassableEdges());
    return List.copyOf(problems);
  }

  /** Every declared map edge must survive the clearance check. An edge that does
   * not is dead weight in the graph and usually means a barrier is sitting in a
   * travel lane. */
  private List<String> impassableEdges() {
    var nodes = new java.util.HashMap<String, WarehouseStore.NodeRow>();
    store.nodes().forEach(node -> nodes.put(node.id(), node));
    List<WarehouseStore.PhysicalObstacle> obstacles = store.physicalObstacles();
    List<String> problems = new ArrayList<>();
    for (WarehouseStore.EdgeRow edge : store.edges()) {
      WarehouseStore.NodeRow from = nodes.get(edge.from());
      WarehouseStore.NodeRow to = nodes.get(edge.to());
      if (from == null || to == null) {
        problems.add("edge %s references a missing node".formatted(edge.id()));
        continue;
      }
      if (!RoutePlanner.segmentPassable(from, to, obstacles == null ? List.of() : obstacles))
        problems.add("edge %s (%s -> %s) is blocked by an obstacle clearance envelope"
            .formatted(edge.id(), edge.from(), edge.to()));
    }
    return problems;
  }

  /** Axis-aligned overlap test over the operating footprints. Footprints may touch
   * but must not intersect. */
  static List<String> overlappingFootprints(List<WarehouseStore.StationFootprint> stations) {
    List<String> problems = new ArrayList<>();
    for (int outer = 0; outer < stations.size(); outer++) {
      for (int inner = outer + 1; inner < stations.size(); inner++) {
        WarehouseStore.StationFootprint a = stations.get(outer);
        WarehouseStore.StationFootprint b = stations.get(inner);
        double overlapX = Math.min(a.x() + a.width() / 2, b.x() + b.width() / 2)
            - Math.max(a.x() - a.width() / 2, b.x() - b.width() / 2);
        double overlapZ = Math.min(a.z() + a.depth() / 2, b.z() + b.depth() / 2)
            - Math.max(a.z() - a.depth() / 2, b.z() - b.depth() / 2);
        if (overlapX > 1e-6 && overlapZ > 1e-6)
          // Locale.ROOT keeps the decimal separator stable; these strings are
          // diagnostics, not user-facing text.
          problems.add(String.format(java.util.Locale.ROOT, "stations %s and %s overlap by %.2f m x %.2f m",
              a.id(), b.id(), overlapX, overlapZ));
      }
    }
    return problems;
  }
}
