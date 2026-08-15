import type { ApiAgv } from "./types";

const MOTION_FIELDS = ["x", "z", "theta", "velocity"] as const;

export function mergeAgvEvent(current: ApiAgv | undefined, incoming: ApiAgv, carriesLivePose: boolean): ApiAgv {
  const hasCurrentPose = current && MOTION_FIELDS.every((field) => Number.isFinite(current[field]));
  if (carriesLivePose || !hasCurrentPose) return incoming;
  const merged = { ...incoming };
  for (const field of MOTION_FIELDS) merged[field] = current[field];
  return merged;
}
