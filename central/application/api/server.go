package server

import (
	"central/application/services/newsparser"
	"central/application/util/entity"
	"central/application/util/pyinterface"
	rsinterface "central/application/util/rustinterface"
	database "central/database/gen"
	"context"
	"database/sql"
	"sync"

	"github.com/spf13/viper"
	"go.uber.org/zap"
)
type Server struct{
	logger zap.Logger
	pycli pyinterface.PyClient
	rscli rsinterface.RustClient
	queries *database.Queries
}

func NewServer(ctx context.Context,logger zap.Logger,db *sql.DB)(Server,error){
	
	queries := database.New(db)	
	pycli, err := pyinterface.NewPyClient(ctx, logger)
	if err != nil {
		logger.Error("Could not connect to python microservice", zap.Error(err))
		return Server{},err
	}
	_, err = pycli.CallGenerateKeywords(ctx, "GRPC TEST 1 2 3")
	if err != nil {
		logger.Error("GRPC is not working", zap.Error(err))
		return Server{},err
	}
	rscli,err := rsinterface.NewRustClient(ctx, logger)
	if err != nil {
			logger.Error("GRPC is not working", zap.Error(err))
			return Server{},err
		}
	return Server{logger:logger,pycli: pycli,rscli: rscli,queries: queries},nil

}

func (s *Server) StartServer(ctx context.Context) {
	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		s.NewsServer(ctx)
	}()
	go func() {
		defer wg.Done()
		s.StockStreamServer(ctx)
	}()
	wg.Wait() 
}
func (s *Server)StockStreamServer(ctx context.Context){
	
	StockStream := make(chan entity.StockStream)
	if viper.GetString("Mode")=="Backtesting"{	
		config := entity.StockStreamConfig{StartDate: viper.GetString("Backtesting.startdate"),EndDate: viper.GetString("Backtesting.enddate"),Tickers: viper.GetStringSlice("Backtesting.tickers")}
		go s.pycli.StreamFromHistoricalData(ctx, config, StockStream)
		s.rscli.SendToRust(ctx, StockStream)
	}
}
func (s *Server)NewsServer(ctx context.Context) {
	newsHandler, err := newsparser.NewNewsStruct(ctx, s.logger, s.pycli, s.queries)
	if err != nil {
		s.logger.Error("Could not initialize news handler", zap.Error(err))
		return
	}

	newsChan := make(chan entity.NewsChanEntry)

	// Optional: RSS feed polling
	// rssFeedURLs := viper.GetStringSlice("rssFeeds")
	// for _, url := range rssFeedURLs {
	//     go newsHandler.PollFeed(ctx, logger, url, newsChan)
	// }

	go newsHandler.PurgeNews(ctx)
	go newsHandler.PollSampleData(ctx, newsChan)
	newsHandler.NewsConsumer(ctx, newsChan)
}
