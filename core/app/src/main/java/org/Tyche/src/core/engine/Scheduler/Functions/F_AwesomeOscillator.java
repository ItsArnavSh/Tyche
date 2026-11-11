package org.Tyche.src.core.engine.Scheduler.Functions;

import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.util.Indicators;

/** Awesome Oscillator crossing above zero. */
public class F_AwesomeOscillator extends BaseFunction {
    private static final int FAST = 5;
    private static final int SLOW = 34;

    @Override
    protected void evaluate(StartParams params) {
        List<Candle> candles = candles(params);
        if (candles.size() < SLOW + 2) {
            return;
        }
        double[] highs = Indicators.highs(candles);
        double[] lows = Indicators.lows(candles);
        double[] ao = Indicators.awesomeOscillator(highs, lows, FAST, SLOW);
        int n = highs.length;
        double previous = ao[n - 2];
        double current = ao[n - 1];
        if (Double.isNaN(previous) || Double.isNaN(current)) {
            return;
        }
        params.repo.cache.set_var(params.context.ticker, "awesome_oscillator", current);
        if (previous <= 0 && current > 0) {
            emit(params, 0.70, String.format("Awesome Oscillator crossed above zero (%.3f)", current));
        }
    }

    @Override
    public String family() {
        return "momentum";
    }
}
