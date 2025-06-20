package main

import (
	setup "central/config"
	"context"
	"fmt"
	"os"

	"github.com/spf13/viper"
	"go.uber.org/zap"
)

func main() {
	_ = context.Background()
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

	fmt.Println(viper.GetString("docker.redis.port"))
}
