export interface PoseSample {
  receivedAt: number;
  x: number;
  z: number;
  theta: number;
  velocity: number;
}

export interface InterpolatedPose {
  x: number;
  z: number;
  theta: number;
  velocity: number;
}

/** Delay-buffered vehicle pose interpolation with shortest-path heading rotation. */
export default class PoseInterpolator {
  private readonly samples: PoseSample[] = [];

  public constructor(private readonly limit = 40) {}

  public push(sample: PoseSample): void {
    const previous = this.samples.at(-1);
    if (previous && previous.x === sample.x && previous.z === sample.z && previous.theta === sample.theta) return;
    this.samples.push(sample);
    while (this.samples.length > this.limit) this.samples.shift();
  }

  public sample(renderTime: number): InterpolatedPose | undefined {
    if (this.samples.length === 0) return undefined;
    while (this.samples.length > 2 && this.samples[1].receivedAt <= renderTime) this.samples.shift();
    const from = this.samples[0];
    const to = this.samples[1] ?? from;
    const span = Math.max(1, to.receivedAt - from.receivedAt);
    const progress = Math.max(0, Math.min(1, (renderTime - from.receivedAt) / span));
    const headingDelta = Math.atan2(Math.sin(to.theta - from.theta), Math.cos(to.theta - from.theta));
    return {
      x: from.x + (to.x - from.x) * progress,
      z: from.z + (to.z - from.z) * progress,
      theta: from.theta + headingDelta * progress,
      velocity: from.velocity + (to.velocity - from.velocity) * progress
    };
  }

  public clear(): void {
    this.samples.length = 0;
  }
}
