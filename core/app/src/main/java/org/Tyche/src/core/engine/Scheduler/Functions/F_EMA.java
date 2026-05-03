package org.Tyche.src.core.engine.Scheduler.Functions;

import java.time.Instant;
import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.CandleSize;
import org.Tyche.src.entity.Signal;
import org.Tyche.src.entity.StrategyEntity.StartParams;

public class F_EMA extends BaseFunction {
    private static final int PERIOD = 14;
    private static final double ALPHA = 2.0 / (PERIOD + 1);

    @Override
    public void Boot(StartParams params) {
        try {
            System.out.println("[F_EMA Boot] Called for ticker: " + params.context.ticker.name);
            var candles = params.repo.cache.candle_cache.get(params.context.ticker);
            if (candles == null || candles.size() < PERIOD) {
                System.err.println("[F_EMA Boot] Insufficient candles");
                return;
            }

            double ema = 0;
            int count = 0;
            for (var c : candles) {
                if (count++ >= PERIOD)
                    break;
                if (Double.isNaN(c.close))
                    return;
                ema = (count == 1) ? c.close : (c.close * ALPHA + ema * (1 - ALPHA));
            }

            params.repo.cache.set_var("ema_" + PERIOD, ema);
            double price = candles.peekFirst().close;

            if (price > ema) {
                System.out.println("[F_EMA Boot] Price above EMA — sending buy signal");
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
            if (candles == null || candles.size() < PERIOD)
                return;

            double prev_ema = params.repo.cache.var_cache.getOrDefault("ema_" + PERIOD, Double.NaN);
            if (Double.isNaN(prev_ema))
                return;

            double newest = candles.peekFirst().close;
            double new_ema = newest * ALPHA + prev_ema * (1 - ALPHA);

            params.repo.cache.set_var("ema_" + PERIOD, new_ema);

            if (newest > new_ema && newest <= prev_ema) {
                System.out.println("[F_EMA Roll] Bullish EMA crossover — sending buy signal");
                Signal sig = new Signal(params.context.ticker.name, 90, CandleSize.min1, Instant.now());
                params.repo.redis.send_signal(sig);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}