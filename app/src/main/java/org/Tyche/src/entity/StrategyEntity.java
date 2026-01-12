package org.Tyche.src.entity;

import org.Tyche.src.entity.Scheduler_Entity.PriorityBlock;
import org.Tyche.src.internal.cache.Repository;

public class StrategyEntity {
    public static class StartParams {
        Repository repo;
        Context context;

        public StartParams(Repository repo, Context context) {
            this.context = context;
            this.repo = repo;
        }
    }

    public static class Context {
        public PriorityBlock ticker;

        public Context(PriorityBlock ticker) {
            this.ticker = ticker;
        }
    }

}
