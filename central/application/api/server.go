package server

import (
	"central/application/services/newsparser"
	"central/application/util/entity"
	"central/application/util/pyinterface"
	database "central/database/gen"
	"context"
	"database/sql"

	"github.com/spf13/viper"
	"go.uber.org/zap"
)

func StartServer(ctx context.Context, logger zap.Logger, db *sql.DB) {
	rssFeedURLs := viper.GetStringSlice("rssFeeds")

	queries := database.New(db)
	NewsUrls := make(chan entity.NewsChanEntry)

	pycli, err := pyinterface.NewPyClient(ctx, logger)
	if err != nil {
		logger.Error("Could not connect to python microservice", zap.Error(err))
		return
	}
	newsHandler, err := newsparser.NewNewsStruct(ctx, logger, pycli, queries)
	for _, rssFeedUrl := range rssFeedURLs {

		go newsHandler.PollFeed(ctx, logger, rssFeedUrl, NewsUrls)
	}
	if err != nil {
		logger.Error("Could Not Connect to Python GRPC", zap.Error(err))
		return
	}
	newsHandler.NewsConsumer(ctx, NewsUrls)
}
