import Controller from "sap/ui/core/mvc/Controller";
import JSONModel from "sap/ui/model/json/JSONModel";
import MessageToast from "sap/m/MessageToast";
import MessageBox from "sap/m/MessageBox";
import MultiComboBox from "sap/m/MultiComboBox";
import SegmentedButton from "sap/m/SegmentedButton";
import Dialog from "sap/m/Dialog";
import type Event from "sap/ui/base/Event";
import WarehouseViewport from "../control/WarehouseViewport";
import WarehouseApi from "../model/WarehouseApi";
import type { ApiAgv, ApiTransportOrder, WarehouseEvent, WarehouseModelData, WarehouseSnapshot, WarehouseVisualConfig } from "../model/types";
import { projectVisualConfig } from "../model/warehouseState";
import { mergeAgvEvent } from "../model/agvState";
import { buildOrderInspection, filterInspectionActivity } from "../model/orderInspection";
import type { InspectionActivityFilter, OrderInspection, TaskInspection, VdaNavigatorItem, VdaSequenceRow } from "../model/orderInspection";

/** @namespace warehouse.visualizer.controller */
export default class MainController extends Controller {
  private readonly api = new WarehouseApi();
  private sceneConfigured = false;
  private sceneSignature = "";
  private initialized = false;
  private refreshTimer?: number;
  private readonly loadStatuses = new Map<string, string>();
  private readonly jobStatuses = new Map<string, string>();
  private simulationEpoch = 0;

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

  public async onSpeedChange(event: Event): Promise<void> {
    const multiplier = Number((event.getSource() as SegmentedButton).getSelectedKey()) as 1 | 2 | 4;
    try {
      await this.api.setSpeed(multiplier);
      this.model().setProperty("/timeScale", multiplier);
      MessageToast.show(`Simulation speed set to ${multiplier}x.`);
    } catch (error) { MessageBox.error(error instanceof Error ? error.message : String(error)); }
  }

  public onResetSimulation(): void {
    MessageBox.confirm("Clear the current scenario and return to the startup dialogue?", {
      title: "Reset scenario",
      emphasizedAction: MessageBox.Action.OK,
      actions: [MessageBox.Action.OK, MessageBox.Action.CANCEL],
      onClose: (action: string | null) => { if (action === MessageBox.Action.OK) void this.performReset(); }
    });
  }

  private async performReset(): Promise<void> {
    try {
      const snapshot = await this.api.resetScenario();
      this.applySnapshot(snapshot);
      this.seedDialog()?.open();
      MessageToast.show("Scenario cleared. Choose the next warehouse story.");
    } catch (error) { MessageBox.error(error instanceof Error ? error.message : String(error)); }
  }

  public onStartBalanced(): void { void this.startScenario("balanced-shift"); }
  public onStartInbound(): void { void this.startScenario("inbound-surge"); }
  public onStartOutbound(): void { void this.startScenario("outbound-wave"); }

  private async startScenario(presetId: string): Promise<void> {
    const dialog = this.seedDialog();
    dialog?.setBusy(true);
    try {
      const snapshot = await this.api.seedScenario(presetId);
      this.applySnapshot(snapshot);
      dialog?.close();
      MessageToast.show(`${snapshot.scenario.name ?? "Scenario"} started. The first task is being dispatched.`);
    } catch (error) { MessageBox.error(error instanceof Error ? error.message : String(error)); }
    finally { dialog?.setBusy(false); }
  }

  public onOpenTransportOrder(): void {
    this.updateEligibleLoads();
    (this.byId("transportOrderDialog") as Dialog | undefined)?.open();
  }

  public onCloseTransportOrder(): void { (this.byId("transportOrderDialog") as Dialog | undefined)?.close(); }

  public onOrderTypeChange(): void {
    this.model().setProperty("/selectedLoadIds", []);
    this.updateEligibleLoads();
  }

