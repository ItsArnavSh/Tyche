package org.Tyche.src.core.engine.Scheduler.Functions;

import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.util.Indicators;

/** Price breaking a prior high on above-average volume. */
public class F_VolumeBreakout extends BaseFunction {
    private static final int PERIOD = 20;
    private static final double VOLUME_MULTIPLIER = 1.5;

    @Override
    protected void evaluate(StartParams params) {
        List<Candle> candles = candles(params);
        if (candles.size() < PERIOD + 1) {
            return;
        }
        double[] highs = Indicators.highs(candles);
        double[] closes = Indicators.closes(candles);
        double[] volumes = Indicators.volumes(candles);
        int n = closes.length;
        int start = n - PERIOD - 1;
        double priorHigh = Double.NEGATIVE_INFINITY;
        double volumeSum = 0;
        for (int i = start; i < n - 1; i++) {
            priorHigh = Math.max(priorHigh, highs[i]);
            volumeSum += volumes[i];
        }
        double averageVolume = volumeSum / PERIOD;
        if (Double.isInfinite(priorHigh) || averageVolume <= 0) {
            return;
        }
        params.repo.cache.set_var(params.context.ticker, "volume_avg", averageVolume);
        if (closes[n - 1] > priorHigh
                && closes[n - 1] > candles.get(n - 1).open
                && volumes[n - 1] > VOLUME_MULTIPLIER * averageVolume) {
            emit(params, 0.80, String.format("volume breakout above %.2f on %.0fx volume",
                    priorHigh, volumes[n - 1] / averageVolume));
        }
    }

    @Override
    public String family() {
        return "volume";
    }
}
