package org.Tyche.src.core.engine.Scheduler.Functions;

import java.time.Instant;
import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.CandleSize;
import org.Tyche.src.entity.Signal;
import org.Tyche.src.entity.StrategyEntity.StartParams;

public class F_RSI extends BaseFunction {
    private static final int PERIOD = 14;

    @Override
    public void Boot(StartParams params) {
        // Simple boot - calculate initial RSI and store gains/losses if needed
        System.out.println("[F_RSI Boot] Initialized for " + params.context.ticker.name);
        // You can expand this later for full initial calculation
    }

    @Override
    public void Roll(StartParams params) {
        try {
            var candles = params.repo.cache.candle_cache.get(params.context.ticker);
            if (candles == null || candles.size() < PERIOD + 1)
                return;

            double gainSum = 0, lossSum = 0;
            var arr = candles.toArray();

            for (int i = 1; i <= PERIOD; i++) {
                double change = ((Candle) arr[i - 1]).close - ((Candle) arr[i]).close;
                if (change > 0)
                    gainSum += change;
                else
                    lossSum -= change;
            }

            double avgGain = gainSum / PERIOD;
            double avgLoss = lossSum / PERIOD;
            double rs = (avgLoss == 0) ? 100 : avgGain / avgLoss;
            double rsi = 100 - (100 / (1 + rs));

            if (rsi < 30) {
                System.out.println("[F_RSI] Oversold (" + rsi + ") — buy signal");
                Signal sig = new Signal(params.context.ticker.name, 90, CandleSize.min1, Instant.now());
                params.repo.redis.send_signal(sig);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}