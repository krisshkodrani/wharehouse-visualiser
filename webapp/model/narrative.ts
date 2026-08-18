import type { ApiAgv, ApiTransportOrder, ApiTransportTask, ApiVdaDispatch } from "./types";

/**
 * Story-view narration. Turns the authoritative order, task, and vehicle state into one
 * plain-English sentence plus a six-step progress strip, so a viewer can answer "what is
 * happening right now?" without reading the dense engineer panels.
 *
 * Pure and derived: every value here already exists in the model, so the narration cannot
 * disagree with the panels it summarises.
 */

export type PipelineStage = "ORDER" | "TASK" | "ROUTE" | "VDA" | "AGV" | "DONE";
export type StepState = "DONE" | "CURRENT" | "PENDING";

/** Ordered once, so the strip and the stage comparison cannot drift apart. */
export const PIPELINE_STAGES: readonly PipelineStage[] = ["ORDER", "TASK", "ROUTE", "VDA", "AGV", "DONE"];

const STAGE_LABELS: Record<PipelineStage, string> = {
  ORDER: "Order", TASK: "Task", ROUTE: "Route", VDA: "VDA", AGV: "AGV", DONE: "Done"
};

/** Task statuses that mean a vehicle is actively working the task. */
const LIVE_TASK_STATUSES = ["ASSIGNED", "DISPATCHED", "ACCEPTED", "EXECUTING"];
const FAILED_STATUSES = ["FAILED", "REJECTED", "CANCELLED"];

/** Handling phases the vehicle reports, phrased for a non-technical viewer. */
const HANDLING_VERBS: Record<string, string> = {
  ALIGNING: "is lining up",
  APPROACHING: "is approaching",
  LIFTING: "is lifting",
  RAISING: "is raising the forks",
  LOWERING: "is setting down",
  PICKING: "is picking up",
  DROPPING: "is dropping off",
  DOCKING: "is docking",
  CHARGING: "is charging"
};

export interface PipelineStep {
  key: PipelineStage;
  label: string;
  state: StepState;
}

export interface Narrative {
  /** One sentence answering "what is happening right now?". */
  sentence: string;
  stage: PipelineStage;
  steps: PipelineStep[];
  /** Quiet protocol evidence: order id, schema version, validity, latest update. */
  proofLine: string;
  /** True when the narration is reporting a failure rather than progress. */
  exception: boolean;
}

/** Location ids read as machine identifiers; soften them without losing the id. */
function place(locationId: string | undefined): string {
  if (!locationId) return "the next location";
  return locationId.replace(/-0*(\d+)$/, " $1").replace(/_/g, " ");
}

function shortId(order: ApiTransportOrder): string {
  return `TO-${order.id.slice(0, 8).toUpperCase()}`;
}

/** The task the narration should describe: live work first, then unfinished, then the last. */
export function activeTask(order: ApiTransportOrder | null): ApiTransportTask | undefined {
  if (!order || order.tasks.length === 0) return undefined;
  return order.tasks.find((task) => LIVE_TASK_STATUSES.includes(task.status))
    ?? order.tasks.find((task) => task.status !== "COMPLETED")
    ?? order.tasks[order.tasks.length - 1];
}

/** Highest orderUpdateId wins; VDA updates are monotonic per order. */
export function latestDispatch(order: ApiTransportOrder | null): ApiVdaDispatch | undefined {
  if (!order || order.vdaDispatches.length === 0) return undefined;
  return order.vdaDispatches.reduce((newest, dispatch) =>
    dispatch.orderUpdateId >= newest.orderUpdateId ? dispatch : newest);
}

