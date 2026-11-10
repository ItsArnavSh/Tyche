package org.Tyche.src.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CandleSizeTest {

    @Test
    void durationsMatchTheirNames() {
        assertEquals(5_000, CandleSize.sec5.get_duration_millis());
        assertEquals(30_000, CandleSize.sec30.get_duration_millis());
        assertEquals(60_000, CandleSize.min1.get_duration_millis());
        assertEquals(900_000, CandleSize.min15.get_duration_millis());
        assertEquals(3_600_000, CandleSize.hour1.get_duration_millis());
    }

    @Test
    void protoConversionsRoundTrip() {
        for (CandleSize size : CandleSize.values()) {
            stockrec.v1.Stockrec.CandleSize proto = CandleSize.ConvToProto(size);
            assertEquals(size, CandleSize.ConvToEntity(proto));
        }
    }

    @Test
    void protoValuesMapToExpectedEntities() {
        assertEquals(CandleSize.sec5, CandleSize.ConvToEntity(stockrec.v1.Stockrec.CandleSize.SEC5));
        assertEquals(CandleSize.sec30, CandleSize.ConvToEntity(stockrec.v1.Stockrec.CandleSize.SEC30));
        assertEquals(CandleSize.min1, CandleSize.ConvToEntity(stockrec.v1.Stockrec.CandleSize.MIN1));
        assertEquals(CandleSize.min15, CandleSize.ConvToEntity(stockrec.v1.Stockrec.CandleSize.MIN15));
        assertEquals(CandleSize.hour1, CandleSize.ConvToEntity(stockrec.v1.Stockrec.CandleSize.HOUR1));
    }

    @Test
    void nullProtoConversionIsRejected() {
        assertThrows(NullPointerException.class, () -> CandleSize.ConvToEntity(null));
    }
}
