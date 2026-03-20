package org.Tyche.src.core.producer;

import java.util.ArrayList;
import java.util.List;

import org.Tyche.src.entity.CandleSize;
import org.Tyche.src.entity.Blocks.Candle;

public class TargetStocks {
        public static final List<String> stocks = new ArrayList<>(List.of(
                        "BSE", "ITC"));

        public static final List<CandleSize> sizes = new ArrayList<>(
                        List.of(CandleSize.sec5, CandleSize.sec30, CandleSize.min1, CandleSize.min15,
                                        CandleSize.hour1));

}
