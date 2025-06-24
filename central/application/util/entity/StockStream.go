package entity
type StockStreamConfig struct{
	StartDate string
	EndDate string
	Tickers []string
	Interval string
}

type StockStream struct{
	Timestamp string
	StockValues []StockData
}
type StockData struct{
	High float32
	Low float32
	Open float32
	Close float32
	Volume float32
	Ticker string
}