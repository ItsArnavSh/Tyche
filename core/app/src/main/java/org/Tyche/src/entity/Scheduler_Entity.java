package org.Tyche.src.entity;

import java.util.Objects;

public class Scheduler_Entity {
    public static class PriorityBlock implements Comparable<PriorityBlock> {
        public CandleSize size;
        public String name;

        public PriorityBlock(CandleSize size, String name) {
            this.size = size;
            this.name = name;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (!(o instanceof PriorityBlock))
                return false;
            PriorityBlock other = (PriorityBlock) o;
            return Objects.equals(this.name, other.name)
                    && Objects.equals(this.size, other.size);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.size, this.name);
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
