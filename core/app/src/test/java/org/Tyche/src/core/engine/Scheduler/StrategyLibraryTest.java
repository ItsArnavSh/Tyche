package org.Tyche.src.core.engine.Scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.Tyche.src.core.engine.Scheduler.Functions.BaseFunction;
import org.Tyche.src.core.engine.Scheduler.Functions.F_BullishEngulfing;
import org.Tyche.src.core.engine.Scheduler.Functions.F_InsideBar;
import org.Tyche.src.core.engine.Scheduler.Functions.F_Hammer;
import org.Tyche.src.core.engine.Scheduler.Functions.F_KeltnerReversion;
import org.Tyche.src.core.engine.Scheduler.Functions.F_MorningStar;
import org.Tyche.src.core.engine.Scheduler.Functions.F_NR7;
import org.Tyche.src.core.engine.Scheduler.Functions.F_SqueezeBreakout;
import org.Tyche.src.core.engine.Scheduler.Functions.F_VolumeBreakout;
import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.CandleSize;
import org.Tyche.src.entity.Scheduler_Entity.PriorityBlock;
import org.Tyche.src.entity.Signal;
import org.Tyche.src.entity.StrategyEntity.Context;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.internal.cache.Cache;
import org.Tyche.src.internal.cache.Redis;
import org.Tyche.src.internal.cache.Repository;
import org.junit.jupiter.api.Test;

class StrategyLibraryTest {

    @Test
    void registrationCountsAreExactAndNamesAreUnique() {
        FunctionAlloter alloter = new FunctionAlloter();
        Map<CandleSize, Integer> expected = Map.of(
                CandleSize.sec5, 4,
                CandleSize.sec30, 8,
                CandleSize.min1, 40,
                CandleSize.min15, 23,
                CandleSize.hour1, 15);

        int total = 0;
        for (Map.Entry<CandleSize, Integer> entry : expected.entrySet()) {
            ArrayList<String> names = alloter.get_names(entry.getKey());
            assertNotNull(names, "no registrations for " + entry.getKey());
            assertEquals(entry.getValue(), names.size(), "count for " + entry.getKey());
            assertEquals(names.size(), new HashSet<>(names).size(),
                    "duplicate strategy names for " + entry.getKey() + ": " + names);
            for (String name : names) {
                assertFalse(name.isBlank(), "blank strategy name for " + entry.getKey());
            }
            total += names.size();
        }
        assertEquals(90, total);
    }

    @Test
    void everyRegisteredStrategyRunsOnSyntheticHistory() {
        CapturingRedis redis = new CapturingRedis();
        Repository repo = new Repository(new Cache(500), redis);
        FunctionAlloter alloter = new FunctionAlloter();
        try {
            List<Candle> history = syntheticCandles(320);
            for (CandleSize size : CandleSize.values()) {
                PriorityBlock block = new PriorityBlock(size, "TEST");
                repo.cache.new_candles(block, history);
                StartParams params = new StartParams(repo, new Context(block));

                ArrayList<Consumer<StartParams>> boot = alloter.get_size_funcs(size, true);
                ArrayList<Consumer<StartParams>> roll = alloter.get_size_funcs(size, false);
                assertNotNull(boot);
                assertNotNull(roll);
                assertEquals(boot.size(), roll.size());
                for (Consumer<StartParams> function : boot) {
                    function.accept(params);
                }
                for (Consumer<StartParams> function : roll) {
                    function.accept(params);
                }
            }
            for (Signal signal : redis.signals) {
                assertNotNull(signal.getStrategy());
                assertNotNull(signal.getFamily());
                assertFalse(signal.getFamily().isBlank());
                assertTrue(signal.getConfidence() >= 0 && signal.getConfidence() <= 1);
            }
        } finally {
            redis.close();
        }
    }

    @Test
    void bootAndRollEmitFamilyMetadata() {
        CapturingRedis redis = new CapturingRedis();
        Repository repo = new Repository(new Cache(100), redis);
        try {
            PriorityBlock block = new PriorityBlock(CandleSize.sec5, "TEST");
            repo.cache.new_candles(block, List.of(
                    new Candle(10, 10.5, 7.5, 8, 100),
                    new Candle(7.8, 10.5, 7.6, 10.2, 150)));
            StartParams params = new StartParams(repo, new Context(block));

            new F_BullishEngulfing().Boot(params);

            assertEquals(1, redis.signals.size());
            Signal signal = redis.signals.get(0);
            assertEquals("TEST", signal.getName());
            assertEquals("bullishengulfing", signal.getStrategy());
            assertEquals("price_action", signal.getFamily());
            assertTrue(signal.getConfidence() > 0);
        } finally {
            redis.close();
        }
    }

    @Test
    void nr7BreakoutEmitsOnlyAfterNarrowRange() {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            candles.add(new Candle(10, 11, 8, 10, 100));
        }
        candles.add(new Candle(10.2, 10.4, 9.9, 10.2, 100));
        candles.add(new Candle(10.3, 11, 10.1, 10.8, 200));

