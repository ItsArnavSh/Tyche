package org.Tyche.src.entity;

import org.Tyche.src.entity.Blocks.Candle;

public enum CandleSize {
    sec5(5 * 1000l),
    sec30(30 * 1000l),
    min1(60 * 1000l),
    min15(60 * 15 * 1000l),
    hour1(60 * 60 * 1000l);

    private final long durationinmillis;

    CandleSize(long durationinmillis) {
        this.durationinmillis = durationinmillis;
    }

    public long get_duration_millis() {
        return durationinmillis;
    }

    public static CandleSize ConvToEntity(stockrec.v1.Stockrec.CandleSize size) {
        switch (size) {
            case HOUR1:
                return hour1;
            case MIN15:
                return min15;
            case MIN1:
                return min1;
            case SEC30:
                return sec30;
            case SEC5:
                return sec5;
            default:
                return hour1;
        }
    }

    public static stockrec.v1.Stockrec.CandleSize ConvToProto(CandleSize size) {
        return switch (size) {
            case sec30 -> stockrec.v1.Stockrec.CandleSize.SEC30;
            case min1 -> stockrec.v1.Stockrec.CandleSize.MIN1;
            case min15 -> stockrec.v1.Stockrec.CandleSize.MIN15;
            case hour1 -> stockrec.v1.Stockrec.CandleSize.HOUR1;
            default -> stockrec.v1.Stockrec.CandleSize.SEC5;
        };
    }
}