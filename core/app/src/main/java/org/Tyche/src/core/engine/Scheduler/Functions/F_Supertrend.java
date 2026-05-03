package org.Tyche.src.core.engine.Scheduler.Functions;

import java.time.Instant;
import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.CandleSize;
import org.Tyche.src.entity.Signal;
import org.Tyche.src.entity.StrategyEntity.StartParams;

public class F_Supertrend extends BaseFunction {
    private static final int PERIOD = 10;
    private static final double MULTIPLIER = 3.0;

    @Override
    public void Boot(StartParams params) {
        System.out.println("[F_Supertrend Boot] Initialized for " + params.context.ticker.name);
    }

    @Override
    public void Roll(StartParams params) {
        try {
            var candles = params.repo.cache.candle_cache.get(params.context.ticker);
            if (candles == null || candles.size() < PERIOD + 5)
                return;

            var arr = candles.toArray();

            // Calculate ATR
            double atr = calculateATR(arr, PERIOD);

            double close = ((Candle) arr[0]).close;
            double high = ((Candle) arr[0]).high;
            double low = ((Candle) arr[0]).low;

            double basicUpper = ((high + low) / 2) + (MULTIPLIER * atr);
            double basicLower = ((high + low) / 2) - (MULTIPLIER * atr);

            double prevSupertrend = params.repo.cache.var_cache.getOrDefault("supertrend", basicUpper);
            double supertrend;

            if (close <= prevSupertrend) {
                supertrend = Math.max(basicUpper, prevSupertrend); // rising trend
            } else {
                supertrend = Math.min(basicLower, prevSupertrend); // falling trend
            }

            params.repo.cache.set_var("supertrend", supertrend);

            if (close > supertrend && close <= prevSupertrend) { // Flip to bullish
                System.out.println("[F_Supertrend] Bullish flip — buy signal");
                Signal sig = new Signal(params.context.ticker.name, 85, CandleSize.min1, Instant.now());
                params.repo.redis.send_signal(sig);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private double calculateATR(Object[] arr, int period) {
        double sum = 0;
        for (int i = 1; i <= period; i++) {
            Candle curr = (Candle) arr[i - 1];
            Candle prev = (Candle) arr[i];
            double tr = Math.max(curr.high - curr.low,
                    Math.max(Math.abs(curr.high - prev.close), Math.abs(curr.low - prev.close)));
            sum += tr;
        }
        return sum / period;
    }
}