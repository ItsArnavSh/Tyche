package pyinterface

import (
	"context"
	"fmt"
	"io"

	"central/application/util/entity"
	pythonpb "central/proto"

	"go.uber.org/zap"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

type PyClient struct {
	logger zap.Logger
	client pythonpb.PythonUtilClient
	conn   *grpc.ClientConn
}

// NewPyClient creates and returns a new Python gRPC client
func NewPyClient(ctx context.Context, logger zap.Logger) (PyClient, error) {
	conn, err := grpc.NewClient(
		"passthrough:///localhost:50051",
		grpc.WithTransportCredentials(insecure.NewCredentials()),
	)
	if err != nil {

		return PyClient{}, fmt.Errorf("failed to dial Python gRPC server: %w", err)
	}
	logger.Info("Connected to GRPC Server")
	client := pythonpb.NewPythonUtilClient(conn)
	return PyClient{
		logger: logger,
		client: client,
		conn:   conn,
	}, nil
}

// Close closes the gRPC connection
func (p *PyClient) Close() error {
	return p.conn.Close()
}

// CallParseNewsArticle sends a URL and returns parsed article
func (p *PyClient) CallParseNewsArticle(ctx context.Context, url string) (*pythonpb.ParseNewsArticleResponse, error) {
	resp, err := p.client.ParseNewsArticle(ctx, &pythonpb.ParseNewsArticleRequest{Url: url})
	if err != nil {
		p.logger.Error("ParseNewsArticle RPC failed", zap.Error(err))
		return nil, err
	}
	return resp, nil
}

// CallGenerateEmbeddings sends content and gets float embeddings
func (p *PyClient) CallGenerateEmbeddings(ctx context.Context, content string) (*pythonpb.GenerateEmbeddingsResponse, error) {
	resp, err := p.client.GenerateEmbeddings(ctx, &pythonpb.GenerateEmbeddingsRequest{Content: content})
	if err != nil {
		p.logger.Error("GenerateEmbeddings RPC failed", zap.Error(err))
		return nil, err
	}
	return resp, nil
}

func (p *PyClient) CallGenerateKeywords(ctx context.Context, content string) ([]string, error) {
	resp, err := p.client.GenerateKeywords(ctx, &pythonpb.GenerateKeywordsRequest{Content: content})
	if err != nil {
		p.logger.Error("Keyword Generation failed", zap.Error(err))
		return nil, err
	}
	return resp.Keywords, nil
}

func (p *PyClient) StreamFromHistoricalData(ctx context.Context,config entity.StockStreamConfig,StockChan chan<- entity.StockStream )error{
	stream,err := p.client.StreamHistorical(ctx, &pythonpb.StreamHistoricalRequest{Startdate:config.StartDate,Enddate: config.EndDate,Tickers: config.Tickers})
	if err!=nil{
		p.logger.Error("Could not stream data", zap.Error(err))	
	return err
	}
	for {
		msg, err := stream.Recv()
		if err == io.EOF {
			close(StockChan)
			break
		}
		if err != nil {
			return fmt.Errorf("stream error: %w", err)
		}

		stocks := msg.GetStocks()
		// Preallocate slice for better perf
		stockSlice := make([]entity.StockData, 0, len(stocks))

		for _, stock := range stocks {
			stockSlice = append(stockSlice, entity.StockData{
				Ticker: stock.Ticker,
				Open:   stock.Open,
				Close:  stock.Close,
				Low:    stock.Low,
				High:   stock.High,
				Volume: stock.Volume,
			})
		}

		// Send to channel
		StockChan <- entity.StockStream{
			Timestamp: msg.Timestamp,
			StockValues:    stockSlice,
		}
}
	return nil
}