  public async onCreateTransportOrder(): Promise<void> {
    const model = this.model();
    const type = String(model.getProperty("/orderType")) as "PUTAWAY" | "OUTBOUND";
    const priority = String(model.getProperty("/orderPriority")) as "NORMAL" | "HIGH" | "URGENT";
    const selector = this.byId("transportOrderLoads") as MultiComboBox;
    const loadIds = selector.getSelectedKeys();
    if (loadIds.length === 0) { MessageToast.show("Select at least one eligible load."); return; }
    const dialog = this.byId("transportOrderDialog") as Dialog;
    dialog.setBusy(true);
    try {
      const order = await this.api.createTransportOrder(type, priority, loadIds, String(model.getProperty("/orderObjective") || ""));
      dialog.close();
      MessageToast.show(`Transport order ${order.id.slice(0, 8)} created and queued for auto-dispatch.`);
      await this.loadSnapshot();
    } catch (error) { MessageBox.error(error instanceof Error ? error.message : String(error)); }
    finally { dialog.setBusy(false); }
  }

  public onOpenReceive(): void { (this.byId("receiveDialog") as Dialog | undefined)?.open(); }
  public onCloseReceive(): void { (this.byId("receiveDialog") as Dialog | undefined)?.close(); }

  public async onReceiveFromDialog(): Promise<void> {
    await this.onReceive();
    (this.byId("receiveDialog") as Dialog | undefined)?.close();
  }

  public onOrderPress(event: Event): void {
    const parameters = event.getParameters() as { listItem?: { getBindingContext: (name: string) => { getObject: () => ApiTransportOrder } | null } };
    const order = parameters.listItem?.getBindingContext("warehouse")?.getObject();
    if (order) this.selectOrder(order);
  }

  public onTaskPress(event: Event): void {
    const task = this.bindingObject<TaskInspection>(event, "listItem");
    if (task) this.updateInspection(task.id, undefined, true);
  }

  public onInspectTaskVda(event: Event): void {
    const task = this.bindingObject<TaskInspection>(event, "source");
    if (!task) return;
    this.updateInspection(task.id, undefined, true);
    this.openVdaInspector();
  }

  public onOpenVdaInspector(): void {
    const inspection = this.model().getProperty("/inspection") as OrderInspection;
    if (!inspection.selectedTaskId && inspection.tasks[0]) this.updateInspection(inspection.tasks[0].id, undefined, true);
    this.openVdaInspector();
  }

  public onCloseVdaInspector(): void { (this.byId("vdaInspectorDialog") as Dialog | undefined)?.close(); }

  public onVdaNavigatorPress(event: Event): void {
    const item = this.bindingObject<VdaNavigatorItem>(event, "listItem");
    if (item?.kind === "DISPATCH" && item.dispatchId) this.updateInspection(item.taskId, item.dispatchId, false);
  }

  public onJumpToLatestVda(): void {
    const inspection = this.model().getProperty("/inspection") as OrderInspection;
    this.updateInspection(inspection.selectedTaskId, undefined, true);
  }

  public onVdaSequencePress(event: Event): void {
    const row = this.bindingObject<VdaSequenceRow>(event, "listItem");
    if (row) this.model().setProperty("/selectedVdaSequence", row);
  }

  public onVdaViewModeChange(event: Event): void {
    this.model().setProperty("/vdaViewMode", (event.getSource() as SegmentedButton).getSelectedKey());
  }

  public onActivityFilterChange(event: Event): void {
    const filter = (event.getSource() as SegmentedButton).getSelectedKey() as InspectionActivityFilter;
    const inspection = this.model().getProperty("/inspection") as OrderInspection;
    this.model().setProperty("/activityFilter", filter);
    this.model().setProperty("/visibleInspectionActivity", filterInspectionActivity(inspection.activity, filter));
  }

