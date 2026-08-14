import Controller from "sap/ui/core/mvc/Controller";
import JSONModel from "sap/ui/model/json/JSONModel";
import MessageToast from "sap/m/MessageToast";
import MessageBox from "sap/m/MessageBox";
import MultiComboBox from "sap/m/MultiComboBox";
import SegmentedButton from "sap/m/SegmentedButton";
import type Event from "sap/ui/base/Event";
import WarehouseViewport from "../control/WarehouseViewport";
import WarehouseApi from "../model/WarehouseApi";
import type { ApiAgv, WarehouseEvent, WarehouseModelData, WarehouseSnapshot, WarehouseVisualConfig } from "../model/types";
import { projectVisualConfig } from "../model/warehouseState";

/** @namespace warehouse.visualizer.controller */
export default class MainController extends Controller {
  private readonly api = new WarehouseApi();
  private sceneConfigured = false;
  private sceneSignature = "";
  private initialized = false;
  private refreshTimer?: number;
  private readonly loadStatuses = new Map<string, string>();
  private readonly jobStatuses = new Map<string, string>();

  public onInit(): void {
    this.getView()?.addEventDelegate({ onAfterRendering: () => this.initialize() });
  }

  public onExit(): void {
    this.api.disconnect();
    if (this.refreshTimer) window.clearTimeout(this.refreshTimer);
  }

  public async onPutaway(): Promise<void> {
    const selected = (this.byId("inboundLoads") as MultiComboBox).getSelectedKeys();
    if (selected.length === 0) {
      MessageToast.show("Select at least one inbound pallet.");
      return;
    }
    const model = this.model();
    model.setProperty("/planningStatus", "PLANNING");
    model.setProperty("/planningMessage", "Requesting and validating a placement plan…");
    try {
      const accepted = await this.api.submitPutaway(selected, String(model.getProperty("/operatorPrompt") || ""));
      model.setProperty("/planningMessage", `Planning request ${accepted.requestId.slice(0, 8)} accepted`);
      this.scheduleRefresh(800);
    } catch (error) {
      model.setProperty("/planningStatus", "REJECTED");
      model.setProperty("/planningMessage", error instanceof Error ? error.message : String(error));
    }
  }

  public async onReceive(): Promise<void> {
    const model = this.model();
    const sku = String(model.getProperty("/receiveSku") || "").trim();
    const quantity = Number(model.getProperty("/receiveQuantity") || 0);
    if (!sku || !Number.isInteger(quantity) || quantity < 1) { MessageToast.show("Enter a material / SKU and a whole-number quantity."); return; }
    try {
      await this.api.receive(sku, quantity);
      MessageToast.show(`${quantity} box${quantity === 1 ? "" : "es"} received in inbound staging.`);
      await this.loadSnapshot();
    } catch (error) { MessageBox.error(error instanceof Error ? error.message : String(error)); }
  }

  public async onOutbound(): Promise<void> {
    const selector = this.byId("outboundLoads") as MultiComboBox;
    const selected = selector.getSelectedKeys();
    if (selected.length === 0) { MessageToast.show("Select at least one stored box."); return; }
    try {
      await this.api.outbound(selected);
      selector.setSelectedKeys([]);
      MessageToast.show(`${selected.length} box${selected.length === 1 ? "" : "es"} queued for shipping.`);
      await this.loadSnapshot();
    } catch (error) { MessageBox.error(error instanceof Error ? error.message : String(error)); }
  }

  public async onToggleOperations(): Promise<void> {
    const paused = this.model().getProperty("/operationState") === "PAUSED";
    try {
      await this.api.operation(paused ? "resume" : "pause");
      await this.loadSnapshot();
      MessageToast.show(paused ? "Warehouse operations resumed." : "Warehouse operations stopped safely.");
    } catch (error) { MessageBox.error(error instanceof Error ? error.message : String(error)); }
  }

  public onResetSimulation(): void {
    MessageBox.confirm("Reset all jobs and shipment history to the seeded 43-box warehouse baseline?", {
      title: "Reset simulation",
      emphasizedAction: MessageBox.Action.OK,
      actions: [MessageBox.Action.OK, MessageBox.Action.CANCEL],
      onClose: (action: string | null) => { if (action === MessageBox.Action.OK) void this.performReset(); }
    });
  }

