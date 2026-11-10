package org.Tyche.src.core.engine.Scheduler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.Tyche.src.entity.CandleSize;
import org.Tyche.src.entity.Scheduler_Entity.PriorityBlock;
import org.Tyche.src.internal.cache.Repository;
import org.junit.jupiter.api.Test;

class LoadBalancerTest {

    @Test
    void bootActivationReleasesEveryCandleSizeImmediately() {
        LoadBalancer loadBalancer = new LoadBalancer(new Repository(false));
        try {
            List<CandleSize> sizes = List.of(
                    CandleSize.sec5,
                    CandleSize.sec30,
                    CandleSize.min1,
                    CandleSize.min15,
                    CandleSize.hour1);

            for (CandleSize size : sizes) {
                PriorityBlock block = new PriorityBlock(size, "TEST");
                loadBalancer.activate_boot(block);
                assertFalse(loadBalancer.give_funcs(block, true).isEmpty(),
                        "boot functions should be available for " + size);
                assertTrue(loadBalancer.give_funcs(block, true).isEmpty(),
                        "a boot activation should be consumed exactly once for " + size);
            }
        } finally {
            loadBalancer.shutdown();
        }
    }
}
