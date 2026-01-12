package org.Tyche.src.core.producer.RealVal.zerodha;

import java.util.ArrayList;

import org.Tyche.src.core.producer.RealVal.StockAPI;
import org.Tyche.src.entity.CoreAPI.BootRequest;
import org.Tyche.src.entity.CoreAPI.RollRequest;
import org.Tyche.src.entity.Scheduler_Entity.PriorityBlock;

public class ZerodhaAPI extends StockAPI {

    @Override
    public BootRequest GetHistoricalData(ArrayList<PriorityBlock> stocks) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'GetHistoricalData'");
    }

    @Override
    public RollRequest GetLatestVals(ArrayList<PriorityBlock> stocks) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'GetLatestVals'");
    }

}
