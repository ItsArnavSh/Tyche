package org.Tyche.src.core.engine.Scheduler.Functions;

import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.util.Indicators;

/** Supertrend flip from bearish to bullish. */
public class F_Supertrend extends BaseFunction {
    private static final int PERIOD = 10;
    private static final double MULTIPLIER = 3.0;

    @Override
    protected void evaluate(StartParams params) {
        List<Candle> candles = candles(params);
        if (candles.size() < PERIOD + 3) {
            return;
        }
        double[] highs = Indicators.highs(candles);
        double[] lows = Indicators.lows(candles);
        double[] closes = Indicators.closes(candles);
        Indicators.SupertrendResult trend = Indicators.supertrend(highs, lows, closes, PERIOD, MULTIPLIER);
        int n = closes.length;
        if (Double.isNaN(trend.line()[n - 1]) || trend.direction()[n - 2] == 0) {
            return;
        }
        params.repo.cache.set_var(params.context.ticker, "supertrend", trend.line()[n - 1]);
        params.repo.cache.set_var(params.context.ticker, "supertrend_dir", trend.direction()[n - 1]);
        if (trend.direction()[n - 2] < 0 && trend.direction()[n - 1] > 0) {
            emit(params, 0.83, String.format("Supertrend flipped bullish at %.2f", trend.line()[n - 1]));
        }
    }

    @Override
    public String family() {
        return "trend";
    }
}
