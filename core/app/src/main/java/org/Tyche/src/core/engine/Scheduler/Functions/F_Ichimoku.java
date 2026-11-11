package org.Tyche.src.core.engine.Scheduler.Functions;

import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.util.Indicators;

/** Close breaking above the Ichimoku cloud with a bullish TK relationship. */
public class F_Ichimoku extends BaseFunction {
    private static final int TENKAN = 9;
    private static final int KIJUN = 26;
    private static final int SENKOU_B = 52;

    @Override
    protected void evaluate(StartParams params) {
        List<Candle> candles = candles(params);
        if (candles.size() < SENKOU_B + 1) {
            return;
        }
        double[] highs = Indicators.highs(candles);
        double[] lows = Indicators.lows(candles);
        double[] closes = Indicators.closes(candles);
        Indicators.IchimokuResult ichimoku = Indicators.ichimoku(highs, lows, TENKAN, KIJUN, SENKOU_B);
        int n = closes.length;
        double currentCloud = Math.max(ichimoku.senkouA()[n - 1], ichimoku.senkouB()[n - 1]);
        double previousCloud = Math.max(ichimoku.senkouA()[n - 2], ichimoku.senkouB()[n - 2]);
        double tenkan = ichimoku.tenkan()[n - 1];
        double kijun = ichimoku.kijun()[n - 1];
        if (Double.isNaN(currentCloud) || Double.isNaN(previousCloud)
                || Double.isNaN(tenkan) || Double.isNaN(kijun)) {
            return;
        }
        params.repo.cache.set_var(params.context.ticker, "ichimoku_cloud", currentCloud);
        params.repo.cache.set_var(params.context.ticker, "tenkan", tenkan);
        params.repo.cache.set_var(params.context.ticker, "kijun", kijun);
        if (closes[n - 2] <= previousCloud && closes[n - 1] > currentCloud && tenkan > kijun) {
            emit(params, 0.78, String.format("price broke above Ichimoku cloud %.2f", currentCloud));
        }
    }

    @Override
    public String family() {
        return "trend";
    }
}
