package org.Tyche.src.core.engine.Scheduler.Functions;

import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.util.Indicators;

/** EMA-smoothed force index crossing above zero. */
public class F_ForceIndex extends BaseFunction {
    private static final int PERIOD = 13;

    @Override
    protected void evaluate(StartParams params) {
        List<Candle> candles = candles(params);
        if (candles.size() < PERIOD + 2) {
            return;
        }
        double[] closes = Indicators.closes(candles);
        double[] volumes = Indicators.volumes(candles);
        double[] force = Indicators.forceIndexSeries(closes, volumes, PERIOD);
        int n = closes.length;
        double previous = force[n - 2];
        double current = force[n - 1];
        if (Double.isNaN(previous) || Double.isNaN(current)) {
            return;
        }
        params.repo.cache.set_var(params.context.ticker, "force_index", current);
        if (previous <= 0 && current > 0) {
            emit(params, 0.70, String.format("Force Index crossed above zero (%.0f)", current));
        }
    }

    @Override
    public String family() {
        return "volume";
    }
}
