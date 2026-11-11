package org.Tyche.src.core.engine.Scheduler.Functions;

import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.StrategyEntity.StartParams;

/** Breakout above an inside bar's high. */
public class F_InsideBar extends BaseFunction {

    @Override
    protected void evaluate(StartParams params) {
        List<Candle> candles = candles(params);
        if (candles.size() < 3) {
            return;
        }
        int n = candles.size();
        Candle mother = candles.get(n - 3);
        Candle inside = candles.get(n - 2);
        Candle current = candles.get(n - 1);
        boolean isInside = inside.high <= mother.high && inside.low >= mother.low;
        params.repo.cache.set_var(params.context.ticker, "inside_bar_high", inside.high);
        if (isInside && current.close > inside.high && current.close > current.open) {
            emit(params, 0.70, String.format("inside-bar breakout above %.2f", inside.high));
        }
    }

    @Override
    public String family() {
        return "volatility";
    }
}
