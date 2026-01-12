package org.Tyche.src.util;

public class BotClock {
    long start;

    public BotClock() {
        this.start = System.currentTimeMillis();
    }

    public long time_elapsed() {
        return System.currentTimeMillis() - this.start;
    }

}
