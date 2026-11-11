package org.Tyche.src.core.engine.Scheduler.Functions;

import org.Tyche.src.entity.StrategyEntity.StartParams;

/** Demo strategy kept for local experiments; it is not registered. */
public class F_Demo extends BaseFunction {

    @Override
    protected void evaluate(StartParams params) {
        System.out.println(
                "Inside F_DEMO time is " + params.context.ticker.size + " for " + params.context.ticker.name);
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public String family() {
        return "technical";
    }
}
