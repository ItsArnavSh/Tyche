package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"

	"gateway/app/config"
	"gateway/app/server"
)

func main() {
	log.SetFlags(log.LstdFlags | log.Lmicroseconds)
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	cfg := config.Load()
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
