package org.Tyche.src.core.engine.Scheduler.Functions;

import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.util.Indicators;

/** StochRSI bullish cross from oversold territory. */
public class F_StochRSI extends BaseFunction {
    private static final int RSI_PERIOD = 14;
    private static final int STOCH_PERIOD = 14;
    private static final int D_PERIOD = 3;
    private static final double OVERSOLD = 30;

    @Override
    protected void evaluate(StartParams params) {
        List<Candle> candles = candles(params);
        if (candles.size() < RSI_PERIOD + STOCH_PERIOD + D_PERIOD) {
            return;
        }
        double[] closes = Indicators.closes(candles);
        Indicators.StochasticResult stoch = Indicators.stochasticRsi(
                closes, RSI_PERIOD, STOCH_PERIOD, D_PERIOD);
        int n = closes.length;
        double prevK = stoch.k()[n - 2];
        double prevD = stoch.d()[n - 2];
        double currentK = stoch.k()[n - 1];
        double currentD = stoch.d()[n - 1];
        if (Double.isNaN(prevK) || Double.isNaN(prevD)
                || Double.isNaN(currentK) || Double.isNaN(currentD)) {
            return;
        }
        params.repo.cache.set_var(params.context.ticker, "stoch_rsi_k", currentK);
        params.repo.cache.set_var(params.context.ticker, "stoch_rsi_d", currentD);
        if (prevK <= prevD && currentK > currentD && currentK < OVERSOLD) {
            emit(params, 0.74, String.format("StochRSI bullish cross (K %.1f, D %.1f)", currentK, currentD));
        }
    }

    @Override
    public String family() {
        return "momentum";
    }
}
