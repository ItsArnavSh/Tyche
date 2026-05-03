package org.Tyche.src.core.engine.Scheduler.Functions;

import java.time.Instant;
import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.CandleSize;
import org.Tyche.src.entity.Signal;
import org.Tyche.src.entity.StrategyEntity.StartParams;

public class F_SMA extends BaseFunction {

    private static final int PERIOD = 14;

    @Override
    public void Boot(StartParams params) {
        try {
            System.out.println("[F_SMA Boot] Called for ticker: " + params.context.ticker.name);

            var candles = params.repo.cache.candle_cache.get(params.context.ticker);

            if (candles == null) {
                System.err.println("[F_SMA Boot] No candle data for ticker: " + params.context.ticker.name);
                return;
            }
            System.out.println("[F_SMA Boot] Candle count: " + candles.size());

            if (candles.size() < PERIOD) {
                System.err.println("[F_SMA Boot] Insufficient candles — need " + PERIOD + ", got " + candles.size());
                return;
            }

            double sum = 0;
            int i = 0;
            for (var c : candles) {
                if (i++ >= PERIOD)
                    break;
                if (Double.isNaN(c.close) || Double.isInfinite(c.close)) {
                    System.err.println("[F_SMA Boot] Bad close value at index " + i + ": " + c.close);
                    return;
                }
                sum += c.close;
            }

            double sma = sum / PERIOD;
            params.repo.cache.set_var("sma_" + PERIOD, sma);
            System.out.println("[F_SMA Boot] OK — sma_" + PERIOD + " = " + sma);

            double price = candles.peekFirst().close;
            System.out.println("[F_SMA Boot] Current price: " + price + " SMA: " + sma);

            if (price > sma) {
                System.out.println("[F_SMA Boot] Price above SMA on boot — sending buy signal");
                Signal sig = new Signal(params.context.ticker.name, 90, CandleSize.min1, Instant.now());
                System.out.println("[F_SMA Boot] Signal: " + sig);
                params.repo.redis.send_signal(sig);
                System.out.println("[F_SMA Boot] send_signal returned — check Redis");
            } else {
                System.out.println(
                        "[F_SMA Boot] Price below SMA on boot — no signal sent (price=" + price + " sma=" + sma + ")");
            }

        } catch (NullPointerException e) {
            System.err.println("[F_SMA Boot] Null reference — params or repo may be uninitialized: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("[F_SMA Boot] Unexpected error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void Roll(StartParams params) {
        try {
            System.out.println(
                    "[F_SMA Roll] Called for ticker: " + params.context.ticker.name);

            var candles = params.repo.cache.candle_cache.get(
                    params.context.ticker);

            if (candles == null) {
                System.err.println(
                        "[F_SMA Roll] No candle data for ticker: " +
                                params.context.ticker.name);
                return;
            }
            System.out.println("[F_SMA Roll] Candle count: " + candles.size());

            if (candles.size() < PERIOD) {
                System.err.println(
                        "[F_SMA Roll] Insufficient candles — need " +
                                PERIOD +
                                ", got " +
                                candles.size());
                return;
            }

            double raw_sma = params.repo.cache.var_cache.getOrDefault(
                    "sma_" + PERIOD,
                    Double.NaN);
            System.out.println("[F_SMA Roll] raw_sma from cache: " + raw_sma);

            if (Double.isNaN(raw_sma)) {
                System.err.println(
                        "[F_SMA Roll] sma_" +
                                PERIOD +
                                " not in cache — Boot may not have run");
                return;
            }

            double newest = candles.peekFirst().close;
            System.out.println("[F_SMA Roll] Newest close: " + newest);

            if (Double.isNaN(newest) || Double.isInfinite(newest)) {
                System.err.println("[F_SMA Roll] Bad newest close: " + newest);
                return;
            }

            var arr = candles.toArray();
            System.out.println(
                    "[F_SMA Roll] Array length: " +
                            arr.length +
                            ", need > " +
                            PERIOD);

            if (arr.length <= PERIOD) {
                System.err.println(
                        "[F_SMA Roll] Array too short to get oldest at index " +
                                PERIOD);
                return;
            }

            double oldest = ((Candle) arr[PERIOD]).close;
            System.out.println(
                    "[F_SMA Roll] Oldest close (index " + PERIOD + "): " + oldest);

            if (Double.isNaN(oldest) || Double.isInfinite(oldest)) {
                System.err.println(
                        "[F_SMA Roll] Bad oldest close at index " +
                                PERIOD +
                                ": " +
                                oldest);
                return;
            }

            double prev_sma = raw_sma;
            double new_sma = (raw_sma * PERIOD + newest - oldest) / PERIOD;
            System.out.println(
                    "[F_SMA Roll] prev_sma=" +
                            prev_sma +
                            " new_sma=" +
                            new_sma +
                            " newest=" +
                            newest);
            System.out.println(
                    "[F_SMA Roll] Crossover check — newest > new_sma: " +
                            (newest > new_sma) +
                            ", newest <= prev_sma: " +
                            (newest <= prev_sma));

            params.repo.cache.set_var("sma_" + PERIOD, new_sma);

            if (newest > new_sma && newest <= prev_sma) {
                System.out.println(
                        "[F_SMA Roll] Crossover confirmed — sending buy signal");
                Signal sig = new Signal(
                        params.context.ticker.name,
                        90,
                        CandleSize.min1,
                        Instant.now());
                System.out.println("[F_SMA Roll] Signal object: " + sig);
                params.repo.redis.send_signal(sig);
                System.out.println(
                        "[F_SMA Roll] send_signal returned — check Redis");
            } else {
                System.out.println(
                        "[F_SMA Roll] No crossover this tick — no signal sent");
            }
        } catch (NullPointerException e) {
            System.err.println(
                    "[F_SMA Roll] Null reference — " + e.getMessage());
            e.printStackTrace();
        } catch (ArrayIndexOutOfBoundsException e) {
            System.err.println(
                    "[F_SMA Roll] Array index out of bounds during oldest fetch: " +
                            e.getMessage());
            e.printStackTrace();
        } catch (ClassCastException e) {
            System.err.println(
                    "[F_SMA Roll] Cast failed on candle array element: " +
                            e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println(
                    "[F_SMA Roll] Unexpected error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
