package org.Tyche.src.entity;

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
}