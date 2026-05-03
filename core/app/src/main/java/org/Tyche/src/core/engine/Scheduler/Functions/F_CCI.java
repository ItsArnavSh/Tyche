package org.Tyche.src.core.engine.Scheduler.Functions;

import java.time.Instant;
import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.CandleSize;
import org.Tyche.src.entity.Signal;
import org.Tyche.src.entity.StrategyEntity.StartParams;

public class F_CCI extends BaseFunction {
    private static final int PERIOD = 20;

    @Override
    public void Boot(StartParams params) {
        System.out.println("[F_CCI Boot] Initialized");
    }

    @Override
    public void Roll(StartParams params) {
        try {
            var candles = params.repo.cache.candle_cache.get(params.context.ticker);
            if (candles == null || candles.size() < PERIOD)
                return;

            var arr = candles.toArray();

            double sumTP = 0;
            for (int i = 0; i < PERIOD; i++) {
                Candle c = (Candle) arr[i];
                sumTP += (c.high + c.low + c.close) / 3.0;
            }
            double smaTP = sumTP / PERIOD;

            double meanDeviation = 0;
            for (int i = 0; i < PERIOD; i++) {
                Candle c = (Candle) arr[i];
                meanDeviation += Math.abs(((c.high + c.low + c.close) / 3.0) - smaTP);
            }
            meanDeviation /= PERIOD;

            double currentTP = (((Candle) arr[0]).high + ((Candle) arr[0]).low + ((Candle) arr[0]).close) / 3.0;
            double cci = (meanDeviation == 0) ? 0 : (currentTP - smaTP) / (0.015 * meanDeviation);

            if (cci < -100) {
                System.out.println("[F_CCI] Oversold (" + cci + ") — buy signal");
                Signal sig = new Signal(params.context.ticker.name, 80, CandleSize.min1, Instant.now());
                params.repo.redis.send_signal(sig);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}