  private async performReset(): Promise<void> {
    try {
      await this.api.operation("reset");
      await this.loadSnapshot();
      MessageToast.show("Simulation reset to the demo baseline.");
    } catch (error) { MessageBox.error(error instanceof Error ? error.message : String(error)); }
  }

  public onModeChange(event: Event): void {
    const key = (event.getSource() as SegmentedButton).getSelectedKey();
    const sandbox = key === "SANDBOX";
    this.model().setProperty("/mode", key);
    this.viewport()?.setSandboxMode(sandbox);
    if (!sandbox) void this.loadSnapshot();
  }

  public onMoveForklift(): void { this.viewport()?.moveForklift(); }

  public onRackSelected(event: Event): void {
    const parameters = event.getParameters() as { rackId?: string; rackName?: string };
    this.model().setProperty("/selectedRackId", parameters.rackId || null);
    this.model().setProperty("/selectedRackName", parameters.rackName || "");
  }

  private initialize(): void {
    if (this.initialized) return;
    this.initialized = true;
    if (!this.sceneConfigured) {
      const data = this.model().getData() as WarehouseModelData;
      const fallback = data.warehouses[0];
      if (fallback) this.viewport()?.setWarehouse(projectVisualConfig(fallback));
    }
    (window as Window & { warehouseVisualizerReady?: () => void }).warehouseVisualizerReady?.();
    void this.loadSnapshot();
    this.api.connect((event) => this.onWarehouseEvent(event), (status) => this.model().setProperty("/connectionStatus", status));
  }

  private async loadSnapshot(): Promise<void> {
    try {
      const snapshot = await this.api.snapshot();
      this.applySnapshot(snapshot);
      this.model().setProperty("/connectionStatus", "CONNECTED");
    } catch (error) {
      this.model().setProperty("/connectionStatus", "OFFLINE DEMO");
      this.log("api", error);
    }
  }

  private applySnapshot(snapshot: WarehouseSnapshot): void {
    this.trackAnimationState(snapshot);
    const model = this.model();
    const inbound = snapshot.loads.filter((load) => load.status === "INBOUND");
    model.setProperty("/warehouseName", snapshot.name);
    model.setProperty("/inboundLoads", inbound);
    model.setProperty("/storedLoads", snapshot.loads.filter((load) => load.status === "STORED"));
    model.setProperty("/operationState", snapshot.runtime.operationState);
    model.setProperty("/selectedLoadIds", inbound.map((load) => load.id));
    model.setProperty("/liveInventory", snapshot.loads);
    model.setProperty("/jobs", snapshot.jobs);
    model.setProperty("/agv", snapshot.agvs[0] || {});
    const selector = this.byId("inboundLoads") as MultiComboBox | undefined;
    selector?.setSelectedKeys(inbound.map((load) => load.id));
    const occupied = new Set(snapshot.loads
      .filter((load) => ["STORED", "OUTBOUND_QUEUED"].includes(load.status))
      .map((load) => load.locationId));
    const stations = snapshot.locations.filter((location) => location.type === "INBOUND" || location.type === "OUTBOUND");
    const inboundStation = stations.find((station) => station.type === "INBOUND");
    const outboundStation = stations.find((station) => station.type === "OUTBOUND");
    const visual: WarehouseVisualConfig = {
        id: snapshot.id,
        signText: "LINZ AI LOGISTICS",
        floorColor: "#dce8e5",
        accentColor: "#0a6ed1",
        racks: snapshot.racks.map((rack) => {
          const slots = snapshot.locations.filter((location) => location.rackId === rack.id);
          return {
            id: rack.id, name: rack.name, position: [rack.x, 0, rack.z] as [number, number, number], rotationY: rack.rotationY, bays: rack.bays,
            emptySlots: slots.length > 0
              ? slots.filter((location) => !occupied.has(location.id)).map((location) => [location.bayIndex ?? 0, location.levelIndex ?? 0] as [number, number])
              : Array.from({ length: rack.bays * 3 }, (_, index) => [index % rack.bays, Math.floor(index / rack.bays)] as [number, number])
          };
        }),
        forkliftStops: [
          [inboundStation?.x ?? 17, 0, inboundStation?.z ?? -12],
          [outboundStation?.x ?? -17, 0, outboundStation?.z ?? 13]
        ],
        stations: stations.map((station) => ({
          id: station.id,
          type: station.type as "INBOUND" | "OUTBOUND",
          position: [station.x, 0, station.z],
          rotationY: station.rotationY ?? 0,
          width: station.operatingWidth ?? 7,
          depth: station.operatingDepth ?? 7
        })),
        obstacles: (snapshot.obstacles ?? []).map((obstacle) => ({
          id: obstacle.id, type: obstacle.type, position: [obstacle.x, 0, obstacle.z], rotationY: obstacle.rotationY,
          width: obstacle.width, depth: obstacle.depth, height: obstacle.height
        })),
        inboundCount: inbound.length,
        conveyorCount: snapshot.loads.filter((load) => load.status === "ON_CONVEYOR").length,
        carriedLoad: snapshot.loads.some((load) => load.status === "IN_TRANSIT")
      };
    const signature = JSON.stringify({
      ...visual,
      inboundCount: undefined,
      conveyorCount: undefined,
      carriedLoad: undefined,
      racks: visual.racks.map(({ emptySlots: _emptySlots, ...rack }) => rack)
    });
    if (!this.sceneConfigured || signature !== this.sceneSignature) {
      this.viewport()?.setWarehouse(visual);
      this.sceneConfigured = true;
      this.sceneSignature = signature;
    } else {
      this.viewport()?.updateOperations(visual);
    }
    const agv = snapshot.agvs[0];
    if (agv) this.applyAgv(agv);
  }

