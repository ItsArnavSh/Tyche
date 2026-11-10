import { assertEquals } from "@std/assert";
import { coreLoad, drift, usedPercent } from "./utils.ts";

Deno.test("drift stays within bounds", () => {
  for (let i = 0; i < 200; i++) {
    const value = drift(100, 50, 150, 40);
    assertEquals(value >= 50 && value <= 150, true);
  }
});

Deno.test("usedPercent handles zero totals", () => {
  assertEquals(usedPercent(0, 0), 0);
  assertEquals(usedPercent(100, 25), 75);
});

Deno.test("coreLoad ignores the first sample and computes deltas", () => {
  assertEquals(coreLoad([10, 0, 5, 85], undefined), 0);
  assertEquals(coreLoad([20, 10, 10, 90], [10, 0, 5, 85]), 83);
  assertEquals(coreLoad([10, 0, 5, 90], [10, 0, 5, 90]), 0);
});
