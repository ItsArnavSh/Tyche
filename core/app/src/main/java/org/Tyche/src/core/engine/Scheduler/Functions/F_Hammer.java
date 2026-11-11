package org.Tyche.src.core.engine.Scheduler.Functions;

import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.util.Indicators;

/** Hammer reversal after a decline. */
public class F_Hammer extends BaseFunction {
    private static final int TREND_PERIOD = 20;

    @Override
    protected void evaluate(StartParams params) {
        List<Candle> candles = candles(params);
        if (candles.size() < TREND_PERIOD + 1) {
            return;
        }
        double[] closes = Indicators.closes(candles);
        double sma = Indicators.sma(closes, TREND_PERIOD);
        int n = candles.size();
        Candle current = candles.get(n - 1);
        double body = Math.abs(current.close - current.open);
        double range = current.high - current.low;
        double lowerShadow = Math.min(current.open, current.close) - current.low;
        double upperShadow = current.high - Math.max(current.open, current.close);
        if (Double.isNaN(sma) || body <= 0 || range <= 0) {
            return;
        }
        boolean downtrend = closes[n - 2] < sma;
        boolean hammerShape = lowerShadow >= 2 * body && upperShadow <= body;
        if (downtrend && hammerShape && current.close >= current.open) {
            emit(params, 0.66, String.format("hammer reversal at %.2f", current.close));
        }
    }

    @Override
    public String family() {
        return "price_action";
    }
}
