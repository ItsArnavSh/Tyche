package org.Tyche.src.core.engine.Scheduler.Functions;

import java.time.Instant;
import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.CandleSize;
import org.Tyche.src.entity.Signal;
import org.Tyche.src.entity.StrategyEntity.StartParams;

public class F_DoubleSMA extends BaseFunction {
    private static final int FAST = 9;
    private static final int SLOW = 21;

    @Override
    public void Boot(StartParams params) {
        try {
            var candles = params.repo.cache.candle_cache.get(params.context.ticker);
            if (candles == null || candles.size() < SLOW)
                return;

            double sumFast = 0, sumSlow = 0;
            int i = 0;
            for (var c : candles) {
                if (i >= SLOW)
                    break;
                if (i < FAST)
                    sumFast += c.close;
                sumSlow += c.close;
                i++;
            }

            double smaFast = sumFast / FAST;
            double smaSlow = sumSlow / SLOW;

            params.repo.cache.set_var("sma_fast", smaFast);
            params.repo.cache.set_var("sma_slow", smaSlow);

            double price = candles.peekFirst().close;
            if (price > smaFast && smaFast > smaSlow) {
                Signal sig = new Signal(params.context.ticker.name, 90, CandleSize.min1, Instant.now());
                params.repo.redis.send_signal(sig);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void Roll(StartParams params) {
        try {
            var candles = params.repo.cache.candle_cache.get(params.context.ticker);
            if (candles == null || candles.size() < SLOW)
                return;

            double prevFast = params.repo.cache.var_cache.getOrDefault("sma_fast", Double.NaN);
            double prevSlow = params.repo.cache.var_cache.getOrDefault("sma_slow", Double.NaN);
            if (Double.isNaN(prevFast) || Double.isNaN(prevSlow))
                return;

            double newest = candles.peekFirst().close;
            var arr = candles.toArray();
            double oldestFast = ((Candle) arr[FAST]).close;
            double oldestSlow = ((Candle) arr[SLOW]).close;

            double newFast = (prevFast * FAST + newest - oldestFast) / FAST;
            double newSlow = (prevSlow * SLOW + newest - oldestSlow) / SLOW;

            params.repo.cache.set_var("sma_fast", newFast);
            params.repo.cache.set_var("sma_slow", newSlow);

            if (newFast > newSlow && prevFast <= prevSlow) {
                System.out.println("[F_DoubleSMA] Golden Cross — buy signal");
                Signal sig = new Signal(params.context.ticker.name, 90, CandleSize.min1, Instant.now());
                params.repo.redis.send_signal(sig);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}