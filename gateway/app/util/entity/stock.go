package entity

type CandleSize int

type TickerHistory struct {
	Name    string
	History CandleSeries
}
type MonoCandle struct {
	Name string
	Size CandleSize
}
type CandleSeries struct {
	Size CandleSize
	Data []CandleData
}
type CandleData struct {
	High   float64
	Low    float64
	Open   float64
	Close  float64
	Volume int64
}
type LatestVal struct {
	Stock  MonoCandle
	Candle CandleData
}
