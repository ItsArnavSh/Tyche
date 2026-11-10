package org.Tyche.src.core.engine.Scheduler.Functions;

import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.util.Indicators;

/** Two consecutive bullish Heikin-Ashi candles after a bearish candle. */
public class F_HeikinAshi extends BaseFunction {

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
        if (candles.size() < 4) {
            return;
        }
        double[] opens = new double[candles.size()];
        double[] highs = Indicators.highs(candles);
        double[] lows = Indicators.lows(candles);
        double[] closes = Indicators.closes(candles);
        for (int i = 0; i < candles.size(); i++) {
            opens[i] = candles.get(i).open;
        }
        Indicators.HeikinAshiResult ha = Indicators.heikinAshi(opens, highs, lows, closes);
        int n = closes.length;
        boolean prevBull = ha.close()[n - 2] > ha.open()[n - 2];
        boolean currentBull = ha.close()[n - 1] > ha.open()[n - 1];
        boolean beforeBear = ha.close()[n - 3] <= ha.open()[n - 3];
        if (beforeBear && prevBull && currentBull) {
            emit(params, 0.66, "two bullish Heikin-Ashi candles after a bearish candle");
        }
    }
}
