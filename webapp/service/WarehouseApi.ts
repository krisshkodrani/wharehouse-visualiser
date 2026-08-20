import type { ApiTransportOrder, ScenarioPreset, WarehouseSnapshot } from "../model/types";

/** Public REST boundary for warehouse commands and projections. */
export default class WarehouseApi {
  public snapshot(): Promise<WarehouseSnapshot> {
    return this.request<WarehouseSnapshot>("/api/v1/warehouses/linz/snapshot");
  }

  public submitPutaway(inboundLoadIds: string[], operatorPrompt: string): Promise<{ requestId: string; status: string }> {
    return this.post("/api/v1/warehouses/linz/putaway-requests", { inboundLoadIds, operatorPrompt });
  }

  public receive(sku: string, quantity: number): Promise<unknown> {
    return this.post("/api/v1/warehouses/linz/inbound-loads", { sku, quantity });
  }

  public outbound(loadIds: string[]): Promise<unknown> {
    return this.post("/api/v1/warehouses/linz/outbound-requests", { loadIds });
  }

  public scenarioPresets(): Promise<ScenarioPreset[]> {
    return this.request<ScenarioPreset[]>("/api/v1/scenario-presets");
  }

  public seedScenario(presetId: string): Promise<WarehouseSnapshot> {
    return this.post("/api/v1/warehouses/linz/scenario", { presetId });
  }

  public resetScenario(): Promise<WarehouseSnapshot> {
    return this.post("/api/v1/warehouses/linz/scenario/reset", {});
  }

  public createTransportOrder(type: "PUTAWAY" | "OUTBOUND", priority: "NORMAL" | "HIGH" | "URGENT",
      loadIds: string[], objective: string): Promise<ApiTransportOrder> {
    return this.post("/api/v1/warehouses/linz/transport-orders", { type, priority, loadIds, objective });
  }

  public cancelTransportOrder(orderId: string): Promise<ApiTransportOrder> {
    return this.post(`/api/v1/warehouses/linz/transport-orders/${orderId}/cancel`, {});
  }

  public operation(command: "pause" | "resume" | "reset"): Promise<unknown> {
    return this.post(`/api/v1/warehouses/linz/operations/${command}`, {});
  }

  public setSpeed(multiplier: 1 | 2 | 4): Promise<unknown> {
    return this.post("/api/v1/warehouses/linz/operations/speed", { multiplier });
  }

  private async request<T>(path: string, init?: RequestInit): Promise<T> {
    const response = await fetch(path, init);
    if (!response.ok) {
      const problem = await response.json().catch(() => ({})) as
        { detail?: string; title?: string; error?: string; message?: string };
      const reason = problem.detail ?? problem.message ?? problem.error ?? problem.title ?? response.statusText;
      throw new Error(reason || `Request failed with ${response.status}`);
    }
    return response.json() as Promise<T>;
  }

  private post<T>(path: string, body: unknown): Promise<T> {
    return this.request<T>(path, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body)
    });
  }
}
