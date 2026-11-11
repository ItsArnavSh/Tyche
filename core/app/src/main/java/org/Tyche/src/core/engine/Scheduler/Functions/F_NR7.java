package org.Tyche.src.core.engine.Scheduler.Functions;

import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.StrategyEntity.StartParams;

/** Breakout from the narrowest range of the last seven bars. */
public class F_NR7 extends BaseFunction {
    private static final int LOOKBACK = 7;

    @Override
    protected void evaluate(StartParams params) {
        List<Candle> candles = candles(params);
        if (candles.size() < LOOKBACK + 1) {
            return;
        }
        int n = candles.size();
        Candle narrow = candles.get(n - 2);
        double narrowRange = narrow.high - narrow.low;
        if (narrowRange <= 0) {
            return;
        }
        for (int i = n - LOOKBACK - 1; i < n - 2; i++) {
            Candle candle = candles.get(i);
            if (candle.high - candle.low < narrowRange) {
                return;
            }
        }
        Candle current = candles.get(n - 1);
        params.repo.cache.set_var(params.context.ticker, "nr7_high", narrow.high);
        if (current.close > narrow.high && current.close > current.open) {
            emit(params, 0.73, String.format("NR7 breakout above %.2f", narrow.high));
        }
    }

    @Override
    public String family() {
        return "volatility";
    }
}
