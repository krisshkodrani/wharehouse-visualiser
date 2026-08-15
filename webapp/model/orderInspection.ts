import type { ApiTransportOrder, ApiTransportTask, ApiVdaDispatch } from "./types";

export type InspectionActivityFilter = "ALL" | "TASK" | "VDA" | "EXCEPTION";

interface VdaActionParameter { key?: string; value?: unknown; }
interface VdaAction { actionType?: string; actionId?: string; actionParameters?: VdaActionParameter[]; }
interface VdaNodePosition { x?: number; y?: number; mapId?: string; }
interface VdaNode { nodeId?: string; sequenceId?: number; released?: boolean; nodePosition?: VdaNodePosition; actions?: VdaAction[]; }
interface VdaEdge { edgeId?: string; sequenceId?: number; released?: boolean; maximumSpeed?: number; actions?: VdaAction[]; }
interface VdaPayload {
  headerId?: number;
  timestamp?: string;
  version?: string;
  manufacturer?: string;
  serialNumber?: string;
  orderId?: string;
  orderUpdateId?: number;
  nodes?: VdaNode[];
  edges?: VdaEdge[];
}

export interface VdaSequenceRow {
  key: string;
  kind: "NODE" | "EDGE";
  id: string;
  sequenceId: number;
  released: boolean;
  releaseLabel: string;
  position: string;
  actionSummary: string;
  actionDetails: string;
  speed: string;
}

export interface DispatchInspection extends ApiVdaDispatch {
  label: string;
  shortOrderId: string;
  timeLabel: string;
  validityLabel: string;
  statusState: "Success" | "Error" | "Information" | "None";
  rawJson: string;
  parseError?: string;
  parsedPayload?: VdaPayload;
  sequence: VdaSequenceRow[];
  nodeCount: number;
  edgeCount: number;
  releasedNodeCount: number;
  horizonNodeCount: number;
  releasedPercent: number;
  diffItems: string[];
  hasDiff: boolean;
}

export interface TaskInspection extends ApiTransportTask {
  label: string;
  routeLabel: string;
  routeText: string;
  vehicleLabel: string;
  statusState: "Success" | "Error" | "Information" | "Warning" | "None";
  updateCount: number;
  updates: DispatchInspection[];
  acceptedLabel: string;
  startedLabel: string;
  completedLabel: string;
  selected: boolean;
}

export interface InspectionActivity {
  id: string;
  category: Exclude<InspectionActivityFilter, "ALL">;
  title: string;
  description: string;
  timestamp: string;
  timeLabel: string;
  icon: string;
  state: "Success" | "Error" | "Information" | "Warning" | "None";
}

export interface VdaNavigatorItem {
  key: string;
  kind: "TASK" | "DISPATCH";
  taskId: string;
  dispatchId?: string;
  title: string;
  description: string;
  info: string;
  infoState: "Success" | "Error" | "Information" | "None";
  selected: boolean;
  enabled: boolean;
}

export interface OrderInspection {
  orderId: string;
  selectedTaskId?: string;
  selectedDispatchId?: string;
  selectedTask?: TaskInspection;
  selectedDispatch?: DispatchInspection;
  tasks: TaskInspection[];
  navigatorItems: VdaNavigatorItem[];
  activity: InspectionActivity[];
  completedTasks: number;
  progress: number;
  assignedAgv: string;
  protocolUpdateCount: number;
  validUpdateCount: number;
  invalidUpdateCount: number;
  protocolHealthLabel: string;
  protocolHealthState: "Success" | "Error" | "Information" | "None";
  followLatest: boolean;
}

export function emptyOrderInspection(): OrderInspection {
  return {
    orderId: "", tasks: [], navigatorItems: [], activity: [], completedTasks: 0, progress: 0,
    assignedAgv: "Awaiting AGV", protocolUpdateCount: 0, validUpdateCount: 0, invalidUpdateCount: 0,
    protocolHealthLabel: "No VDA messages", protocolHealthState: "None", followLatest: true
  };
}

