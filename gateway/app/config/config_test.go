package config

import "testing"

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
