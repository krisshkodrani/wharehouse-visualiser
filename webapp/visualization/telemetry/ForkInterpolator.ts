export interface ForkSample {
  receivedAt: number;
  height: number;
  extension: number;
}

export interface ForkInterpolatorOptions {
  maximumHeight: number;
  maximumExtension: number;
  streamGapMs: number;
  bufferLimit: number;
}

/** Buffered fork interpolation; a long telemetry gap starts a new stream. */
export default class ForkInterpolator {
  private readonly samples: ForkSample[] = [];

  public constructor(private readonly options: ForkInterpolatorOptions) {}

  public push(height = 0, extension = 0, receivedAt = performance.now()): void {
    const sample = {
      receivedAt,
      height: Math.max(0, Math.min(this.options.maximumHeight, height)),
      extension: Math.max(0, Math.min(this.options.maximumExtension, extension))
    };
    const previous = this.samples.at(-1);
    if (previous && receivedAt - previous.receivedAt > this.options.streamGapMs) this.clear();
    this.samples.push(sample);
    while (this.samples.length > this.options.bufferLimit) this.samples.shift();
  }

  public sample(renderTime: number): Omit<ForkSample, "receivedAt"> | undefined {
    if (this.samples.length === 0) return undefined;
    while (this.samples.length > 2 && this.samples[1].receivedAt <= renderTime) this.samples.shift();
    const from = this.samples[0];
    const to = this.samples[1] ?? from;
    const span = Math.max(1, to.receivedAt - from.receivedAt);
    const progress = Math.max(0, Math.min(1, (renderTime - from.receivedAt) / span));
    return {
      height: from.height + (to.height - from.height) * progress,
      extension: from.extension + (to.extension - from.extension) * progress
    };
  }

  public clear(): void {
    this.samples.length = 0;
  }
}
