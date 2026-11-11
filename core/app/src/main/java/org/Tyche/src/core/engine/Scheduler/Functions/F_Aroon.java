package org.Tyche.src.core.engine.Scheduler.Functions;

import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.util.Indicators;

/** Aroon up crossing into a strong uptrend regime. */
public class F_Aroon extends BaseFunction {
    private static final int PERIOD = 25;
    private static final double STRONG = 70;

    @Override
    protected void evaluate(StartParams params) {
        List<Candle> candles = candles(params);
        if (candles.size() < PERIOD + 2) {
            return;
        }
        double[] highs = Indicators.highs(candles);
        double[] lows = Indicators.lows(candles);
        Indicators.AroonResult aroon = Indicators.aroon(highs, lows, PERIOD);
        int n = highs.length;
        double prevUp = aroon.up()[n - 2];
        double currentUp = aroon.up()[n - 1];
        double currentDown = aroon.down()[n - 1];
        if (Double.isNaN(prevUp) || Double.isNaN(currentUp) || Double.isNaN(currentDown)) {
            return;
        }
        params.repo.cache.set_var(params.context.ticker, "aroon_up", currentUp);
        params.repo.cache.set_var(params.context.ticker, "aroon_down", currentDown);
        if (prevUp < STRONG && currentUp >= STRONG && currentUp > currentDown) {
            emit(params, 0.75, String.format("Aroon up %.0f crossed above %.0f", currentUp, STRONG));
        }
    }

    @Override
    public String family() {
        return "trend";
    }
}
