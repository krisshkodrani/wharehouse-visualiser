import Babylon from "../../vendor/BabylonRuntime";
import type { Scene as SceneType } from "@babylonjs/core/scene";
import type { StandardMaterial as StandardMaterialType } from "@babylonjs/core/Materials/standardMaterial";

const { Color3, DynamicTexture, StandardMaterial } = Babylon;

/** Creates and caches scene materials by semantic name for one warehouse build. */
export default class MaterialFactory {
  private readonly cache = new Map<string, StandardMaterialType>();

  public constructor(private readonly scene: SceneType) {}

  /** Forget materials disposed with the previous warehouse root. */
  public clear(): void {
    this.cache.clear();
  }

  public create(name: string, hex: string): StandardMaterialType {
    return this.cached(name, () => {
      const material = new StandardMaterial(name, this.scene);
      material.diffuseColor = Color3.FromHexString(hex);
      material.specularColor = new Color3(0.12, 0.12, 0.12);
      return material;
    });
  }

  public metal(name: string, hex: string, specularPower: number): StandardMaterialType {
    return this.cached(name, () => {
      const material = this.uncached(name, hex);
      material.specularColor = new Color3(0.32, 0.34, 0.35);
      material.specularPower = specularPower;
      material.roughness = 0.58;
      return material;
    });
  }

  public cardboard(name: string): StandardMaterialType {
    return this.cached(name, () => {
      const texture = new DynamicTexture(`${name}-texture`, { width: 512, height: 512 }, this.scene, true);
      const context = texture.getContext() as CanvasRenderingContext2D;
      context.fillStyle = "#a97943";
      context.fillRect(0, 0, 512, 512);
      for (let i = 0; i < 420; i += 1) {
        const x = (i * 47) % 512;
        const y = (i * 83) % 512;
        context.fillStyle = i % 3 === 0 ? "rgba(65, 38, 17, .11)" : "rgba(255, 235, 192, .08)";
        context.fillRect(x, y, 1 + (i % 2), 1);
      }
      context.fillStyle = "rgba(218, 190, 139, .62)";
      context.fillRect(226, 0, 60, 512);
      context.fillStyle = "rgba(69, 43, 24, .52)";
      context.fillRect(30, 340, 150, 5);
      context.fillRect(30, 357, 105, 4);
      texture.update(false);
      texture.anisotropicFilteringLevel = 8;
      const material = this.uncached(name, "#a97943");
      material.diffuseTexture = texture;
      material.specularColor = new Color3(0.025, 0.02, 0.015);
      material.roughness = 0.94;
      return material;
    });
  }

  public pallet(name: string): StandardMaterialType {
    return this.cached(name, () => {
      const texture = new DynamicTexture(`${name}-texture`, { width: 512, height: 256 }, this.scene, true);
      const context = texture.getContext() as CanvasRenderingContext2D;
      context.fillStyle = "#8a5a32";
      context.fillRect(0, 0, 512, 256);
      for (let y = 12; y < 256; y += 18) {
        context.strokeStyle = y % 36 === 12 ? "rgba(54, 29, 12, .28)" : "rgba(224, 168, 102, .18)";
        context.lineWidth = 2;
        context.beginPath();
        context.moveTo(0, y);
        context.bezierCurveTo(150, y - 5, 350, y + 6, 512, y - 2);
        context.stroke();
      }
      texture.update(false);
      texture.anisotropicFilteringLevel = 8;
      const material = this.uncached(name, "#8a5a32");
      material.diffuseTexture = texture;
      material.specularColor = new Color3(0.04, 0.025, 0.015);
      material.roughness = 0.88;
      return material;
    });
  }

  public floor(name: string, baseColor: string): StandardMaterialType {
    return this.cached(name, () => {
      const texture = new DynamicTexture(`${name}Texture`, { width: 1536, height: 1152 }, this.scene, true);
      const context = texture.getContext() as CanvasRenderingContext2D;
      context.fillStyle = baseColor;
      context.fillRect(0, 0, 1536, 1152);
      context.fillStyle = "rgba(72, 78, 77, 0.16)";
      context.fillRect(0, 0, 1536, 1152);
      for (let i = 0; i < 2200; i += 1) {
        const x = (i * 73) % 1536;
        const y = (i * 151 + (i % 17) * 29) % 1152;
        const shade = 90 + (i * 37) % 90;
        context.fillStyle = `rgba(${shade}, ${shade + 3}, ${shade + 1}, ${0.025 + (i % 4) * 0.01})`;
        context.fillRect(x, y, 1 + (i % 3), 1 + ((i + 1) % 3));
      }
      context.lineWidth = 3;
      context.strokeStyle = "rgba(48, 55, 54, 0.34)";
      for (let x = 0; x <= 1536; x += 128) this.line(context, x, 0, x, 1152);
      for (let y = 0; y <= 1152; y += 128) this.line(context, 0, y, 1536, y);
      context.lineWidth = 1;
      context.strokeStyle = "rgba(255, 255, 255, 0.2)";
      for (let x = 2; x <= 1536; x += 128) this.line(context, x, 0, x, 1152);
      for (let y = 2; y <= 1152; y += 128) this.line(context, 0, y, 1536, y);
      texture.update(false);
      texture.anisotropicFilteringLevel = 8;
      const material = this.uncached(name, "#b7bcba");
      material.diffuseTexture = texture;
      material.specularColor = new Color3(0.08, 0.08, 0.075);
      material.specularPower = 22;
      material.roughness = 0.9;
      return material;
    });
  }

  private cached(name: string, create: () => StandardMaterialType): StandardMaterialType {
    const existing = this.cache.get(name);
    if (existing) return existing;
    const material = create();
    this.cache.set(name, material);
    return material;
  }

  private uncached(name: string, hex: string): StandardMaterialType {
    const material = new StandardMaterial(name, this.scene);
    material.diffuseColor = Color3.FromHexString(hex);
    material.specularColor = new Color3(0.12, 0.12, 0.12);
    return material;
  }

  private line(context: CanvasRenderingContext2D, fromX: number, fromY: number, toX: number, toY: number): void {
    context.beginPath();
    context.moveTo(fromX, fromY);
    context.lineTo(toX, toY);
    context.stroke();
  }
}
