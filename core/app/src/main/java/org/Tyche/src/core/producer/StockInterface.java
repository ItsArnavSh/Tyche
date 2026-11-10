package org.Tyche.src.core.producer;

import org.Tyche.Config;
import org.Tyche.src.entity.CoreAPI.BootRequest;
import org.Tyche.src.entity.CoreAPI.RollRequest;
import org.Tyche.src.entity.Scheduler_Entity.PriorityBlock;
import org.Tyche.src.core.engine.Workers.Rayon;
import org.Tyche.src.core.producer.FakeVal.FakeVal;
import org.Tyche.src.core.producer.RealVal.yahoo.YahooAPI;
import org.Tyche.src.core.producer.RealVal.zerodha.ZerodhaAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Boundary between the engine and external market-data providers.  It is also
 * responsible for re-booting tickers whose data has gone stale.
 */
public class StockInterface {
    private static final Logger logger = LoggerFactory.getLogger(StockInterface.class);

    private final Producer producer;
    private final Rayon rayon;
    private final ReentrantLock lock;
    private final ArrayList<PriorityBlock> req;

    public StockInterface(Rayon rayon) {
        this(rayon, createProducer());
    }

    public StockInterface(Rayon rayon, Producer producer) {
        this.rayon = rayon;
        this.producer = producer;
        this.lock = new ReentrantLock();
        this.req = new ArrayList<>();
    }

    /** Selects the configured market-data provider. */
    public static Producer createProducer() {
        String source = Config.DATA_SOURCE;
        logger.info("Market data source: {}", source);
        return switch (source) {
            case "fake" -> new FakeVal();
            case "zerodha", "kite" -> new ZerodhaAPI();
            case "yahoo" -> new YahooAPI();
            default -> {
                logger.warn("Unknown TYCHE_DATA_SOURCE '{}'; falling back to Yahoo", source);
                yield new YahooAPI();
            }
        };
    }

    public void PushHistoricalVals(BootRequest breq) {
        rayon.boot_loader(breq);
    }

    public ArrayList<PriorityBlock> PushLatestVals(RollRequest rreq) {
        var missing = rayon.roll_loader(rreq);

        if (!missing.isEmpty()) {
            this.lock.lock();
            try {
                req.removeAll(missing);
            } finally {
                this.lock.unlock();
            }
            Runnable fixMissing = () -> {
                try {
                    var missingVals = producer.GetHistoricalData(missing);
                    if (missingVals != null && !missingVals.history.isEmpty()) {
                        this.rayon.boot_loader(missingVals);
                        this.lock.lock();
                        try {
                            this.req.addAll(missing);
                        } finally {
                            this.lock.unlock();
                        }
                    } else {
                        logger.warn("Could not re-boot {} stale ticker(s)", missing.size());
                    }
                } catch (Exception e) {
                    logger.error("Failed to re-boot stale tickers", e);
                }
            };
            Thread.ofVirtual().name("tyche-reboot").start(fixMissing);
        }
        return missing;
    }

    /** Drops tickers on request from the gateway. */
    public void PushKillSignal(List<String> tickers) {
        if (tickers == null) {
            return;
        }
        for (String ticker : tickers) {
            rayon.remove_ticker(ticker);
            this.lock.lock();
            try {
                req.removeIf(block -> block.name.equals(ticker));
            } finally {
                this.lock.unlock();
            }
        }
    }
}
