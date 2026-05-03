package org.Tyche.src.core.engine.Scheduler.Functions;

import java.time.Instant;
import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.CandleSize;
import org.Tyche.src.entity.Signal;
import org.Tyche.src.entity.StrategyEntity.StartParams;

public class F_HeikinAshi extends BaseFunction {

    @Override
    public void Boot(StartParams params) {
        System.out.println("[F_HeikinAshi Boot] Initialized");
    }

    @Override
    public void Roll(StartParams params) {
        try {
            var candles = params.repo.cache.candle_cache.get(params.context.ticker);
            if (candles == null || candles.size() < 4)
                return;

            var arr = candles.toArray();
            Candle c0 = (Candle) arr[0]; // current
            Candle c1 = (Candle) arr[1]; // previous

            double haClose = (c0.open + c0.high + c0.low + c0.close) / 4;
            double haOpen = (c1.open + c1.close) / 2; // simplified

            if (haClose > haOpen) { // Bullish Heikin Ashi candle
                System.out.println("[F_HeikinAshi] Bullish HA candle — buy signal");
                Signal sig = new Signal(params.context.ticker.name, 70, CandleSize.min1, Instant.now());
                params.repo.redis.send_signal(sig);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}