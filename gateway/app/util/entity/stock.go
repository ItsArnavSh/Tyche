package entity

type CandleSize int

type TickerHistory struct {
	Name    string
	history CandleSeries
}
type MonoCandle struct {
	Name string
	Size CandleSize
}
type CandleSeries struct {
	Size CandleSize
	data []CandleData
}
type CandleData struct {
	High   float64
	Low    float64
	Open   float64
	Close  float64
	Volume float64
}