export function buildOrderInspection(
  order: ApiTransportOrder | null,
  selectedTaskId?: string,
  selectedDispatchId?: string,
  followLatest = true
): OrderInspection {
  if (!order) return emptyOrderInspection();
  const dispatchByTask = new Map<string, ApiVdaDispatch[]>();
  for (const dispatch of order.vdaDispatches) {
    const values = dispatchByTask.get(dispatch.taskId) ?? [];
    values.push(dispatch);
    dispatchByTask.set(dispatch.taskId, values);
  }
  const tasks = [...order.tasks].sort((a, b) => a.sequence - b.sequence).map((task) => {
    const rawUpdates = [...(dispatchByTask.get(task.id) ?? [])].sort((a, b) => b.orderUpdateId - a.orderUpdateId || b.createdAt.localeCompare(a.createdAt));
    const updates = rawUpdates.map((dispatch, index) => inspectDispatch(dispatch, rawUpdates[index + 1]));
    return inspectTask(task, updates, false);
  });
  const defaultTask = tasks.find((task) => ["DISPATCHED", "ACCEPTED", "EXECUTING"].includes(task.status))
    ?? tasks.find((task) => !["COMPLETED", "CANCELLED", "FAILED"].includes(task.status))
    ?? tasks[0];
  const selectedTask = tasks.find((task) => task.id === selectedTaskId) ?? defaultTask;
  for (const task of tasks) task.selected = task.id === selectedTask?.id;
  const selectedDispatch = followLatest
    ? selectedTask?.updates[0]
    : selectedTask?.updates.find((dispatch) => dispatch.id === selectedDispatchId) ?? selectedTask?.updates[0];
  const activity = buildActivity(order, tasks);
  const navigatorItems = buildNavigator(tasks, selectedDispatch?.id);
  const validUpdateCount = order.vdaDispatches.filter((dispatch) => dispatch.valid).length;
  const invalidUpdateCount = order.vdaDispatches.length - validUpdateCount;
  const completedTasks = tasks.filter((task) => task.status === "COMPLETED").length;
  return {
    orderId: order.id,
    selectedTaskId: selectedTask?.id,
    selectedDispatchId: selectedDispatch?.id,
    selectedTask,
    selectedDispatch,
    tasks,
    navigatorItems,
    activity,
    completedTasks,
    progress: tasks.length ? Math.round(completedTasks / tasks.length * 100) : 0,
    assignedAgv: tasks.find((task) => task.assignedAgvId)?.assignedAgvId ?? "Awaiting AGV",
    protocolUpdateCount: order.vdaDispatches.length,
    validUpdateCount,
    invalidUpdateCount,
    protocolHealthLabel: invalidUpdateCount ? `${invalidUpdateCount} invalid update${invalidUpdateCount === 1 ? "" : "s"}`
      : order.vdaDispatches.length ? `${order.vdaDispatches.length} schema-valid update${order.vdaDispatches.length === 1 ? "" : "s"}` : "No VDA messages",
    protocolHealthState: invalidUpdateCount ? "Error" : order.vdaDispatches.length ? "Success" : "None",
    followLatest
  };
}

export function filterInspectionActivity(activity: InspectionActivity[], filter: InspectionActivityFilter): InspectionActivity[] {
  return filter === "ALL" ? activity : activity.filter((entry) => entry.category === filter);
}

function inspectTask(task: ApiTransportTask, updates: DispatchInspection[], selected: boolean): TaskInspection {
  return {
    ...task,
    label: `Task ${task.sequence}`,
    routeLabel: `${task.source} → ${task.destination}`,
    routeText: task.route.join("  →  "),
    vehicleLabel: task.assignedAgvId ?? "Awaiting assignment",
    statusState: statusState(task.status),
    updateCount: updates.length,
    updates,
    acceptedLabel: formatTime(task.acceptedAt),
    startedLabel: formatTime(task.startedAt),
    completedLabel: formatTime(task.completedAt),
    selected
  };
}

