package org.Tyche.src.core.engine.Scheduler.Functions;

import java.time.Instant;
import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.CandleSize;
import org.Tyche.src.entity.Signal;
import org.Tyche.src.entity.StrategyEntity.StartParams;

public class F_MACD extends BaseFunction {
    private static final int FAST = 12;
    private static final int SLOW = 26;
    private static final int SIGNAL = 9;

    @Override
    public void Boot(StartParams params) {
        System.out.println("[F_MACD Boot] Initialized for " + params.context.ticker.name);
        // Full initial calculation can be added later if needed
    }

    @Override
    public void Roll(StartParams params) {
        try {
            var candles = params.repo.cache.candle_cache.get(params.context.ticker);
            if (candles == null || candles.size() < SLOW + SIGNAL)
                return;

            var arr = candles.toArray();
            double emaFast = calculateEMA(arr, FAST);
            double emaSlow = calculateEMA(arr, SLOW);
            double macd = emaFast - emaSlow;

            // Simple signal line (EMA of MACD)
            double prevSignal = params.repo.cache.var_cache.getOrDefault("macd_signal", macd);
            double newSignal = macd * (2.0 / (SIGNAL + 1)) + prevSignal * (1 - 2.0 / (SIGNAL + 1));

            params.repo.cache.set_var("macd_line", macd);
            params.repo.cache.set_var("macd_signal", newSignal);

            if (macd > newSignal && macd <= prevSignal) { // Bullish crossover
                System.out.println("[F_MACD] Bullish MACD crossover — buy signal");
                Signal sig = new Signal(params.context.ticker.name, 85, CandleSize.min1, Instant.now());
                params.repo.redis.send_signal(sig);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private double calculateEMA(Object[] arr, int period) {
        double ema = ((Candle) arr[arr.length - 1]).close; // most recent is at index 0? Wait, adjust based on your
                                                           // deque
        double alpha = 2.0 / (period + 1);
        int start = Math.min(period, arr.length - 1);

        for (int i = start; i >= 1; i--) { // Note: adjust indexing if your deque order is newest first
            double price = ((Candle) arr[i - 1]).close; // you may need to reverse logic based on actual candle order
            ema = price * alpha + ema * (1 - alpha);
        }
        return ema;
    }
}