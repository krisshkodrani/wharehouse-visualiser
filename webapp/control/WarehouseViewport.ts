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
      renderManager.openStart("div").class("loadTooltip").attr("role", "tooltip").attr("aria-hidden", "true").openEnd();
      renderManager.openStart("span").class("loadTooltipKicker").openEnd().text("PACKAGE").close("span");
      renderManager.openStart("strong").class("loadTooltipId").openEnd().close("strong");
      renderManager.openStart("span").class("loadTooltipItem").openEnd().close("span");
      renderManager.openStart("span").class("loadTooltipMeta").openEnd().close("span");
      renderManager.close("div");
      renderManager.close("div");
    }
  };

  private sceneController?: WarehouseScene;
  private pendingWarehouse?: WarehouseVisualConfig;
  private resizeObserver?: ResizeObserver;
  private tooltip?: HTMLElement;
  private pointerX = 0;
  private pointerY = 0;

  public onAfterRendering(): void {
    this.log("viewport", "onAfterRendering");
    const root = this.getDomRef();
    const canvas = root?.querySelector("canvas");
    if (!(root instanceof HTMLElement) || !(canvas instanceof HTMLCanvasElement)) {
      return;
    }
    this.tooltip = root.querySelector<HTMLElement>(".loadTooltip") ?? undefined;
    canvas.addEventListener("pointermove", this.onPointerMove);
    try {
      this.sceneController?.dispose();
      this.sceneController = new WarehouseScene(canvas, (rackId, rackName) => {
        this.fireEvent("rackSelected", { rackId, rackName });
      }, (loadId) => this.showLoadTooltip(loadId));
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

  public setAgvState(agv: ApiAgv): void {
    this.sceneController?.setAgvState(agv);
  }

  public setAgvOperations(agv: ApiAgv): void {
    this.sceneController?.setAgvOperations(agv);
  }

  public exit(): void {
    this.getDomRef()?.querySelector("canvas")?.removeEventListener("pointermove", this.onPointerMove);
    this.resizeObserver?.disconnect();
    this.sceneController?.dispose();
    this.sceneController = undefined;
  }

  private readonly onPointerMove = (event: PointerEvent): void => {
    const root = this.getDomRef();
    if (!(root instanceof HTMLElement)) return;
    const bounds = root.getBoundingClientRect();
    this.pointerX = event.clientX - bounds.left;
    this.pointerY = event.clientY - bounds.top;
    this.positionTooltip();
  };

  private showLoadTooltip(loadId?: string): void {
    if (!this.tooltip) return;
    if (!loadId) {
      this.tooltip.classList.remove("isVisible");
      this.tooltip.setAttribute("aria-hidden", "true");
      return;
    }
    const load = this.pendingWarehouse?.loadDetails?.find((candidate) => candidate.id === loadId);
    this.tooltip.querySelector<HTMLElement>(".loadTooltipId")!.textContent = loadId;
    this.tooltip.querySelector<HTMLElement>(".loadTooltipItem")!.textContent = load?.item ?? "Package";
    this.tooltip.querySelector<HTMLElement>(".loadTooltipMeta")!.textContent =
      [load?.status?.replaceAll("_", " "), load?.locationId].filter(Boolean).join("  ·  ") || "Live warehouse load";
    this.tooltip.classList.add("isVisible");
    this.tooltip.setAttribute("aria-hidden", "false");
    this.positionTooltip();
  }

  private positionTooltip(): void {
    const root = this.getDomRef();
    if (!(root instanceof HTMLElement) || !this.tooltip?.classList.contains("isVisible")) return;
    const x = Math.min(this.pointerX + 14, root.clientWidth - this.tooltip.offsetWidth - 10);
    const y = Math.min(this.pointerY + 14, root.clientHeight - this.tooltip.offsetHeight - 10);
    this.tooltip.style.transform = `translate(${Math.max(10, x)}px, ${Math.max(10, y)}px)`;
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
