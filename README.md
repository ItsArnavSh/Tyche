# Tyche

> A distributed, real-time trading system focused on fairness, scalability, and low-latency engineering.

**Tyche** is named after the Greek goddess of fortune, embodying both good and bad luck. (Fun fact: the name was inspired during a God of War gaming session — it just made sense!)

Tyche is a fully autonomous, real-time, distributed, load-balanced trading bot. It streams **real Indian market data**, runs technical strategies in a Java core, publishes signals through Redis, and executes them in a thread-safe **paper-trading** ledger in the Go gateway. A standalone Python backtester lets you evaluate the same strategy families on real history before letting them run.

> **Paper trading only.** The Zerodha order endpoints are implemented and tested, but the autonomous decision path deliberately routes fills to an in-memory paper ledger. Do not point this at a live account without adding an explicit execution adapter, risk limits, and monitoring.

## Features

- **Real market data** — Yahoo Finance chart/spark endpoints by default; Zerodha Kite Connect v3 when credentials are supplied. The `fake` source remains available for offline demos.
- **Autonomous loop** — bootstrap history → roll updates → indicators/signals → Redis signal queue → confidence/risk checks → paper fills → stop-loss/take-profit/time exits. No human input required.
- **Cross-language boundary** — Go gateway fetches data and trades; Java core owns scheduling, indicator math, and strategy execution over gRPC (`SendBootSignal`, `SendRollData`, `SendKillSignal`).
- **Fair scheduling** — boot jobs pre-empt rolling jobs, rolling work is FIFO per priority, and a bounded per-ticker candle cache prevents memory growth.
- **Resilience** — HTTP retries, stale-ticker detection and re-boot, graceful shutdown, Redis-optional operation, and per-ticker failure isolation.
- **Backtesting** — real Yahoo/yfinance/CSV data, five strategy families with parameter overrides, portfolio cash sharing, fees/slippage/stops/targets, and JSON/CSV exports.
- **Tests** — JUnit 5 for the Java core, `go test -race` for the gateway, and `unittest` for the backtester.

## Architecture

![Internal design overview](assets/demo2.png)

The diagram is the original high-level design; the current data flow is:

1. **Go gateway (`gateway/`)** pulls candles from Yahoo or Kite and pushes them to the Java core over gRPC. It re-boots tickers the core reports as missing/stale.
2. **Java core (`core/`)** maintains a newest-first candle cache per `(symbol, candle size)`, runs fair scheduling across all jobs, executes the indicator functions, and publishes `Signal` JSON to Redis. It can also fetch history itself to self-heal stale tickers.
3. **Go gateway decision/monitor loop** consumes signals, applies confidence/position/sizing/cooldown checks, executes paper trades, and manages stop-loss, take-profit, max-hold, and signal-based exits.
4. **Monitoring (`monitoring/`)** exposes a small Deno metrics service and a Svelte dashboard.

Communication is event-driven and designed for horizontal scalability. Fairness and backpressure live in the system, not in individual strategies.

