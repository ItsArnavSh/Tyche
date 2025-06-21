package newsparser

import (
	"central/application/util/entity"
	"central/application/util/pyinterface"
	database "central/database/gen"
	"context"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/mmcdole/gofeed/rss"
	"go.uber.org/zap"
)

type NewsStruct struct {
	db       *database.Queries
	logger   zap.Logger
	pyclient pyinterface.PyClient
}

func NewNewsStruct(ctx context.Context, logger zap.Logger, pyclient pyinterface.PyClient, db *database.Queries) (NewsStruct, error) {
	return NewsStruct{db: db, logger: logger, pyclient: pyclient}, nil
}
func (n *NewsStruct) PollSampleData(ctx context.Context, resChan chan<- entity.NewsChanEntry) {

	headlines := []string{
		// 🌀 Earthquake News
		"Massive Earthquake Strikes Southern Japan, Thousands Displaced",
		"Southern Japan Hit by Powerful Quake, Residents Forced to Evacuate",
		"Quake Shakes Japan’s South, Triggers Widespread Evacuations",
		"Thousands Evacuated After 7.8 Magnitude Earthquake Rocks Japan",

		// 🧠 GPT-5 Launch
		"OpenAI Releases GPT-5, Capable of Multi-Modal Real-Time Reasoning",
		"GPT-5 Debuts with Support for Text, Images, and Video",
		"OpenAI’s GPT-5 Promises Real-Time Multimodal Intelligence",

		// 🪐 NASA Discovery
		"NASA Finds Earth-Like Planet Orbiting in Habitable Zone",
		"New Exoplanet Discovered by NASA May Be Capable of Supporting Life",
		"NASA Detects Potentially Habitable Earth Twin Outside Solar System",

		// 🚀 Indian Hypersonic Missile
		"India Successfully Tests Hypersonic Missile Prototype",
		"India Joins Hypersonic Missile Race With Successful Test Launch",

		// 📢 Random Feed Ads or Noise
		"Get 20% Off on All Winter Jackets — Limited Time Offer!",
		"Here’s What Your Coffee Says About Your Personality",
		"Top 10 Anime You Should Watch Before the End of 2025",
		"New Study Reveals How Blue Light Affects Your Sleep Cycle",
	}
	for _, news := range headlines {
		resChan <- entity.NewsChanEntry{Title: news}
	}
}
func (n *NewsStruct) PollFeed(ctx context.Context, logger zap.Logger, url string, resChan chan<- entity.NewsChanEntry) {
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

func (n *NewsStruct) NewsConsumer(ctx context.Context, newChannel <-chan entity.NewsChanEntry) {
	for {
		select {
		case <-ctx.Done():
			n.logger.Info("News consumer shutting down")
			return

		case news := <-newChannel:

			res, err := n.IsMajorNews(ctx, news)
			if err != nil {
				n.logger.Error("Could not check for Major News", zap.Error(err))
			}
			if res {
				n.logger.Info(news.Title + " YES")
			} else {
				n.logger.Info(news.Title + " NO")
			}
		}
	}
}
