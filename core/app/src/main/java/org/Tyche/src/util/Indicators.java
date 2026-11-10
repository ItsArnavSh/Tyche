package org.Tyche.src.util;

import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;

/**
 * Pure, dependency-free technical indicator implementations.
 *
 * <p>All methods operate on <b>chronological</b> arrays (index 0 is the
 * oldest value, the last element is the newest).  Methods return {@code NaN}
 * while the warm-up window is incomplete rather than silently using partial
 * data.</p>
 */
public final class Indicators {

    private Indicators() {
    }

    public static double[] closes(List<Candle> candles) {
        double[] out = new double[candles.size()];
        for (int i = 0; i < candles.size(); i++) {
            out[i] = candles.get(i).close;
        }
        return out;
    }

    public static double[] highs(List<Candle> candles) {
        double[] out = new double[candles.size()];
        for (int i = 0; i < candles.size(); i++) {
            out[i] = candles.get(i).high;
        }
        return out;
    }

    public static double[] lows(List<Candle> candles) {
        double[] out = new double[candles.size()];
        for (int i = 0; i < candles.size(); i++) {
            out[i] = candles.get(i).low;
        }
        return out;
    }

    public static double[] volumes(List<Candle> candles) {
        double[] out = new double[candles.size()];
        for (int i = 0; i < candles.size(); i++) {
            out[i] = candles.get(i).volume;
        }
        return out;
    }

    // ── Moving averages ─────────────────────────────────────────────────

    public static double sma(double[] values, int period) {
        if (values == null || period <= 0 || values.length < period) {
            return Double.NaN;
        }
        double sum = 0;
        for (int i = values.length - period; i < values.length; i++) {
            sum += values[i];
        }
        return sum / period;
    }

    public static double[] smaSeries(double[] values, int period) {
        double[] out = new double[values == null ? 0 : values.length];
        java.util.Arrays.fill(out, Double.NaN);
        if (values == null || period <= 0 || values.length < period) {
            return out;
        }
        double sum = 0;
        for (int i = 0; i < values.length; i++) {
            sum += values[i];
            if (i >= period) {
                sum -= values[i - period];
            }
            if (i >= period - 1) {
                out[i] = sum / period;
            }
        }
        return out;
    }

    /** Exponential moving average seeded with the SMA of the first window. */
    public static double ema(double[] values, int period) {
        double[] series = emaSeries(values, period);
        return series.length == 0 ? Double.NaN : series[series.length - 1];
    }

    public static double[] emaSeries(double[] values, int period) {
        if (values == null) {
            return new double[0];
        }
        double[] out = new double[values.length];
        java.util.Arrays.fill(out, Double.NaN);
        if (period <= 0 || values.length < period) {
            return out;
        }
        double seed = 0;
        for (int i = 0; i < period; i++) {
            seed += values[i];
        }
        double alpha = 2.0 / (period + 1.0);
        double current = seed / period;
        out[period - 1] = current;
        for (int i = period; i < values.length; i++) {
            current = values[i] * alpha + current * (1 - alpha);
            out[i] = current;
        }
        return out;
    }

    /** EMA that tolerates leading NaNs (used by the MACD signal line). */
    public static double[] emaSparse(double[] values, int period) {
        double[] out = new double[values == null ? 0 : values.length];
        java.util.Arrays.fill(out, Double.NaN);
        if (values == null || period <= 0) {
            return out;
        }
        int first = 0;
        while (first < values.length && Double.isNaN(values[first])) {
            first++;
        }
        if (values.length - first < period) {
            return out;
        }
        double seed = 0;
        for (int i = first; i < first + period; i++) {
            seed += values[i];
        }
        double alpha = 2.0 / (period + 1.0);
        double current = seed / period;
        out[first + period - 1] = current;
        for (int i = first + period; i < values.length; i++) {
            current = values[i] * alpha + current * (1 - alpha);
            out[i] = current;
        }
        return out;
    }

    // ── Momentum ────────────────────────────────────────────────────────

