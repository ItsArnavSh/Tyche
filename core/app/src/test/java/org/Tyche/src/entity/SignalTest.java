package org.Tyche.src.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class SignalTest {

    @Test
    void constructorClampsConfidenceAndAppliesDefaults() {
        Signal high = new Signal("TCS", 1.7, CandleSize.min1, null, "rsi");
        Signal low = new Signal("TCS", -0.2, CandleSize.min1, null, "rsi");
        Signal nan = new Signal("TCS", Double.NaN, CandleSize.min1, null, "rsi");
        Signal blankStrategy = new Signal("TCS", 0.5, CandleSize.min1, null, "  ");
        Signal withFamily = new Signal("TCS", 0.7, CandleSize.min1, null, "rsi", "momentum");

        assertEquals(1, high.getConfidence(), 1e-9);
        assertEquals(0, low.getConfidence(), 1e-9);
        assertEquals(0, nan.getConfidence(), 1e-9);
        assertNotNull(high.getTime());
        assertEquals("unknown", blankStrategy.getStrategy());
        assertEquals("momentum", withFamily.getFamily());
    }

    @Test
    void threeArgumentConstructorUsesUnknownStrategy() {
        Signal signal = new Signal("INFY", 0.6, CandleSize.hour1, Instant.parse("2026-01-01T00:00:00Z"));
        assertEquals("unknown", signal.getStrategy());
        assertEquals("unknown", signal.getFamily());
    }

    @Test
    void settersClampConfidence() {
        Signal signal = new Signal();
        signal.setConfidence(12);
        assertEquals(1, signal.getConfidence(), 1e-9);
        signal.setConfidence(-12);
        assertEquals(0, signal.getConfidence(), 1e-9);
    }

    @Test
    void jsonRoundTripPreservesAllFields() throws Exception {
        Instant time = Instant.parse("2026-11-06T10:15:30Z");
        Signal original = new Signal("RELIANCE.NS", 0.82, CandleSize.min15, time, "bollinger", "mean_reversion");

        String json = original.toJson();
        Signal restored = Signal.fromJson(json);

        assertTrue(json.contains("\"size\":\"min15\""));
        assertTrue(json.contains("\"strategy\":\"bollinger\""));
        assertTrue(json.contains("\"family\":\"mean_reversion\""));
        assertTrue(json.contains("\"time\":\"2026-11-06T10:15:30Z\""));
        assertEquals(original.getName(), restored.getName());
        assertEquals(original.getConfidence(), restored.getConfidence(), 1e-9);
        assertEquals(original.getSize(), restored.getSize());
        assertEquals(original.getTime(), restored.getTime());
        assertEquals(original.getStrategy(), restored.getStrategy());
        assertEquals(original.getFamily(), restored.getFamily());
    }

    @Test
    void unknownJsonFieldsAreIgnored() throws Exception {
        Signal signal = Signal.fromJson(
                "{\"name\":\"TCS\",\"confidence\":0.4,\"size\":\"min1\","
                        + "\"time\":\"2026-01-02T03:04:05Z\",\"strategy\":\"sma\",\"extra\":true}");

        assertEquals("TCS", signal.getName());
        assertEquals(0.4, signal.getConfidence(), 1e-9);
        assertEquals(CandleSize.min1, signal.getSize());
        assertEquals("sma", signal.getStrategy());
        assertEquals(Instant.parse("2026-01-02T03:04:05Z"), signal.getTime());
    }

    @Test
    void toStringFallsBackToJson() {
        Signal signal = new Signal("TCS", 0.5, CandleSize.sec5, Instant.parse("2026-01-01T00:00:00Z"), "ema");
        String text = signal.toString();

        assertTrue(text.startsWith("{"));
        assertTrue(text.contains("\"strategy\":\"ema\""));
        assertFalse(text.contains("Signal@"));
    }
}
