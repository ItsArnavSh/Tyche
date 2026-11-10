package org.Tyche.src.core.engine.Scheduler.Functions;

import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.util.Indicators;

/** Rolling VWAP reclaim. */
public class F_VWAP extends BaseFunction {
    private static final int PERIOD = 50;

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
        if (candles.size() < PERIOD + 1) {
            return;
        }
        double[] highs = Indicators.highs(candles);
        double[] lows = Indicators.lows(candles);
        double[] closes = Indicators.closes(candles);
        double[] volumes = Indicators.volumes(candles);
        double currentVwap = Indicators.vwap(highs, lows, closes, volumes, PERIOD);
        double previousVwap = Indicators.vwap(
                java.util.Arrays.copyOf(highs, highs.length - 1),
                java.util.Arrays.copyOf(lows, lows.length - 1),
                java.util.Arrays.copyOf(closes, closes.length - 1),
                java.util.Arrays.copyOf(volumes, volumes.length - 1),
                PERIOD);
        if (Double.isNaN(currentVwap) || Double.isNaN(previousVwap)) {
            return;
        }
        int n = closes.length;
        params.repo.cache.set_var(params.context.ticker, "vwap", currentVwap);
        if (closes[n - 2] <= previousVwap && closes[n - 1] > currentVwap) {
            emit(params, 0.68, String.format("price reclaimed VWAP %.2f", currentVwap));
        }
    }
}
