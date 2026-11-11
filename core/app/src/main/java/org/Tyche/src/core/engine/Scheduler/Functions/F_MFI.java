package org.Tyche.src.core.engine.Scheduler.Functions;

import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.util.Indicators;

/** Money Flow Index recovering from oversold. */
public class F_MFI extends BaseFunction {
    private static final int PERIOD = 14;
    private static final double OVERSOLD = 20;

    @Override
    protected void evaluate(StartParams params) {
        List<Candle> candles = candles(params);
        if (candles.size() < PERIOD + 2) {
            return;
        }
        double[] highs = Indicators.highs(candles);
        double[] lows = Indicators.lows(candles);
        double[] closes = Indicators.closes(candles);
        double[] volumes = Indicators.volumes(candles);
        double[] mfi = Indicators.mfi(highs, lows, closes, volumes, PERIOD);
        int n = closes.length;
        double previous = mfi[n - 2];
        double current = mfi[n - 1];
        if (Double.isNaN(previous) || Double.isNaN(current)) {
            return;
        }
        params.repo.cache.set_var(params.context.ticker, "mfi", current);
        if (previous < OVERSOLD && current >= OVERSOLD) {
            emit(params, 0.73, String.format("MFI%d exited oversold (%.1f -> %.1f)",
                    PERIOD, previous, current));
        }
    }

    @Override
    public String family() {
        return "momentum";
    }
}
