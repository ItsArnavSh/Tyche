package org.Tyche.src.core.engine.Scheduler.Functions;

import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.util.Indicators;

/** Close breaking above the prior N-bar high. */
public class F_DonchianChannel extends BaseFunction {
    private static final int PERIOD = 20;

    @Override
    protected void evaluate(StartParams params) {
        List<Candle> candles = candles(params);
        if (candles.size() < PERIOD + 2) {
            return;
        }
        double[] highs = Indicators.highs(candles);
        double[] lows = Indicators.lows(candles);
        double[] closes = Indicators.closes(candles);
        Indicators.DonchianResult channel = Indicators.donchian(highs, lows, PERIOD);
        if (Double.isNaN(channel.highest())) {
            return;
        }
        int n = closes.length;
        params.repo.cache.set_var(params.context.ticker, "donchian_high", channel.highest());
        params.repo.cache.set_var(params.context.ticker, "donchian_low", channel.lowest());
        if (closes[n - 1] > channel.highest() && closes[n - 2] <= channel.highest()) {
            emit(params, 0.84, String.format("Donchian breakout above %.2f", channel.highest()));
        }
    }

    @Override
    public String family() {
        return "breakout";
    }
}
