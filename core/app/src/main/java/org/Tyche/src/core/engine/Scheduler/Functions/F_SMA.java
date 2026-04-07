package org.Tyche.src.core.engine.Scheduler.Functions;

import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.entity.Blocks.Candle;

public class F_SMA extends BaseFunction {

    private static final int PERIOD = 14;

    @Override
    public void Boot(StartParams params) {
        try {
            var candles = params.repo.cache.candle_cache.get(params.context.ticker);

            if (candles == null) {
                System.err.println("[F_SMA Boot] No candle data for ticker: " + params.context.ticker.name);
                return;
            }
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

            params.repo.cache.set_var("sma_" + PERIOD, sum / PERIOD);
            System.out.println("[F_SMA Boot] OK — sma_" + PERIOD + " = " + sum / PERIOD);

        } catch (NullPointerException e) {
            System.err.println("[F_SMA Boot] Null reference — params or repo may be uninitialized: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("[F_SMA Boot] Unexpected error: " + e.getMessage());
        }
    }

    @Override
    public void Roll(StartParams params) {
        try {
            var candles = params.repo.cache.candle_cache.get(params.context.ticker);

            if (candles == null) {
                System.err.println("[F_SMA Roll] No candle data for ticker: " + params.context.ticker.name);
                return;
            }
            if (candles.size() < PERIOD) {
                System.err.println("[F_SMA Roll] Insufficient candles — need " + PERIOD + ", got " + candles.size());
                return;
            }

            double raw_sma = params.repo.cache.var_cache.getOrDefault("sma_" + PERIOD, Double.NaN);
            if (Double.isNaN(raw_sma)) {
                System.err.println("[F_SMA Roll] sma_" + PERIOD + " not in cache — Boot may not have run");
                return;
            }

            double newest = candles.peekFirst().close;
            if (Double.isNaN(newest) || Double.isInfinite(newest)) {
                System.err.println("[F_SMA Roll] Bad newest close: " + newest);
                return;
            }

            var arr = candles.toArray();
            if (arr.length <= PERIOD) {
                System.err.println("[F_SMA Roll] Array too short to get oldest at index " + PERIOD);
                return;
            }

            double oldest = ((Candle) arr[PERIOD]).close;
            if (Double.isNaN(oldest) || Double.isInfinite(oldest)) {
                System.err.println("[F_SMA Roll] Bad oldest close at index " + PERIOD + ": " + oldest);
                return;
            }

            double result = (raw_sma * PERIOD + newest - oldest) / PERIOD;
            params.repo.cache.set_var("sma_" + PERIOD, result);

        } catch (NullPointerException e) {
            System.err.println("[F_SMA Roll] Null reference — " + e.getMessage());
        } catch (ArrayIndexOutOfBoundsException e) {
            System.err.println("[F_SMA Roll] Array index out of bounds during oldest fetch: " + e.getMessage());
        } catch (ClassCastException e) {
            System.err.println("[F_SMA Roll] Cast failed on candle array element: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("[F_SMA Roll] Unexpected error: " + e.getMessage());
        }
    }
}