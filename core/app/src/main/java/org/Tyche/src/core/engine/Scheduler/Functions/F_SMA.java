package org.Tyche.src.core.engine.Scheduler.Functions;

import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.util.Indicators;

/** Price crossing above its simple moving average. */
public class F_SMA extends BaseFunction {
    private static final int PERIOD = 14;

    @Override
    protected void evaluate(StartParams params) {
        List<Candle> candles = candles(params);
        if (candles.size() < PERIOD + 1) {
            return;
        }
        double[] closes = Indicators.closes(candles);
        double[] sma = Indicators.smaSeries(closes, PERIOD);
        int n = closes.length;
        double prevClose = closes[n - 2];
        double prevSma = sma[n - 2];
        double close = closes[n - 1];
        double currentSma = sma[n - 1];
        if (Double.isNaN(prevSma) || Double.isNaN(currentSma)) {
            return;
        }
        params.repo.cache.set_var(params.context.ticker, "sma", currentSma);
        if (prevClose <= prevSma && close > currentSma) {
            emit(params, 0.80, String.format("price %.2f crossed above SMA%d %.2f", close, PERIOD, currentSma));
        }
    }

    @Override
    public String family() {
        return "trend";
    }
}
