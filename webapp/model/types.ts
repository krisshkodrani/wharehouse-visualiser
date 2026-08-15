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
  loads?: RackLoadDefinition[];
}

export interface RackLoadDefinition { id: string; item: string; status?: string; locationId?: string; bay: number; level: number; }

export interface StationDefinition {
  id: string;
  type: "INBOUND" | "OUTBOUND" | "PARKING_CHARGING";
  position: [number, number, number];
  rotationY: number;
  width: number;
  depth: number;
}

export interface LoadVisualDefinition { id: string; item: string; status?: string; locationId?: string; }

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
  inboundLoads?: LoadVisualDefinition[];
  conveyorCount?: number;
  conveyorLoads?: LoadVisualDefinition[];
  loadDetails?: LoadVisualDefinition[];
  carriedLoadId?: string;
  chargingStationId?: string;
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
export interface ApiLocation { id: string; name: string; type: string; capacity: number; occupied: number; reserved: number; x: number; z: number; rackId?: string; bayIndex?: number; levelIndex?: number; rotationY?: number; operatingWidth?: number; operatingDepth?: number; handlingX?: number; handlingZ?: number; handlingTheta?: number; handlingHeight?: number; }
export interface ApiObstacle { id: string; type: "WALL" | "BARRIER"; x: number; z: number; width: number; depth: number; rotationY: number; height: number; }
export interface ApiLoad { id: string; item: string; status: string; locationId: string; receivedAt: string; shippedAt?: string; }
export interface ApiAgv { id: string; x: number; z: number; theta: number; velocity: number; battery: number; status: string; taskId?: string; charging: boolean; currentStationId?: string; handlingPhase: string; forkHeight: number; forkExtension: number; carriedLoadId?: string; }
export interface ApiJob { id: string; requestId: string; sequence: number; loadId: string; source: string; destination: string; status: string; route: string[]; }
export interface ApiTransportTask { id: string; transportOrderId: string; sequence: number; loadId: string; source: string; destination: string; status: string; route: string[]; assignedAgvId?: string; acceptedAt?: string; startedAt?: string; completedAt?: string; error?: string; }
export interface ApiVdaDispatch { id: string; taskId: string; manufacturer: string; serialNumber: string; orderId: string; orderUpdateId: number; status: string; valid: boolean; validationError?: string; rejectionError?: string; createdAt: string; publishedAt?: string; acceptedAt?: string; finishedAt?: string; payload: string; }
export interface ApiTransportOrder { id: string; type: "PUTAWAY" | "OUTBOUND"; priority: "NORMAL" | "HIGH" | "URGENT"; status: string; objective?: string; scenarioId?: string; error?: string; createdAt: string; completedAt?: string; tasks: ApiTransportTask[]; vdaDispatches: ApiVdaDispatch[]; }
export interface ApiScenario { id?: string; name?: string; configured: boolean; }
export interface ScenarioPreset { id: string; name: string; description: string; storedLoads: number; inboundLoads: number; orderType: string; orderLoads: number; priority: string; agvBattery: number; }
export interface ApiRuntime { operationState: "RUNNING" | "PAUSED"; simulationEpoch: number; timeScale: 1 | 2 | 4; scenarioId?: string; scenarioConfigured: boolean; changedAt: string; }
export interface ApiConveyorTransfer { id: string; loadId: string; status: string; enteredAt: string; exitDueAt: string; completedAt?: string; }
export interface WarehouseSnapshot {
  id: string; name: string; width: number; depth: number; racks: ApiRack[]; locations: ApiLocation[]; loads: ApiLoad[]; agvs: ApiAgv[]; jobs: ApiJob[]; transportOrders: ApiTransportOrder[]; tasks: ApiTransportTask[]; scenario: ApiScenario;
  runtime: ApiRuntime; conveyorTransfers: ApiConveyorTransfer[]; obstacles?: ApiObstacle[];
}
export interface WarehouseEvent { eventId: string; type: string; occurredAt: string; warehouseId: string; simulationEpoch: number; payload: unknown; }
