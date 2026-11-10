# Tyche Dashboard

SvelteKit + Tailwind live console for the Go gateway status API. It renders:

- paper equity, cash, PnL and win rate
- open positions marked from the live feed
- recent BUY/SELL decisions and fills
- closed round trips
- system health, market watch and latest signals

The gateway is the only data source. There is no Deno/Node monitoring server.

## Development

```bash
npm install
npm run dev
```

By default the page polls `http://<current-host>:8080/api/status`. Override it
with a query parameter if needed:

```text
http://localhost:5173/?api=http://gateway-host:8080/api/status
```

## Checks

```bash
npm run check
npm run build
```
