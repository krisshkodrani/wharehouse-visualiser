package com.example.warehouse.idempotency;

import com.example.warehouse.ApiModels;
import com.example.warehouse.WarehouseStore;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotencyService {
  private static final String CREATE_ORDER = "create-transport-order";
  private final JdbcClient jdbc;
  private final ObjectMapper mapper;
  private final WarehouseStore store;

  public IdempotencyService(JdbcClient jdbc, ObjectMapper mapper, WarehouseStore store) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.store = store;
  }

  @Transactional
  public ApiModels.TransportOrderView createTransportOrder(String key, ApiModels.TransportOrderRequest request,
      Supplier<ApiModels.TransportOrderView> command) {
    if (key == null || key.isBlank()) return command.get();
    String normalizedKey = key.trim();
    if (normalizedKey.length() > 200) throw new IllegalArgumentException("Idempotency-Key must not exceed 200 characters");

    String hash = hash(request);
    jdbc.sql("select pg_advisory_xact_lock(hashtext(:lockKey))")
        .param("lockKey", CREATE_ORDER + ":" + normalizedKey).query().listOfRows();
    var existing = jdbc.sql("select request_hash, resource_id from api_idempotency_key where scope=:scope and idempotency_key=:key")
        .param("scope", CREATE_ORDER).param("key", normalizedKey)
        .query((rs, row) -> new Entry(rs.getString("request_hash"), rs.getObject("resource_id", UUID.class))).optional();
    if (existing.isPresent()) {
      if (!existing.get().requestHash().equals(hash)) throw new IdempotencyConflictException();
      return store.transportOrder(existing.get().resourceId())
          .orElseThrow(() -> new IllegalStateException("Idempotency record references a missing transport order"));
    }

    ApiModels.TransportOrderView created = command.get();
    jdbc.sql("insert into api_idempotency_key(scope,idempotency_key,request_hash,resource_id) values(:scope,:key,:hash,:resourceId)")
        .param("scope", CREATE_ORDER).param("key", normalizedKey).param("hash", hash)
        .param("resourceId", created.id()).update();
    return created;
  }

  private String hash(ApiModels.TransportOrderRequest request) {
    try {
      byte[] canonical = mapper.writeValueAsString(request).getBytes(StandardCharsets.UTF_8);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
    } catch (Exception exception) {
      throw new IllegalStateException("Could not fingerprint transport-order request", exception);
    }
  }

  private record Entry(String requestHash, UUID resourceId) {}
}
