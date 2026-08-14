package com.example.warehouse;

class OperationsPausedException extends RuntimeException {
  OperationsPausedException() { super("Warehouse operations are paused"); }
}
