package main

import (
	"context"
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"

	"gateway/app/config"
	"gateway/app/interface/producer/zerodha"
	"gateway/app/server"
	"gateway/app/universe"
)

func main() {
	log.SetFlags(log.LstdFlags | log.Lmicroseconds)

	cfg := config.Load()
	if handleKiteCLI(cfg, os.Args[1:]) {
		return
	}
	if handleUniverseCLI(cfg, os.Args[1:]) {
		return
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	resolved, err := universe.Resolve(ctx, universe.Options{
		Explicit: cfg.Symbols,
		Index:    cfg.Universe,
		URL:      cfg.UniverseURL,
		Limit:    cfg.UniverseLimit,
		Fallback: cfg.FallbackSymbols,
		Timeout:  cfg.HTTPTimeout,
	})
	if err != nil {
		log.Printf("[universe] %v; using %d fallback symbols", err, len(resolved.Symbols))
	}
	if len(resolved.Symbols) == 0 {
		log.Fatal("[universe] no symbols available; set TYCHE_SYMBOLS or TYCHE_UNIVERSE")
	}
	cfg.Symbols = resolved.Symbols
	cfg.UniverseSource = resolved.Source
	log.Printf("[universe] source=%s symbols=%d", resolved.Source, len(resolved.Symbols))

	srv, err := server.NewServer(ctx, cfg)
	if err != nil {
		log.Printf("gateway startup failed: %v", err)
		os.Exit(1)
	}
	if err := srv.BootServer(ctx); err != nil {
		log.Printf("gateway stopped with error: %v", err)
		os.Exit(1)
	}
}

// handleUniverseCLI exposes the same resolver used at startup so operators can
// inspect exactly which symbols an index resolves to before launching a run.
func handleUniverseCLI(cfg config.Config, args []string) bool {
	if len(args) == 0 || args[0] != "universe" {
		return false
	}

	index := cfg.Universe
	explicit := cfg.Symbols
	if len(args) > 1 {
		index = args[1]
		// An explicit index on the command line is a stronger signal than an
		// ambient TYCHE_SYMBOLS override.
		explicit = nil
	}
	if index == "" {
		index = universe.DefaultIndex
	}

	resolved, err := universe.Resolve(context.Background(), universe.Options{
		Explicit: explicit,
		Index:    index,
		URL:      cfg.UniverseURL,
		Limit:    cfg.UniverseLimit,
		Fallback: cfg.FallbackSymbols,
		Timeout:  cfg.HTTPTimeout,
	})
	if err != nil {
		fmt.Fprintf(os.Stderr, "universe: %v\n", err)
	}
	if len(resolved.Symbols) == 0 {
		fmt.Fprintln(os.Stderr, "usage: tyche-gateway universe [nifty50|nifty100|nifty200|nifty500|...]")
		os.Exit(1)
	}
	fmt.Fprintf(os.Stderr, "source=%s symbols=%d\n", resolved.Source, len(resolved.Symbols))
	for _, symbol := range resolved.Symbols {
		fmt.Println(symbol)
	}
	if err != nil {
		os.Exit(1)
	}
	return true
}

// handleKiteCLI keeps the one-time Kite auth flow in the Go data plane now
// that the Java core has no outbound market-data or broker access.
func handleKiteCLI(cfg config.Config, args []string) bool {
	if len(args) == 0 {
		return false
	}
	switch args[0] {
	case "kite-login-url":
		if cfg.KiteAPIKey == "" {
			fmt.Fprintln(os.Stderr, "TYCHE_KITE_API_KEY is required")
			os.Exit(1)
		}
		fmt.Println(zerodha.LoginURL(cfg.KiteAPIKey))
		return true
	case "kite-session":
		if len(args) < 2 || args[1] == "" {
			fmt.Fprintln(os.Stderr, "usage: tyche-gateway kite-session <request_token>")
			os.Exit(1)
		}
		if cfg.KiteAPIKey == "" || cfg.KiteAPISecret == "" {
			fmt.Fprintln(os.Stderr, "TYCHE_KITE_API_KEY and TYCHE_KITE_API_SECRET are required")
			os.Exit(1)
		}
		token, err := zerodha.ExchangeSession(
			cfg.KiteBaseURL, cfg.KiteAPIKey, cfg.KiteAPISecret, args[1], cfg.HTTPTimeout,
		)
		if err != nil {
			fmt.Fprintf(os.Stderr, "kite session failed: %v\n", err)
			os.Exit(1)
		}
		fmt.Println(token)
		return true
	default:
		return false
	}
}
