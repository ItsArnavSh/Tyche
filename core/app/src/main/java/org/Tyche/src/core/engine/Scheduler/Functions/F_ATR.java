package org.Tyche.src.core.engine.Scheduler.Functions;

import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.util.Indicators;

/** Volatility breakout: close advances more than 1.5 ATR in one bar. */
public class F_ATR extends BaseFunction {
    private static final int PERIOD = 14;
    private static final double MULTIPLIER = 1.5;

    @Override
    public void Boot(StartParams params) {
        evaluate(params);
    }

    @Override
    public void Roll(StartParams params) {
        evaluate(params);
    }

    private void evaluate(StartParams params) {
        List<Candle> candles = candles(params);
        if (candles.size() < PERIOD + 2) {
            return;
        }
        double[] highs = Indicators.highs(candles);
        double[] lows = Indicators.lows(candles);
        double[] closes = Indicators.closes(candles);
        double[] atr = Indicators.atrSeries(highs, lows, closes, PERIOD);
        int n = closes.length;
        double currentAtr = atr[n - 2]; // ATR excluding the breakout bar
        if (Double.isNaN(currentAtr) || currentAtr <= 0) {
            return;
        }
        params.repo.cache.set_var(params.context.ticker, "atr", atr[n - 1]);
        double move = closes[n - 1] - closes[n - 2];
        if (move > MULTIPLIER * currentAtr) {
            emit(params, 0.70, String.format("ATR breakout: move %.2f > %.2fxATR %.2f",
                    move, MULTIPLIER, currentAtr));
        }
    }
}
