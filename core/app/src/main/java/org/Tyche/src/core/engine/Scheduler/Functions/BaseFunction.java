package org.Tyche.src.core.engine.Scheduler.Functions;

import java.time.Instant;
import java.util.List;

import org.Tyche.Config;
import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.Signal;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class BaseFunction {

    protected static final Logger logger = LoggerFactory.getLogger(BaseFunction.class);

    /**
     * Boot runs after a full history bootstrap.  By default it evaluates the
     * same latest-bar logic as a roll, but strategies can override it when
     * they need extra warm-up work.
     */
    public void Boot(StartParams params) {
        evaluate(params);
    }

    /** Roll runs after a single incremental candle update. */
    public void Roll(StartParams params) {
        evaluate(params);
    }

    /** Strategy evaluation for the latest completed candle. */
    protected abstract void evaluate(StartParams params);

    /** Stable strategy identifier used for signal de-duplication and metrics. */
    public String name() {
        return getClass().getSimpleName().replace("F_", "").toLowerCase();
    }

    /**
     * Coarse strategy family.  The gateway uses this to stop correlated
     * signals from being counted as independent confirmation.
     */
    public String family() {
        return "technical";
    }

    /** Minimum gap between signals for this strategy/ticker. */
    protected long cooldownMillis(StartParams params) {
        return Math.max(Config.SIGNAL_COOLDOWN_MILLIS,
                params.context.ticker.size.get_duration_millis());
    }

    /** Convenience: chronological candles (oldest first) for indicator math. */
    protected List<Candle> candles(StartParams params) {
        return params.repo.cache.get_candles_chronological(params.context.ticker);
    }

    /**
     * Emits a signal if the per-strategy cooldown has elapsed.  Returns true
     * when a signal was actually queued.
     */
    protected boolean emit(StartParams params, double confidence, String reason) {
        if (!params.repo.cache.tryAcquireSignalSlot(params.context.ticker, name(), cooldownMillis(params))) {
            logger.debug("{} signal for {} suppressed by cooldown", name(), params.context.ticker.name);
            return false;
        }
        Signal sig = new Signal(params.context.ticker.name, confidence, params.context.ticker.size,
                Instant.now(), name(), family());
        boolean queued = params.repo.redis.send_signal(sig);
        if (queued) {
            logger.debug("[{}] {} {}: {}", name(), params.context.ticker.name, reason, sig);
        }
        return queued;
    }
}
