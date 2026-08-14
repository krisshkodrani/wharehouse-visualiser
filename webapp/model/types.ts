export interface InventoryItem {
  item: string;
  quantity: number;
}

export interface RackDefinition {
  id: string;
  name: string;
  position: [number, number, number];
  rotationY?: number;
  bays: number;
  emptySlots?: Array<[number, number]>;
}

export interface StationDefinition {
  id: string;
  type: "INBOUND" | "OUTBOUND";
  position: [number, number, number];
  rotationY: number;
  width: number;
  depth: number;
}

export interface ObstacleDefinition {
  id: string;
  type: "WALL" | "BARRIER";
  position: [number, number, number];
  rotationY: number;
  width: number;
  depth: number;
  height: number;
}

export interface WarehouseVisualConfig {
  id: string;
  signText: string;
  floorColor: string;
  accentColor: string;
  racks: RackDefinition[];
  forkliftStops: [[number, number, number], [number, number, number]];
  stations?: StationDefinition[];
  obstacles?: ObstacleDefinition[];
  inboundCount?: number;
  conveyorCount?: number;
  carriedLoad?: boolean;
}

export interface Warehouse extends WarehouseVisualConfig {
  name: string;
  location: string;
  inventory: InventoryItem[];
}

export interface WarehouseModelData {
  warehouses: Warehouse[];
  selectedWarehouseId: string;
  selectedRackId: string | null;
  selectedRackName: string;
}

export interface ApiRack { id: string; name: string; x: number; z: number; rotationY: number; bays: number; }
export interface ApiLocation { id: string; name: string; type: string; capacity: number; occupied: number; reserved: number; x: number; z: number; rackId?: string; bayIndex?: number; levelIndex?: number; rotationY?: number; operatingWidth?: number; operatingDepth?: number; }
export interface ApiObstacle { id: string; type: "WALL" | "BARRIER"; x: number; z: number; width: number; depth: number; rotationY: number; height: number; }
export interface ApiLoad { id: string; item: string; status: string; locationId: string; receivedAt: string; shippedAt?: string; }
export interface ApiAgv { id: string; x: number; z: number; theta: number; battery: number; status: string; jobId?: string; }
export interface ApiJob { id: string; requestId: string; sequence: number; loadId: string; source: string; destination: string; status: string; route: string[]; }
export interface ApiRuntime { operationState: "RUNNING" | "PAUSED"; simulationEpoch: number; changedAt: string; }
export interface ApiConveyorTransfer { id: string; loadId: string; status: string; enteredAt: string; exitDueAt: string; completedAt?: string; }
export interface WarehouseSnapshot {
  id: string; name: string; width: number; depth: number; racks: ApiRack[]; locations: ApiLocation[]; loads: ApiLoad[]; agvs: ApiAgv[]; jobs: ApiJob[];
  runtime: ApiRuntime; conveyorTransfers: ApiConveyorTransfer[]; obstacles?: ApiObstacle[];
}
export interface WarehouseEvent { eventId: string; type: string; occurredAt: string; warehouseId: string; payload: unknown; }
