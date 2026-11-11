package org.Tyche.src.core.engine.Scheduler.Functions;

import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.util.Indicators;

/** Chaikin Money Flow turning positive. */
public class F_CMF extends BaseFunction {
    private static final int PERIOD = 20;

    @Override
    protected void evaluate(StartParams params) {
        List<Candle> candles = candles(params);
        if (candles.size() < PERIOD + 1) {
            return;
        }
        double[] highs = Indicators.highs(candles);
        double[] lows = Indicators.lows(candles);
        double[] closes = Indicators.closes(candles);
        double[] volumes = Indicators.volumes(candles);
        double[] cmf = Indicators.cmfSeries(highs, lows, closes, volumes, PERIOD);
        int n = closes.length;
        double previous = cmf[n - 2];
        double current = cmf[n - 1];
        if (Double.isNaN(previous) || Double.isNaN(current)) {
            return;
        }
        params.repo.cache.set_var(params.context.ticker, "cmf", current);
        if (previous <= 0 && current > 0) {
            emit(params, 0.71, String.format("Chaikin Money Flow crossed above zero (%.3f)", current));
        }
    }

    @Override
    public String family() {
        return "volume";
    }
}
