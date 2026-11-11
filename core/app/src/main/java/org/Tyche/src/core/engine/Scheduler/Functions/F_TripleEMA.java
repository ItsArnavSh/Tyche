package org.Tyche.src.core.engine.Scheduler.Functions;

import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.util.Indicators;

/** Three exponential moving averages becoming fully bullish-aligned. */
public class F_TripleEMA extends BaseFunction {
    private static final int FAST = 8;
    private static final int MID = 13;
    private static final int SLOW = 21;

    @Override
    protected void evaluate(StartParams params) {
        List<Candle> candles = candles(params);
        if (candles.size() < SLOW + 1) {
            return;
        }
        double[] closes = Indicators.closes(candles);
        double[] fast = Indicators.emaSeries(closes, FAST);
        double[] mid = Indicators.emaSeries(closes, MID);
        double[] slow = Indicators.emaSeries(closes, SLOW);
        int n = closes.length;
        double prevFast = fast[n - 2];
        double prevMid = mid[n - 2];
        double prevSlow = slow[n - 2];
        double currentFast = fast[n - 1];
        double currentMid = mid[n - 1];
        double currentSlow = slow[n - 1];
        if (Double.isNaN(prevFast) || Double.isNaN(prevMid) || Double.isNaN(prevSlow)
                || Double.isNaN(currentFast) || Double.isNaN(currentMid) || Double.isNaN(currentSlow)) {
            return;
        }
        params.repo.cache.set_var(params.context.ticker, "ema_fast", currentFast);
        params.repo.cache.set_var(params.context.ticker, "ema_mid", currentMid);
        params.repo.cache.set_var(params.context.ticker, "ema_slow", currentSlow);
        boolean aligned = currentFast > currentMid && currentMid > currentSlow;
        boolean wasAligned = prevFast > prevMid && prevMid > prevSlow;
        if (aligned && !wasAligned) {
            emit(params, 0.80, String.format("EMA ribbon aligned bullish (%.2f > %.2f > %.2f)",
                    currentFast, currentMid, currentSlow));
        }
    }

    @Override
    public String family() {
        return "trend";
    }
}