  private onWarehouseEvent(event: WarehouseEvent): void {
    if (this.model().getProperty("/mode") === "SANDBOX") return;
    if (event.type === "AGV_UPDATED") {
      const agv = event.payload as ApiAgv;
      this.model().setProperty("/agv", agv);
      this.applyAgv(agv);
      return;
    }
    if (event.type === "PUTAWAY_REJECTED") {
      const payload = event.payload as { error?: string };
      this.model().setProperty("/planningStatus", "REJECTED");
      this.model().setProperty("/planningMessage", payload.error || "Placement was rejected");
    } else if (event.type === "PUTAWAY_PLANNED") {
      this.model().setProperty("/planningStatus", "VALIDATED");
      this.model().setProperty("/planningMessage", "Placement validated; jobs dispatched automatically");
    }
    this.scheduleRefresh(100);
  }

  private applyAgv(agv: ApiAgv): void {
    if (this.model().getProperty("/mode") !== "SANDBOX") this.viewport()?.setAgvPose(agv.x, agv.z, agv.theta);
  }

  private trackAnimationState(snapshot: WarehouseSnapshot): void {
    for (const load of snapshot.loads) {
      const previous = this.loadStatuses.get(load.id);
      if (previous !== load.status) this.animation("LOAD_STATUS", { loadId: load.id, from: previous ?? "UNSEEN", to: load.status, locationId: load.locationId });
      this.loadStatuses.set(load.id, load.status);
    }
    for (const job of snapshot.jobs) {
      const previous = this.jobStatuses.get(job.id);
      if (previous !== job.status) this.animation("JOB_STATUS", {
        jobId: job.id, loadId: job.loadId, from: previous ?? "UNSEEN", to: job.status, source: job.source, destination: job.destination, route: job.route
      });
      this.jobStatuses.set(job.id, job.status);
    }
  }

  private animation(event: string, payload: Record<string, unknown>): void {
    (window as Window & { __warehouseRecordAnimation?: (event: string, payload: Record<string, unknown>) => void })
      .__warehouseRecordAnimation?.(event, payload);
  }

  private scheduleRefresh(delay: number): void {
    if (this.refreshTimer) window.clearTimeout(this.refreshTimer);
    this.refreshTimer = window.setTimeout(() => void this.loadSnapshot(), delay);
  }

  private viewport(): WarehouseViewport | undefined { return this.byId("viewport") as WarehouseViewport | undefined; }

  private model(): JSONModel {
    const model = this.getOwnerComponent()?.getModel("warehouse");
    if (!(model instanceof JSONModel)) throw new Error("Warehouse JSONModel is not available");
    return model;
  }

  private log(stage: string, value: unknown): void {
    (window as Window & { __warehouseDiagnostics?: { info: (stage: string, value: unknown) => void } }).__warehouseDiagnostics?.info(stage, value);
  }
}