export function pipelineStage(order: ApiTransportOrder | null, task?: ApiTransportTask): PipelineStage {
  if (!order) return "ORDER";
  if (order.status === "COMPLETED") return "DONE";
  if (!task) return "ORDER";
  switch (task.status) {
    case "QUEUED":
    case "READY":
      return "TASK";
    // A route exists as soon as planning succeeds; before that the task is only assigned.
    case "ASSIGNED":
      return task.route.length > 0 ? "ROUTE" : "TASK";
    case "DISPATCHED":
      return "VDA";
    case "ACCEPTED":
    case "EXECUTING":
      return "AGV";
    case "COMPLETED":
      return order.tasks.every((other) => other.status === "COMPLETED") ? "DONE" : "AGV";
    default:
      // Failures keep the stage they reached; the exception flag carries the bad news.
      return task.route.length > 0 ? "VDA" : "TASK";
  }
}

function buildSteps(stage: PipelineStage): PipelineStep[] {
  const current = PIPELINE_STAGES.indexOf(stage);
  return PIPELINE_STAGES.map((key, index) => ({
    key,
    label: STAGE_LABELS[key],
    state: index < current ? "DONE" : index === current ? "CURRENT" : "PENDING"
  }));
}

function buildSentence(agv: ApiAgv | null | undefined, order: ApiTransportOrder | null,
    task: ApiTransportTask | undefined): { sentence: string; exception: boolean } {
  if (order && FAILED_STATUSES.includes(order.status)) {
    const reason = order.error ?? task?.error;
    return {
      sentence: `${shortId(order)} ${order.status.toLowerCase()}${reason ? `: ${reason}` : "."}`,
      exception: true
    };
  }
  if (task && FAILED_STATUSES.includes(task.status)) {
    return {
      sentence: `A load movement in ${order ? shortId(order) : "the active order"} ${task.status.toLowerCase()}${task.error ? `: ${task.error}` : "."}`,
      exception: true
    };
  }
  if (!agv?.id) return { sentence: "Waiting for vehicle telemetry.", exception: false };
  if (agv.status === "FAULT") return { sentence: `${agv.id} has faulted and needs attention.`, exception: true };

  const load = agv.carriedLoadId;
  const verb = agv.handlingPhase ? HANDLING_VERBS[agv.handlingPhase] : undefined;
  if (verb && agv.handlingPhase !== "CHARGING") {
    const target = agv.handlingPhase === "LOWERING" || agv.handlingPhase === "DROPPING"
      ? place(task?.destination)
      : place(task?.source);
    return { sentence: `${agv.id} ${verb} ${load ?? "a pallet"} at ${target}.`, exception: false };
  }
  if (agv.charging || agv.handlingPhase === "CHARGING") {
    return { sentence: `${agv.id} is charging at ${Math.round(agv.battery)}% and waiting for work.`, exception: false };
  }
  if (agv.status === "MOVING") {
    return load
      ? { sentence: `${agv.id} is carrying ${load} to ${place(task?.destination)}.`, exception: false }
      : { sentence: `${agv.id} is driving to ${place(task?.source)} to collect a load.`, exception: false };
  }
  if (order?.status === "COMPLETED") {
    return { sentence: `${shortId(order)} is complete. ${agv.id} is ready for the next order.`, exception: false };
  }
  if (!order) return { sentence: `${agv.id} is idle. Create a transport order to start work.`, exception: false };
  return { sentence: `${agv.id} is ${agv.status.toLowerCase()} and ready for the next task.`, exception: false };
}

function buildProofLine(order: ApiTransportOrder | null): string {
  if (!order) return "VDA 5050 v3.0.0 · awaiting an order";
  const dispatch = latestDispatch(order);
  if (!dispatch) return `${shortId(order)} · VDA 5050 v3.0.0 · no order published yet`;
  const validity = dispatch.rejectionError ? "rejected by vehicle"
    : dispatch.valid ? "schema valid"
    : "schema invalid";
  return `${shortId(order)} · VDA 5050 v3.0.0 · ${validity} · update ${dispatch.orderUpdateId}`;
}

export function buildNarrative(agv: ApiAgv | null | undefined, order: ApiTransportOrder | null): Narrative {
  const task = activeTask(order);
  const stage = pipelineStage(order, task);
  const { sentence, exception } = buildSentence(agv, order, task);
  return { sentence, stage, steps: buildSteps(stage), proofLine: buildProofLine(order), exception };
}
