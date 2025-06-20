package newsparser

import (
	"central/application/util/entity"
	"central/application/util/pyinterface"
	"context"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/mmcdole/gofeed/rss"
	"go.uber.org/zap"
)

func PollFeed(ctx context.Context, logger zap.Logger, url string, resChan chan<- entity.NewsChanEntry) {
	fp := rss.Parser{}
	ticker := time.NewTicker(time.Minute)
	defer ticker.Stop()
	seen := make(map[string]struct{})
	for {
		resp, err := http.Get(url)
		if err != nil {
			logger.Error("Invalid RSS URL", zap.Error(err))
		}
		body, err := io.ReadAll(resp.Body)
		if err != nil {
			logger.Error("Could not load RSS body", zap.Error(err))
		}
		err = resp.Body.Close()
		if err != nil {
			logger.Error("Could not close the RSS body", zap.Error(err))
		}
		rssFeed, err := fp.Parse(strings.NewReader(string(body)))
		if err != nil {
			logger.Error("Unable to construct RSS object from the body", zap.Error(err))
		}
		newSeen := make(map[string]struct{})
		items := rssFeed.Items
		for i := len(items) - 1; i >= 0; i-- {
			item := items[i]
			newSeen[item.Link] = struct{}{}
			if _, alredySeen := seen[item.Link]; alredySeen {
				continue
			}
			newsEntry := entity.NewsChanEntry{
				Title:   item.Title,
				Url:     item.Link,
				PubDate: item.PubDate,
			}

			resChan <- newsEntry
		}
		seen = newSeen
		<-ticker.C
	}
}

func NewsConsumer(ctx context.Context, newChannel <-chan entity.NewsChanEntry, logger zap.Logger, pycli *pyinterface.PyClient) {
	for {
		select {
		case <-ctx.Done():
			logger.Info("News consumer shutting down")
			return

		case news := <-newChannel:
			//article, _ := pycli.CallParseNewsArticle(ctx, news.Url)
			//logger.Info(article.Content)
			logger.Info(news.PubDate)

		}
	}
}