  public async onCopyVdaJson(): Promise<void> {
    const raw = (this.model().getProperty("/inspection/selectedDispatch/rawJson") as string | undefined) ?? "";
    if (!raw) return;
    try { await navigator.clipboard.writeText(raw); MessageToast.show("VDA payload copied to clipboard."); }
    catch { MessageBox.error("The browser did not allow clipboard access."); }
  }

  public onDownloadVdaJson(): void {
    const inspection = this.model().getProperty("/inspection") as OrderInspection;
    const dispatch = inspection.selectedDispatch;
    if (!dispatch) return;
    const link = document.createElement("a");
    link.href = URL.createObjectURL(new Blob([dispatch.rawJson], { type: "application/json" }));
    link.download = `vda-order-${dispatch.orderId}-update-${dispatch.orderUpdateId}.json`;
    link.click();
    URL.revokeObjectURL(link.href);
  }

  public async onCancelSelectedOrder(): Promise<void> {
    const order = this.model().getProperty("/selectedOrder") as ApiTransportOrder | null;
    if (!order || ["COMPLETED", "CANCELLED"].includes(order.status)) return;
    try { await this.api.cancelTransportOrder(order.id); await this.loadSnapshot(); MessageToast.show("Transport order cancelled through VDA instant actions."); }
    catch (error) { MessageBox.error(error instanceof Error ? error.message : String(error)); }
  }

  public async onDemoReject(): Promise<void> { await this.runDemoEvent("VDA_REJECTION"); }
  public async onDemoBlock(): Promise<void> { await this.runDemoEvent("BLOCK_ROUTE"); }

  public onDemoMenuItem(event: Event): void {
    const parameters = event.getParameters() as { item?: { getKey: () => string } };
    if (parameters.item?.getKey() === "reject") void this.onDemoReject();
    if (parameters.item?.getKey() === "block") void this.onDemoBlock();
  }

