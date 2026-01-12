package org.Tyche.src.core.engine.Scheduler.Functions;

import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class BaseFunction {

    protected static final Logger logger = LoggerFactory.getLogger(BaseFunction.class);;

    abstract public void Boot(StartParams params);

    abstract public void Roll(StartParams params);
}
