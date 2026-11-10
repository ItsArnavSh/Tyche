/** Small pure helpers kept separate so they can be tested without Redis. */

/** Randomly drifts a value by at most `step / 2`, clamped to [min, max]. */
export function drift(
  val: number,
  min: number,
  max: number,
  step: number,
): number {
  const change = (Math.random() - 0.5) * step;
  return Math.min(max, Math.max(min, val + change));
}

/** Percentage of used memory given total and available kilobytes. */
export function usedPercent(total: number, available: number): number {
  return total <= 0 ? 0 : Math.round((1 - available / total) * 100);
}

/** Maps per-core counters into a 0..100 busy percentage. */
export function coreLoad(
  current: number[],
  previous: number[] | undefined,
): number {
  if (!previous || previous.length !== current.length) {
    return 0;
  }
  const idle = current[3] - previous[3];
  const total = current.reduce((a, b) => a + b, 0) -
    previous.reduce((a, b) => a + b, 0);
  return total <= 0 ? 0 : Math.round((1 - idle / total) * 100);
}
