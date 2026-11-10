package org.Tyche.src.core.engine.Scheduler.Functions;

import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.util.Indicators;

/** Stochastic %K crossing above %D in oversold territory. */
public class F_Stochastic extends BaseFunction {
    private static final int K_PERIOD = 14;
    private static final int D_PERIOD = 3;

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
        if (candles.size() < K_PERIOD + D_PERIOD + 1) {
            return;
        }
        double[] highs = Indicators.highs(candles);
        double[] lows = Indicators.lows(candles);
        double[] closes = Indicators.closes(candles);
        Indicators.StochasticResult stoch = Indicators.stochastic(highs, lows, closes, K_PERIOD, D_PERIOD);
        int n = closes.length;
        double prevK = stoch.k()[n - 2];
        double prevD = stoch.d()[n - 2];
        double currentK = stoch.k()[n - 1];
        double currentD = stoch.d()[n - 1];
        if (Double.isNaN(prevK) || Double.isNaN(prevD) || Double.isNaN(currentK) || Double.isNaN(currentD)) {
            return;
        }
        params.repo.cache.set_var(params.context.ticker, "stoch_k", currentK);
        params.repo.cache.set_var(params.context.ticker, "stoch_d", currentD);
        if (prevK <= prevD && currentK > currentD && currentK < 30) {
            emit(params, 0.74, String.format("stochastic bullish cross (K %.1f, D %.1f)", currentK, currentD));
        }
    }
}
