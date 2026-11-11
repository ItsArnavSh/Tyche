package org.Tyche.src.core.engine.Scheduler.Functions;

import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.util.Indicators;

/** Parabolic SAR flipping from bearish to bullish. */
public class F_PSAR extends BaseFunction {
    private static final double STEP = 0.02;
    private static final double MAX_STEP = 0.2;

    @Override
    protected void evaluate(StartParams params) {
        List<Candle> candles = candles(params);
        if (candles.size() < 3) {
            return;
        }
        double[] highs = Indicators.highs(candles);
        double[] lows = Indicators.lows(candles);
        Indicators.ParabolicSarResult psar = Indicators.parabolicSar(highs, lows, STEP, MAX_STEP);
        int n = highs.length;
        int previousDirection = psar.direction()[n - 2];
        int currentDirection = psar.direction()[n - 1];
        if (Double.isNaN(psar.sar()[n - 1])) {
            return;
        }
        params.repo.cache.set_var(params.context.ticker, "psar", psar.sar()[n - 1]);
        if (previousDirection < 0 && currentDirection > 0) {
            emit(params, 0.77, String.format("Parabolic SAR flipped bullish at %.2f", psar.sar()[n - 1]));
        }
    }

    @Override
    public String family() {
        return "trend";
    }
}
