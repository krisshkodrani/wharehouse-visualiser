import ForkliftVisual from "../entities/ForkliftVisual";

/** Applies sampled chassis pose and derives wheel rotation from travelled distance. */
export default class ForkliftAnimator {
  public constructor(private readonly visual: ForkliftVisual) {}

  public setPose(x: number, z: number, theta: number): number {
    const distance = this.visual.setPose(x, z, theta);
    this.visual.rotateWheels(distance);
    return distance;
  }
}
