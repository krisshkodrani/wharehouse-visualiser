package com.example.warehouse.idempotency;

public class IdempotencyConflictException extends RuntimeException {
  public IdempotencyConflictException() {
    super("The Idempotency-Key was already used with a different request body");
  }
}
