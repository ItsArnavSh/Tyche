package setup

import (
	"github.com/spf13/viper"
	"go.uber.org/zap"
)

func LoadConfig(logger *zap.Logger) error {
	viper.SetConfigName("config")
	viper.SetConfigType("yaml")
	viper.AddConfigPath("./config")
	err := viper.ReadInConfig()
	if err != nil {
		logger.Error("Error reading config", zap.Error(err))
	}
	return nil
}
