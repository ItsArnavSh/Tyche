package org.Tyche.src.core.engine.Workers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.Tyche.Config;
import org.Tyche.src.entity.CandleSize;
import org.Tyche.src.entity.Scheduler_Entity.PriorityBlock;
import org.Tyche.src.util.BotClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StockHandlerTest {

    /** Controllable clock so stale checks are deterministic and fast. */
    private static final class FakeClock extends BotClock {
        private long now;

        @Override
        public long time_elapsed() {
            return now;
        }

        void advance(long millis) {
            now += millis;
        }
    }

    private static final PriorityBlock TCS_MIN1 = new PriorityBlock(CandleSize.min1, "TCS");
    private static final PriorityBlock TCS_MIN15 = new PriorityBlock(CandleSize.min15, "TCS");

    private FakeClock clock;
    private StockHandler handler;
    private long staleThreshold;

    @BeforeEach
    void setUp() {
        clock = new FakeClock();
        handler = new StockHandler(clock);
        staleThreshold = (long) (CandleSize.min1.get_duration_millis() * Config.STALE_MULTIPLIER);
    }

    @Test
    void unknownTickersNeedBoot() {
        assertTrue(handler.boot_check(TCS_MIN1));
        assertTrue(handler.stale_check(TCS_MIN1));
        assertEquals(0, handler.active_count());
    }

    @Test
    void markingBootedMovesTickerToRollingMode() {
        handler.mark_booted(TCS_MIN1);

        assertFalse(handler.boot_check(TCS_MIN1));
        assertFalse(handler.stale_check(TCS_MIN1));
        assertEquals(1, handler.active_count());
    }

    @Test
    void dataBecomesStaleAfterThresholdAndIsDropped() {
        handler.mark_booted(TCS_MIN1);
        clock.advance(staleThreshold);

        assertFalse(handler.stale_check(TCS_MIN1), "exactly at the threshold is still fresh");

        clock.advance(1);

        assertTrue(handler.stale_check(TCS_MIN1));
        assertEquals(0, handler.active_count());
        assertTrue(handler.boot_check(TCS_MIN1), "a stale ticker requires a fresh boot");
    }

    @Test
    void touchRefreshesFreshnessWindow() {
        handler.mark_booted(TCS_MIN1);
        clock.advance(staleThreshold - 1);
        handler.touch(TCS_MIN1);
        clock.advance(staleThreshold);

        assertFalse(handler.stale_check(TCS_MIN1));
    }

    @Test
    void tickersAreTrackedIndependently() {
        handler.mark_booted(TCS_MIN1);
        handler.mark_booted(TCS_MIN15);
        clock.advance(staleThreshold + 1);

        assertTrue(handler.stale_check(TCS_MIN1));
        assertFalse(handler.stale_check(TCS_MIN15));
        assertEquals(1, handler.active_count());
    }

    @Test
    void removeForgetsTicker() {
        handler.mark_booted(TCS_MIN1);
        handler.remove(TCS_MIN1);

        assertEquals(0, handler.active_count());
        assertTrue(handler.boot_check(TCS_MIN1));
    }

    @Test
    void nullInputsAreHandledSafely() {
        handler.mark_booted(null);
        handler.touch(null);
        handler.remove(null);

        assertEquals(0, handler.active_count());
        assertTrue(handler.boot_check(null));
        assertTrue(handler.stale_check(null));
        assertTrue(handler.stale_check(new PriorityBlock(null, "TCS")));
    }
}
