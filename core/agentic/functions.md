# Tyche Trading Functions Collection

A set of modular Java trading strategy functions for the **Tyche** engine.

---

## Project Structure

All functions are located in:
`package org.Tyche.src.core.engine.Scheduler.Functions;`

Each class extends `BaseFunction` and implements two core methods:

- **`Boot(StartParams params)`** — Called once on strategy initialization
- **`Roll(StartParams params)`** — Called on every new candle update

---

## How to Generate New Functions

### Best Prompt Template for Claude / Grok / GPT

Copy and paste this template:

```text
Create a complete Java file named F_{STRATEGY_NAME}.java

Follow the exact same style, structure, logging format, error handling, and coding conventions as F_SMA.java.

Strategy Description: {Write clear description here}

Requirements:
- Use the same package declaration and imports
- Define periods as static final constants at the top
- Implement both Boot() and Roll() methods properly
- Use params.repo.cache for storing indicator values
- Send buy Signal only on bullish conditions (for now)
- Add clear logging with prefix [F_STRATEGY_NAME Boot/Roll]
- Include defensive checks (null, NaN, insufficient data, etc.)
- Use System.out.println for logging and System.err for errors
```

---

### Examples of Good Strategy Requests

- `F_TripleEMA` → "Triple EMA crossover (9, 21, 55). Buy when fast > medium > slow"
- `F_Supertrend` → "Supertrend indicator with period 10 and multiplier 3"
- `F_ Ichimoku` → "Simplified Ichimoku Cloud bullish signal (price above cloud)"
- `F_PivotPoints` → "Classic Pivot Points with buy on bounce from support"
- `F_VolumeProfile` → "High volume node breakout strategy"

---

## Current Available Functions

| File                        | Strategy                     | Type                  |
|----------------------------|------------------------------|-----------------------|
| F_SMA.java                 | Simple Moving Average        | Trend                 |
| F_EMA.java                 | Exponential Moving Average   | Trend                 |
| F_DoubleSMA.java           | Fast/Slow SMA Crossover      | Trend                 |
| F_MACD.java                | MACD Line & Signal           | Momentum              |
| F_RSI.java                 | Relative Strength Index      | Oscillator            |
| F_RSI_Divergence.java      | RSI + Basic Divergence       | Oscillator            |
| F_Stochastic.java          | Stochastic Oscillator        | Oscillator            |
| F_CCI.java                 | Commodity Channel Index      | Oscillator            |
| F_BollingerBands.java      | Bollinger Bands              | Volatility            |
| F_ATR.java                 | Average True Range Breakout  | Volatility            |
| F_DonchianChannel.java     | Donchian Channel Breakout    | Breakout              |
| F_Supertrend.java          | Supertrend                   | Trend/Volatility      |
| F_ParabolicSAR.java        | Parabolic SAR                | Trend                 |
| F_VWAP.java                | Volume Weighted Average Price| Intraday              |
| F_HeikinAshi.java          | Heikin Ashi Candles          | Price Action          |
| F_OBV.java                 | On-Balance Volume            | Volume                |

---

## Customization Tips

1. **Add Sell Signals** — Check opposite condition and send negative signal strength
2. **Improve Boot()** — Calculate proper initial indicator values from history
3. **Add Filters** — Volume filter, time filter, ADX trend filter, etc.
4. **Change Signal Strength** — Higher number = stronger signal (e.g., 90 vs 70)

---

## Folder Setup

Just place all `F_*.java` files inside:
`src/main/java/org/Tyche/src/core/engine/Scheduler/Functions/`

