package org.Tyche.src.core.engine;

import java.util.ArrayList;
import java.util.List;

import org.Tyche.src.core.engine.Workers.Rayon;
import org.Tyche.src.entity.CoreAPI.BootRequest;
import org.Tyche.src.entity.CoreAPI.RollRequest;
import org.Tyche.src.entity.Scheduler_Entity.PriorityBlock;

/**
 * Boundary between the Java engine and the Go gateway.
 *
 * <p>The Go gateway is the only owner of the market-data plane. It pushes
 * historical candles and rolling updates here; the core only schedules work
 * and runs strategies. When a ticker is stale or unknown, the job is returned
 * to the gateway, which re-boots it from its data source.</p>
 */
public class GatewayBoundary {
    private final Rayon rayon;

    public GatewayBoundary(Rayon rayon) {
        this.rayon = rayon;
    }

    public void PushHistoricalVals(BootRequest breq) {
        rayon.boot_loader(breq);
    }

    /** Returns the (ticker, candle-size) jobs that need to be re-booted by Go. */
    public ArrayList<PriorityBlock> PushLatestVals(RollRequest rreq) {
        return rayon.roll_loader(rreq);
    }

    /** Drops tickers on request from the gateway. */
    public void PushKillSignal(List<String> tickers) {
        if (tickers == null) {
            return;
        }
        for (String ticker : tickers) {
            rayon.remove_ticker(ticker);
        }
    }
}
