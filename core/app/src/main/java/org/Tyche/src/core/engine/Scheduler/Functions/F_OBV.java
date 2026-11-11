package org.Tyche.src.core.engine.Scheduler.Functions;

import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.util.Indicators;

/** On-balance-volume confirming a price breakout. */
public class F_OBV extends BaseFunction {
    private static final int BREAKOUT_LOOKBACK = 10;
    private static final int TREND_PERIOD = 20;

    @Override
    protected void evaluate(StartParams params) {
        List<Candle> candles = candles(params);
        if (candles.size() < TREND_PERIOD + BREAKOUT_LOOKBACK + 1) {
            return;
        }
        double[] closes = Indicators.closes(candles);
        double[] volumes = Indicators.volumes(candles);
        double[] obv = Indicators.obvSeries(closes, volumes);
        int n = closes.length;
        double priceSma = Indicators.sma(closes, TREND_PERIOD);

        double priorObvHigh = Double.NEGATIVE_INFINITY;
        for (int i = n - BREAKOUT_LOOKBACK - 1; i < n - 1; i++) {
            priorObvHigh = Math.max(priorObvHigh, obv[i]);
        }
        params.repo.cache.set_var(params.context.ticker, "obv", obv[n - 1]);
        if (closes[n - 1] > priceSma && obv[n - 1] > priorObvHigh) {
            emit(params, 0.65, String.format("OBV %.0f made a %d-bar high with price above SMA%d",
                    obv[n - 1], BREAKOUT_LOOKBACK, TREND_PERIOD));
        }
    }

    @Override
    public String family() {
        return "volume";
    }
}