    /** Wilder's RSI. */
    public static double rsi(double[] closes, int period) {
        double[] series = rsiSeries(closes, period);
        return series.length == 0 ? Double.NaN : series[series.length - 1];
    }

    public static double[] rsiSeries(double[] closes, int period) {
        double[] out = new double[closes == null ? 0 : closes.length];
        java.util.Arrays.fill(out, Double.NaN);
        if (closes == null || period <= 0 || closes.length <= period) {
            return out;
        }
        double gain = 0;
        double loss = 0;
        for (int i = 1; i <= period; i++) {
            double change = closes[i] - closes[i - 1];
            if (change >= 0) {
                gain += change;
            } else {
                loss -= change;
            }
        }
        double avgGain = gain / period;
        double avgLoss = loss / period;
        out[period] = rsiFromAverages(avgGain, avgLoss);
        for (int i = period + 1; i < closes.length; i++) {
            double change = closes[i] - closes[i - 1];
            double g = Math.max(change, 0);
            double l = Math.max(-change, 0);
            avgGain = (avgGain * (period - 1) + g) / period;
            avgLoss = (avgLoss * (period - 1) + l) / period;
            out[i] = rsiFromAverages(avgGain, avgLoss);
        }
        return out;
    }

    private static double rsiFromAverages(double avgGain, double avgLoss) {
        if (avgLoss == 0) {
            return avgGain == 0 ? 50 : 100;
        }
        double rs = avgGain / avgLoss;
        return 100 - (100 / (1 + rs));
    }

    public record MacdResult(double[] macd, double[] signal, double[] histogram) {
    }

    public static MacdResult macd(double[] closes, int fast, int slow, int signalPeriod) {
        if (closes == null || closes.length == 0) {
            return new MacdResult(new double[0], new double[0], new double[0]);
        }
        double[] emaFast = emaSeries(closes, fast);
        double[] emaSlow = emaSeries(closes, slow);
        double[] macdLine = new double[closes.length];
        java.util.Arrays.fill(macdLine, Double.NaN);
        for (int i = 0; i < closes.length; i++) {
            if (!Double.isNaN(emaFast[i]) && !Double.isNaN(emaSlow[i])) {
                macdLine[i] = emaFast[i] - emaSlow[i];
            }
        }
        double[] signalLine = emaSparse(macdLine, signalPeriod);
        double[] histogram = new double[closes.length];
        java.util.Arrays.fill(histogram, Double.NaN);
        for (int i = 0; i < closes.length; i++) {
            if (!Double.isNaN(macdLine[i]) && !Double.isNaN(signalLine[i])) {
                histogram[i] = macdLine[i] - signalLine[i];
            }
        }
        return new MacdResult(macdLine, signalLine, histogram);
    }

    public record BollingerResult(double middle, double upper, double lower) {
    }

    public static BollingerResult bollinger(double[] closes, int period, double multiplier) {
        double middle = sma(closes, period);
        if (Double.isNaN(middle)) {
            return new BollingerResult(Double.NaN, Double.NaN, Double.NaN);
        }
        double variance = 0;
        int start = closes.length - period;
        for (int i = start; i < closes.length; i++) {
            double diff = closes[i] - middle;
            variance += diff * diff;
        }
        double std = Math.sqrt(variance / period);
        return new BollingerResult(middle, middle + multiplier * std, middle - multiplier * std);
    }

    // ── Volatility ──────────────────────────────────────────────────────

    public static double[] trueRangeSeries(double[] highs, double[] lows, double[] closes) {
        int n = Math.min(highs.length, Math.min(lows.length, closes.length));
        double[] tr = new double[n];
        for (int i = 0; i < n; i++) {
            if (i == 0) {
                tr[i] = highs[i] - lows[i];
            } else {
                tr[i] = Math.max(highs[i] - lows[i],
                        Math.max(Math.abs(highs[i] - closes[i - 1]),
                                Math.abs(lows[i] - closes[i - 1])));
            }
        }
        return tr;
    }

