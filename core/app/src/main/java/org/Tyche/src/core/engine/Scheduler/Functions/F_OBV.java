package org.Tyche.src.core.engine.Scheduler.Functions;

import java.time.Instant;
import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.CandleSize;
import org.Tyche.src.entity.Signal;
import org.Tyche.src.entity.StrategyEntity.StartParams;

public class F_OBV extends BaseFunction {

    @Override
    public void Boot(StartParams params) {
        params.repo.cache.set_var("obv", 0.0);
        System.out.println("[F_OBV Boot] Initialized");
    }

    @Override
    public void Roll(StartParams params) {
        try {
            var candles = params.repo.cache.candle_cache.get(params.context.ticker);
            if (candles == null || candles.size() < 3)
                return;

            var arr = candles.toArray();
            Candle curr = (Candle) arr[0];
            Candle prev = (Candle) arr[1];

            double prevOBV = params.repo.cache.var_cache.getOrDefault("obv", 0.0);
            double newOBV = prevOBV;

            if (curr.close > prev.close) {
                newOBV += curr.volume;
            } else if (curr.close < prev.close) {
                newOBV -= curr.volume;
            }

            params.repo.cache.set_var("obv", newOBV);

            if (curr.close > prev.close && newOBV > prevOBV) {
                System.out.println("[F_OBV] OBV rising with price — buy signal");
                Signal sig = new Signal(params.context.ticker.name, 72, CandleSize.min1, Instant.now());
                params.repo.redis.send_signal(sig);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}