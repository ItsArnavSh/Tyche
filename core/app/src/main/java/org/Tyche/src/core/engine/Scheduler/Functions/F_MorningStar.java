package org.Tyche.src.core.engine.Scheduler.Functions;

import java.util.List;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.StrategyEntity.StartParams;

/** Three-bar morning-star reversal. */
public class F_MorningStar extends BaseFunction {

    @Override
    protected void evaluate(StartParams params) {
        List<Candle> candles = candles(params);
        if (candles.size() < 3) {
            return;
        }
        int n = candles.size();
        Candle first = candles.get(n - 3);
        Candle second = candles.get(n - 2);
        Candle third = candles.get(n - 1);
        double firstBody = Math.abs(first.close - first.open);
        double secondBody = Math.abs(second.close - second.open);
        if (firstBody <= 0) {
            return;
        }
        boolean firstBearish = first.close < first.open;
        boolean secondSmall = secondBody <= firstBody * 0.5;
        boolean thirdBullish = third.close > third.open;
        double firstMidpoint = (first.open + first.close) / 2.0;
        if (firstBearish && secondSmall && thirdBullish && third.close > firstMidpoint) {
            emit(params, 0.75, "morning-star reversal");
        }
    }

    @Override
    public String family() {
        return "price_action";
    }
}
