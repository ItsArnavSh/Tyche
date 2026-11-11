package entity

import (
	"encoding/json"
	"testing"
	"time"
)

func TestCandleSizeDurationAndString(t *testing.T) {
	cases := []struct {
		size CandleSize
		dur  time.Duration
		str  string
	}{
		{SEC5, 5 * time.Second, "sec5"},
		{SEC30, 30 * time.Second, "sec30"},
		{MIN1, time.Minute, "min1"},
		{MIN15, 15 * time.Minute, "min15"},
		{HOUR1, time.Hour, "hour1"},
	}
	for _, tc := range cases {
		if got := tc.size.Duration(); got != tc.dur {
			t.Fatalf("%v duration = %v, want %v", tc.size, got, tc.dur)
		}
		if got := tc.size.String(); got != tc.str {
			t.Fatalf("%v string = %q, want %q", tc.size, got, tc.str)
		}
		if got := tc.size.GetMilliSize(); got != float64(tc.dur.Milliseconds()) {
			t.Fatalf("%v millis = %v", tc.size, got)
		}
	}
}

func TestParseCandleSize(t *testing.T) {
	for _, raw := range []string{"sec5", " SEC30 ", "Min1", "min15", "HOUR1"} {
		if _, err := ParseCandleSize(raw); err != nil {
			t.Fatalf("ParseCandleSize(%q): %v", raw, err)
		}
	}
	if _, err := ParseCandleSize("daily"); err == nil {
		t.Fatal("expected unknown size error")
	}
}

func TestCandleSizeJSON(t *testing.T) {
	encoded, err := json.Marshal(MIN15)
	if err != nil {
		t.Fatal(err)
	}
	if string(encoded) != `"min15"` {
		t.Fatalf("encoded = %s", encoded)
	}
	var decoded CandleSize
	if err := json.Unmarshal(encoded, &decoded); err != nil || decoded != MIN15 {
		t.Fatalf("decoded = %v, err=%v", decoded, err)
	}
	if err := json.Unmarshal([]byte("3"), &decoded); err != nil || decoded != MIN15 {
		t.Fatalf("numeric decoded = %v, err=%v", decoded, err)
	}
	if err := json.Unmarshal([]byte("99"), &decoded); err == nil {
		t.Fatal("expected out-of-range error")
	}
}

func TestSignalExpiryAndStrategyKey(t *testing.T) {
	now := time.Now()
	signal := Signal{Name: "X", Confidence: 0.8, Size: MIN1, Time: now, Strategy: ""}
	if signal.StrategyKey() != "unknown@min1" {
		t.Fatalf("strategy key = %q", signal.StrategyKey())
	}
	if signal.FamilyKey() != "unknown" {
		t.Fatalf("family key = %q", signal.FamilyKey())
	}
	if signal.Expired(now.Add(30 * time.Second)) {
		t.Fatal("signal expired too early")
	}
	if !signal.Expired(now.Add(2 * time.Minute)) {
		t.Fatal("signal should have expired")
	}
	if (Signal{}).Expired(now) {
		t.Fatal("zero-time signal should not expire")
	}
}

func TestSignalKeyIncludesTimeframeAndFamily(t *testing.T) {
	minute := Signal{Strategy: "rsi", Size: MIN1}
	fifteen := Signal{Strategy: "rsi", Size: MIN15}
	if minute.StrategyKey() == fifteen.StrategyKey() {
		t.Fatalf("same strategy on different timeframes collides: %q", minute.StrategyKey())
	}
	if minute.StrategyKey() != "rsi@min1" || fifteen.StrategyKey() != "rsi@min15" {
		t.Fatalf("unexpected keys: %q, %q", minute.StrategyKey(), fifteen.StrategyKey())
	}
	if got := (Signal{Strategy: "rsi", Family: "momentum"}).FamilyKey(); got != "momentum" {
		t.Fatalf("family key = %q", got)
	}
	if got := (Signal{Strategy: "rsi"}).FamilyKey(); got != "rsi" {
		t.Fatalf("fallback family key = %q", got)
	}
}

func TestSignalClamped(t *testing.T) {
	if got := (Signal{Confidence: -1}).Clamped().Confidence; got != 0 {
		t.Fatalf("negative clamp = %v", got)
	}
	if got := (Signal{Confidence: 2}).Clamped().Confidence; got != 1 {
		t.Fatalf("upper clamp = %v", got)
	}
	if got := (Signal{Confidence: 0.4}).Clamped().Confidence; got != 0.4 {
		t.Fatalf("identity clamp = %v", got)
	}
}
