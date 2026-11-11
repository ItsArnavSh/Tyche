package org.Tyche.src.core.engine.Scheduler.Functions;

import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.util.Indicators;

/** On-balance volume crossing above its own trend while price is above SMA. */
public class F_OBVTrend extends BaseFunction {
    private static final int PERIOD = 20;

    @Override
    protected void evaluate(StartParams params) {
        List<Candle> candles = candles(params);
        if (candles.size() < PERIOD + 2) {
            return;
        }
        double[] closes = Indicators.closes(candles);
        double[] volumes = Indicators.volumes(candles);
        double[] obv = Indicators.obvSeries(closes, volumes);
        double[] obvEma = Indicators.emaSeries(obv, PERIOD);
        double priceSma = Indicators.sma(closes, PERIOD);
        int n = closes.length;
        double prevObv = obv[n - 2];
        double prevEma = obvEma[n - 2];
        double currentObv = obv[n - 1];
        double currentEma = obvEma[n - 1];
        if (Double.isNaN(prevEma) || Double.isNaN(currentEma) || Double.isNaN(priceSma)) {
            return;
        }
        params.repo.cache.set_var(params.context.ticker, "obv", currentObv);
        params.repo.cache.set_var(params.context.ticker, "obv_ema", currentEma);
        if (prevObv <= prevEma && currentObv > currentEma && closes[n - 1] > priceSma) {
            emit(params, 0.72, String.format("OBV crossed above its EMA with price above SMA%d", PERIOD));
        }
    }

    @Override
    public String family() {
        return "volume";
    }
}
