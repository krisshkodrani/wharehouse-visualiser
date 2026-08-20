package com.example.warehouse;

public class OperationsPausedException extends RuntimeException {
  OperationsPausedException() { super("Warehouse operations are paused"); }
}
