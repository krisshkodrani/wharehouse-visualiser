import type { Mesh } from "@babylonjs/core/Meshes/mesh";
import type { TransformNode as TransformNodeType } from "@babylonjs/core/Meshes/transformNode";

/** Identity-bearing cargo visual reused across ownership handovers. */
export default class PalletVisual {
  public constructor(
    public readonly id: string,
    public readonly root: TransformNodeType,
    public carried: boolean,
    public readonly meshes: Mesh[] = []
  ) {}

  public attachTo(parent: TransformNodeType): void {
    this.root.parent = parent;
    this.carried = true;
  }

  public detachTo(parent: TransformNodeType | null): void {
    this.root.setParent(parent);
    this.carried = false;
  }
}
