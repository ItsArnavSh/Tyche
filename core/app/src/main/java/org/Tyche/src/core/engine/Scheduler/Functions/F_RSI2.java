package org.Tyche.src.core.engine.Scheduler.Functions;

import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.util.Indicators;

/** Short-term RSI(2) mean-reversion reclaim. */
public class F_RSI2 extends BaseFunction {
    private static final int PERIOD = 2;
    private static final double OVERSOLD = 10;

    @Override
    protected void evaluate(StartParams params) {
        List<Candle> candles = candles(params);
        if (candles.size() < PERIOD + 2) {
            return;
        }
        double[] closes = Indicators.closes(candles);
        double[] rsi = Indicators.rsiSeries(closes, PERIOD);
        int n = closes.length;
        double previous = rsi[n - 2];
        double current = rsi[n - 1];
        if (Double.isNaN(previous) || Double.isNaN(current)) {
            return;
        }
        params.repo.cache.set_var(params.context.ticker, "rsi2", current);
        if (previous < OVERSOLD && current >= OVERSOLD) {
            emit(params, 0.69, String.format("RSI2 exited oversold (%.1f -> %.1f)", previous, current));
        }
    }

    @Override
    public String family() {
        return "mean_reversion";
    }
}
