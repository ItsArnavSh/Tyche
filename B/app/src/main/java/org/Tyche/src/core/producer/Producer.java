package org.Tyche.src.core.producer;

import java.util.ArrayList;

import org.Tyche.src.entity.CoreAPI.BootRequest;
import org.Tyche.src.entity.CoreAPI.RollRequest;
import org.Tyche.src.entity.Scheduler_Entity.PriorityBlock;

public interface Producer {
    BootRequest GetHistoricalData(ArrayList<PriorityBlock> stocks);

    RollRequest GetLatestVals(ArrayList<PriorityBlock> stocks);
}
