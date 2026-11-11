package org.Tyche.src.core.engine.Scheduler.Functions;

import java.util.Arrays;
import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.util.Indicators;

/** Commodity Channel Index reversal from oversold. */
public class F_CCI extends BaseFunction {
    private static final int PERIOD = 20;

    @Override
    protected void evaluate(StartParams params) {
        List<Candle> candles = candles(params);
        if (candles.size() < PERIOD + 2) {
            return;
        }
        double[] highs = Indicators.highs(candles);
        double[] lows = Indicators.lows(candles);
        double[] closes = Indicators.closes(candles);
        double current = Indicators.cci(highs, lows, closes, PERIOD);
        double previous = Indicators.cci(
                Arrays.copyOf(highs, highs.length - 1),
                Arrays.copyOf(lows, lows.length - 1),
                Arrays.copyOf(closes, closes.length - 1),
                PERIOD);
        if (Double.isNaN(current) || Double.isNaN(previous)) {
            return;
        }
        params.repo.cache.set_var(params.context.ticker, "cci", current);
        if (previous < -100 && current >= -100) {
            emit(params, 0.72, String.format("CCI%d exited oversold (%.1f -> %.1f)", PERIOD, previous, current));
        }
    }

    @Override
    public String family() {
        return "momentum";
    }
}