    /** Wilder ATR aligned to the input array. */
    public static double[] atrSeries(double[] highs, double[] lows, double[] closes, int period) {
        double[] tr = trueRangeSeries(highs, lows, closes);
        double[] out = new double[tr.length];
        java.util.Arrays.fill(out, Double.NaN);
        if (period <= 0 || tr.length < period) {
            return out;
        }
        double sum = 0;
        for (int i = 0; i < period; i++) {
            sum += tr[i];
        }
        double atr = sum / period;
        out[period - 1] = atr;
        for (int i = period; i < tr.length; i++) {
            atr = (atr * (period - 1) + tr[i]) / period;
            out[i] = atr;
        }
        return out;
    }

    public static double atr(double[] highs, double[] lows, double[] closes, int period) {
        double[] series = atrSeries(highs, lows, closes, period);
        return series.length == 0 ? Double.NaN : series[series.length - 1];
    }

    // ── Channels & oscillators ──────────────────────────────────────────

    public record StochasticResult(double[] k, double[] d) {
    }

    public static StochasticResult stochastic(double[] highs, double[] lows, double[] closes,
            int kPeriod, int dPeriod) {
        int n = Math.min(highs.length, Math.min(lows.length, closes.length));
        double[] k = new double[n];
        java.util.Arrays.fill(k, Double.NaN);
        for (int i = kPeriod - 1; i < n; i++) {
            double hh = Double.NEGATIVE_INFINITY;
            double ll = Double.POSITIVE_INFINITY;
            for (int j = i - kPeriod + 1; j <= i; j++) {
                hh = Math.max(hh, highs[j]);
                ll = Math.min(ll, lows[j]);
            }
            k[i] = hh == ll ? 50 : ((closes[i] - ll) / (hh - ll)) * 100;
        }
        double[] d = smaSeriesNaNSafe(k, dPeriod);
        return new StochasticResult(k, d);
    }

    /** SMA that ignores leading NaNs. */
    public static double[] smaSeriesNaNSafe(double[] values, int period) {
        double[] out = new double[values == null ? 0 : values.length];
        java.util.Arrays.fill(out, Double.NaN);
        if (values == null || period <= 0) {
            return out;
        }
        int first = 0;
        while (first < values.length && Double.isNaN(values[first])) {
            first++;
        }
        if (values.length - first < period) {
            return out;
        }
        double sum = 0;
        for (int i = first; i < values.length; i++) {
            sum += values[i];
            if (i - first >= period) {
                sum -= values[i - period];
            }
            if (i - first >= period - 1) {
                out[i] = sum / period;
            }
        }
        return out;
    }

    public static double cci(double[] highs, double[] lows, double[] closes, int period) {
        if (closes.length < period) {
            return Double.NaN;
        }
        int start = closes.length - period;
        double[] typical = new double[period];
        double sum = 0;
        for (int i = 0; i < period; i++) {
            int idx = start + i;
            typical[i] = (highs[idx] + lows[idx] + closes[idx]) / 3.0;
            sum += typical[i];
        }
        double mean = sum / period;
        double meanDeviation = 0;
        for (double tp : typical) {
            meanDeviation += Math.abs(tp - mean);
        }
        meanDeviation /= period;
        if (meanDeviation == 0) {
            return 0;
        }
        return (typical[period - 1] - mean) / (0.015 * meanDeviation);
    }

    public record DonchianResult(double highest, double lowest) {
    }

    /**
     * Donchian channel of the previous {@code period} bars (the current bar is
     * excluded).  This makes breakout checks meaningful.
     */
    public static DonchianResult donchian(double[] highs, double[] lows, int period) {
        if (highs.length < period + 1) {
            return new DonchianResult(Double.NaN, Double.NaN);
        }
        double highest = Double.NEGATIVE_INFINITY;
        double lowest = Double.POSITIVE_INFINITY;
        int start = highs.length - period - 1;
        for (int i = start; i < highs.length - 1; i++) {
            highest = Math.max(highest, highs[i]);
            lowest = Math.min(lowest, lows[i]);
        }
        return new DonchianResult(highest, lowest);
    }

    public record SupertrendResult(double[] line, int[] direction, double[] atr) {
        public boolean bullish(int index) {
            return direction[index] > 0;
        }
    }

