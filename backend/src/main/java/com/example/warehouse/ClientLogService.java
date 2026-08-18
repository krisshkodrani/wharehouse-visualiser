package com.example.warehouse;

import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Re-logs diagnostics the browser buffered, so the operator's view and the server's
 * view end up in one queryable stream.
 *
 * <p>The two halves of this system fail in ways only the other can see. The backend
 * knows a task was dispatched but not that the pallet vanished from the shelf for
 * five seconds; the browser knows the pallet vanished but not that the vehicle was
 * holding a stale task id. Correlating them previously meant reading a browser panel
 * and a container log side by side and matching wall-clock times by eye.
 *
 * <p>This endpoint is unauthenticated, like the rest of the demo API, so it is
 * deliberately hostile to misuse: batches and fields are truncated rather than
 * rejected, and nothing the page sends can widen a log line without bound.
 */
@Service
class ClientLogService {
  private static final Logger log = LoggerFactory.getLogger("browser");

  /** Caps chosen so a runaway page costs a bounded amount of disk per request rather
   * than an unbounded one. Truncating beats rejecting: a partial record from a page
   * that is misbehaving is usually the record that explains why. */
  static final int MAX_ENTRIES = 200;
  static final int MAX_FIELD = 512;

  void record(List<ApiModels.ClientLogEntry> entries) {
    if (entries == null) return;
    for (ApiModels.ClientLogEntry entry : entries.stream().limit(MAX_ENTRIES).toList()) {
      try (var scope = LogContext.of(LogContext.SOURCE, "browser")
          .and(LogContext.EVENT, clip(entry.event()))
          .and(LogContext.CORRELATION_ID, clip(entry.correlationId()))
          .and(LogContext.ORDER_ID, clip(entry.orderId()))
          .and(LogContext.TASK_ID, clip(entry.taskId()))
          .and(LogContext.VEHICLE_ID, clip(entry.vehicleId()))
          .and(LogContext.LOAD_ID, clip(entry.loadId()))
          .open()) {
        String message = clip(entry.message());
        switch (level(entry.level())) {
          case "ERROR" -> log.error("{}", message);
          case "WARN" -> log.warn("{}", message);
          default -> log.info("{}", message);
        }
      }
    }
  }

  /** Only three levels are honoured. A page that invents its own does not get to
   * choose how loud it is in the server's log. */
  private static String level(String value) {
    if (value == null) return "INFO";
    String upper = value.toUpperCase(Locale.ROOT);
    return switch (upper) {
      case "ERROR", "WARN" -> upper;
      default -> "INFO";
    };
  }

  private static String clip(String value) {
    if (value == null) return null;
    return value.length() <= MAX_FIELD ? value : value.substring(0, MAX_FIELD) + "…";
  }
}
