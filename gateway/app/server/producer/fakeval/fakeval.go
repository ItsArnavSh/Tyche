package fakeval

import (
	"gateway/app/util"
	"gateway/app/util/entity"
)

type FakeValGen struct {
	candleCount   int
	startPrice    float64
	currentPrices map[string]float64
}

func NewFakeValGen() *FakeValGen {
	return &FakeValGen{
		candleCount:   100,
		startPrice:    100.0,
		currentPrices: make(map[string]float64),
	}
}

func NewFakeValGenWithParams(candleCount int, startPrice float64) *FakeValGen {
	return &FakeValGen{
		candleCount:   candleCount,
		startPrice:    startPrice,
		currentPrices: make(map[string]float64),
	}
}

func (f *FakeValGen) BootRequest(stocks []entity.MonoCandle) []entity.TickerHistory {
	// Group stocks by ticker name
	tickerMap := make(map[string]map[entity.CandleSize]bool)
	for _, stock := range stocks {
		if tickerMap[stock.Name] == nil {
			tickerMap[stock.Name] = make(map[entity.CandleSize]bool)
		}
		tickerMap[stock.Name][stock.Size] = true
	}

	// Generate fake data for each ticker
	var result []entity.TickerHistory
	for ticker, sizes := range tickerMap {
		var allSeries []entity.CandleSeries
		price := f.startPrice

		for size := range sizes {
			series := entity.CandleSeries{
				Size: size,
			}
			price = f.startPrice // Reset for each series

			var candles []entity.CandleData
			for i := 0; i < f.candleCount; i++ {
				candle := util.CandleGen(price)
				candles = append(candles, candle)
				price = candle.Close
			}
			series.Data = candles
			allSeries = append(allSeries, series)

			// Store the last price for this ticker
			f.currentPrices[ticker] = price
		}

		for _, series := range allSeries {
			tickerHistory := entity.TickerHistory{
				Name:    ticker,
				History: series,
			}
			result = append(result, tickerHistory)
		}
	}

	return result
}

func (f *FakeValGen) RollRequest(stocks []entity.MonoCandle) []entity.LatestVal {
	// Group stocks by ticker name
	tickerMap := make(map[string]map[entity.CandleSize]bool)
	for _, stock := range stocks {
		if tickerMap[stock.Name] == nil {
			tickerMap[stock.Name] = make(map[entity.CandleSize]bool)
		}
		tickerMap[stock.Name][stock.Size] = true
	}

	// Generate latest values for each ticker
	var result []entity.LatestVal
	for ticker, sizes := range tickerMap {
		// Get or initialize current price for this ticker
		currentPrice, exists := f.currentPrices[ticker]
		if !exists {
			currentPrice = f.startPrice
		}

		var firstCandle entity.CandleData
		firstIteration := true

		for size := range sizes {
			candle := util.CandleGen(currentPrice)
			if firstIteration {
				firstCandle = candle
				firstIteration = false
			}

			latestVal := entity.LatestVal{
				Stock: entity.MonoCandle{
					Name: ticker,
					Size: size,
				},
				Candle: candle,
			}
			result = append(result, latestVal)
		}

		// Update stored price using the first candle's close price
		if !firstIteration {
			f.currentPrices[ticker] = firstCandle.Close
		}
	}

	return result
}
