package org.Tyche.src.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Signal {

    @JsonProperty("name")
    private String name;

    @JsonProperty("confidence")
    private double confidence;

    @JsonProperty("size")
    private CandleSize size;

    @JsonProperty("time")
    private Instant time;

    /** Identifier of the strategy that produced the signal (e.g. "rsi"). */
    @JsonProperty("strategy")
    private String strategy;

    // --- Constructors ---

    public Signal() {
    }

    public Signal(String name, double confidence, CandleSize size, Instant time) {
        this(name, confidence, size, time, "unknown");
    }

    public Signal(String name, double confidence, CandleSize size, Instant time, String strategy) {
        this.name = name;
        this.confidence = clamp(confidence);
        this.size = size;
        this.time = time == null ? Instant.now() : time;
        this.strategy = strategy == null || strategy.isBlank() ? "unknown" : strategy;
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0;
        }
        return Math.max(0, Math.min(1, value));
    }

    // --- Getters / Setters ---

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = clamp(confidence);
    }

    public CandleSize getSize() {
        return size;
    }

    public void setSize(CandleSize size) {
        this.size = size;
    }

    public Instant getTime() {
        return time;
    }

    public void setTime(Instant time) {
        this.time = time;
    }

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    // --- JSON serialization ---

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public String toJson() throws Exception {
        return MAPPER.writeValueAsString(this);
    }

    public static Signal fromJson(String json) throws Exception {
        return MAPPER.readValue(json, Signal.class);
    }

    @Override
    public String toString() {
        try {
            return toJson();
        } catch (Exception e) {
            return super.toString();
        }
    }
}
