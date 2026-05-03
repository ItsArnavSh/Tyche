package org.Tyche.src.core.engine.Scheduler.Functions;

import java.time.Instant;
import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.CandleSize;
import org.Tyche.src.entity.Signal;
import org.Tyche.src.entity.StrategyEntity.StartParams;

public class F_VWAP extends BaseFunction {

    @Override
    public void Boot(StartParams params) {
        System.out.println("[F_VWAP Boot] Initialized");
    }

    @Override
    public void Roll(StartParams params) {
        try {
            var candles = params.repo.cache.candle_cache.get(params.context.ticker);
            if (candles == null || candles.size() < 50)
                return; // at least one trading session

            var arr = candles.toArray();
            double cumulativeTPV = 0; // Typical Price * Volume
            double cumulativeVolume = 0;

            int limit = Math.min(390, arr.length); // approx one day of 1min candles

            for (int i = 0; i < limit; i++) {
                Candle c = (Candle) arr[i];
                double typicalPrice = (c.high + c.low + c.close) / 3.0;
                cumulativeTPV += typicalPrice * c.volume;
                cumulativeVolume += c.volume;
            }

            if (cumulativeVolume == 0)
                return;

            double vwap = cumulativeTPV / cumulativeVolume;
            double price = ((Candle) arr[0]).close;

            if (price > vwap) {
                System.out.println("[F_VWAP] Price above VWAP — bullish bias, sending buy");
                Signal sig = new Signal(params.context.ticker.name, 70, CandleSize.min1, Instant.now());
                params.repo.redis.send_signal(sig);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}