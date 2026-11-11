package org.Tyche.src.core.engine.Scheduler.Functions;

import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.util.Indicators;

/** Williams %R reclaiming the oversold threshold. */
public class F_WilliamsR extends BaseFunction {
    private static final int PERIOD = 14;
    private static final double OVERSOLD = -80;

    @Override
    protected void evaluate(StartParams params) {
        List<Candle> candles = candles(params);
        if (candles.size() < PERIOD + 1) {
            return;
        }
        double[] highs = Indicators.highs(candles);
        double[] lows = Indicators.lows(candles);
        double[] closes = Indicators.closes(candles);
        double[] williams = Indicators.williamsR(highs, lows, closes, PERIOD);
        int n = closes.length;
        double previous = williams[n - 2];
        double current = williams[n - 1];
        if (Double.isNaN(previous) || Double.isNaN(current)) {
            return;
        }
        params.repo.cache.set_var(params.context.ticker, "williams_r", current);
        if (previous <= OVERSOLD && current > OVERSOLD) {
            emit(params, 0.72, String.format("Williams %%R%d exited oversold (%.1f -> %.1f)",
                    PERIOD, previous, current));
        }
    }

    @Override
    public String family() {
        return "momentum";
    }
}
