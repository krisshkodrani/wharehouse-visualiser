import type { WarehouseEvent } from "../model/types";

export type WarehouseConnectionStatus = "CONNECTED" | "ERROR" | "RECONNECTING";

/** Owns WebSocket/STOMP mechanics and emits parsed warehouse events. */
export default class WarehouseEventService {
  private socket?: WebSocket;
  private reconnectTimer?: number;
  private stopped = true;

  public connect(onEvent: (event: WarehouseEvent) => void,
      onStatus: (status: WarehouseConnectionStatus) => void): void {
    this.stopped = false;
    const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    this.socket = new WebSocket(`${protocol}//${window.location.host}/ws`);
    this.socket.onopen = () => {
      this.socket?.send(`CONNECT\naccept-version:1.2\nhost:${window.location.host}\nheart-beat:10000,10000\n\n\0`);
    };
    this.socket.onmessage = (message) => this.consumeFrames(String(message.data), onEvent, onStatus);
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

  private consumeFrames(data: string, onEvent: (event: WarehouseEvent) => void,
      onStatus: (status: WarehouseConnectionStatus) => void): void {
    for (const raw of data.split("\0")) {
      const frame = raw.replace(/^\n+/, "");
      if (frame.startsWith("CONNECTED")) {
        this.socket?.send("SUBSCRIBE\nid:warehouse-ui\ndestination:/topic/warehouses/linz\nack:auto\n\n\0");
        onStatus("CONNECTED");
      } else if (frame.startsWith("MESSAGE")) {
        const bodyIndex = frame.indexOf("\n\n");
        if (bodyIndex < 0) continue;
        try { onEvent(JSON.parse(frame.slice(bodyIndex + 2)) as WarehouseEvent); }
        catch { /* Ignore malformed server frames. */ }
      }
    }
  }
}
