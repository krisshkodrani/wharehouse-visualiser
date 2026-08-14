import type { Warehouse, WarehouseModelData, WarehouseVisualConfig } from "./types";

export function getWarehouseIndex(data: WarehouseModelData, id: string): number {
  return data.warehouses.findIndex((warehouse) => warehouse.id === id);
}

export function getWarehouse(data: WarehouseModelData, id: string): Warehouse | undefined {
  return data.warehouses[getWarehouseIndex(data, id)];
}

export function projectVisualConfig(warehouse: Warehouse): WarehouseVisualConfig {
  const { id, signText, floorColor, accentColor, racks, forkliftStops } = warehouse;
  return { id, signText, floorColor, accentColor, racks, forkliftStops };
}

export function selectWarehouse(data: WarehouseModelData, id: string): boolean {
  if (!getWarehouse(data, id)) {
    return false;
  }
  data.selectedWarehouseId = id;
  data.selectedRackId = null;
  data.selectedRackName = "";
  return true;
}

export function selectRack(data: WarehouseModelData, rackId: string): boolean {
  const warehouse = getWarehouse(data, data.selectedWarehouseId);
  const rack = warehouse?.racks.find((candidate) => candidate.id === rackId);
  if (!rack) {
    return false;
  }
  data.selectedRackId = rack.id;
  data.selectedRackName = rack.name;
  return true;
}
