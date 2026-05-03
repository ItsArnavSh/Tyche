package org.Tyche.src.core.engine.Scheduler.Functions;

import java.time.Instant;
import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.CandleSize;
import org.Tyche.src.entity.Signal;
import org.Tyche.src.entity.StrategyEntity.StartParams;

public class F_ATR extends BaseFunction {
    private static final int PERIOD = 14;

    @Override
    public void Boot(StartParams params) {
        System.out.println("[F_ATR Boot] Initialized for " + params.context.ticker.name);
    }

    @Override
    public void Roll(StartParams params) {
        try {
            var candles = params.repo.cache.candle_cache.get(params.context.ticker);
            if (candles == null || candles.size() < PERIOD + 1)
                return;

            var arr = candles.toArray();
            double atrSum = 0;

            for (int i = 1; i <= PERIOD; i++) {
                Candle curr = (Candle) arr[i - 1];
                Candle prev = (Candle) arr[i];
                double tr = Math.max(curr.high - curr.low,
                        Math.max(Math.abs(curr.high - prev.close),
                                Math.abs(curr.low - prev.close)));
                atrSum += tr;
            }

            double atr = atrSum / PERIOD;
            double price = ((Candle) arr[0]).close;
            double prevClose = ((Candle) arr[1]).close;

            // Buy on strong upward breakout
            if (price > prevClose + (atr * 1.5)) {
                System.out.println("[F_ATR] Volatility breakout detected — buy signal");
                Signal sig = new Signal(params.context.ticker.name, 82, CandleSize.min1, Instant.now());
                params.repo.redis.send_signal(sig);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}