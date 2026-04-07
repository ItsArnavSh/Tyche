export interface Data {
  LoadedFuncs: number; //Always constant
  FunctionsRanPS: number; //Varies
  ThroughputOfStocks: number; //Varies
  DroppedStocks: number; //Varies
  CoreCount: number; //constant
  CoreStatus: number[]; //Shows how many tasks given to each core varies
  PendingFunctions: number; //varies
  ScheduledFunctions: number; //varies
  ProcessRAMUsage: number; //varies
}
let functionsRanPS = 200;
let throughput = 500;
let dropped = 2;
let pending = 50;
let scheduled = 150;
let ram = 512;
let coreLoads: number[] = Array(8).fill(5);

function drift(val: number, min: number, max: number, step: number): number {
  const change = (Math.random() - 0.5) * step;
  return Math.min(max, Math.max(min, val + change));
}

export function generateMetrics(loaded: number, cores: number): Data {
  functionsRanPS = Math.round(drift(functionsRanPS, 50, 800, 60));
  throughput = Math.round(drift(throughput, 100, 2000, 100));
  dropped = Math.round(drift(dropped, 0, 30, 2));
  pending = Math.round(drift(pending, 0, 300, 20));
  scheduled = Math.round(drift(scheduled, 0, 400, 25));
  ram = Math.round(drift(ram, 128, 2048, 80));

  // cores drift individually but loosely follow overall load
  const load_bias = functionsRanPS / 800; // 0–1
  coreLoads = coreLoads.map((c) =>
    Math.round(
      Math.min(100, Math.max(0, drift(c, 0, 100, 15) * 0.7 + load_bias * 30)),
    ),
  );

  return {
    LoadedFuncs: loaded,
    FunctionsRanPS: functionsRanPS,
    ThroughputOfStocks: throughput,
    DroppedStocks: dropped,
    CoreCount: cores,
    CoreStatus: coreLoads,
    PendingFunctions: pending,
    ScheduledFunctions: scheduled,
    ProcessRAMUsage: ram,
  };
}
