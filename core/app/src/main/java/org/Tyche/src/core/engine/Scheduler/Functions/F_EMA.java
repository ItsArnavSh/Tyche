package org.Tyche.src.core.engine.Scheduler.Functions;

import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.util.Indicators;

/** Price crossing above its exponential moving average. */
public class F_EMA extends BaseFunction {
    private static final int PERIOD = 14;

    @Override
    protected void evaluate(StartParams params) {
        List<Candle> candles = candles(params);
        if (candles.size() < PERIOD + 1) {
            return;
        }
        double[] closes = Indicators.closes(candles);
        double[] ema = Indicators.emaSeries(closes, PERIOD);
        int n = closes.length;
        double prevClose = closes[n - 2];
        double prevEma = ema[n - 2];
        double close = closes[n - 1];
        double currentEma = ema[n - 1];
        if (Double.isNaN(prevEma) || Double.isNaN(currentEma)) {
            return;
        }
        params.repo.cache.set_var(params.context.ticker, "ema", currentEma);
        if (prevClose <= prevEma && close > currentEma) {
            emit(params, 0.80, String.format("price %.2f crossed above EMA%d %.2f", close, PERIOD, currentEma));
        }
    }

    @Override
    public String family() {
        return "trend";
    }
}
