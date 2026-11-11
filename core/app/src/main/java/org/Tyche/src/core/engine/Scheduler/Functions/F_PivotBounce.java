package org.Tyche.src.core.engine.Scheduler.Functions;

import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.util.Indicators;

/** Intraday bounce from the prior bar's first support pivot. */
public class F_PivotBounce extends BaseFunction {

    @Override
    protected void evaluate(StartParams params) {
        List<Candle> candles = candles(params);
        if (candles.size() < 3) {
            return;
        }
        int n = candles.size();
        Candle previous = candles.get(n - 2);
        Candle current = candles.get(n - 1);
        Indicators.PivotResult pivot = Indicators.pivot(previous.high, previous.low, previous.close);
        params.repo.cache.set_var(params.context.ticker, "pivot_s1", pivot.support1());
        if (Double.isNaN(pivot.support1())) {
            return;
        }
        if (current.low <= pivot.support1() && current.close > pivot.support1()
                && current.close > current.open) {
            emit(params, 0.68, String.format("price bounced from pivot support %.2f", pivot.support1()));
        }
    }

    @Override
    public String family() {
        return "mean_reversion";
    }
}
