package org.Tyche.src.core.producer.FakeVal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.Tyche.src.core.producer.Producer;
import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.CandleSize;
import org.Tyche.src.entity.CoreAPI.BootRequest;
import org.Tyche.src.entity.CoreAPI.LatestStockVal;
import org.Tyche.src.entity.CoreAPI.RollRequest;
import org.Tyche.src.entity.CoreAPI.Series;
import org.Tyche.src.entity.CoreAPI.StockUpdate;
import org.Tyche.src.entity.CoreAPI.TickerHistory;
import org.Tyche.src.entity.Scheduler_Entity.PriorityBlock;

public class FakeVal implements Producer {

    private int candleCount;
    private double startPrice;
    private Map<String, Double> currentPrices;

    public FakeVal() {
        this(100, 100.0);
    }

    public FakeVal(int candleCount, double startPrice) {
        this.candleCount = candleCount;
        this.startPrice = startPrice;
        this.currentPrices = new HashMap<>();
    }

    @Override
    public BootRequest GetHistoricalData(ArrayList<PriorityBlock> stocks) {
        BootRequest request = new BootRequest();

        // Group stocks by ticker name
        Map<String, Set<CandleSize>> tickerMap = new HashMap<>();
        for (PriorityBlock block : stocks) {
            tickerMap.computeIfAbsent(block.name, k -> new HashSet<>()).add(block.size);
        }

        // Generate fake data for each ticker
        for (Map.Entry<String, Set<CandleSize>> entry : tickerMap.entrySet()) {
            String ticker = entry.getKey();
            Set<CandleSize> sizes = entry.getValue();

            TickerHistory tickerHistory = new TickerHistory(ticker);
            double price = startPrice;

            for (CandleSize size : sizes) {
                Series series = new Series(size);
                price = startPrice; // Reset for each series

                for (int i = 0; i < candleCount; i++) {
                    Candle candle = generateCandle(price);
                    series.candles.add(candle);
                    price = candle.close;
                }

                tickerHistory.series.add(series);
                // Store the last price for this ticker
                currentPrices.put(ticker, price);
            }

            request.history.add(tickerHistory);
        }

        return request;
    }

    @Override
    public RollRequest GetLatestVals(ArrayList<PriorityBlock> stocks) {
        RollRequest request = new RollRequest();

        // Group stocks by ticker name
        Map<String, Set<CandleSize>> tickerMap = new HashMap<>();
        for (PriorityBlock block : stocks) {
            tickerMap.computeIfAbsent(block.name, k -> new HashSet<>()).add(block.size);
        }

        // Generate latest values for each ticker
        for (Map.Entry<String, Set<CandleSize>> entry : tickerMap.entrySet()) {
            String ticker = entry.getKey();
            Set<CandleSize> sizes = entry.getValue();

            StockUpdate update = new StockUpdate(ticker);

            // Get or initialize current price for this ticker
            double currentPrice = currentPrices.getOrDefault(ticker, startPrice);

            for (CandleSize size : sizes) {
                Candle candle = generateCandle(currentPrice);
                LatestStockVal latestVal = new LatestStockVal(candle, size);
                update.val.add(latestVal);
            }

            // Update stored price
            currentPrices.put(ticker, update.val.get(0).candle.close);
            request.update.add(update);
        }

        return request;
    }

    private Candle generateCandle(double currentPrice) {
        // Generate random price movement with 2% volatility
        double volatility = 0.02;
        double change = (Math.random() - 0.5) * 2 * volatility * currentPrice;
        double open = currentPrice;
        double close = currentPrice + change;
        double high = Math.max(open, close) * (1 + Math.random() * 0.01);
        double low = Math.min(open, close) * (1 - Math.random() * 0.01);
        long volume = (long) (Math.random() * 1000000) + 100000;

        return new Candle(open, high, low, close, volume);
    }
}
