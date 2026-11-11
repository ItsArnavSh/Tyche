package org.Tyche.src.core.engine.Scheduler.Functions;

import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.util.Indicators;

/** MACD line crossing above its signal line. */
public class F_MACD extends BaseFunction {
    private static final int FAST = 12;
    private static final int SLOW = 26;
    private static final int SIGNAL = 9;

    @Override
    protected void evaluate(StartParams params) {
        List<Candle> candles = candles(params);
        if (candles.size() < SLOW + SIGNAL + 1) {
            return;
        }
        double[] closes = Indicators.closes(candles);
        Indicators.MacdResult macd = Indicators.macd(closes, FAST, SLOW, SIGNAL);
        int n = closes.length;
        double prevMacd = macd.macd()[n - 2];
        double prevSignal = macd.signal()[n - 2];
        double currentMacd = macd.macd()[n - 1];
        double currentSignal = macd.signal()[n - 1];
        if (Double.isNaN(prevMacd) || Double.isNaN(prevSignal)
                || Double.isNaN(currentMacd) || Double.isNaN(currentSignal)) {
            return;
        }
        params.repo.cache.set_var(params.context.ticker, "macd", currentMacd);
        params.repo.cache.set_var(params.context.ticker, "macd_signal", currentSignal);
        if (prevMacd <= prevSignal && currentMacd > currentSignal) {
            emit(params, 0.82, String.format("MACD bullish cross (%.3f > %.3f)", currentMacd, currentSignal));
        }
    }

    @Override
    public String family() {
        return "trend";
    }
}
