package org.Tyche.src.core.engine.Scheduler.Functions;

import java.util.Arrays;
import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.util.Indicators;

/** Bollinger/Keltner squeeze followed by an upside expansion. */
public class F_SqueezeBreakout extends BaseFunction {
    private static final int PERIOD = 20;
    private static final double BB_MULTIPLIER = 2.0;
    private static final double KELTNER_MULTIPLIER = 1.5;

    @Override
    protected void evaluate(StartParams params) {
        List<Candle> candles = candles(params);
        if (candles.size() < PERIOD + 2) {
            return;
        }
        double[] highs = Indicators.highs(candles);
        double[] lows = Indicators.lows(candles);
        double[] closes = Indicators.closes(candles);
        int n = closes.length;
        Indicators.BollingerResult currentBb = Indicators.bollinger(closes, PERIOD, BB_MULTIPLIER);
        Indicators.BollingerResult previousBb = Indicators.bollinger(
                Arrays.copyOf(closes, n - 1), PERIOD, BB_MULTIPLIER);
        Indicators.KeltnerResult currentKeltner = Indicators.keltner(
                highs, lows, closes, PERIOD, PERIOD, KELTNER_MULTIPLIER);
        Indicators.KeltnerResult previousKeltner = Indicators.keltner(
                Arrays.copyOf(highs, n - 1),
                Arrays.copyOf(lows, n - 1),
                Arrays.copyOf(closes, n - 1),
                PERIOD, PERIOD, KELTNER_MULTIPLIER);
        if (Double.isNaN(currentBb.upper()) || Double.isNaN(previousBb.upper())
                || Double.isNaN(currentKeltner.upper())) {
            return;
        }
        boolean previousSqueeze = previousBb.upper() < previousKeltner.upper()
                && previousBb.lower() > previousKeltner.lower();
        if (previousSqueeze && closes[n - 1] > currentBb.upper()) {
            emit(params, 0.76, String.format("squeeze expansion above %.2f", currentBb.upper()));
        }
    }

    @Override
    public String family() {
        return "volatility";
    }
}