function inspectDispatch(dispatch: ApiVdaDispatch, previous?: ApiVdaDispatch): DispatchInspection {
  let payload: VdaPayload | undefined;
  let parseError: string | undefined;
  let rawJson = dispatch.payload;
  try {
    payload = JSON.parse(dispatch.payload) as VdaPayload;
    rawJson = JSON.stringify(payload, null, 2);
  } catch (error) {
    parseError = error instanceof Error ? error.message : String(error);
  }
  const nodes = payload?.nodes ?? [];
  const edges = payload?.edges ?? [];
  const sequence = [
    ...nodes.map(nodeRow),
    ...edges.map(edgeRow)
  ].sort((a, b) => a.sequenceId - b.sequenceId);
  const diffItems = semanticDiff(payload, parsePayload(previous?.payload));
  const releasedNodeCount = nodes.filter((node) => node.released).length;
  return {
    ...dispatch,
    label: `Update ${dispatch.orderUpdateId}`,
    shortOrderId: dispatch.orderId.slice(0, 12),
    timeLabel: formatTime(dispatch.publishedAt ?? dispatch.createdAt),
    validityLabel: dispatch.valid ? "Schema valid" : "Invalid",
    statusState: !dispatch.valid || dispatch.rejectionError ? "Error" : dispatch.status === "FINISHED" ? "Success" : "Information",
    rawJson,
    parseError,
    parsedPayload: payload,
    sequence,
    nodeCount: nodes.length,
    edgeCount: edges.length,
    releasedNodeCount,
    horizonNodeCount: nodes.length - releasedNodeCount,
    releasedPercent: nodes.length ? Math.round(releasedNodeCount / nodes.length * 100) : 0,
    diffItems,
    hasDiff: diffItems.length > 0
  };
}

function nodeRow(node: VdaNode): VdaSequenceRow {
  return {
    key: `node-${node.sequenceId ?? 0}`,
    kind: "NODE",
    id: node.nodeId ?? "Unnamed node",
    sequenceId: node.sequenceId ?? 0,
    released: Boolean(node.released),
    releaseLabel: node.released ? "Base" : "Horizon",
    position: node.nodePosition ? `x ${number(node.nodePosition.x)} · y ${number(node.nodePosition.y)} · ${node.nodePosition.mapId ?? "map"}` : "—",
    actionSummary: actionSummary(node.actions),
    actionDetails: actionDetails(node.actions),
    speed: "—"
  };
}

function edgeRow(edge: VdaEdge): VdaSequenceRow {
  return {
    key: `edge-${edge.sequenceId ?? 0}`,
    kind: "EDGE",
    id: edge.edgeId ?? "Unnamed edge",
    sequenceId: edge.sequenceId ?? 0,
    released: Boolean(edge.released),
    releaseLabel: edge.released ? "Base" : "Horizon",
    position: "Connects adjacent nodes",
    actionSummary: actionSummary(edge.actions),
    actionDetails: actionDetails(edge.actions),
    speed: edge.maximumSpeed == null ? "—" : `${edge.maximumSpeed.toFixed(1)} m/s`
  };
}

function actionSummary(actions?: VdaAction[]): string {
  return actions?.length ? actions.map((action) => action.actionType ?? "action").join(", ") : "No actions";
}

function actionDetails(actions?: VdaAction[]): string {
  if (!actions?.length) return "This sequence element has no actions.";
  return actions.map((action) => {
    const parameters = action.actionParameters?.map((parameter) => `${parameter.key}: ${String(parameter.value)}`).join(" · ");
    return `${action.actionType ?? "action"}${parameters ? ` — ${parameters}` : ""}`;
  }).join("\n");
}

function semanticDiff(current?: VdaPayload, previous?: VdaPayload): string[] {
  if (!current || !previous) return [];
  const items: string[] = [];
  const previousNodes = new Map((previous.nodes ?? []).map((node) => [node.nodeId, node]));
  const previousEdges = new Map((previous.edges ?? []).map((edge) => [edge.edgeId, edge]));
  const newlyReleasedNodes = (current.nodes ?? []).filter((node) => node.released && !previousNodes.get(node.nodeId)?.released).map((node) => node.nodeId);
  const newlyReleasedEdges = (current.edges ?? []).filter((edge) => edge.released && !previousEdges.get(edge.edgeId)?.released).map((edge) => edge.edgeId);
  if (newlyReleasedNodes.length) items.push(`Released nodes: ${newlyReleasedNodes.join(", ")}`);
  if (newlyReleasedEdges.length) items.push(`Released edges: ${newlyReleasedEdges.join(", ")}`);
  const currentActionCount = countActions(current);
  const previousActionCount = countActions(previous);
  if (currentActionCount !== previousActionCount) items.push(`Actions changed from ${previousActionCount} to ${currentActionCount}`);
  if ((current.nodes?.length ?? 0) !== (previous.nodes?.length ?? 0)) items.push(`Node count changed from ${previous.nodes?.length ?? 0} to ${current.nodes?.length ?? 0}`);
  return items;
}

