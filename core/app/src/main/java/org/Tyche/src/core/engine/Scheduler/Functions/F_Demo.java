package org.Tyche.src.core.engine.Scheduler.Functions;

import org.Tyche.src.entity.StrategyEntity.StartParams;

public class F_Demo extends BaseFunction {

    @Override
    public void Boot(StartParams params) {
        BaseFunction.logger.info("Booted into demo");
    }

    @Override
    public void Roll(StartParams params) {
        BaseFunction.logger.info("Rolling Demo");
    }
}