For the original write-up, see [Let’s Go Gambling – Internal Working Explained](https://medium.com/@itsarnavsh/lets-go-gambling-2101c2a67dac).

## Quick start

### Docker Compose

```bash
cp .env.example .env       # optional; sane defaults are built in
docker compose up --build
```

- Core gRPC: internal `core:50051`
- Monitoring API: http://localhost:8082
- Dashboard: http://localhost:5173

The default universe is ten liquid NSE symbols, the default source is Yahoo, and the default mode is paper trading. Redis is optional for the core but required for gateway signal transport.

### Local development

Requirements: Java 21, Go 1.25+, Python 3.10+, and Redis (for the full loop).

```bash
# terminal 1 — Redis
redis-server

# terminal 2 — Java core
cd core && ./gradlew :app:run

# terminal 3 — Go gateway (Yahoo data by default)
cd gateway && go run .
```

Useful shortcuts:

```bash
make test          # Java + Go + Python suites
make backtest      # real-data backtest with the default universe
make build         # installDist + gateway binary
make up / down     # Docker Compose stack
```

## Data sources

Set `TYCHE_DATA_SOURCE` on both the core and the gateway.

| Value | Description | Requirements |
| --- | --- | --- |
| `yahoo` (default) | Public Yahoo chart/spark endpoints, `.NS` suffix applied to bare NSE tickers | Network access |
| `zerodha` / `kite` | Kite Connect v3 historical candles and batched LTP/OHLC quotes | API key, secret, daily access token |
| `fake` | Deterministic synthetic candles for offline development | None |

Yahoo's `/v7/finance/quote` endpoint now requires a crumb/cookie, so Tyche uses `/v8/finance/chart` for history and `/v8/finance/spark` for batched latest prices.

### Zerodha Kite Connect

1. Create an app at <https://developers.kite.trade> and set a redirect URL.
2. Export `TYCHE_KITE_API_KEY` and `TYCHE_KITE_API_SECRET`.
3. Print the login URL and open it in a browser:

   ```bash
   docker compose run --rm core kite-login-url
   # or, with a local install:
   core/app/build/install/app/bin/app kite-login-url
   ```

4. After login, copy the `request_token` from the redirect URL and exchange it:

   ```bash
   docker compose run --rm core kite-session <request_token>
   ```

5. Export the printed token as `TYCHE_KITE_ACCESS_TOKEN`, set `TYCHE_DATA_SOURCE=zerodha`, and restart both services. Kite access tokens expire daily.

The Java producer also exposes profile, margins, positions, order placement/cancellation, and instrument-token resolution through `ZerodhaAPI`; the Go gateway mirrors the data path for streaming. The autonomous loop still uses the paper ledger.

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

Both services read environment variables; see [`.env.example`](.env.example) for the full list. The most important ones:

| Variable | Default | Used by | Meaning |
| --- | --- | --- | --- |
| `TYCHE_DATA_SOURCE` | `yahoo` | both | `yahoo`, `zerodha`, or `fake` |
| `TYCHE_SYMBOLS` | 10 NSE names | both | Comma-separated universe (`.NS` suffixes supported) |
| `TYCHE_CORE_ADDR` | `localhost:50051` | gateway | Java gRPC address |
| `TYCHE_REDIS_ADDR` | `localhost:6379` | gateway | Redis address |
| `TYCHE_REDIS_HOST` / `_PORT` | `localhost` / `6379` | core | Redis address |
| `TYCHE_REDIS_ENABLED` | `true` | core | Allow Redis-less degraded mode |
| `TYCHE_SIGNAL_QUEUE` | `signal` | both | Redis list used for signals |
| `TYCHE_BOOT_CANDLES` (core alias: `TYCHE_BOOT_CANDLE_COUNT`) | `300` | both | Historical bars requested per series |
| `TYCHE_ROLL_INTERVAL_MS` | `5000` | gateway | Latest-price poll interval |
| `TYCHE_INITIAL_CASH` | `100000` | gateway | Paper account starting cash |
| `TYCHE_PAPER_FEE_BPS` | `0` | gateway | Paper fill fee in basis points |
| `TYCHE_MIN_CONFIDENCE` | `0.70` | gateway | Minimum signal confidence |
| `TYCHE_MAX_POSITIONS` | `5` | gateway | Concurrent paper positions |
| `TYCHE_MAX_POSITION_FRACTION` | `0.15` | gateway | Max fraction of equity per entry |
| `TYCHE_STOP_LOSS_PCT` / `TAKE_PROFIT_PCT` | `0.05` / `0.10` | gateway | Exit thresholds (0 disables) |
| `TYCHE_HOLD_MS` | `86400000` | gateway | Maximum holding time |
| `TYCHE_REBUY_COOLDOWN_MS` | `300000` | gateway | Per-symbol re-entry cooldown |
| `TYCHE_SIGNAL_COOLDOWN_MS` | `60000` | core | Per-strategy/symbol signal cooldown |
| `TYCHE_KITE_API_KEY` / `_SECRET` / `_ACCESS_TOKEN` | empty | both | Kite credentials |

Durations accept plain milliseconds or Go-style strings such as `5s`/`1m` in the gateway. Invalid values fall back to defaults.

## Project layout

```
core/       Java 21 engine: scheduling, cache, indicators, strategies, providers, gRPC
gateway/    Go ingress/execution: providers, gRPC client, risk, paper ledger, monitor
backtest/   Python research: data loaders, strategies, engine, metrics, CLI, tests
monitoring/ Deno metrics API + Svelte dashboard
```

## Testing

```bash
# Java (JUnit 5, 80+ tests)
cd core && ./gradlew :app:test

# Go (race detector + vet)
cd gateway && go vet ./... && go test -race ./...

# Python
python3 -m unittest discover -s backtest/tests -v
```

CI runs all three suites on every push and pull request.

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
