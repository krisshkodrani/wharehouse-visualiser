package com.example.warehouse;

class IdempotencyConflictException extends RuntimeException {
  IdempotencyConflictException() {
    super("The Idempotency-Key was already used with a different request body");
  }
}