function countActions(payload: VdaPayload): number {
  return [...(payload.nodes ?? []), ...(payload.edges ?? [])].reduce((sum, element) => sum + (element.actions?.length ?? 0), 0);
}

function parsePayload(value?: string): VdaPayload | undefined {
  if (!value) return undefined;
  try { return JSON.parse(value) as VdaPayload; } catch { return undefined; }
}

function buildNavigator(tasks: TaskInspection[], selectedDispatchId?: string): VdaNavigatorItem[] {
  return tasks.flatMap((task) => [
    {
      key: `task-${task.id}`, kind: "TASK" as const, taskId: task.id, title: `${task.label} · ${task.loadId}`,
      description: task.routeLabel, info: task.status, infoState: task.statusState === "Warning" ? "Information" : task.statusState,
      selected: false, enabled: false
    },
    ...task.updates.map((dispatch) => ({
      key: dispatch.id, kind: "DISPATCH" as const, taskId: task.id, dispatchId: dispatch.id, title: dispatch.label,
      description: `${dispatch.timeLabel} · ${dispatch.status}`, info: dispatch.validityLabel, infoState: dispatch.statusState,
      selected: dispatch.id === selectedDispatchId, enabled: true
    }))
  ]);
}

function buildActivity(order: ApiTransportOrder, tasks: TaskInspection[]): InspectionActivity[] {
  const activity: InspectionActivity[] = [];
  addActivity(activity, `${order.id}-created`, "TASK", "Transport order created", `${order.type} · ${order.priority} priority`, order.createdAt, "sap-icon://create", "Information");
  for (const task of tasks) {
    addActivity(activity, `${task.id}-accepted`, "TASK", `${task.label} accepted`, `${task.loadId} assigned to ${task.vehicleLabel}`, task.acceptedAt, "sap-icon://accept", "Information");
    addActivity(activity, `${task.id}-started`, "TASK", `${task.label} started`, task.routeLabel, task.startedAt, "sap-icon://process", "Information");
    addActivity(activity, `${task.id}-completed`, "TASK", `${task.label} completed`, `${task.loadId} arrived at ${task.destination}`, task.completedAt, "sap-icon://complete", "Success");
    if (task.error) addActivity(activity, `${task.id}-error`, "EXCEPTION", `${task.label} failed`, task.error, task.completedAt ?? task.startedAt ?? order.createdAt, "sap-icon://error", "Error");
    for (const dispatch of task.updates) {
      const category = dispatch.valid && !dispatch.rejectionError ? "VDA" : "EXCEPTION";
      const description = dispatch.rejectionError ?? dispatch.validationError ?? `${dispatch.status} · ${dispatch.nodeCount} nodes · ${dispatch.edgeCount} edges`;
      addActivity(activity, dispatch.id, category, `${task.label} · VDA ${dispatch.label.toLowerCase()}`, description,
        dispatch.publishedAt ?? dispatch.createdAt, category === "EXCEPTION" ? "sap-icon://error" : "sap-icon://source-code", dispatch.statusState);
    }
  }
  if (order.completedAt) addActivity(activity, `${order.id}-completed`, "TASK", "Transport order completed", `${tasks.length} task${tasks.length === 1 ? "" : "s"} finished`, order.completedAt, "sap-icon://complete", "Success");
  return activity.sort((a, b) => b.timestamp.localeCompare(a.timestamp));
}

function addActivity(activity: InspectionActivity[], id: string, category: InspectionActivity["category"], title: string,
    description: string, timestamp: string | undefined, icon: string, state: InspectionActivity["state"]): void {
  if (!timestamp) return;
  activity.push({ id, category, title, description, timestamp, timeLabel: formatTime(timestamp), icon, state });
}

function statusState(status: string): TaskInspection["statusState"] {
  if (status === "COMPLETED") return "Success";
  if (["FAILED", "REJECTED", "CANCELLED"].includes(status)) return "Error";
  if (["DISPATCHED", "ACCEPTED", "EXECUTING"].includes(status)) return "Information";
  if (["PLANNING", "QUEUED", "READY"].includes(status)) return "Warning";
  return "None";
}

function formatTime(value?: string): string {
  if (!value) return "Not yet";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat(undefined, { hour: "2-digit", minute: "2-digit", second: "2-digit" }).format(date);
}

function number(value?: number): string { return value == null ? "—" : value.toFixed(1); }
