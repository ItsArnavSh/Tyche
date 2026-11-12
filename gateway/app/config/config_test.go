package config

import (
	"testing"
	"time"
)

func TestUniverseDefaultsByDataSource(t *testing.T) {
	t.Setenv("TYCHE_UNIVERSE", "")
	t.Setenv("TYCHE_SYMBOLS", "")

	t.Setenv("TYCHE_DATA_SOURCE", "yahoo")
	cfg := Load()
	if cfg.Universe != DefaultUniverse {
		t.Fatalf("yahoo universe = %q, want %q", cfg.Universe, DefaultUniverse)
	}
	if len(cfg.FallbackSymbols) == 0 {
		t.Fatal("fallback symbols must be populated")
	}
	if cfg.BootWorkers < 1 {
		t.Fatalf("boot workers = %d", cfg.BootWorkers)
	}

	t.Setenv("TYCHE_DATA_SOURCE", "fake")
	cfg = Load()
	if cfg.Universe != "" {
		t.Fatalf("fake universe = %q, want empty (offline fallback)", cfg.Universe)
	}
	if len(cfg.Symbols) != 0 {
		t.Fatalf("explicit symbols = %v, want empty by default", cfg.Symbols)
	}
}

func TestExplicitSymbolsAndLimits(t *testing.T) {
	t.Setenv("TYCHE_DATA_SOURCE", "fake")
	t.Setenv("TYCHE_SYMBOLS", "A.NS, B.NS, a.ns")
	t.Setenv("TYCHE_UNIVERSE_LIMIT", "-5")
	t.Setenv("TYCHE_BOOT_WORKERS", "0")

	cfg := Load()
	if len(cfg.Symbols) != 2 || cfg.Symbols[0] != "A.NS" || cfg.Symbols[1] != "B.NS" {
		t.Fatalf("symbols = %v", cfg.Symbols)
	}
	if cfg.UniverseLimit != 0 {
		t.Fatalf("universe limit = %d, want 0", cfg.UniverseLimit)
	}
	if cfg.BootWorkers != 1 {
		t.Fatalf("boot workers = %d, want 1", cfg.BootWorkers)
	}
}

func TestDecisionDefaultsAndOverrides(t *testing.T) {
	t.Setenv("TYCHE_DATA_SOURCE", "fake")
	t.Setenv("TYCHE_SYMBOLS", "A.NS")
	t.Setenv("TYCHE_MAX_POSITIONS", "")
	t.Setenv("TYCHE_MAX_POSITION_FRACTION", "")
	t.Setenv("TYCHE_CASH_RESERVE_PCT", "")
	t.Setenv("TYCHE_HOLD_MS", "")
	t.Setenv("TYCHE_SIGNAL_LOSS_GRACE_MS", "")

	cfg := Load()
	if cfg.Decision.MaxPositions != 10 {
		t.Fatalf("max positions = %d, want 10", cfg.Decision.MaxPositions)
	}
	if cfg.Decision.CashReserveFraction != 0.02 {
		t.Fatalf("cash reserve = %v, want 0.02", cfg.Decision.CashReserveFraction)
	}
	if cfg.Decision.HoldDuration != 4*time.Hour {
		t.Fatalf("hold duration = %v, want 4h", cfg.Decision.HoldDuration)
	}
	if cfg.Decision.SignalLossGrace != 5*time.Minute {
		t.Fatalf("signal loss grace = %v, want 5m", cfg.Decision.SignalLossGrace)
	}

	t.Setenv("TYCHE_MAX_POSITIONS", "7")
	t.Setenv("TYCHE_MAX_POSITION_FRACTION", "0.12")
	t.Setenv("TYCHE_CASH_RESERVE_PCT", "0.05")
	t.Setenv("TYCHE_HOLD_MS", "2h")
	t.Setenv("TYCHE_SIGNAL_LOSS_GRACE_MS", "90s")

	cfg = Load()
	if cfg.Decision.MaxPositions != 7 || cfg.Decision.MaxPositionFraction != 0.12 {
		t.Fatalf("position settings = %+v", cfg.Decision)
	}
	if cfg.Decision.CashReserveFraction != 0.05 {
		t.Fatalf("cash reserve = %v", cfg.Decision.CashReserveFraction)
	}
	if cfg.Decision.HoldDuration != 2*time.Hour {
		t.Fatalf("hold duration = %v", cfg.Decision.HoldDuration)
	}
	if cfg.Decision.SignalLossGrace != 90*time.Second {
		t.Fatalf("signal loss grace = %v", cfg.Decision.SignalLossGrace)
	}
}
