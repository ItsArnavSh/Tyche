package org.Tyche.src.core.engine.Scheduler.Functions;

import org.Tyche.src.entity.StrategyEntity.StartParams;

public class F_Demo extends BaseFunction {

    @Override
    public void Boot(StartParams params) {
        System.out.println("Booted into demo");
    }

    @Override
    public void Roll(StartParams params) {
        System.out.println("Inside Rolling F_DEMO");
        try {
            Thread.sleep(2000);
        } catch (Exception e) {
        }
    }
}
