import { generateMetrics } from "./metrics.ts";

Deno.serve({ port: 8082 }, (_req) => {
  const metrics = generateMetrics(12, 8);

  return new Response(JSON.stringify(metrics), {
    headers: {
      "Content-Type": "application/json",
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "*",
      "Access-Control-Allow-Headers": "*",
    },
  });
});
