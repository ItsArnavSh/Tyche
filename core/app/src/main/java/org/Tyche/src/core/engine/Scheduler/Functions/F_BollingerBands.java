package org.Tyche.src.core.engine.Scheduler.Functions;

import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.util.Indicators;

/** Mean reversion when price closes below the lower Bollinger band. */
public class F_BollingerBands extends BaseFunction {
    private static final int PERIOD = 20;
    private static final double MULTIPLIER = 2.0;

    @Override
    protected void evaluate(StartParams params) {
        List<Candle> candles = candles(params);
        if (candles.size() < PERIOD + 1) {
            return;
        }
        double[] closes = Indicators.closes(candles);
        Indicators.BollingerResult current = Indicators.bollinger(closes, PERIOD, MULTIPLIER);
        Indicators.BollingerResult previous = Indicators.bollinger(
                java.util.Arrays.copyOf(closes, closes.length - 1), PERIOD, MULTIPLIER);
        if (Double.isNaN(current.lower()) || Double.isNaN(previous.lower())) {
            return;
        }
        double close = closes[closes.length - 1];
        double prevClose = closes[closes.length - 2];
        params.repo.cache.set_var(params.context.ticker, "bb_upper", current.upper());
        params.repo.cache.set_var(params.context.ticker, "bb_lower", current.lower());
        params.repo.cache.set_var(params.context.ticker, "bb_middle", current.middle());
        if (prevClose <= previous.lower() && close > current.lower()) {
            emit(params, 0.73, String.format("price %.2f reclaimed lower Bollinger band %.2f", close, current.lower()));
        }
    }

    @Override
    public String family() {
        return "mean_reversion";
    }
}
