import type { ApiTransportOrder } from "./types";

export const ATTENTION_ORDER_STATUSES = ["FAILED", "REJECTED", "CANCELLED"] as const;

export interface TransportOrderRow extends ApiTransportOrder {
  shortId: string;
  displayType: "Put-away" | "Outbound";
  completedTasks: number;
  progress: number;
  assignedAgv: string;
}

export function calculateOrderProgress(order: ApiTransportOrder): number {
  if (order.tasks.length === 0) return 0;
  const completed = order.tasks.filter((task) => task.status === "COMPLETED").length;
  return Math.round(completed / order.tasks.length * 100);
}

export function presentTransportOrder(order: ApiTransportOrder): TransportOrderRow {
  const completedTasks = order.tasks.filter((task) => task.status === "COMPLETED").length;
  return {
    ...order,
    shortId: order.id.slice(0, 8).toUpperCase(),
    displayType: order.type === "PUTAWAY" ? "Put-away" : "Outbound",
    completedTasks,
    progress: calculateOrderProgress(order),
    assignedAgv: order.tasks.find((task) => task.assignedAgvId)?.assignedAgvId ?? "Awaiting AGV"
  };
}

export function presentTransportOrders(orders: ApiTransportOrder[]): TransportOrderRow[] {
  return orders.map(presentTransportOrder);
}

export function selectAttentionOrders<T extends ApiTransportOrder>(orders: T[]): T[] {
  return orders.filter((order) => (ATTENTION_ORDER_STATUSES as readonly string[]).includes(order.status));
}

export function selectMostRelevantOrder<T extends ApiTransportOrder>(orders: T[]): T | undefined {
  return orders.find((order) => order.status === "IN_PROGRESS")
    ?? orders.find((order) => ["PLANNING", "READY"].includes(order.status))
    ?? orders.find((order) => order.status === "COMPLETED")
    ?? orders[0];
}
