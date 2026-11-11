package org.Tyche.src.core.engine.Scheduler.Functions;

import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.StrategyEntity.StartParams;

/** Bullish engulfing candle after a down bar. */
public class F_BullishEngulfing extends BaseFunction {

    @Override
    protected void evaluate(StartParams params) {
        List<Candle> candles = candles(params);
        if (candles.size() < 2) {
            return;
        }
        int n = candles.size();
        Candle previous = candles.get(n - 2);
        Candle current = candles.get(n - 1);
        boolean previousBearish = previous.close < previous.open;
        boolean currentBullish = current.close > current.open;
        boolean engulfs = current.open <= previous.close && current.close >= previous.open;
        if (previousBearish && currentBullish && engulfs) {
            emit(params, 0.67, "bullish engulfing candle");
        }
    }

    @Override
    public String family() {
        return "price_action";
    }
}
