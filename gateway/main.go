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
)

func main() {
	log.SetFlags(log.LstdFlags | log.Lmicroseconds)

	cfg := config.Load()
	if handleKiteCLI(cfg, os.Args[1:]) {
		return
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

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
