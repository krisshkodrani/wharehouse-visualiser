import Control from "sap/ui/core/Control";
import RenderManager from "sap/ui/core/RenderManager";
import WarehouseScene from "../visualization/WarehouseScene";
import type { ApiAgv, WarehouseVisualConfig } from "../model/types";

/** @namespace warehouse.visualizer.control */
export default class WarehouseViewport extends Control {
  static readonly metadata = {
    events: {
      rackSelected: {
        parameters: { rackId: { type: "string" }, rackName: { type: "string" } }
      }
    }
  };

  static readonly renderer = {
    apiVersion: 2,
    render(renderManager: RenderManager, control: WarehouseViewport): void {
      renderManager.openStart("div", control).class("warehouseViewportHost").openEnd();
      renderManager.openStart("canvas").class("warehouseCanvas").attr("aria-label", "Interactive 3D warehouse").attr("tabindex", "0").openEnd().close("canvas");
      renderManager.close("div");
    }
  };

  private sceneController?: WarehouseScene;
  private pendingWarehouse?: WarehouseVisualConfig;
  private resizeObserver?: ResizeObserver;

  public onAfterRendering(): void {
    this.log("viewport", "onAfterRendering");
    const root = this.getDomRef();
    const canvas = root?.querySelector("canvas");
    if (!(root instanceof HTMLElement) || !(canvas instanceof HTMLCanvasElement)) {
      return;
    }
    try {
      this.sceneController?.dispose();
      this.sceneController = new WarehouseScene(canvas, (rackId, rackName) => {
        this.fireEvent("rackSelected", { rackId, rackName });
      });
      this.log("viewport", "Babylon scene initialized");
      if (this.pendingWarehouse) {
        this.sceneController.setWarehouse(this.pendingWarehouse);
      }
    } catch (error) {
      this.log("viewport", error, true);
      root.classList.add("warehouseViewportError");
      root.textContent = `3D view unavailable: ${error instanceof Error ? error.message : String(error)}`;
      return;
    }
    this.resizeObserver?.disconnect();
    this.resizeObserver = new ResizeObserver(() => this.sceneController?.resize());
    this.resizeObserver.observe(root);
  }

  public setWarehouse(config: WarehouseVisualConfig): void {
    this.pendingWarehouse = config;
    this.sceneController?.setWarehouse(config);
  }

  public updateOperations(config: WarehouseVisualConfig): void {
    this.pendingWarehouse = config;
    this.sceneController?.updateOperations(config);
  }

  public moveForklift(): void {
    this.sceneController?.moveForklift();
  }

  public setAgvState(agv: ApiAgv): void {
    this.sceneController?.setAgvState(agv);
  }

  public setSandboxMode(enabled: boolean): void {
    this.sceneController?.setSandboxMode(enabled);
  }

  public exit(): void {
    this.resizeObserver?.disconnect();
    this.sceneController?.dispose();
    this.sceneController = undefined;
  }

  private log(stage: string, value: unknown, error = false): void {
    const diagnostics = (window as Window & {
      __warehouseDiagnostics?: {
        info: (stage: string, value: unknown) => void;
        error: (stage: string, value: unknown) => void;
      };
    }).__warehouseDiagnostics;
    diagnostics?.[error ? "error" : "info"](stage, value);
  }
}
