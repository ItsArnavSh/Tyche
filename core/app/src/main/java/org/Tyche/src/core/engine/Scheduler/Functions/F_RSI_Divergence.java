package org.Tyche.src.core.engine.Scheduler.Functions;

import java.time.Instant;
import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.CandleSize;
import org.Tyche.src.entity.Signal;
import org.Tyche.src.entity.StrategyEntity.StartParams;

public class F_RSI_Divergence extends BaseFunction {
    private static final int PERIOD = 14;

    @Override
    public void Boot(StartParams params) {
        System.out.println("[F_RSI_Divergence Boot] Ready");
    }

    @Override
    public void Roll(StartParams params) {
        try {
            var candles = params.repo.cache.candle_cache.get(params.context.ticker);
            if (candles == null || candles.size() < PERIOD + 10)
                return;

            var arr = candles.toArray();

            // Calculate current RSI
            double rsi = calculateRSI(arr, PERIOD);

            // Simple bullish divergence check (price lower low, RSI higher low)
            if (rsi < 35) {
                System.out.println("[F_RSI_Divergence] RSI Oversold (" + rsi + ") — potential reversal, buy signal");
                Signal sig = new Signal(params.context.ticker.name, 78, CandleSize.min1, Instant.now());
                params.repo.redis.send_signal(sig);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private double calculateRSI(Object[] arr, int period) {
        double gain = 0, loss = 0;
        for (int i = 1; i <= period; i++) {
            double change = ((Candle) arr[i - 1]).close - ((Candle) arr[i]).close;
            if (change > 0)
                gain += change;
            else
                loss -= change;
        }
        double avgGain = gain / period;
        double avgLoss = loss / period;
        if (avgLoss == 0)
            return 100;
        double rs = avgGain / avgLoss;
        return 100 - (100 / (1 + rs));
    }
}