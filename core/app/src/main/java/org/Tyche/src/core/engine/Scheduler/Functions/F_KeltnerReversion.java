package org.Tyche.src.core.engine.Scheduler.Functions;

import java.util.Arrays;
import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.util.Indicators;

/** Mean reversion when price reclaims the lower Keltner band. */
public class F_KeltnerReversion extends BaseFunction {
    private static final int EMA_PERIOD = 20;
    private static final int ATR_PERIOD = 20;
    private static final double MULTIPLIER = 1.5;

    @Override
    protected void evaluate(StartParams params) {
        List<Candle> candles = candles(params);
        if (candles.size() < EMA_PERIOD + 1) {
            return;
        }
        double[] highs = Indicators.highs(candles);
        double[] lows = Indicators.lows(candles);
        double[] closes = Indicators.closes(candles);
        int n = closes.length;
        Indicators.KeltnerResult current = Indicators.keltner(
                highs, lows, closes, EMA_PERIOD, ATR_PERIOD, MULTIPLIER);
        Indicators.KeltnerResult previous = Indicators.keltner(
                Arrays.copyOf(highs, n - 1),
                Arrays.copyOf(lows, n - 1),
                Arrays.copyOf(closes, n - 1),
                EMA_PERIOD, ATR_PERIOD, MULTIPLIER);
        if (Double.isNaN(current.lower()) || Double.isNaN(previous.lower())) {
            return;
        }
        params.repo.cache.set_var(params.context.ticker, "keltner_lower", current.lower());
        if (closes[n - 2] <= previous.lower() && closes[n - 1] > current.lower()
                && closes[n - 1] < current.middle()) {
            emit(params, 0.71, String.format("price reclaimed lower Keltner band %.2f", current.lower()));
        }
    }

    @Override
    public String family() {
        return "mean_reversion";
    }
}
