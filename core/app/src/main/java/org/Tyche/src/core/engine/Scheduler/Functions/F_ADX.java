package org.Tyche.src.core.engine.Scheduler.Functions;

import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.util.Indicators;

/** Confirmed trend start: ADX above 20 with a bullish DI cross. */
public class F_ADX extends BaseFunction {
    private static final int PERIOD = 14;
    private static final double TREND_THRESHOLD = 20;

    @Override
    protected void evaluate(StartParams params) {
        List<Candle> candles = candles(params);
        if (candles.size() < PERIOD * 2 + 1) {
            return;
        }
        double[] highs = Indicators.highs(candles);
        double[] lows = Indicators.lows(candles);
        double[] closes = Indicators.closes(candles);
        Indicators.DmiResult dmi = Indicators.dmi(highs, lows, closes, PERIOD);
        int n = closes.length;
        double currentAdx = dmi.adx()[n - 1];
        double prevPlus = dmi.plusDi()[n - 2];
        double prevMinus = dmi.minusDi()[n - 2];
        double currentPlus = dmi.plusDi()[n - 1];
        double currentMinus = dmi.minusDi()[n - 1];
        if (Double.isNaN(currentAdx) || Double.isNaN(prevPlus) || Double.isNaN(prevMinus)
                || Double.isNaN(currentPlus) || Double.isNaN(currentMinus)) {
            return;
        }
        params.repo.cache.set_var(params.context.ticker, "adx", currentAdx);
        params.repo.cache.set_var(params.context.ticker, "plus_di", currentPlus);
        params.repo.cache.set_var(params.context.ticker, "minus_di", currentMinus);
        if (currentAdx >= TREND_THRESHOLD && prevPlus <= prevMinus && currentPlus > currentMinus) {
            emit(params, 0.81, String.format("ADX %.1f with +DI cross above -DI", currentAdx));
        }
    }

    @Override
    public String family() {
        return "trend";
    }
}
