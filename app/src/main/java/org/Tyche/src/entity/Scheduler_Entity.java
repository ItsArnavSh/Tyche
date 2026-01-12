package org.Tyche.src.entity;

public class Scheduler_Entity {
    public static class PriorityBlock implements Comparable<PriorityBlock> {
        public CandleSize size;
        public String name;

        public PriorityBlock(CandleSize size, String name) {
            this.size = size;
            this.name = name;
        }

        @Override
        public int compareTo(PriorityBlock other) {
            int sizeComparison = Long.compare(this.size.get_duration_millis(),
                    other.size.get_duration_millis());

            if (sizeComparison == 0) {
                return this.name.compareTo(other.name);
            }

            return sizeComparison;
        }
    }

    public static class PriorityMapVal {
        String name;
        CandleSize size;

        public PriorityMapVal(String name, CandleSize size) {
            this.name = name;
            this.size = size;
        }
    }

}
