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

    // ── Momentum / trend extensions ─────────────────────────────────────

    public static double[] medianPriceSeries(double[] highs, double[] lows) {
        int n = Math.min(highs == null ? 0 : highs.length, lows == null ? 0 : lows.length);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = (highs[i] + lows[i]) / 2.0;
        }
        return out;
    }

    /** Rolling z-score of the latest value versus the prior {@code period} bars. */
    public static double[] zScoreSeries(double[] values, int period) {
        double[] out = new double[values == null ? 0 : values.length];
        java.util.Arrays.fill(out, Double.NaN);
        if (values == null || period <= 0 || values.length < period) {
            return out;
        }
        for (int i = period - 1; i < values.length; i++) {
            double sum = 0;
            for (int j = i - period + 1; j <= i; j++) {
                sum += values[j];
            }
            double mean = sum / period;
            double variance = 0;
            for (int j = i - period + 1; j <= i; j++) {
                double diff = values[j] - mean;
                variance += diff * diff;
            }
            double std = Math.sqrt(variance / period);
            out[i] = std == 0 ? 0 : (values[i] - mean) / std;
        }
        return out;
    }

    public record DmiResult(double[] adx, double[] plusDi, double[] minusDi) {
    }

    /** Wilder's directional movement index with ADX smoothing. */
    public static DmiResult dmi(double[] highs, double[] lows, double[] closes, int period) {
        int n = Math.min(highs == null ? 0 : highs.length,
                Math.min(lows == null ? 0 : lows.length, closes == null ? 0 : closes.length));
        double[] adx = new double[n];
        double[] plusDi = new double[n];
        double[] minusDi = new double[n];
        java.util.Arrays.fill(adx, Double.NaN);
        java.util.Arrays.fill(plusDi, Double.NaN);
        java.util.Arrays.fill(minusDi, Double.NaN);
        if (n < period + 2 || period <= 0) {
            return new DmiResult(adx, plusDi, minusDi);
        }

        double[] tr = trueRangeSeries(highs, lows, closes);
        double[] plusDm = new double[n];
        double[] minusDm = new double[n];
        for (int i = 1; i < n; i++) {
            double up = highs[i] - highs[i - 1];
            double down = lows[i - 1] - lows[i];
            if (up > down && up > 0) {
                plusDm[i] = up;
            }
            if (down > up && down > 0) {
                minusDm[i] = down;
            }
        }

        double trSum = 0;
        double plusSum = 0;
        double minusSum = 0;
        for (int i = 1; i <= period; i++) {
            trSum += tr[i];
            plusSum += plusDm[i];
            minusSum += minusDm[i];
        }

        double[] dx = new double[n];
        java.util.Arrays.fill(dx, Double.NaN);
        setDmiValues(period, trSum, plusSum, minusSum, plusDi, minusDi, dx);
        for (int i = period + 1; i < n; i++) {
            trSum = trSum - trSum / period + tr[i];
            plusSum = plusSum - plusSum / period + plusDm[i];
            minusSum = minusSum - minusSum / period + minusDm[i];
            setDmiValues(i, trSum, plusSum, minusSum, plusDi, minusDi, dx);
        }

        double dxSum = 0;
        int dxCount = 0;
        double adxValue = Double.NaN;
        for (int i = period; i < n; i++) {
            if (Double.isNaN(dx[i])) {
                continue;
            }
            if (dxCount < period) {
                dxSum += dx[i];
                dxCount++;
                if (dxCount == period) {
                    adxValue = dxSum / period;
                    adx[i] = adxValue;
                }
            } else {
                adxValue = (adxValue * (period - 1) + dx[i]) / period;
                adx[i] = adxValue;
            }
        }
        return new DmiResult(adx, plusDi, minusDi);
    }

    private static void setDmiValues(int index, double trSum, double plusSum, double minusSum,
            double[] plusDi, double[] minusDi, double[] dx) {
        double plus = trSum == 0 ? 0 : 100.0 * plusSum / trSum;
        double minus = trSum == 0 ? 0 : 100.0 * minusSum / trSum;
        plusDi[index] = plus;
        minusDi[index] = minus;
        double denominator = plus + minus;
        dx[index] = denominator == 0 ? 0 : 100.0 * Math.abs(plus - minus) / denominator;
    }

    public record AroonResult(double[] up, double[] down) {
    }

    /** Aroon up/down over a window of {@code period + 1} bars. */
    public static AroonResult aroon(double[] highs, double[] lows, int period) {
        int n = Math.min(highs == null ? 0 : highs.length, lows == null ? 0 : lows.length);
        double[] up = new double[n];
        double[] down = new double[n];
        java.util.Arrays.fill(up, Double.NaN);
        java.util.Arrays.fill(down, Double.NaN);
        if (period <= 0 || n < period + 1) {
            return new AroonResult(up, down);
        }
        for (int i = period; i < n; i++) {
            int start = i - period;
            int highIndex = start;
            int lowIndex = start;
            for (int j = start + 1; j <= i; j++) {
                if (highs[j] >= highs[highIndex]) {
                    highIndex = j;
                }
                if (lows[j] <= lows[lowIndex]) {
                    lowIndex = j;
                }
            }
            up[i] = 100.0 * (period - (i - highIndex)) / period;
            down[i] = 100.0 * (period - (i - lowIndex)) / period;
        }
        return new AroonResult(up, down);
    }

    public record ParabolicSarResult(double[] sar, int[] direction) {
    }

    /** Parabolic SAR with an acceleration factor that starts at {@code step}. */
    public static ParabolicSarResult parabolicSar(double[] highs, double[] lows,
            double step, double maxStep) {
        int n = Math.min(highs == null ? 0 : highs.length, lows == null ? 0 : lows.length);
        double[] sar = new double[n];
        int[] direction = new int[n];
        if (n < 2 || step <= 0 || maxStep < step) {
            return new ParabolicSarResult(sar, direction);
        }

        int dir = highs[1] >= highs[0] ? 1 : -1;
        double af = step;
        double extreme = dir > 0 ? highs[0] : lows[0];
        sar[0] = dir > 0 ? lows[0] : highs[0];
        direction[0] = dir;

        for (int i = 1; i < n; i++) {
            double next = sar[i - 1] + af * (extreme - sar[i - 1]);
            if (dir > 0) {
                next = Math.min(next, lows[i - 1]);
                if (i >= 2) {
                    next = Math.min(next, lows[i - 2]);
                }
            } else {
                next = Math.max(next, highs[i - 1]);
                if (i >= 2) {
                    next = Math.max(next, highs[i - 2]);
                }
            }

            if (dir > 0 && lows[i] < next) {
                dir = -1;
                sar[i] = extreme;
                extreme = lows[i];
                af = step;
            } else if (dir < 0 && highs[i] > next) {
                dir = 1;
                sar[i] = extreme;
                extreme = highs[i];
                af = step;
            } else {
                sar[i] = next;
                if (dir > 0) {
                    extreme = Math.max(extreme, highs[i]);
                } else {
                    extreme = Math.min(extreme, lows[i]);
                }
                af = Math.min(maxStep, af + step);
            }
            direction[i] = dir;
        }
        return new ParabolicSarResult(sar, direction);
    }

    public record IchimokuResult(double[] tenkan, double[] kijun,
            double[] senkouA, double[] senkouB) {
    }

    /**
     * Ichimoku components at each bar.  The cloud is not displaced forward;
     * this makes the values usable as a pure current-bar filter.
     */
    public static IchimokuResult ichimoku(double[] highs, double[] lows,
            int tenkanPeriod, int kijunPeriod, int senkouBPeriod) {
        int n = Math.min(highs == null ? 0 : highs.length, lows == null ? 0 : lows.length);
        double[] tenkan = new double[n];
        double[] kijun = new double[n];
        double[] senkouA = new double[n];
        double[] senkouB = new double[n];
        java.util.Arrays.fill(tenkan, Double.NaN);
        java.util.Arrays.fill(kijun, Double.NaN);
        java.util.Arrays.fill(senkouA, Double.NaN);
        java.util.Arrays.fill(senkouB, Double.NaN);
        if (tenkanPeriod <= 0 || kijunPeriod <= 0 || senkouBPeriod <= 0) {
            return new IchimokuResult(tenkan, kijun, senkouA, senkouB);
        }
        for (int i = 0; i < n; i++) {
            if (i >= tenkanPeriod - 1) {
                tenkan[i] = (highest(highs, i - tenkanPeriod + 1, i)
                        + lowest(lows, i - tenkanPeriod + 1, i)) / 2.0;
            }
            if (i >= kijunPeriod - 1) {
                kijun[i] = (highest(highs, i - kijunPeriod + 1, i)
                        + lowest(lows, i - kijunPeriod + 1, i)) / 2.0;
            }
            if (!Double.isNaN(tenkan[i]) && !Double.isNaN(kijun[i])) {
                senkouA[i] = (tenkan[i] + kijun[i]) / 2.0;
            }
            if (i >= senkouBPeriod - 1) {
                senkouB[i] = (highest(highs, i - senkouBPeriod + 1, i)
                        + lowest(lows, i - senkouBPeriod + 1, i)) / 2.0;
            }
        }
        return new IchimokuResult(tenkan, kijun, senkouA, senkouB);
    }

    /** Stochastic oscillator calculated on the RSI series. */
    public static StochasticResult stochasticRsi(double[] closes, int rsiPeriod,
            int stochPeriod, int dPeriod) {
        double[] rsi = rsiSeries(closes, rsiPeriod);
        int n = rsi.length;
        double[] k = new double[n];
        java.util.Arrays.fill(k, Double.NaN);
        if (rsiPeriod <= 0 || stochPeriod <= 0 || dPeriod <= 0 || n < rsiPeriod + stochPeriod) {
            double[] d = new double[n];
            java.util.Arrays.fill(d, Double.NaN);
            return new StochasticResult(k, d);
        }
        for (int i = rsiPeriod + stochPeriod - 1; i < n; i++) {
            double high = Double.NEGATIVE_INFINITY;
            double low = Double.POSITIVE_INFINITY;
            boolean valid = true;
            for (int j = i - stochPeriod + 1; j <= i; j++) {
                if (Double.isNaN(rsi[j])) {
                    valid = false;
                    break;
                }
                high = Math.max(high, rsi[j]);
                low = Math.min(low, rsi[j]);
            }
            if (!valid) {
                continue;
            }
            k[i] = high == low ? 50 : (rsi[i] - low) / (high - low) * 100.0;
        }
        double[] d = smaSeriesNaNSafe(k, dPeriod);
        return new StochasticResult(k, d);
    }

    /** Williams %R. */
    public static double[] williamsR(double[] highs, double[] lows, double[] closes, int period) {
        int n = Math.min(highs == null ? 0 : highs.length,
                Math.min(lows == null ? 0 : lows.length, closes == null ? 0 : closes.length));
        double[] out = new double[n];
        java.util.Arrays.fill(out, Double.NaN);
        if (period <= 0 || n < period) {
            return out;
        }
        for (int i = period - 1; i < n; i++) {
            double high = highest(highs, i - period + 1, i);
            double low = lowest(lows, i - period + 1, i);
            double range = high - low;
            out[i] = range == 0 ? -50 : -100.0 * (high - closes[i]) / range;
        }
        return out;
    }

    /** Money Flow Index. */
    public static double[] mfi(double[] highs, double[] lows, double[] closes,
            double[] volumes, int period) {
        int n = Math.min(highs == null ? 0 : highs.length,
                Math.min(lows == null ? 0 : lows.length,
                        Math.min(closes == null ? 0 : closes.length,
                                volumes == null ? 0 : volumes.length)));
        double[] out = new double[n];
        java.util.Arrays.fill(out, Double.NaN);
        if (period <= 0 || n < period + 1) {
            return out;
        }
        double[] typical = new double[n];
        for (int i = 0; i < n; i++) {
            typical[i] = (highs[i] + lows[i] + closes[i]) / 3.0;
        }
        for (int i = period; i < n; i++) {
            double positive = 0;
            double negative = 0;
            for (int j = i - period + 1; j <= i; j++) {
                double flow = typical[j] * volumes[j];
                if (typical[j] > typical[j - 1]) {
                    positive += flow;
                } else if (typical[j] < typical[j - 1]) {
                    negative += flow;
                }
            }
            if (positive == 0 && negative == 0) {
                out[i] = 50;
            } else if (negative == 0) {
                out[i] = 100;
            } else if (positive == 0) {
                out[i] = 0;
            } else {
                out[i] = 100.0 - 100.0 / (1.0 + positive / negative);
            }
        }
        return out;
    }

    /** Awesome Oscillator: SMA(median price, fast) - SMA(median price, slow). */
    public static double[] awesomeOscillator(double[] highs, double[] lows, int fast, int slow) {
        double[] median = medianPriceSeries(highs, lows);
        double[] fastSma = smaSeries(median, fast);
        double[] slowSma = smaSeries(median, slow);
        double[] out = new double[median.length];
        java.util.Arrays.fill(out, Double.NaN);
        for (int i = 0; i < median.length; i++) {
            if (!Double.isNaN(fastSma[i]) && !Double.isNaN(slowSma[i])) {
                out[i] = fastSma[i] - slowSma[i];
            }
        }
        return out;
    }

    public record KeltnerResult(double middle, double upper, double lower) {
    }

    /** Keltner channel using an EMA middle line and ATR envelope. */
    public static KeltnerResult keltner(double[] highs, double[] lows, double[] closes,
            int emaPeriod, int atrPeriod, double multiplier) {
        int n = Math.min(highs == null ? 0 : highs.length,
                Math.min(lows == null ? 0 : lows.length, closes == null ? 0 : closes.length));
        if (n == 0 || emaPeriod <= 0 || atrPeriod <= 0 || multiplier <= 0) {
            return new KeltnerResult(Double.NaN, Double.NaN, Double.NaN);
        }
        double[] ema = emaSeries(closes, emaPeriod);
        double[] atr = atrSeries(highs, lows, closes, atrPeriod);
        double middle = ema[n - 1];
        double band = atr[n - 1];
        if (Double.isNaN(middle) || Double.isNaN(band)) {
            return new KeltnerResult(Double.NaN, Double.NaN, Double.NaN);
        }
        return new KeltnerResult(middle, middle + multiplier * band, middle - multiplier * band);
    }

    public record PivotResult(double pivot, double support1, double resistance1) {
    }

    /** Classic floor-trader pivot levels from one completed bar. */
    public static PivotResult pivot(double high, double low, double close) {
        double pivot = (high + low + close) / 3.0;
        return new PivotResult(pivot, 2 * pivot - high, 2 * pivot - low);
    }

    /** Chaikin Money Flow. */
    public static double[] cmfSeries(double[] highs, double[] lows, double[] closes,
            double[] volumes, int period) {
        int n = Math.min(highs == null ? 0 : highs.length,
                Math.min(lows == null ? 0 : lows.length,
                        Math.min(closes == null ? 0 : closes.length,
                                volumes == null ? 0 : volumes.length)));
        double[] out = new double[n];
        java.util.Arrays.fill(out, Double.NaN);
        if (period <= 0 || n < period) {
            return out;
        }
        for (int i = period - 1; i < n; i++) {
            double moneyFlowVolume = 0;
            double volumeSum = 0;
            for (int j = i - period + 1; j <= i; j++) {
                double range = highs[j] - lows[j];
                double multiplier = range == 0 ? 0
                        : ((closes[j] - lows[j]) - (highs[j] - closes[j])) / range;
                moneyFlowVolume += multiplier * volumes[j];
                volumeSum += volumes[j];
            }
            out[i] = volumeSum == 0 ? 0 : moneyFlowVolume / volumeSum;
        }
        return out;
    }

    /** EMA-smoothed force index. */
    public static double[] forceIndexSeries(double[] closes, double[] volumes, int period) {
        int n = Math.min(closes == null ? 0 : closes.length,
                volumes == null ? 0 : volumes.length);
        double[] raw = new double[n];
        for (int i = 1; i < n; i++) {
            raw[i] = (closes[i] - closes[i - 1]) * volumes[i];
        }
        return emaSeries(raw, period);
    }

    private static double highest(double[] values, int start, int end) {
        double high = Double.NEGATIVE_INFINITY;
        for (int i = start; i <= end; i++) {
            high = Math.max(high, values[i]);
        }
        return high;
    }

    private static double lowest(double[] values, int start, int end) {
        double low = Double.POSITIVE_INFINITY;
        for (int i = start; i <= end; i++) {
            low = Math.min(low, values[i]);
        }
        return low;
    }
}
