package org.Tyche.src.core.engine.Scheduler.Functions;

import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.util.Indicators;

/** RSI momentum regime shift above the 50 midline. */
public class F_RSI50 extends BaseFunction {
    private static final int PERIOD = 14;
    private static final double MIDLINE = 50;

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
        params.repo.cache.set_var(params.context.ticker, "rsi", current);
        if (previous < MIDLINE && current >= MIDLINE) {
            emit(params, 0.71, String.format("RSI%d crossed above %.0f (%.1f -> %.1f)",
                    PERIOD, MIDLINE, previous, current));
        }
    }

    @Override
    public String family() {
        return "momentum";
    }
}
