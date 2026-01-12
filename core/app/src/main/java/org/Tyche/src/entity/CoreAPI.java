package org.Tyche.src.entity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import org.Tyche.src.entity.Blocks.Candle;

public class CoreAPI {

    public static class BootRequest {
        public ArrayList<TickerHistory> history;

        public BootRequest() {
            this.history = new ArrayList<>();
        }

        public static BootRequest generateFakeData(
                String[] tickers,
                CandleSize[] candleSizes,
                int candleCount,
                double startPrice) {

            BootRequest request = new BootRequest();

            for (String ticker : tickers) {
                TickerHistory tickerHistory = new TickerHistory(ticker);

                for (CandleSize size : candleSizes) {
                    Series series = new Series(size);
                    double price = startPrice;

                    for (int i = 0; i < candleCount; i++) {
                        // Generate random price movement with 2% volatility
                        double volatility = 0.02;
                        double change = (Math.random() - 0.5) * 2 * volatility * price;

                        double open = price;
                        double close = price + change;
                        double high = Math.max(open, close) * (1 + Math.random() * 0.01);
                        double low = Math.min(open, close) * (1 - Math.random() * 0.01);
                        long volume = (long) (Math.random() * 1000000) + 100000;

                        Candle candle = new Candle(open, high, low, close, volume);

                        series.candles.add(candle);
                        price = close; // Next candle starts where previous ended
                    }

                    tickerHistory.series.add(series);
                }

                request.history.add(tickerHistory);
            }

            return request;
        }

        /**
         * Convenience method with default parameters
         */
        public static BootRequest generateFakeData(String[] tickers, CandleSize[] candleSizes) {
            return generateFakeData(tickers, candleSizes, 100, 100.0);
        }
    }

    public static class RollRequest {
        public ArrayList<StockUpdate> update;

        public RollRequest() {
            this.update = new ArrayList<>();
        }
    }

    public static class TickerHistory {
        public String name;
        public ArrayList<Series> series;

        public TickerHistory() {
            this.series = new ArrayList<>();
        }

        public TickerHistory(String name) {
            this();
            this.name = name;
        }
    }

    public static class Series {
        public CandleSize size; // corrected to your enum type
        public ArrayDeque<Candle> candles;

        public Series() {
            this.candles = new ArrayDeque<>();
        }

        public Series(CandleSize size) {
            this();
            this.size = size;
        }
    }

    public static class StockUpdate {
        public String name;
        public ArrayList<LatestStockVal> val;

        public StockUpdate() {
            this.val = new ArrayList<>();
        }

        public StockUpdate(String name) {
            this();
            this.name = name;
        }
    }

    public static class LatestStockVal {
        public Candle candle;
        public CandleSize size;

        public LatestStockVal() {
        }

        public LatestStockVal(Candle candle, CandleSize size) {
            this.candle = candle;
            this.size = size;
        }
    }
}