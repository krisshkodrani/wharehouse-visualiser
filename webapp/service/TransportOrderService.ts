import type { ApiTransportOrder } from "../model/types";
import WarehouseApi from "./WarehouseApi";

/** Application-facing transport-order commands. */
export default class TransportOrderService {
  public constructor(private readonly api: WarehouseApi) {}

  public create(type: "PUTAWAY" | "OUTBOUND", priority: "NORMAL" | "HIGH" | "URGENT",
      loadIds: string[], objective: string): Promise<ApiTransportOrder> {
    return this.api.createTransportOrder(type, priority, loadIds, objective);
  }

  public cancel(orderId: string): Promise<ApiTransportOrder> {
    return this.api.cancelTransportOrder(orderId);
  }
}
