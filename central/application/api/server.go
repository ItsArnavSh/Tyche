package server

import (
	"central/application/services/newsparser"
	"central/application/util/entity"
	"central/application/util/pyinterface"
	"context"
	"database/sql"

	"github.com/spf13/viper"
	"go.uber.org/zap"
)

func StartServer(ctx context.Context, logger zap.Logger, database *sql.DB) {
	rssFeedURLs := viper.GetStringSlice("rssFeeds")
	NewsUrls := make(chan entity.NewsChanEntry)
	for _, rssFeedUrl := range rssFeedURLs {

		go newsparser.PollFeed(ctx, logger, rssFeedUrl, NewsUrls)
	}
	pycli, err := pyinterface.NewPyClient(ctx, &logger)
	if err != nil {
		logger.Error("Could Not Connect to Python GRPC", zap.Error(err))
		return
	}
	newsparser.NewsConsumer(ctx, NewsUrls, logger, pycli)
}
