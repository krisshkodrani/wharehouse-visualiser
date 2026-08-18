package com.example.warehouse;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;

/** The shared field vocabulary for structured logs.
 *
 * <p>Values go through MDC rather than into the message text because the ECS encoder
 * lifts MDC into the JSON record, which is what makes a log queryable
 * ({@code jq 'select(.event=="DISPATCH_SKIPPED")'}) rather than merely readable. A
 * message that formats its own ids is greppable at best and unparseable at worst.
 *
 * <p>{@link RequestCorrelationFilter} already establishes {@code correlationId} for
 * HTTP work, and this deliberately reuses that key instead of introducing a second
 * one. Correlation used to stop at the servlet boundary, so everything on the MQTT
 * callback thread, the outbox drain and the scheduled loops logged anonymously --
 * which is precisely where the failures that motivated this work lived. Opening a
 * scope in those handlers extends the same identifier across the whole path.
 *
 * <p>Usage keeps the scope tight so a thread pool never leaks context between tasks:
 *
 * <pre>{@code
 * try (var scope = LogContext.of("taskId", taskId).and("vehicleId", agvId).open()) {
 *   log.info("dispatched");
 * }
 * }</pre>
 */
final class LogContext {
  static final String EVENT = "event";
  static final String REASON = "reason";
  static final String CORRELATION_ID = RequestCorrelationFilter.MDC_KEY;
  static final String ORDER_ID = "orderId";
  static final String TASK_ID = "taskId";
  static final String VEHICLE_ID = "vehicleId";
  static final String LOAD_ID = "loadId";
  static final String EPOCH = "epoch";
  static final String SOURCE = "source";

  private final List<String> keys = new ArrayList<>();
  private final List<String> values = new ArrayList<>();

  private LogContext() {}

  static LogContext of(String key, Object value) {
    return new LogContext().and(key, value);
  }

  /** Null values are dropped rather than written as "null": an absent field is a
   * cleaner query than a field whose value is the string null. */
  LogContext and(String key, Object value) {
    if (value == null) return this;
    String text = value instanceof UUID id ? id.toString() : String.valueOf(value);
    if (text.isBlank()) return this;
    keys.add(key);
    values.add(text);
    return this;
  }

  /** Opens the scope, restoring whatever each key held before. Restoring rather than
   * removing matters on pooled threads, where an outer scope may already be active. */
  Scope open() {
    List<String> previous = new ArrayList<>(keys.size());
    for (int index = 0; index < keys.size(); index++) {
      previous.add(MDC.get(keys.get(index)));
      MDC.put(keys.get(index), values.get(index));
    }
    return new Scope(List.copyOf(keys), previous);
  }

  record Scope(List<String> keys, List<String> previous) implements AutoCloseable {
    @Override public void close() {
      for (int index = 0; index < keys.size(); index++) {
        String value = previous.get(index);
        if (value == null) MDC.remove(keys.get(index));
        else MDC.put(keys.get(index), value);
      }
    }
  }
}
