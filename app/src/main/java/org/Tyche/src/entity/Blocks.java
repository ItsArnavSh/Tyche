package org.Tyche.src.entity;

public class Blocks {
    public static class Candle {
        public double open, close, high, low, volume;

        public Candle(double open, double high, double low, double close, long volume) {
            this.open = open;
            this.close = close;
            this.high = high;
            this.low = low;
            this.volume = volume;
        }
    }

}
