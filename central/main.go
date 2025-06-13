package main

import (
	setup "central/config"
	"context"
	"fmt"

	"github.com/spf13/viper"
	"go.uber.org/zap"
)

func main() {
	_ = context.Background()
	logger, _ := zap.NewProduction()
	defer logger.Sync()
	setup.LoadConfig(logger)
	fmt.Println(viper.GetString("docker.redis.port"))
}
