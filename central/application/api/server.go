package server

import (
	"central/application/services/newsparser"
	"central/application/util/entity"
	"context"

	"github.com/spf13/viper"
	"go.uber.org/zap"
)

func StartServer(ctx context.Context, logger zap.Logger) {
	rssFeedURLs := viper.GetStringSlice("rssFeeds")
	NewsUrls := make(chan entity.NewsChanEntry)
	for _, rssFeedUrl := range rssFeedURLs {

		go newsparser.PollFeed(ctx, logger, rssFeedUrl, NewsUrls)
	}

	newsparser.NewsConsumer(ctx, NewsUrls, logger)
}
