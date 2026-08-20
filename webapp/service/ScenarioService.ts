import type { WarehouseSnapshot } from "../model/types";
import WarehouseApi from "./WarehouseApi";

/** Demo scenario and runtime controls, separate from business transport orders. */
export default class ScenarioService {
  public constructor(private readonly api: WarehouseApi) {}

  public start(presetId: string): Promise<WarehouseSnapshot> {
    return this.api.seedScenario(presetId);
  }

  public reset(): Promise<WarehouseSnapshot> {
    return this.api.resetScenario();
  }

  public operation(command: "pause" | "resume" | "reset"): Promise<unknown> {
    return this.api.operation(command);
  }

  public setSpeed(multiplier: 1 | 2 | 4): Promise<unknown> {
    return this.api.setSpeed(multiplier);
  }
}