        List<Signal> signals = runStrategy(CandleSize.sec5, candles, new F_NR7());

        assertEquals(1, signals.size());
        assertEquals("nr7", signals.get(0).getStrategy());
        assertEquals("volatility", signals.get(0).getFamily());
    }

    @Test
    void insideBarBreakoutEmitsAfterCompression() {
        List<Candle> candles = List.of(
                new Candle(10, 12, 8, 10, 100),
                new Candle(10, 11, 9, 10.5, 100),
                new Candle(10.6, 12, 10.5, 11.5, 200));

        List<Signal> signals = runStrategy(CandleSize.sec5, candles, new F_InsideBar());

        assertEquals(1, signals.size());
        assertEquals("insidebar", signals.get(0).getStrategy());
        assertEquals("volatility", signals.get(0).getFamily());
    }

    @Test
    void volumeBreakoutRequiresPriceAndVolumeExpansion() {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            candles.add(new Candle(10, 11, 9, 10, 100));
        }
        candles.add(new Candle(10.5, 12.5, 10, 12, 200));

        List<Signal> signals = runStrategy(CandleSize.sec5, candles, new F_VolumeBreakout());

        assertEquals(1, signals.size());
        assertEquals("volumebreakout", signals.get(0).getStrategy());
        assertEquals("volume", signals.get(0).getFamily());
    }

    @Test
    void keltnerReversionEmitsOnBandReclaim() {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            candles.add(new Candle(100, 101, 99, 100, 100));
        }
        candles.add(new Candle(100, 91, 89, 90, 100));
        candles.add(new Candle(96, 98, 95, 97, 100));

        List<Signal> signals = runStrategy(CandleSize.min1, candles, new F_KeltnerReversion());

        assertEquals(1, signals.size());
        assertEquals("keltnerreversion", signals.get(0).getStrategy());
        assertEquals("mean_reversion", signals.get(0).getFamily());
    }

    @Test
    void squeezeBreakoutEmitsAfterCompression() {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            candles.add(new Candle(100, 100.1, 99.9, 100, 100));
        }
        candles.add(new Candle(100, 106, 99.8, 105, 1000));

        List<Signal> signals = runStrategy(CandleSize.min1, candles, new F_SqueezeBreakout());

        assertEquals(1, signals.size());
        assertEquals("squeezebreakout", signals.get(0).getStrategy());
        assertEquals("volatility", signals.get(0).getFamily());
    }

    @Test
    void hammerEmitsAfterDowntrend() {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            double close = 100 - i;
            candles.add(new Candle(close + 1, close + 2, close - 2, close, 100));
        }
        candles.add(new Candle(80, 80.7, 77.5, 80.5, 200));

        List<Signal> signals = runStrategy(CandleSize.min1, candles, new F_Hammer());

        assertEquals(1, signals.size());
        assertEquals("hammer", signals.get(0).getStrategy());
        assertEquals("price_action", signals.get(0).getFamily());
    }

    @Test
    void morningStarEmitsOnThreeBarReversal() {
        List<Candle> candles = List.of(
                new Candle(12, 12.2, 9.8, 10, 100),
                new Candle(9.9, 10.2, 9.7, 10, 100),
                new Candle(10.1, 11.4, 10, 11.2, 200));

        List<Signal> signals = runStrategy(CandleSize.sec5, candles, new F_MorningStar());

        assertEquals(1, signals.size());
        assertEquals("morningstar", signals.get(0).getStrategy());
        assertEquals("price_action", signals.get(0).getFamily());
    }

    private static List<Signal> runStrategy(CandleSize size, List<Candle> candles, BaseFunction function) {
        CapturingRedis redis = new CapturingRedis();
        Repository repo = new Repository(new Cache(500), redis);
        try {
            PriorityBlock block = new PriorityBlock(size, "TEST");
            repo.cache.new_candles(block, candles);
            function.Boot(new StartParams(repo, new Context(block)));
            return new ArrayList<>(redis.signals);
        } finally {
            redis.close();
        }
    }

    private static List<Candle> syntheticCandles(int count) {
        List<Candle> candles = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double close = 100 + i * 0.04 + Math.sin(i / 3.0) * 4;
            double open = close - Math.sin((i + 2) / 4.0) * 2.5;
            double high = Math.max(open, close) + 0.8 + (i % 5) * 0.1;
            double low = Math.min(open, close) - 0.8 - (i % 3) * 0.1;
            long volume = 1000 + (i % 20) * 100L + (i % 7) * 50L;
            candles.add(new Candle(open, high, low, close, volume));
        }
        return candles;
    }

    private static final class CapturingRedis extends Redis {
        private final List<Signal> signals = new ArrayList<>();

        CapturingRedis() {
            super("127.0.0.1", 6379, "test", "test-signals", false);
        }

        @Override
        public boolean send_signal(Signal sig) {
            if (sig != null) {
                signals.add(sig);
            }
            return true;
        }
    }
}
