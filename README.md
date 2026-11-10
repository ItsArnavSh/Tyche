# Tyche

> A distributed, real-time trading system focused on fairness, scalability, and low-latency engineering.

**Tyche** is named after the Greek goddess of fortune, embodying both good and bad luck. (Fun fact: the name was inspired during a God of War gaming session — it just made sense!)

Tyche is a fully autonomous, real-time, distributed, load-balanced trading bot. The **Go gateway owns the entire market-data plane**: it streams real Indian market data, pushes candles into the Java core, consumes strategy signals through Redis, and executes them in a thread-safe **paper-trading** ledger. The Java core is compute-only: scheduling, indicator math, and strategy execution. A standalone Python backtester lets you evaluate the same strategy families on real history before letting them run.

> **Paper trading only.** The autonomous decision path routes every fill to an in-memory paper ledger. There is no live order adapter. Do not point this at a real account without adding explicit execution, risk, and monitoring layers.

## Features

- **One data-plane owner** — the Go gateway is the only component that talks to Yahoo Finance, Kite Connect, or the synthetic feed. The Java core never makes outbound market-data or broker calls.
- **Real market data** — Yahoo Finance chart/spark endpoints by default; Zerodha Kite Connect v3 when credentials are supplied. The `fake` source remains available for offline demos.
- **Autonomous loop** — bootstrap history → roll updates → indicators/signals → Redis signal queue → confidence/risk checks → paper fills → stop-loss/take-profit/time exits. No human input required.
- **Cross-language boundary** — the single proto source lives in `gateway/proto/stockrec.proto`. Java stubs are generated from it and committed for clean-checkout builds.
- **Live status API** — the Go gateway exposes an in-memory JSON snapshot of decisions, fills, cash, equity, open positions, closed trades, signals, and feed health. No separate monitoring service.
- **Fair scheduling** — boot jobs pre-empt rolling jobs, rolling work is FIFO per priority, and a bounded per-ticker candle cache prevents memory growth.
- **Resilience** — HTTP retries, stale-ticker detection and re-boot, graceful shutdown, Redis-optional operation, and per-ticker failure isolation.
- **Backtesting** — real Yahoo/yfinance/CSV data, five strategy families with parameter overrides, portfolio cash sharing, fees/slippage/stops/targets, and JSON/CSV exports.
- **Tests** — JUnit 5 for the Java core, `go test -race` for the gateway, and `unittest` for the backtester.

## Architecture

![Internal design overview](assets/demo2.png)

The diagram is the original high-level design; the current data flow is:

1. **Go gateway (`gateway/`)** pulls candles from Yahoo, Kite, or the fake feed and pushes them to the Java core over gRPC. It owns ticker selection, history re-boots, quote polling, and the status API.
2. **Java core (`core/`)** maintains a newest-first candle cache per `(symbol, candle size)`, runs fair scheduling across all jobs, executes the indicator functions, and publishes `Signal` JSON to Redis. It has no external HTTP or broker client. Tickers the core cannot apply are returned to the gateway, which re-boots them.
3. **Go gateway decision/monitor loop** consumes signals, applies confidence/position/sizing/cooldown checks, executes paper trades, and manages stop-loss, take-profit, max-hold, and signal-based exits.
4. **Dashboard (`monitoring/client/`)** polls the Go status API and renders the live paper portfolio, decisions, fills, positions, signals, and system health.

Communication is event-driven and designed for horizontal scalability. Fairness and backpressure live in the system, not in individual strategies.

