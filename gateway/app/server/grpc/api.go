package grpc

import (
	"context"
	"gateway/app/util/entity"
	pb "gateway/proto"
)

// All the api interface functions for the GRPC comm are set here

// SendBootRequest sends boot signal with historical data
func (g *GrpcClient) SendBootRequest(ctx context.Context, histories []entity.TickerHistory) error {
	param := &pb.SendBootSignalRequest{
		Hist: convertTickerHistoriesToProto(histories),
	}
	_, err := g.client.SendBootSignal(ctx, param)
	return err
}

// SendRollData sends rolling/latest stock updates
func (g *GrpcClient) SendRollData(ctx context.Context, updates []entity.LatestVal) ([]entity.MonoCandle, error) {
	param := &pb.SendRollDataRequest{
		Stock: convertLatestValsToProto(updates),
	}

	resp, err := g.client.SendRollData(ctx, param)
	if err != nil {
		return nil, err
	}

	return convertProtoToMonoCandles(resp.Missing), nil
}

// SendKillSignal sends kill signal for specific tickers
func (g *GrpcClient) SendKillSignal(ctx context.Context, tickers []string) error {
	param := &pb.SendKillSignalRequest{
		Name: tickers,
	}
	_, err := g.client.SendKillSignal(ctx, param)
	return err
}

// Conversion helpers: Entity -> Proto

func convertTickerHistoriesToProto(histories []entity.TickerHistory) []*pb.TickerHistory {
	result := make([]*pb.TickerHistory, len(histories))
	for i, hist := range histories {
		result[i] = &pb.TickerHistory{
			Name:   hist.Name,
			Series: []*pb.Series{convertCandleSeriesToProto(hist.History)},
		}
	}
	return result
}

func convertCandleSeriesToProto(series entity.CandleSeries) *pb.Series {
	return &pb.Series{
		Size: convertCandleSizeToProto(series.Size),
		Val:  convertCandleDataSliceToProto(series.Data),
	}
}

func convertCandleDataSliceToProto(candles []entity.CandleData) []*pb.StockValue {
	result := make([]*pb.StockValue, len(candles))
	for i, candle := range candles {
		result[i] = convertCandleDataToProto(candle)
	}
	return result
}

func convertCandleDataToProto(candle entity.CandleData) *pb.StockValue {
	return &pb.StockValue{
		Open:  float32(candle.Open),
		Close: float32(candle.Close),
		High:  float32(candle.High),
		Low:   float32(candle.Low),
	}
}

func convertLatestValsToProto(updates []entity.LatestVal) []*pb.StockUpdate {
	// Group by ticker name
	updateMap := make(map[string][]*pb.LatestStockVals)

	for _, update := range updates {
		ticker := update.Stock.Name
		latestVal := &pb.LatestStockVals{
			Size: convertCandleSizeToProto(update.Stock.Size),
			Val:  convertCandleDataToProto(update.Candle),
		}
		updateMap[ticker] = append(updateMap[ticker], latestVal)
	}

	// Convert map to slice
	result := make([]*pb.StockUpdate, 0, len(updateMap))
	for ticker, vals := range updateMap {
		result = append(result, &pb.StockUpdate{
			Name: ticker,
			Vals: vals,
		})
	}

	return result
}

func convertCandleSizeToProto(size entity.CandleSize) pb.CandleSize {
	switch size {
	case entity.SEC5:
		return pb.CandleSize_SEC5
	case entity.SEC30:
		return pb.CandleSize_SEC30
	case entity.MIN1:
		return pb.CandleSize_MIN1
	case entity.MIN15:
		return pb.CandleSize_MIN15
	case entity.HOUR1:
		return pb.CandleSize_HOUR1
	default:
		return pb.CandleSize_SEC5
	}
}

// Conversion helpers: Proto -> Entity

func convertProtoToMonoCandles(stockSeries []*pb.StockSeries) []entity.MonoCandle {
	result := make([]entity.MonoCandle, len(stockSeries))
	for i, series := range stockSeries {
		result[i] = entity.MonoCandle{
			Name: series.Name,
			Size: convertProtoToCandleSize(series.Size),
		}
	}
	return result
}

func convertProtoToCandleSize(size pb.CandleSize) entity.CandleSize {
	switch size {
	case pb.CandleSize_SEC5:
		return entity.SEC5
	case pb.CandleSize_SEC30:
		return entity.SEC30
	case pb.CandleSize_MIN1:
		return entity.MIN1
	case pb.CandleSize_MIN15:
		return entity.MIN15
	case pb.CandleSize_HOUR1:
		return entity.HOUR1
	default:
		return entity.SEC5
	}
}
