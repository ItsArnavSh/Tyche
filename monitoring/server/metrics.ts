import { readFileSync } from "node:fs";
import { get } from "./redis.ts";

export interface Data {
  LoadedFuncs: number;
  FunctionsRanPS: number;
  ThroughputOfStocks: number;
  DroppedStocks: number;
  CoreCount: number;
  CoreStatus: number[];
  PendingFunctions: number;
  ScheduledFunctions: number;
  ProcessRAMUsage: number;
}

let functionsRanPS = 200;
let throughput = 500;
let dropped = 2;
let scheduled = 150;
let coreLoads: number[] = [];
let prevCoreStats: number[][] = [];

console.log(await get("tyche:monitor:cores_no"));

function getCoreStats(): number[][] {
  const lines = readFileSync("/proc/stat", "utf8").split("\n");
  return lines
    .filter((l) => /^cpu\d/.test(l))
    .map((l) => l.split(/\s+/).slice(1).map(Number));
}

function getRealCoreLoads(): number[] {
  const curr = getCoreStats();
  if (prevCoreStats.length === 0) {
    prevCoreStats = curr;
    return curr.map(() => 0);
  }
  const loads = curr.map((core, i) => {
    const prev = prevCoreStats[i];
    const idle = core[3] - prev[3];
    const total =
      core.reduce((a, b) => a + b, 0) - prev.reduce((a, b) => a + b, 0);
    return total === 0 ? 0 : Math.round((1 - idle / total) * 100);
  });
  prevCoreStats = curr;
  return loads;
}

function getSystemRAMPercent(): number {
  const meminfo = readFileSync("/proc/meminfo", "utf8");
  const get = (key: string) =>
    Number(meminfo.match(new RegExp(`${key}:\\s+(\\d+)`))?.[1] ?? 0);
  const total = get("MemTotal");
  const available = get("MemAvailable");
  return total === 0 ? 0 : Math.round((1 - available / total) * 100);
}

function drift(val: number, min: number, max: number, step: number): number {
  const change = (Math.random() - 0.5) * step;
  return Math.min(max, Math.max(min, val + change));
}

export async function generateMetrics(
  loaded: number,
  cores: number,
): Promise<Data> {
  functionsRanPS = Math.round(drift(functionsRanPS, 50, 800, 60));
  throughput = Math.round(drift(throughput, 100, 2000, 100));
  dropped = Math.round(drift(dropped, 0, 30, 2));
  scheduled = Math.round(drift(scheduled, 0, 400, 25));
  coreLoads = getRealCoreLoads();

  const pending = Number((await get("tyche:monitor:pending_functions")) ?? 0);

  return {
    LoadedFuncs: loaded,
    FunctionsRanPS: functionsRanPS,
    ThroughputOfStocks: throughput,
    DroppedStocks: dropped,
    CoreCount: cores,
    CoreStatus: coreLoads,
    PendingFunctions: pending,
    ScheduledFunctions: scheduled,
    ProcessRAMUsage: getSystemRAMPercent(),
  };
}
