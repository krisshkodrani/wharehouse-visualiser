import type { WarehouseEvent, WarehouseSnapshot } from "./types";

export default class WarehouseApi {
  private socket?: WebSocket;
  private reconnectTimer?: number;
  private stopped = false;

  public async snapshot(): Promise<WarehouseSnapshot> {
    return this.request<WarehouseSnapshot>("/api/v1/warehouses/linz/snapshot");
  }

  public async submitPutaway(inboundLoadIds: string[], operatorPrompt: string): Promise<{ requestId: string; status: string }> {
    return this.request("/api/v1/warehouses/linz/putaway-requests", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ inboundLoadIds, operatorPrompt })
    });
  }

  public receive(sku: string, quantity: number): Promise<unknown> {
    return this.post("/api/v1/warehouses/linz/inbound-loads", { sku, quantity });
  }

  public outbound(loadIds: string[]): Promise<unknown> {
    return this.post("/api/v1/warehouses/linz/outbound-requests", { loadIds });
  }

  public operation(command: "pause" | "resume" | "reset"): Promise<unknown> {
    return this.post(`/api/v1/warehouses/linz/operations/${command}`, {});
  }

  public connect(onEvent: (event: WarehouseEvent) => void, onStatus: (status: string) => void): void {
    this.stopped = false;
    const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    this.socket = new WebSocket(`${protocol}//${window.location.host}/ws`);
    this.socket.onopen = () => {
      this.socket?.send(`CONNECT\naccept-version:1.2\nhost:${window.location.host}\nheart-beat:10000,10000\n\n\0`);
    };
    this.socket.onmessage = (message) => {
      const frames = String(message.data).split("\0");
      for (const raw of frames) {
        const frame = raw.replace(/^\n+/, "");
        if (frame.startsWith("CONNECTED")) {
          this.socket?.send("SUBSCRIBE\nid:warehouse-ui\ndestination:/topic/warehouses/linz\nack:auto\n\n\0");
          onStatus("CONNECTED");
        } else if (frame.startsWith("MESSAGE")) {
          const bodyIndex = frame.indexOf("\n\n");
          if (bodyIndex >= 0) {
            try { onEvent(JSON.parse(frame.slice(bodyIndex + 2)) as WarehouseEvent); } catch { /* Ignore malformed server frames. */ }
          }
        }
      }
    };
    this.socket.onerror = () => onStatus("ERROR");
    this.socket.onclose = () => {
      onStatus("RECONNECTING");
      if (!this.stopped) this.reconnectTimer = window.setTimeout(() => this.connect(onEvent, onStatus), 2000);
    };
  }

  public disconnect(): void {
    this.stopped = true;
    if (this.reconnectTimer) window.clearTimeout(this.reconnectTimer);
    if (this.socket?.readyState === WebSocket.OPEN) this.socket.send("DISCONNECT\nreceipt:bye\n\n\0");
    this.socket?.close();
  }

  private async request<T>(path: string, init?: RequestInit): Promise<T> {
    const response = await fetch(path, init);
    if (!response.ok) {
      const error = await response.json().catch(() => ({ error: response.statusText })) as { error?: string };
      throw new Error(error.error || `Request failed with ${response.status}`);
    }
    return response.json() as Promise<T>;
  }

  private post<T>(path: string, body: unknown): Promise<T> {
    return this.request<T>(path, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
  }
}
