package org.Tyche.src.core.engine.Scheduler.Functions;

import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.util.Indicators;

/** Price recovering from a two-standard-deviation stretch below its mean. */
public class F_ZScoreReversion extends BaseFunction {
    private static final int PERIOD = 20;
    private static final double ENTRY_Z = -2;

    @Override
    protected void evaluate(StartParams params) {
        List<Candle> candles = candles(params);
        if (candles.size() < PERIOD + 1) {
            return;
        }
        double[] closes = Indicators.closes(candles);
        double[] zScore = Indicators.zScoreSeries(closes, PERIOD);
        int n = closes.length;
        double previous = zScore[n - 2];
        double current = zScore[n - 1];
        if (Double.isNaN(previous) || Double.isNaN(current)) {
            return;
        }
        params.repo.cache.set_var(params.context.ticker, "z_score", current);
        if (previous <= ENTRY_Z && current > ENTRY_Z) {
            emit(params, 0.70, String.format("z-score recovered from %.2f to %.2f", previous, current));
        }
    }

    @Override
    public String family() {
        return "mean_reversion";
    }
}
