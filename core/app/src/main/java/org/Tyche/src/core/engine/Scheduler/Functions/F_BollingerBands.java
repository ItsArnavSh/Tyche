package org.Tyche.src.core.engine.Scheduler.Functions;

import java.time.Instant;
import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.CandleSize;
import org.Tyche.src.entity.Signal;
import org.Tyche.src.entity.StrategyEntity.StartParams;

public class F_BollingerBands extends BaseFunction {
    private static final int PERIOD = 20;
    private static final double MULTIPLIER = 2.0;

    @Override
    public void Boot(StartParams params) {
        System.out.println("[F_Bollinger Boot] Ready for " + params.context.ticker.name);
    }

    @Override
    public void Roll(StartParams params) {
        try {
            var candles = params.repo.cache.candle_cache.get(params.context.ticker);
            if (candles == null || candles.size() < PERIOD)
                return;

            var arr = candles.toArray();
            double sum = 0;
            for (int i = 0; i < PERIOD; i++) {
                sum += ((Candle) arr[i]).close;
            }
            double sma = sum / PERIOD;

            double variance = 0;
            for (int i = 0; i < PERIOD; i++) {
                double diff = ((Candle) arr[i]).close - sma;
                variance += diff * diff;
            }
            double stdDev = Math.sqrt(variance / PERIOD);
            double upper = sma + MULTIPLIER * stdDev;
            double lower = sma - MULTIPLIER * stdDev;

            double price = ((Candle) arr[0]).close;

            if (price < lower) {
                System.out.println("[F_Bollinger] Price below lower band — buy signal");
                Signal sig = new Signal(params.context.ticker.name, 80, CandleSize.min1, Instant.now());
                params.repo.redis.send_signal(sig);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}