  private async runDemoEvent(type: "VDA_REJECTION" | "BLOCK_ROUTE"): Promise<void> {
    const order = this.model().getProperty("/selectedOrder") as ApiTransportOrder | null;
    const taskId = order?.tasks.find((task) => ["DISPATCHED", "ACCEPTED", "EXECUTING"].includes(task.status))?.id;
    try { await this.api.demoEvent(type, taskId); MessageToast.show(type === "VDA_REJECTION" ? "VDA rejection injected." : "Route blockage injected."); }
    catch (error) { MessageBox.error(error instanceof Error ? error.message : String(error)); }
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
    const shouldResetPose = !this.sceneConfigured || snapshot.runtime.simulationEpoch !== this.simulationEpoch;
    this.trackAnimationState(snapshot);
    const model = this.model();
    const snapshotAgv = snapshot.agvs[0];
    const agv = snapshotAgv
      ? mergeAgvEvent(model.getProperty("/agv") as ApiAgv | undefined, snapshotAgv, shouldResetPose)
      : undefined;
    this.simulationEpoch = snapshot.runtime.simulationEpoch;
    const inbound = snapshot.loads.filter((load) => load.status === "INBOUND");
    model.setProperty("/warehouseName", snapshot.name);
    model.setProperty("/inboundLoads", inbound);
    model.setProperty("/storedLoads", snapshot.loads.filter((load) => load.status === "STORED"));
    model.setProperty("/operationState", snapshot.runtime.operationState);
    model.setProperty("/timeScale", snapshot.runtime.timeScale);
    model.setProperty("/selectedLoadIds", inbound.map((load) => load.id));
    model.setProperty("/liveInventory", snapshot.loads);
    model.setProperty("/jobs", snapshot.jobs);
    model.setProperty("/tasks", snapshot.tasks);
    const orders = snapshot.transportOrders.map((order) => ({
      ...order,
      shortId: order.id.slice(0, 8).toUpperCase(),
      displayType: order.type === "PUTAWAY" ? "Put-away" : "Outbound",
      completedTasks: order.tasks.filter((task) => task.status === "COMPLETED").length,
      progress: order.tasks.length ? Math.round(order.tasks.filter((task) => task.status === "COMPLETED").length / order.tasks.length * 100) : 0,
      assignedAgv: order.tasks.find((task) => task.assignedAgvId)?.assignedAgvId ?? "Awaiting AGV"
    }));
    model.setProperty("/transportOrders", orders);
    model.setProperty("/scenario", snapshot.scenario);
    model.setProperty("/activeOrderCount", orders.filter((order) => order.status === "IN_PROGRESS").length);
    model.setProperty("/queuedOrderCount", orders.filter((order) => ["PLANNING", "READY"].includes(order.status)).length);
    model.setProperty("/attentionCount", orders.filter((order) => ["FAILED", "REJECTED"].includes(order.status)).length);
    const selectedId = (model.getProperty("/selectedOrder") as ApiTransportOrder | null)?.id;
    const selected = orders.find((order) => order.id === selectedId) ?? orders[0];
    if (selected) this.selectOrder(selected);
    else this.selectOrder(null);
    model.setProperty("/agv", snapshot.agvs[0] || {});
    model.setProperty("/activityText", this.activityText(snapshot));
    this.updateEligibleLoads();
    const selector = this.byId("inboundLoads") as MultiComboBox | undefined;
    selector?.setSelectedKeys(inbound.map((load) => load.id));
    const occupied = new Set(snapshot.loads
      .filter((load) => ["STORED", "OUTBOUND_QUEUED"].includes(load.status))
      .map((load) => load.locationId));
    const stations = snapshot.locations.filter((location) => location.type !== "STORAGE");
    const inboundStation = stations.find((station) => ["REC-STG-01", "RECEIVING_STAGING"].includes(station.canonicalId ?? station.type))
      ?? stations.find((station) => station.type === "INBOUND");
    const outboundStation = stations.find((station) => ["OUT-STG-01", "OUTBOUND_STAGING"].includes(station.canonicalId ?? station.type))
      ?? stations.find((station) => station.type === "OUTBOUND");
    const conveyorLoads = [
      ...snapshot.loads.filter((load) => load.status === "ON_CONVEYOR")
        .map((load) => ({ id: load.id, item: load.item, status: load.status, locationId: load.locationId })),
      ...(snapshot.cartons ?? []).filter((carton) => carton.status === "ON_CONVEYOR")
        .map((carton) => ({ id: carton.id, item: carton.sku, status: carton.status, locationId: carton.locationId }))
    ];
    const visual: WarehouseVisualConfig = {
        id: snapshot.id,
        signText: "LINZ AI LOGISTICS",
        floorColor: "#dce8e5",
        accentColor: "#0a6ed1",
        racks: snapshot.racks.map((rack) => {
          const slots = snapshot.locations.filter((location) => location.rackId === rack.id);
          const slotById = new Map(slots.map((slot) => [slot.id, slot]));
          const loads = snapshot.loads.filter((load) => ["STORED", "OUTBOUND_QUEUED"].includes(load.status) && slotById.has(load.locationId)).map((load) => {
            const slot = slotById.get(load.locationId)!;
            return { id: load.id, item: load.item, status: load.status, locationId: load.locationId, bay: slot.bayIndex ?? 0, level: slot.levelIndex ?? 0 };
          });
          return {
            id: rack.id, canonicalId: rack.canonicalId, name: rack.name, position: [rack.x, 0, rack.z] as [number, number, number], rotationY: rack.rotationY, bays: rack.bays,
            loads,
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
          canonicalId: station.canonicalId,
          type: station.type,
          position: [station.x, 0, station.z],
          rotationY: station.rotationY ?? 0,
          width: station.operatingWidth ?? 7,
          depth: station.operatingDepth ?? 7
        })),
        obstacles: (snapshot.obstacles ?? []).map((obstacle) => ({
          id: obstacle.id, type: obstacle.type, position: [obstacle.x, 0, obstacle.z], rotationY: obstacle.rotationY,
          width: obstacle.width, depth: obstacle.depth, height: obstacle.height
        })),
        cartons: (snapshot.cartons ?? []).map((carton) => ({
          id: carton.id, palletId: carton.palletId, sku: carton.sku, status: carton.status, locationId: carton.locationId
        })),
        robotCells: (snapshot.robotCells ?? []).map((cell) => ({ id: cell.id, phase: cell.phase, activePickJobId: cell.activePickJobId })),
        conveyorTransfers: (snapshot.conveyorTransfers ?? []).map((transfer) => ({
          id: transfer.id, cartonId: transfer.cartonId, loadId: transfer.loadId, status: transfer.status, conveyorId: transfer.conveyorId
        })),
        fleetAgvs: snapshot.agvs,
        inboundCount: inbound.length,
        inboundLoads: inbound.map((load) => ({ id: load.id, item: load.item, status: load.status, locationId: load.locationId })),
        conveyorCount: conveyorLoads.length,
        conveyorLoads,
        loadDetails: snapshot.loads.map((load) => ({ id: load.id, item: load.item, status: load.status, locationId: load.locationId })),
        carriedLoadId: agv?.carriedLoadId,
        chargingStationId: agv?.charging ? agv.currentStationId : undefined
      };
    const signature = JSON.stringify({
      ...visual,
      inboundCount: undefined,
      inboundLoads: undefined,
      conveyorCount: undefined,
      conveyorLoads: undefined,
      loadDetails: undefined,
      carriedLoadId: undefined,
      chargingStationId: undefined,
      fleetAgvs: undefined,
      racks: visual.racks.map(({ emptySlots: _emptySlots, ...rack }) => rack)
    });
    if (!this.sceneConfigured || signature !== this.sceneSignature) {
      this.viewport()?.setWarehouse(visual);
      this.sceneConfigured = true;
      this.sceneSignature = signature;
    } else {
      this.viewport()?.updateOperations(visual);
    }
    if (agv) {
      if (shouldResetPose) this.applyAgv(agv);
      else this.applyAgvOperations(agv);
    }
    if (!snapshot.scenario.configured) window.setTimeout(() => this.seedDialog()?.open(), 0);
  }

