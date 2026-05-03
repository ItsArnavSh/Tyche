package org.Tyche.src.core.engine.Scheduler.Functions;

import java.time.Instant;
import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.CandleSize;
import org.Tyche.src.entity.Signal;
import org.Tyche.src.entity.StrategyEntity.StartParams;

public class F_Stochastic extends BaseFunction {
    private static final int K_PERIOD = 14;
    private static final int D_PERIOD = 3;

    @Override
    public void Boot(StartParams params) {
        System.out.println("[F_Stochastic Boot] Initialized");
    }

    @Override
    public void Roll(StartParams params) {
        try {
            var candles = params.repo.cache.candle_cache.get(params.context.ticker);
            if (candles == null || candles.size() < K_PERIOD + 5)
                return;

            var arr = candles.toArray();

            double highestHigh = Double.MIN_VALUE;
            double lowestLow = Double.MAX_VALUE;

            for (int i = 0; i < K_PERIOD; i++) {
                Candle c = (Candle) arr[i];
                highestHigh = Math.max(highestHigh, c.high);
                lowestLow = Math.min(lowestLow, c.low);
            }

            double currentClose = ((Candle) arr[0]).close;
            double k = (lowestLow == highestHigh) ? 50 : ((currentClose - lowestLow) / (highestHigh - lowestLow)) * 100;

            // Simple %D (SMA of %K)
            double prevD = params.repo.cache.var_cache.getOrDefault("stoch_d", k);
            double newD = (prevD * (D_PERIOD - 1) + k) / D_PERIOD;

            params.repo.cache.set_var("stoch_k", k);
            params.repo.cache.set_var("stoch_d", newD);

            if (k < 20 && newD > prevD) { // Oversold + %K crossing above %D
                System.out.println("[F_Stochastic] Oversold crossover — buy signal");
                Signal sig = new Signal(params.context.ticker.name, 75, CandleSize.min1, Instant.now());
                params.repo.redis.send_signal(sig);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}