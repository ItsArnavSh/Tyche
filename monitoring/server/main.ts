import { generateMetrics } from "./metrics.ts";

Deno.serve({ port: 8082 }, async (_req) => {
  const metrics = await generateMetrics(12, 8);
  return new Response(JSON.stringify(metrics), {
    headers: {
      "Content-Type": "application/json",
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "*",
      "Access-Control-Allow-Headers": "*",
    },
  });
});