    /** Standard Supertrend implementation (ATR period + multiplier). */
    public static SupertrendResult supertrend(double[] highs, double[] lows, double[] closes,
            int period, double multiplier) {
        int n = closes.length;
        double[] atr = atrSeries(highs, lows, closes, period);
        double[] line = new double[n];
        int[] direction = new int[n];
        java.util.Arrays.fill(line, Double.NaN);
        java.util.Arrays.fill(direction, 0);

        double finalUpper = Double.NaN;
        double finalLower = Double.NaN;
        int dir = 1;
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(atr[i])) {
                continue;
            }
            double mid = (highs[i] + lows[i]) / 2.0;
            double basicUpper = mid + multiplier * atr[i];
            double basicLower = mid - multiplier * atr[i];

            if (i == 0 || Double.isNaN(finalUpper)) {
                finalUpper = basicUpper;
                finalLower = basicLower;
                dir = closes[i] >= mid ? 1 : -1;
            } else {
                finalUpper = (basicUpper < finalUpper || closes[i - 1] > finalUpper)
                        ? basicUpper
                        : finalUpper;
                finalLower = (basicLower > finalLower || closes[i - 1] < finalLower)
                        ? basicLower
                        : finalLower;

                if (dir > 0) {
                    dir = closes[i] >= finalLower ? 1 : -1;
                } else {
                    dir = closes[i] <= finalUpper ? -1 : 1;
                }
            }
            direction[i] = dir;
            line[i] = dir > 0 ? finalLower : finalUpper;
        }
        return new SupertrendResult(line, direction, atr);
    }

    // ── Volume ──────────────────────────────────────────────────────────

    public static double[] obvSeries(double[] closes, double[] volumes) {
        int n = Math.min(closes.length, volumes.length);
        double[] obv = new double[n];
        for (int i = 1; i < n; i++) {
            if (closes[i] > closes[i - 1]) {
                obv[i] = obv[i - 1] + volumes[i];
            } else if (closes[i] < closes[i - 1]) {
                obv[i] = obv[i - 1] - volumes[i];
            } else {
                obv[i] = obv[i - 1];
            }
        }
        return obv;
    }

    /** Rolling VWAP over the last {@code period} bars. */
    public static double vwap(double[] highs, double[] lows, double[] closes, double[] volumes, int period) {
        int n = Math.min(highs.length, Math.min(lows.length, Math.min(closes.length, volumes.length)));
        if (n == 0 || period <= 0) {
            return Double.NaN;
        }
        int start = Math.max(0, n - period);
        double tpv = 0;
        double vol = 0;
        for (int i = start; i < n; i++) {
            double typical = (highs[i] + lows[i] + closes[i]) / 3.0;
            tpv += typical * volumes[i];
            vol += volumes[i];
        }
        return vol == 0 ? Double.NaN : tpv / vol;
    }

    // ── Candlestick transforms ──────────────────────────────────────────

    public record HeikinAshiResult(double[] open, double[] high, double[] low, double[] close) {
    }

    public static HeikinAshiResult heikinAshi(double[] opens, double[] highs, double[] lows, double[] closes) {
        int n = Math.min(opens.length, Math.min(highs.length, Math.min(lows.length, closes.length)));
        double[] haOpen = new double[n];
        double[] haHigh = new double[n];
        double[] haLow = new double[n];
        double[] haClose = new double[n];
        for (int i = 0; i < n; i++) {
            haClose[i] = (opens[i] + highs[i] + lows[i] + closes[i]) / 4.0;
            if (i == 0) {
                haOpen[i] = (opens[i] + closes[i]) / 2.0;
            } else {
                haOpen[i] = (haOpen[i - 1] + haClose[i - 1]) / 2.0;
            }
            haHigh[i] = Math.max(highs[i], Math.max(haOpen[i], haClose[i]));
            haLow[i] = Math.min(lows[i], Math.min(haOpen[i], haClose[i]));
        }
        return new HeikinAshiResult(haOpen, haHigh, haLow, haClose);
    }
}
