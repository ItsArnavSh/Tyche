package main

import (
	server "central/application/api"
	setup "central/config"
	"central/database"
	"context"
	"fmt"
	"os"

	"go.uber.org/zap"
)

func main() {
	ctx := context.Background()
	logger, _ := zap.NewProduction()
	defer func() {
		err := logger.Sync()
		if err != nil {
			fmt.Fprintf(os.Stderr, "logger sync error: %v\n", err)
		}
	}()
	err := setup.LoadConfig(logger)
	if err != nil {
		logger.Error("Conifg Failed to Load", zap.Error(err))
	}
	db, _ := databasehandler.DbConnection()
	logger.Info("Connected to database")
	serv,_:=server.NewServer(ctx, *logger, db)
	serv.StartServer(ctx)	
}