  private onWarehouseEvent(event: WarehouseEvent): void {
    if (this.model().getProperty("/mode") === "SANDBOX") return;
    if (event.simulationEpoch < this.simulationEpoch) return;
    if (event.type === "AGV_POSE" || event.type === "AGV_UPDATED" || event.type === "AGV_HANDLING_UPDATED") {
      const carriesLivePose = event.type === "AGV_POSE";
      const current = this.model().getProperty("/agv") as ApiAgv | undefined;
      const agv = mergeAgvEvent(current, event.payload as ApiAgv, carriesLivePose);
      this.model().setProperty("/agv", agv);
      if (carriesLivePose) this.applyAgv(agv);
      else this.applyAgvOperations(agv);
      this.model().setProperty("/activityText", this.activityText(undefined, agv));
      return;
    }
    if (event.type === "VDA_REJECTION" || event.type === "BLOCK_ROUTE") {
      const payload = event.payload as { message?: string };
      this.model().setProperty("/activityText", payload.message ?? event.type);
      this.model().setProperty("/attentionCount", 1);
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
    if (this.model().getProperty("/mode") !== "SANDBOX") this.viewport()?.setAgvState(agv);
  }

  private applyAgvOperations(agv: ApiAgv): void {
    if (this.model().getProperty("/mode") !== "SANDBOX") this.viewport()?.setAgvOperations(agv);
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

  private seedDialog(): Dialog | undefined { return this.byId("seedDialog") as Dialog | undefined; }

  private selectOrder(order: ApiTransportOrder | null): void {
    const model = this.model();
    const previous = model.getProperty("/inspection") as OrderInspection | undefined;
    const preserveSelection = Boolean(order && previous?.orderId === order.id);
    const inspection = buildOrderInspection(order,
      preserveSelection ? previous?.selectedTaskId : undefined,
      preserveSelection ? previous?.selectedDispatchId : undefined,
      preserveSelection ? previous?.followLatest : true);
    model.setProperty("/selectedOrder", order);
    model.setProperty("/selectedOrderTasks", inspection.tasks);
    model.setProperty("/selectedVdaDispatches", order?.vdaDispatches ?? []);
    model.setProperty("/inspection", inspection);
    model.setProperty("/selectedVdaPayload", inspection.selectedDispatch?.rawJson ?? "No VDA order has been published yet.");
    model.setProperty("/selectedVdaSequence", inspection.selectedDispatch?.sequence[0] ?? null);
    const filter = model.getProperty("/activityFilter") as InspectionActivityFilter;
    model.setProperty("/visibleInspectionActivity", filterInspectionActivity(inspection.activity, filter));
  }

  private updateInspection(taskId?: string, dispatchId?: string, followLatest = true): void {
    const model = this.model();
    const order = model.getProperty("/selectedOrder") as ApiTransportOrder | null;
    const inspection = buildOrderInspection(order, taskId, dispatchId, followLatest);
    model.setProperty("/inspection", inspection);
    model.setProperty("/selectedOrderTasks", inspection.tasks);
    model.setProperty("/selectedVdaPayload", inspection.selectedDispatch?.rawJson ?? "No VDA order has been published yet.");
    model.setProperty("/selectedVdaSequence", inspection.selectedDispatch?.sequence[0] ?? null);
    const filter = model.getProperty("/activityFilter") as InspectionActivityFilter;
    model.setProperty("/visibleInspectionActivity", filterInspectionActivity(inspection.activity, filter));
  }

  private openVdaInspector(): void { (this.byId("vdaInspectorDialog") as Dialog | undefined)?.open(); }

  private bindingObject<T>(event: Event, parameter: "listItem" | "source"): T | undefined {
    const source = parameter === "source" ? event.getSource() : (event.getParameters() as { listItem?: unknown }).listItem;
    const control = source as { getBindingContext?: (name: string) => { getObject: () => T } | null } | undefined;
    return control?.getBindingContext?.("warehouse")?.getObject();
  }

  private updateEligibleLoads(): void {
    const model = this.model();
    const type = String(model.getProperty("/orderType"));
    model.setProperty("/eligibleLoads", type === "OUTBOUND" ? model.getProperty("/storedLoads") : model.getProperty("/inboundLoads"));
  }

  private activityText(snapshot?: WarehouseSnapshot, agvOverride?: ApiAgv): string {
    const agv = agvOverride ?? snapshot?.agvs[0] ?? this.model().getProperty("/agv") as ApiAgv;
    if (!agv?.id) return "Waiting for AGV telemetry.";
    if (agv.handlingPhase && !["IDLE", "COMPLETE"].includes(agv.handlingPhase)) return `${agv.id} · ${agv.handlingPhase.replaceAll("_", " ").toLowerCase()}${agv.carriedLoadId ? ` · ${agv.carriedLoadId}` : ""}`;
    if (agv.status === "MOVING") return `${agv.id} is executing a transport task at ${agv.velocity.toFixed(1)} m/s.`;
    if (agv.charging) return `${agv.id} is charging at ${Math.round(agv.battery)}%.`;
    return `${agv.id} is ${agv.status.toLowerCase()} and ready for the next task.`;
  }

  private model(): JSONModel {
    const model = this.getOwnerComponent()?.getModel("warehouse");
    if (!(model instanceof JSONModel)) throw new Error("Warehouse JSONModel is not available");
    return model;
  }

  private log(stage: string, value: unknown): void {
    (window as Window & { __warehouseDiagnostics?: { info: (stage: string, value: unknown) => void } }).__warehouseDiagnostics?.info(stage, value);
  }
}
