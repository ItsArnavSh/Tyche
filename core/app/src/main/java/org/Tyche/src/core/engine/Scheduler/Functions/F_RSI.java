package org.Tyche.src.core.engine.Scheduler.Functions;

import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.util.Indicators;

/** Wilder RSI mean-reversion: buy when RSI exits oversold territory. */
public class F_RSI extends BaseFunction {
    private static final int PERIOD = 14;
    private static final double OVERSOLD = 30;

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
        if (candles.size() < PERIOD + 2) {
            return;
        }
        double[] closes = Indicators.closes(candles);
        double[] rsi = Indicators.rsiSeries(closes, PERIOD);
        int n = closes.length;
        double prev = rsi[n - 2];
        double current = rsi[n - 1];
        if (Double.isNaN(prev) || Double.isNaN(current)) {
            return;
        }
        params.repo.cache.set_var(params.context.ticker, "rsi", current);
        if (prev < OVERSOLD && current >= OVERSOLD) {
            emit(params, 0.78, String.format("RSI%d exited oversold (%.1f -> %.1f)", PERIOD, prev, current));
        }
    }
}
