package org.Tyche.src.core.engine.Scheduler.Functions;

import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.util.Indicators;

/**
 * Bullish RSI divergence: price makes a lower low while RSI makes a higher
 * low.  The two swing lows must be at least three bars apart.
 */
public class F_RSI_Divergence extends BaseFunction {
    private static final int RSI_PERIOD = 14;
    private static final int LOOKBACK = 30;

    @Override
    public void Boot(StartParams params) {
        evaluate(params);
    }

    @Override
    public void Roll(StartParams params) {
        evaluate(params);
    }

    private void evaluate(StartParams params) {
        List<Candle> candles = candles(params);
        if (candles.size() < LOOKBACK + RSI_PERIOD) {
            return;
        }
        double[] closes = Indicators.closes(candles);
        double[] rsi = Indicators.rsiSeries(closes, RSI_PERIOD);
        int n = closes.length;

        // Find the most recent swing low in the last LOOKBACK bars.
        int recentStart = n - 1;
        int priorStart = n - LOOKBACK;
        int priorEnd = n - 6;
        int recentLowIdx = recentStart;
        for (int i = recentStart; i >= n - 5; i--) {
            if (closes[i] < closes[recentLowIdx]) {
                recentLowIdx = i;
            }
        }
        int priorLowIdx = -1;
        for (int i = priorStart; i <= priorEnd; i++) {
            if (Double.isNaN(rsi[i]) || Double.isNaN(rsi[recentLowIdx])) {
                continue;
            }
            if (priorLowIdx == -1 || closes[i] < closes[priorLowIdx]) {
                priorLowIdx = i;
            }
        }
        if (priorLowIdx == -1 || recentLowIdx - priorLowIdx < 3) {
            return;
        }
        boolean lowerLow = closes[recentLowIdx] < closes[priorLowIdx];
        boolean higherRsiLow = rsi[recentLowIdx] > rsi[priorLowIdx] + 2.0;
        if (lowerLow && higherRsiLow && rsi[recentLowIdx] < 45) {
            emit(params, 0.76, String.format("bullish RSI divergence (price %.2f < %.2f, RSI %.1f > %.1f)",
                    closes[recentLowIdx], closes[priorLowIdx], rsi[recentLowIdx], rsi[priorLowIdx]));
        }
    }
}
