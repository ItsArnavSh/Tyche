package org.Tyche.src.core.engine.Scheduler.Functions;

import java.time.Instant;
import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.CandleSize;
import org.Tyche.src.entity.Signal;
import org.Tyche.src.entity.StrategyEntity.StartParams;

public class F_DonchianChannel extends BaseFunction {
    private static final int PERIOD = 20;

    @Override
    public void Boot(StartParams params) {
        System.out.println("[F_Donchian Boot] Ready");
    }

    @Override
    public void Roll(StartParams params) {
        try {
            var candles = params.repo.cache.candle_cache.get(params.context.ticker);
            if (candles == null || candles.size() < PERIOD)
                return;

            var arr = candles.toArray();
            double highest = Double.MIN_VALUE;
            double lowest = Double.MAX_VALUE;

            for (int i = 0; i < PERIOD; i++) {
                Candle c = (Candle) arr[i];
                highest = Math.max(highest, c.high);
                lowest = Math.min(lowest, c.low);
            }

            double price = ((Candle) arr[0]).close;

            if (price > highest) {
                System.out.println("[F_Donchian] Upper channel breakout — buy signal");
                Signal sig = new Signal(params.context.ticker.name, 88, CandleSize.min1, Instant.now());
                params.repo.redis.send_signal(sig);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}