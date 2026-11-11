package org.Tyche.src.core.engine.Scheduler.Functions;

import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.util.Indicators;

/** Classic fast/slow SMA golden cross. */
public class F_DoubleSMA extends BaseFunction {
    private static final int FAST = 9;
    private static final int SLOW = 21;

    @Override
    protected void evaluate(StartParams params) {
        List<Candle> candles = candles(params);
        if (candles.size() < SLOW + 1) {
            return;
        }
        double[] closes = Indicators.closes(candles);
        double[] fast = Indicators.smaSeries(closes, FAST);
        double[] slow = Indicators.smaSeries(closes, SLOW);
        int n = closes.length;
        double prevFast = fast[n - 2];
        double prevSlow = slow[n - 2];
        double currentFast = fast[n - 1];
        double currentSlow = slow[n - 1];
        if (Double.isNaN(prevFast) || Double.isNaN(prevSlow)
                || Double.isNaN(currentFast) || Double.isNaN(currentSlow)) {
            return;
        }
        params.repo.cache.set_var(params.context.ticker, "sma_fast", currentFast);
        params.repo.cache.set_var(params.context.ticker, "sma_slow", currentSlow);
        if (prevFast <= prevSlow && currentFast > currentSlow) {
            emit(params, 0.85, String.format("golden cross SMA%d %.2f > SMA%d %.2f",
                    FAST, currentFast, SLOW, currentSlow));
        }
    }

    @Override
    public String family() {
        return "trend";
    }
}