For the original write-up, see [Let’s Go Gambling – Internal Working Explained](https://medium.com/@itsarnavsh/lets-go-gambling-2101c2a67dac).

## Quick start

### Docker Compose

```bash
cp .env.example .env       # optional; sane defaults are built in
docker compose up --build
```

- Core gRPC: internal `core:50051`
- Status API: http://localhost:8080/api/status
- Dashboard: http://localhost:5173

The default universe is ten liquid NSE symbols, the default source is Yahoo, and the default mode is paper trading. Redis is required for gateway signal transport.

### Local development

Requirements: Java 21, Go 1.25+, Node 20+, Python 3.10+, and Redis (for the full loop).

```bash
# terminal 1 — Redis
redis-server

# terminal 2 — Java core
cd core && ./gradlew :app:run

# terminal 3 — Go gateway (Yahoo data, status API on :8080)
cd gateway && go run .

# terminal 4 — dashboard (optional; polls the gateway)
cd monitoring/client && npm install && npm run dev
```

Useful shortcuts:

```bash
make test          # Java + Go + Python suites
make backtest      # real-data backtest with the default universe
make build         # installDist + gateway binary
make proto         # regenerate Go and Java stubs from gateway/proto/stockrec.proto
make up / down     # Docker Compose stack
```

## Status API

The gateway serves a read-only JSON snapshot from memory:

```bash
curl -s http://localhost:8080/api/status | jq
```

It includes:

- `portfolio`: initial cash, current cash, market value, equity, realised/unrealised/total PnL, fees, buy/sell and win/loss counts.
- `positions`: quantity, entry, live price, market value, PnL, stop/target, confidence, and hold metadata.
- `decisions`: the recent BUY/SELL attempts with fill/rejection status and reason.
- `closed_trades`: completed round trips with exit reason and holding time.
- `signals`: recent Redis signals before aggregation.
- `market`: the live watchlist with prices and aggregate/peak confidence.
- `feed`: data source, symbols, core/Redis connectivity, last roll/boot, and missing-ticker diagnostics.
- `stats` and `risk`: event counters and the active sizing/exit controls.

The dashboard defaults to `http://<current-host>:8080/api/status`. Override it with `?api=http://host:port/api/status` if the gateway is behind a different host or proxy.

## Data sources

Set `TYCHE_DATA_SOURCE` **on the gateway only**. The Java core no longer reads any market-data configuration.

| Value | Description | Requirements |
| --- | --- | --- |
| `yahoo` (default) | Public Yahoo chart/spark endpoints, `.NS` suffix applied to bare NSE tickers | Network access |
| `zerodha` / `kite` | Kite Connect v3 historical candles and batched LTP/OHLC quotes | API key, secret, daily access token |
| `fake` | Deterministic synthetic candles for offline development | None |

Yahoo's `/v7/finance/quote` endpoint now requires a crumb/cookie, so Tyche uses `/v8/finance/chart` for history and `/v8/finance/spark` for batched latest prices.

### Zerodha Kite Connect

The one-time login flow lives in the Go gateway alongside the data plane:

1. Create an app at <https://developers.kite.trade> and set a redirect URL.
2. Put `TYCHE_KITE_API_KEY` and `TYCHE_KITE_API_SECRET` in `.env` (Compose passes them to the gateway). For a local install, export them in the shell instead.
3. Print the login URL and open it in a browser:

   ```bash
   docker compose run --rm gateway kite-login-url
   # or, with a local install:
   gateway/bin/tyche-gateway kite-login-url
   ```

4. After login, copy the `request_token` from the redirect URL and exchange it:

   ```bash
   docker compose run --rm gateway kite-session <request_token>
   ```

5. Put the printed token in `.env` as `TYCHE_KITE_ACCESS_TOKEN`, set `TYCHE_DATA_SOURCE=zerodha`, and restart the stack. Kite access tokens expire daily.

## Backtesting

The standalone backtester has no dependency on the running stack:

```bash
python3 -m backtest \
  --symbols RELIANCE.NS,TCS.NS,HDFCBANK.NS,INFY.NS,ICICIBANK.NS \
  --period 2y \
  --strategy sma \
  --cash 100000 \
  --source yahoo
```

Strategies: `sma_cross`, `rsi_reversion`, `macd_trend`, `bollinger_reversion`, `donchian_breakout` (aliases such as `sma`, `rsi`, `macd`, `bollinger`, `donchian` are accepted). Override parameters with repeatable `--param key=value`.

Execution model:

- Signals are generated on a bar close and executed at the **next bar's open**.
- Cash is shared across symbols; position sizing respects `--max-positions` and `--position-fraction`.
- Fees and slippage are applied per side in basis points (default 5 bps each).
- Optional stop-loss, take-profit, and max-hold-bar exits; stops are checked before targets.
- Positions are liquidated at the final bar by default.

Other sources:

```bash
--source yfinance                      # optional convenience wrapper
--source csv --csv-dir /path/to/csvs    # <SYMBOL>.csv with OHLCV columns
```

Exports: `--json-out metrics.json`, `--trades-out trades.csv`, `--equity-out equity.csv`.
Data is cached under `~/.cache/tyche-backtest` (override with `--cache-dir`; an empty value disables caching).

## Configuration

The gateway reads market-data, execution, and status settings. The core reads only engine/Redis settings. See [`.env.example`](.env.example) for the full list. The most important ones:

| Variable | Default | Used by | Meaning |
| --- | --- | --- | --- |
| `TYCHE_DATA_SOURCE` | `yahoo` | gateway | `yahoo`, `zerodha`, or `fake` |
| `TYCHE_SYMBOLS` | 10 NSE names | gateway | Comma-separated universe (`.NS` suffixes supported) |
| `TYCHE_CORE_ADDR` | `localhost:50051` | gateway | Java gRPC address |
| `TYCHE_REDIS_ADDR` | `localhost:6379` | gateway | Redis address |
| `TYCHE_REDIS_HOST` / `_PORT` | `localhost` / `6379` | core | Redis address |
| `TYCHE_REDIS_ENABLED` | `true` | core | Allow Redis-less degraded mode |
| `TYCHE_SIGNAL_QUEUE` | `signal` | gateway + core | Redis list used for signals |
| `TYCHE_BOOT_CANDLES` | `300` | gateway | Historical bars requested per series |
| `TYCHE_ROLL_INTERVAL_MS` | `5000` | gateway | Latest-price poll interval |
| `TYCHE_STATUS_ADDR` | `:8080` | gateway | Status API listen address (Compose fixes this at `:8080`) |
| `TYCHE_STATUS_PORT` | `8080` | Compose | Host port mapped to the status API |
| `TYCHE_INITIAL_CASH` | `100000` | gateway | Paper account starting cash |
| `TYCHE_PAPER_FEE_BPS` | `0` | gateway | Paper fill fee in basis points |
| `TYCHE_MIN_CONFIDENCE` | `0.70` | gateway | Minimum signal confidence |
| `TYCHE_MAX_POSITIONS` | `5` | gateway | Concurrent paper positions |
| `TYCHE_MAX_POSITION_FRACTION` | `0.15` | gateway | Max fraction of equity per entry |
| `TYCHE_STOP_LOSS_PCT` / `TAKE_PROFIT_PCT` | `0.05` / `0.10` | gateway | Exit thresholds (0 disables) |
| `TYCHE_HOLD_MS` | `86400000` | gateway | Maximum holding time |
| `TYCHE_REBUY_COOLDOWN_MS` | `300000` | gateway | Per-symbol re-entry cooldown |
| `TYCHE_SIGNAL_COOLDOWN_MS` | `60000` | core | Per-strategy/symbol signal cooldown |
| `TYCHE_KITE_API_KEY` / `_SECRET` / `_ACCESS_TOKEN` | empty | gateway | Kite credentials |

Durations accept plain milliseconds or Go-style strings such as `5s`/`1m` in the gateway. Invalid values fall back to defaults.

## Project layout

```
core/       Java 21 engine: scheduling, cache, indicators, strategies, gRPC boundary
gateway/    Go data plane + execution: providers, gRPC client, risk, ledger, monitor, status API
backtest/   Python research: data loaders, strategies, engine, metrics, CLI, tests
monitoring/ Svelte dashboard (reads only the Go status API)
```

## Testing

```bash
# Java (JUnit 5)
cd core && ./gradlew :app:test

# Go (race detector + vet)
cd gateway && go vet ./... && go test -race ./...

# Python
python3 -m unittest discover -s backtest/tests -v

# Dashboard
cd monitoring/client && npm run check && npm run build
```

CI runs all suites on every push and pull request.

## Scope & non-goals

Tyche is primarily an engineering and systems exploration project. It is **not** intended to be:

- A production-ready trading platform
- A profit-optimized trading strategy
- A consumer-facing trading product

The emphasis is on architecture, concurrency, fault tolerance, and data-flow design. There is no real-money execution adapter, no compliance layer, and no guarantee that any strategy is profitable.

## Motivation

Most trading bots focus on strategies and profitability. Tyche intentionally flips that priority — treating trading as a stress test for distributed systems design.

The project explores real-time data pipelines, backpressure and flow control, fairness under concurrent load, and cross-language system boundaries.

## Project status

Tyche is an active research/engineering project and is evolving rapidly. APIs and internal behavior may change without notice.
