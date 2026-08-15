import UIComponent from "sap/ui/core/UIComponent";
import JSONModel from "sap/ui/model/json/JSONModel";
import { warehouseModelData } from "./model/warehouseData";
import { emptyOrderInspection } from "./model/orderInspection";

/** @namespace warehouse.visualizer */
export default class Component extends UIComponent {
  static readonly metadata = { manifest: "json" };

  public init(): void {
    this.log("component", "init started");
    super.init();
    const model = new JSONModel({
      ...structuredClone(warehouseModelData),
      mode: "LIVE",
      connectionStatus: "CONNECTING",
      planningStatus: "READY",
      planningMessage: "",
      operatorPrompt: "Store all selected incoming pallets in the best available locations.",
      inboundLoads: [],
      storedLoads: [],
      receiveSku: "STANDARD-BOX",
      receiveQuantity: 1,
      operationState: "RUNNING",
      timeScale: 2,
      selectedLoadIds: [],
      liveInventory: [],
      jobs: [],
      transportOrders: [],
      tasks: [],
      selectedOrder: null,
      selectedOrderTasks: [],
      selectedVdaDispatches: [],
      selectedVdaPayload: "No VDA order has been published yet.",
      inspection: emptyOrderInspection(),
      activityFilter: "ALL",
      visibleInspectionActivity: [],
      vdaViewMode: "STRUCTURED",
      selectedVdaSequence: null,
      scenario: { configured: false },
      scenarioPresets: [],
      orderType: "PUTAWAY",
      orderPriority: "NORMAL",
      orderObjective: "Move selected loads safely and efficiently.",
      eligibleLoads: [],
      activeOrderCount: 0,
      queuedOrderCount: 0,
      attentionCount: 0,
      activityText: "Waiting for a scenario to start.",
      agv: { id: "FL-01", status: "CONNECTING", battery: 0, velocity: 0, x: 8.2, z: -4.7, charging: false, handlingPhase: "IDLE", forkHeight: 0, forkExtension: 0 },
      warehouseName: "Linz Central Warehouse",
      warehouseLocation: "Industriezeile 44, Linz"
    });
    model.setDefaultBindingMode("TwoWay");
    this.setModel(model, "warehouse");
    this.log("component", "warehouse model installed");
  }

  private log(stage: string, message: string): void {
    (window as Window & { __warehouseDiagnostics?: { info: (stage: string, value: unknown) => void } })
      .__warehouseDiagnostics?.info(stage, message);
  }
}
